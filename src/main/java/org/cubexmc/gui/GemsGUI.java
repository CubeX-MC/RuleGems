package org.cubexmc.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.model.AllowedCommand;
import org.cubexmc.model.GemDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 宝石列表 GUI（支持分页和筛选）
 * 
 * 布局:
 * - 第 1-4 行: 宝石展示区域（每页最多36个）
 * - 第 5 行: 装饰分隔行
 * - 第 6 行: 控制栏（翻页、筛选、关闭等）
 */
public class GemsGUI {

    private final GUIManager guiManager;
    private final GemManager gemManager;
    private final LanguageManager lang;

    public GemsGUI(GUIManager guiManager, GemManager gemManager, LanguageManager languageManager) {
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

    /**
     * 打开宝石列表 GUI
     */
    public void open(Player player, boolean isAdmin, int page, String filter) {
        // 获取所有宝石并应用筛选
        List<UUID> allGemIds = new ArrayList<>(gemManager.getAllGemUuids());
        
        // 应用筛选条件
        if (filter != null && !filter.isEmpty()) {
            allGemIds = filterGems(allGemIds, filter);
        }
        
        int totalItems = allGemIds.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / GUIManager.ITEMS_PER_PAGE));
        page = Math.max(0, Math.min(page, totalPages - 1));
        
        // 创建 GUI
        String title = isAdmin ? msg("gems.title_admin") : msg("gems.title_player");
        if (totalPages > 1) {
            title += " &8(" + (page + 1) + "/" + totalPages + ")";
        }
        title = ChatColor.translateAlternateColorCodes('&', title);
        
        GUIHolder holder = new GUIHolder(
                GUIHolder.GUIType.GEMS, 
                player.getUniqueId(), 
                isAdmin, 
                page, 
                filter
        );
        
        Inventory gui = Bukkit.createInventory(holder, GUIManager.GUI_SIZE, title);
        holder.setInventory(gui);
        
        // 填充装饰和控制栏（启用返回按钮）
        guiManager.fillDecoration(gui);
        guiManager.addControlBar(gui, page, totalPages, totalItems, true, true);
        
