package org.cubexmc.manager;

import org.bukkit.entity.Player;
import org.cubexmc.model.AllowedCommand;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.model.PowerStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * GemAllowanceManager 单元测试
 * 测试命令限次的 grant/consume/refund 逻辑、held vs redeemed 隔离、
 * 可用标签聚合、脏标记等核心功能。
 */
@ExtendWith(MockitoExtension.class)
class GemAllowanceManagerTest {

    @Mock
    private GemDefinitionParser gemParser;

    @Mock
    private GameplayConfig gameplayConfig;

    private GemAllowanceManager manager;

    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID GEM_1 = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID GEM_2 = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        manager = new GemAllowanceManager(gemParser, gameplayConfig);
    }

    // ==================== Helper methods ====================

    private void putHeld(UUID player, UUID gemId, String label, int uses) {
        manager.getPlayerGemHeldUses()
                .computeIfAbsent(player, k -> new HashMap<>())
                .computeIfAbsent(gemId, k -> new HashMap<>())
                .put(label, uses);
    }

    private void putRedeemed(UUID player, UUID gemId, String label, int uses) {
        manager.getPlayerGemRedeemUses()
                .computeIfAbsent(player, k -> new HashMap<>())
                .computeIfAbsent(gemId, k -> new HashMap<>())
                .put(label, uses);
    }

    private void putGlobal(UUID player, String label, int uses) {
        manager.getPlayerGlobalAllowedUses()
                .computeIfAbsent(player, k -> new HashMap<>())
                .put(label, uses);
    }

    private GemDefinition createGemDefWithAllowed(String key, String label, int uses) {
        AllowedCommand ac = new AllowedCommand(label, uses, null, 0);
        PowerStructure ps = new PowerStructure();
        ps.setAllowedCommands(Collections.singletonList(ac));
        return new GemDefinition.Builder(key)
                .displayName("Test Gem").powerStructure(ps).build();
    }

    // ==================== hasAnyAllowed ====================

    @Nested
    class HasAnyAllowed {

        @Test
        void returnsFalseForNullInputs() {
            assertFalse(manager.hasAnyAllowed(null, "fly"));
            assertFalse(manager.hasAnyAllowed(PLAYER_A, null));
        }

        @Test
        void returnsFalseWhenNoData() {
            assertFalse(manager.hasAnyAllowed(PLAYER_A, "fly"));
        }

        @Test
        void returnsTrueFromGlobalPositive() {
            putGlobal(PLAYER_A, "fly", 3);
            assertTrue(manager.hasAnyAllowed(PLAYER_A, "fly"));
        }

        @Test
        void returnsTrueFromGlobalUnlimited() {
            putGlobal(PLAYER_A, "fly", -1);
            assertTrue(manager.hasAnyAllowed(PLAYER_A, "fly"));
        }

        @Test
        void returnsFalseFromGlobalZero() {
            putGlobal(PLAYER_A, "fly", 0);
            assertFalse(manager.hasAnyAllowed(PLAYER_A, "fly"));
        }

        @Test
        void returnsTrueFromHeldPositive() {
            putHeld(PLAYER_A, GEM_1, "fly", 2);
            assertTrue(manager.hasAnyAllowed(PLAYER_A, "fly"));
        }

        @Test
        void returnsTrueFromRedeemedPositive() {
            putRedeemed(PLAYER_A, GEM_1, "fly", 1);
            assertTrue(manager.hasAnyAllowed(PLAYER_A, "fly"));
        }

        @Test
        void isCaseInsensitive() {
            putGlobal(PLAYER_A, "fly", 5);
            assertTrue(manager.hasAnyAllowed(PLAYER_A, "FLY"));
            assertTrue(manager.hasAnyAllowed(PLAYER_A, "Fly"));
        }

        @Test
        void returnsFalseForDifferentLabel() {
            putGlobal(PLAYER_A, "fly", 3);
            assertFalse(manager.hasAnyAllowed(PLAYER_A, "heal"));
        }
    }

    // ==================== tryConsumeAllowed ====================

    @Nested
    class TryConsumeAllowed {

        @Test
        void returnsFalseForNullInputs() {
            assertFalse(manager.tryConsumeAllowed(null, "fly"));
            assertFalse(manager.tryConsumeAllowed(PLAYER_A, null));
        }

        @Test
        void returnsFalseWhenNoData() {
            assertFalse(manager.tryConsumeAllowed(PLAYER_A, "fly"));
        }

        @Test
        void consumesFromHeldFirst() {
            putHeld(PLAYER_A, GEM_1, "fly", 3);
            putRedeemed(PLAYER_A, GEM_2, "fly", 5);
            putGlobal(PLAYER_A, "fly", 10);

            assertTrue(manager.tryConsumeAllowed(PLAYER_A, "fly"));

            // Held should be decremented from 3 to 2
            int heldRemaining = manager.getPlayerGemHeldUses().get(PLAYER_A).get(GEM_1).get("fly");
            assertEquals(2, heldRemaining);

            // Redeemed and global should be unchanged
            assertEquals(5, manager.getPlayerGemRedeemUses().get(PLAYER_A).get(GEM_2).get("fly"));
            assertEquals(10, manager.getPlayerGlobalAllowedUses().get(PLAYER_A).get("fly"));
        }

        @Test
        void fallsToRedeemedWhenHeldExhausted() {
            putHeld(PLAYER_A, GEM_1, "fly", 0);
            putRedeemed(PLAYER_A, GEM_2, "fly", 3);

            assertTrue(manager.tryConsumeAllowed(PLAYER_A, "fly"));
            assertEquals(2, manager.getPlayerGemRedeemUses().get(PLAYER_A).get(GEM_2).get("fly"));
        }

        @Test
        void fallsToGlobalWhenInstancesExhausted() {
            putHeld(PLAYER_A, GEM_1, "fly", 0);
            putRedeemed(PLAYER_A, GEM_2, "fly", 0);
            putGlobal(PLAYER_A, "fly", 5);

            assertTrue(manager.tryConsumeAllowed(PLAYER_A, "fly"));
            assertEquals(4, manager.getPlayerGlobalAllowedUses().get(PLAYER_A).get("fly"));
        }

        @Test
        void returnsFalseWhenAllExhausted() {
            putHeld(PLAYER_A, GEM_1, "fly", 0);
            putRedeemed(PLAYER_A, GEM_2, "fly", 0);
            putGlobal(PLAYER_A, "fly", 0);

            assertFalse(manager.tryConsumeAllowed(PLAYER_A, "fly"));
        }

        @Test
        void unlimitedHeldDoesNotDecrement() {
            putHeld(PLAYER_A, GEM_1, "fly", -1);

            assertTrue(manager.tryConsumeAllowed(PLAYER_A, "fly"));
            // Should stay -1 (unlimited)
            assertEquals(-1, manager.getPlayerGemHeldUses().get(PLAYER_A).get(GEM_1).get("fly"));
        }

        @Test
        void unlimitedRedeemedDoesNotDecrement() {
            putRedeemed(PLAYER_A, GEM_1, "fly", -1);

            assertTrue(manager.tryConsumeAllowed(PLAYER_A, "fly"));
            assertEquals(-1, manager.getPlayerGemRedeemUses().get(PLAYER_A).get(GEM_1).get("fly"));
        }

        @Test
        void unlimitedGlobalDoesNotDecrement() {
            putGlobal(PLAYER_A, "fly", -1);

            assertTrue(manager.tryConsumeAllowed(PLAYER_A, "fly"));
            assertEquals(-1, manager.getPlayerGlobalAllowedUses().get(PLAYER_A).get("fly"));
        }

        @Test
        void isCaseInsensitive() {
            putGlobal(PLAYER_A, "fly", 2);
            assertTrue(manager.tryConsumeAllowed(PLAYER_A, "FLY"));
            assertEquals(1, manager.getPlayerGlobalAllowedUses().get(PLAYER_A).get("fly"));
        }

        @Test
        void consumeDecrementsToZero() {
            putGlobal(PLAYER_A, "fly", 1);
            assertTrue(manager.tryConsumeAllowed(PLAYER_A, "fly"));
            assertEquals(0, manager.getPlayerGlobalAllowedUses().get(PLAYER_A).get("fly"));
            // Second consume should fail
            assertFalse(manager.tryConsumeAllowed(PLAYER_A, "fly"));
        }
    }

    // ==================== refundAllowed ====================

    @Nested
    class RefundAllowed {

        @Test
        void refundsToHeldFirst() {
            putHeld(PLAYER_A, GEM_1, "fly", 2);
            putRedeemed(PLAYER_A, GEM_2, "fly", 3);

            manager.refundAllowed(PLAYER_A, "fly");

            assertEquals(3, manager.getPlayerGemHeldUses().get(PLAYER_A).get(GEM_1).get("fly"));
            // Redeemed should be unchanged
            assertEquals(3, manager.getPlayerGemRedeemUses().get(PLAYER_A).get(GEM_2).get("fly"));
        }

        @Test
        void refundsToRedeemedWhenNoHeld() {
            putRedeemed(PLAYER_A, GEM_1, "fly", 2);

            manager.refundAllowed(PLAYER_A, "fly");
            assertEquals(3, manager.getPlayerGemRedeemUses().get(PLAYER_A).get(GEM_1).get("fly"));
        }

        @Test
        void refundsToGlobalWhenNoInstances() {
            manager.refundAllowed(PLAYER_A, "fly");

            // Should create global entry with 1
            assertEquals(1, manager.getPlayerGlobalAllowedUses().get(PLAYER_A).get("fly"));
        }

        @Test
        void refundIgnoresNullInputs() {
            // Should not throw
            manager.refundAllowed(null, "fly");
            manager.refundAllowed(PLAYER_A, null);
            assertTrue(manager.getPlayerGlobalAllowedUses().isEmpty());
        }

        @Test
        void isCaseInsensitive() {
            putGlobal(PLAYER_A, "fly", 1);
            manager.refundAllowed(PLAYER_A, "FLY");
            assertEquals(2, manager.getPlayerGlobalAllowedUses().get(PLAYER_A).get("fly"));
        }
    }

    // ==================== getRemainingAllowed ====================

    @Nested
    class GetRemainingAllowed {

        @Test
        void returnsZeroForNullInputs() {
            assertEquals(0, manager.getRemainingAllowed(null, "fly"));
            assertEquals(0, manager.getRemainingAllowed(PLAYER_A, null));
        }

        @Test
        void sumsAcrossAllSources() {
            putHeld(PLAYER_A, GEM_1, "fly", 3);
            putRedeemed(PLAYER_A, GEM_2, "fly", 5);
            putGlobal(PLAYER_A, "fly", 2);

            assertEquals(10, manager.getRemainingAllowed(PLAYER_A, "fly"));
        }

        @Test
        void sumsMultipleHeldInstances() {
            putHeld(PLAYER_A, GEM_1, "fly", 2);
            putHeld(PLAYER_A, GEM_2, "fly", 3);

            assertEquals(5, manager.getRemainingAllowed(PLAYER_A, "fly"));
        }

        @Test
        void returnsMinusOneForUnlimitedHeld() {
            putHeld(PLAYER_A, GEM_1, "fly", -1);
            putGlobal(PLAYER_A, "fly", 5);

            assertEquals(-1, manager.getRemainingAllowed(PLAYER_A, "fly"));
        }

        @Test
        void returnsMinusOneForUnlimitedRedeemed() {
            putRedeemed(PLAYER_A, GEM_1, "fly", -1);
            assertEquals(-1, manager.getRemainingAllowed(PLAYER_A, "fly"));
        }

        @Test
        void returnsMinusOneForUnlimitedGlobal() {
            putGlobal(PLAYER_A, "fly", -1);
            assertEquals(-1, manager.getRemainingAllowed(PLAYER_A, "fly"));
        }

        @Test
        void returnsZeroWhenNoData() {
            assertEquals(0, manager.getRemainingAllowed(PLAYER_A, "fly"));
        }

        @Test
        void isCaseInsensitive() {
            putGlobal(PLAYER_A, "fly", 7);
            assertEquals(7, manager.getRemainingAllowed(PLAYER_A, "FLY"));
        }
    }

    // ==================== getAvailableCommandLabels ====================

    @Nested
    class GetAvailableCommandLabels {

        @Test
        void returnsEmptyForNull() {
            assertTrue(manager.getAvailableCommandLabels(null).isEmpty());
        }

        @Test
        void returnsEmptyWhenNoData() {
            assertTrue(manager.getAvailableCommandLabels(PLAYER_A).isEmpty());
        }

        @Test
        void aggregatesFromAllSources() {
            putHeld(PLAYER_A, GEM_1, "fly", 2);
            putRedeemed(PLAYER_A, GEM_2, "heal", 1);
            putGlobal(PLAYER_A, "speed", 3);

            Set<String> labels = manager.getAvailableCommandLabels(PLAYER_A);
            assertEquals(3, labels.size());
            assertTrue(labels.contains("fly"));
            assertTrue(labels.contains("heal"));
            assertTrue(labels.contains("speed"));
        }

        @Test
        void excludesZeroRemaining() {
            putGlobal(PLAYER_A, "fly", 0);
            putGlobal(PLAYER_A, "heal", 3);

            Set<String> labels = manager.getAvailableCommandLabels(PLAYER_A);
            assertEquals(1, labels.size());
            assertTrue(labels.contains("heal"));
        }

        @Test
        void includesUnlimited() {
            putGlobal(PLAYER_A, "fly", -1);

            Set<String> labels = manager.getAvailableCommandLabels(PLAYER_A);
            assertTrue(labels.contains("fly"));
        }

        @Test
        void returnsDefensiveCopy() {
            putGlobal(PLAYER_A, "fly", 3);
            Set<String> labels1 = manager.getAvailableCommandLabels(PLAYER_A);
            labels1.add("injected");
            Set<String> labels2 = manager.getAvailableCommandLabels(PLAYER_A);
            assertFalse(labels2.contains("injected"));
        }

        @Test
        void cacheInvalidatedAfterConsume() {
            putGlobal(PLAYER_A, "fly", 1);
            Set<String> before = manager.getAvailableCommandLabels(PLAYER_A);
            assertTrue(before.contains("fly"));

            manager.tryConsumeAllowed(PLAYER_A, "fly");
            Set<String> after = manager.getAvailableCommandLabels(PLAYER_A);
            assertFalse(after.contains("fly"));
        }
    }

    // ==================== clearPlayerData ====================

    @Nested
    class ClearPlayerData {

        @Test
        void removesAllDataForPlayer() {
            putHeld(PLAYER_A, GEM_1, "fly", 3);
            putRedeemed(PLAYER_A, GEM_2, "heal", 2);
            putGlobal(PLAYER_A, "speed", 1);

            manager.clearPlayerData(PLAYER_A);

            assertNull(manager.getPlayerGemHeldUses().get(PLAYER_A));
            assertNull(manager.getPlayerGemRedeemUses().get(PLAYER_A));
            assertNull(manager.getPlayerGlobalAllowedUses().get(PLAYER_A));
        }

        @Test
        void doesNotAffectOtherPlayers() {
            putGlobal(PLAYER_A, "fly", 3);
            putGlobal(PLAYER_B, "heal", 5);

            manager.clearPlayerData(PLAYER_A);

            assertNull(manager.getPlayerGlobalAllowedUses().get(PLAYER_A));
            assertEquals(5, manager.getPlayerGlobalAllowedUses().get(PLAYER_B).get("heal"));
        }

        @Test
        void handlesNullGracefully() {
            // Should not throw
            manager.clearPlayerData(null);
        }
    }

    // ==================== grantGlobalAllowedCommands ====================

    @Nested
    class GrantGlobalAllowedCommands {

        @Test
        void grantsAllowedCommandsFromDefinition() {
            GemDefinition def = createGemDefWithAllowed("fire_gem", "fireball", 5);
            Player mockPlayer = mock(Player.class);
            when(mockPlayer.getUniqueId()).thenReturn(PLAYER_A);

            manager.grantGlobalAllowedCommands(mockPlayer, def);

            Map<String, Integer> global = manager.getPlayerGlobalAllowedUses().get(PLAYER_A);
            assertNotNull(global);
            assertEquals(5, global.get("fireball"));
        }

        @Test
        void handlesNullInputs() {
            GemDefinition def = createGemDefWithAllowed("fire_gem", "fireball", 5);
            Player mockPlayer = mock(Player.class);

            // Should not throw
            manager.grantGlobalAllowedCommands(null, def);
            manager.grantGlobalAllowedCommands(mockPlayer, null);
        }

        @Test
        void handlesDefinitionWithNoAllowed() {
            GemDefinition def = new GemDefinition.Builder("basic")
                    .displayName("Basic").build();
            Player mockPlayer = mock(Player.class);
            // getUniqueId() may or may not be called depending on early return
            lenient().when(mockPlayer.getUniqueId()).thenReturn(PLAYER_A);

            manager.grantGlobalAllowedCommands(mockPlayer, def);

            // No global data should be created (empty allowed commands, no puts)
            Map<String, Integer> global = manager.getPlayerGlobalAllowedUses().get(PLAYER_A);
            // Map might have been created but empty, or null
            assertTrue(global == null || global.isEmpty());
        }

        @Test
        void grantsUnlimitedUses() {
            GemDefinition def = createGemDefWithAllowed("fire_gem", "fireball", -1);
            Player mockPlayer = mock(Player.class);
            when(mockPlayer.getUniqueId()).thenReturn(PLAYER_A);

            manager.grantGlobalAllowedCommands(mockPlayer, def);

            assertEquals(-1, manager.getPlayerGlobalAllowedUses().get(PLAYER_A).get("fireball"));
        }
    }

    // ==================== reassignHeldInstanceAllowance ====================

    @Nested
    class ReassignHeldInstanceAllowance {

        @Test
        void transfersFromOldToNewOwner() {
            GemDefinition def = createGemDefWithAllowed("fire_gem", "fireball", 10);
            putHeld(PLAYER_A, GEM_1, "fireball", 7);

            manager.reassignHeldInstanceAllowance(GEM_1, PLAYER_B, def);

            // PLAYER_A should lose the gem
            Map<UUID, Map<String, Integer>> playerAHeld = manager.getPlayerGemHeldUses().get(PLAYER_A);
            assertTrue(playerAHeld == null || !playerAHeld.containsKey(GEM_1));

            // PLAYER_B should get the existing payload (7)
            assertEquals(7, manager.getPlayerGemHeldUses().get(PLAYER_B).get(GEM_1).get("fireball"));
        }

        @Test
        void doesNotResetWhenSameOwner() {
            GemDefinition def = createGemDefWithAllowed("fire_gem", "fireball", 10);
            putHeld(PLAYER_A, GEM_1, "fireball", 3);

            manager.reassignHeldInstanceAllowance(GEM_1, PLAYER_A, def);

            // Should remain 3, not reset to 10
            assertEquals(3, manager.getPlayerGemHeldUses().get(PLAYER_A).get(GEM_1).get("fireball"));
        }

        @Test
        void createsNewAllowanceForNewGem() {
            GemDefinition def = createGemDefWithAllowed("fire_gem", "fireball", 10);

            manager.reassignHeldInstanceAllowance(GEM_1, PLAYER_A, def);

            // Should create with default uses (10)
            assertEquals(10, manager.getPlayerGemHeldUses().get(PLAYER_A).get(GEM_1).get("fireball"));
        }

        @Test
        void handlesNullInputs() {
            GemDefinition def = createGemDefWithAllowed("fire_gem", "fireball", 10);
            // Should not throw
            manager.reassignHeldInstanceAllowance(null, PLAYER_A, def);
            manager.reassignHeldInstanceAllowance(GEM_1, null, def);
            manager.reassignHeldInstanceAllowance(GEM_1, PLAYER_A, null);
        }
    }

    // ==================== reassignRedeemInstanceAllowance ====================

    @Nested
    class ReassignRedeemInstanceAllowance {

        @Test
        void transfersFromOldToNewOwner() {
            GemDefinition def = createGemDefWithAllowed("fire_gem", "fireball", 10);
            putRedeemed(PLAYER_A, GEM_1, "fireball", 4);

            manager.reassignRedeemInstanceAllowance(GEM_1, PLAYER_B, def, false);

            Map<UUID, Map<String, Integer>> playerARedeem = manager.getPlayerGemRedeemUses().get(PLAYER_A);
            assertTrue(playerARedeem == null || !playerARedeem.containsKey(GEM_1));

            // Existing payload (4) is transferred (not reset)
            assertEquals(4, manager.getPlayerGemRedeemUses().get(PLAYER_B).get(GEM_1).get("fireball"));
        }

        @Test
        void resetEvenIfSameOwner() {
            GemDefinition def = createGemDefWithAllowed("fire_gem", "fireball", 10);
            putRedeemed(PLAYER_A, GEM_1, "fireball", 2);

            manager.reassignRedeemInstanceAllowance(GEM_1, PLAYER_A, def, true);

            // Should be reset to 10 (from definition)
            assertEquals(10, manager.getPlayerGemRedeemUses().get(PLAYER_A).get(GEM_1).get("fireball"));
        }

        @Test
        void sameOwnerWithoutResetDoesNothing() {
            GemDefinition def = createGemDefWithAllowed("fire_gem", "fireball", 10);
            putRedeemed(PLAYER_A, GEM_1, "fireball", 2);

            manager.reassignRedeemInstanceAllowance(GEM_1, PLAYER_A, def, false);

            // Should remain 2
            assertEquals(2, manager.getPlayerGemRedeemUses().get(PLAYER_A).get(GEM_1).get("fireball"));
        }

        @Test
        void resetWhenTransferBetweenDifferentPlayers() {
            GemDefinition def = createGemDefWithAllowed("fire_gem", "fireball", 10);
            putRedeemed(PLAYER_A, GEM_1, "fireball", 4);

            manager.reassignRedeemInstanceAllowance(GEM_1, PLAYER_B, def, true);

            // Reset overrides existing payload → uses definition default (10)
            assertEquals(10, manager.getPlayerGemRedeemUses().get(PLAYER_B).get(GEM_1).get("fireball"));
        }
    }

    // ==================== Dirty flag ====================

    @Nested
    class DirtyFlag {

        @Test
        void initiallyClean() {
            assertFalse(manager.isDirty());
        }

        @Test
        void dirtyAfterConsume() {
            putGlobal(PLAYER_A, "fly", 3);
            manager.tryConsumeAllowed(PLAYER_A, "fly");
            assertTrue(manager.isDirty());
        }

        @Test
        void dirtyAfterRefund() {
            manager.refundAllowed(PLAYER_A, "fly");
            assertTrue(manager.isDirty());
        }

        @Test
        void flushResetsDirty() {
            Runnable mockCallback = mock(Runnable.class);
            manager.setSaveCallback(mockCallback);

            putGlobal(PLAYER_A, "fly", 3);
            manager.tryConsumeAllowed(PLAYER_A, "fly");
            assertTrue(manager.isDirty());

            manager.flushIfDirty();
            assertFalse(manager.isDirty());
            verify(mockCallback, times(1)).run();
        }

        @Test
        void flushDoesNothingWhenClean() {
            Runnable mockCallback = mock(Runnable.class);
            manager.setSaveCallback(mockCallback);

            manager.flushIfDirty();
            verify(mockCallback, never()).run();
        }

        @Test
        void dirtyAfterUnlimitedConsume() {
            // Even unlimited consume marks dirty (for consistency)
            putGlobal(PLAYER_A, "fly", -1);
            manager.tryConsumeAllowed(PLAYER_A, "fly");
            assertTrue(manager.isDirty());
        }
    }

    // ==================== clearAll ====================

    @Nested
    class ClearAll {

        @Test
        void clearsAllInternalData() {
            putHeld(PLAYER_A, GEM_1, "fly", 3);
            putRedeemed(PLAYER_A, GEM_2, "heal", 2);
            putGlobal(PLAYER_A, "speed", 1);
            // Force label cache to build
            manager.getAvailableCommandLabels(PLAYER_A);

            manager.clearAll();

            assertTrue(manager.getPlayerGemHeldUses().isEmpty());
            assertTrue(manager.getPlayerGemRedeemUses().isEmpty());
            assertTrue(manager.getPlayerGlobalAllowedUses().isEmpty());
            assertTrue(manager.getAvailableCommandLabels(PLAYER_A).isEmpty());
        }
    }

    // ==================== getAllowedCommand ====================

    @Nested
    class GetAllowedCommand {

        @Test
        void findsFromGemDefinitions() {
            AllowedCommand ac = new AllowedCommand("fly", 5, null, 10);
            GemDefinition def = createGemDefWithAllowed("fire_gem", "fly", 5);
            when(gemParser.getGemDefinitions()).thenReturn(Collections.singletonList(def));

            AllowedCommand result = manager.getAllowedCommand(PLAYER_A, "fly");
            assertNotNull(result);
            assertEquals("fly", result.getLabel());
        }

        @Test
        void returnsNullForNonexistentLabel() {
            when(gemParser.getGemDefinitions()).thenReturn(Collections.emptyList());
            when(gameplayConfig.getRedeemAllPowerStructure()).thenReturn(null);

            assertNull(manager.getAllowedCommand(PLAYER_A, "nonexistent"));
        }

        @Test
        void returnsNullForNullInputs() {
            assertNull(manager.getAllowedCommand(null, "fly"));
            assertNull(manager.getAllowedCommand(PLAYER_A, null));
        }

        @Test
        void findsFromRedeemAllPower() {
            when(gemParser.getGemDefinitions()).thenReturn(Collections.emptyList());

            AllowedCommand ac = new AllowedCommand("superfly", 3, null, 0);
            PowerStructure ps = new PowerStructure();
            ps.setAllowedCommands(Collections.singletonList(ac));
            when(gameplayConfig.getRedeemAllPowerStructure()).thenReturn(ps);

            AllowedCommand result = manager.getAllowedCommand(PLAYER_A, "superfly");
            assertNotNull(result);
            assertEquals("superfly", result.getLabel());
        }
    }

    // ==================== Isolation between players ====================

    @Nested
    class PlayerIsolation {

        @Test
        void differentPlayersHaveIndependentAllowances() {
            putGlobal(PLAYER_A, "fly", 3);
            putGlobal(PLAYER_B, "fly", 10);

            manager.tryConsumeAllowed(PLAYER_A, "fly");

            assertEquals(2, manager.getRemainingAllowed(PLAYER_A, "fly"));
            assertEquals(10, manager.getRemainingAllowed(PLAYER_B, "fly"));
        }

        @Test
        void heldAndRedeemedAreIndependent() {
            putHeld(PLAYER_A, GEM_1, "fly", 3);
            putRedeemed(PLAYER_A, GEM_2, "fly", 5);

            // Consume should take from held first
            manager.tryConsumeAllowed(PLAYER_A, "fly");
            assertEquals(2, manager.getPlayerGemHeldUses().get(PLAYER_A).get(GEM_1).get("fly"));
            assertEquals(5, manager.getPlayerGemRedeemUses().get(PLAYER_A).get(GEM_2).get("fly"));

            // Total remaining should be 2 + 5 = 7
            assertEquals(7, manager.getRemainingAllowed(PLAYER_A, "fly"));
        }
    }
}
