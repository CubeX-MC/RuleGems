package me.hushu;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static org.bukkit.Bukkit.getConsoleSender;
import static org.bukkit.Bukkit.getPluginManager;

/**
 * PowerGem 插件主类
 */

public class PowerGem extends JavaPlugin implements Listener, CommandExecutor {

    // 从 config 里读取
    private int requiredCount;         // 宝石总数
    private Material gemMaterial;      // 宝石的材质
    private String gemName;            // 宝石名称（可带颜色）
    private Particle gemParticle;      // 宝石粒子特效
    private Sound gemSound;            // 宝石声音
    private boolean useRequiredLoc;   // 是否强制要求放置在指定坐标
    private Location requiredLocCenter; // 存所有必放坐标
    private int requiredLocRadius;

    private Location randomPlaceCorner1; // 随机放置范围的角落1
    private Location randomPlaceCorner2; // 随机放置范围的角落2

    private NamespacedKey powerGemKey;
    private NamespacedKey uniqueIdKey;
    private final Map<Location, UUID> locationToGemUuid = new HashMap<>();

    // 配置文件，保存方块坐标和UUID
    private File gemsFile;
    private FileConfiguration gemsData;

    // 指令、播放音效、粒子特效
    private ExecuteConfig gemUnionExecute;
    private ExecuteConfig gemScatterExecute;
    private ExecuteConfig powerRevokeExecute;

    private Player powerPlayer;

    @Override
    public void onEnable() {

        loadPlugin();

        getLogger().info("PowerGems has been enabled!");
        this.powerGemKey = new NamespacedKey(this, "power_gem");
        this.uniqueIdKey = new NamespacedKey(this, "unique_id");

        getPluginManager().registerEvents(this, this);

        // 注册命令
        getCommand("powergem").setExecutor(this);
        getCommand("powergem").setTabCompleter(new PowerGemTabCompleter());

        Bukkit.getScheduler().runTaskTimer(
                this,
                () -> checkPlayersNearPowerGems(),
                20L,  // 1秒后第一次执行
                20L   // 每隔1秒执行一次
        );

        // store gemData per hour
        Bukkit.getScheduler().runTaskTimer(
                this,
                () -> saveGemsToConfig(),
                20L * 60 * 60,  // 1小时后第一次执行
                20L * 60 * 60   // 每隔1小时执行一次
        );

        startParticleEffectTask(this.gemParticle);
    }

