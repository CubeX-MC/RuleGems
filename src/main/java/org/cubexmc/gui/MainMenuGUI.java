package org.cubexmc.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

/**
 * 主菜单 GUI - 提供导航到宝石列表和统治者列表
 * 
 * 布局 (27格 3x9):
 * ┌─────────────────────────────────────────┐
 * │  ▬  ▬  ▬  ▬  ▬  ▬  ▬  ▬  ▬  │ 装饰行
 * │  ▬  ▬  💎  ▬  ▬  ▬  👑  ▬  ▬  │ 功能行
 * │  ▬  ▬  ▬  ▬  ✕  ▬  ▬  ▬  ▬  │ 控制行
 * └─────────────────────────────────────────┘
 */
public class MainMenuGUI {

    private static final int GUI_SIZE = 27;
    private static final int SLOT_GEMS = 11;      // 宝石按钮位置
    private static final int SLOT_RULERS = 15;    // 统治者按钮位置
    private static final int SLOT_CLOSE = 22;     // 关闭按钮位置

    private final GUIManager guiManager;
    private final GemManager gemManager;
    private final LanguageManager lang;

    public MainMenuGUI(GUIManager guiManager, GemManager gemManager, LanguageManager languageManager) {
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
     * 打开主菜单 GUI
     */
    public void open(Player player, boolean isAdmin) {
        String title = msg("menu.title");
        
        GUIHolder holder = new GUIHolder(
                GUIHolder.GUIType.MAIN_MENU,
                player.getUniqueId(),
                isAdmin
        );
        
        Inventory gui = Bukkit.createInventory(holder, GUI_SIZE, title);
        holder.setInventory(gui);
        
        // 填充背景
        ItemStack filler = ItemBuilder.filler();
        for (int i = 0; i < GUI_SIZE; i++) {
            gui.setItem(i, filler);
        }
        
        // 宝石按钮
        int gemCount = gemManager.getAllGemUuids().size();
        gui.setItem(SLOT_GEMS, createGemsButton(gemCount, isAdmin));
        
        // 统治者按钮
        int rulerCount = gemManager.getCurrentRulers().size();
        gui.setItem(SLOT_RULERS, createRulersButton(rulerCount, isAdmin));
        
        // 关闭按钮
        gui.setItem(SLOT_CLOSE, ItemBuilder.closeButton(
                guiManager.getNavActionKey(), 
                rawMsg("control.close")));
        
        player.openInventory(gui);
    }

    /**
     * 创建宝石按钮
     */
    private ItemStack createGemsButton(int gemCount, boolean isAdmin) {
        Material material = Material.DIAMOND;
        
        ItemBuilder builder = new ItemBuilder(material)
                .name("&b" + rawMsg("menu.gems_title"))
                .data(guiManager.getNavActionKey(), "open_gems")
                .glow();
        
        builder.addEmptyLore()
               .addLore("&7" + rawMsg("menu.gems_desc"))
               .addEmptyLore()
               .addLore("&e▸ " + rawMsg("menu.gem_count") + ": &f" + gemCount);
        
        if (isAdmin) {
            builder.addEmptyLore()
                   .addLore("&8" + rawMsg("menu.admin_view"));
        }
        
        builder.addEmptyLore()
               .addLore("&a» " + rawMsg("menu.click_to_open"));
        
        return builder.build();
    }

    /**
     * 创建统治者按钮
     */
    private ItemStack createRulersButton(int rulerCount, boolean isAdmin) {
        Material material = Material.GOLDEN_HELMET;
        
        ItemBuilder builder = new ItemBuilder(material)
                .name("&6" + rawMsg("menu.rulers_title"))
                .data(guiManager.getNavActionKey(), "open_rulers")
                .hideAttributes()
                .glow();
        
        builder.addEmptyLore()
               .addLore("&7" + rawMsg("menu.rulers_desc"))
               .addEmptyLore()
               .addLore("&e▸ " + rawMsg("menu.ruler_count") + ": &f" + rulerCount);
        
        if (isAdmin) {
            builder.addEmptyLore()
                   .addLore("&8" + rawMsg("menu.admin_view"));
        }
        
        builder.addEmptyLore()
               .addLore("&a» " + rawMsg("menu.click_to_open"));
        
        return builder.build();
    }
}

