package org.cubexmc.update

import org.bukkit.configuration.file.FileConfiguration

object RuleGemsLinks {
    const val DOCUMENTATION = "https://github.com/CubeX-MC/RuleGems"
    const val LEGACY_DOCUMENTATION = "https://github.com/angushushu/RuleGems"
    const val DISCORD = "https://discord.com/invite/7tJeSZPZgv"
    const val QQ = "https://pd.qq.com/s/1n3hpe4e7?b=9"

    fun placeholders(config: FileConfiguration): Map<String, String> = mapOf(
        "docs" to configured(config, "links.documentation", DOCUMENTATION),
        "discord" to configured(config, "links.discord", DISCORD),
        "qq" to configured(config, "links.qq", QQ),
    )

    private fun configured(config: FileConfiguration, path: String, fallback: String): String =
        config.getString(path, fallback) ?: fallback
}
