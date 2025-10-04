package me.hushu.manager;

// import java.util.ArrayList; // no longer used
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import me.hushu.RulerGem;
import me.hushu.model.ExecuteConfig;
import me.hushu.utils.EffectUtils;
import me.hushu.utils.SchedulerUtil;

public class GemManager {
    private final RulerGem plugin;
    private final ConfigManager configManager;
    private final EffectUtils effectUtils;
    private final LanguageManager languageManager;

    private final NamespacedKey rulerGemKey;
    private final NamespacedKey uniqueIdKey;
    private final NamespacedKey gemKeyKey;
    private final Map<Location, UUID> locationToGemUuid = new HashMap<>();
    private final Map<UUID, Player> gemUuidToHolder = new HashMap<>();
    private final Map<UUID, String> gemUuidToKey = new HashMap<>();
    private final Set<String> redeemedGemKeys = new HashSet<>();
    private final Map<java.util.UUID, java.util.Set<String>> playerUuidToRedeemedKeys = new HashMap<>();
    // Separate attachments: inventory-based vs redeem-based
    private final Map<java.util.UUID, org.bukkit.permissions.PermissionAttachment> invAttachments = new HashMap<>();
    private final Map<java.util.UUID, org.bukkit.permissions.PermissionAttachment> redeemAttachments = new HashMap<>();
    // Redeem ownership state
    private final Map<String, java.util.UUID> gemKeyToRedeemer = new HashMap<>();
    private java.util.UUID fullSetOwner = null;

    private Player powerPlayer;

    public GemManager(RulerGem plugin, ConfigManager configManager, EffectUtils effectUtils,
                      LanguageManager languageManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.effectUtils = effectUtils;
        this.languageManager = languageManager;
        this.rulerGemKey = new NamespacedKey(plugin, "power_gem");
        this.uniqueIdKey = new NamespacedKey(plugin, "unique_id");
        this.gemKeyKey = new NamespacedKey(plugin, "gem_key");
    }

    @EventHandler
    public void onGemDamage(BlockDamageEvent event) {
        Block block = event.getBlock();
        if (block == null) return;
        // 该方块登记为宝石：忽略材质硬度，允许一击破坏
        if (locationToGemUuid.containsKey(block.getLocation())) {
            event.setInstaBreak(true);
        }
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
        // 兼容旧键名 "placed-gams" 的笔误
        ConfigurationSection placedGemsSection = gemsData.getConfigurationSection("placed-gems");
        if (placedGemsSection == null) {
            placedGemsSection = gemsData.getConfigurationSection("placed-gams");
        }

        // 读取每位玩家已兑换的 gem key 列表
        ConfigurationSection redeemedSection = gemsData.getConfigurationSection("redeemed");
        if (redeemedSection != null) {
            for (String playerUuidStr : redeemedSection.getKeys(false)) {
                java.util.UUID pu = java.util.UUID.fromString(playerUuidStr);
                java.util.List<String> list = redeemedSection.getStringList(playerUuidStr);
                if (list != null) {
                    playerUuidToRedeemedKeys.put(pu, new java.util.HashSet<>(list));
                }
            }
        }
        ConfigurationSection ownerSec = gemsData.getConfigurationSection("redeem_owner");
        if (ownerSec != null) {
            for (String gemKey : ownerSec.getKeys(false)) {
                String uuidStr = ownerSec.getString(gemKey);
                try { gemKeyToRedeemer.put(gemKey.toLowerCase(), java.util.UUID.fromString(uuidStr)); } catch (Exception ignored) {}
            }
        }
        ConfigurationSection fso = gemsData.getConfigurationSection("full_set_owner");
        if (fso != null) {
            String u = fso.getString("uuid");
            try { fullSetOwner = java.util.UUID.fromString(u); } catch (Exception ignored) {}
        }
        if (placedGemsSection != null) {
            for (String uuidStr : placedGemsSection.getKeys(false)) {
                // key 类似 "gems.<uuid>"
                String worldName = placedGemsSection.getString(uuidStr + ".world");
                double x = placedGemsSection.getDouble(uuidStr + ".x");
                double y = placedGemsSection.getDouble(uuidStr + ".y");
                double z = placedGemsSection.getDouble(uuidStr + ".z");
                String gemKey = placedGemsSection.getString(uuidStr + ".gem_key", "default");

                World w = Bukkit.getWorld(worldName);
                if (w == null) {
                    continue;
                }

                Location loc = new Location(w, x, y, z);
                UUID gemId;
                try {
                    gemId = UUID.fromString(uuidStr);
                } catch (Exception ignored) {
                    continue;
                }

                locationToGemUuid.put(loc, gemId);
                gemUuidToKey.put(gemId, gemKey);
            }
        }

        // 持有的宝石
        ConfigurationSection heldGemsSection = gemsData.getConfigurationSection("held-gems");
        if (heldGemsSection != null) {
            for (String uuidStr : heldGemsSection.getKeys(false)) {
                // key 就是uuid
                String playerUUIDStr = heldGemsSection.getString(uuidStr + ".player_uuid");
                String gemKey = heldGemsSection.getString(uuidStr + ".gem_key", "default");

                if (playerUUIDStr == null) {
                    continue;
                }
                UUID playerUUID;
                try { playerUUID = UUID.fromString(playerUUIDStr); } catch (Exception ignored) { continue; }
                UUID gemId = UUID.fromString(uuidStr);
                Player player = Bukkit.getPlayer(playerUUID);
                if (player == null) {
                    continue;
                }
                if (!player.isOnline()) {
                    // remove from player's inventory
                    Inventory inv = player.getInventory();
                    for (ItemStack item : inv.getContents()) {
                        if (isRulerGem(item) && getGemUUID(item).equals(gemId)) {
                            inv.remove(item);
                        }
                    }
                    gemUuidToKey.put(gemId, gemKey);
                    placeRulerGem(player.getLocation(), gemId);
                } else {
                    gemUuidToHolder.put(gemId, player);
                    gemUuidToKey.put(gemId, gemKey);
                }
            }
        }

        // Log loaded counts (match lang keys expecting %count%)
        Map<String, String> placeholders1 = new HashMap<>();
        placeholders1.put("count", String.valueOf(locationToGemUuid.size()));
        languageManager.logMessage("gems_loaded", placeholders1);
        Map<String, String> placeholders2 = new HashMap<>();
        placeholders2.put("count", String.valueOf(gemUuidToHolder.size()));
        languageManager.logMessage("gems_held_loaded", placeholders2);
    }

