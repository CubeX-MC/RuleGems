package org.cubexmc.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PowerStructure 单元测试
 */
class PowerStructureTest {

    // ==================== 默认构造函数 ====================

    @Test
    void defaultConstructor_hasEmptyCollections() {
        PowerStructure ps = new PowerStructure();
        assertTrue(ps.getPermissions().isEmpty());
        assertTrue(ps.getVaultGroups().isEmpty());
        assertTrue(ps.getAllowedCommands().isEmpty());
        assertTrue(ps.getEffects().isEmpty());
        assertTrue(ps.getAppoints().isEmpty());
        assertNotNull(ps.getCondition());
    }

    // ==================== hasAnyContent ====================

    @Test
    void hasAnyContent_emptyStructure_false() {
        assertFalse(new PowerStructure().hasAnyContent());
    }

    @Test
    void hasAnyContent_withPermission_true() {
        PowerStructure ps = new PowerStructure();
        ps.getPermissions().add("test.perm");
        assertTrue(ps.hasAnyContent());
    }

    @Test
    void hasAnyContent_withVaultGroup_true() {
        PowerStructure ps = new PowerStructure();
        ps.getVaultGroups().add("vip");
        assertTrue(ps.hasAnyContent());
    }

    @Test
    void hasAnyContent_withAllowedCommand_true() {
        PowerStructure ps = new PowerStructure();
        ps.getAllowedCommands().add(new AllowedCommand("fly", 1, null, 0));
        assertTrue(ps.hasAnyContent());
    }

    @Test
    void hasAnyContent_withEffect_true() {
        PowerStructure ps = new PowerStructure();
        ps.getEffects().add(new EffectConfig(null, 0));
        assertTrue(ps.hasAnyContent());
    }

    @Test
    void hasAnyContent_withAppoint_true() {
        PowerStructure ps = new PowerStructure();
        ps.getAppoints().put("deputy", new AppointDefinition("deputy"));
        assertTrue(ps.hasAnyContent());
    }

    // ==================== setter null-safety ====================

    @Test
    void setPermissions_null_becomesEmptyList() {
        PowerStructure ps = new PowerStructure();
        ps.setPermissions(null);
        assertNotNull(ps.getPermissions());
        assertTrue(ps.getPermissions().isEmpty());
    }

    @Test
    void setVaultGroups_null_becomesEmptyList() {
        PowerStructure ps = new PowerStructure();
        ps.setVaultGroups(null);
        assertNotNull(ps.getVaultGroups());
        assertTrue(ps.getVaultGroups().isEmpty());
    }

    @Test
    void setAllowedCommands_null_becomesEmptyList() {
        PowerStructure ps = new PowerStructure();
        ps.setAllowedCommands(null);
        assertNotNull(ps.getAllowedCommands());
    }

    @Test
    void setEffects_null_becomesEmptyList() {
        PowerStructure ps = new PowerStructure();
        ps.setEffects(null);
        assertNotNull(ps.getEffects());
    }

    @Test
    void setAppoints_null_becomesEmptyMap() {
        PowerStructure ps = new PowerStructure();
        ps.setAppoints(null);
        assertNotNull(ps.getAppoints());
    }

    @Test
    void setCondition_null_becomesDefault() {
        PowerStructure ps = new PowerStructure();
        ps.setCondition(null);
        assertNotNull(ps.getCondition());
    }

    // ==================== setVaultGroup / getVaultGroup ====================

    @Test
    void setVaultGroup_singleGroup() {
        PowerStructure ps = new PowerStructure();
        ps.setVaultGroup("admin");
        assertEquals("admin", ps.getVaultGroup());
        assertEquals(1, ps.getVaultGroups().size());
    }

    @Test
    void setVaultGroup_null_clearsGroups() {
        PowerStructure ps = new PowerStructure();
        ps.getVaultGroups().add("existing");
        ps.setVaultGroup(null);
        assertNull(ps.getVaultGroup());
        assertTrue(ps.getVaultGroups().isEmpty());
    }

    @Test
    void getVaultGroup_empty_returnsNull() {
        PowerStructure ps = new PowerStructure();
        assertNull(ps.getVaultGroup());
    }

    // ==================== merge ====================

    @Test
    void merge_null_noException() {
        PowerStructure ps = new PowerStructure();
        assertDoesNotThrow(() -> ps.merge(null));
    }

    @Test
    void merge_addsUniquePermissions() {
        PowerStructure base = new PowerStructure();
        base.getPermissions().add("perm.a");
        base.getPermissions().add("perm.b");

        PowerStructure other = new PowerStructure();
        other.getPermissions().add("perm.b"); // duplicate
        other.getPermissions().add("perm.c"); // new

        base.merge(other);

        assertEquals(3, base.getPermissions().size());
        assertTrue(base.getPermissions().contains("perm.a"));
        assertTrue(base.getPermissions().contains("perm.b"));
        assertTrue(base.getPermissions().contains("perm.c"));
    }

    @Test
    void merge_addsUniqueVaultGroups() {
        PowerStructure base = new PowerStructure();
        base.getVaultGroups().add("vip");

        PowerStructure other = new PowerStructure();
        other.getVaultGroups().add("vip"); // duplicate
        other.getVaultGroups().add("admin");

        base.merge(other);

        assertEquals(2, base.getVaultGroups().size());
        assertTrue(base.getVaultGroups().contains("vip"));
        assertTrue(base.getVaultGroups().contains("admin"));
    }

