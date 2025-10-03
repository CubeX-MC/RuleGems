package me.hushu;

import static org.bukkit.Bukkit.getPluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import me.hushu.commands.RulerGemTabCompleter;
import me.hushu.commands.RulerGemCommand;
import me.hushu.listeners.GemInventoryListener;
import me.hushu.listeners.GemPlaceListener;
import me.hushu.listeners.PlayerEventListener;
import me.hushu.manager.ConfigManager;
import me.hushu.manager.GemManager;
import me.hushu.manager.LanguageManager;
import me.hushu.metrics.Metrics;
import me.hushu.utils.EffectUtils;
import me.hushu.utils.SchedulerUtil;
import net.milkbowl.vault.permission.Permission;

/**
 * RulerGem 插件主类
 */

public class RulerGem extends JavaPlugin {

    private ConfigManager configManager;
    private GemManager gemManager;
    private EffectUtils effectUtils;
    private LanguageManager languageManager;
    private Permission vaultPerms;
    private Metrics metrics;

    @Override
    public void onEnable() {
        // 初始化配置管理器

        this.configManager = new ConfigManager(this);
        this.languageManager = new LanguageManager(this);
        this.effectUtils = new EffectUtils(this);
//        this.configManager.loadConfigs();   // 读 config.yml, rulergem.yml
        this.gemManager = new GemManager(this, configManager, effectUtils, languageManager);

        this.metrics = new Metrics(this, 27436);
        loadPlugin();

        // 注册命令
        RulerGemCommand rulerGemCommand = new RulerGemCommand(this, gemManager, configManager, languageManager);
        org.bukkit.command.PluginCommand cmd = getCommand("rulergem");
        if (cmd != null) {
            cmd.setExecutor(rulerGemCommand);
            cmd.setTabCompleter(new RulerGemTabCompleter(configManager));
        } else {
            getLogger().warning("Command 'rulergem' not found in plugin.yml");
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
    public Permission getVaultPerms() { return vaultPerms; }

}
