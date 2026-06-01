package org.cubexmc.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.GemPermissionManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.model.GemDefinition;

public class PowerTogglesGUI extends ChestMenu {

    private static final int ITEMS_PER_PAGE = 27;
    private static final int[] ITEM_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35
    };

    private final GemManager gemManager;
    private final LanguageManager lang;

    public PowerTogglesGUI(GUIManager guiManager, GemManager gemManager, LanguageManager languageManager) {
        super(guiManager);
        this.gemManager = gemManager;
        this.lang = languageManager;
    }

    @Override
    protected String getTitle() {
        return msg("power_toggles.title");
    }

    @Override
    protected int getSize() {
        return GUIManager.GUI_SIZE;
    }

    @Override
    protected GUIHolder.GUIType getHolderType() {
        return GUIHolder.GUIType.POWER_TOGGLES;
    }

    public void open(Player player, int page) {
        GemPermissionManager permManager = gemManager.getPermissionManager();
        Set<String> rulerKeys = gemManager.getCurrentRulers().getOrDefault(player.getUniqueId(), java.util.Collections.emptySet());

        List<String> ownedGems = rulerKeys.stream()
                .filter(key -> !"ALL".equals(key))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());

        int totalItems = ownedGems.size();
        int totalPages = Math.max(1, (int) Math.ceil(totalItems / (double) ITEMS_PER_PAGE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        String title = msg("power_toggles.title");
        if (totalPages > 1) {
            title += " &8(" + (page + 1) + "/" + totalPages + ")";
        }
        title = org.cubexmc.utils.ColorUtils.translateColorCodes(title);

        GUIHolder holder = new GUIHolder(GUIHolder.GUIType.POWER_TOGGLES, player.getUniqueId(), player.hasPermission("rulegems.admin"), page);
        Inventory gui = Bukkit.createInventory(holder, GUIManager.GUI_SIZE, title);
        holder.setInventory(gui);

        manager.fillDecoration(gui);
        manager.addControlBar(gui, page, totalPages, totalItems, false, true);

        // Add back to profile button at bottom left
        gui.setItem(45, new ItemBuilder(Material.ARROW)
                .name("&a" + rawMsg("power_toggles.back_to_profile"))
                .build());

        if (ownedGems.isEmpty()) {
            gui.setItem(22, new ItemBuilder(Material.BARRIER)
                    .name("&c" + rawMsg("power_toggles.no_gems"))
                    .addLore("&7" + rawMsg("power_toggles.no_gems_lore"))
                    .build());
        } else {
            int startIndex = page * ITEMS_PER_PAGE;
            int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, ownedGems.size());
            for (int i = startIndex; i < endIndex; i++) {
                gui.setItem(ITEM_SLOTS[i - startIndex], createGemToggleItem(player, ownedGems.get(i), permManager));
            }
        }

        player.openInventory(gui);
    }

    private ItemStack createGemToggleItem(Player player, String gemKey, GemPermissionManager permManager) {
        GemDefinition definition = gemManager.findGemDefinitionByKey(gemKey);
        boolean isOff = permManager.isGemToggledOff(player.getUniqueId(), gemKey);
        
        String displayName = definition != null ? definition.getDisplayName() : gemKey;
        Material material = definition != null ? definition.getMaterial() : Material.EMERALD;

        ItemBuilder builder = new ItemBuilder(material)
                .name(org.cubexmc.utils.ColorUtils.translateColorCodes("&f" + displayName))
                .hideAttributes()
                .addEmptyLore();
        
        if (isOff) {
            builder.addLore("&c" + rawMsg("power_toggles.status_off"));
            builder.addLore("&7" + rawMsg("power_toggles.click_to_enable"));
        } else {
            builder.glow();
            builder.addLore("&a" + rawMsg("power_toggles.status_on"));
            builder.addLore("&7" + rawMsg("power_toggles.click_to_disable"));
        }

        // We use localized name to store the gem key for easy retrieval in click handler
        return builder.build(); // Unfortunately ItemBuilder in this project may not have a simple way to attach NBT/PDC.
        // We will rely on inventory slot or list index, but since we sort we might just parse the name or use PDC if ItemBuilder supports it.
        // Let's just use the click handler logic in GUIManager using the item's position if possible, 
        // or actually ItemBuilder has `localizedName` in Spigot. But wait, `PowerTogglesGUI` does not need to handle clicks itself, `GUIManager` does.
        // So I'll add the gemKey to the invisible lore or just re-calculate from page.
    }

    private String msg(String path) {
        return org.cubexmc.utils.ColorUtils.translateColorCodes(lang.getMessage("gui." + path));
    }

    private String rawMsg(String path) {
        return lang.getMessage("gui." + path);
    }

    @Override
    public void onClick(Player player, GUIHolder holder, int slot, ItemStack clicked, org.bukkit.persistence.PersistentDataContainer pdc, boolean isShiftClick) {
        if (slot == 45) {
            manager.openProfileGUI(player);
            return;
        }

        int index = -1;
        for (int i = 0; i < ITEM_SLOTS.length; i++) {
            if (ITEM_SLOTS[i] == slot) {
                index = i;
                break;
            }
        }
        if (index == -1) return;

        Set<String> rulerKeys = gemManager.getCurrentRulers().getOrDefault(player.getUniqueId(), java.util.Collections.emptySet());
        List<String> ownedGems = rulerKeys.stream()
                .filter(key -> !"ALL".equals(key))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());

        int itemIndex = holder.getPage() * ITEMS_PER_PAGE + index;
        if (itemIndex >= 0 && itemIndex < ownedGems.size()) {
            String gemKey = ownedGems.get(itemIndex);
            GemPermissionManager permManager = gemManager.getPermissionManager();
            boolean currentlyOff = permManager.isGemToggledOff(player.getUniqueId(), gemKey);
            
            permManager.toggleGemPower(player, gemKey, currentlyOff); // Pass 'enabled = currentlyOff'
            
            if (currentlyOff) {
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 2.0f);
            } else {
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
            }
            
            // Reopen GUI to refresh state
            open(player, holder.getPage());
        }
    }
}