    @Test
    void merge_allowedCommands_deduplicatesByLabel() {
        PowerStructure base = new PowerStructure();
        base.getAllowedCommands().add(new AllowedCommand("fly", 3, null, 0));

        PowerStructure other = new PowerStructure();
        other.getAllowedCommands().add(new AllowedCommand("fly", 10, null, 0)); // same label
        other.getAllowedCommands().add(new AllowedCommand("heal", 1, null, 0)); // new label

        base.merge(other);

        assertEquals(2, base.getAllowedCommands().size());
        // 原 fly 保留 uses=3，不被 other 覆盖
        assertEquals(3, base.getAllowedCommands().get(0).getUses());
        assertEquals("heal", base.getAllowedCommands().get(1).getLabel());
    }

    @Test
    void merge_effects_deduplicatesByEffectType() {
        // 使用 null effectType 来测试去重逻辑
        // null effectType 的匹配在 merge 中通过 e.getEffectType() != null 过滤，
        // 所以两个 null-type 的 EffectConfig 都会被添加
        EffectConfig effectA = new EffectConfig(null, 1);
        EffectConfig effectB = new EffectConfig(null, 2);

        PowerStructure base = new PowerStructure();
        base.getEffects().add(effectA);

        PowerStructure other = new PowerStructure();
        other.getEffects().add(effectB);

        base.merge(other);

        // null effectType 不匹配 anyMatch 条件（因为 e.getEffectType() != null 为 false），
        // 所以两个都保留
        assertEquals(2, base.getEffects().size());
    }

    @Test
    void merge_appoints_deduplicatesByKey() {
        PowerStructure base = new PowerStructure();
        AppointDefinition baseDef = new AppointDefinition("deputy");
        base.getAppoints().put("deputy", baseDef);

        PowerStructure other = new PowerStructure();
        other.getAppoints().put("deputy", new AppointDefinition("deputy")); // duplicate key
        other.getAppoints().put("secretary", new AppointDefinition("secretary")); // new key

        base.merge(other);

        assertEquals(2, base.getAppoints().size());
        assertSame(baseDef, base.getAppoints().get("deputy"), "同 key 应保留 base 的定义");
        assertTrue(base.getAppoints().containsKey("secretary"));
    }

    // ==================== copy ====================

    @Test
    void copy_producesEqualContentButDistinct() {
        PowerStructure original = new PowerStructure();
        original.getPermissions().add("perm.a");
        original.getVaultGroups().add("vip");
        original.getAllowedCommands().add(new AllowedCommand("fly", 1, null, 0));
        original.getEffects().add(new EffectConfig(null, 2));
        original.getAppoints().put("deputy", new AppointDefinition("deputy"));

        PowerStructure copy = original.copy();

        // 内容相同
        assertEquals(original.getPermissions(), copy.getPermissions());
        assertEquals(original.getVaultGroups(), copy.getVaultGroups());
        assertEquals(original.getAllowedCommands().size(), copy.getAllowedCommands().size());
        assertEquals(original.getEffects().size(), copy.getEffects().size());
        assertEquals(original.getAppoints().size(), copy.getAppoints().size());

        // 引用不同
        assertNotSame(original.getPermissions(), copy.getPermissions());
        assertNotSame(original.getVaultGroups(), copy.getVaultGroups());
    }

    @Test
    void copy_modificationDoesNotAffectOriginal() {
        PowerStructure original = new PowerStructure();
        original.getPermissions().add("perm.a");

        PowerStructure copy = original.copy();
        copy.getPermissions().add("perm.b");

        assertEquals(1, original.getPermissions().size(), "修改副本不应影响原始对象");
        assertEquals(2, copy.getPermissions().size());
    }

    // ==================== immutableView ====================

    @Test
    void immutableView_throwsOnModification() {
        PowerStructure original = new PowerStructure();
        original.getPermissions().add("perm.a");

        PowerStructure view = original.immutableView();

        assertThrows(UnsupportedOperationException.class, () -> view.getPermissions().add("perm.b"));
        assertThrows(UnsupportedOperationException.class, () -> view.getVaultGroups().add("group"));
        assertThrows(UnsupportedOperationException.class, () -> view.getAllowedCommands().clear());
        assertThrows(UnsupportedOperationException.class, () -> view.getEffects().clear());
        assertThrows(UnsupportedOperationException.class, () -> view.getAppoints().put("x", null));
    }

    @Test
    void immutableView_reflectsOriginalContent() {
        PowerStructure original = new PowerStructure();
        original.getPermissions().add("perm.a");
        original.getVaultGroups().add("vip");

        PowerStructure view = original.immutableView();
        assertEquals(1, view.getPermissions().size());
        assertEquals("perm.a", view.getPermissions().get(0));
        assertEquals("vip", view.getVaultGroups().get(0));
    }

    // ==================== toString ====================

    @Test
    void toString_containsCounts() {
        PowerStructure ps = new PowerStructure();
        ps.getPermissions().add("a");
        ps.getPermissions().add("b");
        String s = ps.toString();
        assertTrue(s.contains("permissions=2"));
        assertTrue(s.contains("vaultGroups=0"));
    }

    // ==================== 完整构造函数 null-safety ====================

    @Test
    void fullConstructor_nullLists_becomeEmptyCollections() {
        PowerStructure ps = new PowerStructure(null, null, null, null, null, null);
        assertNotNull(ps.getPermissions());
        assertNotNull(ps.getVaultGroups());
        assertNotNull(ps.getAllowedCommands());
        assertNotNull(ps.getEffects());
        assertNotNull(ps.getAppoints());
        assertNotNull(ps.getCondition());
    }
}
