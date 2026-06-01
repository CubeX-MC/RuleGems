package org.cubexmc.gui;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.cubexmc.RuleGems;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

/**
 * GUI 管理器 - 统一管理所有 GUI 相关功能
 * 
 * 界面布局（54格 6x9）:
 * - 第 1-4 行 (0-35): 主内容区域
 * - 第 5 行 (36-44): 分隔/装饰行
 * - 第 6 行 (45-53): 控制栏
 */
public class GUIManager implements Listener {

    // 布局常量
    public static final int GUI_SIZE = 54; // 总槽位数
    public static final int CONTENT_ROWS = 4; // 内容行数
    public static final int ITEMS_PER_PAGE = 36; // 每页内容数 (4行 x 9列)

    // 控制栏槽位
    public static final int SLOT_PREV = 45; // 上一页
    public static final int SLOT_BACK = 46; // 返回
    public static final int SLOT_FILTER = 47; // 筛选
    public static final int SLOT_INFO = 49; // 页码信息
    public static final int SLOT_REFRESH = 51; // 刷新
    public static final int SLOT_CLOSE = 52; // 关闭
    public static final int SLOT_NEXT = 53; // 下一页

    private final RuleGems plugin;
    private final LanguageManager lang;

    // 持久化数据键
    private final NamespacedKey gemIdKey;
    private final NamespacedKey navActionKey;
    private final NamespacedKey playerUuidKey;
    private final NamespacedKey appointKeyKey;

    private final MainMenuGUI mainMenuGUI;
    private final GemsGUI gemsGUI;
    private final RulersGUI rulersGUI;
    private final RulerAppointeesGUI rulerAppointeesGUI;
    private final ProfileGUI profileGUI;
    private final CabinetGUI cabinetGUI;
    private final CabinetMembersGUI cabinetMembersGUI;
    private final PowerTogglesGUI powerTogglesGUI;

