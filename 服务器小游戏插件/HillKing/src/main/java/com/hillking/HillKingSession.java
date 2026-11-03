package com.hillking;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 高台之王的对局会话 —— 第一个接入 {@code com.hub.session} 的游戏（P1a / R1.5 样板），
 * 也是 **I3（事件裁决唯一）第一批样板**：下线 / 死亡 / 重生 / 不掉饥饿 / 不掉落
 * 全部改由框架的单一裁决入口 {@link com.hub.session.SessionEvents} 派发到这里，
 * 游戏模块里不再有对应的 {@code @EventHandler}。
 *
 * <p>它只做两件事：把框架的状态机接上，并在结束时**按 reason 分流**。
 * 具体的玩法逻辑仍在 {@link HillKingManager} 里（这一轮不改玩法，只换生命周期外壳）。
 *
 * <p>关键点：{@link #onEnd(EndReason)} 里只有 {@code ROUND_END} 才结算成绩。
 * 这样"玩家 /hub 退出导致人走空"（ABORT）不会污染战绩。
 */
final class HillKingSession extends GameSession {

    private final HillKingManager manager;

    HillKingSession(HillKingManager manager) {
        super("hillking", "");
        this.manager = manager;
        // 本局禁止丢任何物品（重构前是 HillKingListener 里的一段 @EventHandler，逐字等价）
        rules().noItemDrop(true);
        enterWaiting();   // 一创建就进等待室（protected 只能自己调）
    }

    /**
     * 开始对局（WAITING → RUNNING）。
     *
     * <p>框架把 {@code enterRunning()} 设成 {@code protected}，只有会话自己能动自己的状态 ——
     * 所以由这里开一个小口子给 manager 用。这样"状态只能由会话推进"这条（I5）没被破坏。
     */
    void start() {
        enterRunning();
    }

    /**
     * 进入 RUNNING 之前的钩子。
     *
     * <p>当前实现为空：起计时器、传送、发装备仍由 {@link HillKingManager#startGame(boolean)}
     * 的原逻辑完成（这一轮的目标是"行为零变化"）。等 R3/R4 把玩法逻辑收进 session 时再搬过来。
     */
    @Override
    protected void onStart() {
        // 见类注释：本轮不改玩法，保持行为一致
    }

    /** 收场：把控制权交回 manager 的原有收场逻辑，但由框架保证只调一次。 */
    @Override
    protected void onEnd(EndReason reason) {
        manager.doEndGame(reason);
    }

    // ---------- I3 第一批：从 HillKingListener 搬过来的三个处理器 ----------

    /** 下线：交给 manager 清理自己的状态（scores / 计分板 / 场次）。**摘归属链由框架兜底**。 */
    @Override
    protected void onPlayerQuit(Player player) {
        manager.handleQuit(player);
    }

    /** 本局不死人：取消死亡并回满血量/饱食度（高台之王靠掉台淘汰，不靠死）。 */
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

    /** 重生点回到本场地的等待室（掉进虚空之类的情况）。 */
    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInGame(player)) {
            return;
        }
        if (manager.arena().getLobby() != null) {
            event.setRespawnLocation(manager.arena().getLobby());
        }
    }
}
