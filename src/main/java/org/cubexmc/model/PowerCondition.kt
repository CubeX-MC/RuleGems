package org.cubexmc.model

import org.bukkit.entity.Player
import org.cubexmc.manager.LanguageManager

/**
 * 权力条件配置。
 */
class PowerCondition {
    var isTimeEnabled: Boolean = false
    var timeType: TimeType = TimeType.ALWAYS
        set(value) {
            field = value
        }
    var timeFrom: Long = 0
    var timeTo: Long = 24000
    var isWorldEnabled: Boolean = false
    var worldMode: WorldMode = WorldMode.WHITELIST
        set(value) {
            field = value
        }
    var worldList: List<String> = ArrayList()
        set(value) {
            field = value ?: ArrayList()
        }

    enum class TimeType {
        ALWAYS,
        DAY,
        NIGHT,
        CUSTOM,
    }

    enum class WorldMode {
        WHITELIST,
        BLACKLIST,
    }

    fun checkConditions(player: Player?): Boolean {
        if (player == null || !player.isOnline) return false
        if (!checkTimeCondition(player)) return false
        if (!checkWorldCondition(player)) return false
        return true
    }

    fun checkTimeCondition(player: Player): Boolean {
        if (!isTimeEnabled) return true
        val time = player.world.time
        return when (timeType) {
            TimeType.ALWAYS -> true
            TimeType.DAY -> time in 0 until 12000
            TimeType.NIGHT -> time in 12000 until 24000
            TimeType.CUSTOM -> if (timeFrom <= timeTo) {
                time >= timeFrom && time < timeTo
            } else {
                time >= timeFrom || time < timeTo
            }
        }
    }

    fun checkWorldCondition(player: Player): Boolean {
        if (!isWorldEnabled || worldList.isEmpty()) return true
        val worldName = player.world.name
        val inList = worldList.contains(worldName)
        return when (worldMode) {
            WorldMode.WHITELIST -> inList
            WorldMode.BLACKLIST -> !inList
        }
    }

    @JvmOverloads
    fun getConditionDescription(lang: LanguageManager? = null): String {
        val builder = StringBuilder()
        if (isTimeEnabled && timeType != TimeType.ALWAYS) {
            when (timeType) {
                TimeType.DAY -> builder.append(msg(lang, "condition.time_day", "Day only"))
                TimeType.NIGHT -> builder.append(msg(lang, "condition.time_night", "Night only"))
                TimeType.CUSTOM -> builder.append(msg(lang, "condition.time_custom", "Time")).append(" ").append(timeFrom).append("-").append(timeTo)
                TimeType.ALWAYS -> {}
            }
        }

        if (isWorldEnabled && worldList.isNotEmpty()) {
            if (builder.isNotEmpty()) builder.append(", ")
            builder.append(
                if (worldMode == WorldMode.WHITELIST) {
                    msg(lang, "condition.world_whitelist", "Only in: ")
                } else {
                    msg(lang, "condition.world_blacklist", "Except: ")
                },
            )
            builder.append(worldList.joinToString(", "))
        }

        return if (builder.isNotEmpty()) builder.toString() else msg(lang, "condition.no_limit", "No restrictions")
    }

    fun hasAnyCondition(): Boolean = (isTimeEnabled && timeType != TimeType.ALWAYS) || (isWorldEnabled && worldList.isNotEmpty())

    fun copy(): PowerCondition {
        val copy = PowerCondition()
        copy.isTimeEnabled = isTimeEnabled
        copy.timeType = timeType
        copy.timeFrom = timeFrom
        copy.timeTo = timeTo
        copy.isWorldEnabled = isWorldEnabled
        copy.worldMode = worldMode
        copy.worldList = ArrayList(worldList)
        return copy
    }

    companion object {
        private fun msg(lang: LanguageManager?, key: String, fallback: String): String {
            if (lang == null) return fallback
            val value = lang.getMessage(key)
            return if (value != null && !value.startsWith("Missing message")) value else fallback
        }
    }
}
