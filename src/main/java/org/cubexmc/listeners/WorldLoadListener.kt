package org.cubexmc.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.cubexmc.manager.GemManager

/**
 * 监听世界加载，绑定那些在 RuleGems 启动时所在世界尚未加载的已放置宝石。
 *
 * 处理 Multiverse / MultiWorld 等在本插件之后才载入世界的场景：
 * 此前这些宝石会被丢弃，并被 ensureConfiguredGemsPresent 误判为缺失而复制一颗。
 */
class WorldLoadListener(private val gemManager: GemManager) : Listener {
    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        gemManager.handleWorldLoad(event.world)
    }
}