        // 填充宝石内容
        int startIndex = page * GUIManager.ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + GUIManager.ITEMS_PER_PAGE, totalItems);
        
        for (int i = startIndex; i < endIndex; i++) {
            int slot = i - startIndex;
            UUID gemId = allGemIds.get(i);
            ItemStack item = createGemItem(gemId, isAdmin);
            gui.setItem(slot, item);
        }
        
        player.openInventory(gui);
    }

    /**
     * 筛选宝石
     */
    private List<UUID> filterGems(List<UUID> gems, String filter) {
        String lowerFilter = filter.toLowerCase(Locale.ROOT);
        
        return gems.stream().filter(gemId -> {
            String gemKey = gemManager.getGemKey(gemId);
            if (gemKey != null && gemKey.toLowerCase(Locale.ROOT).contains(lowerFilter)) {
                return true;
            }
            
            GemDefinition def = gemKey != null ? gemManager.findGemDefinitionByKey(gemKey) : null;
            if (def != null && def.getDisplayName() != null) {
                String stripped = ChatColor.stripColor(
                        ChatColor.translateAlternateColorCodes('&', def.getDisplayName()));
                if (stripped.toLowerCase(Locale.ROOT).contains(lowerFilter)) {
                    return true;
                }
            }
            
            // 筛选状态
            if ("held".equals(lowerFilter) || "持有".equals(lowerFilter)) {
                return gemManager.getGemHolder(gemId) != null;
            }
            if ("placed".equals(lowerFilter) || "放置".equals(lowerFilter)) {
                return gemManager.getGemLocation(gemId) != null;
            }
            
            return false;
        }).collect(Collectors.toList());
    }

    /**
     * 创建宝石展示物品
     */
    private ItemStack createGemItem(UUID gemId, boolean isAdmin) {
        String gemKey = gemManager.getGemKey(gemId);
        GemDefinition def = gemKey != null ? gemManager.findGemDefinitionByKey(gemKey) : null;
        
        Material material = def != null && def.getMaterial() != null 
                ? def.getMaterial() : Material.RED_STAINED_GLASS;
        String displayName = def != null && def.getDisplayName() != null 
                ? def.getDisplayName() : rawMsg("gems.default_name");
        
        ItemBuilder builder = new ItemBuilder(material)
                .name(displayName)
                .data(guiManager.getGemIdKey(), gemId.toString());
        
        // 获取状态
        Player holder = gemManager.getGemHolder(gemId);
        Location location = gemManager.getGemLocation(gemId);
        
        if (isAdmin) {
            buildAdminLore(builder, gemId, gemKey, def, holder, location);
        } else {
            buildPlayerLore(builder, def, holder, location);
        }
        
        // 附魔效果
        if (def != null && def.isEnchanted()) {
            builder.glow();
        }
        
        return builder.hideAttributes().build();
    }

    /**
     * 构建管理员视图 Lore
     */
    private void buildAdminLore(ItemBuilder builder, UUID gemId, String gemKey, 
                                 GemDefinition def, Player holder, Location location) {
        // ID 信息
        builder.addEmptyLore()
               .addLore("&e▸ " + rawMsg("gems.section_id"))
               .addLore("&8  Key: &f" + (gemKey != null ? gemKey : "N/A"))
               .addLore("&8  UUID: &7" + gemId.toString().substring(0, 8) + "...");
        
        // 状态信息
        builder.addEmptyLore()
               .addLore("&e▸ " + rawMsg("gems.section_status"));
        
        if (holder != null) {
            builder.addLore("&a  ● " + rawMsg("gems.status_held"))
                   .addLore("&8  → &b" + holder.getName());
        } else if (location != null) {
            builder.addLore("&6  ● " + rawMsg("gems.status_placed"))
                   .addLore("&8  → &f" + formatLocation(location));
        } else {
            builder.addLore("&c  ● " + rawMsg("gems.status_unknown"));
        }
        
        // 权限信息
        if (def != null) {
            boolean hasPerms = (def.getPermissions() != null && !def.getPermissions().isEmpty())
                    || (def.getVaultGroup() != null && !def.getVaultGroup().isEmpty());
            
            if (hasPerms) {
                builder.addEmptyLore()
                       .addLore("&e▸ " + rawMsg("gems.section_permissions"));
                
                if (def.getPermissions() != null) {
                    for (String perm : def.getPermissions()) {
                        builder.addLore("&8  • &a" + perm);
                    }
                }
                if (def.getVaultGroup() != null && !def.getVaultGroup().isEmpty()) {
                    builder.addLore("&8  Group: &d" + def.getVaultGroup());
                }
            }
            
            // 命令信息
            if (def.getAllowedCommands() != null && !def.getAllowedCommands().isEmpty()) {
                builder.addEmptyLore()
                       .addLore("&e▸ " + rawMsg("gems.section_commands"));
                
                for (AllowedCommand cmd : def.getAllowedCommands()) {
                    String uses = cmd.getUses() < 0 ? rawMsg("gems.uses_unlimited") : String.valueOf(cmd.getUses());
                    builder.addLore("&8  • &b/" + cmd.getLabel() + " &7(" + uses + ")");
                }
            }
            
            // 祭坛位置
            Location altar = def.getAltarLocation();
            if (altar != null) {
                builder.addEmptyLore()
                       .addLore("&e▸ " + rawMsg("gems.section_altar"))
                       .addLore("&8  → &d" + formatLocation(altar));
            }
        }
        
        // 操作提示
        builder.addEmptyLore();
        if (holder != null) {
            builder.addLore("&a» " + rawMsg("gems.click_tp_holder"));
        } else if (location != null) {
            builder.addLore("&a» " + rawMsg("gems.click_tp_location"));
        }
    }

    /**
     * 构建玩家视图 Lore
     */
    private void buildPlayerLore(ItemBuilder builder, GemDefinition def, 
                                  Player holder, Location location) {
        // 宝石描述
        if (def != null && def.getLore() != null && !def.getLore().isEmpty()) {
            builder.addEmptyLore();
            for (String line : def.getLore()) {
                builder.addLore(line);
            }
        }
        
        // 状态
        builder.addEmptyLore();
        if (holder != null) {
            builder.addLore("&7" + rawMsg("gems.status_held") + ": &b" + holder.getName());
        } else if (location != null) {
            builder.addLore("&7" + rawMsg("gems.hidden_in_world"));
        } else {
            builder.addLore("&7" + rawMsg("gems.status_unknown"));
        }
    }

    /**
     * 格式化位置显示
     */
    private String formatLocation(Location loc) {
        String world = loc.getWorld() != null ? loc.getWorld().getName() : "?";
        return String.format("%d, %d, %d (%s)", 
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), world);
    }
}
