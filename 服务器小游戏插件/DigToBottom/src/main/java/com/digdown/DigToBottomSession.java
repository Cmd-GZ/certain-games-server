package com.digdown;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/**
 * DigToBottom 的对局会话（D22：**每个场地一个会话**，多图游戏照 TntRun 的写法）。
 */
final class DigToBottomSession extends GameSession {

    private final DigToBottomManager manager;

    DigToBottomSession(DigToBottomManager manager, String arenaName) {
        super("digdown", arenaName);
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
}
