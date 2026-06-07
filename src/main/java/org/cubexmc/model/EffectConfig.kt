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
        val effect = PotionEffect(effectType, DURATION_TICKS, amplifier, isAmbient, particles, icon)
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
        const val DURATION_TICKS: Int = 100
        const val REFRESH_INTERVAL_TICKS: Int = 40
    }
}