    /**
     * 启动一个定时任务来播放粒子特效
     */
    private void startParticleEffectTask(Particle particle) {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Location loc : locationToGemUuid.keySet()) {
                    World world = loc.getWorld();
                    if (world == null) continue;

                    // 简单的例子特效
                    world.spawnParticle(particle, loc, 1);
                }
            }
        }.runTaskTimer(this, 0L, 20L); // 每秒更新一次粒子
    }

    /**
     * 从 config.yml 读取各项设置
     */
    private void loadConfigValues() {
        FileConfiguration cfg = getConfig();

        // 1) 读取宝石总数
        this.requiredCount = cfg.getInt("required_count", 5);

        // 2) 读取宝石材质
        String matStr = cfg.getString("gem_material", "RED_STAINED_GLASS").toUpperCase();
        try {
            this.gemMaterial = Material.valueOf(matStr);
        } catch (IllegalArgumentException e) {
            getLogger().warning("[PowerGem] 无效的 gem_material: " + matStr + "，将使用RED_STAINED_GLASS代替。");
            this.gemMaterial = Material.RED_STAINED_GLASS;
        }

        // 3) 读取宝石名称
        String gemNameStr = cfg.getString("gem_name", "&c权力宝石");
        // 需要把 & 转义成 §
        this.gemName = ChatColor.translateAlternateColorCodes('&', gemNameStr);
        // 4) 读取宝石粒子特效
        this.gemParticle = Particle.valueOf(cfg.getString("gem_particle", "FLAME"));
        // 5) 读取宝石声音
        this.gemSound = Sound.valueOf(cfg.getString("gem_sound", "ENTITY_EXPERIENCE_ORB_PICKUP"));
        // 4) 是否强制要求放置在指定坐标
        this.useRequiredLoc = cfg.getBoolean("use_required_location", false);

        // 如果启用，就读坐标列表
        if (this.useRequiredLoc) {
            ConfigurationSection locsSection = cfg.getConfigurationSection("required_locations");
            String worldName = locsSection.getString("world");
            this.requiredLocRadius = locsSection.getInt("radius");
            World w = Bukkit.getWorld(worldName);
            if (w != null) {
                this.requiredLocCenter = getLocationFromConfig(locsSection, "center", w);
            } else {
                getLogger().warning("读取到无效世界: " + worldName);
            }
            getLogger().info(String.format("启用指定放置坐标: %.2f %.2f %.2f",
                    this.requiredLocCenter.getX(), this.requiredLocCenter.getY(), this.requiredLocCenter.getZ()));
        } else {
            getLogger().info("未启用指定坐标放置模式。");
        }

        // 5) 读取随机放置范围
        ConfigurationSection randomPlaceRange = getConfig().getConfigurationSection("random_place_range");
        if (randomPlaceRange == null) {
            getLogger().severe("配置文件中缺少 random_place_range 部分。");
            return;
        }

        String worldName = randomPlaceRange.getString("world");
        if (worldName == null) {
            getLogger().severe("random_place_range 中缺少 world 名称。");
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().severe("无法找到指定的世界: " + worldName);
            return;
        }

        // 读取 corner1 和 corner2 作为 Location 对象
        this.randomPlaceCorner1 = getLocationFromConfig(randomPlaceRange, "corner1", world);
        this.randomPlaceCorner2 = getLocationFromConfig(randomPlaceRange, "corner2", world);

        if (this.randomPlaceCorner1 == null || this.randomPlaceCorner2 == null) {
            getLogger().severe("random_place_range 中的 corner1 或 corner2 配置无效。");
            return;
        }

        // 6) 读取情景效果
        this.gemUnionExecute       = loadExecuteConfig("gem_union_execute");
        this.gemScatterExecute     = loadExecuteConfig("gem_scatter_execute");
        this.powerRevokeExecute    = loadExecuteConfig("power_revoke_execute");
    }

    private ExecuteConfig loadExecuteConfig(String path) {
        ExecuteConfig execCfg = new ExecuteConfig(
                getConfig().getStringList(path + ".commands"),
                getConfig().getString(path + ".sound"),
                getConfig().getString(path + ".particle"));
        return execCfg;
    }

    private Location getLocationFromConfig(ConfigurationSection configSection, String path, World world) {
        ConfigurationSection locSection = configSection.getConfigurationSection(path);
        if (locSection == null) {
            getLogger().severe("配置中缺少 " + path + " 节。");
            return null;
        }

        double x = locSection.getDouble("x");
        double y = locSection.getDouble("y");
        double z = locSection.getDouble("z");

        return new Location(world, x, y, z);
    }

    /**
     * 初始化配置文件
     */
    private void initGemFile() {
        gemsFile = new File(getDataFolder(), "powergems.yml");
        if (!gemsFile.exists()) {
            gemsFile.getParentFile().mkdirs();
            saveResource("powergems.yml", false);
        }
    }

    /**
     * 从配置文件加载宝石坐标及其UUID
     */
    private void loadGemsFromFile() {
        // 读取powerPlayer数据
        ConfigurationSection powerPlayerSection = gemsData.getConfigurationSection("power_player");
        if (powerPlayerSection != null) {
            String uuidStr = powerPlayerSection.getString("uuid");
            if (uuidStr != null) {
                UUID powerPlayerUUID = UUID.fromString(uuidStr);
                this.powerPlayer = Bukkit.getPlayer(powerPlayerUUID);
            }
        }
        // 读取宝石数据
        ConfigurationSection gemsSection = gemsData.getConfigurationSection("gems");
        if (gemsSection == null) return;

        for (String key : gemsSection.getKeys(false)) {
            // key 类似 "gems.<index>"
            String worldName = gemsSection.getString(key + ".world");
            double x = gemsSection.getDouble(key + ".x");
            double y = gemsSection.getDouble(key + ".y");
            double z = gemsSection.getDouble(key + ".z");
            String uuidStr = gemsSection.getString(key + ".uuid");

            World w = Bukkit.getWorld(worldName);
            if (w == null || uuidStr == null) {
                continue;
            }

            Location loc = new Location(w, x, y, z);
            UUID gemId = UUID.fromString(uuidStr);

            locationToGemUuid.put(loc, gemId);
        }
        getLogger().info("已从文件加载 " + locationToGemUuid.size() + " 个宝石方块数据。");
    }

    /**
     * 将当前所有宝石方块保存到配置文件
     */
    private void saveGemsToConfig() {
        // 先清空再写
        gemsData.set("gems", null);

        int index = 0;
        for (Location loc : locationToGemUuid.keySet()) {
            String path = "gems." + index++;
            gemsData.set(path + ".world", loc.getWorld().getName());
            gemsData.set(path + ".x", loc.getX());
            gemsData.set(path + ".y", loc.getY());
            gemsData.set(path + ".z", loc.getZ());
            gemsData.set(path + ".uuid", locationToGemUuid.get(loc).toString());
        }

        try {
            gemsData.save(gemsFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 查看宝石状态
     * 显示宝石数量，每个宝石的坐标或者持有者
     */
    private void gemStatus(CommandSender sender) {
        // 你可以在这里实现查看宝石状态的逻辑
        // 例如：显示宝石数量，每个宝石的坐标或者持有者
        HashMap<String, Integer> gemHolders = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory inv = p.getInventory();
            for (ItemStack item : inv.getContents()) {
                if (isPowerGem(item)) {
                    if (gemHolders.containsKey(p.getName())) {
                        gemHolders.put(p.getName(), gemHolders.get(p.getName()) + 1);
                    } else {
                        gemHolders.put(p.getName(), 1);
                    }
                }
            }
        }
        int heldGems = 0;
        for (String p : gemHolders.keySet()) {
            heldGems += gemHolders.get(p);
        }
        sender.sendMessage(ChatColor.GREEN + "预计宝石数量: " + this.requiredCount);
        sender.sendMessage(ChatColor.GREEN + "放置宝石数量: " + locationToGemUuid.size());
        sender.sendMessage(ChatColor.GREEN + "持有宝石数量: " + heldGems);
        // 展示所有宝石及坐标
        for (Location loc : locationToGemUuid.keySet()) {
            sender.sendMessage(ChatColor.GREEN + "坐标: " + loc.getBlockX() + " " + loc.getBlockY() + " " +
                    loc.getBlockZ() + " " + loc.getWorld().getName());
        }
        // 展示所有玩家及持有宝石数量
        for (String p : gemHolders.keySet()) {
            sender.sendMessage(ChatColor.GREEN + "玩家: " + p + " 持有宝石: " + gemHolders.get(p));
        }
    }

    /**
     * 自定义命令：/powergem ...
     * 可能的子命令：
     *  - place <x> <y> <z> : 在指定坐标放置宝石方块
     *  - revoke <playerName> : 收回该玩家的权限
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("powergem")) {
            return false;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "用法: /powergem <place|revoke|reload|powerplayer|gems|scatter|help>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                saveGemsToConfig();
                reloadConfig();
                loadPlugin();
                sender.sendMessage(ChatColor.GREEN + "[PowerGem] 配置已重新加载！");
                return true;
            case "powerplayer":
                sender.sendMessage(ChatColor.GREEN + "当前的 powerPlayer 是: " + powerPlayer.getName());
                return true;
            case "gems":
                gemStatus(sender);
                return true;
            case "scatter":
                scatterGems();
                sender.sendMessage(ChatColor.GREEN + "[PowerGem] 权力被收回，宝石已散落！");
                return true;
            case "place":
                return handlePlaceCommand(sender, args);
            case "revoke":
                return handleRevokeCommand(sender);
            case "help":
                sendHelp(sender);
                return true;
            default:
                sender.sendMessage(ChatColor.YELLOW + "未知的子命令。用法: /powergem <place|revoke|reload|powerplayer|gems|scatter|help>");
                return true;
        }
    }

    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + "=== PowerGem 帮助 ===");
        sender.sendMessage(ChatColor.YELLOW + "/powergem place <x> <y> <z> " + ChatColor.WHITE + "- 在指定坐标放置宝石方块");
        sender.sendMessage(ChatColor.YELLOW + "/powergem revoke <playerName> " + ChatColor.WHITE + "- 收回该玩家的权限");
        sender.sendMessage(ChatColor.YELLOW + "/powergem reload " + ChatColor.WHITE + "- 重新加载配置");
        sender.sendMessage(ChatColor.YELLOW + "/powergem powerplayer " + ChatColor.WHITE + "- 查看当前的 powerPlayer");
        sender.sendMessage(ChatColor.YELLOW + "/powergem gems " + ChatColor.WHITE + "- 查看所有宝石信息");
        sender.sendMessage(ChatColor.YELLOW + "/powergem scatter " + ChatColor.WHITE + "- 散落宝石");
        sender.sendMessage(ChatColor.YELLOW + "/powergem help " + ChatColor.WHITE + "- 显示帮助信息");
        sender.sendMessage(ChatColor.GREEN + "======================");
    }

    /**
     * 处理 /powergem place 子命令
     */
    private boolean handlePlaceCommand(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "用法: /powergem place <x> <y> <z>");
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "只能玩家执行此命令！");
            return true;
        }
        Player player = (Player) sender;
        World world = player.getWorld();
        double x, y, z;
        try {
            x = Double.parseDouble(args[1]);
            y = Double.parseDouble(args[2]);
            z = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "坐标必须是数字！");
            return true;
        }

        Location loc = new Location(world, x, y, z);
        if (!loc.getChunk().isLoaded()) {
            loc.getChunk().load();
        }

        // 放置宝石方块
        UUID newGemId = UUID.randomUUID();
        placePowerGem(loc, newGemId);

        sender.sendMessage(ChatColor.GREEN + "已在 " + x + " " + y + " " + z + " 放置一颗宝石。");
        return true;
    }

    /**
     * 处理 /powergem revoke 子命令
     */
    private boolean handleRevokeCommand(CommandSender sender) {
        if (this.powerPlayer == null) {
            sender.sendMessage(ChatColor.RED + "当前没有 powerPlayer。");
        } else {
            revokePermission();
            sender.sendMessage(ChatColor.GREEN + "已收回 " + this.powerPlayer.getName() + " 的权限。");
            this.powerPlayer = null;
        }
        return true;
    }

    /**
     * 重新加载本插件的配置
     */
    private void loadPlugin() {
        saveDefaultConfig();
        initGemFile();
        loadConfigValues();
        gemsData = YamlConfiguration.loadConfiguration(gemsFile);
        locationToGemUuid.clear();
        loadGemsFromFile();
        setGems();

        // 你也可以选择在这里重启粒子定时任务、定时器等，如果需要的话
        // 不过一般情况下不需要，因为那些任务依赖的数据本身就是
        // locationToGemUuid，这个容器现在已经刷新了即可。
    }

    /**
     * 随机放置宝石
     */
    private void scatterGems() {
        System.out.println("Scattering gems...");
        Set<Location> locCopy = new HashSet<>(locationToGemUuid.keySet());
        for (Location loc : locCopy) {
            unplacePowerGem(loc, locationToGemUuid.get(loc));
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            // 检查玩家身上的宝石
            Inventory inv = player.getInventory();
            for (ItemStack item : inv.getContents()) {
                if (isPowerGem(item)) {
                    inv.remove(item);
                }
            }
        }
        System.out.println("Recollected all gems.");
        for (int i = 0; i < requiredCount; i++) {
            UUID gemId = UUID.randomUUID();
            randomPlaceGem(gemId, this.randomPlaceCorner1, this.randomPlaceCorner2);
        }
        System.out.println("Scattered " + requiredCount + " gems.");
        // 执行情景效果 %amount% 和 %powerPlayer% 两个占位符
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%amount%", String.valueOf(requiredCount));
        if (this.powerPlayer != null) {
            placeholders.put("%player%", powerPlayer.getName());
            this.powerPlayer = null;
        }
        executeCommands(gemScatterExecute, placeholders);
        playGlobalSound(gemScatterExecute, 1.0f, 1.0f);
        playParticle(this.randomPlaceCorner1, gemScatterExecute);
        // 保存到配置文件
        saveGemsToConfig();
    }

    /**
     * 实际的检测逻辑
     */
    private void checkPlayersNearPowerGems() {
        if (locationToGemUuid.isEmpty()) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            Location playerLoc = player.getLocation();
            World playerWorld = playerLoc.getWorld();

            for (Location blockLoc : locationToGemUuid.keySet()) {
                if (!blockLoc.getWorld().equals(playerWorld)) {
                    continue;
                }
                double distance = playerLoc.distance(blockLoc);
                if (distance < 16.0) {
                    // 播放音效，声音可以随距离衰减
                    float volume = (float) (1.0 - (distance / 16.0)); // 距离越远，音量越小
                    float pitch = 1.0f; // 或者根据距离动态调整音高

                    // 播放音效
                    player.playSound(
                            playerLoc,
                            this.gemSound,
                            volume,
                            pitch
                    );

                    // 如果只要播放一次就行，可以在这里break
                    // break;
                }
            }
        }
    }

    /**
     * 示例：在某处注册一个方法，当玩家放置方块时，如果它是“自定义方块”，就记录坐标
     */
    public void placePowerGem(Location loc, UUID gemId) {
        Block block = loc.getBlock();
        block.setType(this.gemMaterial);
        getConsoleSender().sendMessage(ChatColor.GREEN + "放置了一个宝石方块在 " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());

        locationToGemUuid.put(loc, gemId);
    }

    /**
     * 如果方块被破坏了，也要移除坐标
     */
    public void unplacePowerGem(Location loc, UUID gemId) {
        loc.getBlock().setType(Material.AIR);
        locationToGemUuid.remove(loc, gemId);
    }

    /**
     * 根据指定的 UUID，创建一颗“权力宝石”物品。
     * @param gemId 这颗宝石的专属 UUID
     */
    public ItemStack createPowerGem(UUID gemId) {
        // 用STONE举例
        ItemStack powerGem = new ItemStack(this.gemMaterial, 1);
        ItemMeta meta = powerGem.getItemMeta();

        meta.setDisplayName(this.gemName);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        // 标记这是宝石
        pdc.set(powerGemKey, PersistentDataType.BYTE, (byte) 1);
        // 写入UUID
        pdc.set(uniqueIdKey, PersistentDataType.STRING, gemId.toString());

        powerGem.setItemMeta(meta);
        return powerGem;
    }

    /**
     * 如果物品是宝石，则返回它的 UUID；如果不是宝石，则返回 null。
     */
    public UUID getGemUUID(ItemStack item) {
        if (!isPowerGem(item)) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String uuidStr = pdc.get(uniqueIdKey, PersistentDataType.STRING);
        if (uuidStr == null) return null;

        return UUID.fromString(uuidStr);
    }

    /**
     * 如果方块是宝石，则返回它的 UUID；如果不是宝石，则返回 null。
     */
    public UUID getGemUUID(Block block) {
        if (!isPlacedPowerGem(block)) {
            return null;
        }

        Location loc = block.getLocation();
        return locationToGemUuid.get(loc);
    }

    /**
     * 根据locationToGemUuid里的数据，初始化宝石方块
     */
    private void setGems() {
        for (Location loc : locationToGemUuid.keySet()) {
            loc.getBlock().setType(this.gemMaterial);
        }
        // 检查所有在线玩家，如果背包里有宝石，设置宝石材质
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory inv = player.getInventory();
            for (ItemStack item : inv.getContents()) {
                if (isPowerGem(item)) {
                    item.setType(this.gemMaterial);
                }
            }
        }
    }

    /**
     * 监听玩家退出事件
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Inventory inv = player.getInventory();

        for (ItemStack item : inv.getContents()) {
            if (isPowerGem(item)) {
                // 拿到这颗宝石的UUID
                UUID gemId = getGemUUID(item);

                // 移除背包物品
                inv.remove(item);

                // 在玩家脚下放置方块
                Location loc = player.getLocation();
                loc.getBlock().setType(Material.STONE);

                // 记录到Map
                locationToGemUuid.put(loc, gemId);
            }
        }
    }

    private void executeCommands(ExecuteConfig execCfg, Map<String, String> placeholders) {
        List<String> commands = execCfg.getCommands();
        if (commands == null || commands.isEmpty()) {
            return;
        }
        for (String cmd : commands) {
            // 依次替换占位符
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String placeholder = entry.getKey();   // 例如 "%player%"
                String value = entry.getValue();       // 例如 "Steve"
                cmd = cmd.replace(placeholder, value);
            }
            // 以控制台身份执行命令
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    private void playGlobalSound(ExecuteConfig execCfg, float volume, float pitch) {
        if (execCfg.getSound() != null) {
            try {
                Sound sound = Sound.valueOf(execCfg.getSound());
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.playSound(p.getLocation(), sound, volume, pitch);
                }
            } catch (Exception ex) {
                getLogger().warning("[PowerGem] 无效的音效名称: " + execCfg.getSound());
            }
        }
    }

    private void playLocalSound(Location location, ExecuteConfig execCfg, float volume, float pitch) {
        if (execCfg.getSound() != null) {
            try {
                Sound sound = Sound.valueOf(execCfg.getSound());
                location.getWorld().playSound(location, sound, volume, pitch);
            } catch (Exception ex) {
                getLogger().warning("[PowerGem] 无效的音效名称: " + execCfg.getSound());
            }
        }
    }

    private void playParticle(Location location, ExecuteConfig execCfg) {
        if (execCfg.getParticle() != null) {
            try {
                Particle particle = Particle.valueOf(execCfg.getParticle());
                location.getWorld().spawnParticle(particle, location, 1);
            } catch (Exception ex) {
                getLogger().warning("[PowerGem] 无效的粒子名称: " + execCfg.getParticle());
            }
        }
    }

    /**
     * 监听玩家死亡事件
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Inventory inv = player.getInventory();

        for (ItemStack item : inv.getContents()) {
            if (isPowerGem(item)) {
                // 拿到这颗宝石的UUID
                UUID gemId = getGemUUID(item);

                // 移除背包物品
                inv.remove(item);

                Location deathLocation = player.getLocation();

                // 如果掉出世界，在世界中随机放置
                if (deathLocation != null) {
                    // 寻找附近的安全位置
                    Location safeLocation = findSafeLocationAround(deathLocation, 50, 10);
                    if (safeLocation != null) {
                        placePowerGem(deathLocation, gemId);
                    } else {
                        // 如果未找到安全位置，随机放置
                        randomPlaceGem(gemId, this.randomPlaceCorner1, this.randomPlaceCorner2);
                    }
                }
            }
        }
    }

    /**
     * 在指定范围内随机放置一个宝石方块
     */
    /**
     * 随机放置指定数量的宝石。
     */
    private void randomPlaceGem(UUID gemId, Location corner1, Location corner2) {
        System.out.println("corner1: " + corner1);
        System.out.println("corner2: " + corner2);
        Location loc = findSafeLocation(corner1, corner2, 10);
        System.out.println("Randomly placing a gem at " + loc);
        if (loc != null) {
            placePowerGem(loc, gemId);
            System.out.println("Placed a gem at " + loc);
        }
    }

    /**
     * 检查指定位置是否适合放置宝石。
     *
     * @param loc 需要检查的位置
     * @return 如果适合返回 true，否则返回 false
     */
    private boolean isSafeLocation(Location loc) {
        WorldBorder border = loc.getWorld().getWorldBorder();
        int maxHeight = loc.getWorld().getMaxHeight();
        int minHeight = loc.getWorld().getMinHeight();
        // check if the location is within the world border
        if (!border.isInside(loc)) {
            return false;
        }
        // check if the location is within the world height limit
        if (loc.getBlockY() < minHeight || loc.getBlockY() > maxHeight) {
            return false;
        }
        Block block = loc.getBlock();

        System.out.println("Checking block at: " + loc);
        System.out.println(block.getType() == Material.AIR &&
                !locationToGemUuid.keySet().contains(loc));
        System.out.println(block.getType() == Material.AIR);
        System.out.println(block.getType());
        System.out.println(!locationToGemUuid.keySet().contains(loc));
        // 检查当前位置是空气
        return block.getType() == Material.AIR &&
                !locationToGemUuid.keySet().contains(loc);
    }

    /**
     * 找一个位置附近的安全位置。
     * @param startLoc 起始位置
     * @return 安全的位置，若未找到则返回 null
     */
    private Location findSafeLocationAround(Location startLoc, int radius, int maxAttempts) {
        Location corner1 = new Location(startLoc.getWorld(),
                startLoc.getBlockX() - radius,
                startLoc.getBlockY() - radius,
                startLoc.getBlockZ() - radius);
        Location corner2 = new Location(startLoc.getWorld(),
                startLoc.getBlockX() + radius,
                startLoc.getBlockY() + radius,
                startLoc.getBlockZ() + radius);
        return findSafeLocation(corner1, corner2, maxAttempts);
    }

    /**
     * 从指定位置向上寻找一个安全的位置。
     *
     * @param corner1     随机放置范围的角落1
     * @param corner2     随机放置范围的角落2
     * @param maxAttempts 最大尝试次数
     * @return 安全的位置，若未找到则返回 null
     */
    private Location findSafeLocation(Location corner1, Location corner2, int maxAttempts) {
        if (corner1.getWorld() != corner2.getWorld()) {
            getLogger().warning("两个角落的世界不一致，无法选择位置。");
            return null;
        }
        World world = corner1.getWorld();

        int minX = Math.min(corner1.getBlockX(), corner2.getBlockX());
        int minZ = Math.min(corner1.getBlockZ(), corner2.getBlockZ());
        int maxX = Math.max(corner1.getBlockX(), corner2.getBlockX());
        int maxZ = Math.max(corner1.getBlockZ(), corner2.getBlockZ());

        Random rand = new Random();
        int attempts = 0;

        while (attempts < maxAttempts) {
            int x = rand.nextInt(maxX - minX + 1) + minX;
            int z = rand.nextInt(maxZ - minZ + 1) + minZ;
            int y = world.getHighestBlockYAt(x, z) + 1;

            Location loc = new Location(world, x, y, z);
            System.out.println("Checking location: " + loc);

            // 检查该位置是否适合放置宝石
            if (isSafeLocation(loc)) {
                return loc;
            }

            attempts++;
        }
        return null;
    }

    /**
     * 判断物品是否为权力宝石
     */
    public boolean isPowerGem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(powerGemKey, PersistentDataType.BYTE);
    }

    /**
     * 判断方块是否为放置了的权力宝石
     */
    private boolean isPlacedPowerGem(Block block) {
        // 判断 block 是否是为权力宝石
        if (block.getType() == Material.STONE) {
            Location loc = block.getLocation();
            return locationToGemUuid.keySet().contains(loc);
        }
        return false;
    }

    /**
     * 给玩家授予权限
     */
    private void grantPermission(Player player) {
        // 执行授予权限的逻辑
        // 你也可以在这里给玩家一段提示
        player.sendMessage(ChatColor.GREEN + "恭喜！你放下了最后一个特殊方块，获得了 myplugin.specialblock 权限！");
    }

    /** 收回权限 */
    private void revokePermission() {
        executeCommands(powerRevokeExecute, Collections.singletonMap("%player%", this.powerPlayer.getName()));
        playGlobalSound(powerRevokeExecute, 1.0f, 1.0f);
        if (this.powerPlayer.isOnline()) {
            playParticle(this.powerPlayer.getLocation(), powerRevokeExecute);
            this.powerPlayer.sendMessage(ChatColor.RED + "你的权力已被收回！");
        }
        this.powerPlayer = null;
        saveGemsToConfig();
    }

    @EventHandler
    // 禁止玩家将 Gem 放入容器
    public void onInventoryDrag(InventoryDragEvent event) {
        for (ItemStack item : event.getNewItems().values()) {
            if (isPowerGem(item)) {
                // 取消拖拽事件以防止将 Gem 放入容器
                event.setCancelled(true);
                event.getWhoClicked().sendMessage(ChatColor.RED + "不能将 Power Gem 拖拽到此处！");
                break;
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack inHand = event.getItemInHand();

        // 判断这个 block 是否是我们的“特殊方块”
        if (!isPowerGem(inHand)) {
            return;
        }

        // 如果是宝石，先拿到它的 UUID
        UUID gemId = getGemUUID(inHand);
        if (gemId == null) {
            // 理论上不会出现，但防御性判断
            gemId = UUID.randomUUID();
        }

        Block block = event.getBlockPlaced();
        Location placedLoc = block.getLocation();

        locationToGemUuid.put(placedLoc, gemId);

        // 如果达到要求的数量
        if (checkWinCondition()) {
            // 给予当前玩家权限
            grantPermission(player);

            // 执行情景效果
            executeCommands(gemUnionExecute, Collections.singletonMap("%player%", player.getName()));
            playGlobalSound(gemUnionExecute, 1.0f, 1.0f);
            playParticle(placedLoc, gemUnionExecute);

            // 你也可以做一次性触发，把 placedCount 重置，避免重复触发
            // placedCount = 0;

            // 或者如果只想触发一次，就可以把监听器注销掉
            // HandlerList.unregisterAll(this);
        }
    }

    /**
     * 判断当前所有的“特殊方块”是否都在 (0,60,0) 为中心、半径5 的**立方体**范围内
     */
    private boolean checkWinCondition() {
        if (locationToGemUuid.size() < requiredCount) {
            return false;
        }
        if(useRequiredLoc) {
            for (Location loc : locationToGemUuid.keySet()) {
                if (!isInCube(loc, requiredLocCenter, requiredLocRadius)) {
                    return false;
                }
            }
            return true;
        } else {
            // 检查是否全部方块共同组成一个整体
            return isAllConnected(locationToGemUuid.keySet());
        }

    }

    /**
     * 判断指定集合中的方块是否连成一个整体（面邻接，6个方向）。
     * @param locations 存放所有方块坐标的集合
     * @return 若所有方块连通则返回 true，否则 false
     */
    public boolean isAllConnected(Set<Location> locations) {
        // 如果集合为空或只有一个方块，直接视为连通
        if (locations.size() <= 1) {
            return true;
        }
        // 拿到集合里“第一个”坐标作为起点
        Location start = locations.iterator().next();

        // 用 BFS 或 DFS 搜索能到达的方块数
        int visitedCount = bfsCount(start, locations);

        // 如果能访问到的数 == 集合总大小，则说明全都连通
        return visitedCount == locations.size();
    }

    /**
     * 从起始坐标 start 对集合 locations 做 BFS，返回能访问到的方块数量
     */
    private int bfsCount(Location start, Set<Location> locations) {
        Queue<Location> queue = new LinkedList<>();
        Set<Location> visited = new HashSet<>();

        queue.offer(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            Location current = queue.poll();

            // 找 6 个方向的邻居
            for (Location neighbor : getNeighbors(current)) {
                // 如果 neighbor 在集合中，且尚未访问
                if (locations.contains(neighbor) && !visited.contains(neighbor)) {
                    visited.add(neighbor);
                    queue.offer(neighbor);
                }
            }
        }
        // 返回访问到的方块数
        return visited.size();
    }

    /**
     * 获取某方块坐标在 6 个方向上的邻居
     */
    private List<Location> getNeighbors(Location loc) {
        List<Location> neighbors = new ArrayList<>(6);

        // 上下
        neighbors.add(loc.clone().add(0, 1, 0));
        neighbors.add(loc.clone().add(0, -1, 0));
        // 南北 (z轴 ±1)
        neighbors.add(loc.clone().add(0, 0, 1));
        neighbors.add(loc.clone().add(0, 0, -1));
        // 东西 (x轴 ±1)
        neighbors.add(loc.clone().add(1, 0, 0));
        neighbors.add(loc.clone().add(-1, 0, 0));

        return neighbors;
    }

    /**
     * 判断某个坐标是否在以 center 为中心、边长=半径*2+1 的立方体内
     * 这里用“绝对值 <= 半径”来判断
     */
    private boolean isInCube(Location loc, Location center, double radius) {
        // 如果世界不一致，直接返回 false
        if (!loc.getWorld().equals(center.getWorld())) {
            return false;
        }

        // 立方体判断： |dx|<=r && |dy|<=r && |dz|<=r
        double dx = Math.abs(loc.getX() - center.getX());
        double dy = Math.abs(loc.getY() - center.getY());
        double dz = Math.abs(loc.getZ() - center.getZ());

        return (dx <= radius && dy <= radius && dz <= radius);
    }

    /**
     * 当玩家破坏方块时触发
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // 如果这个坐标存在于 locationToGemUuid，说明是宝石方块
        if (locationToGemUuid.containsKey(block.getLocation())) {
            // 先取到UUID
            UUID gemId = locationToGemUuid.get(block.getLocation());

            // 从Map里移除
            locationToGemUuid.remove(block.getLocation());

            // 给破坏者掉落/添加对应UUID的物品
            Player player = event.getPlayer();
            Inventory inv = player.getInventory();

            ItemStack gemItem = createPowerGem(gemId); // 同一个UUID
            if (inv.firstEmpty() == -1) {
                // 背包已满 -> 掉落在地上
                placePowerGem(block.getLocation(), gemId);
            } else {
                // 放进玩家背包
                inv.addItem(gemItem);
                unplacePowerGem(block.getLocation(), gemId);
            }
        }
    }

    @EventHandler
    // 丢弃将自动放置在地面
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        UUID gemId = getGemUUID(item);
        if (isPowerGem(item)) {
            // 确保 Gem 只能作为实体存在于地面
            // 如果有其他逻辑需求，可以在这里处理
            event.getItemDrop().remove();
            placePowerGem(event.getItemDrop().getLocation(), gemId);
        }
    }

    @Override
    public void onDisable() {
        saveGemsToConfig();
        getLogger().info("PowerGems has been disabled!");
    }
}
