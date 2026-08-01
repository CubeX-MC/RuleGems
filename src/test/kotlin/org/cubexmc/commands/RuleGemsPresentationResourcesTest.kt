package org.cubexmc.commands

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.update.RuleGemsLinks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class RuleGemsPresentationResourcesTest {
    @Test
    fun `bundled configuration uses current official repository`() {
        val config = loadYaml("config.yml")

        assertEquals(3, config.getInt("config-version"))
        assertEquals(RuleGemsLinks.DOCUMENTATION, config.getString("links.documentation"))
    }

    @Test
    fun `bundled languages provide complete help and history presentation`() {
        for (locale in listOf("zh_CN", "en_US")) {
            val language = loadYaml("lang/$locale.yml")
            for (path in REQUIRED_PRESENTATION_KEYS) {
                assertNotNull(language.getString(path), "$locale is missing $path")
            }
        }
    }

    private fun loadYaml(path: String): YamlConfiguration {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "Missing test resource $path"
        }
        return InputStreamReader(stream, StandardCharsets.UTF_8).use(YamlConfiguration::loadConfiguration)
    }

    private companion object {
        val REQUIRED_PRESENTATION_KEYS = listOf(
            "messages.command.help.title",
            "messages.command.help.section_player",
            "messages.command.help.section_admin",
            "messages.command.help.section_more",
            "messages.command.help.item_marker",
            "messages.command.help.link_documentation",
            "messages.command.help.link_discord",
            "messages.command.help.link_qq",
            "messages.command.history.title_player",
            "messages.command.history.title_recent",
            "messages.command.history.summary",
            "messages.command.history.entry",
            "messages.command.history.navigation_previous",
            "messages.command.history.navigation_page",
            "messages.command.history.navigation_next",
            "messages.command.history.console_navigation",
        )
    }
}
