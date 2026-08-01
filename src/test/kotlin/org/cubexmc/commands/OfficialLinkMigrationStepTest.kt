package org.cubexmc.commands

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.RuleGems
import org.cubexmc.config.MigrationPlan
import org.cubexmc.config.MigrationRunner
import org.cubexmc.update.OfficialLinkMigrationStep
import org.cubexmc.update.RuleGemsLinks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

class OfficialLinkMigrationStepTest {
    @TempDir
    lateinit var dataDir: Path

    @Test
    fun `legacy official documentation link moves to CubeX-MC`() {
        val configFile = writeConfig(RuleGemsLinks.LEGACY_DOCUMENTATION)
        val report = MigrationRunner(plugin()).run(plan())
        val migrated = YamlConfiguration.loadConfiguration(configFile.toFile())

        assertEquals(3, migrated.getInt("config-version"))
        assertEquals(RuleGemsLinks.DOCUMENTATION, migrated.getString("links.documentation"))
        assertNotNull(report.backupFile())
    }

    @Test
    fun `custom documentation link is preserved`() {
        val customLink = "https://docs.example.test/rulegems"
        val configFile = writeConfig(customLink)

        MigrationRunner(plugin()).run(plan())

        val migrated = YamlConfiguration.loadConfiguration(configFile.toFile())
        assertEquals(3, migrated.getInt("config-version"))
        assertEquals(customLink, migrated.getString("links.documentation"))
    }

    private fun plugin(): RuleGems {
        val plugin = mock(RuleGems::class.java)
        `when`(plugin.dataFolder).thenReturn(dataDir.toFile())
        `when`(plugin.logger).thenReturn(Logger.getLogger("OfficialLinkMigrationStepTest"))
        return plugin
    }

    private fun plan(): MigrationPlan =
        MigrationPlan.yaml("RuleGems config", "config.yml")
            .versionKey("config-version")
            .targetVersion(3)
            .addStep(OfficialLinkMigrationStep(2, 3))

    private fun writeConfig(documentation: String): Path {
        val configFile = dataDir.resolve("config.yml")
        Files.writeString(
            configFile,
            """
            config-version: 2
            links:
              documentation: "$documentation"
              discord: "https://discord.example.test"
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )
        return configFile
    }
}
