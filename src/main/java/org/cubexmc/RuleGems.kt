package org.cubexmc

import net.milkbowl.vault.permission.Permission
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.SimpleCommandMap
import org.cubexmc.commands.AllowedCommandProxy
import org.cubexmc.commands.CloudCommandManager
import org.cubexmc.config.LegacyTextToMiniMessageStep
import org.cubexmc.config.MigrationException
import org.cubexmc.config.MigrationPlan
import org.cubexmc.config.MigrationRunner
import org.cubexmc.config.NoOpMigrationStep
import org.cubexmc.config.ResourceFiles
import org.cubexmc.core.CubexPlugin
import org.cubexmc.features.FeatureManager
import org.cubexmc.features.appoint.AppointFeature
import org.cubexmc.gui.GUIManager
import org.cubexmc.listeners.CommandAllowanceListener
import org.cubexmc.listeners.GemConsumeListener
import org.cubexmc.listeners.GemInventoryListener
import org.cubexmc.listeners.GemPlaceListener
import org.cubexmc.listeners.PlayerEventListener
import org.cubexmc.listeners.WorldLoadListener
import org.cubexmc.manager.ConfigManager
import org.cubexmc.manager.CustomCommandExecutor
import org.cubexmc.manager.GameplayConfig
import org.cubexmc.manager.GemDefinitionParser
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.HistoryLogger
import org.cubexmc.manager.LanguageManager
import org.cubexmc.manager.PowerStructureManager
import org.cubexmc.manager.RuleGemsDoctor
import org.cubexmc.metrics.Metrics
import org.cubexmc.model.AppointDefinition
import org.cubexmc.model.PowerStructure
import org.cubexmc.provider.BukkitPermissionProvider
import org.cubexmc.provider.LuckPermsPermissionProvider
import org.cubexmc.provider.PermissionProvider
import org.cubexmc.provider.VaultPermissionProvider
import org.cubexmc.utils.EffectUtils
import org.cubexmc.utils.SchedulerUtil
import java.lang.reflect.Field
import java.util.Collections
import java.util.Locale
import java.util.logging.Level

class RuleGems : CubexPlugin() {
    lateinit var configManager: ConfigManager
        private set
    lateinit var gemParser: GemDefinitionParser
        private set
    lateinit var gameplayConfig: GameplayConfig
        private set
    lateinit var gemManager: GemManager
        private set
    lateinit var effectUtils: EffectUtils
        private set
    lateinit var languageManager: LanguageManager
        private set
    lateinit var historyLogger: HistoryLogger
        private set
    lateinit var customCommandExecutor: CustomCommandExecutor
        private set
    lateinit var guiManager: GUIManager
        private set
    lateinit var featureManager: FeatureManager
        private set
    lateinit var powerStructureManager: PowerStructureManager
        private set
    var permissionProvider: PermissionProvider? = null
        private set

    @Suppress("unused")
    private var metrics: Metrics? = null
    private var commandAllowanceListener: CommandAllowanceListener? = null
    private var gemConsumeListener: GemConsumeListener? = null
    private val proxyCommands: MutableMap<String, AllowedCommandProxy> = HashMap()
    private var cachedCommandMap: CommandMap? = null

    override fun enablePlugin() {
        languageManager = LanguageManager(this)
        configManager = ConfigManager(this, languageManager)
        gemParser = configManager.gemParser
        gameplayConfig = configManager.gameplayConfig
        effectUtils = EffectUtils(this)
        powerStructureManager = PowerStructureManager(this)
        historyLogger = HistoryLogger(this, languageManager)
        customCommandExecutor = CustomCommandExecutor(this, languageManager, gameplayConfig)
        gemManager = GemManager(this, configManager, gemParser, gameplayConfig, effectUtils, languageManager)
        gemManager.setHistoryLogger(historyLogger)
        guiManager = GUIManager(this, gemManager, languageManager)

        metrics = Metrics(this, 27483)
        loadPlugin()

        val currentGemManager = gemManager
        val currentGameplayConfig = gameplayConfig
        val currentLanguageManager = languageManager
        val currentGuiManager = guiManager

        CloudCommandManager(this, currentGemManager, currentGameplayConfig, currentLanguageManager, currentGuiManager).registerAll()
        Bukkit.getPluginManager().registerEvents(GemPlaceListener(currentGemManager), this)
        Bukkit.getPluginManager().registerEvents(GemInventoryListener(currentGemManager, currentLanguageManager), this)
        Bukkit.getPluginManager().registerEvents(PlayerEventListener(this, currentGemManager), this)
        Bukkit.getPluginManager().registerEvents(WorldLoadListener(currentGemManager), this)

        val consumeListener = GemConsumeListener(this, currentGemManager, currentGameplayConfig, currentLanguageManager)
        gemConsumeListener = consumeListener
        if (currentGameplayConfig.isHoldToRedeemEnabled) {
            Bukkit.getPluginManager().registerEvents(consumeListener, this)
        }

        val allowanceListener = CommandAllowanceListener(
            currentGemManager.allowanceManager,
            currentLanguageManager,
            customCommandExecutor,
            currentGameplayConfig,
        )
        commandAllowanceListener = allowanceListener
        Bukkit.getPluginManager().registerEvents(allowanceListener, this)

        if (currentGameplayConfig.isOpEscalationAllowed) {
            logger.warning("========================================")
            logger.warning("allow_op_escalation is ENABLED!")
            logger.warning("This temporarily grants OP to players when executing allowed commands.")
            logger.warning("This is a security risk. Consider using 'console:' executor prefix instead.")
            logger.warning("========================================")
        }

        featureManager = FeatureManager(this, currentGemManager)
        featureManager.registerFeatures()
        configureAllowanceSourceLookups()

        val ruleGateFeature = featureManager.ruleGateFeature
        if (ruleGateFeature != null) {
            powerStructureManager.setRuleGateFeature(ruleGateFeature)
            currentGemManager.allowanceManager.setRuleGateFeature(ruleGateFeature)
        }
        RuleGemsDoctor(this).logWarnings()

        initializePermissionProvider()

        SchedulerUtil.globalRun(
            this,
            { currentGemManager.checkPlayersNearRuleGems() },
            PROXIMITY_CHECK_INTERVAL,
            PROXIMITY_CHECK_INTERVAL,
        )

        currentGemManager.startParticleEffectTask(Particle.FLAME)

        SchedulerUtil.globalRun(
            this,
            { currentGemManager.saveGems() },
            AUTO_SAVE_INTERVAL,
            AUTO_SAVE_INTERVAL,
        )

        powerStructureManager.startEffectRefreshTask()

        refreshAllowedCommandProxies()
        bindShutdownActions()

        currentLanguageManager.logMessage("plugin_enabled")
        currentLanguageManager.logMessage("documentation", linkPlaceholders())
    }

