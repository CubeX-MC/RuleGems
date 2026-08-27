package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.RuleGems;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageManagerModernizationTest {

    private static final String LANG = """
            lang-version: 2
            prefix: "<gray>[<red>RuleGems<gray>]<reset>"
            messages:
              command:
                reload_success: "<green><prefix> Reloaded <name>"
              hold_redeem:
                progress_bar: "<gold>* <bar> <yellow><percent>%"
              allowance:
                args_required: "<red>Missing <argument>. Usage: <usage>"
            title:
              gems_scattered:
                - "<red>Scattered <count>"
                - "<gray>Done \\\\<id>"
            """;

    @TempDir
    Path dataDir;

    private RuleGems plugin;
    private LanguageManager languageManager;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(dataDir.resolve("lang"));
        Files.writeString(dataDir.resolve("lang/zh_CN.yml"), LANG, StandardCharsets.UTF_8);
        Files.writeString(dataDir.resolve("lang/en_US.yml"), LANG, StandardCharsets.UTF_8);

        YamlConfiguration config = new YamlConfiguration();
        config.set("language", "zh_CN");

        plugin = mock(RuleGems.class);
        when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RuleGemsLanguageTest"));
        when(plugin.getResource("lang/zh_CN.yml")).thenAnswer(ignored -> resource(LANG));
        when(plugin.getResource("lang/en_US.yml")).thenAnswer(ignored -> resource(LANG));

        languageManager = new LanguageManager(plugin);
        languageManager.loadLanguage();
    }

    @Test
    void formatMessageRendersMiniMessageWithCanonicalPlaceholders() {
        String rendered = languageManager.formatMessage("messages.command.reload_success", Map.of("name", "Steve"));

        assertEquals("§7[§cRuleGems§7]§r Reloaded Steve", rendered);
    }

    @Test
    void rawCompatibilityKeepsLegacyPlaceholdersForActionBarCallers() {
        String rendered = languageManager.getMessage("messages.hold_redeem.progress_bar");

        assertTrue(rendered.contains("§6* %bar%"));
        assertTrue(rendered.contains("§e%percent%%"));
    }

    @Test
    void titleAdapterRendersMiniMessageAndLiteralUsageText() {
        assertEquals("§cScattered 3",
                languageManager.renderTitleLine("<red>Scattered <count>", Map.of("count", "3")));
        assertEquals("§7Done <id>",
                languageManager.renderTitleLine("<gray>Done \\<id>", Map.of()));
    }

    @Test
    void argumentUsageIsRenderedAsLiteralTextIncludingAngleBrackets() {
        assertEquals("§cMissing arg2. Usage: /cxfine <player> <amount>", languageManager.formatMessage(
                "messages.allowance.args_required", Map.of("argument", "arg2", "usage", "/cxfine <player> <amount>")));
    }

    @Test
    void customLanguageFallsBackForTitlesAndLists() throws Exception {
        Files.writeString(dataDir.resolve("lang/fr_FR.yml"), "prefix: '[FR]'\n", StandardCharsets.UTF_8);
        plugin.getConfig().set("language", "fr_FR");
        languageManager.reload();
        assertEquals(java.util.List.of("§cScattered %count%", "§7Done <id>"),
                languageManager.getMessageList("title.gems_scattered"));

        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        try (org.mockito.MockedStatic<org.cubexmc.utils.SchedulerUtil> scheduler =
                     org.mockito.Mockito.mockStatic(org.cubexmc.utils.SchedulerUtil.class)) {
            scheduler.when(() -> org.cubexmc.utils.SchedulerUtil.entityRun(
                    org.mockito.ArgumentMatchers.eq(plugin), org.mockito.ArgumentMatchers.eq(player),
                    org.mockito.ArgumentMatchers.any(Runnable.class),
                    org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.eq(-1L)))
                    .thenAnswer(invocation -> {
                        ((Runnable) invocation.getArgument(2)).run();
                        return null;
                    });
            languageManager.showTitle(player, "gems_scattered", Map.of("count", "3"));
        }
        org.mockito.Mockito.verify(player).sendTitle("§cScattered 3", "§7Done <id>", 10, 70, 20);
    }

    @Test
    void aCustomLocaleWithoutAPrefixUsesTheSameFallbackChain() throws Exception {
        Files.writeString(dataDir.resolve("lang/fr_FR.yml"), "messages: {}\n", StandardCharsets.UTF_8);
        plugin.getConfig().set("language", "fr_FR");
        languageManager.reload();

        assertEquals("§7[§cRuleGems§7]§r Reloaded Steve",
                languageManager.formatMessage("messages.command.reload_success", Map.of("name", "Steve")));
    }

    @Test
    void legacyRawAdapterResolvesPrefixAndPreservesCallerPlaceholders() {
        assertEquals("§7[§cRuleGems§7]§r Reloaded %name%",
                languageManager.getMessage("messages.command.reload_success"));
    }

    private static ByteArrayInputStream resource(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
