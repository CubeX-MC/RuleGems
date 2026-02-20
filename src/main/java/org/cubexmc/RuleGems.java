package org.cubexmc;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.bukkit.Bukkit.getPluginManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.java.JavaPlugin;
import org.cubexmc.commands.RuleGemsCommand;
import org.cubexmc.commands.RuleGemsTabCompleter;
import org.cubexmc.features.FeatureManager;
import org.cubexmc.listeners.CommandAllowanceListener;
import org.cubexmc.listeners.GemConsumeListener;
import org.cubexmc.listeners.GemInventoryListener;
import org.cubexmc.listeners.GemPlaceListener;
import org.cubexmc.listeners.PlayerEventListener;
import org.cubexmc.manager.ConfigManager;
import org.cubexmc.manager.GameplayConfig;
import org.cubexmc.manager.GemDefinitionParser;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.HistoryLogger;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.manager.PowerStructureManager;
import org.cubexmc.gui.GUIManager;
import org.cubexmc.metrics.Metrics;
import org.cubexmc.utils.EffectUtils;
import org.cubexmc.utils.SchedulerUtil;

import net.milkbowl.vault.permission.Permission;

/**
 * RuleGems 插件主类
 */
public class RuleGems extends JavaPlugin {

    private ConfigManager configManager;
    private GemDefinitionParser gemParser;
    private GameplayConfig gameplayConfig;
    private GemManager gemManager;
    private EffectUtils effectUtils;
    private LanguageManager languageManager;
    private HistoryLogger historyLogger;
    private org.cubexmc.manager.CustomCommandExecutor customCommandExecutor;
    private GUIManager guiManager;
    private FeatureManager featureManager;
    private PowerStructureManager powerStructureManager;
    private Permission vaultPerms;
    @SuppressWarnings("unused")
    private Metrics metrics;
    private CommandAllowanceListener commandAllowanceListener;
    private final Map<String, org.cubexmc.commands.AllowedCommandProxy> proxyCommands = new HashMap<>();
    private CommandMap cachedCommandMap;

    @Override
    public void onEnable() {
        // 初始化配置管理器
        // 初始化配置管理器
        this.languageManager = new LanguageManager(this);
        this.configManager = new ConfigManager(this, languageManager);
        this.gemParser = configManager.getGemParser();
        this.gameplayConfig = configManager.getGameplayConfig();
        this.effectUtils = new EffectUtils(this);
        this.powerStructureManager = new PowerStructureManager(this);
        this.historyLogger = new HistoryLogger(this, languageManager);
        this.customCommandExecutor = new org.cubexmc.manager.CustomCommandExecutor(this, languageManager, gameplayConfig);
        this.gemManager = new GemManager(this, configManager, gemParser, gameplayConfig, effectUtils, languageManager);
        this.gemManager.setHistoryLogger(historyLogger);
        this.guiManager = new GUIManager(this, gemManager, languageManager);

        this.metrics = new Metrics(this, 27483);
        loadPlugin();

        // 注册命令
        RuleGemsCommand ruleGemsCommand = new RuleGemsCommand(this, gemManager, gameplayConfig, languageManager,
                guiManager);
        org.bukkit.command.PluginCommand cmd = getCommand("rulegems");
        if (cmd != null) {
            cmd.setExecutor(ruleGemsCommand);
            cmd.setTabCompleter(new RuleGemsTabCompleter(gemParser, gemManager));
        } else {
            getLogger().warning("Command 'rulegems' not found in plugin.yml");
        }
        // 注册监听器
        getPluginManager().registerEvents(new GemPlaceListener(this, gemManager), this);
        getPluginManager().registerEvents(new GemInventoryListener(gemManager, languageManager), this);
        getPluginManager().registerEvents(new PlayerEventListener(this, gemManager), this);
        getPluginManager().registerEvents(new GemConsumeListener(this, gemManager, gameplayConfig, languageManager),
                this);
        this.commandAllowanceListener = new CommandAllowanceListener(gemManager.getAllowanceManager(), languageManager,
                customCommandExecutor, gameplayConfig);
        getPluginManager().registerEvents(commandAllowanceListener, this);

        // 安全警告
        if (gameplayConfig.isOpEscalationAllowed()) {
            getLogger().warning("========================================");
            getLogger().warning("allow_op_escalation is ENABLED!");
            getLogger().warning("This temporarily grants OP to players when executing allowed commands.");
            getLogger().warning("This is a security risk. Consider using 'console:' executor prefix instead.");
            getLogger().warning("========================================");
        }

        // 初始化功能管理器
        this.featureManager = new FeatureManager(this, gemManager);
        featureManager.registerFeatures();

        // Setup Vault permissions (optional)
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            try {
                org.bukkit.plugin.RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager()
                        .getRegistration(Permission.class);
                if (rsp != null) {
                    this.vaultPerms = rsp.getProvider();
                }
            } catch (Exception e) {
                getLogger().warning("Failed to initialize Vault permissions: " + e.getMessage());
            }
        }

        SchedulerUtil.globalRun(
                this,
                () -> gemManager.checkPlayersNearRuleGems(),
                20L,
                20L);

