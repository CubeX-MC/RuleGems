package org.cubexmc.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.cubexmc.manager.GemManager;

/**
 * 监听世界加载，绑定那些在 RuleGems 启动时所在世界尚未加载的已放置宝石。
 *
 * <p>处理 Multiverse / MultiWorld 等在本插件之后才载入世界的场景：
 * 此前这些宝石会被丢弃，并被 ensureConfiguredGemsPresent 误判为缺失而复制一颗。</p>
 */
public class WorldLoadListener implements Listener {

    private final GemManager gemManager;

    public WorldLoadListener(GemManager gemManager) {
        this.gemManager = gemManager;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        gemManager.handleWorldLoad(event.getWorld());
    }
}