    public void saveGems() {
        FileConfiguration gemsData = configManager.getGemsData();
        gemsData.set("placed-gems", null);
        gemsData.set("held-gems", null);
        gemsData.set("power_player", null);
        gemsData.set("redeemed", null);
        gemsData.set("redeem_owner", null);
        gemsData.set("full_set_owner", null);

        for (Location loc : locationToGemUuid.keySet()) {
            String path = "placed-gems." + locationToGemUuid.get(loc).toString();
            gemsData.set(path + ".world", loc.getWorld().getName());
            gemsData.set(path + ".x", loc.getX());
            gemsData.set(path + ".y", loc.getY());
            gemsData.set(path + ".z", loc.getZ());
            gemsData.set(path + ".gem_key", gemUuidToKey.get(locationToGemUuid.get(loc)));
//            gemsData.set(path + ".uuid", locationToGemUuid.get(loc).toString());
        }
        for (UUID gemId : gemUuidToHolder.keySet()) {
            Player player = gemUuidToHolder.get(gemId);
            String path = "held-gems." + gemId.toString();
            gemsData.set(path + ".player", player.getName());
            gemsData.set(path + ".player_uuid", player.getUniqueId().toString());
            gemsData.set(path + ".gem_key", gemUuidToKey.get(gemId));
//            gemsData.set(path + ".uuid", gemId.toString());
        }
        // 保存powerPlayer数据
        if (this.powerPlayer != null) {
            gemsData.set("power_player.uuid", this.powerPlayer.getUniqueId().toString());
        }
        // 保存每位玩家已兑换的 gem key
        for (Map.Entry<java.util.UUID, java.util.Set<String>> e : playerUuidToRedeemedKeys.entrySet()) {
            String base = "redeemed." + e.getKey().toString();
            gemsData.set(base, new java.util.ArrayList<>(e.getValue()));
        }
        for (Map.Entry<String, java.util.UUID> e : gemKeyToRedeemer.entrySet()) {
            gemsData.set("redeem_owner." + e.getKey(), e.getValue().toString());
        }
        if (fullSetOwner != null) {
            gemsData.set("full_set_owner.uuid", fullSetOwner.toString());
        }
        configManager.saveGemData(gemsData);
    }

    /**
     * 确保配置中定义的每一颗 gem 至少存在一颗（放置或持有）。
     * 若缺失，则为该定义生成新的 UUID 并随机放置。
     */
    public void ensureConfiguredGemsPresent() {
        List<me.hushu.model.GemDefinition> defs = configManager.getGemDefinitions();
        if (defs == null || defs.isEmpty()) return;
        // 已存在的 gemKey 集合（来自放置与持有）
        java.util.Set<String> presentKeys = new java.util.HashSet<>();
        for (java.util.UUID id : gemUuidToKey.keySet()) {
            String k = gemUuidToKey.get(id);
            if (k != null) presentKeys.add(k.toLowerCase());
        }
        // 如果有遗漏，则补齐
        for (me.hushu.model.GemDefinition d : defs) {
            String k = d.getGemKey();
            if (k == null) continue;
            if (!presentKeys.contains(k.toLowerCase())) {
                java.util.UUID newId = java.util.UUID.randomUUID();
                gemUuidToKey.put(newId, k);
                randomPlaceGem(newId, configManager.getRandomPlaceCorner1(), configManager.getRandomPlaceCorner2());
            }
        }
    }

