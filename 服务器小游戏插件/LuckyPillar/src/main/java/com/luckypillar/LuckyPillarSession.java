package com.luckypillar;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** LuckyPillar 的对局会话（D22：每个场地一个会话）。 */
final class LuckyPillarSession extends GameSession {

    private final LuckyPillarManager manager;

    LuckyPillarSession(LuckyPillarManager manager, String arenaName) {
        super("luckypillar", arenaName);
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
        manager.doEndMatch(arenaName());
    }

    // ---------- I3 A 批：从 listener 搬过来的处理器 ----------

    @Override
    protected void onPlayerQuit(Player player) {
        manager.handleQuit(player);
    }

    @Override
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        LuckyPillarArena a = manager.activeArenaOf(p.getUniqueId());
        if (a == null || a.getState() == LuckyPillarArena.State.WAITING) {
            return;
        }
        if (a.getState() == LuckyPillarArena.State.RUNNING && !a.getPlayers().contains(p.getUniqueId())) {
            return;
        }
        // 死亡掉落：不 keepInventory，也**不清 drops**（原来这里清空 = 谁死了身上东西直接没）
        event.setKeepInventory(false);
        event.setDroppedExp(0);
        event.deathMessage(null);
        manager.handleDeath(p);
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        manager.handleRespawn(event.getPlayer(), event);
    }
}
