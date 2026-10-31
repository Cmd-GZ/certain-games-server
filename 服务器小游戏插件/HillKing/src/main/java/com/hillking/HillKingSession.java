package com.hillking;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/**
 * 高台之王的对局会话 —— 第一个接入 {@code com.hub.session} 的游戏（P1a / R1.5 样板）。
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
}
