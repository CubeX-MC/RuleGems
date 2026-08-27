package org.cubexmc.manager;

import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoryLoggerTest {
    @TempDir Path directory;
    private HistoryLogger history;

    @BeforeEach
    void setup() {
        RuleGems plugin = mock(RuleGems.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("HistoryLoggerTest"));
        history = new HistoryLogger(plugin, null);
    }

    @Test
    void pagesKeepNewestFileAndNewestLineOrderingWithExactTotals() throws Exception {
        Files.write(directory.resolve("history/2026-07.log"), List.of("old-1", "old-2"));
        Files.write(directory.resolve("history/2026-08.log"), List.of("new-1", "new-2", "new-3"));
        assertEquals(List.of("new-3", "new-2", "new-1"), history.getRecentHistoryPage(1, 3).getEntries());
        var second = history.getRecentHistoryPage(2, 3);
        assertEquals(List.of("old-2", "old-1"), second.getEntries());
        assertEquals(5, second.getTotalCount());
        assertTrue(history.getRecentHistoryPage(Integer.MAX_VALUE, Integer.MAX_VALUE).getEntries().isEmpty());
        assertEquals(5, history.getRecentHistoryPage(1, 0).getTotalCount());
    }

    @Test
    void playerFilterPreservesEnglishChineseAndCaseInsensitiveFormats() throws Exception {
        Files.write(directory.resolve("history/2026-08.log"),
                List.of("Player: Angus old", "Player: Other", "玩家: ANGUS new", "Player: Other last"));
        var page = history.getPlayerHistoryPage("angus", 1, 10);
        assertEquals(List.of("玩家: ANGUS new", "Player: Angus old"), page.getEntries());
        assertEquals(2, page.getTotalCount());
    }

    @Test
    void largeFilePaginationMatchesReferenceResults() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) lines.add("Player: Angus " + i);
        Files.write(directory.resolve("history/2026-08.log"), lines);
        Collections.reverse(lines);
        for (int page : List.of(1, 2, 800, 2000)) {
            var result = history.getRecentHistoryPage(page, 10);
            assertEquals(lines.subList((page - 1) * 10, page * 10), result.getEntries());
            assertEquals(lines.size(), result.getTotalCount());
        }
    }

    @Test
    void writesUtf8WithoutLegacyColorCodes() throws Exception {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Angus");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        history.logGemPlace(player, "权力", "world 1 2 3");
        var lines = history.getRecentHistory(10);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("权力"));
        assertFalse(lines.get(0).contains("§"));
    }
}
