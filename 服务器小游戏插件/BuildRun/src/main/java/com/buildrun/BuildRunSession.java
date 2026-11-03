package com.buildrun;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * 你建我跑的对局会话（D22：**每个场地一个会话**，多图游戏照 TntRun 的写法）。
 */
final class BuildRunSession extends GameSession {

    private final BuildRunManager manager;

    BuildRunSession(BuildRunManager manager, String arenaName) {
        super("buildrun", arenaName);
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
        manager.doEndGame(arenaName());
    }

    // ---------- I3 A 批：从 listener 搬过来的处理器 ----------

    @Override
    protected void onPlayerQuit(Player player) {
        manager.handleQuit(player);
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        String arenaName = manager.arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        BuildRunArena a = manager.getArena(arenaName);
        if (a == null) {
            return;
        }
        Location loc = a.getLobby() != null ? a.getLobby() : a.getSpawn();
        if (a.getPhase() == BuildRunArena.Phase.RUN) {
            BuildRunPlot plot = a.currentPlot();
            if (plot != null && plot.getSpawn() != null) {
                loc = plot.getSpawn();
            }
        }
        if (loc != null) {
            event.setRespawnLocation(loc);
        }
    }

    /** 掉落：自家道具一律不许丢；对局中其余物品也不许丢（原 listener 里那段 @EventHandler）。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        if (manager.isOurItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            return;
        }
        if (manager.forbidItemDrop(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
