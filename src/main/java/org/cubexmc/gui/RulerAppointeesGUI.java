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
import org.cubexmc.model.AppointDefinition;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 统治者任命详情 GUI
 * 显示某个统治者任命的所有玩家及其权限信息
 */
public class RulerAppointeesGUI {

    private final GUIManager guiManager;
    private final GemManager gemManager;
    private final LanguageManager lang;
    private final RuleGems plugin;

    public RulerAppointeesGUI(GUIManager guiManager, GemManager gemManager, LanguageManager languageManager, RuleGems plugin) {
        this.guiManager = guiManager;
        this.gemManager = gemManager;
        this.lang = languageManager;
        this.plugin = plugin;
    }

    private String msg(String path) {
        return ChatColor.translateAlternateColorCodes('&', lang.getMessage("gui." + path));
    }

    private String rawMsg(String path) {
        return lang.getMessage("gui." + path);
    }

    /**
     * 打开统治者任命详情 GUI
     * @param player 查看者
     * @param rulerUuid 统治者 UUID
     * @param isAdmin 是否为管理员视图
     * @param page 页码
     */
    public void open(Player player, UUID rulerUuid, boolean isAdmin, int page) {
        AppointFeature appointFeature = getAppointFeature();
        
        // 获取该统治者任命的所有人
        List<Appointment> allAppointments = new ArrayList<>();
        if (appointFeature != null && appointFeature.isEnabled()) {
            allAppointments = appointFeature.getAppointmentsByAppointer(rulerUuid);
        }
        
        int totalItems = allAppointments.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / GUIManager.ITEMS_PER_PAGE));
        page = Math.max(0, Math.min(page, totalPages - 1));
        
        // 获取统治者名称
        String rulerName = gemManager.getCachedPlayerName(rulerUuid);
        
        // 创建 GUI
        String title = msg("appointees.title").replace("%ruler%", rulerName);
        if (totalPages > 1) {
            title += " &8(" + (page + 1) + "/" + totalPages + ")";
        }
        title = ChatColor.translateAlternateColorCodes('&', title);
        
        GUIHolder holder = new GUIHolder(
                GUIHolder.GUIType.RULER_APPOINTEES,
                player.getUniqueId(),
                isAdmin,
                page,
                rulerUuid.toString()  // 使用 filter 字段存储统治者 UUID
        );
        
        Inventory gui = Bukkit.createInventory(holder, GUIManager.GUI_SIZE, title);
        holder.setInventory(gui);
        
        // 填充装饰和控制栏（启用返回按钮）
        guiManager.fillDecoration(gui);
        guiManager.addControlBar(gui, page, totalPages, totalItems, false, true);
        
        if (allAppointments.isEmpty()) {
            // 无任命时显示提示
            gui.setItem(13, createNoAppointeesItem());
        } else {
            // 按权限集分组
            Map<String, List<Appointment>> groupedAppointments = new HashMap<>();
            for (Appointment appointment : allAppointments) {
                groupedAppointments.computeIfAbsent(appointment.getPermSetKey(), k -> new ArrayList<>())
                                  .add(appointment);
            }
            
            // 填充内容
            int startIndex = page * GUIManager.ITEMS_PER_PAGE;
            int endIndex = Math.min(startIndex + GUIManager.ITEMS_PER_PAGE, totalItems);
            
            for (int i = startIndex; i < endIndex; i++) {
                int slot = i - startIndex;
                Appointment appointment = allAppointments.get(i);
                ItemStack item = createAppointeeItem(appointment, appointFeature, isAdmin);
                gui.setItem(slot, item);
            }
        }
        
        player.openInventory(gui);
    }

    /**
     * 创建"无任命"提示物品
     */
    private ItemStack createNoAppointeesItem() {
        return new ItemBuilder(Material.BARRIER)
                .name("&c" + rawMsg("appointees.no_appointees"))
                .addLore("&7" + rawMsg("appointees.no_appointees_lore"))
                .build();
    }

    /**
     * 创建被任命者展示物品
     */
    private ItemStack createAppointeeItem(Appointment appointment, AppointFeature appointFeature, boolean isAdmin) {
        UUID appointeeUuid = appointment.getAppointeeUuid();
        Player appointee = Bukkit.getPlayer(appointeeUuid);
        String playerName = gemManager.getCachedPlayerName(appointeeUuid);
        boolean isOnline = appointee != null && appointee.isOnline();
        
        // 获取权限集信息
        AppointDefinition def = appointFeature != null ? 
            appointFeature.getAppointDefinition(appointment.getPermSetKey()) : null;
        
        String displayName = def != null ? 
            ChatColor.translateAlternateColorCodes('&', def.getDisplayName()) : 
            appointment.getPermSetKey();
        
        // 选择材质和颜色
        Material material = Material.PLAYER_HEAD;
        ChatColor nameColor = isOnline ? ChatColor.GREEN : ChatColor.GRAY;
        
        // 构建物品
        ItemBuilder builder = new ItemBuilder(material)
                .name(nameColor + "◆ " + playerName)
                .data(guiManager.getPlayerUuidKey(), appointeeUuid.toString());
        
        // 设置头颅皮肤
        if (appointee != null) {
            builder.skullOwner(appointee);
        } else {
            try {
                builder.skullOwner(Bukkit.getOfflinePlayer(appointeeUuid));
            } catch (Exception ignored) {}
        }
        
        // 职位/权限集名称
        builder.addEmptyLore();
        builder.addLore("&e▸ " + rawMsg("appointees.position") + " &f" + displayName);
        
        // 权限集描述
        if (def != null && def.getDescription() != null && !def.getDescription().isEmpty()) {
            builder.addLore("&7  " + def.getDescription());
        }
        
        // 权限列表
        if (def != null && def.getPowerStructure() != null && !def.getPowerStructure().getPermissions().isEmpty()) {
            builder.addEmptyLore();
            builder.addLore("&b▸ " + rawMsg("appointees.permissions"));
            int count = 0;
            List<String> perms = def.getPowerStructure().getPermissions();
            for (String perm : perms) {
                if (count >= 5) {
                    int remaining = perms.size() - 5;
                    builder.addLore("&8  ... " + rawMsg("appointees.and_more").replace("%count%", String.valueOf(remaining)));
                    break;
                }
                builder.addLore("&8  • &7" + perm);
                count++;
            }
        }
        
        // 限次命令
        if (def != null && def.getPowerStructure() != null && !def.getPowerStructure().getAllowedCommands().isEmpty()) {
            builder.addEmptyLore();
            builder.addLore("&d▸ " + rawMsg("appointees.allowed_commands"));
            int count = 0;
            for (org.cubexmc.model.AllowedCommand cmd : def.getPowerStructure().getAllowedCommands()) {
                if (count >= 3) {
                    int remaining = def.getPowerStructure().getAllowedCommands().size() - 3;
                    builder.addLore("&8  ... " + rawMsg("appointees.and_more").replace("%count%", String.valueOf(remaining)));
                    break;
                }
                String cmdText = "/" + cmd.getLabel();
                if (cmd.getUses() > 0) {
                    cmdText += " &7(" + cmd.getUses() + "x)";
                }
                builder.addLore("&8  • &7" + cmdText);
                count++;
            }
        }
        
        // 委任权限（可以任命他人）
        // 检查 appoints 和 permissions 中的 rulegems.appoint.xxx
        List<String> canAppoint = new ArrayList<>();
        if (def != null && def.getPowerStructure() != null) {
            // 1. 从 appoints 获取
            if (def.getPowerStructure().getAppoints() != null) {
                canAppoint.addAll(def.getPowerStructure().getAppoints().keySet());
            }
            // 2. 从 permissions 获取
            for (String perm : def.getPowerStructure().getPermissions()) {
                if (perm.startsWith("rulegems.appoint.")) {
                    String key = perm.substring("rulegems.appoint.".length());
                    if (!canAppoint.contains(key)) {
                        canAppoint.add(key);
                    }
                }
            }
        }
        
        if (!canAppoint.isEmpty()) {
            builder.addEmptyLore();
            builder.addLore("&6▸ " + rawMsg("appointees.delegate_permissions"));
            for (String key : canAppoint) {
                AppointDefinition delegateDef = appointFeature != null ? 
                    appointFeature.getAppointDefinition(key) : null;
                String delegateName = delegateDef != null ? 
                    ChatColor.translateAlternateColorCodes('&', delegateDef.getDisplayName()) : 
                    key;
                builder.addLore("&8  • &7" + rawMsg("appointees.can_appoint") + " " + delegateName);
            }
        }
        
        // 任命时间
        builder.addEmptyLore();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        String appointedTime = sdf.format(new Date(appointment.getAppointedAt()));
        builder.addLore("&7" + rawMsg("appointees.appointed_at") + " &f" + appointedTime);
        
        // 状态
        builder.addEmptyLore();
        if (isOnline) {
            builder.addLore("&a● " + rawMsg("rulers.status_online"));
        } else {
            builder.addLore("&c● " + rawMsg("rulers.status_offline"));
        }
        
        // 管理员操作提示
        if (isAdmin) {
            builder.addEmptyLore();
            if (isOnline) {
                builder.addLore("&a» " + rawMsg("appointees.click_tp"));
            }
            builder.addLore("&c» " + rawMsg("appointees.shift_click_dismiss"));
        }
        
        return builder.build();
    }

    private AppointFeature getAppointFeature() {
        if (plugin.getFeatureManager() == null) return null;
        return plugin.getFeatureManager().getAppointFeature();
    }
}
