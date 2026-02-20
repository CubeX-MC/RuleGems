package org.cubexmc.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AllowedCommand 单元测试
 */
class AllowedCommandTest {

    // ==================== 构造函数边界值 ====================

    @Test
    void constructor_usesClampedToMinusOne() {
        AllowedCommand cmd = new AllowedCommand("fly", -5, null, 0);
        assertEquals(-1, cmd.getUses(), "uses 应被钳位到 -1（无限）");
    }

    @Test
    void constructor_usesNormalValue() {
        AllowedCommand cmd = new AllowedCommand("fly", 3, null, 0);
        assertEquals(3, cmd.getUses());
    }

    @Test
    void constructor_cooldownClampedToZero() {
        AllowedCommand cmd = new AllowedCommand("fly", 1, null, -10);
        assertEquals(0, cmd.getCooldown(), "cooldown 应被钳位到 0");
    }

    @Test
    void constructor_nullExecuteCommandsFallbackToLabel() {
        AllowedCommand cmd = new AllowedCommand("fly", 1, null, 0);
        assertEquals(1, cmd.getCommands().size());
        assertEquals("fly", cmd.getCommands().get(0));
    }

    @Test
    void constructor_emptyExecuteCommandsFallbackToLabel() {
        AllowedCommand cmd = new AllowedCommand("fly", 1, Collections.emptyList(), 0);
        assertEquals(1, cmd.getCommands().size());
        assertEquals("fly", cmd.getCommands().get(0));
    }

    @Test
    void constructor_preservesExecuteCommands() {
        List<String> cmds = Arrays.asList("console:fly {player}", "give {player} diamond 1");
        AllowedCommand cmd = new AllowedCommand("tfly", 5, cmds, 10);
        assertEquals("tfly", cmd.getLabel());
        assertEquals(5, cmd.getUses());
        assertEquals(2, cmd.getCommands().size());
        assertEquals(10, cmd.getCooldown());
    }

    // ==================== isSimpleCommand ====================

    @Test
    void isSimpleCommand_trueWhenSingleMatchingCommand() {
        AllowedCommand cmd = new AllowedCommand("fly", 1, Collections.singletonList("fly"), 0);
        assertTrue(cmd.isSimpleCommand());
    }

    @Test
    void isSimpleCommand_trueWhenNullExecute() {
        AllowedCommand cmd = new AllowedCommand("fly", 1, null, 0);
        assertTrue(cmd.isSimpleCommand());
    }

    @Test
    void isSimpleCommand_falseWhenMultipleCommands() {
        AllowedCommand cmd = new AllowedCommand("tfly", 1,
                Arrays.asList("fly", "msg {player} Fly granted"), 0);
        assertFalse(cmd.isSimpleCommand());
    }

    @Test
    void isSimpleCommand_falseWhenConsolePrefixed() {
        AllowedCommand cmd = new AllowedCommand("fly", 1,
                Collections.singletonList("console:fly"), 0);
        assertFalse(cmd.isSimpleCommand());
    }

    @Test
    void isSimpleCommand_falseWhenPlayerOpPrefixed() {
        AllowedCommand cmd = new AllowedCommand("fly", 1,
                Collections.singletonList("player-op:fly"), 0);
        assertFalse(cmd.isSimpleCommand());
    }

    @Test
    void isSimpleCommand_falseWhenLabelDiffers() {
        AllowedCommand cmd = new AllowedCommand("tfly", 1,
                Collections.singletonList("fly"), 0);
        assertFalse(cmd.isSimpleCommand());
    }

    // ==================== parseExecutor ====================

    @Test
    void parseExecutor_consolePrefix() {
        String[] result = AllowedCommand.parseExecutor("console:fly {player}");
        assertEquals("console", result[0]);
        assertEquals("fly {player}", result[1]);
    }

    @Test
    void parseExecutor_playerOpPrefix() {
        String[] result = AllowedCommand.parseExecutor("player-op:fly");
        assertEquals("player-op", result[0]);
        assertEquals("fly", result[1]);
    }

    @Test
    void parseExecutor_noPrefix_defaultsToPlayerOp() {
        String[] result = AllowedCommand.parseExecutor("fly");
        assertEquals("player-op", result[0]);
        assertEquals("fly", result[1]);
    }

    @Test
    void parseExecutor_nullInput() {
        String[] result = AllowedCommand.parseExecutor(null);
        assertEquals("player-op", result[0]);
        assertEquals("", result[1]);
    }

    @Test
    void parseExecutor_emptyInput() {
        String[] result = AllowedCommand.parseExecutor("");
        assertEquals("player-op", result[0]);
        assertEquals("", result[1]);
    }

    @Test
    void parseExecutor_consolePrefixWithSpaces() {
        String[] result = AllowedCommand.parseExecutor("console:  give Steve diamond 1  ");
        assertEquals("console", result[0]);
        assertEquals("give Steve diamond 1", result[1]);
    }
}