    private fun initializePermissionProvider() {
        val luckPermsProvider = LuckPermsPermissionProvider(this)
        if (luckPermsProvider.isAvailable()) {
            permissionProvider = luckPermsProvider
            logger.info("Using LuckPerms permission provider.")
            return
        }

        if (server.pluginManager.getPlugin("Vault") != null) {
            try {
                val rsp = server.servicesManager.getRegistration(Permission::class.java)
                if (rsp != null) {
                    permissionProvider = VaultPermissionProvider(this, rsp.provider)
                    logger.info("Using Vault permission provider.")
                    return
                }
            } catch (e: Exception) {
                logger.warning("Failed to initialize Vault permissions: " + e.message)
            }
        }

        permissionProvider = BukkitPermissionProvider()
        logger.info("Using Bukkit permission provider.")
    }

    override fun disablePlugin() {
    }

    private fun bindShutdownActions() {
        bind {
            if (::languageManager.isInitialized) {
                languageManager.logMessage("plugin_disabled")
            }
        }
        bind {
            if (::gemManager.isInitialized) {
                gemManager.saveGems()
            }
        }
        bind {
            val map = getCommandMapSafely()
            if (map != null) {
                unregisterProxyCommands(map)
            }
        }
        bind {
            if (::featureManager.isInitialized) {
                featureManager.shutdownAll()
            }
        }
        bind {
            if (::powerStructureManager.isInitialized) {
                powerStructureManager.stopEffectRefreshTask()
            }
        }
    }

    fun loadPlugin() {
        saveDefaultResources()
        reloadConfig()
        try {
            migrateConfigAndLang()
        } catch (ex: MigrationException) {
            logger.severe("RuleGems reload aborted: migration failed. " + ex.message)
            throw IllegalStateException("RuleGems migration failed. See logs for details.", ex)
        }
        languageManager.updateBundledLanguages()
        languageManager.loadLanguage()
        configManager.initGemFile()
        configManager.loadConfigs()
        configManager.getGemsData()
        gemManager.loadGems()
        gemManager.initializePlacedGemBlocks()
        gemManager.ensureConfiguredGemsPresent()

        val currentGemConsumeListener = gemConsumeListener
        if (currentGemConsumeListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(currentGemConsumeListener)
            if (gameplayConfig.isHoldToRedeemEnabled) {
                Bukkit.getPluginManager().registerEvents(currentGemConsumeListener, this)
            }
        }

        if (::featureManager.isInitialized) {
            featureManager.reloadAll()
            configureAllowanceSourceLookups()
            RuleGemsDoctor(this).logWarnings()
        }
    }

    private fun configureAllowanceSourceLookups() {
        if (!::gemManager.isInitialized || !::featureManager.isInitialized) {
            return
        }
        val currentFeatureManager = featureManager
        val allowanceManager = gemManager.allowanceManager
        if (allowanceManager == null) {
            return
        }
        allowanceManager.setAppointmentPowerLookup { key ->
            val appointFeature: AppointFeature? = currentFeatureManager.appointFeature
            if (appointFeature == null || key == null) {
                return@setAppointmentPowerLookup null
            }
            var def: AppointDefinition? = appointFeature.getAppointDefinition(key)
            if (def == null) {
                for ((entryKey, entryValue) in appointFeature.appointDefinitions) {
                    if (entryKey != null && entryKey.equals(key, ignoreCase = true)) {
                        def = entryValue
                        break
                    }
                }
            }
            def?.powerStructure
        }
    }

