package com.anvilbattle;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 铁砧掘战的对局会话（D22：会话层统一按场地键索引，单图游戏只有一个键）。
 * 样板：{@code MemoryFlipSession} / {@code TntRunSession}。
 */
final class AnvilBattleSession extends GameSession {

    private final AnvilBattleManager manager;

    AnvilBattleSession(AnvilBattleManager manager, String arenaName) {
        super("anvilbattle", arenaName);
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
        // 本轮不改玩法：计时/传送仍由 manager 负责
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
    @SuppressWarnings("deprecation")
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!manager.isInGame(player)) {
            return;
        }
        event.setCancelled(true);
        if (manager.isPlaying(player)) {
            manager.handleDeath(player);
        } else {
            player.setHealth(20.0);
            player.setFoodLevel(20);
        }
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInGame(player)) {
            return;
        }
        Location target = manager.isWaiting(player) || manager.isPlaying(player)
                ? manager.arena().getLobby()
                : manager.spectatorSpot();
        if (target != null) {
            event.setRespawnLocation(target);
        }
    }
}
