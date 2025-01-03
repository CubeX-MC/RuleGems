package me.hushu;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import static org.bukkit.Bukkit.getPluginManager;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import me.hushu.commands.PowerGemCommand;
import me.hushu.commands.PowerGemTabCompleter;
import me.hushu.listeners.GemInventoryListener;
import me.hushu.listeners.GemPlaceListener;
import me.hushu.listeners.PlayerEventListener;
import me.hushu.manager.ConfigManager;
import me.hushu.manager.GemManager;
import me.hushu.utils.EffectUtils;

/**
 * PowerGem 插件主类
 */

public class PowerGem extends JavaPlugin {

    private ConfigManager configManager;
    private GemManager gemManager;
    private EffectUtils effectUtils;

    private final Map<Location, UUID> locationToGemUuid = new HashMap<>();

    @Override
    public void onEnable() {

        // 初始化配置管理器
        this.configManager = new ConfigManager(this);
        this.configManager.loadConfigs();   // 读 config.yml, powergems.yml

        // 初始化 ActionUtils
        this.effectUtils = new EffectUtils(this);

        // 初始化宝石管理器
        this.gemManager = new GemManager(this, configManager, effectUtils);

        loadPlugin();

        // 注册命令
        PowerGemCommand powerGemCommand = new PowerGemCommand(this, gemManager, configManager);
        getCommand("powergem").setExecutor(powerGemCommand);
        getCommand("powergem").setTabCompleter(new PowerGemTabCompleter());
        // 注册监听器
        getPluginManager().registerEvents(new GemPlaceListener(this, gemManager), this);
        getPluginManager().registerEvents(new GemInventoryListener(gemManager), this);
        getPluginManager().registerEvents(new PlayerEventListener(this, gemManager), this);

        Bukkit.getScheduler().runTaskTimer(
                this,
                () -> gemManager.checkPlayersNearPowerGems(),
                20L,  // 1秒后第一次执行
                20L   // 每隔1秒执行一次
        );

        // store gemData per hour
        Bukkit.getScheduler().runTaskTimer(
                this,
                () -> gemManager.saveGems(),
                20L * 60 * 60,  // 1小时后第一次执行
                20L * 60 * 60   // 每隔1小时执行一次
        );

        gemManager.startParticleEffectTask(configManager.getGemParticle());

        getLogger().info("PowerGem 插件已成功启用！");
    }

    @Override
    public void onDisable() {
        gemManager.saveGems();
        getLogger().info("PowerGems has been disabled!");
    }

    /**
     * 重新加载本插件的配置
     */
    public void loadPlugin() {
        saveDefaultConfig();
        configManager.initGemFile();
        configManager.loadConfigs();
        configManager.getGemsData();
        gemManager.loadGems();
        gemManager.setGems();
    }

    public ConfigManager getConfigManager() { return configManager; }
    public GemManager getGemManager() { return gemManager; }
    public EffectUtils getEffectUtils() { return effectUtils; }

}
