package org.cubexmc;

import static org.bukkit.Bukkit.getPluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import org.cubexmc.commands.RuleGemsTabCompleter;
import org.cubexmc.commands.RuleGemsCommand;
import org.cubexmc.listeners.GemInventoryListener;
import org.cubexmc.listeners.GemPlaceListener;
import org.cubexmc.listeners.PlayerEventListener;
import org.cubexmc.manager.ConfigManager;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.HistoryLogger;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.metrics.Metrics;
import org.cubexmc.utils.EffectUtils;
import org.cubexmc.utils.SchedulerUtil;
import net.milkbowl.vault.permission.Permission;

/**
 * RuleGems 插件主类
 */

public class RuleGems extends JavaPlugin {

    private ConfigManager configManager;
    private GemManager gemManager;
    private EffectUtils effectUtils;
    private LanguageManager languageManager;
    private HistoryLogger historyLogger;
    private Permission vaultPerms;
    private Metrics metrics;

    @Override
    public void onEnable() {
        // 初始化配置管理器

        this.configManager = new ConfigManager(this);
        this.languageManager = new LanguageManager(this);
        this.effectUtils = new EffectUtils(this);
        this.historyLogger = new HistoryLogger(this);
//        this.configManager.loadConfigs();   // 读 config.yml, rulergem.yml
        this.gemManager = new GemManager(this, configManager, effectUtils, languageManager);
        this.gemManager.setHistoryLogger(historyLogger);

        this.metrics = new Metrics(this, 27483);
        loadPlugin();

        // 注册命令
        RuleGemsCommand ruleGemsCommand = new RuleGemsCommand(this, gemManager, configManager, languageManager);
        org.bukkit.command.PluginCommand cmd = getCommand("rulegems");
        if (cmd != null) {
            cmd.setExecutor(ruleGemsCommand);
            cmd.setTabCompleter(new RuleGemsTabCompleter(configManager));
        } else {
            getLogger().warning("Command 'rulegems' not found in plugin.yml");
        }
        // 注册监听器
        getPluginManager().registerEvents(new GemPlaceListener(this, gemManager), this);
        getPluginManager().registerEvents(new GemInventoryListener(gemManager, languageManager), this);
        getPluginManager().registerEvents(new PlayerEventListener(this, gemManager), this);
        // Setup Vault permissions (optional)
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            try {
                org.bukkit.plugin.RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager().getRegistration(Permission.class);
                if (rsp != null) {
                    this.vaultPerms = rsp.getProvider();
                }
            } catch (Exception ignored) {}
        }

        SchedulerUtil.globalRun(
                this,
                () -> gemManager.checkPlayersNearRulerGems(),
                20L,
                20L
        );

    // Start per-gem particle task (uses per-gem definitions internally)
    gemManager.startParticleEffectTask(org.bukkit.Particle.FLAME);

        // store gemData per hour
        SchedulerUtil.globalRun(
                this,
                () -> gemManager.saveGems(),
                20L * 60 * 60,
                20L * 60 * 60
        );

        // 取消依赖全局粒子设置；如需粒子展示可在 GemManager 内按 per-gem 自行实现

        languageManager.logMessage("plugin_enabled");
    }

    @Override
    public void onDisable() {
        gemManager.saveGems();
        languageManager.logMessage("plugin_disabled");
    }

    /**
     * 重新加载本插件的配置
     */
    public void loadPlugin() {
        saveDefaultConfig();
        languageManager.loadLanguage();
        configManager.initGemFile();
        configManager.loadConfigs();
        configManager.getGemsData();
        gemManager.loadGems();
        // 恢复已记录坐标的宝石方块材质，确保首次启动即可看到实体方块
        gemManager.initializePlacedGemBlocks();
        // 补齐配置定义但当前不存在的宝石，保证“服务器里永远有配置中的所有 gems”
        gemManager.ensureConfiguredGemsPresent();
    }

    public ConfigManager getConfigManager() { return configManager; }
    public GemManager getGemManager() { return gemManager; }
    public EffectUtils getEffectUtils() { return effectUtils; }
    public LanguageManager getLanguageManager() { return languageManager; }
    public HistoryLogger getHistoryLogger() { return historyLogger; }
    public Permission getVaultPerms() { return vaultPerms; }

}
