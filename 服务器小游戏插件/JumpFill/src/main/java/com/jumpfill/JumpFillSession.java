package com.jumpfill;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * JumpFill 的对局会话（D22：会话层统一按场地键索引，单图游戏只有一个键）。
 */
final class JumpFillSession extends GameSession {

    private final JumpFillManager manager;

    JumpFillSession(JumpFillManager manager, String arenaName) {
        super("jumpfill", arenaName);
        this.manager = manager;
        enterWaiting();
    }

    void start() {
        enterRunning();
    }

    @Override
    protected void onStart() {
        // 本轮不改玩法
    }

    @Override
    protected void onEnd(EndReason reason) {
        manager.doEndGame();
    }

    // ---------- I3 A 批：从 listener 搬过来的处理器 ----------

    @Override
    protected void onPlayerQuit(Player player) {
        manager.quit(player);
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isPlaying(player)) {
            return;
        }
        Location start = manager.startForPublic(player.getUniqueId());
        if (start != null) {
            event.setRespawnLocation(start);
        }
        manager.respawnNextTick(player);
    }

    /** 掉落：只禁"开始纸"（原 listener 里那段 @EventHandler）。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        if (manager.isStartItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }
}
