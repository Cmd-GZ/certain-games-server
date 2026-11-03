package com.oneshot;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** One Shot 的对局会话（D22：会话层按场地键索引，单图游戏只有一个键）。 */
final class OneShotSession extends GameSession {

    private final OneShotManager manager;

    OneShotSession(OneShotManager manager, String arenaName) {
        super("oneshot", arenaName);
        this.manager = manager;
        // 本局禁止丢任何物品（原 listener 里那段 @EventHandler，逐字等价）
        rules().noItemDrop(true);

        // I3 C1（区域裁决）：本局禁改方块，且**只在自家场地范围内**表态。
        arbiter()
                .on(com.hub.session.EventKind.BLOCK_BREAK, ctx -> ctx.cancel())
                .on(com.hub.session.EventKind.BLOCK_PLACE, ctx -> ctx.cancel());
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
        manager.handleQuit(player);
    }

    @Override
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!manager.isInGame(player)) {
            return;
        }
        event.deathMessage(null);
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        manager.handleDeath(player);
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInGame(player)) {
            return;
        }
        Location spot = manager.arena().spectatorSpot();
        if (spot != null) {
            event.setRespawnLocation(spot);
        }
        manager.respawnedNextTick(player);
    }
}
