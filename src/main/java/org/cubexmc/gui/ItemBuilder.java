package org.cubexmc.gui;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 物品构建器 - 使用流式API简化物品创建
 */
public class ItemBuilder {

    private final ItemStack item;
    private final ItemMeta meta;

    /**
     * 创建新的物品构建器
     * @param material 材质
     */
    public ItemBuilder(Material material) {
        this.item = new ItemStack(material);
        this.meta = item.getItemMeta();
    }

    /**
     * 创建新的物品构建器（指定数量）
     * @param material 材质
     * @param amount 数量
     */
    public ItemBuilder(Material material, int amount) {
        this.item = new ItemStack(material, amount);
        this.meta = item.getItemMeta();
    }

    /**
     * 从现有物品创建构建器
     * @param item 现有物品
     */
    public ItemBuilder(ItemStack item) {
        this.item = item.clone();
        this.meta = this.item.getItemMeta();
    }

    /**
     * 设置显示名称
     * @param name 名称（支持颜色代码）
     */
    public ItemBuilder name(String name) {
        if (meta != null && name != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        }
        return this;
    }

    /**
     * 设置 Lore（覆盖）
     * @param lore Lore 行（支持颜色代码）
     */
    public ItemBuilder lore(String... lore) {
        if (meta != null && lore != null) {
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(coloredLore);
        }
        return this;
    }

    /**
     * 设置 Lore（覆盖）
     * @param lore Lore 列表
     */
    public ItemBuilder lore(List<String> lore) {
        if (meta != null && lore != null) {
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(coloredLore);
        }
        return this;
    }

    /**
     * 添加一行 Lore
     * @param line Lore 行
     */
    public ItemBuilder addLore(String line) {
        if (meta != null && line != null) {
            List<String> lore = meta.getLore();
            if (lore == null) {
                lore = new ArrayList<>();
            }
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
            meta.setLore(lore);
        }
        return this;
    }

    /**
     * 添加多行 Lore
     * @param lines Lore 行
     */
    public ItemBuilder addLore(String... lines) {
        if (meta != null && lines != null) {
            List<String> lore = meta.getLore();
            if (lore == null) {
                lore = new ArrayList<>();
            }
            for (String line : lines) {
                lore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(lore);
        }
        return this;
    }

    /**
     * 添加空行到 Lore
     */
    public ItemBuilder addEmptyLore() {
        return addLore("");
    }

    /**
     * 添加分隔线到 Lore
     */
    public ItemBuilder addSeparator() {
        return addLore("&8─────────────────");
    }

    /**
     * 添加附魔效果（隐藏附魔）
     */
    public ItemBuilder glow() {
        if (meta != null) {
            applyGlowEffect(meta);
        }
        return this;
    }

    /**
     * 版本兼容的发光效果工具方法。
     * 1.20.5+ 优先使用 setEnchantmentGlintOverride(true)，
     * 低版本回退到隐藏附魔 + HIDE_ENCHANTS。
     * 
     * @param meta 物品 Meta
     */
    public static void applyGlowEffect(ItemMeta meta) {
        if (meta == null) return;
        // 1.20.5+ API: setEnchantmentGlintOverride
        try {
            java.lang.reflect.Method glintMethod = meta.getClass().getMethod("setEnchantmentGlintOverride", boolean.class);
            glintMethod.invoke(meta, true);
            return;
        } catch (Throwable ignored) {
            // 旧版本不支持，回退
        }
        // 回退：使用任意附魔 + HIDE_ENCHANTS
        try {
            Enchantment glintEnchant = Enchantment.getByKey(NamespacedKey.minecraft("luck_of_the_sea"));
            if (glintEnchant != null) {
                meta.addEnchant(glintEnchant, 1, true);
            }
        } catch (Throwable e) { Bukkit.getLogger().fine("Failed to apply enchantment glint fallback: " + e.getMessage()); }
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
    }

    /**
     * 添加指定附魔
     * @param enchantment 附魔
     * @param level 等级
     */
    public ItemBuilder enchant(Enchantment enchantment, int level) {
        if (meta != null) {
            meta.addEnchant(enchantment, level, true);
        }
        return this;
    }

    /**
     * 隐藏所有属性
     */
    public ItemBuilder hideAttributes() {
        if (meta != null) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            try {
                meta.addItemFlags(ItemFlag.valueOf("HIDE_POTION_EFFECTS"));
            } catch (Throwable e) { Bukkit.getLogger().fine("HIDE_POTION_EFFECTS not available on this server version: " + e.getMessage()); }
        }
        return this;
    }

    /**
     * 添加物品标志
     * @param flags 标志
     */
    public ItemBuilder flags(ItemFlag... flags) {
        if (meta != null && flags != null) {
            meta.addItemFlags(flags);
        }
        return this;
    }