    private fun saveDefaultResources() {
        ResourceFiles(this).saveIfMissing(
            listOf(
                "config.yml",
                "lang/zh_CN.yml",
                "lang/en_US.yml",
            ),
        )
    }

    @Throws(MigrationException::class)
    private fun migrateConfigAndLang() {
        val migrations = MigrationRunner(this)
        migrations.run(
            MigrationPlan.yaml("RuleGems config", "config.yml")
                .versionKey("config-version")
                .targetVersion(2)
                .addStep(NoOpMigrationStep(1, 2, "Add RuleGems config-version.")),
        )
        migrateLang(migrations, "zh_CN")
        migrateLang(migrations, "en_US")
    }

    @Throws(MigrationException::class)
    private fun migrateLang(migrations: MigrationRunner, locale: String) {
        migrations.run(
            MigrationPlan.yaml("RuleGems lang $locale", "lang/$locale.yml")
                .versionKey("lang-version")
                .targetVersion(2)
                .addStep(LegacyTextToMiniMessageStep(1, 2)),
        )
    }

    private fun linkPlaceholders(): Map<String, String> {
        val placeholders: MutableMap<String, String> = HashMap()
        placeholders["docs"] = config.getString("links.documentation", "https://github.com/angushushu/RuleGems")
            ?: "https://github.com/angushushu/RuleGems"
        placeholders["discord"] = config.getString("links.discord", "https://discord.com/invite/7tJeSZPZgv")
            ?: "https://discord.com/invite/7tJeSZPZgv"
        placeholders["qq"] = config.getString("links.qq", "https://pd.qq.com/s/1n3hpe4e7?b=9")
            ?: "https://pd.qq.com/s/1n3hpe4e7?b=9"
        return placeholders
    }

    fun refreshAllowedCommandProxies() {
        val map = getCommandMapSafely()
        val listener = commandAllowanceListener
        if (map == null || listener == null) {
            return
        }
        unregisterProxyCommands(map)
        val configuredLabels = configManager.collectAllowedCommandLabels() ?: Collections.emptySet()

        val registered: MutableSet<String> = HashSet()
        val known = getKnownCommands(map)
        for (label in configuredLabels) {
            if (label.isNullOrEmpty()) {
                continue
            }
            val normalized = label.lowercase(Locale.ROOT)
            val existing = map.getCommand(normalized)
            if (existing != null && existing !is AllowedCommandProxy) {
                logger.warning(
                    "Skipping proxy registration for /$normalized because another plugin already provides it. " +
                        "RuleGems will still intercept this command for players with matching allowed-command uses.",
                )
                continue
            }

            val proxy = AllowedCommandProxy(normalized, this, listener)
            map.register("rulegems", proxy)
            proxyCommands[normalized] = proxy
            registered.add(normalized)
            if (known != null) {
                known[normalized] = proxy
                known["rulegems:$normalized"] = proxy
            }
        }
        listener.updateProxyLabels(registered)
    }

    private fun unregisterProxyCommands(map: CommandMap) {
        if (proxyCommands.isEmpty()) {
            return
        }
        val known = getKnownCommands(map)
        for (proxy in proxyCommands.values) {
            proxy.unregister(map)
            if (known != null) {
                known.remove(proxy.name)
                known.remove("rulegems:" + proxy.name)
            }
        }
        proxyCommands.clear()
    }

    private fun getCommandMapSafely(): CommandMap? {
        if (cachedCommandMap != null) {
            return cachedCommandMap
        }
        try {
            cachedCommandMap = Bukkit::class.java.getMethod("getCommandMap").invoke(null) as CommandMap
            return cachedCommandMap
        } catch (_: NoSuchMethodException) {
        } catch (e: Exception) {
            logger.fine("Bukkit.getCommandMap() failed: " + e.message)
        }

        try {
            val field: Field = server.javaClass.getDeclaredField("commandMap")
            field.isAccessible = true
            cachedCommandMap = field.get(server) as CommandMap
        } catch (ex: ReflectiveOperationException) {
            logger.log(Level.SEVERE, "Unable to access Bukkit command map via reflection", ex)
        } catch (ex: SecurityException) {
            logger.log(Level.SEVERE, "Unable to access Bukkit command map via reflection", ex)
        }
        return cachedCommandMap
    }

    @Suppress("UNCHECKED_CAST")
    private fun getKnownCommands(map: CommandMap): MutableMap<String, Command>? {
        if (map !is SimpleCommandMap) {
            return null
        }
        return try {
            val field = SimpleCommandMap::class.java.getDeclaredField("knownCommands")
            field.isAccessible = true
            field.get(map) as MutableMap<String, Command>
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TICKS_PER_SECOND = 20L
        private const val PROXIMITY_CHECK_INTERVAL = TICKS_PER_SECOND
        private const val AUTO_SAVE_INTERVAL = TICKS_PER_SECOND * 60 * 60
    }
}
