package com.jumpfill;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/**
 * JumpFill 的对局会话（D22：会话层统一按场地键索引，单图游戏只有一个键）。
 */
final class JumpFillSession extends GameSession {

    private final JumpFillManager manager;

    JumpFillSession(JumpFillManager manager, String arenaName) {
        super("jumpfill", arenaName);
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
        manager.doEndGame();
    }
}
