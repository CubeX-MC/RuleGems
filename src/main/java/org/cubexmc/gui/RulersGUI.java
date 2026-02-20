package org.cubexmc.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.RuleGems;
import org.cubexmc.features.appoint.Appointment;
import org.cubexmc.features.appoint.AppointFeature;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.model.GemDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 统治者列表 GUI（支持分页）
 * 
 * 布局:
 * - 第 1-4 行: 统治者展示区域（每页最多36个）
 * - 第 5 行: 装饰分隔行
 * - 第 6 行: 控制栏（翻页、关闭等）
 */
public class RulersGUI {

    private final GUIManager guiManager;
    private final GemManager gemManager;
    private final LanguageManager lang;

    public RulersGUI(GUIManager guiManager, GemManager gemManager, LanguageManager languageManager) {
        this.guiManager = guiManager;
        this.gemManager = gemManager;
        this.lang = languageManager;
    }

    private String msg(String path) {
        return ChatColor.translateAlternateColorCodes('&', lang.getMessage("gui." + path));
    }

    private String rawMsg(String path) {
        return lang.getMessage("gui." + path);
    }
    
    private RuleGems getPlugin() {
        return guiManager.getPlugin();
    }

    /**
     * 打开统治者列表 GUI
     */
    public void open(Player player, boolean isAdmin, int page) {
        Map<UUID, Set<String>> rulers = gemManager.getCurrentRulers();
        List<Map.Entry<UUID, Set<String>>> rulerList = new ArrayList<>(rulers.entrySet());
        
        int totalItems = rulerList.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / GUIManager.ITEMS_PER_PAGE));
        page = Math.max(0, Math.min(page, totalPages - 1));
        
        // 创建 GUI
        String title = isAdmin ? msg("rulers.title_admin") : msg("rulers.title_player");
        if (totalPages > 1) {
            title += " &8(" + (page + 1) + "/" + totalPages + ")";
        }
        title = ChatColor.translateAlternateColorCodes('&', title);
        
        GUIHolder holder = new GUIHolder(
                GUIHolder.GUIType.RULERS,
                player.getUniqueId(),
                isAdmin,
                page
        );
        
        Inventory gui = Bukkit.createInventory(holder, GUIManager.GUI_SIZE, title);
        holder.setInventory(gui);
        
        // 填充装饰和控制栏（启用返回按钮）
        guiManager.fillDecoration(gui);
        guiManager.addControlBar(gui, page, totalPages, totalItems, false, true);
        
        if (rulers.isEmpty()) {
            // 无统治者时显示提示
            gui.setItem(13, createNoRulersItem());
        } else {
            // 填充统治者内容
            int startIndex = page * GUIManager.ITEMS_PER_PAGE;
            int endIndex = Math.min(startIndex + GUIManager.ITEMS_PER_PAGE, totalItems);
            
            for (int i = startIndex; i < endIndex; i++) {
                int slot = i - startIndex;
                Map.Entry<UUID, Set<String>> entry = rulerList.get(i);
                ItemStack item = createRulerItem(entry.getKey(), entry.getValue(), isAdmin);
                gui.setItem(slot, item);
            }
        }
        
        player.openInventory(gui);
    }

    /**
     * 创建"无统治者"提示物品
     */
    private ItemStack createNoRulersItem() {
        return new ItemBuilder(Material.BARRIER)
                .name("&c" + rawMsg("rulers.no_rulers"))
                .addLore("&7" + rawMsg("rulers.no_rulers_lore"))
                .build();
    }

    /**
     * 创建统治者展示物品
     */
    private ItemStack createRulerItem(UUID playerUuid, Set<String> gemKeys, boolean isAdmin) {
        Player ruler = Bukkit.getPlayer(playerUuid);
        String playerName = gemManager.getCachedPlayerName(playerUuid);
        boolean isOnline = ruler != null && ruler.isOnline();
        
        // 计算权力等级
        boolean isSupreme = gemKeys.contains("ALL");
        int gemCount = (int) gemKeys.stream().filter(k -> !k.equals("ALL")).count();
        
        // 选择名称颜色
        ChatColor nameColor;
        if (isSupreme) {
            nameColor = ChatColor.GOLD;
        } else if (gemCount >= 3) {
            nameColor = ChatColor.LIGHT_PURPLE;
        } else if (gemCount >= 2) {
            nameColor = ChatColor.AQUA;
        } else {
            nameColor = ChatColor.GREEN;
        }
        
        // 构建物品
        ItemBuilder builder = new ItemBuilder(Material.PLAYER_HEAD)
                .name(nameColor + "✦ " + playerName + " ✦")
                .data(guiManager.getPlayerUuidKey(), playerUuid.toString());
        
        // 设置头颅皮肤
        if (ruler != null) {
            builder.skullOwner(ruler);
        } else {
            // 离线玩家尝试获取
            try {
                builder.skullOwner(Bukkit.getOfflinePlayer(playerUuid));
            } catch (Exception e) { Bukkit.getLogger().fine("Failed to set skull owner for offline ruler: " + e.getMessage()); }
        }
        
        // ID 信息
        builder.addEmptyLore();
        
        // 至高统治者标识
        if (isSupreme) {
            builder.addLore("&6★ " + rawMsg("rulers.supreme_ruler") + " ★")
                   .addLore("&7" + rawMsg("rulers.supreme_ruler_desc"))
                   .addEmptyLore();
        }
        
        // 持有的宝石
        builder.addLore("&e▸ " + rawMsg("rulers.holding_gems") + " &7(" + gemCount + ")");
        
        for (String key : gemKeys) {
            if (key.equals("ALL")) continue;
            
            GemDefinition def = gemManager.findGemDefinitionByKey(key);
            String gemName = def != null && def.getDisplayName() != null
                    ? ChatColor.translateAlternateColorCodes('&', def.getDisplayName())
                    : key;
            builder.addLore("&8  • " + gemName);
        }
        
        // 状态
        builder.addEmptyLore();
        if (isOnline) {
            builder.addLore("&a● " + rawMsg("rulers.status_online"));
        } else {
            builder.addLore("&c● " + rawMsg("rulers.status_offline"));
        }
        
        // 任命信息
        int appointeeCount = getAppointeeCount(playerUuid);
        if (appointeeCount > 0) {
            builder.addEmptyLore();
            builder.addLore("&d▸ " + rawMsg("rulers.appointee_count").replace("%count%", String.valueOf(appointeeCount)));
        }
        
        // 管理员信息
        if (isAdmin) {
            builder.addEmptyLore()
                   .addLore("&8UUID: &7" + playerUuid.toString().substring(0, 8) + "...");
        }
        
        // 点击提示
        builder.addEmptyLore();
        builder.addLore("&e» " + rawMsg("rulers.click_view_appointees"));
        if (isAdmin && isOnline) {
            builder.addLore("&a» " + rawMsg("rulers.shift_click_tp"));
        }
        
        return builder.build();
    }
    
    /**
     * 获取某个统治者任命的人数
     */
    private int getAppointeeCount(UUID rulerUuid) {
        RuleGems plugin = getPlugin();
        if (plugin.getFeatureManager() == null) return 0;
        
        AppointFeature appointFeature = plugin.getFeatureManager().getAppointFeature();
        if (appointFeature == null || !appointFeature.isEnabled()) return 0;
        
        List<Appointment> appointments = appointFeature.getAppointmentsByAppointer(rulerUuid);
        return appointments.size();
    }
}
