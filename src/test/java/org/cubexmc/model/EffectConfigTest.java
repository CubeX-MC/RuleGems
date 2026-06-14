package org.cubexmc.model;

import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EffectConfig 单元测试（使用 Mockito mock Bukkit 类型）
 * 
 * 注意：PotionEffectType 是 abstract class，这里使用 PotionEffectType 的已注册静态实例。
 * 在无服务端环境下，Bukkit 静态字段可能为 null，因此对依赖 PotionEffectType 实例的测试
 * 做了 null-safe 处理。
 */
class EffectConfigTest {

    // ==================== equals / hashCode ====================

    @Test
    void equals_sameValues_areEqual() {
        // 使用 null PotionEffectType 来测试纯逻辑
        EffectConfig a = new EffectConfig(null, 2, true, false, true);
        EffectConfig b = new EffectConfig(null, 2, true, false, true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentAmplifier_notEqual() {
        EffectConfig a = new EffectConfig(null, 1, false, true, true);
        EffectConfig b = new EffectConfig(null, 2, false, true, true);
        assertNotEquals(a, b);
    }

    @Test
    void equals_differentAmbient_notEqual() {
        EffectConfig a = new EffectConfig(null, 1, true, true, true);
        EffectConfig b = new EffectConfig(null, 1, false, true, true);
        assertNotEquals(a, b);
    }

    @Test
    void equals_differentParticles_notEqual() {
        EffectConfig a = new EffectConfig(null, 1, false, true, true);
        EffectConfig b = new EffectConfig(null, 1, false, false, true);
        assertNotEquals(a, b);
    }

    @Test
    void equals_differentIcon_notEqual() {
        EffectConfig a = new EffectConfig(null, 1, false, true, true);
        EffectConfig b = new EffectConfig(null, 1, false, true, false);
        assertNotEquals(a, b);
    }

    @Test
    void equals_reflexive() {
        EffectConfig a = new EffectConfig(null, 3, true, true, false);
        assertEquals(a, a);
    }

    @Test
    void equals_null_notEqual() {
        EffectConfig a = new EffectConfig(null, 0);
        assertNotEquals(null, a);
    }

    @Test
    void equals_differentClass_notEqual() {
        EffectConfig a = new EffectConfig(null, 0);
        assertNotEquals("string", a);
    }

    // ==================== copy ====================

    @Test
    void copy_producesEqualButDistinct() {
        EffectConfig original = new EffectConfig(null, 3, true, false, true);
        EffectConfig copy = original.copy();
        assertEquals(original, copy);
        assertNotSame(original, copy);
    }

    @Test
    void copy_preservesAllFields() {
        EffectConfig o = new EffectConfig(null, 5, true, false, false);
        EffectConfig c = o.copy();
        assertEquals(5, c.getAmplifier());
        assertTrue(c.isAmbient());
        assertFalse(c.hasParticles());
        assertFalse(c.hasIcon());
    }

    // ==================== 构造函数默认值 ====================

    @Test
    void simplifiedConstructor_defaults() {
        EffectConfig cfg = new EffectConfig(null, 2);
        assertEquals(2, cfg.getAmplifier());
        assertFalse(cfg.isAmbient());
        assertTrue(cfg.hasParticles());
        assertTrue(cfg.hasIcon());
    }

    @Test
    void minimalConstructor_defaults() {
        EffectConfig cfg = new EffectConfig(null);
        assertEquals(0, cfg.getAmplifier());
        assertFalse(cfg.isAmbient());
        assertTrue(cfg.hasParticles());
        assertTrue(cfg.hasIcon());
    }

    // ==================== getDescription ====================

    @Test
    void getDescription_nullType_returnsUnknown() {
        EffectConfig cfg = new EffectConfig(null, 0);
        assertEquals("Unknown", cfg.getDescription());
    }

    // ==================== toRomanNumeral (via getDescription) ====================
    // toRomanNumeral is private, tested indirectly if PotionEffectType is available.
    // We test the null-type case above; further roman numeral testing
    // requires a live PotionEffectType instance (integration test).

    // ==================== toString ====================

    @Test
    void toString_nullType_containsNull() {
        EffectConfig cfg = new EffectConfig(null, 1, true, false, true);
        String s = cfg.toString();
        assertTrue(s.contains("null"), "toString 应包含 'null' 当 type 为 null 时");
        assertTrue(s.contains("amplifier=1"));
        assertTrue(s.contains("ambient=true"));
    }

    // ==================== configure：防夜视闪烁的钳制 ====================

    @Test
    void configure_safeValues_keptAsIs() {
        EffectConfig.Companion.configure(400, 60, null);
        assertEquals(400, EffectConfig.Companion.getDurationTicks());
        assertEquals(60, EffectConfig.Companion.getRefreshIntervalTicks());
    }

    @Test
    void configure_durationBelowFlickerThreshold_isClampedUp() {
        // 单次 5s(100t) 会持续闪烁；应被上调到至少 200+20+refresh
        EffectConfig.Companion.configure(100, 60, null);
        assertEquals(60, EffectConfig.Companion.getRefreshIntervalTicks());
        assertEquals(
                EffectConfig.NIGHT_VISION_FLICKER_THRESHOLD_TICKS + 20 + 60,
                EffectConfig.Companion.getDurationTicks());
    }

    @Test
    void configure_nonPositiveRefresh_coercedToOne() {
        EffectConfig.Companion.configure(400, 0, null);
        assertEquals(1, EffectConfig.Companion.getRefreshIntervalTicks());
    }

    @Test
    void configure_alwaysKeepsRemainingAboveFlickerThreshold() {
        // 不变性：任意输入下，两次刷新之间的剩余时长都 > 200t
        int[][] inputs = {{0, 0}, {100, 60}, {220, 200}, {400, 60}, {50, 500}};
        for (int[] in : inputs) {
            EffectConfig.Companion.configure(in[0], in[1], null);
            int duration = EffectConfig.Companion.getDurationTicks();
            int refresh = EffectConfig.Companion.getRefreshIntervalTicks();
            assertTrue(
                    duration - refresh > EffectConfig.NIGHT_VISION_FLICKER_THRESHOLD_TICKS,
                    "duration(" + duration + ") - refresh(" + refresh + ") 必须 > "
                            + EffectConfig.NIGHT_VISION_FLICKER_THRESHOLD_TICKS);
        }
    }
}
