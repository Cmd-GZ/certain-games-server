package com.tntrun;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/**
 * 雪塔大作战的对局会话 —— **第一个多场地游戏**接入 {@code com.hub.session}。
 *
 * <p>和单场地的 HillKing / MemoryFlip 不同：这个游戏每个场地可以**同时各开一局**，
 * 所以**一个场地一个会话**（`Sessions` 天然支持，`activeByGame()` 会显示成 `tntrun=2`）。
 * 会话名里带上场地名，doctor 里能一眼区分是哪一块场地。
 */
final class TntRunSession extends GameSession {

    private final TntRunManager manager;

    TntRunSession(TntRunManager manager, String arenaName) {
        super("tntrun", arenaName);
        this.manager = manager;
        enterWaiting();
    }

    /** WAITING → RUNNING（状态只能由会话自己推进，见 I5）。 */
    void start() {
        enterRunning();
    }

    @Override
    protected void onStart() {
        // 本轮不改玩法：计时/传送仍由 manager 负责
    }

    @Override
    protected void onEnd(EndReason reason) {
        manager.doEndGame(arenaName());
    }
}