    /**
     * 设置数量
     * @param amount 数量
     */
    public ItemBuilder amount(int amount) {
        item.setAmount(amount);
        return this;
    }

    /**
     * 存储持久化数据（String）
     * @param key 键
     * @param value 值
     */
    public ItemBuilder data(NamespacedKey key, String value) {
        if (meta != null && key != null && value != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(key, PersistentDataType.STRING, value);
        }
        return this;
    }

    /**
     * 存储持久化数据（Integer）
     * @param key 键
     * @param value 值
     */
    public ItemBuilder data(NamespacedKey key, int value) {
        if (meta != null && key != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(key, PersistentDataType.INTEGER, value);
        }
        return this;
    }

    /**
     * 设置玩家头颅的拥有者
     * @param player 玩家
     */
    public ItemBuilder skullOwner(org.bukkit.OfflinePlayer player) {
        if (meta instanceof SkullMeta && player != null) {
            ((SkullMeta) meta).setOwningPlayer(player);
        }
        return this;
    }

    /**
     * 设置自定义模型数据
     * @param data 模型数据
     */
    public ItemBuilder customModelData(int data) {
        if (meta != null) {
            try {
                meta.setCustomModelData(data);
            } catch (Throwable ignored) {
                // 1.13 以下版本不支持
            }
        }
        return this;
    }

    /**
     * 构建最终物品
     * @return 构建的物品
     */
    public ItemStack build() {
        if (meta != null) {
            item.setItemMeta(meta);
        }
        return item;
    }

    // ========== 静态便捷方法 ==========

    /**
     * 创建填充用的灰色玻璃板
     */
    public static ItemStack filler() {
        return new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                .name(" ")
                .hideAttributes()
                .build();
    }

    /**
     * 创建上一页按钮
     * @param currentPage 当前页码
     * @param key 导航键
     */
    public static ItemStack prevButton(int currentPage, NamespacedKey key, String label, String pageLabel) {
        if (currentPage > 0) {
            return new ItemBuilder(Material.ARROW)
                    .name("&a« " + label)
                    .addLore("&7" + pageLabel + " " + currentPage)
                    .data(key, "prev")
                    .build();
        } else {
            return new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                    .name("&8« " + label)
                    .hideAttributes()
                    .build();
        }
    }

    /**
     * 创建下一页按钮
     * @param currentPage 当前页码
     * @param totalPages 总页数
     * @param key 导航键
     */
    public static ItemStack nextButton(int currentPage, int totalPages, NamespacedKey key, String label, String pageLabel) {
        if (currentPage < totalPages - 1) {
            return new ItemBuilder(Material.ARROW)
                    .name("&a" + label + " »")
                    .addLore("&7" + pageLabel + " " + (currentPage + 2))
                    .data(key, "next")
                    .build();
        } else {
            return new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                    .name("&8" + label + " »")
                    .hideAttributes()
                    .build();
        }
    }

    /**
     * 创建页码信息按钮
     */
    public static ItemStack pageInfo(int currentPage, int totalPages, int totalItems, 
                                      String pageLabel, String totalLabel) {
        return new ItemBuilder(Material.PAPER)
                .name("&e" + pageLabel + " &f" + (currentPage + 1) + "&7/&f" + totalPages)
                .addLore("&7" + totalLabel + ": &f" + totalItems)
                .hideAttributes()
                .build();
    }

    /**
     * 创建关闭按钮
     */
    public static ItemStack closeButton(NamespacedKey key, String label) {
        return new ItemBuilder(Material.BARRIER)
                .name("&c" + label)
                .data(key, "close")
                .build();
    }

    /**
     * 创建返回按钮
     */
    public static ItemStack backButton(NamespacedKey key, String label) {
        return new ItemBuilder(Material.OAK_DOOR)
                .name("&e" + label)
                .data(key, "back")
                .hideAttributes()
                .build();
    }

    /**
     * 创建筛选按钮
     */
    public static ItemStack filterButton(NamespacedKey key, String label, String currentFilter, String... options) {
        ItemBuilder builder = new ItemBuilder(Material.HOPPER)
                .name("&e" + label)
                .addLore("&7当前: &f" + currentFilter)
                .addEmptyLore();
        for (String option : options) {
            builder.addLore("&8• " + option);
        }
        return builder.data(key, "filter").hideAttributes().build();
    }

    /**
     * 创建刷新按钮
     */
    public static ItemStack refreshButton(NamespacedKey key, String label) {
        return new ItemBuilder(Material.SUNFLOWER)
                .name("&a" + label)
                .data(key, "refresh")
                .hideAttributes()
                .build();
    }
}

