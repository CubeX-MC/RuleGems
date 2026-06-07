package org.cubexmc.utils

import org.bukkit.ChatColor
import java.util.regex.Pattern

object ColorUtils {
    private val HEX_PATTERN: Pattern = Pattern.compile("&#([A-Fa-f0-9]{6})")

    @JvmStatic
    fun translateColorCodes(message: String?): String? {
        if (message == null) return null

        val matcher = HEX_PATTERN.matcher(message)
        val buffer = StringBuffer()
        while (matcher.find()) {
            try {
                matcher.appendReplacement(buffer, net.md_5.bungee.api.ChatColor.of("#" + matcher.group(1)).toString())
            } catch (_: NoSuchMethodError) {
                matcher.appendReplacement(buffer, "")
            } catch (_: NoClassDefFoundError) {
                matcher.appendReplacement(buffer, "")
            }
        }
        matcher.appendTail(buffer)
        return ChatColor.translateAlternateColorCodes('&', buffer.toString())
    }
}
