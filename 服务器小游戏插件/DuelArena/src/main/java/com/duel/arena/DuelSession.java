package com.duel.arena;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** 单挑 PvP 的会话（D22：每个场地一个会话）。 */
final class DuelSession extends GameSession {

    private final DuelManager manager;

    DuelSession(DuelManager manager, String arenaName) {
        super("duel", arenaName);
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
        manager.doEndFight(arenaName());
    }

    // ---------- I3 A 批：从 DuelListener 搬过来的处理器 ----------

    @Override
    protected void onPlayerQuit(Player player) {
        manager.handleQuit(player);
    }

    @Override
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (manager.arenaOf(p.getUniqueId()) == null) {
            return;
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.deathMessage(null);
        manager.handleDeath(p);
    }

    /** 在场内重生 → 回本场等待点。（"不在场内却站在决斗世界"那半段留在 listener 里当全服兜底） */
    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        String name = manager.arenaOf(p.getUniqueId());
        if (name == null) {
            return;
        }
        DuelArena a = manager.getArena(name);
        if (a != null && a.getLobbyPoint() != null) {
            event.setRespawnLocation(a.getLobbyPoint());
        }
    }
}
