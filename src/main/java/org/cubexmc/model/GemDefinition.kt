package org.cubexmc.model

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import kotlin.math.max

/**
 * GemDefinition 定义单颗宝石的展示与效果参数。
 * 使用 PowerStructure 统一管理权限结构。
 * 支持从全局默认值回退：若某字段未配置，则由调用者在构造时传入默认值。
 */
class GemDefinition private constructor(builder: Builder) {
    val gemKey: String = builder.gemKey
    val material: Material = builder.material
    val displayName: String? = builder.displayName
    val particle: Particle = builder.particle
    val sound: Sound = builder.sound
    val onPickup: ExecuteConfig? = builder.onPickup
    val onScatter: ExecuteConfig? = builder.onScatter
    val onRedeem: ExecuteConfig? = builder.onRedeem
    val lore: List<String>? = builder.lore
    val redeemTitle: List<String>? = builder.redeemTitle
    val isEnchanted: Boolean = builder.enchanted
    val mutualExclusive: List<String> = builder.mutualExclusive ?: emptyList()
    val count: Int = max(1, builder.count)
    val randomPlaceCorner1: Location? = builder.randomPlaceCorner1
    val randomPlaceCorner2: Location? = builder.randomPlaceCorner2
    var altarLocation: Location? = builder.altarLocation
    val redeemRequirements: RedeemRequirements = builder.redeemRequirements ?: RedeemRequirements.NONE

    // 使用 PowerStructure 统一管理权限结构
    val powerStructure: PowerStructure = builder.powerStructure ?: PowerStructure()

    // ==================== PowerStructure 委托方法 ====================

    /**
     * 获取权限列表
     */
    val permissions: List<String>
        get() = powerStructure.permissions

    /**
     * 获取 Vault 组（兼容旧 API，返回第一个组）
     */
    val vaultGroup: String?
        get() = powerStructure.vaultGroup

    /**
     * 获取 Vault 组列表（新 API）
     */
    val vaultGroups: List<String>
        get() = powerStructure.vaultGroups

    /**
     * 获取限次命令列表
     */
    val allowedCommands: List<AllowedCommand>
        get() = powerStructure.allowedCommands

    /**
     * 获取条件配置
     */
    var condition: PowerCondition
        get() = powerStructure.condition
        set(value) {
            powerStructure.setCondition(value)
        }

    /**
     * 获取药水效果列表
     */
    var effects: MutableList<EffectConfig>
        get() = powerStructure.effects
        set(value) {
            powerStructure.setEffects(value)
        }

    /**
     * Fluent builder for [GemDefinition].
     * Only `gemKey` is required; all other fields have sensible defaults.
     */
    class Builder(val gemKey: String) {
        internal var material: Material = Material.RED_STAINED_GLASS
        internal var displayName: String? = null
        internal var particle: Particle = Particle.FLAME
        internal var sound: Sound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP
        internal var onPickup: ExecuteConfig? = null
        internal var onScatter: ExecuteConfig? = null
        internal var onRedeem: ExecuteConfig? = null
        internal var powerStructure: PowerStructure? = null
        internal var lore: List<String>? = null
        internal var redeemTitle: List<String>? = null
        internal var enchanted = false
        internal var mutualExclusive: List<String>? = null
        internal var count = 1
        internal var randomPlaceCorner1: Location? = null
        internal var randomPlaceCorner2: Location? = null
        internal var altarLocation: Location? = null
        internal var redeemRequirements: RedeemRequirements? = RedeemRequirements.NONE

        fun material(value: Material): Builder {
            material = value
            return this
        }

        fun displayName(value: String?): Builder {
            displayName = value
            return this
        }

        fun particle(value: Particle): Builder {
            particle = value
            return this
        }

        fun sound(value: Sound): Builder {
            sound = value
            return this
        }

        fun onPickup(value: ExecuteConfig?): Builder {
            onPickup = value
            return this
        }

        fun onScatter(value: ExecuteConfig?): Builder {
            onScatter = value
            return this
        }

        fun onRedeem(value: ExecuteConfig?): Builder {
            onRedeem = value
            return this
        }

        fun powerStructure(value: PowerStructure?): Builder {
            powerStructure = value
            return this
        }

        fun lore(value: List<String>?): Builder {
            lore = value
            return this
        }

        fun redeemTitle(value: List<String>?): Builder {
            redeemTitle = value
            return this
        }

        fun enchanted(value: Boolean): Builder {
            enchanted = value
            return this
        }

        fun mutualExclusive(value: List<String>?): Builder {
            mutualExclusive = value
            return this
        }

        fun count(value: Int): Builder {
            count = value
            return this
        }

        fun randomPlaceCorner1(value: Location?): Builder {
            randomPlaceCorner1 = value
            return this
        }

        fun randomPlaceCorner2(value: Location?): Builder {
            randomPlaceCorner2 = value
            return this
        }

        fun altarLocation(value: Location?): Builder {
            altarLocation = value
            return this
        }

        fun redeemRequirements(value: RedeemRequirements?): Builder {
            redeemRequirements = value
            return this
        }

        fun build(): GemDefinition = GemDefinition(this)
    }
}