    public GUIManager(RuleGems plugin, GemManager gemManager, LanguageManager languageManager) {
        this.plugin = plugin;
        this.lang = languageManager;

        this.gemIdKey = new NamespacedKey(plugin, "gem_id");
        this.navActionKey = new NamespacedKey(plugin, "nav_action");
        this.playerUuidKey = new NamespacedKey(plugin, "player_uuid");
        this.appointKeyKey = new NamespacedKey(plugin, "appoint_key");

        // 初始化各个 GUI
        this.mainMenuGUI = new MainMenuGUI(this, gemManager, languageManager);
        this.gemsGUI = new GemsGUI(this, gemManager, languageManager);
        this.rulersGUI = new RulersGUI(this, gemManager, languageManager);
        this.rulerAppointeesGUI = new RulerAppointeesGUI(this, gemManager, languageManager, plugin);
        this.profileGUI = new ProfileGUI(this, gemManager, languageManager, plugin);
        this.cabinetGUI = new CabinetGUI(this, gemManager, languageManager, plugin);
        this.cabinetMembersGUI = new CabinetMembersGUI(this, gemManager, languageManager, plugin);
        this.powerTogglesGUI = new PowerTogglesGUI(this, gemManager, languageManager);

        // 注册事件监听器
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ========== Getter 方法 ==========

    public NamespacedKey getGemIdKey() {
        return gemIdKey;
    }

    public NamespacedKey getNavActionKey() {
        return navActionKey;
    }

    public NamespacedKey getPlayerUuidKey() {
        return playerUuidKey;
    }

    public NamespacedKey getAppointKeyKey() {
        return appointKeyKey;
    }

    public RuleGems getPlugin() {
        return plugin;
    }

    /**
     * 获取语言消息（带颜色转换）
     */
    public String msg(String path) {
        return org.cubexmc.utils.ColorUtils.translateColorCodes(lang.getMessage("gui." + path));
    }

    /**
     * 获取语言消息（原始）
     */
    public String rawMsg(String path) {
        return lang.getMessage("gui." + path);
    }

    // ========== GUI 打开方法 ==========

    /**
     * 打开主菜单 GUI
     */
    public void openMainMenu(Player player, boolean isAdmin) {
        mainMenuGUI.open(player, isAdmin);
    }

    /**
     * 打开宝石列表 GUI
     */
    public void openGemsGUI(Player player, boolean isAdmin) {
        openGemsGUI(player, isAdmin, 0, null);
    }

    /**
     * 打开宝石列表 GUI（指定页码和筛选）
     */
    public void openGemsGUI(Player player, boolean isAdmin, int page, String filter) {
        if (!canOpenGems(player)) {
            lang.sendMessage(player, "command.no_permission");
            return;
        }
        gemsGUI.open(player, isAdmin, page, filter);
    }

    /**
     * 打开统治者列表 GUI
     */
    public void openRulersGUI(Player player, boolean isAdmin) {
        openRulersGUI(player, isAdmin, 0);
    }

    /**
     * 打开统治者列表 GUI（指定页码）
     */
    public void openRulersGUI(Player player, boolean isAdmin, int page) {
        if (!canOpenRulers(player)) {
            lang.sendMessage(player, "command.no_permission");
            return;
        }
        rulersGUI.open(player, isAdmin, page);
    }

    /**
     * 打开统治者任命详情 GUI
     */
    public void openRulerAppointeesGUI(Player player, UUID rulerUuid, boolean isAdmin) {
        openRulerAppointeesGUI(player, rulerUuid, isAdmin, 0);
    }

    /**
     * 打开统治者任命详情 GUI（指定页码）
     */
    public void openRulerAppointeesGUI(Player player, UUID rulerUuid, boolean isAdmin, int page) {
        rulerAppointeesGUI.open(player, rulerUuid, isAdmin, page);
    }

    public void openProfileGUI(Player player) {
        openProfileGUI(player, 0);
    }

    public void openProfileGUI(Player player, int page) {
        profileGUI.open(player, page);
    }

    public void openCabinetGUI(Player player) {
        openCabinetGUI(player, 0);
    }

    public void openCabinetGUI(Player player, int page) {
        cabinetGUI.open(player, page);
    }

    public void openCabinetMembersGUI(Player player, String appointKey) {
        openCabinetMembersGUI(player, appointKey, 0);
    }

    public void openCabinetMembersGUI(Player player, String appointKey, int page) {
        cabinetMembersGUI.open(player, appointKey, page);
    }

    public void openPowerTogglesGUI(Player player, boolean isAdmin) {
        openPowerTogglesGUI(player, isAdmin, 0);
    }

    public void openPowerTogglesGUI(Player player, boolean isAdmin, int page) {
        powerTogglesGUI.open(player, page);
    }

    public boolean canOpenCabinet(Player player) {
        if (player == null || plugin.getFeatureManager() == null || plugin.getFeatureManager().getAppointFeature() == null
                || !plugin.getFeatureManager().getAppointFeature().isEnabled()) {
            return false;
        }
        if (player.hasPermission("rulegems.admin")) {
            return true;
        }
        return plugin.getFeatureManager().getAppointFeature().getAppointDefinitions().keySet().stream()
                .anyMatch(key -> player.hasPermission("rulegems.appoint." + key)
                        || player.hasPermission("rulegems.appoint."
                                + key.toLowerCase(java.util.Locale.ROOT)));
    }

    public boolean canOpenGems(Player player) {
        return player != null && (player.hasPermission("rulegems.admin")
                || player.hasPermission("rulegems.gems"));
    }

    public boolean canOpenRulers(Player player) {
        return player != null && (player.hasPermission("rulegems.admin")
                || player.hasPermission("rulegems.rulers"));
    }

    // ========== 布局辅助方法 ==========

    /**
     * 填充装饰行（第5行）和控制栏背景
     */
    public void fillDecoration(Inventory gui) {
        ItemStack filler = ItemBuilder.filler();

        // 第5行装饰 (36-44)
        for (int i = 36; i <= 44; i++) {
            gui.setItem(i, filler);
        }

        // 控制栏空位填充 (45-53 中未使用的)
        int[] decorSlots = { 48, 50 };
        for (int slot : decorSlots) {
            gui.setItem(slot, filler);
        }
    }

    /**
     * 添加标准控制栏
     */
    public void addControlBar(Inventory gui, int currentPage, int totalPages, int totalItems,
            boolean showFilter, boolean showBack) {
        // 上一页
        gui.setItem(SLOT_PREV, ItemBuilder.prevButton(
                currentPage, navActionKey,
                rawMsg("control.prev"), rawMsg("control.page")));

        // 返回按钮（可选）
        if (showBack) {
            gui.setItem(SLOT_BACK, ItemBuilder.backButton(navActionKey, rawMsg("control.back")));
        } else {
            gui.setItem(SLOT_BACK, ItemBuilder.filler());
        }

        // 筛选按钮（可选）
        if (showFilter && isFilterImplemented()) {
            gui.setItem(SLOT_FILTER, new ItemBuilder(Material.HOPPER)
                    .name("&e" + rawMsg("control.filter"))
                    .addLore("&7" + rawMsg("control.filter_hint"))
                    .data(navActionKey, "filter")
                    .hideAttributes()
                    .build());
        } else {
            gui.setItem(SLOT_FILTER, ItemBuilder.filler());
        }

        // 页码信息
        gui.setItem(SLOT_INFO, ItemBuilder.pageInfo(
                currentPage, totalPages, totalItems,
                rawMsg("control.page"), rawMsg("control.total")));

        // 刷新按钮
        gui.setItem(SLOT_REFRESH, ItemBuilder.refreshButton(navActionKey, rawMsg("control.refresh")));

        // 关闭按钮
        gui.setItem(SLOT_CLOSE, ItemBuilder.closeButton(navActionKey, rawMsg("control.close")));

        // 下一页
        gui.setItem(SLOT_NEXT, ItemBuilder.nextButton(
                currentPage, totalPages, navActionKey,
                rawMsg("control.next"), rawMsg("control.page")));
    }

    // ========== 事件处理 ==========

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player))
            return;

        Inventory inventory = event.getInventory();
        GUIHolder holder = GUIHolder.getHolder(inventory);

        // 不是我们的 GUI
        if (holder == null)
            return;

        // 阻止物品移动
        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();

        if (clicked == null || clicked.getType() == Material.AIR)
            return;
        if (clicked.getType() == Material.GRAY_STAINED_GLASS_PANE)
            return; // 忽略填充物

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null)
            return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        // 处理导航操作
        String navAction = pdc.get(navActionKey, PersistentDataType.STRING);
        if (navAction != null) {
            handleNavigation(player, holder, navAction);
            return;
        }

        // 根据 GUI 类型委托给对应的 ChestMenu
        ChestMenu menu = getMenuForHolder(holder);
        if (menu != null) {
            menu.onClick(player, holder, event.getSlot(), clicked, pdc, event.isShiftClick());
        }
    }

    private boolean isFilterImplemented() {
        return true;
    }

    /**
     * Maps a GUIHolder type to its corresponding ChestMenu instance.
     */
    private ChestMenu getMenuForHolder(GUIHolder holder) {
        switch (holder.getType()) {
            case GEMS:
                return gemsGUI;
            case RULERS:
                return rulersGUI;
            case RULER_APPOINTEES:
                return rulerAppointeesGUI;
            case PROFILE:
                return profileGUI;
            case CABINET:
                return cabinetGUI;
            case CABINET_MEMBERS:
                return cabinetMembersGUI;
            case POWER_TOGGLES:
                return powerTogglesGUI;
            case MAIN_MENU:
                return mainMenuGUI;
            default:
                return null;
        }
    }

    /**
     * 处理导航操作
     */
    private void handleNavigation(Player player, GUIHolder holder, String action) {
        switch (action) {
            case "prev":
                reopenCurrentGUI(player, holder, Math.max(0, holder.getPage() - 1));
                break;
            case "next":
                reopenCurrentGUI(player, holder, Math.max(0, holder.getPage() + 1));
                break;
            case "close":
                player.closeInventory();
                break;
            case "refresh":
                reopenCurrentGUI(player, holder, holder.getPage());
                break;
            case "back":
                openBackDestination(player, holder);
                break;
            case "filter":
                cycleGemFilter(player, holder);
                break;
            case "open_gems":
                openGemsGUI(player, holder.isAdmin());
                break;
            case "open_rulers":
                openRulersGUI(player, holder.isAdmin());
                break;
            case "show_redeem_help":
                sendRedeemGuide(player);
                break;
            case "show_navigate_help":
                sendNavigateGuide(player);
                break;
            case "open_profile":
                openProfileGUI(player);
                break;
            case "open_cabinet":
                openCabinetFromMenu(player);
                break;
        }
    }

    private void openCabinetFromMenu(Player player) {
        if (plugin.getFeatureManager() == null || plugin.getFeatureManager().getAppointFeature() == null
                || !plugin.getFeatureManager().getAppointFeature().isEnabled()) {
            lang.sendMessage(player, "command.appoint.disabled");
            return;
        }
        if (!canOpenCabinet(player)) {
            lang.sendMessage(player, "command.no_permission");
            return;
        }
        openCabinetGUI(player);
    }

    private void cycleGemFilter(Player player, GUIHolder holder) {
        if (holder.getType() != GUIHolder.GUIType.GEMS) {
            return;
        }
        String current = holder.getContext();
        String next;
        if (current == null || current.isEmpty()) {
            next = "held";
        } else if ("held".equalsIgnoreCase(current)) {
            next = "placed";
        } else {
            next = null;
        }
        openGemsGUI(player, holder.isAdmin(), 0, next);
    }

    private void sendRedeemGuide(Player player) {
        lang.sendMessage(player, "command.help.section_player");
        if (plugin.getGameplayConfig().isRedeemEnabled() && player.hasPermission("rulegems.redeem")) {
            lang.sendMessage(player, "command.help.redeem");
        }
        if (plugin.getGameplayConfig().isHoldToRedeemEnabled()
                && plugin.getGameplayConfig().isRedeemEnabled()
                && player.hasPermission("rulegems.redeem")) {
            lang.sendMessage(player, plugin.getGameplayConfig().isSneakToRedeem()
                    ? "command.help.hold_redeem_sneak"
                    : "command.help.hold_redeem_normal");
        }
        if (plugin.getGameplayConfig().isPlaceRedeemEnabled()) {
            lang.sendMessage(player, "command.help.place_redeem");
        }
        if (plugin.getGameplayConfig().isFullSetGrantsAllEnabled()
                && player.hasPermission("rulegems.redeemall")) {
            lang.sendMessage(player, "command.help.redeemall");
        }
    }

    private void sendNavigateGuide(Player player) {
        lang.sendMessage(player, "command.help.section_player");
        if (plugin.getFeatureManager() != null
                && plugin.getFeatureManager().getNavigator() != null
                && plugin.getFeatureManager().getNavigator().isEnabled()
                && player.hasPermission("rulegems.navigate")) {
            lang.sendMessage(player, "command.help.navigate");
        } else {
            player.sendMessage(org.cubexmc.utils.ColorUtils.translateColorCodes(rawMsg("menu.navigate_disabled_chat")));
        }
    }

    private void openBackDestination(Player player, GUIHolder holder) {
        if (holder.getType() == GUIHolder.GUIType.RULER_APPOINTEES) {
            openRulersGUI(player, holder.isAdmin());
            return;
        }
        if (holder.getType() == GUIHolder.GUIType.CABINET_MEMBERS) {
            openCabinetGUI(player);
            return;
        }
        if (holder.getType() == GUIHolder.GUIType.POWER_TOGGLES) {
            openProfileGUI(player);
            return;
        }
        openMainMenu(player, holder.isAdmin());
    }

    /**
     * 按 holder 当前状态重新打开同一个 GUI，可用于翻页和刷新。
     */
    private void reopenCurrentGUI(Player player, GUIHolder holder, int page) {
        switch (holder.getType()) {
            case MAIN_MENU:
                openMainMenu(player, holder.isAdmin());
                break;
            case GEMS:
                openGemsGUI(player, holder.isAdmin(), page, holder.getContext());
                break;
            case RULERS:
                openRulersGUI(player, holder.isAdmin(), page);
                break;
            case RULER_APPOINTEES:
                UUID rulerUuid = parseContextUuid(holder, "GUI reopen");
                if (rulerUuid != null) {
                    openRulerAppointeesGUI(player, rulerUuid, holder.isAdmin(), page);
                }
                break;
            case PROFILE:
                openProfileGUI(player, page);
                break;
            case CABINET:
                openCabinetGUI(player, page);
                break;
            case CABINET_MEMBERS:
                if (holder.getContext() != null) {
                    openCabinetMembersGUI(player, holder.getContext(), page);
                }
                break;
            case POWER_TOGGLES:
                openPowerTogglesGUI(player, holder.isAdmin(), page);
                break;
            default:
                break;
        }
    }

    private UUID parseContextUuid(GUIHolder holder, String source) {
        String context = holder.getContext();
        if (context == null || context.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(context);
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to parse holder UUID context during " + source + ": " + e.getMessage());
            return null;
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory inventory = event.getInventory();
        GUIHolder holder = GUIHolder.getHolder(inventory);

        if (holder != null) {
            event.setCancelled(true);
        }
    }
}
