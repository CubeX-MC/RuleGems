package org.cubexmc.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

/**
 * 主菜单 GUI - 提供导航到宝石列表和统治者列表
 * 
 * 布局 (27格 3x9):
 * ┌─────────────────────────────────────────┐
 * │ ▬ ▬ ▬ ▬ ▬ ▬ ▬ ▬ ▬ │ 装饰行
 * │ ▬ 💎 👤 💠 👑 🏛 🧭 ▬ │ 功能行
 * │ ▬ ▬ ▬ ▬ ✕ ▬ ▬ ▬ ▬ │ 控制行
 * └─────────────────────────────────────────┘
 */
public class MainMenuGUI extends ChestMenu {

    private static final int GUI_SIZE = 27;
    private static final int SLOT_GEMS = 11;
    private static final int SLOT_RULERS = 15;
    private static final int SLOT_CLOSE = 22;

    private final GemManager gemManager;
    private final LanguageManager lang;

    public MainMenuGUI(GUIManager guiManager, GemManager gemManager, LanguageManager languageManager) {
        super(guiManager);
        this.gemManager = gemManager;
        this.lang = languageManager;
    }

    private String msg(String path) {
        return org.cubexmc.utils.ColorUtils.translateColorCodes(lang.getMessage("gui." + path));
    }

    private String rawMsg(String path) {
        return lang.getMessage("gui." + path);
    }

    // ------------------------------------------------------------------
    // ChestMenu contract
    // ------------------------------------------------------------------

    @Override
    protected String getTitle() {
        return msg("menu.title");
    }

    @Override
    protected int getSize() {
        return GUI_SIZE;
    }

    @Override
    protected GUIHolder.GUIType getHolderType() {
        return GUIHolder.GUIType.MAIN_MENU;
    }

    @Override
    protected void populate(org.bukkit.inventory.Inventory gui, GUIHolder holder, Player player) {
        // 填充背景
        ItemStack filler = ItemBuilder.filler();
        for (int i = 0; i < GUI_SIZE; i++)
            gui.setItem(i, filler);

        boolean isAdmin = player != null ? player.hasPermission("rulegems.admin") : holder.isAdmin();
        if (manager.canOpenGems(player)) {
            gui.setItem(SLOT_GEMS, createGemsButton(gemManager.getAllGemUuids().size(), isAdmin));
        }
        if (manager.canOpenRulers(player)) {
            gui.setItem(SLOT_RULERS, createRulersButton(gemManager.getCurrentRulers().size(), isAdmin));
        }
        gui.setItem(SLOT_CLOSE, ItemBuilder.closeButton(manager.getNavActionKey(), rawMsg("control.close")));
    }

    // No item-click handling needed — navigation items handle everything.

    /**
     * 创建宝石按钮
     */
    private ItemStack createGemsButton(int gemCount, boolean isAdmin) {
        Material material = Material.DIAMOND;

        ItemBuilder builder = new ItemBuilder(material)
                .name("&b" + rawMsg("menu.gems_title"))
                .data(manager.getNavActionKey(), "open_gems")
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
                .data(manager.getNavActionKey(), "open_rulers")
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
