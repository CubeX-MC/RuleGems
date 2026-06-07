package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.cubexmc.model.ExecuteConfig
import org.cubexmc.model.PowerStructure
import org.cubexmc.utils.ConfigParseUtils.parseTimeToTicks
import org.cubexmc.utils.ConfigParseUtils.stringOf
import org.cubexmc.utils.ConfigParseUtils.toStringList
import java.util.Collections
import java.util.TreeMap
import java.util.logging.Logger

/**
 * 游戏玩法配置 — 存储从 config.yml 读取的所有运行时游戏设置。
 *
 * 长期存活对象；[loadFrom] 会原地刷新所有字段，
 * 因此 reload 后持有引用的消费者自动看到新值。
 */
class GameplayConfig {
    // ==================== 授权策略 ====================
    var isInventoryGrantsEnabled = false
        private set
    var isRedeemEnabled = false
        private set
    var isFullSetGrantsAllEnabled = false
        private set

    // ==================== Redeem 广播 ====================
    var isBroadcastRedeemTitle = true
        private set

    // ==================== Redeem-All ====================
    var redeemAllTitle: List<String> = Collections.emptyList()
        private set
    var redeemAllBroadcastOverride: Boolean? = null
        private set
    var redeemAllSound: String = "ENTITY_ENDER_DRAGON_GROWL"
        private set
    var redeemAllPowerStructure: PowerStructure = PowerStructure()
        private set
    var gemCollectThresholdGroups: Map<Int, String> = Collections.emptyMap()
        private set

    // ==================== 放置 / 散落 ====================
    var gemScatterExecute: ExecuteConfig? = null
        private set
    var randomPlaceCorner1: Location? = null
        private set
    var randomPlaceCorner2: Location? = null
        private set

    // ==================== 宝石逃逸 ====================
    var isGemEscapeEnabled = false
        private set
    var gemEscapeMinIntervalTicks = 0L
        private set
    var gemEscapeMaxIntervalTicks = 0L
        private set
    var isGemEscapeBroadcast = false
        private set
    var gemEscapeSound: String? = null
        private set
    var gemEscapeParticle: String? = null
        private set

    // ==================== 放置兑换（祭坛模式） ====================
    var isPlaceRedeemEnabled = false
        private set
    var placeRedeemRadius = 0
        private set
    var placeRedeemSound: String? = null
        private set
    var placeRedeemParticle: String? = null
        private set
    var isPlaceRedeemBeaconBeam = false
        private set
    var placeRedeemBeaconDuration = 0
        private set

    // ==================== 长按右键兑换 ====================
    var isHoldToRedeemEnabled = false
        private set
    var isSneakToRedeem = false
        private set
    var holdToRedeemDurationTicks = 0
        private set

    // ==================== 安全 ====================
    var isOpEscalationAllowed = false
        private set

