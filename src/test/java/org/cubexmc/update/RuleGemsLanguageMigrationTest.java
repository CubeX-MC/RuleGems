package org.cubexmc.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.RuleGems;
import org.cubexmc.config.LegacyTextToMiniMessageStep;
import org.cubexmc.config.MigrationPlan;
import org.cubexmc.config.MigrationReport;
import org.cubexmc.config.MigrationRunner;
import org.cubexmc.utils.ColorUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleGemsLanguageMigrationTest {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    @TempDir
    Path dataDir;

    @Test
    void legacyLanguageMigratesToMiniMessageWithBackupAndIdempotency() throws Exception {
        Path langFile = dataDir.resolve("lang/zh_CN.yml");
        Files.createDirectories(langFile.getParent());
        Files.writeString(langFile, """
                prefix: "&7[&cRuleGems&7]&r"
                messages:
                  command:
                    usage: "&e%prefix% Usage: /rg <gemId> &#12AB34%player%"
                title:
                  gems_scattered:
                    - "&cScattered %count%"
                """, StandardCharsets.UTF_8);

        RuleGems plugin = mock(RuleGems.class);
        when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RuleGemsMigrationTest"));

        MigrationRunner runner = new MigrationRunner(plugin);
        MigrationPlan plan = MigrationPlan.yaml("RuleGems lang zh_CN", "lang/zh_CN.yml")
                .versionKey("lang-version")
                .targetVersion(2)
                .addStep(new LegacyTextToMiniMessageStep(1, 2));

        MigrationReport first = runner.run(plan);
        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(langFile.toFile());
        long backupCount = countBackupFiles();

        assertTrue(first.migrated());
        assertNotNull(first.backupFile());
        assertTrue(first.backupFile().exists());
        assertEquals(2, migrated.getInt("lang-version"));
        assertEquals("<yellow><prefix> Usage: /rg \\<gemId> <#12AB34><player>",
                migrated.getString("messages.command.usage"));
        assertEquals("<red>Scattered <count>", migrated.getStringList("title.gems_scattered").get(0));

        MigrationReport second = runner.run(plan);

        assertTrue(second.skipped());
        assertEquals(backupCount, countBackupFiles());
    }

    @Test
    void convertedRuleGemsTextIsVisuallyEquivalentToLegacyText() {
        LegacyTextToMiniMessageStep step = new LegacyTextToMiniMessageStep(1, 2);
        String legacyPrefix = "&7[&cRuleGems&7]&r";
        String legacy = "&e%prefix% Usage: /rg <gemId> &#12AB34%player%";
        String migrated = step.convert(legacy);
        String migratedPrefix = step.convert(legacyPrefix);

        String miniRendered = LEGACY_SERIALIZER.serialize(MiniMessage.miniMessage().deserialize(
                migrated.replace("<prefix>", migratedPrefix),
                Placeholder.unparsed("player", "Alex")));

        assertEquals(ColorUtils.translateColorCodes("&7[&cRuleGems&7]&r Usage: /rg <gemId> &#12ab34Alex"),
                miniRendered);
        assertEquals("<yellow><prefix> Usage: /rg \\<gemId> <#12AB34><player>", migrated);
    }

    private long countBackupFiles() throws Exception {
        Path backups = dataDir.resolve("backups/migrations");
        if (!Files.exists(backups)) {
            return 0L;
        }
        try (var walk = Files.walk(backups)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }
}
