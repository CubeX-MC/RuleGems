package org.cubexmc.model

import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.Locale
import java.util.Objects

/**
 * 药水效果配置。
 */
class EffectConfig(
    val effectType: PotionEffectType?,
    val amplifier: Int,
    val isAmbient: Boolean,
    private val particles: Boolean,
    private val icon: Boolean,
) {
    constructor(effectType: PotionEffectType?, amplifier: Int) : this(effectType, amplifier, false, true, true)

    constructor(effectType: PotionEffectType?) : this(effectType, 0, false, true, true)

    fun hasParticles(): Boolean = particles

    fun hasIcon(): Boolean = icon

    fun apply(player: Player?) {
        if (player == null || !player.isOnline || effectType == null) return
        val effect = PotionEffect(effectType, durationTicks, amplifier, isAmbient, particles, icon)
        player.addPotionEffect(effect)
    }

    fun remove(player: Player?) {
        if (player == null || !player.isOnline || effectType == null) return
        player.removePotionEffect(effectType)
    }

    fun hasEffect(player: Player?): Boolean {
        if (player == null || effectType == null) return false
        return player.hasPotionEffect(effectType)
    }

    val description: String
        get() {
            if (effectType == null) return "Unknown"
            val words = effectType.name.replace("_", " ").split(" ")
            val name = words.joinToString(" ") { word ->
                if (word.isEmpty()) word else word.substring(0, 1).uppercase(Locale.getDefault()) + word.substring(1).lowercase(Locale.getDefault())
            }
            val level = toRomanNumeral(amplifier + 1)
            return "$name $level".trim()
        }

    private fun toRomanNumeral(number: Int): String {
        if (number <= 0) return number.toString()
        if (number > 10) return number.toString()
        val numerals = arrayOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
        return numerals[number - 1]
    }

    fun copy(): EffectConfig = EffectConfig(effectType, amplifier, isAmbient, particles, icon)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as EffectConfig
        return amplifier == other.amplifier &&
            isAmbient == other.isAmbient &&
            particles == other.particles &&
            icon == other.icon &&
            Objects.equals(effectType, other.effectType)
    }

    override fun hashCode(): Int = Objects.hash(effectType, amplifier, isAmbient, particles, icon)

    override fun toString(): String =
        "EffectConfig{" +
            "type=" + (effectType?.name ?: "null") +
            ", amplifier=" + amplifier +
            ", ambient=" + isAmbient +
            ", particles=" + particles +
            ", icon=" + icon +
            '}'

    companion object {
        // 客户端对夜视等效果写死的"临期闪烁"阈值：剩余 ≤ 200 tick(10s) 就会正弦淡入淡出
        // 提示快没了（vanilla getNightVisionScale），插件无法关闭。
        const val NIGHT_VISION_FLICKER_THRESHOLD_TICKS: Int = 200

        // 钳制时在阈值之上额外保留的安全余量，用于吸收调度抖动/卡服，避免刷新略微延迟就跌入闪烁区。
        private const val SAFE_MARGIN_TICKS: Int = 20

        // 默认值：单次 20s、每 3s 重施一次。采用"限时 + 周期重施"而非无限时长，是为了防止孤儿效果
        // （插件卸载/重载、玩家掉线重连未重新授权时，效果能自行过期清理）。
        const val DEFAULT_DURATION_TICKS: Int = 400
        const val DEFAULT_REFRESH_INTERVAL_TICKS: Int = 60

        // 单次施加的药水时长（tick）。运行时由 config 通过 [configure] 设置，已钳制为不会触发闪烁的安全值。
        @Volatile
        var durationTicks: Int = DEFAULT_DURATION_TICKS
            private set

        // 周期重施间隔（tick）。
        @Volatile
        var refreshIntervalTicks: Int = DEFAULT_REFRESH_INTERVAL_TICKS
            private set

        /**
         * 由配置加载时调用，设置并**钳制**单次时长与重施间隔。
         *
         * 不变性保证：`durationTicks - refreshIntervalTicks > 200`，即剩余时长在两次刷新之间
         * 永不跌入 200 tick 的夜视闪烁区；同时仍是"限时"以防孤儿效果。
         * 若入参不满足该不变性，会把单次时长上调到安全下限，并通过 logger 提示。
         */
        fun configure(duration: Int, refresh: Int, logger: java.util.logging.Logger? = null) {
            val safeRefresh = refresh.coerceAtLeast(1)
            val minDuration = NIGHT_VISION_FLICKER_THRESHOLD_TICKS + SAFE_MARGIN_TICKS + safeRefresh
            val safeDuration = duration.coerceAtLeast(minDuration)
            if (safeRefresh != refresh || safeDuration != duration) {
                logger?.warning(
                    "Effect timing adjusted to avoid night-vision flicker: " +
                        "duration ${duration}→$safeDuration tick, refresh ${refresh}→$safeRefresh tick. " +
                        "Single-apply duration must stay above ${NIGHT_VISION_FLICKER_THRESHOLD_TICKS} tick " +
                        "more than the refresh interval.",
                )
            }
            durationTicks = safeDuration
            refreshIntervalTicks = safeRefresh
        }
    }
}