    /**
     * 从 config.yml 读取并刷新所有 gameplay 字段。
     *
     * @param config     主配置文件
     * @param parser     GemDefinitionParser（用于 parsePowerStructure）
     * @param lang       LanguageManager（目前未直接使用，保留扩展性）
     * @param logger     日志记录器
     * @param cornerLoader 辅助函数：(ConfigurationSection, String, World) → Location
     */
    fun loadFrom(
        config: FileConfiguration,
        parser: GemDefinitionParser,
        @Suppress("UNUSED_PARAMETER") lang: LanguageManager,
        logger: Logger?,
        cornerLoader: LocationLoader?,
    ) {
        // 授权策略
        val gp = config.getConfigurationSection("grant_policy")
        isInventoryGrantsEnabled = gp != null && gp.getBoolean("inventory_grants", false)
        isRedeemEnabled = gp == null || gp.getBoolean("redeem_enabled", true)
        isFullSetGrantsAllEnabled = gp == null || gp.getBoolean("full_set_grants_all", true)
        isPlaceRedeemEnabled = gp != null && gp.getBoolean("place_redeem_enabled", false)
        isHoldToRedeemEnabled = gp != null && gp.getBoolean("hold_to_redeem_enabled", gp.getBoolean("hold_to_redeem", true))

        // hold_to_redeem 配置块
        val htr = config.getConfigurationSection("hold_to_redeem")
        if (htr != null) {
            isSneakToRedeem = htr.getBoolean("sneak_to_redeem", true)
            val durationSeconds = htr.getDouble("duration", 3.0)
            holdToRedeemDurationTicks = (durationSeconds * 20).toInt()
        } else {
            isSneakToRedeem = gp == null || gp.getBoolean("sneak_to_redeem", true)
            holdToRedeemDurationTicks = 60 // 默认3秒
        }

        // 全局开关
        val toggles = config.getConfigurationSection("toggles")
        if (toggles != null) {
            isBroadcastRedeemTitle = toggles.getBoolean("broadcast_redeem_title", true)
        }

        // redeem_all
        val ra = config.getConfigurationSection("redeem_all")
        if (ra != null) {
            val titlesObj = ra.get("titles")
            redeemAllTitle = toStringList(titlesObj)
            redeemAllBroadcastOverride = if (ra.isSet("broadcast")) ra.getBoolean("broadcast") else null
            val sound = stringOf(ra.get("sound"))
            if (!sound.isNullOrEmpty()) {
                redeemAllSound = sound
            }

            redeemAllPowerStructure = parser.parsePowerStructure(ra)
        } else {
            redeemAllTitle = Collections.emptyList()
            redeemAllBroadcastOverride = null
            redeemAllPowerStructure = PowerStructure()
        }

        gemCollectThresholdGroups = parseThresholdGroups(config.getConfigurationSection("gem_collect_thresholds"), logger)

        // 散落效果
        gemScatterExecute = ExecuteConfig(
            config.getStringList("gem_scatter_execute.commands"),
            config.getString("gem_scatter_execute.sound"),
            config.getString("gem_scatter_execute.particle"),
        )

        // 随机放置范围
        val randomPlaceRange = config.getConfigurationSection("random_place_range")
        if (randomPlaceRange != null && cornerLoader != null) {
            val worldName = randomPlaceRange.getString("world")
            if (worldName != null) {
                val world = Bukkit.getWorld(worldName)
                if (world != null) {
                    randomPlaceCorner1 = cornerLoader.load(randomPlaceRange, "corner1", world)
                    randomPlaceCorner2 = cornerLoader.load(randomPlaceRange, "corner2", world)
                }
            }
        }

        // 宝石逃逸配置
        val escapeSection = config.getConfigurationSection("gem_escape")
        if (escapeSection != null) {
            isGemEscapeEnabled = escapeSection.getBoolean("enabled", false)
            gemEscapeMinIntervalTicks = parseTimeToTicks(escapeSection.getString("min_interval", "30m"), logger)
            gemEscapeMaxIntervalTicks = parseTimeToTicks(escapeSection.getString("max_interval", "2h"), logger)
            isGemEscapeBroadcast = escapeSection.getBoolean("broadcast", true)
            gemEscapeSound = escapeSection.getString("sound", "ENTITY_ENDERMAN_TELEPORT")
            gemEscapeParticle = escapeSection.getString("particle", "PORTAL")
        } else {
            isGemEscapeEnabled = false
            gemEscapeMinIntervalTicks = 30 * 60 * 20L
            gemEscapeMaxIntervalTicks = 2 * 60 * 60 * 20L
            isGemEscapeBroadcast = true
            gemEscapeSound = "ENTITY_ENDERMAN_TELEPORT"
            gemEscapeParticle = "PORTAL"
        }
        // 确保 min <= max
        if (gemEscapeMinIntervalTicks > gemEscapeMaxIntervalTicks) {
            val tmp = gemEscapeMinIntervalTicks
            gemEscapeMinIntervalTicks = gemEscapeMaxIntervalTicks
            gemEscapeMaxIntervalTicks = tmp
        }
        // 确保最小间隔至少 1 秒
        if (gemEscapeMinIntervalTicks < 20L) {
            gemEscapeMinIntervalTicks = 20L
        }

        // 放置兑换（祭坛模式）全局设置
        val prSection = config.getConfigurationSection("place_redeem")
        if (prSection != null) {
            placeRedeemRadius = prSection.getInt("radius", 1)
            placeRedeemSound = prSection.getString("sound", "BLOCK_BEACON_ACTIVATE")
            placeRedeemParticle = prSection.getString("particle", "TOTEM")
            isPlaceRedeemBeaconBeam = prSection.getBoolean("beacon_beam", true)
            placeRedeemBeaconDuration = prSection.getInt("beacon_beam_duration", 5)
        } else {
            placeRedeemRadius = 1
            placeRedeemSound = "BLOCK_BEACON_ACTIVATE"
            placeRedeemParticle = "TOTEM"
            isPlaceRedeemBeaconBeam = true
            placeRedeemBeaconDuration = 5
        }

        // 安全配置
        isOpEscalationAllowed = config.getBoolean("allow_op_escalation", false)
    }

    private fun parseThresholdGroups(section: ConfigurationSection?, logger: Logger?): Map<Int, String> {
        if (section == null) {
            return Collections.emptyMap()
        }
        val result = TreeMap<Int, String>()
        for (rawKey in section.getKeys(false)) {
            try {
                val threshold = rawKey.toInt()
                val group = section.getString(rawKey)
                if (threshold > 0 && group != null && group.trim().isNotEmpty()) {
                    result[threshold] = group.trim()
                }
            } catch (_: NumberFormatException) {
                logger?.warning("Ignoring invalid gem_collect_thresholds key '$rawKey': expected number")
            }
        }
        return if (result.isEmpty()) Collections.emptyMap() else Collections.unmodifiableMap(result)
    }

    /**
     * 函数式接口，用于从 ConfigurationSection 加载 Location。
     * 由 ConfigManager 提供实现，避免 GameplayConfig 依赖 RuleGems。
     */
    fun interface LocationLoader {
        fun load(section: ConfigurationSection, path: String, world: World): Location?
    }
}
