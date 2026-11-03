package com.memoryflip;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 记忆翻牌的对局会话 —— 第 3 个接入 {@code com.hub.session} 的游戏
 * （样板见 {@code HillKingSession}）。
 *
 * <p>它只负责把框架的状态机接上：创建即 WAITING，开局推 RUNNING，
 * 收场统一走 {@link #onEnd(EndReason)} → {@code manager.doEndGame}。
 * 玩法逻辑仍留在 manager 里（这一轮不改玩法，只换生命周期外壳）。
 */
final class MemoryFlipSession extends GameSession {

    private final MemoryFlipManager manager;

    MemoryFlipSession(MemoryFlipManager manager, String arenaName) {
        super("memoryflip", arenaName);
        this.manager = manager;
        // 本局禁止丢任何物品（原 listener 里那段 @EventHandler，逐字等价）
        rules().noItemDrop(true);
        enterWaiting();
    }

    /** WAITING → RUNNING（状态只能由会话自己推进，见 I5）。 */
    void start() {
        enterRunning();
    }

    @Override
    protected void onStart() {
        // 本轮不改玩法：起计时器/传送仍由 manager 负责
    }

    @Override
    protected void onEnd(EndReason reason) {
        manager.doEndGame(reason);
    }

    // ---------- I3 A 批：从 listener 搬过来的处理器 ----------

    @Override
    @SuppressWarnings("deprecation")
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!manager.isInGame(player)) {
            return;
        }
        event.setCancelled(true);
        player.setHealth(20.0);
        player.setFoodLevel(20);
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInGame(player)) {
            return;
        }
        Location target = manager.arena().getLobby();
        if (target != null) {
            event.setRespawnLocation(target);
        }
    }
}
