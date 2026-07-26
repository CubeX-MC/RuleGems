package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.cubexmc.RuleGems
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
) {
    var config: FileConfiguration? = null
        private set
    private var gemsData: FileConfiguration? = null
    var language: String? = null
        private set
    private var storageProvider: StorageProvider? = null

    // 内部委托对象
    /** 宝石定义解析器 */
    val gemParser = GemDefinitionParser(plugin.logger, languageManager)

    /** 运行时游戏玩法配置 */
    val gameplayConfig = GameplayConfig()

    // ==================== 加载 / 重载 ====================

    fun loadConfigs() {
        plugin.saveDefaultConfig()
        ConfigUpdater.merge(plugin)
        plugin.reloadConfig()
        config = plugin.config
        initStorageProvider()

        // 确保默认资源存在
        ensurePowersFolder()
        initGemsFolder()
        backupLegacyConfigIfNeeded()

        val loadedConfig = config ?: return
        language = loadedConfig.getString("language", "zh_CN")

        // 1) 加载权力结构模板
        gemParser.loadPowerTemplates(plugin.dataFolder)

        // 2) 校验全局随机放置范围（提前退出逻辑保留，与旧版行为一致）
        val randomPlaceRange = loadedConfig.getConfigurationSection("random_place_range")
        if (randomPlaceRange == null) {
            languageManager.logMessage("config.missing_random_place")
            return
        }
        val worldName = randomPlaceRange.getString("world")
        if (worldName == null) {
            languageManager.logMessage("config.missing_world_name")
            return
        }
        val world = Bukkit.getWorld(worldName)
        if (world == null) {
            val placeholders = HashMap<String, String>()
            placeholders["world"] = worldName
            languageManager.logMessage("config.world_not_found", placeholders)
            return
        }
        val corner1 = getLocationFromConfig(randomPlaceRange, "corner1", world)
        val corner2 = getLocationFromConfig(randomPlaceRange, "corner2", world)
        if (corner1 == null || corner2 == null) {
            languageManager.logMessage("config.invalid_corners")
            return
        }

        // 3) 加载宝石定义
        gemParser.loadGemDefinitions(loadedConfig, plugin.dataFolder)

        // 4) 加载游戏玩法配置（GameplayConfig 原地刷新）
        gameplayConfig.loadFrom(
            loadedConfig,
            gemParser,
            languageManager,
            plugin.logger,
        ) { section, path, loadedWorld -> getLocationFromConfig(section, path, loadedWorld) }
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
        val result = getStorageProvider().readGemData()
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
        var type = config?.getString("storage.type", "yaml") ?: "yaml"
        if (type.isBlank()) {
            type = "yaml"
        }
        if ("sqlite".equals(type, ignoreCase = true)) {
            storageProvider = SqliteStorageProvider(plugin, config)
            return
        }
        if (!"yaml".equals(type, ignoreCase = true)) {
            plugin.logger.warning("storage.type '$type' is not supported. Falling back to YAML storage.")
        }
        storageProvider = YamlStorageProvider(plugin)
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
