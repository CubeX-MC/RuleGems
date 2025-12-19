package org.cubexmc.gui;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
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
import org.cubexmc.utils.SchedulerUtil;

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
    public static final int GUI_SIZE = 54;           // 总槽位数
    public static final int CONTENT_ROWS = 4;        // 内容行数
    public static final int ITEMS_PER_PAGE = 36;     // 每页内容数 (4行 x 9列)
    
    // 控制栏槽位
    public static final int SLOT_PREV = 45;          // 上一页
    public static final int SLOT_BACK = 46;          // 返回
    public static final int SLOT_FILTER = 47;        // 筛选
    public static final int SLOT_INFO = 49;          // 页码信息
    public static final int SLOT_REFRESH = 51;       // 刷新
    public static final int SLOT_CLOSE = 52;         // 关闭
    public static final int SLOT_NEXT = 53;          // 下一页

    private final RuleGems plugin;
    private final GemManager gemManager;
    private final LanguageManager lang;
    
    // 持久化数据键
    private final NamespacedKey gemIdKey;
    private final NamespacedKey navActionKey;
    private final NamespacedKey playerUuidKey;

    private final MainMenuGUI mainMenuGUI;
    private final GemsGUI gemsGUI;
    private final RulersGUI rulersGUI;

    public GUIManager(RuleGems plugin, GemManager gemManager, LanguageManager languageManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.lang = languageManager;
        
        this.gemIdKey = new NamespacedKey(plugin, "gem_id");
        this.navActionKey = new NamespacedKey(plugin, "nav_action");
        this.playerUuidKey = new NamespacedKey(plugin, "player_uuid");

        // 初始化各个 GUI
        this.mainMenuGUI = new MainMenuGUI(this, gemManager, languageManager);
        this.gemsGUI = new GemsGUI(this, gemManager, languageManager);
        this.rulersGUI = new RulersGUI(this, gemManager, languageManager);

        // 注册事件监听器
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ========== Getter 方法 ==========
    
    public NamespacedKey getGemIdKey() { return gemIdKey; }
    public NamespacedKey getNavActionKey() { return navActionKey; }
    public NamespacedKey getPlayerUuidKey() { return playerUuidKey; }
    public RuleGems getPlugin() { return plugin; }

    /**
     * 获取语言消息（带颜色转换）
     */
    public String msg(String path) {
        return ChatColor.translateAlternateColorCodes('&', lang.getMessage("gui." + path));
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
        rulersGUI.open(player, isAdmin, page);
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
        int[] decorSlots = {48, 50};
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
        if (showFilter) {
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
        if (!(event.getWhoClicked() instanceof Player)) return;
        
        Inventory inventory = event.getInventory();
        GUIHolder holder = GUIHolder.getHolder(inventory);
        
        // 不是我们的 GUI
        if (holder == null) return;
        
        // 阻止物品移动
        event.setCancelled(true);
        
        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        
        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return; // 忽略填充物
        
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;
        
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        
        // 处理导航操作
        String navAction = pdc.get(navActionKey, PersistentDataType.STRING);
        if (navAction != null) {
            handleNavigation(player, holder, navAction);
            return;
        }
        
        // 根据 GUI 类型处理点击
        switch (holder.getType()) {
            case GEMS:
                handleGemsClick(player, holder, pdc);
                break;
            case RULERS:
                handleRulersClick(player, holder, pdc, clicked);
                break;
            default:
                break;
        }
    }

    /**
     * 处理导航操作
     */
    private void handleNavigation(Player player, GUIHolder holder, String action) {
        switch (action) {
            case "prev":
                navigatePage(player, holder, -1);
                break;
            case "next":
                navigatePage(player, holder, 1);
                break;
            case "close":
                player.closeInventory();
                break;
            case "refresh":
                refreshGUI(player, holder);
                break;
            case "back":
                // 返回主菜单
                openMainMenu(player, holder.isAdmin());
                break;
            case "filter":
                // 循环切换筛选（暂未实现）
                break;
            case "open_gems":
                // 从主菜单打开宝石列表
                openGemsGUI(player, holder.isAdmin());
                break;
            case "open_rulers":
                // 从主菜单打开统治者列表
                openRulersGUI(player, holder.isAdmin());
                break;
        }
    }

    /**
     * 翻页
     */
    private void navigatePage(Player player, GUIHolder holder, int delta) {
        int newPage = Math.max(0, holder.getPage() + delta);
        
        switch (holder.getType()) {
            case GEMS:
                openGemsGUI(player, holder.isAdmin(), newPage, holder.getFilter());
                break;
            case RULERS:
                openRulersGUI(player, holder.isAdmin(), newPage);
                break;
            default:
                break;
        }
    }

    /**
     * 刷新当前 GUI
     */
    private void refreshGUI(Player player, GUIHolder holder) {
        switch (holder.getType()) {
            case MAIN_MENU:
                openMainMenu(player, holder.isAdmin());
                break;
            case GEMS:
                openGemsGUI(player, holder.isAdmin(), holder.getPage(), holder.getFilter());
                break;
            case RULERS:
                openRulersGUI(player, holder.isAdmin(), holder.getPage());
                break;
            default:
                break;
        }
    }

    /**
     * 处理宝石 GUI 点击
     */
    private void handleGemsClick(Player player, GUIHolder holder, PersistentDataContainer pdc) {
        // 只有管理员可以传送
        if (!holder.isAdmin()) return;
        
        String gemIdStr = pdc.get(gemIdKey, PersistentDataType.STRING);
        if (gemIdStr == null) return;
        
        try {
            UUID gemId = UUID.fromString(gemIdStr);
            Player gemHolder = gemManager.getGemHolder(gemId);
            
            if (gemHolder != null && gemHolder.isOnline()) {
                player.closeInventory();
                SchedulerUtil.safeTeleport(plugin, player, gemHolder.getLocation());
                player.sendMessage(msg("gems.teleported_to_holder").replace("%player%", gemHolder.getName()));
            } else {
                Location loc = gemManager.getGemLocation(gemId);
                if (loc != null) {
                    player.closeInventory();
                    SchedulerUtil.safeTeleport(plugin, player, loc.clone().add(0.5, 1, 0.5));
                    player.sendMessage(msg("gems.teleported_to_location"));
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * 处理统治者 GUI 点击
     */
    private void handleRulersClick(Player player, GUIHolder holder, PersistentDataContainer pdc, ItemStack clicked) {
        // 只有管理员可以传送
        if (!holder.isAdmin()) return;
        
        // 检查是否点击了玩家头颅
        if (clicked.getType() != Material.PLAYER_HEAD) return;
        
        String playerUuidStr = pdc.get(playerUuidKey, PersistentDataType.STRING);
        if (playerUuidStr == null) return;
        
        try {
            UUID targetUuid = UUID.fromString(playerUuidStr);
            Player target = Bukkit.getPlayer(targetUuid);
            
            if (target != null && target.isOnline()) {
                player.closeInventory();
                SchedulerUtil.safeTeleport(plugin, player, target.getLocation());
                player.sendMessage(msg("rulers.teleported_to_player").replace("%player%", target.getName()));
            }
        } catch (Exception ignored) {}
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
