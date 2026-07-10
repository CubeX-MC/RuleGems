package org.cubexmc.features

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.manager.GemManager
import org.cubexmc.utils.ColorUtils
import org.cubexmc.utils.SchedulerUtil
import java.io.File
import java.util.Locale
import java.util.Random
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Periodically leaks soft, fragmentary information about placed gems to players outside power.
 */
class GemIntelBroadcaster(
    plugin: RuleGems,
    private val gemManager: GemManager,
) : Feature(plugin, PERMISSION) {
    private val random = Random()
    private val recipientCooldowns: MutableMap<UUID, Long> = HashMap()
    private var task: Any? = null

    private var intervalSeconds = 1800
    private var initialDelaySeconds = 300
    private var requireNonRuler = true
    private var maxRedeemedGems = 0
    private var perPlayerCooldownSeconds = 7200
    private var minDistanceFromRecipient = 0.0
    private var clusterBiasEnabled = true
    private var clusterRadius = 96.0
    private var clusterMultiplier = 2.0
    private var axisTypes = DEFAULT_AXIS_TYPES
    private var includeWorld = true
    private var labelEnabled = true
    private var labelFormat = "未知宝石{code}"
    private var labelAlphabet = DEFAULT_LABEL_ALPHABET
    private var labelMinLength = 1
    private var rangeDistribution = "lognormal"
    private var rangeMedian = 600.0
    private var rangeSigma = 0.45
    private var rangeStdDev = 250.0
    private var rangeMin = 200
    private var rangeMax = 1800
    private var rangeRoundTo = 50

    override fun initialize() {
        reload()
    }

    override fun shutdown() {
        stopTask()
        recipientCooldowns.clear()
    }

    override fun reload() {
        stopTask()

        val featuresFolder = File(plugin.dataFolder, "features")
        if (!featuresFolder.exists()) {
            featuresFolder.mkdirs()
        }

        val configFile = File(featuresFolder, "intel.yml")
        if (!configFile.exists()) {
            plugin.saveResource("features/intel.yml", false)
        }
        val loaded = YamlConfiguration.loadConfiguration(configFile)

        enabled = loaded.getBoolean("enabled", false)
        intervalSeconds = max(1, loaded.getInt("interval_seconds", 1800))
        initialDelaySeconds = max(0, loaded.getInt("initial_delay_seconds", 300))
        requireNonRuler = loaded.getBoolean("recipients.require_non_ruler", true)
        maxRedeemedGems = loaded.getInt("recipients.max_redeemed_gems", 0)
        perPlayerCooldownSeconds = max(0, loaded.getInt("recipients.per_player_cooldown_seconds", 7200))
        minDistanceFromRecipient = max(0.0, loaded.getDouble("targets.min_distance_from_recipient", 0.0))
        clusterBiasEnabled = loaded.getBoolean("targets.cluster_bias.enabled", true)
        clusterRadius = max(1.0, loaded.getDouble("targets.cluster_bias.radius", 96.0))
        clusterMultiplier = max(1.0, loaded.getDouble("targets.cluster_bias.multiplier", 2.0))
        axisTypes = sanitizeAxisTypes(loaded.getStringList("fragments.axes"))
        if (axisTypes.isEmpty()) {
            axisTypes = sanitizeLegacyFragmentTypes(loaded.getStringList("fragments.enabled_types"))
        }
        if (axisTypes.isEmpty()) {
            axisTypes = DEFAULT_AXIS_TYPES
        }
        includeWorld = loaded.getBoolean("fragments.include_world", true)
        labelEnabled = loaded.getBoolean("fragments.label.enabled", true)
        labelFormat = loaded.getString("fragments.label.format", "未知宝石{code}") ?: "未知宝石{code}"
        labelAlphabet = sanitizeAlphabet(
            loaded.getString("fragments.label.alphabet", DEFAULT_LABEL_ALPHABET) ?: DEFAULT_LABEL_ALPHABET,
        )
        labelMinLength = max(1, loaded.getInt("fragments.label.min_length", 1))
        rangeDistribution = loaded.getString("fragments.range.distribution", "lognormal")
            ?.lowercase(Locale.ROOT)
            ?: "lognormal"
        rangeMedian = max(1.0, loaded.getDouble("fragments.range.median", 600.0))
        rangeSigma = max(0.05, loaded.getDouble("fragments.range.sigma", 0.45))
        rangeStdDev = max(1.0, loaded.getDouble("fragments.range.std_dev", 250.0))
        rangeMin = max(1, loaded.getInt("fragments.range.min", 200))
        rangeMax = max(rangeMin, loaded.getInt("fragments.range.max", 1800))
        rangeRoundTo = max(1, loaded.getInt("fragments.range.round_to", 50))

        if (enabled) {
            startTask()
        }
    }

    private fun startTask() {
        task = SchedulerUtil.globalRun(
            plugin,
            { broadcastIntel() },
            initialDelaySeconds * 20L,
            intervalSeconds * 20L,
        )
    }

    private fun stopTask() {
        SchedulerUtil.cancelTask(task)
        task = null
    }

    private fun broadcastIntel() {
        if (!enabled) return

        val recipient = chooseRecipient() ?: return
        val target = chooseTargetGem(recipient) ?: return
        val message = buildMessage(target) ?: return

        recipientCooldowns[recipient.uniqueId] = System.currentTimeMillis()
        SchedulerUtil.entityRun(
            plugin,
            recipient,
            {
                if (recipient.isOnline) {
                    recipient.sendMessage(ColorUtils.translateColorCodes(message) ?: "")
                }
            },
            0L,
            -1L,
        )
    }

    private fun chooseRecipient(): Player? {
        val now = System.currentTimeMillis()
        val rulers = gemManager.currentRulers
        val eligible = Bukkit.getOnlinePlayers().filter { player ->
            if (!player.isOnline) {
                return@filter false
            }
            val redeemedCount = rulers[player.uniqueId]?.size ?: 0
            if (requireNonRuler && redeemedCount > 0) {
                return@filter false
            }
            if (maxRedeemedGems >= 0 && redeemedCount > maxRedeemedGems) {
                return@filter false
            }
            val last = recipientCooldowns[player.uniqueId]
            if (last != null && now - last < perPlayerCooldownSeconds * 1000L) {
                return@filter false
            }
            true
        }
        if (eligible.isEmpty()) {
            return null
        }
        return eligible[random.nextInt(eligible.size)]
    }

    private fun chooseTargetGem(recipient: Player): IntelTarget? {
        val targets = gemManager.getAllGemLocations()
            .mapNotNull { (gemId, location) ->
                if (location.world == null) {
                    null
                } else {
                    IntelTarget(gemId, location.clone())
                }
            }
            .filter { target ->
                if (minDistanceFromRecipient <= 0) {
                    return@filter true
                }
                if (recipient.location.world != target.location.world) {
                    return@filter true
                }
                recipient.location.distance(target.location) >= minDistanceFromRecipient
            }
        if (targets.isEmpty()) {
            return null
        }

        val weighted = targets.map { target -> target to weightFor(target, targets) }
        val totalWeight = weighted.sumOf { it.second }
        if (totalWeight <= 0.0) {
            return targets[random.nextInt(targets.size)]
        }
        var roll = random.nextDouble() * totalWeight
        for ((target, weight) in weighted) {
            roll -= weight
            if (roll <= 0.0) {
                return target
            }
        }
        return weighted.last().first
    }

    private fun weightFor(target: IntelTarget, allTargets: List<IntelTarget>): Double {
        if (!clusterBiasEnabled || clusterMultiplier <= 1.0) {
            return 1.0
        }
        val radiusSquared = clusterRadius * clusterRadius
        val nearbyCount = allTargets.count { other ->
            other.location.world == target.location.world &&
                other.location.distanceSquared(target.location) <= radiusSquared
        }
        return 1.0 + max(0, nearbyCount - 1) * (clusterMultiplier - 1.0)
    }

    private fun buildMessage(target: IntelTarget): String? {
        val color = colorForGem(target.gemId)
        val axis = axisTypes[random.nextInt(axisTypes.size)]
        val placeholders = HashMap<String, String>()
        placeholders["color"] = ""
        placeholders["label"] = labelForGem(target.gemId)
        placeholders["world"] = target.location.world?.name ?: "unknown"

        val body = when (axis) {
            "x" -> axisRangeMessage("X", target.location.blockX, placeholders)
            "y" -> axisRangeMessage("Y", target.location.blockY, placeholders)
            "z" -> axisRangeMessage("Z", target.location.blockZ, placeholders)
            else -> null
        }
        return if (body == null) null else color + body
    }

    private fun axisRangeMessage(axis: String, coordinate: Int, placeholders: MutableMap<String, String>): String {
        val range = rangeContaining(coordinate)
        placeholders["axis"] = axis
        placeholders["min"] = range.first.toString()
        placeholders["max"] = range.second.toString()
        val key = if (includeWorld) "feature.intel.axis_range_with_world" else "feature.intel.axis_range"
        return plugin.languageManager.formatMessage(key, placeholders)
    }

    private fun rangeContaining(coordinate: Int): Pair<Int, Int> {
        val width = drawRangeWidth()
        val offset = if (width <= 0) 0 else random.nextInt(width + 1)
        var start = roundDown(coordinate - offset, rangeRoundTo)
        var end = start + width
        if (coordinate > end) {
            end = roundUp(coordinate, rangeRoundTo)
            start = end - width
        }
        return min(start, end) to max(start, end)
    }

    private fun drawRangeWidth(): Int {
        val raw = if ("gaussian" == rangeDistribution) {
            rangeMedian + random.nextGaussian() * rangeStdDev
        } else {
            exp(ln(rangeMedian) + random.nextGaussian() * rangeSigma)
        }
        val clamped = min(rangeMax.toDouble(), max(rangeMin.toDouble(), raw)).roundToInt()
        if (rangeRoundTo <= 1) {
            return clamped
        }
        return max(rangeRoundTo, (clamped.toDouble() / rangeRoundTo).roundToInt() * rangeRoundTo)
    }

    private fun roundDown(value: Int, step: Int): Int {
        if (step <= 1) return value
        return Math.floorDiv(value, step) * step
    }

    private fun roundUp(value: Int, step: Int): Int {
        if (step <= 1) return value
        return -Math.floorDiv(-value, step) * step
    }

    private fun sanitizeAxisTypes(configured: List<String>): List<String> {
        return configured
            .map { it.lowercase(Locale.ROOT) }
            .filter { VALID_AXIS_TYPES.contains(it) }
    }

    private fun sanitizeLegacyFragmentTypes(configured: List<String>): List<String> {
        return configured
            .map { it.lowercase(Locale.ROOT) }
            .mapNotNull {
                when (it) {
                    "x_range" -> "x"
                    "y_range" -> "y"
                    "z_range" -> "z"
                    else -> null
                }
            }
            .filter { VALID_AXIS_TYPES.contains(it) }
    }

    private fun sanitizeAlphabet(configured: String): String {
        val unique = StringBuilder()
        for (char in configured) {
            if (!char.isWhitespace() && unique.indexOf(char.toString()) < 0) {
                unique.append(char)
            }
        }
        return if (unique.length >= 2) unique.toString() else DEFAULT_LABEL_ALPHABET
    }

    private fun labelForGem(gemId: UUID): String {
        if (!labelEnabled) {
            return plugin.languageManager.getMessage("feature.intel.default_label")
        }
        val code = codeForGem(gemId)
        return labelFormat
            .replace("{code}", code)
            .replace("%code%", code)
            .replace("<code>", code)
    }

    private fun codeForGem(gemId: UUID): String {
        val sorted = gemManager.allGemUuids.sortedBy { it.toString() }
        val index = sorted.indexOf(gemId).let {
            if (it >= 0) it else Math.floorMod(gemId.hashCode(), Int.MAX_VALUE)
        }
        return encodeIndex(index)
    }

    private fun encodeIndex(index: Int): String {
        val base = labelAlphabet.length
        var value = index
        val result = StringBuilder()
        do {
            result.append(labelAlphabet[value % base])
            value = value / base - 1
        } while (value >= 0)
        while (result.length < labelMinLength) {
            result.append(labelAlphabet[0])
        }
        return result.reverse().toString()
    }

    private fun colorForGem(gemId: UUID): String {
        val mixed = gemId.mostSignificantBits xor java.lang.Long.rotateLeft(gemId.leastSignificantBits, 17)
        val hue = Math.floorMod(mixed, 360L).toDouble()
        val rgb = hslToRgb(hue, 0.72, 0.62)
        return String.format(Locale.ROOT, "&#%02X%02X%02X", rgb[0], rgb[1], rgb[2])
    }

    private fun hslToRgb(hueDegrees: Double, saturation: Double, lightness: Double): IntArray {
        val chroma = (1.0 - Math.abs(2.0 * lightness - 1.0)) * saturation
        val hue = hueDegrees / 60.0
        val x = chroma * (1.0 - Math.abs(hue % 2.0 - 1.0))
        val (red, green, blue) = when {
            hue < 1.0 -> Triple(chroma, x, 0.0)
            hue < 2.0 -> Triple(x, chroma, 0.0)
            hue < 3.0 -> Triple(0.0, chroma, x)
            hue < 4.0 -> Triple(0.0, x, chroma)
            hue < 5.0 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        val match = lightness - chroma / 2.0
        return intArrayOf(toRgbChannel(red + match), toRgbChannel(green + match), toRgbChannel(blue + match))
    }

    private fun toRgbChannel(value: Double): Int {
        return min(255.0, max(0.0, value * 255.0)).roundToInt()
    }

    private class IntelTarget(
        val gemId: UUID,
        val location: Location,
    )

    companion object {
        private const val PERMISSION = "rulegems.intel"
        private const val DEFAULT_LABEL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val DEFAULT_AXIS_TYPES = listOf("x", "z")
        private val VALID_AXIS_TYPES = setOf("x", "y", "z")
    }
}
