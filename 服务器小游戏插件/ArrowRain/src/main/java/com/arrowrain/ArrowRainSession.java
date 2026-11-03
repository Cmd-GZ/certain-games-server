package com.arrowrain;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** ArrowRain 的对局会话（D22：会话层按场地键索引）。 */
final class ArrowRainSession extends GameSession {

    private final ArrowRainManager manager;

    ArrowRainSession(ArrowRainManager manager, String arenaName) {
        super("arrowrain", arenaName);
        this.manager = manager;
        // 本局禁止丢任何物品（原 listener 里那段 @EventHandler，逐字等价）
        rules().noItemDrop(true);
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
        if (!manager.isPlaying(player)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        manager.eliminate(player, "被其他玩家击杀");
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInGame(player)) {
            return;
        }
        Location lobby = manager.arena().lobby();
        if (lobby != null) {
            event.setRespawnLocation(lobby);
        }
        manager.spectateNextTick(player);
    }
}
