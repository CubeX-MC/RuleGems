package org.cubexmc.utils;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColorUtils {

    // Regex pattern for hex codes: &#RRGGBB or &#rrggbb
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    /**
     * Translates standard color codes and hex codes format (&#RRGGBB) into colored string.
     *
     * @param message The original message String
     * @return The translated colored String
     */
    public static String translateColorCodes(String message) {
        if (message == null) return null;

        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            try {
                // net.md_5.bungee.api.ChatColor is bundled with Spigot 1.16+
                matcher.appendReplacement(buffer, net.md_5.bungee.api.ChatColor.of("#" + matcher.group(1)).toString());
            } catch (NoSuchMethodError | NoClassDefFoundError e) {
                // Fallback if the server doesn't support bungeecord ChatColor.of (should not happen on 1.16+)
                matcher.appendReplacement(buffer, "");
            }
        }
        matcher.appendTail(buffer);

        // Translate the normal legacy color codes
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }
}