        // Start per-gem particle task (uses per-gem definitions internally)
        gemManager.startParticleEffectTask(org.bukkit.Particle.FLAME);

        // store gemData per hour
        SchedulerUtil.globalRun(
                this,
                () -> gemManager.saveGems(),
                20L * 60 * 60,
                20L * 60 * 60);

        // 取消依赖全局粒子设置；如需粒子展示可在 GemManager 内按 per-gem 自行实现

        refreshAllowedCommandProxies();

        languageManager.logMessage("plugin_enabled");
    }

    @Override
    public void onDisable() {
        // 关闭功能管理器
        if (featureManager != null) {
            featureManager.shutdownAll();
        }

        CommandMap map = getCommandMapSafely();
        if (map != null) {
            unregisterProxyCommands(map);
        }
        gemManager.saveGems();
        languageManager.logMessage("plugin_disabled");
    }

    /**
     * 重新加载本插件的配置
     */
    public void loadPlugin() {
        saveDefaultConfig();
        reloadConfig(); // Ensure config is loaded for LanguageManager
        languageManager.updateBundledLanguages();
        languageManager.loadLanguage();
        configManager.initGemFile();
        configManager.loadConfigs();
        configManager.getGemsData();
        gemManager.loadGems();
        // 恢复已记录坐标的宝石方块材质，确保首次启动即可看到实体方块
        gemManager.initializePlacedGemBlocks();
        // 补齐配置定义但当前不存在的宝石，保证“服务器里永远有配置中的所有 gems”
        gemManager.ensureConfiguredGemsPresent(); // 重载功能配置
        if (featureManager != null) {
            featureManager.reloadAll();
        }
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public GemDefinitionParser getGemParser() {
        return gemParser;
    }

    public GameplayConfig getGameplayConfig() {
        return gameplayConfig;
    }

    public GemManager getGemManager() {
        return gemManager;
    }

    public EffectUtils getEffectUtils() {
        return effectUtils;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public HistoryLogger getHistoryLogger() {
        return historyLogger;
    }

    public org.cubexmc.manager.CustomCommandExecutor getCustomCommandExecutor() {
        return customCommandExecutor;
    }

    public GUIManager getGUIManager() {
        return guiManager;
    }

    public FeatureManager getFeatureManager() {
        return featureManager;
    }

    public Permission getVaultPerms() {
        return vaultPerms;
    }

    public PowerStructureManager getPowerStructureManager() {
        return powerStructureManager;
    }

    public void refreshAllowedCommandProxies() {
        CommandMap map = getCommandMapSafely();
        if (map == null || commandAllowanceListener == null) {
            return;
        }
        unregisterProxyCommands(map);
        Set<String> configuredLabels = configManager.collectAllowedCommandLabels();
        if (configuredLabels == null) {
            configuredLabels = Collections.emptySet();
        }

        Set<String> registered = new HashSet<>();
        Map<String, Command> known = getKnownCommands(map);
        for (String label : configuredLabels) {
            if (label == null || label.isEmpty()) {
                continue;
            }
            String normalized = label.toLowerCase(Locale.ROOT);
            Command existing = map.getCommand(normalized);
            if (existing != null && !(existing instanceof org.cubexmc.commands.AllowedCommandProxy)) {
                getLogger().warning("Skipping proxy registration for /" + normalized
                        + " because another plugin already provides it.");
                continue;
            }

            org.cubexmc.commands.AllowedCommandProxy proxy = new org.cubexmc.commands.AllowedCommandProxy(normalized,
                    this, commandAllowanceListener);
            map.register("rulegems", proxy);
            proxyCommands.put(normalized, proxy);
            registered.add(normalized);
            if (known != null) {
                known.put(normalized, proxy);
                known.put("rulegems:" + normalized, proxy);
            }
        }
        commandAllowanceListener.updateProxyLabels(registered);
    }

    private void unregisterProxyCommands(CommandMap map) {
        if (proxyCommands.isEmpty()) {
            return;
        }
        Map<String, Command> known = getKnownCommands(map);
        for (org.cubexmc.commands.AllowedCommandProxy proxy : proxyCommands.values()) {
            proxy.unregister(map);
            if (known != null) {
                known.remove(proxy.getName());
                known.remove("rulegems:" + proxy.getName());
            }
        }
        proxyCommands.clear();
    }

    private CommandMap getCommandMapSafely() {
        if (cachedCommandMap != null) {
            return cachedCommandMap;
        }
        try {
            Field field = getServer().getClass().getDeclaredField("commandMap");
            field.setAccessible(true);
            cachedCommandMap = (CommandMap) field.get(getServer());
        } catch (Exception ex) {
            getLogger().warning("Unable to access Bukkit command map: " + ex.getMessage());
        }
        return cachedCommandMap;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> getKnownCommands(CommandMap map) {
        if (!(map instanceof SimpleCommandMap)) {
            return null;
        }
        try {
            Field field = SimpleCommandMap.class.getDeclaredField("knownCommands");
            field.setAccessible(true);
            return (Map<String, Command>) field.get(map);
        } catch (Exception ignored) {
            return null;
        }
    }

}
