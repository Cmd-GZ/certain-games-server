package com.memoryflip;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

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
}
