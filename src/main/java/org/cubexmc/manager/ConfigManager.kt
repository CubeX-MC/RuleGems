package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.cubexmc.RuleGems
import org.cubexmc.core.Reloadable
import org.cubexmc.model.AllowedCommand
import org.cubexmc.model.AppointDefinition
import org.cubexmc.model.PowerStructure
import org.cubexmc.storage.SqliteStorageProvider
import org.cubexmc.storage.StorageException
import org.cubexmc.storage.StorageLoadResult
import org.cubexmc.storage.StorageProvider
import org.cubexmc.storage.StorageSaveResult
import org.cubexmc.storage.YamlStorageProvider
import org.cubexmc.update.BackupHelper
import org.cubexmc.update.ConfigUpdater
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Locale
import org.bukkit.configuration.file.YamlConfiguration

/**
 * ConfigManager — 配置协调器。
 *
 * 负责文件 I/O、配置加载编排、以及跨域查询方法。
 * 解析逻辑委托给 [GemDefinitionParser]，
 * 运行时游戏设置存储在 [GameplayConfig]。
 */
class ConfigManager(
    private val plugin: RuleGems,
    private val languageManager: LanguageManager,
) : Reloadable {
    var config: FileConfiguration? = null
        private set
    private var gemsData: FileConfiguration? = null
    var language: String? = null
        private set
    private var storageProvider: StorageProvider? = null
    private var preparedGemData: StorageLoadResult? = null

    // 内部委托对象
    /** 宝石定义解析器 */
    val gemParser = GemDefinitionParser(plugin.logger, languageManager)

    /** 运行时游戏玩法配置 */
    val gameplayConfig = GameplayConfig()

    // ==================== 加载 / 重载 ====================

    override fun reload() = loadConfigs()

    fun loadConfigs() {
        plugin.saveDefaultConfig()
        ConfigUpdater.merge(plugin)
        val candidate = YamlConfiguration().apply { load(File(plugin.dataFolder, "config.yml")) }
        ensurePowersFolder()
        initGemsFolder()
        validateYamlInputs(candidate)
        val parser = GemDefinitionParser(plugin.logger, languageManager)
        parser.loadPowerTemplates(plugin.dataFolder)
        val range = requireNotNull(candidate.getConfigurationSection("random_place_range")) {
            "Missing random_place_range"
        }
        val worldName = requireNotNull(range.getString("world")) { "Missing random_place_range.world" }
        val world = requireNotNull(Bukkit.getWorld(worldName)) { "Unknown random placement world: $worldName" }
        requireNotNull(getLocationFromConfig(range, "corner1", world)) { "Invalid random_place_range.corner1" }
        requireNotNull(getLocationFromConfig(range, "corner2", world)) { "Invalid random_place_range.corner2" }
        parser.loadGemDefinitions(candidate, plugin.dataFolder)

        val provider = createStorageProvider(candidate)
        val loaded = provider.readGemData()
        check(loaded.isUsable) { "Gem storage is unavailable: " + loaded.error?.message }
        val validation = GemDataValidator.validate(requireNotNull(loaded.data), parser.gemDefinitions)
        check(validation.valid) { "Invalid gem data: " + validation.errors.joinToString("; ") }
        // Validate the full snapshot before publishing any new config/parser/provider.
        GemDataSnapshot.capture(requireNotNull(loaded.data)).materialize()
        // loadFrom publishes global effect timing, so run it only after all input/storage validation.
        val gameplay = GameplayConfig()
        gameplay.loadFrom(candidate, parser, languageManager, plugin.logger) { section, path, loadedWorld ->
            getLocationFromConfig(section, path, loadedWorld)
        }
        config = candidate
        language = candidate.getString("language", "zh_CN")
        storageProvider = provider
        preparedGemData = loaded
        gemParser.copyFrom(parser)
        gameplayConfig.copyFrom(gameplay)
        plugin.reloadConfig()
        backupLegacyConfigIfNeeded()
    }

    fun reloadConfigs() {
        loadConfigs()
    }

    // ==================== 数据文件 I/O ====================

    fun initGemFile() {
        getStorageProvider().initialize()
    }

    fun saveGemData(data: FileConfiguration): StorageSaveResult = getStorageProvider().saveGemData(data)

    /**
     * Writes a provider-independent recovery file without touching the primary
     * YAML or SQLite store. This is the final fallback for synchronous shutdown
     * and reload saves.
     */
    fun saveEmergencyGemData(data: FileConfiguration): File {
        val recoveryFolder = File(plugin.dataFolder, "data/recovery")
        if (!recoveryFolder.exists() && !recoveryFolder.mkdirs()) {
            throw StorageException("Could not create emergency recovery directory: $recoveryFolder")
        }
        val target = File(recoveryFolder, "gems-emergency-${Instant.now().toEpochMilli()}.yml")
        val temp = File.createTempFile("gems-emergency-", ".tmp", recoveryFolder)
        try {
            data.save(temp)
            YamlConfiguration().load(temp)
            FileChannel.open(temp.toPath(), StandardOpenOption.WRITE).use { channel -> channel.force(true) }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath())
            }
            return target
        } catch (failure: Exception) {
            Files.deleteIfExists(temp.toPath())
            throw StorageException("Failed to write emergency recovery snapshot", failure)
        }
    }

    fun readGemsData(): StorageLoadResult {
        val result = preparedGemData ?: getStorageProvider().readGemData()
        preparedGemData = null
        if (result.isUsable) {
            gemsData = result.data
        }
        return result
    }

    fun getGemsData(): FileConfiguration {
        val cached = gemsData
        if (cached != null) {
            return cached
        }
        val result = readGemsData()
        return result.data
            ?: throw StorageException("Gem data is unavailable because the storage read failed", result.error)
    }

    fun getStorageProvider(): StorageProvider {
        if (storageProvider == null) {
            initStorageProvider()
        }
        return storageProvider ?: YamlStorageProvider(plugin).also { storageProvider = it }
    }

    // ==================== 跨域查询 ====================

    /**
     * 收集所有已配置的 allowed-command label（供 proxy 注册）。
     * 需要访问 gemParser（宝石定义）和 gameplayConfig（redeem_all power）。
     */
    fun collectAllowedCommandLabels(): Set<String> {
        val labels = LinkedHashSet<String>()
        val definitions = gemParser.gemDefinitions
        if (definitions != null) {
            for (definition in definitions) {
                if (definition == null) {
                    continue
                }
                collectAllowedLabelsFromPower(definition.powerStructure, labels)
            }
        }
        val redeemAllPower = gameplayConfig.redeemAllPowerStructure
        collectAllowedLabelsFromPower(redeemAllPower, labels)
        return labels
    }

    private fun collectAllowedLabelsFromPower(power: PowerStructure?, labels: MutableSet<String>?) {
        if (power == null || labels == null) {
            return
        }
        for (command: AllowedCommand? in power.allowedCommands) {
            if (command == null) {
                continue
            }
            val label = command.label
            if (label.isNotEmpty()) {
                labels.add(label.lowercase(Locale.ROOT))
            }
        }
        if (power.appoints.isEmpty()) {
            return
        }
        for (appoint: AppointDefinition? in power.appoints.values) {
            if (appoint != null) {
                collectAllowedLabelsFromPower(appoint.powerStructure, labels)
            }
        }
    }

    // ==================== 内部辅助 ====================

    private fun validateYamlInputs(candidate: FileConfiguration) {
        for (directory in listOf("powers", "gems", "features")) {
            File(plugin.dataFolder, directory).walkTopDown()
                .onFail { _, failure -> throw failure }
                .filter { it.isFile && it.name.endsWith(".yml", ignoreCase = true) }
                .forEach { YamlConfiguration().load(it) }
        }
        val appointmentData = File(plugin.dataFolder, "data/appoints.yml")
            .takeIf { it.exists() } ?: File(plugin.dataFolder, "features/appoint_data.yml")
        if (appointmentData.exists()) YamlConfiguration().load(appointmentData)
        for (fileName in listOf("revokes.yml", "transfer-operations.yml")) {
            val file = File(plugin.dataFolder, "data/$fileName")
            if (file.exists()) YamlConfiguration().load(file)
        }
        val selected = candidate.getString("language", "zh_CN").orEmpty()
            .takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: "zh_CN"
        for (locale in setOf(selected, "en_US", "zh_CN")) {
            val file = File(plugin.dataFolder, "lang/$locale.yml")
            if (file.exists()) YamlConfiguration().load(file)
        }
    }

    private fun initGemsFolder() {
        val gemsFolder = File(plugin.dataFolder, "gems")
        if (!gemsFolder.exists()) {
            gemsFolder.mkdirs()
            plugin.logger.info("Creating gems folder")
            try {
                plugin.saveResource("gems/gems.yml", false)
                plugin.logger.info("Creating default gem config file: gems/gems.yml")
            } catch (e: Exception) {
                plugin.logger.warning("Failed to copy default gems.yml file: " + e.message)
            }
        }
    }

    private fun initStorageProvider() {
        storageProvider = createStorageProvider(config)
    }

    private fun createStorageProvider(candidate: FileConfiguration?): StorageProvider {
        val type = candidate?.getString("storage.type", "yaml").orEmpty().ifBlank { "yaml" }
        if ("sqlite".equals(type, ignoreCase = true)) return SqliteStorageProvider(plugin, candidate)
        if (!"yaml".equals(type, ignoreCase = true)) {
            plugin.logger.warning("storage.type '$type' is not supported. Falling back to YAML storage.")
        }
        return YamlStorageProvider(plugin)
    }

    private fun ensurePowersFolder() {
        val powersFolder = File(plugin.dataFolder, "powers")
        if (!powersFolder.exists()) {
            powersFolder.mkdirs()
            plugin.saveResource("powers/powers.yml", false)
        }
    }

    private fun backupLegacyConfigIfNeeded() {
        val loadedConfig = config ?: return
        val findings = gemParser.detectLegacySyntax(loadedConfig, plugin.dataFolder)
        if (findings.isEmpty()) {
            return
        }
        val backupDir = BackupHelper.createConfigOptimizationBackup(plugin)
        plugin.logger.warning(
            "Detected legacy RuleGems configuration syntax: " +
                findings.joinToString(", ") +
                ". Backup directory: " +
                (backupDir?.absolutePath ?: "backup failed") +
                ". Please migrate to power.base, power.permission_groups, and recipe-style redeem_requirements; " +
                "future version may remove this compatibility.",
        )
    }

    private fun getLocationFromConfig(configSection: ConfigurationSection, path: String, world: World): Location? {
        val locSection = configSection.getConfigurationSection(path)
        if (locSection == null) {
            plugin.logger.severe("Missing section '$path' in configuration.")
            return null
        }
        val x = locSection.getDouble("x")
        val y = locSection.getDouble("y")
        val z = locSection.getDouble("z")
        return Location(world, x, y, z)
    }
}
