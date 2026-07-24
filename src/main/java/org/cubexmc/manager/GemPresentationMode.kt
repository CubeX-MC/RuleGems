package org.cubexmc.manager

import java.util.Locale

enum class GemPresentationMode {
    BLOCK,
    PROXIMITY_DISPLAY,
    ;

    companion object {
        @JvmStatic
        fun parse(value: String?): GemPresentationMode {
            return when (value?.trim()?.lowercase(Locale.ROOT)) {
                "proximity_display", "display" -> PROXIMITY_DISPLAY
                else -> BLOCK
            }
        }
    }
}