    @EventHandler
    public void onGemPlaced(BlockPlaceEvent event) {
        // 放置宝石只更新位置，不触发授予权限
        ItemStack inHand = event.getItemInHand();

        // 判断这个 block 是否是我们的"特殊方块"
        if (!isRulerGem(inHand)) {
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

        // 不再通过放置触发权限或胜利逻辑
    }

    @EventHandler
    public void onGemBroken(BlockBreakEvent event) {
        Block block = event.getBlock();

        // 如果这个坐标存在于 locationToGemUuid，说明是宝石方块
        if (locationToGemUuid.containsKey(block.getLocation())) {
            // 阻止原始方块材料的默认掉落，避免重复获取材料
            event.setDropItems(false);
            try { event.setExpToDrop(0); } catch (Throwable ignored) {}
            // 先取到UUID
            UUID gemId = locationToGemUuid.get(block.getLocation());
            Player player = event.getPlayer();
            Inventory inv = player.getInventory();
//            Map<String, String> placeholders = new HashMap<>();
//            placeholders.put("slot", String.valueOf(inv.firstEmpty()));
//            languageManager.logMessage("inventory_full", placeholders);
            if (inv.firstEmpty() == -1) {
                // 背包已满：取消破坏，保留原位置上的宝石，不生成任何掉落实体
                languageManager.logMessage("inventory_full");
                event.setCancelled(true);
                return;
            } else {
                // 给破坏者：若开启“背包即生效”，直接入包；否则仍可入包但权限不会因背包自动授予
                ItemStack gemItem = createRulerGem(gemId);
                inv.addItem(gemItem);
                gemUuidToHolder.put(gemId, player);
                unplaceRulerGem(block.getLocation(), gemId);
                // 每颗宝石自定义拾取效果（可选），否则用全局（此处是破坏方块后“入包”的瞬间）
                me.hushu.model.GemDefinition def = findGemDefinition(gemUuidToKey.get(gemId));
                if (def != null && def.getOnPickup() != null) {
                    effectUtils.executeCommands(def.getOnPickup(), Collections.singletonMap("%player%", player.getName()));
                    effectUtils.playLocalSound(player.getLocation(), def.getOnPickup(), 1.0f, 1.0f);
                    effectUtils.playParticle(player.getLocation(), def.getOnPickup());
                }
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Inventory inv = player.getInventory();

        for (ItemStack item : inv.getContents()) {
            if (isRulerGem(item)) {
                // 拿到这颗宝石的UUID
                UUID gemId = getGemUUID(item);

                // 移除背包物品
                inv.remove(item);

                // 在玩家脚下放置方块
                Location loc = player.getLocation();
                
                gemUuidToHolder.remove(gemId);
                placeRulerGem(loc, gemId);
            }
        }
    }

    @EventHandler
    // 丢弃将自动放置在地面
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        UUID gemId = getGemUUID(item);
        if (isRulerGem(item)) {
            // 确保 Gem 只能作为实体存在于地面
            // 如果有其他逻辑需求，可以在这里处理
            event.getItemDrop().remove();
            gemUuidToHolder.remove(gemId);
            Location loc = event.getItemDrop().getLocation();
            triggerScatterEffects(gemId, loc, event.getPlayer().getName());
            placeRulerGem(loc, gemId);
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
            if (isRulerGem(item)) {
                UUID gemId = getGemUUID(item);
                inv.remove(item);
                gemUuidToHolder.remove(gemId);

                Location deathLocation = player.getLocation();

                if (deathLocation != null) {
                    triggerScatterEffects(gemId, deathLocation, player.getName());
                    // 直接在死亡位置尝试放置（region-safe），内部会垂直校正并在必要时回退到随机散落
                    placeRulerGem(deathLocation, gemId);
                }
            }
        }
    }

    // onPlayerJoin
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Inventory inv = player.getInventory();
        for (ItemStack item : inv.getContents()) {
            if (isRulerGem(item)) {
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
        int scatteredCount = 0;
        // 1) 取消世界中的所有已放置宝石
        Set<Location> locCopy = new HashSet<>(locationToGemUuid.keySet());
        for (Location loc : locCopy) {
            unplaceRulerGem(loc, locationToGemUuid.get(loc));
        }
        // 清空坐标-宝石映射，确保后续重新计算
        locationToGemUuid.clear();

        // 2) 收回所有玩家背包中的宝石，并清空"持有映射"
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory inv = player.getInventory();
            for (ItemStack item : inv.getContents()) {
                if (isRulerGem(item)) {
                    inv.remove(item);
                }
            }
        }
        gemUuidToHolder.clear();

        // 3) 清理旧的 UUID->gemKey 关系，避免历史残留
        gemUuidToKey.clear();

        languageManager.logMessage("gems_recollected");
        List<me.hushu.model.GemDefinition> defs = configManager.getGemDefinitions();
        if (defs != null && !defs.isEmpty()) {
            scatteredCount = defs.size();
            // 每个定义生成一颗对应类型的宝石
            for (me.hushu.model.GemDefinition def : defs) {
                UUID gemId = UUID.randomUUID();
                gemUuidToKey.put(gemId, def.getGemKey());
                randomPlaceGem(gemId, configManager.getRandomPlaceCorner1(), configManager.getRandomPlaceCorner2());
                // 若定义了散落效果，尽量在放置地点播放
                if (def.getOnScatter() != null) {
                    Location placedLoc = findLocationByGemId(gemId);
                    triggerScatterEffects(gemId, placedLoc, null, false);
                }
            }
        } else {
            // 没有 per-gem 定义，按数量生成（向后兼容）
            int toPlace = Math.max(0, configManager.getRequiredCount());
            scatteredCount = toPlace;
            for (int i = 0; i < toPlace; i++) {
                UUID gemId = UUID.randomUUID();
                randomPlaceGem(gemId, configManager.getRandomPlaceCorner1(), configManager.getRandomPlaceCorner2());
            }
        }
        // 实际散落数量以当前已放置统计为准
        Map<String, String> placeholders = new HashMap<>();
        // 使用计划散落数量，避免 Folia 下区域任务尚未完成导致的统计偏差
        placeholders.put("count", String.valueOf(scatteredCount));
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
        // 输出总览
        Map<String, String> summary = new HashMap<>();
        summary.put("count", String.valueOf(configManager.getRequiredCount()));
        summary.put("placed_count", String.valueOf(locationToGemUuid.size()));
        summary.put("held_count", String.valueOf(gemUuidToHolder.size()));
        languageManager.sendMessage(sender, "gem_status.total_expected", summary);
        languageManager.sendMessage(sender, "gem_status.total_counts", summary);

        // 逐颗宝石列出：[gem_key] gem_name: 被<持有人>持有 / 放置在<x,y,z world>
        // 遍历已知的全部宝石（以 gemUuidToKey 为准）
        java.util.List<java.util.Map.Entry<java.util.UUID, String>> entries = new java.util.ArrayList<>(gemUuidToKey.entrySet());
        // 稳定排序：按 gem_key 再按 UUID
        entries.sort((a, b) -> {
            String ka = a.getValue() != null ? a.getValue().toLowerCase() : "";
            String kb = b.getValue() != null ? b.getValue().toLowerCase() : "";
            int c = ka.compareTo(kb);
            if (c != 0) return c;
            return a.getKey().toString().compareTo(b.getKey().toString());
        });

        boolean isPlayerSender = sender instanceof Player;
        for (java.util.Map.Entry<java.util.UUID, String> ent : entries) {
            java.util.UUID gemId = ent.getKey();
            String gemKey = ent.getValue();
            me.hushu.model.GemDefinition def = gemKey != null ? findGemDefinition(gemKey) : null;
            String displayName = def != null && def.getDisplayName() != null ? def.getDisplayName() : "Gem";

            // 构造状态
            String statusText;
            Player holder = gemUuidToHolder.get(gemId);
            Location loc = findLocationByGemId(gemId);
            if (holder != null && holder.isOnline()) {
                Map<String, String> ph = new HashMap<>();
                ph.put("player", holder.getName());
                statusText = languageManager.formatMessage("messages.gem_status.status_held", ph);
            } else if (loc != null) {
                String worldName = (loc.getWorld() != null ? loc.getWorld().getName() : "world");
                Map<String, String> ph = new HashMap<>();
                ph.put("x", String.valueOf(loc.getBlockX()));
                ph.put("y", String.valueOf(loc.getBlockY()));
                ph.put("z", String.valueOf(loc.getBlockZ()));
                ph.put("world", worldName);
                statusText = languageManager.formatMessage("messages.gem_status.status_placed", ph);
            } else {
                statusText = languageManager.formatMessage("messages.gem_status.status_unknown", java.util.Collections.emptyMap());
            }

            Map<String, String> linePh = new HashMap<>();
            linePh.put("gem_key", gemKey != null ? gemKey : "unknown");
            linePh.put("gem_name", displayName);
            linePh.put("status", statusText);
            String plain = languageManager.formatMessage("messages.gem_status.gem_line", linePh);

            // 组件：hover 显示 lore；click 传送
            if (isPlayerSender) {
                Player ps = (Player) sender;
                net.md_5.bungee.api.chat.TextComponent comp = new net.md_5.bungee.api.chat.TextComponent(org.bukkit.ChatColor.translateAlternateColorCodes('&', plain));

                // Hover lore 构建
                StringBuilder loreBuilder = new StringBuilder();
                if (def != null && def.getLore() != null && !def.getLore().isEmpty()) {
                    for (String line : def.getLore()) {
                        String l = org.bukkit.ChatColor.translateAlternateColorCodes('&', line);
                        loreBuilder.append(l).append("\n");
                    }
                } else {
                    loreBuilder.append(org.bukkit.ChatColor.GRAY).append("没有更多信息");
                }
        // Prefer content-based HoverEvent to avoid deprecation
        net.md_5.bungee.api.chat.hover.content.Text text = new net.md_5.bungee.api.chat.hover.content.Text(loreBuilder.toString().trim());
        comp.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
            net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
            text
        ));

                // Click 行为：/rulergem tp <uuid>
                String clickCmd = "/rulergem tp " + gemId.toString();
                comp.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                        net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND,
                        clickCmd
                ));
                ps.spigot().sendMessage(comp);
            } else {
                // 非玩家：纯文本输出
                sender.sendMessage(org.bukkit.ChatColor.stripColor(org.bukkit.ChatColor.translateAlternateColorCodes('&', plain)));
            }
        }
    }

    /**
     * 判断物品是否为权力宝石
     */
    public boolean isRulerGem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(rulerGemKey, PersistentDataType.BYTE);
    }

    /**
     * 判断方块是否为放置了的权力宝石
     */
    private boolean isRulerGem(Block block) {
        // 判断 block 是否是为权力宝石（使用方块坐标作为键）
        Location loc = block.getLocation();
        return locationToGemUuid.containsKey(loc);
    }

    /**
     * 根据指定的 UUID，创建一颗"权力宝石"物品。
     * @param gemId 这颗宝石的专属 UUID
     */
    public ItemStack createRulerGem(UUID gemId) {
        // 根据 gemKey 决定外观，若无则回退到全局默认
        String gemKey = gemUuidToKey.getOrDefault(gemId, null);
        ItemStack rulerGem = new ItemStack(Material.RED_STAINED_GLASS, 1);
        if (gemKey != null) {
            me.hushu.model.GemDefinition def = findGemDefinition(gemKey);
            if (def != null) {
                rulerGem = new ItemStack(def.getMaterial(), 1);
            }
        }
        ItemMeta meta = rulerGem.getItemMeta();
        if (meta == null) return rulerGem;
        
        // 名称按定义或默认
        String displayName = "§c权力宝石";
        if (gemKey != null) {
            me.hushu.model.GemDefinition def = findGemDefinition(gemKey);
            if (def != null && def.getDisplayName() != null) {
                displayName = def.getDisplayName();
            }
            // lore
            if (def != null && def.getLore() != null && !def.getLore().isEmpty()) {
                java.util.List<String> lore = new java.util.ArrayList<>();
                for (String line : def.getLore()) {
                    lore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', line));
                }
                meta.setLore(lore);
            }
        }
        meta.setDisplayName(displayName);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        // 标记这是宝石
        pdc.set(rulerGemKey, PersistentDataType.BYTE, (byte) 1);
        // 写入UUID & gemKey
        pdc.set(uniqueIdKey, PersistentDataType.STRING, gemId.toString());
        if (gemKey != null) {
            pdc.set(gemKeyKey, PersistentDataType.STRING, gemKey);
        }

        rulerGem.setItemMeta(meta);
        return rulerGem;
    }

    private me.hushu.model.GemDefinition findGemDefinition(String key) {
        for (me.hushu.model.GemDefinition d : configManager.getGemDefinitions()) {
            if (d.getGemKey().equalsIgnoreCase(key)) return d;
        }
        return null;
    }

    private void ensureGemKeyAssigned(UUID gemId) {
        if (gemUuidToKey.containsKey(gemId)) return;
        List<me.hushu.model.GemDefinition> defs = configManager.getGemDefinitions();
        if (defs == null || defs.isEmpty()) return;
        String key = defs.get(new Random().nextInt(defs.size())).getGemKey();
        gemUuidToKey.put(gemId, key);
    }

    private void grantPermissions(Player player, List<String> perms) {
        org.bukkit.permissions.PermissionAttachment attachment = redeemAttachments.computeIfAbsent(player.getUniqueId(), p -> player.addAttachment(plugin));
        for (String node : perms) {
            if (node == null || node.trim().isEmpty()) continue;
            attachment.setPermission(node, true);
        }
        player.recalculatePermissions();
    }

    // 兑换场景：除附件外，若存在 Vault 权限后端（如 LuckPerms），同时向后端添加永久节点
    private void grantRedeemPermissions(Player player, List<String> perms) {
        if (perms == null || perms.isEmpty()) return;
        // 先本地附件生效（即时）
        grantPermissions(player, perms);
        // 再持久化到权限后端
        if (plugin.getVaultPerms() != null) {
            for (String node : perms) {
                if (node == null || node.trim().isEmpty()) continue;
                try { plugin.getVaultPerms().playerAdd(player, node); } catch (Exception ignored) {}
            }
        }
    }

    public ConfigManager getConfigManager() { return configManager; }

    public void recalculateGrants(Player player) {
        if (!configManager.isInventoryGrantsEnabled()) return;
        java.util.Set<String> shouldHave = new java.util.HashSet<>();
        // 由背包持有的 gem key 决定的权限节点集合
        Inventory inv = player.getInventory();
        for (ItemStack item : inv.getContents()) {
            if (!isRulerGem(item)) continue;
            UUID id = getGemUUID(item);
            String key = gemUuidToKey.get(id);
            if (key == null) continue;
            me.hushu.model.GemDefinition def = findGemDefinition(key);
            if (def == null) continue;
            if (def.getPermissions() != null) {
                for (String node : def.getPermissions()) {
                    if (node != null && !node.trim().isEmpty()) shouldHave.add(node);
                }
            }
        }
        org.bukkit.permissions.PermissionAttachment attachment = invAttachments.computeIfAbsent(player.getUniqueId(), p -> player.addAttachment(plugin));
        // 现有的权限节点
        java.util.Set<String> current = new java.util.HashSet<>(attachment.getPermissions().keySet());
        // 需要新增
        for (String node : shouldHave) {
            if (!current.contains(node)) attachment.setPermission(node, true);
        }
        // 需要移除
        for (String node : current) {
            if (!shouldHave.contains(node)) attachment.unsetPermission(node);
        }
        player.recalculatePermissions();
    }

    /**
     * 启动后根据已加载的 locationToGemUuid，在世界中恢复方块显示。
     * 不进行数量限制或位置调整，仅按定义材质设置方块类型。
     */
    public void initializePlacedGemBlocks() {
        for (Map.Entry<Location, UUID> e : locationToGemUuid.entrySet()) {
            Location loc = e.getKey();
            UUID gemId = e.getValue();
            World w = loc.getWorld();
            if (w == null) continue;
            try {
                String key = gemUuidToKey.get(gemId);
                Material mat = Material.RED_STAINED_GLASS;
                if (key != null) {
                    me.hushu.model.GemDefinition def = findGemDefinition(key);
                    if (def != null && def.getMaterial() != null) mat = def.getMaterial();
                }
                final Material m = mat;
                final Location f = loc;
                SchedulerUtil.regionRun(plugin, f, () -> {
                    try {
                        if (!f.getChunk().isLoaded()) {
                            f.getChunk().load();
                        }
                        f.getBlock().setType(m);
                    } catch (Exception ignored2) {}
                }, 0L, -1L);
            } catch (Exception ignored) {}
        }
    }

    private void revokeNodes(Player player, java.util.Collection<String> nodes) {
        org.bukkit.permissions.PermissionAttachment i = invAttachments.get(player.getUniqueId());
        if (i != null) {
            for (String n : nodes) {
                try { i.unsetPermission(n); } catch (Exception ignored) {}
            }
        }
        org.bukkit.permissions.PermissionAttachment r = redeemAttachments.get(player.getUniqueId());
        if (r != null) {
            for (String n : nodes) {
                try { r.unsetPermission(n); } catch (Exception ignored) {}
            }
        }
        // 同步撤销权限后端中的永久节点（若存在）
        if (plugin.getVaultPerms() != null) {
            for (String n : nodes) {
                try { plugin.getVaultPerms().playerRemove(player, n); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 根据locationToGemUuid里的数据，初始化宝石方块
     */
//    public void setGems() {
//        for (Location loc : locationToGemUuid.keySet()) {
//            placeRulerGem(loc, locationToGemUuid.get(loc));
//        }
//        // 检查所有在线玩家，如果背包里有宝石，设置宝石材质
//        // 刚开机应该不会有
//        for (Player player : Bukkit.getOnlinePlayers()) {
//            Inventory inv = player.getInventory();
//            for (ItemStack item : inv.getContents()) {
//                if (isRulerGem(item)) {
//                    placeRulerGem(player.getLocation(), getGemUUID(item));
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
    public void placeRulerGem(Location loc, UUID gemId) {
        placeRulerGemInternal(loc, gemId, false);
    }

    private void placeRulerGemInternal(Location loc, UUID gemId, boolean ignoreLimit) {
        if (loc == null) return;
        if (!ignoreLimit && getTotalGemCount() >= configManager.getRequiredCount()) {
            languageManager.logMessage("gem_limit_reached");
            return;
        }
        // 在目标区域线程执行所有方块操作，确保 Folia 兼容
        final Location base = loc.clone();
        SchedulerUtil.regionRun(plugin, base, () -> {
            World world = base.getWorld();
            if (world == null) return;
            WorldBorder border = world.getWorldBorder();
            Location target = base.getBlock().getLocation();
            // 垂直向上寻找空气（最多尝试 6 格），避免方块被覆盖
            int tries = 0;
            while (tries < 6 && target.getBlock().getType().isSolid()) {
                target.add(0, 1, 0);
                tries++;
            }
            // 基础安全性校验
            if (!border.isInside(target) || target.getBlockY() < world.getMinHeight() || target.getBlockY() > world.getMaxHeight()) {
                // 回退到随机散落（异步调度，每次在候选坐标区域线程执行）
                randomPlaceGem(gemId, configManager.getRandomPlaceCorner1(), configManager.getRandomPlaceCorner2());
                return;
            }
            // 设置材质并登记
            String gemKey = gemUuidToKey.getOrDefault(gemId, null);
            Material mat = Material.RED_STAINED_GLASS;
            if (gemKey != null) {
                me.hushu.model.GemDefinition def = findGemDefinition(gemKey);
                if (def != null && def.getMaterial() != null) {
                    mat = def.getMaterial();
                }
            }
            target.getBlock().setType(mat);
            locationToGemUuid.put(target, gemId);
        }, 0L, -1L);
    }

    /**
     * 如果方块被破坏了，也要移除坐标
     */
    public void unplaceRulerGem(Location loc, UUID gemId) {
        if (loc == null) return;
        final Location fLoc = loc.getBlock().getLocation();
        SchedulerUtil.regionRun(plugin, fLoc, () -> {
            fLoc.getBlock().setType(Material.AIR);
            locationToGemUuid.remove(fLoc, gemId);
        }, 0L, -1L);
    }

    /**
     * 在指定范围内随机放置一个宝石方块
     */
    /**
     * 随机放置指定数量的宝石。
     */
    private void randomPlaceGem(UUID gemId, Location corner1, Location corner2) {
        ensureGemKeyAssigned(gemId);
        scheduleRandomAttempt(gemId, corner1, corner2, 12);
    }

    // 在随机范围内尝试放置（Folia 安全）：每次选择一个候选 (x,z)，在该坐标区域线程中计算最高地面并放置
    private void scheduleRandomAttempt(UUID gemId, Location corner1, Location corner2, int attemptsLeft) {
        if (corner1 == null || corner2 == null || attemptsLeft <= 0) return;
        if (corner1.getWorld() != corner2.getWorld()) return;
        World world = corner1.getWorld();
        Random rand = new Random();
        int minX = Math.min(corner1.getBlockX(), corner2.getBlockX());
        int minZ = Math.min(corner1.getBlockZ(), corner2.getBlockZ());
        int maxX = Math.max(corner1.getBlockX(), corner2.getBlockX());
        int maxZ = Math.max(corner1.getBlockZ(), corner2.getBlockZ());
        int x = rand.nextInt(maxX - minX + 1) + minX;
        int z = rand.nextInt(maxZ - minZ + 1) + minZ;
        // 以世界最小高度作为占位 y，实际 y 在区域线程中计算
        final Location candidate = new Location(world, x, world.getMinHeight() + 1, z);
        SchedulerUtil.regionRun(plugin, candidate, () -> {
            try {
                int y = world.getHighestBlockYAt(x, z) + 1;
                Location place = new Location(world, x, y, z);
                WorldBorder border = world.getWorldBorder();
                if (!border.isInside(place)) {
                    if (attemptsLeft - 1 > 0) scheduleRandomAttempt(gemId, corner1, corner2, attemptsLeft - 1);
                    return;
                }
                // 最终放置
                placeRulerGem(place, gemId);
            } catch (Throwable t) {
                // 若计算失败，继续下一次尝试
                if (attemptsLeft - 1 > 0) scheduleRandomAttempt(gemId, corner1, corner2, attemptsLeft - 1);
            }
        }, 0L, -1L);
    }

    /**
     * 检查指定位置是否适合放置宝石。
     *
     * @param loc 需要检查的位置
     * @return 如果适合返回 true，否则返回 false
     */
    // 删除本地同步的安全性检测方法，安全检查在区域线程中处理

    /**
     * 找一个位置附近的安全位置。
     * @param startLoc 起始位置
     * @return 安全的位置，若未找到则返回 null
     */
    // 删除 Folia 不安全的寻找函数，改用 scheduleRandomAttempt

    public void startParticleEffectTask(Particle particle) {
        // Deprecated single-particle task retained for backward compatibility; now use per-gem particles
        SchedulerUtil.globalRun(plugin, () -> {
            for (Location loc : locationToGemUuid.keySet()) {
                Location target = loc;
                SchedulerUtil.regionRun(plugin, target, () -> {
                    World world = target.getWorld();
                    if (world == null) return;
                    // Per-gem particle
                    UUID id = locationToGemUuid.get(target);
                    me.hushu.model.GemDefinition def = id != null ? findGemDefinition(gemUuidToKey.get(id)) : null;
                    org.bukkit.Particle p = def != null && def.getParticle() != null ? def.getParticle() : particle;
                    world.spawnParticle(p, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 1);
                }, 0L, -1L);
            }
        }, 0L, 20L);
    }

    /**
     * 实际的检测逻辑
     */
    public void checkPlayersNearRulerGems() {
        if (locationToGemUuid.isEmpty()) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            checkPlayerNearRulerGems(player);
        }
    }

    /**
     * 检查某个玩家附近是否有宝石，并播放提示音。
     */
    public void checkPlayerNearRulerGems(Player player) {
        if (player == null || locationToGemUuid.isEmpty()) return;
        // 统一通过 SchedulerUtil 派发，内部已处理 Folia 与主线程
        SchedulerUtil.entityRun(plugin, player, () -> doPlayerNearCheck(player), 0L, -1L);
    }

    private void doPlayerNearCheck(Player player) {
        if (player == null) return;
        Location playerLoc = player.getLocation();
        World playerWorld = playerLoc.getWorld();
        if (playerWorld == null) return;
        for (Location blockLoc : locationToGemUuid.keySet()) {
            World w = blockLoc.getWorld();
            if (w == null || !w.equals(playerWorld)) continue;
            double distance = playerLoc.distance(blockLoc);
            if (distance < 16.0) {
                float volume = (float) (1.0 - (distance / 16.0));
                float pitch = 1.0f;
                player.playSound(playerLoc, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, volume, pitch);
            }
        }
    }

    /**
     * 判断当前所有的"特殊方块"是否都在 (0,60,0) 为中心、半径5 的**立方体**范围内
     */
    // 玩法已调整：相关方法移除，避免误用

    /**
     * 判断指定集合中的方块是否连成一个整体（面邻接，6个方向）。
     * @param locations 存放所有方块坐标的集合
     * @return 若所有方块连通则返回 true，否则 false
     */
    // 玩法调整后不再需要该方法

    /**
     * 从起始坐标 start 对集合 locations 做 BFS，返回能访问到的方块数量
     */
    // 玩法调整后不再需要该方法

    /**
     * 获取某方块坐标在 6 个方向上的邻居
     */
    // 玩法调整后不再需要该方法

    private Location findLocationByGemId(UUID gemId) {
        for (Map.Entry<Location, UUID> e : locationToGemUuid.entrySet()) {
            if (e.getValue().equals(gemId)) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * 公共获取：根据宝石 UUID 返回其放置坐标（若未放置则为 null）。
     */
    public Location getGemLocation(UUID gemId) {
        return findLocationByGemId(gemId);
    }

    /**
     * 公共获取：根据宝石 UUID 返回其当前持有人（若无人持有则为 null）。
     */
    public Player getGemHolder(UUID gemId) {
        return gemUuidToHolder.get(gemId);
    }

    /**
     * 判断某个坐标是否在以 center 为中心、边长=半径*2+1 的立方体内
     * 这里用"绝对值 <= 半径"来判断
     */
    // 玩法调整后不再需要该方法

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
        if (!isRulerGem(item)) {
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
        if (!isRulerGem(block)) {
            return null;
        }

        Location loc = block.getLocation();
        return locationToGemUuid.get(loc);
    }

    public Player getPowerPlayer() {
        return powerPlayer;
    }

    // 兑换单颗宝石：要求玩家背包里持有该 gemKey 对应的宝石
    public boolean redeemGem(Player player, String gemKeyOrName) {
        if (!configManager.isRedeemEnabled()) {
            languageManager.sendMessage(player, "command.redeem.disabled");
            return true;
        }
        // 允许以 key 或显示名匹配
        String targetKey = resolveGemKeyByNameOrKey(gemKeyOrName);
        if (targetKey == null) {
            return false;
        }
        // 检查玩家是否持有该类型的宝石
        UUID matchedGemId = null;
        for (ItemStack item : player.getInventory().getContents()) {
            if (!isRulerGem(item)) continue;
            UUID id = getGemUUID(item);
            String key = gemUuidToKey.get(id);
            if (key != null && key.equalsIgnoreCase(targetKey)) {
                matchedGemId = id;
                break;
            }
        }
        if (matchedGemId == null) {
            return false;
        }
        // 记为已兑换（示例：记录 key，实际授权逻辑可在此处挂接）
        markGemRedeemed(player, targetKey);
        me.hushu.model.GemDefinition def = findGemDefinition(targetKey);
        applyRedeemRewards(player, def);
    // 兑换后：从玩家身上移除并随机散落（精确移除该UUID的宝石，避免残留导致重复）
    removeGemItemFromInventory(player, matchedGemId);
    gemUuidToHolder.remove(matchedGemId);
    randomPlaceGem(matchedGemId, configManager.getRandomPlaceCorner1(), configManager.getRandomPlaceCorner2());
        // 单颗竞争撤销：新兑换者成功后撤销旧兑换者的该 gem 权限
        String normalizedKey = targetKey.toLowerCase(Locale.ROOT);
        java.util.UUID old = gemKeyToRedeemer.put(normalizedKey, player.getUniqueId());
        if (old != null && !old.equals(player.getUniqueId())) {
            Player oldP = Bukkit.getPlayer(old);
            if (oldP != null && oldP.isOnline()) {
                if (def != null && def.getPermissions() != null) revokeNodes(oldP, def.getPermissions());
                if (def != null && def.getVaultGroup() != null && !def.getVaultGroup().isEmpty() && plugin.getVaultPerms() != null) {
                    try { plugin.getVaultPerms().playerRemoveGroup(oldP, def.getVaultGroup()); } catch (Exception ignored) {}
                }
                oldP.recalculatePermissions();
            } else {
                java.util.Set<String> s = playerUuidToRedeemedKeys.get(old);
                if (s != null) s.remove(normalizedKey);
            }
        }
        // 广播标题（可配置开关）
        if (configManager.isBroadcastRedeemTitle()) {
            java.util.Map<String, String> ph = new java.util.HashMap<>();
            ph.put("player", player.getName());
            ph.put("gem", def != null ? (def.getDisplayName() != null ? def.getDisplayName() : targetKey) : targetKey);
            java.util.List<String> title = def != null ? def.getRedeemTitle() : null;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (title != null && !title.isEmpty()) {
                    if (title.size() == 1) {
                        p.sendTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&', languageManager.formatText(title.get(0), java.util.Collections.singletonMap("player", player.getName()))), null, 10, 70, 20);
                    } else {
                        String l1 = languageManager.formatText(title.get(0), ph);
                        String l2 = languageManager.formatText(title.get(1), ph);
                        p.sendTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&', l1), org.bukkit.ChatColor.translateAlternateColorCodes('&', l2), 10, 70, 20);
                    }
                } else {
                    // fallback to language title if present
                    languageManager.showTitle(p, "gems_scattered", java.util.Collections.singletonMap("count", String.valueOf(1)));
                }
            }
        }
        return true;
    }

    /**
     * 从玩家背包（含副手）精确移除一颗指定 UUID 的宝石物品。
     */
    private void removeGemItemFromInventory(Player player, UUID targetId) {
        if (player == null || targetId == null) return;
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        ItemStack off = inv.getItemInOffHand();
        if (isRulerGem(off)) {
            UUID id = getGemUUID(off);
            if (targetId.equals(id)) {
                inv.setItemInOffHand(new ItemStack(Material.AIR));
                return;
            }
        }
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (!isRulerGem(it)) continue;
            UUID id = getGemUUID(it);
            if (targetId.equals(id)) {
                inv.setItem(i, new ItemStack(Material.AIR));
                break;
            }
        }
    }

    private void markGemRedeemed(Player player, String gemKey) {
        if (player == null || gemKey == null || gemKey.isEmpty()) {
            return;
        }
        String normalizedKey = gemKey.toLowerCase(Locale.ROOT);
        redeemedGemKeys.add(normalizedKey);
        playerUuidToRedeemedKeys.computeIfAbsent(player.getUniqueId(), u -> new HashSet<>()).add(normalizedKey);
    }

    private void applyRedeemRewards(Player player, me.hushu.model.GemDefinition definition) {
        if (player == null || definition == null) {
            return;
        }
        ExecuteConfig onRedeem = definition.getOnRedeem();
        if (onRedeem != null) {
            Map<String, String> placeholders = java.util.Map.of("%player%", player.getName());
            effectUtils.executeCommands(onRedeem, placeholders);
            effectUtils.playLocalSound(player.getLocation(), onRedeem, 1.0f, 1.0f);
            effectUtils.playParticle(player.getLocation(), onRedeem);
        }
        if (definition.getPermissions() != null && !definition.getPermissions().isEmpty()) {
            grantRedeemPermissions(player, definition.getPermissions());
        }
        if (definition.getVaultGroup() != null && !definition.getVaultGroup().isEmpty() && plugin.getVaultPerms() != null) {
            try {
                plugin.getVaultPerms().playerAddGroup(player, definition.getVaultGroup());
            } catch (Exception ignored) {}
        }
    }

    private void triggerScatterEffects(UUID gemId, Location location, String playerName) {
        triggerScatterEffects(gemId, location, playerName, true);
    }

    private void triggerScatterEffects(UUID gemId, Location location, String playerName, boolean allowFallback) {
        if (location == null) {
            return;
        }
        me.hushu.model.GemDefinition definition = findGemDefinition(gemUuidToKey.get(gemId));
        Map<String, String> placeholders = playerName == null
                ? Collections.emptyMap()
                : Collections.singletonMap("%player%", playerName);
        if (definition != null && definition.getOnScatter() != null) {
            effectUtils.executeCommands(definition.getOnScatter(), placeholders);
            effectUtils.playLocalSound(location, definition.getOnScatter(), 1.0f, 1.0f);
            effectUtils.playParticle(location, definition.getOnScatter());
            return;
        }
        if (allowFallback) {
            ExecuteConfig fallback = configManager.getGemScatterExecute();
            effectUtils.playLocalSound(location, fallback, 1.0f, 1.0f);
            effectUtils.playParticle(location, fallback);
        }
    }

    // 兑换全部：要求集齐并且都在背包中
    public boolean redeemAll(Player player) {
        if (!configManager.isFullSetGrantsAllEnabled()) {
            languageManager.sendMessage(player, "command.redeemall.disabled");
            return true;
        }
        List<me.hushu.model.GemDefinition> defs = configManager.getGemDefinitions();
        if (defs == null || defs.isEmpty()) {
            return false;
        }
        // 检查每个定义是否都持有
        Map<String, UUID> keyToGemId = new HashMap<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (!isRulerGem(item)) continue;
            UUID id = getGemUUID(item);
            String key = gemUuidToKey.get(id);
            if (key != null && !keyToGemId.containsKey(key.toLowerCase())) {
                keyToGemId.put(key.toLowerCase(), id);
            }
        }
    for (me.hushu.model.GemDefinition d : defs) {
            if (!keyToGemId.containsKey(d.getGemKey().toLowerCase())) {
                return false;
            }
        }
        // 所有都持有：依次触发 per-gem 兑换，再统一散落，并将所有 gem 的“当前拥有者”切换为本玩家
        java.util.UUID previousFull = this.fullSetOwner;
        this.fullSetOwner = player.getUniqueId();
        for (me.hushu.model.GemDefinition d : defs) {
            String normalizedKey = d.getGemKey().toLowerCase(Locale.ROOT);
            markGemRedeemed(player, d.getGemKey());
            // 切换该 gem 的当前拥有者，撤销旧拥有者该 gem 的权限/组
            java.util.UUID old = gemKeyToRedeemer.put(normalizedKey, player.getUniqueId());
            if (old != null && !old.equals(player.getUniqueId())) {
                Player oldP = Bukkit.getPlayer(old);
                if (oldP != null && oldP.isOnline()) {
                    if (d.getPermissions() != null) revokeNodes(oldP, d.getPermissions());
                    if (d.getVaultGroup() != null && !d.getVaultGroup().isEmpty() && plugin.getVaultPerms() != null) {
                        try { plugin.getVaultPerms().playerRemoveGroup(oldP, d.getVaultGroup()); } catch (Exception ignored) {}
                    }
                    oldP.recalculatePermissions();
                } else {
                    java.util.Set<String> s = playerUuidToRedeemedKeys.get(old);
                    if (s != null) s.remove(normalizedKey);
                }
            }
            applyRedeemRewards(player, d);
            UUID gid = keyToGemId.get(normalizedKey);
            if (gid != null) {
                removeGemItemFromInventory(player, gid);
                gemUuidToHolder.remove(gid);
                randomPlaceGem(gid, configManager.getRandomPlaceCorner1(), configManager.getRandomPlaceCorner2());
            }
        }
        // 切换全权力持有者：撤销上一任的全部权限与组
        if (previousFull != null && !previousFull.equals(this.fullSetOwner)) {
            Player prev = Bukkit.getPlayer(previousFull);
            if (prev != null && prev.isOnline()) {
                for (me.hushu.model.GemDefinition d : defs) {
                    if (d.getPermissions() != null) revokeNodes(prev, d.getPermissions());
                    if (d.getVaultGroup() != null && !d.getVaultGroup().isEmpty() && plugin.getVaultPerms() != null) {
                        try { plugin.getVaultPerms().playerRemoveGroup(prev, d.getVaultGroup()); } catch (Exception ignored) {}
                    }
                }
                prev.recalculatePermissions();
            }
        }
        // 广播标题（可配置开关）：优先使用 config.titles.redeem_all，否则使用语言文件的 gems_recollected
        boolean broadcast = configManager.getRedeemAllBroadcastOverride() != null ? configManager.getRedeemAllBroadcastOverride() : configManager.isBroadcastRedeemTitle();
        if (broadcast) {
            java.util.List<String> title = configManager.getRedeemAllTitle();
            java.util.Map<String, String> ph = new java.util.HashMap<>();
            ph.put("player", player.getName());
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (title != null && !title.isEmpty()) {
                    if (title.size() == 1) {
                        p.sendTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&', languageManager.formatText(title.get(0), ph)), null, 10, 70, 20);
                    } else {
                        String l1 = languageManager.formatText(title.get(0), ph);
                        String l2 = languageManager.formatText(title.get(1), ph);
                        p.sendTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&', l1), org.bukkit.ChatColor.translateAlternateColorCodes('&', l2), 10, 70, 20);
                    }
                } else {
                    languageManager.showTitle(p, "gems_recollected", ph);
                }
            }
        }
        // 播放全局音效（支持覆盖，默认龙吼）
        try {
            org.bukkit.Sound s = org.bukkit.Sound.valueOf(configManager.getRedeemAllSound());
            effectUtils.playGlobalSound(new ExecuteConfig(java.util.Collections.emptyList(), s.name(), null), 1.0f, 1.0f);
        } catch (Exception ignored) {}
        return true;
    }

    /**
     * 返回当前拥有权力的玩家列表：
     * - fullSetOwner（若存在）
     * - gemKeyToRedeemer 中的各个当前持有者
     */
    public java.util.Map<java.util.UUID, java.util.Set<String>> getCurrentPowerHolders() {
        java.util.Map<java.util.UUID, java.util.Set<String>> map = new java.util.HashMap<>();
        if (this.fullSetOwner != null) {
            map.put(this.fullSetOwner, new java.util.HashSet<>(java.util.Collections.singleton("ALL")));
        }
        for (java.util.Map.Entry<String, java.util.UUID> e : gemKeyToRedeemer.entrySet()) {
            java.util.UUID u = e.getValue();
            if (u == null) continue;
            map.computeIfAbsent(u, k -> new java.util.HashSet<>()).add(e.getKey());
        }
        return map;
    }

    public java.util.UUID getFullSetOwner() {
        return this.fullSetOwner;
    }

    private String resolveGemKeyByNameOrKey(String input) {
        if (input == null) return null;
        String lc = input.toLowerCase();
        for (me.hushu.model.GemDefinition d : configManager.getGemDefinitions()) {
            if (d.getGemKey().equalsIgnoreCase(input)) return d.getGemKey();
            String name = d.getDisplayName();
            if (name != null && org.bukkit.ChatColor.stripColor(name).replace("§", "&").replace("&", "").toLowerCase().contains(lc)) {
                return d.getGemKey();
            }
        }
        return null;
    }

    /**
     * 返回该宝石使用的材质；若未知则返回 RED_STAINED_GLASS 作为回退。
     */
    public org.bukkit.Material getGemMaterial(java.util.UUID gemId) {
        String key = gemUuidToKey.get(gemId);
        if (key != null) {
            me.hushu.model.GemDefinition def = null;
            for (me.hushu.model.GemDefinition d : configManager.getGemDefinitions()) {
                if (d.getGemKey().equalsIgnoreCase(key)) { def = d; break; }
            }
            if (def != null && def.getMaterial() != null) return def.getMaterial();
        }
        return org.bukkit.Material.RED_STAINED_GLASS;
    }

    /**
     * 某些材质（非完整方块）需要下方有支撑。如果需要支撑且目标位置下方不是实心方块，我们应当拒绝放置。
     * 这里采用一个保守的白名单：常见的易受支撑影响的方块返回 true。完整方块返回 false。
     */
    public boolean isSupportRequired(org.bukkit.Material mat) {
        if (mat == null) return false;
        // 运行时名称检查，避免编译期依赖高版本常量
        String name = mat.name();
        if ("SCULK_CATALYST".equals(name)) return true;
        // 保守策略：非实体（非 solid）的方块一般需要支撑
        try {
            if (!mat.isSolid()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 判断某坐标下方方块是否提供支撑（是否为实心方块）。
     */
    public boolean hasBlockSupport(org.bukkit.Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        org.bukkit.Location below = loc.clone().add(0, -1, 0);
        org.bukkit.block.Block b = below.getBlock();
        if (b == null) return false;
        org.bukkit.Material m = b.getType();
        try {
            return m.isSolid();
        } catch (Throwable t) {
            // 某些版本 API 差异，保守返回 true 以避免误拒
            return true;
        }
    }

    /**
     * 解析命令中的 gem 标识：优先按 UUID 匹配现存的宝石，否则按 gemKey/显示名匹配唯一的一颗。
     * 若无法解析或不存在则返回 null。
     */
    public java.util.UUID resolveGemIdentifier(String input) {
        if (input == null || input.trim().isEmpty()) return null;
        // Try parse UUID directly
        try {
            java.util.UUID id = java.util.UUID.fromString(input.trim());
            if (gemUuidToKey.containsKey(id)) return id;
        } catch (Exception ignored) {}
        // Fallback to gem key/name
        String key = resolveGemKeyByNameOrKey(input.trim());
        if (key == null) return null;
        for (Map.Entry<java.util.UUID, String> e : gemUuidToKey.entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(key)) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * 强制将指定宝石移动到目标坐标：
     * - 若在玩家背包中，移除该物品
     * - 若已经放置在世界中，移除原位置
     * - 清理同 gemKey 的重复实例，确保全服唯一
     * - 最后在目标位置放置此宝石
     */
    public void forcePlaceGem(java.util.UUID gemId, org.bukkit.Location target) {
        if (gemId == null || target == null) return;
        final Location oldLoc = findLocationByGemId(gemId);
        final org.bukkit.entity.Player holder = gemUuidToHolder.get(gemId);
        final String key = gemUuidToKey.get(gemId);
        // 在目标区进行安全检查并放置；只有成功放置后才清理旧位置/持有/重复
        final Location base = target.clone();
        SchedulerUtil.regionRun(plugin, base, () -> {
            World world = base.getWorld();
            if (world == null) return;
            WorldBorder border = world.getWorldBorder();
            Location t = base.getBlock().getLocation();
            if (!border.isInside(t) || t.getBlockY() < world.getMinHeight() || t.getBlockY() > world.getMaxHeight()) {
                return; // 不放置，不清理
            }
            // 支撑性检查（如 SCULK_CATALYST）
            Material mat = getGemMaterial(gemId);
            if (isSupportRequired(mat) && !hasBlockSupport(t)) {
                return; // 不放置，不清理
            }
            // 放置并登记
            try {
                if (!t.getChunk().isLoaded()) t.getChunk().load();
            } catch (Throwable ignored) {}
            t.getBlock().setType(mat);
            locationToGemUuid.put(t, gemId);
            // 成功后：清理旧位置
            if (oldLoc != null) {
                unplaceRulerGem(oldLoc, gemId);
            }
            // 成功后：移除持有并撤回临时附件
            if (holder != null) {
                gemUuidToHolder.remove(gemId);
                removeGemItemFromInventory(holder, gemId);
                recalculateGrants(holder);
            }
            // 成功后：清理同 key 的重复
            if (key != null) {
                java.util.List<java.util.UUID> dups = new java.util.ArrayList<>();
                for (Map.Entry<java.util.UUID, String> e : gemUuidToKey.entrySet()) {
                    java.util.UUID otherId = e.getKey();
                    if (otherId.equals(gemId)) continue;
                    String k = e.getValue();
                    if (k != null && k.equalsIgnoreCase(key)) {
                        dups.add(otherId);
                    }
                }
                for (java.util.UUID dup : dups) {
                    org.bukkit.entity.Player h = gemUuidToHolder.remove(dup);
                    if (h != null) {
                        removeGemItemFromInventory(h, dup);
                        recalculateGrants(h);
                    }
                    Location loc = findLocationByGemId(dup);
                    if (loc != null) {
                        unplaceRulerGem(loc, dup);
                    }
                    gemUuidToKey.remove(dup);
                }
            }
        }, 0L, -1L);
    }
}
