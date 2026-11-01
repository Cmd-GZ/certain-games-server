package com.snakeparty;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/**
 * 蛇行派对的会话（D22：会话层按场地键索引，单图游戏只有一个键）。
 */
final class SnakePartySession extends GameSession {

    private final SnakePartyManager manager;

    SnakePartySession(SnakePartyManager manager, String arenaName) {
        super("snakeparty", arenaName);
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
