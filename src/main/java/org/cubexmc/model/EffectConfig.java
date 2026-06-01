package org.cubexmc.model;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Objects;

/**
 * 药水效果配置
 * 用于 PowerStructure 中的效果管理
 */
public class EffectConfig {

    private final PotionEffectType effectType;
    private final int amplifier;  // 效果等级（0 = I级, 1 = II级, ...）
    private final boolean ambient;  // 是否为环境效果（粒子效果更少）
    private final boolean particles;  // 是否显示粒子
    private final boolean icon;  // 是否在屏幕上显示图标

    /**
     * 完整构造函数
     */
    public EffectConfig(PotionEffectType effectType, int amplifier, boolean ambient, boolean particles, boolean icon) {
        this.effectType = effectType;
        this.amplifier = amplifier;
        this.ambient = ambient;
        this.particles = particles;
        this.icon = icon;
    }

    /**
     * 简化构造函数（使用默认值）
     */
    public EffectConfig(PotionEffectType effectType, int amplifier) {
        this(effectType, amplifier, false, true, true);
    }

    /**
     * 最简构造函数（I级效果）
     */
    public EffectConfig(PotionEffectType effectType) {
        this(effectType, 0, false, true, true);
    }

    // ==================== Getters ====================

    public PotionEffectType getEffectType() {
        return effectType;
    }

    public int getAmplifier() {
        return amplifier;
    }

    public boolean isAmbient() {
        return ambient;
    }

    public boolean hasParticles() {
        return particles;
    }

    public boolean hasIcon() {
        return icon;
    }

    // ==================== 应用方法 ====================

    /**
     * 为玩家应用此效果（无限持续时间）
     * 使用 Integer.MAX_VALUE 作为持续时间，使效果持续直到被手动移除
     * 
     * @param player 目标玩家
     */
    public void apply(Player player) {
        if (player == null || !player.isOnline() || effectType == null) return;
        
        // 使用很长的持续时间（约68年的 ticks）
        // 这样效果会一直持续直到被移除
        PotionEffect effect = new PotionEffect(
            effectType,
            Integer.MAX_VALUE,  // 无限持续
            amplifier,
            ambient,
            particles,
            icon
        );
        
        player.addPotionEffect(effect);
    }

    /**
     * 移除玩家的此效果
     * 
     * @param player 目标玩家
     */
    public void remove(Player player) {
        if (player == null || !player.isOnline() || effectType == null) return;
        player.removePotionEffect(effectType);
    }

    /**
     * 检查玩家是否拥有此效果
     * 
     * @param player 目标玩家
     * @return 是否拥有效果
     */
    public boolean hasEffect(Player player) {
        if (player == null || effectType == null) return false;
        return player.hasPotionEffect(effectType);
    }

    /**
     * 创建用于显示的描述文本
     * 
     * @return 效果描述
     */
    public String getDescription() {
        if (effectType == null) return "Unknown";
        
        String name = effectType.getName();
        // 将下划线转换为空格，并首字母大写
        name = name.replace("_", " ");
        String[] words = name.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.length() > 0) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                  .append(word.substring(1).toLowerCase())
                  .append(" ");
            }
        }
        
        String level = toRomanNumeral(amplifier + 1);
        return sb.toString().trim() + " " + level;
    }

    /**
     * 将数字转换为罗马数字
     */
    private String toRomanNumeral(int number) {
        if (number <= 0) return String.valueOf(number);
        if (number > 10) return String.valueOf(number);
        
        String[] numerals = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return numerals[number - 1];
    }

    /**
     * 创建副本
     */
    public EffectConfig copy() {
        return new EffectConfig(effectType, amplifier, ambient, particles, icon);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        EffectConfig other = (EffectConfig) obj;
        return amplifier == other.amplifier
                && ambient == other.ambient
                && particles == other.particles
                && icon == other.icon
                && Objects.equals(effectType, other.effectType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(effectType, amplifier, ambient, particles, icon);
    }

    @Override
    public String toString() {
        return "EffectConfig{" +
                "type=" + (effectType != null ? effectType.getName() : "null") +
                ", amplifier=" + amplifier +
                ", ambient=" + ambient +
                ", particles=" + particles +
                ", icon=" + icon +
                '}';
    }
}
