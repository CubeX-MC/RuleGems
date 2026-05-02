package org.cubexmc.manager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.potion.PotionEffectType;
import org.cubexmc.RuleGems;
import org.cubexmc.model.EffectConfig;
import org.cubexmc.model.PowerCondition;
import org.cubexmc.model.PowerStructure;
import org.cubexmc.provider.PermissionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PowerStructureManagerTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private RuleGems plugin;
    private PermissionProvider permissionProvider;
    private Player player;
    private PermissionAttachment attachment;
    private PowerStructureManager manager;

    @BeforeEach
    void setUp() {
        plugin = mock(RuleGems.class);
        permissionProvider = mock(PermissionProvider.class);
        player = mock(Player.class);
        attachment = mock(PermissionAttachment.class);

        when(plugin.getLogger()).thenReturn(Logger.getLogger("PowerStructureManagerTest"));
        when(plugin.getPermissionProvider()).thenReturn(permissionProvider);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.isOnline()).thenReturn(true);
        when(player.addAttachment(plugin)).thenReturn(attachment);

        manager = new PowerStructureManager(plugin);
    }

    @Test
    void sharedPermissionStaysUntilLastSourceRemoved() {
        PowerStructure first = structureWithPermission("rulegems.shared");
        PowerStructure second = structureWithPermission("rulegems.shared");

        manager.applyStructure(player, first, "appoint", "knight", false);
        manager.applyStructure(player, second, "appoint", "minister", false);

        verify(attachment, times(1)).setPermission("rulegems.shared", true);

        manager.removeStructure(player, first, "appoint", "knight");
        verify(attachment, never()).unsetPermission("rulegems.shared");

        manager.removeStructure(player, second, "appoint", "minister");
        verify(attachment, times(1)).unsetPermission("rulegems.shared");
    }

    @Test
    void sharedVaultGroupStaysUntilLastSourceRemoved() {
        PowerStructure first = structureWithGroup("ruler");
        PowerStructure second = structureWithGroup("ruler");

        manager.applyStructure(player, first, "gem_redeem", "fire", false);
        manager.applyStructure(player, second, "gem_redeem", "water", false);

        verify(permissionProvider, times(1)).addGroup(player, "ruler");

        manager.removeStructure(player, first, "gem_redeem", "fire");
        verify(permissionProvider, never()).removeGroup(player, "ruler");

        manager.removeStructure(player, second, "gem_redeem", "water");
        verify(permissionProvider, times(1)).removeGroup(player, "ruler");
    }

    @Test
    void clearNamespaceRemovesTrackedGroups() {
        PowerStructure structure = structureWithGroup("minister");

        manager.applyStructure(player, structure, "appoint", "minister", false);
        manager.clearNamespace(player, "appoint");

        verify(permissionProvider, times(1)).removeGroup(player, "minister");
    }

    @Test
    void effectAppliedAndRemovedWithSingleSource() {
        PowerStructure structure = structureWithEffect(PotionEffectType.SPEED, 1);

        manager.applyStructure(player, structure, "appoint", "knight", false);
        verify(player, times(1)).addPotionEffect(org.mockito.ArgumentMatchers.argThat(
                effect -> effect.getType().equals(PotionEffectType.SPEED) && effect.getAmplifier() == 1));

        manager.removeStructure(player, structure, "appoint", "knight");
        verify(player, times(1)).removePotionEffect(PotionEffectType.SPEED);
    }

    @Test
    void sharedEffectStaysUntilLastSourceRemoved() {
        PowerStructure first = structureWithEffect(PotionEffectType.NIGHT_VISION, 0);
        PowerStructure second = structureWithEffect(PotionEffectType.NIGHT_VISION, 1);

        manager.applyStructure(player, first, "appoint", "guard", false);
        manager.applyStructure(player, second, "appoint", "advisor", false);

        manager.removeStructure(player, first, "appoint", "guard");
        verify(player, never()).removePotionEffect(PotionEffectType.NIGHT_VISION);

        manager.removeStructure(player, second, "appoint", "advisor");
        verify(player, times(1)).removePotionEffect(PotionEffectType.NIGHT_VISION);
    }

    @Test
    void clearNamespaceRemovesTrackedEffects() {
        PowerStructure structure = structureWithEffect(PotionEffectType.JUMP_BOOST, 0);

        manager.applyStructure(player, structure, "gem_redeem", "spring", false);
        manager.clearNamespace(player, "gem_redeem");

        verify(player, times(1)).removePotionEffect(PotionEffectType.JUMP_BOOST);
    }

    @Test
    void applyStructureSkipsWhenConditionFails() {
        PowerCondition condition = mock(PowerCondition.class);
        when(condition.hasAnyCondition()).thenReturn(true);
        when(condition.checkConditions(player)).thenReturn(false);

        PowerStructure structure = structureWithPermission("rulegems.conditional");
        structure.setCondition(condition);

        org.junit.jupiter.api.Assertions.assertFalse(
                manager.applyStructure(player, structure, "appoint", "conditional", true));
        verify(attachment, never()).setPermission("rulegems.conditional", true);
    }

    private PowerStructure structureWithPermission(String permission) {
        PowerStructure structure = new PowerStructure();
        structure.setPermissions(Collections.singletonList(permission));
        return structure;
    }

    private PowerStructure structureWithGroup(String group) {
        PowerStructure structure = new PowerStructure();
        structure.setVaultGroups(Collections.singletonList(group));
        return structure;
    }

    private PowerStructure structureWithEffect(PotionEffectType effectType, int amplifier) {
        PowerStructure structure = new PowerStructure();
        structure.setEffects(Collections.singletonList(new EffectConfig(effectType, amplifier)));
        return structure;
    }
}
