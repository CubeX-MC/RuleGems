package me.hushu.manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import me.hushu.PowerGem;
import me.hushu.model.ExecuteConfig;
import me.hushu.utils.EffectUtils;
import me.hushu.utils.SchedulerUtils;

public class GemManager {
    private final PowerGem plugin;
    private final ConfigManager configManager;
    private final EffectUtils effectUtils;
    private final LanguageManager languageManager;

    private final NamespacedKey powerGemKey;
    private final NamespacedKey uniqueIdKey;
    private final Map<Location, UUID> locationToGemUuid = new HashMap<>();
    private final Map<UUID, Player> gemUuidToHolder = new HashMap<>();

    private Player powerPlayer;

    public GemManager(PowerGem plugin, ConfigManager configManager, EffectUtils effectUtils,
                      LanguageManager languageManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.effectUtils = effectUtils;
        this.languageManager = languageManager;
        this.powerGemKey = new NamespacedKey(plugin, "power_gem");
        this.uniqueIdKey = new NamespacedKey(plugin, "unique_id");
    }

    public void loadGems() {
        // 获取 gemsData
        FileConfiguration gemsData = configManager.readGemsData();

        if (gemsData == null) {
            plugin.getLogger().warning("无法加载 gemsData 配置！请检查文件是否存在。");
            return;
        }
//        locationToGemUuid.clear();
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
        // 放置的宝石
        ConfigurationSection placedGemsSection = gemsData.getConfigurationSection("placed-gems");
        if (placedGemsSection != null) {
            for (String uuidStr : placedGemsSection.getKeys(false)) {
                // key 类似 "gems.<uuid>"
                String worldName = placedGemsSection.getString(uuidStr + ".world");
                double x = placedGemsSection.getDouble(uuidStr + ".x");
                double y = placedGemsSection.getDouble(uuidStr + ".y");
                double z = placedGemsSection.getDouble(uuidStr + ".z");

                World w = Bukkit.getWorld(worldName);
                if (w == null || uuidStr == null) {
                    continue;
                }

                Location loc = new Location(w, x, y, z);
                UUID gemId = UUID.fromString(uuidStr);

                locationToGemUuid.put(loc, gemId);
            }
        }

        // 持有的宝石
        ConfigurationSection heldGemsSection = gemsData.getConfigurationSection("held-gems");
        if (heldGemsSection != null) {
            for (String uuidStr : heldGemsSection.getKeys(false)) {
                // key 就是uuid
                String playerUUIDStr = heldGemsSection.getString(uuidStr + ".player_uuid");

                UUID playerUUID = UUID.fromString(playerUUIDStr);
                UUID gemId = UUID.fromString(uuidStr);
                Player player = Bukkit.getPlayer(playerUUID);
                if (player == null) {
                    continue;
                }
                if (!player.isOnline()) {
                    // remove from player's inventory
                    Inventory inv = player.getInventory();
                    for (ItemStack item : inv.getContents()) {
                        if (isPowerGem(item) && getGemUUID(item).equals(gemId)) {
                            inv.remove(item);
                        }
                    }
                    placePowerGem(player.getLocation(), gemId);
                } else {
                    gemUuidToHolder.put(gemId, player);
                }
            }
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("placed_count", String.valueOf(locationToGemUuid.size()));
        placeholders.put("held_count", String.valueOf(gemUuidToHolder.size()));
        languageManager.logMessage("gems_loaded", placeholders);
        languageManager.logMessage("gems_held_loaded", placeholders);
    }

    public void saveGems() {
        FileConfiguration gemsData = configManager.getGemsData();
        gemsData.set("placed-gems", null);
        gemsData.set("held-gems", null);
        gemsData.set("power_player", null);

        for (Location loc : locationToGemUuid.keySet()) {
            String path = "placed-gams." + locationToGemUuid.get(loc).toString();
            gemsData.set(path + ".world", loc.getWorld().getName());
            gemsData.set(path + ".x", loc.getX());
            gemsData.set(path + ".y", loc.getY());
            gemsData.set(path + ".z", loc.getZ());
//            gemsData.set(path + ".uuid", locationToGemUuid.get(loc).toString());
        }
        for (UUID gemId : gemUuidToHolder.keySet()) {
            Player player = gemUuidToHolder.get(gemId);
            String path = "held-gems." + gemId.toString();
            gemsData.set(path + ".player", player.getName());
            gemsData.set(path + ".player_uuid", player.getUniqueId().toString());
//            gemsData.set(path + ".uuid", gemId.toString());
        }
        // 保存powerPlayer数据
        if (this.powerPlayer != null) {
            gemsData.set("power_player.uuid", this.powerPlayer.getUniqueId().toString());
        }
        configManager.saveGemData(gemsData);
    }

    @EventHandler
    public void onGemPlaced(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack inHand = event.getItemInHand();

        // 判断这个 block 是否是我们的"特殊方块"
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
        // 更新放置和持有信息
        gemUuidToHolder.remove(gemId);
        locationToGemUuid.put(placedLoc, gemId);

        // 如果达到要求的数量
        if (checkWinCondition()) {
            // 撤销旧的powerPlayer
            if (this.powerPlayer != null) {
                revokePermission(this.powerPlayer);
            }
            // 设置新的powerPlayer
            this.powerPlayer = player;
            // 执行情景效果
            ExecuteConfig gemUnionExecute = configManager.getGemUnionExecute();
            effectUtils.executeCommands(gemUnionExecute, Collections.singletonMap("%player%", player.getName()));
            effectUtils.playGlobalSound(gemUnionExecute, 1.0f, 1.0f);
            effectUtils.playParticle(placedLoc, gemUnionExecute);

            // 显示收集完成的标题
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", player.getName());
            for (Player p : Bukkit.getOnlinePlayers()) {
                languageManager.showTitle(p, "gems_recollected", placeholders);
            }
        }
    }

    @EventHandler
    public void onGemBroken(BlockBreakEvent event) {
        Block block = event.getBlock();

        // 如果这个坐标存在于 locationToGemUuid，说明是宝石方块
        if (locationToGemUuid.containsKey(block.getLocation())) {
            // 先取到UUID
            UUID gemId = locationToGemUuid.get(block.getLocation());
            Player player = event.getPlayer();
            Inventory inv = player.getInventory();
//            Map<String, String> placeholders = new HashMap<>();
//            placeholders.put("slot", String.valueOf(inv.firstEmpty()));
//            languageManager.logMessage("inventory_full", placeholders);
            if (inv.firstEmpty() == -1) {
                languageManager.logMessage("inventory_full");
                event.setCancelled(true);
                placePowerGem(block.getLocation(), gemId);
            } else {
                // 给破坏者掉落/添加对应UUID的物品
                // 从Map里移除
                // locationToGemUuid.remove(block.getLocation());
                ItemStack gemItem = createPowerGem(gemId); // 同一个UUID
                // 放进玩家背包
                inv.addItem(gemItem);
                gemUuidToHolder.put(gemId, player);
                unplacePowerGem(block.getLocation(), gemId);
            }
        }
    }

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
                
                gemUuidToHolder.remove(gemId);
                placePowerGem(loc, gemId);
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
            gemUuidToHolder.remove(gemId);
            placePowerGem(event.getItemDrop().getLocation(), gemId);
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
                UUID gemId = getGemUUID(item);
                inv.remove(item);
                gemUuidToHolder.remove(gemId);

                Location deathLocation = player.getLocation();

                // 如果掉出世界，在世界中随机放置
                if (deathLocation != null) {
                    // 寻找附近的安全位置
                    Location safeLocation = findSafeLocationAround(deathLocation, 50, 10);
                    if (safeLocation != null) {
                        placePowerGem(deathLocation, gemId);
                    } else {
                        // 如果未找到安全位置，随机放置
                        randomPlaceGem(gemId, configManager.getRandomPlaceCorner1(), configManager.getRandomPlaceCorner2());
                    }
                }
            }
        }
    }

    // onPlayerJoin
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Inventory inv = player.getInventory();
        for (ItemStack item : inv.getContents()) {
            if (isPowerGem(item)) {
                UUID gemId = getGemUUID(item);
                if (!gemUuidToHolder.containsKey(gemId)) {
                    inv.remove(item);
                    gemUuidToHolder.remove(gemId);
                }
            }
        }
    }

    /**
     * 随机放置宝石
     */
    public void scatterGems() {
        languageManager.logMessage("scatter_start");
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
        languageManager.logMessage("gems_recollected");
        for (int i = 0; i < configManager.getRequiredCount(); i++) {
            UUID gemId = UUID.randomUUID();
            randomPlaceGem(gemId, configManager.getRandomPlaceCorner1(), configManager.getRandomPlaceCorner2());
        }
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("count", String.valueOf(configManager.getRequiredCount()));
        languageManager.logMessage("gems_scattered", placeholders);

        if (this.powerPlayer != null) {
            placeholders.put("player", powerPlayer.getName());
            this.powerPlayer = null;
        }
        ExecuteConfig gemScatterExecute = configManager.getGemScatterExecute();
        effectUtils.executeCommands(gemScatterExecute, placeholders);
        effectUtils.playGlobalSound(gemScatterExecute, 1.0f, 1.0f);
        // 保存到配置文件
        for (Player p : Bukkit.getOnlinePlayers()) {
            languageManager.showTitle(p, "gems_scattered", placeholders);
        }
        saveGems();
    }

    /**
     * 查看宝石状态
     * 显示宝石数量，每个宝石的坐标或者持有者
     */
    public void gemStatus(CommandSender sender) {
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

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("count", String.valueOf(configManager.getRequiredCount()));
        placeholders.put("placed_count", String.valueOf(locationToGemUuid.size()));
        placeholders.put("held_count", String.valueOf(gemUuidToHolder.size()));
        languageManager.sendMessage(sender, "gem_status.total_expected", placeholders);
        languageManager.sendMessage(sender, "gem_status.total_counts", placeholders);

        // 展示所有宝石及坐标
        for (Location loc : locationToGemUuid.keySet()) {
            placeholders = new HashMap<>();
            placeholders.put("x", String.valueOf(loc.getBlockX()));
            placeholders.put("y", String.valueOf(loc.getBlockY()));
            placeholders.put("z", String.valueOf(loc.getBlockZ()));
            placeholders.put("world", loc.getWorld().getName());
            languageManager.sendMessage(sender, "gem_status.gem_location", placeholders);
        }

        // 展示所有玩家及持有宝石数量
        for (String p : gemHolders.keySet()) {
            placeholders = new HashMap<>();
            placeholders.put("player", p);
            placeholders.put("count", String.valueOf(gemHolders.get(p)));
            languageManager.sendMessage(sender, "gem_status.player_holding", placeholders);
        }

        // 列出所有的UUID，用gemUuidToHolder和locationToGemUuid
        for (UUID gemId : gemUuidToHolder.keySet()) {
            Player player = gemUuidToHolder.get(gemId);
            placeholders = new HashMap<>();
            placeholders.put("player", player.getName());
            placeholders.put("uuid", gemId.toString());
            languageManager.sendMessage(sender, "gem_status.player_gem_uuid", placeholders);
        }
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
    private boolean isPowerGem(Block block) {
        // 判断 block 是否是为权力宝石
        if (block.getType() == configManager.getGemMaterial()) {
            Location loc = block.getLocation();
            return locationToGemUuid.keySet().contains(loc);
        }
        return false;
    }

    /**
     * 根据指定的 UUID，创建一颗"权力宝石"物品。
     * @param gemId 这颗宝石的专属 UUID
     */
    public ItemStack createPowerGem(UUID gemId) {
        // 用STONE举例
        ItemStack powerGem = new ItemStack(configManager.getGemMaterial(), 1);
        ItemMeta meta = powerGem.getItemMeta();

        meta.setDisplayName(configManager.getGemName());

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        // 标记这是宝石
        pdc.set(powerGemKey, PersistentDataType.BYTE, (byte) 1);
        // 写入UUID
        pdc.set(uniqueIdKey, PersistentDataType.STRING, gemId.toString());

        powerGem.setItemMeta(meta);
        return powerGem;
    }

    /**
     * 根据locationToGemUuid里的数据，初始化宝石方块
     */
//    public void setGems() {
//        for (Location loc : locationToGemUuid.keySet()) {
//            placePowerGem(loc, locationToGemUuid.get(loc));
//        }
//        // 检查所有在线玩家，如果背包里有宝石，设置宝石材质
//        // 刚开机应该不会有
//        for (Player player : Bukkit.getOnlinePlayers()) {
//            Inventory inv = player.getInventory();
//            for (ItemStack item : inv.getContents()) {
//                if (isPowerGem(item)) {
//                    placePowerGem(player.getLocation(), getGemUUID(item));
//                }
//            }
//        }
//    }

    /*
     * get total gem count
     */
    public int getTotalGemCount() {
        return locationToGemUuid.size() + gemUuidToHolder.size();
    }

    /**
     * 示例：在某处注册一个方法，当玩家放置方块时，如果它是"自定义方块"，就记录坐标
     */
    public void placePowerGem(Location loc, UUID gemId) {
        if (getTotalGemCount() >= configManager.getRequiredCount()) {
            languageManager.logMessage("gem_limit_reached");
            return;
        }
        Block block = loc.getBlock();
        // 如果位置为solid，提高y值
        if (block.getType().isSolid()) {
            loc.add(0, 1, 0);
        }
        if (!isSafeLocation(loc)) {
            loc = findSafeLocationAround(loc, 10, 10);
        }
        if (loc == null) {
            randomPlaceGem(gemId, configManager.getRandomPlaceCorner1(), configManager.getRandomPlaceCorner2());
        }
        // getConsoleSender().sendMessage(ChatColor.GREEN + "放置了一个宝石方块在 " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
        loc.getBlock().setType(configManager.getGemMaterial());
//        Map<String, String> placeholders = new HashMap<>();
//        placeholders.put("x", String.valueOf(loc.getBlockX()));
//        placeholders.put("y", String.valueOf(loc.getBlockY()));
//        placeholders.put("z", String.valueOf(loc.getBlockZ()));
//        languageManager.logMessage("place_gem", placeholders);
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
     * 在指定范围内随机放置一个宝石方块
     */
    /**
     * 随机放置指定数量的宝石。
     */
    private void randomPlaceGem(UUID gemId, Location corner1, Location corner2) {
        Location loc = findSafeLocation(corner1, corner2, 10);
        if (loc != null) {
            placePowerGem(loc, gemId);
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
            plugin.getLogger().warning("两个角落的世界不一致，无法选择位置。");
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

            // 检查该位置是否适合放置宝石
            if (isSafeLocation(loc)) {
                return loc;
            }

            attempts++;
        }
        return null;
    }

    public void startParticleEffectTask(Particle particle) {
        // 使用调度器工具类替换BukkitRunnable
        SchedulerUtils.runGlobalTask(plugin, () -> {
            for (Location loc : locationToGemUuid.keySet()) {
                World world = loc.getWorld();
                if (world == null) continue;

                // 使用调度器工具类的粒子生成方法
                SchedulerUtils.spawnParticle(world, particle, loc, 1);
            }
        }, 0L, 20L); // 每秒更新一次粒子
    }

    /**
     * 实际的检测逻辑
     */
    public void checkPlayersNearPowerGems() {
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
                            configManager.getGemSound(),
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
     * 判断当前所有的"特殊方块"是否都在 (0,60,0) 为中心、半径5 的**立方体**范围内
     */
    private boolean checkWinCondition() {
        if (locationToGemUuid.size() < configManager.getRequiredCount()) {
            return false;
        }
        if(configManager.isUseRequiredLoc()) {
            for (Location loc : locationToGemUuid.keySet()) {
                if (!isInCube(loc, configManager.getRequiredLocCenter(), configManager.getRequiredLocRadius())) {
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
        Location start = locations.iterator().next();
        int visitedCount = bfsCount(start, locations);
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

            // 6 个方向的邻居
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
     * 这里用"绝对值 <= 半径"来判断
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

    /** 收回权限 */
    public void revokePermission(CommandSender sender) {
        if (this.powerPlayer == null) {
            languageManager.sendMessage(sender, "command.revoke.no_power_player");
            return;
        }
        ExecuteConfig revokeExecute = configManager.getPowerRevokeExecute();
        effectUtils.executeCommands(revokeExecute, Collections.singletonMap("%player%", this.powerPlayer.getName()));
        effectUtils.playGlobalSound(revokeExecute, 1.0f, 1.0f);
        if (this.powerPlayer.isOnline()) {
            effectUtils.playParticle(this.powerPlayer.getLocation(), revokeExecute);
            languageManager.sendMessage(this.powerPlayer, "command.revoke.success", Collections.singletonMap("player", this.powerPlayer.getName()));
        }
        this.powerPlayer = null;
        saveGems();
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
        if (!isPowerGem(block)) {
            return null;
        }

        Location loc = block.getLocation();
        return locationToGemUuid.get(loc);
    }

    public Player getPowerPlayer() {
        return powerPlayer;
    }
}
