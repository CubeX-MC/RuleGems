package org.cubexmc.utils

import java.util.Collections
import java.util.Locale
import java.util.logging.Logger

object ConfigParseUtils {
    @JvmStatic
    fun stringOf(value: Any?): String? = value?.toString()

    @JvmStatic
    fun toStringList(value: Any?): List<String> {
        if (value == null) return emptyList()
        if (value is List<*>) {
            val out = ArrayList<String>()
            for (entry in value) {
                if (entry != null) out.add(entry.toString())
            }
            return out
        }
        return Collections.singletonList(value.toString())
    }

    @JvmStatic
    fun parseTimeToTicks(timeValue: String?, logger: Logger?): Long {
        if (timeValue.isNullOrEmpty()) {
            return 30 * 60 * 20L
        }

        val timeStr = timeValue.trim().lowercase(Locale.getDefault())
        var multiplier = 60 * 20L
        var numPart = timeStr
        when {
            timeStr.endsWith("s") -> {
                multiplier = 20L
                numPart = timeStr.substring(0, timeStr.length - 1)
            }
            timeStr.endsWith("m") -> {
                multiplier = 60 * 20L
                numPart = timeStr.substring(0, timeStr.length - 1)
            }
            timeStr.endsWith("h") -> {
                multiplier = 60 * 60 * 20L
                numPart = timeStr.substring(0, timeStr.length - 1)
            }
            timeStr.endsWith("d") -> {
                multiplier = 24 * 60 * 60 * 20L
                numPart = timeStr.substring(0, timeStr.length - 1)
            }
        }

        return try {
            (numPart.trim().toDouble() * multiplier).toLong()
        } catch (_: NumberFormatException) {
            logger?.warning("Failed to parse time format: $timeStr, using default 30m")
            30 * 60 * 20L
        }
    }

    @JvmStatic
    fun isTrue(value: Any?): Boolean {
        if (value is Boolean) return value
        return value != null && "true".equals(value.toString(), ignoreCase = true)
    }
}
