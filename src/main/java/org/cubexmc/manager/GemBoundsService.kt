package org.cubexmc.manager

import org.bukkit.Location
import org.bukkit.World
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * 宝石合法活动区域的唯一真相源：放置、散落、逃逸都以这里为准。
 *
 * 合法区域 = 该宝石的 random_place_range（未单独配置时用全局默认） ∩ 原版世界边界。
 *
 * 为什么不能只看原版 [World.getWorldBorder]：
 *  - 原版边界只能是正方形，表达不了矩形边界；
 *  - ChunkyBorder 这类边界插件默认不同步原版边界，此时原版半径仍是 2999.9 万格，
 *    `isInside` 恒为 true，校验等于没写。
 *
 * random_place_range 是管理员对"宝石该待在哪"的唯一声明，因此把它作为硬约束：
 * 使用边界插件的服务器只要把 random_place_range 配在自己的边界内，宝石就不会逃出可玩区域。
 */
class GemBoundsService(
    private val gemParser: GemDefinitionParser,
    private val gameplayConfig: GameplayConfig,
    private val stateManager: GemStateManager,
    private val logger: Logger,
) {
    /** X/Z 闭区间；Y 不参与，随机放置总是取该列的最高方块之上一格。 */
    data class Bounds(
        val world: World,
        val minX: Int,
        val maxX: Int,
        val minZ: Int,
        val maxZ: Int,
    ) {
        fun contains(x: Int, z: Int): Boolean = x in minX..maxX && z in minZ..maxZ

        fun clampX(x: Int): Int = x.coerceIn(minX, maxX)

        fun clampZ(z: Int): Int = z.coerceIn(minZ, maxZ)
    }

    fun boundsFor(gemId: UUID?): Bounds? {
        val range = configuredRange(gemId) ?: return null
        val world = range[0].world ?: return null
        if (world != range[1].world) return null

        var minX = minOf(range[0].blockX, range[1].blockX)
        var maxX = maxOf(range[0].blockX, range[1].blockX)
        var minZ = minOf(range[0].blockZ, range[1].blockZ)
        var maxZ = maxOf(range[0].blockZ, range[1].blockZ)

        val border = vanillaBorder(world)
        if (border != null) {
            val intersected = intersect(minX, maxX, minZ, maxZ, border)
            if (intersected == null) {
                // 配置范围与原版边界没有交集时不存在合法位置。必须失败关闭，
                // 不能为了"保证散落成功"而把宝石生成到世界边界之外。
                warnOnce(world.name)
                return null
            } else {
                minX = intersected.minX
                maxX = intersected.maxX
                minZ = intersected.minZ
                maxZ = intersected.maxZ
            }
        }
        return Bounds(world, minX, maxX, minZ, maxZ)
    }

    fun isInside(gemId: UUID?, location: Location?): Boolean {
        if (location == null || location.world == null) return false
        val bounds = boundsFor(gemId)
        if (bounds == null) {
            // 有配置却算不出交集代表配置无效，不得退化成"只要在世界边界内就算合法"。
            return configuredRange(gemId) == null && isInsideBorder(location)
        }
        if (bounds.world != location.world) return false
        return bounds.contains(location.blockX, location.blockZ)
    }

    // ---- 逃逸专用：只看原版世界边界，不看 random_place_range ----
    //
    // 局部逃逸是"让宝石在当前位置附近游走"，用 random_place_range 卡它是错的：
    // 那个范围的语义只是"随机散落取点的范围"，一旦拿它约束逃逸，离开出生点区域的宝石
    // 每次逃逸都会被拽回盒子里。全局兜底重散落才该用 random_place_range，
    // 那是配置注释明确要求的行为。

    /** 是否在原版世界边界内（含安全边距）。没有设置边界时恒为 true。 */
    fun isInsideBorder(location: Location?): Boolean {
        val world = location?.world ?: return false
        val border = vanillaBorder(world) ?: return true
        return border.contains(location.blockX, location.blockZ)
    }

    /** 把 X/Z 夹回原版世界边界内，保留 Y。没有设置边界时原样返回。 */
    fun clampToBorder(location: Location): Location {
        val world = location.world ?: return location
        val border = vanillaBorder(world) ?: return location
        if (border.contains(location.blockX, location.blockZ)) return location
        return Location(
            world,
            border.clampX(location.blockX).toDouble(),
            location.y,
            border.clampZ(location.blockZ).toDouble(),
        )
    }

    /**
     * 这个世界是否设置了**有实际约束力**的原版边界。
     *
     * 原版默认直径 5999.9968 万格，等于没有边界；此时逃逸没有任何上界，
     * 宝石会一路漂移进从未生成的地形，触发区块生成。用来在启动时提示管理员。
     */
    fun hasEffectiveBorder(world: World?): Boolean {
        if (world == null) return false
        return try {
            world.worldBorder.size < UNBOUNDED_BORDER_SIZE
        } catch (_: Throwable) {
            false
        }
    }

    /** 在合法区域内均匀取一个 X/Z；Y 留给调用方按最高方块计算。 */
    fun randomColumn(gemId: UUID?, random: Random): Location? {
        val bounds = boundsFor(gemId) ?: return null
        val x = bounds.minX + random.nextInt(bounds.maxX - bounds.minX + 1)
        val z = bounds.minZ + random.nextInt(bounds.maxZ - bounds.minZ + 1)
        return Location(bounds.world, x.toDouble(), bounds.world.minHeight.toDouble() + 1, z.toDouble())
    }

    /** 合法区域的中心列，作为所有随机尝试耗尽后的最终兜底。 */
    fun centerColumn(gemId: UUID?): Location? {
        val bounds = boundsFor(gemId) ?: return null
        val x = ((bounds.minX.toLong() + bounds.maxX.toLong()) / 2L).toInt()
        val z = ((bounds.minZ.toLong() + bounds.maxZ.toLong()) / 2L).toInt()
        return Location(bounds.world, x.toDouble(), bounds.world.minHeight.toDouble() + 1, z.toDouble())
    }

    private fun configuredRange(gemId: UUID?): Array<Location>? {
        val gemKey = stateManager.getGemKey(gemId)
        if (gemKey != null) {
            for (definition in gemParser.gemDefinitions) {
                if (definition.gemKey == gemKey) {
                    val first = definition.randomPlaceCorner1
                    val second = definition.randomPlaceCorner2
                    if (first != null && second != null) {
                        return arrayOf(first, second)
                    }
                    break
                }
            }
        }
        val defaultFirst = gameplayConfig.randomPlaceCorner1
        val defaultSecond = gameplayConfig.randomPlaceCorner2
        if (defaultFirst != null && defaultSecond != null) {
            return arrayOf(defaultFirst, defaultSecond)
        }
        return null
    }

    private fun vanillaBorder(world: World): Bounds? {
        return try {
            val border = world.worldBorder
            val center = border.center
            val half = border.size / 2.0 - EDGE_MARGIN
            if (half <= 0.0) return null
            Bounds(
                world,
                toSafeInt(center.x - half),
                toSafeInt(center.x + half),
                toSafeInt(center.z - half),
                toSafeInt(center.z + half),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun intersect(minX: Int, maxX: Int, minZ: Int, maxZ: Int, border: Bounds): Bounds? {
        val resultMinX = maxOf(minX, border.minX)
        val resultMaxX = minOf(maxX, border.maxX)
        val resultMinZ = maxOf(minZ, border.minZ)
        val resultMaxZ = minOf(maxZ, border.maxZ)
        if (resultMinX > resultMaxX || resultMinZ > resultMaxZ) return null
        return Bounds(border.world, resultMinX, resultMaxX, resultMinZ, resultMaxZ)
    }

    private fun toSafeInt(value: Double): Int =
        value.coerceIn(-MAX_COORDINATE, MAX_COORDINATE).toInt()

    private fun warnOnce(worldName: String) {
        if (warnedWorlds.add(worldName)) {
            logger.warning(
                "random_place_range for world '$worldName' lies entirely outside the vanilla world border; " +
                    "refusing to place a gem outside the border. Fix the configured range.",
            )
        }
    }

    // isInside 会被各区域线程并发调用（Folia），这里必须是并发集合。
    private val warnedWorlds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    companion object {
        /** 与原版边界保持的安全距离，避免宝石落在会持续掉血的边界带上。 */
        private const val EDGE_MARGIN = 4.0
        private const val MAX_COORDINATE = 30_000_000.0

        /** 大于等于这个直径就视为"没有设置边界"，原版默认值是 5999.9968 万。 */
        private const val UNBOUNDED_BORDER_SIZE = 100_000.0
    }
}
