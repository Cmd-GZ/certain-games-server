package com.buildbattle.gtb.game;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/** 建筑猜猜乐的对局会话（D22：会话层按场地键索引）。 */
final class GTBSession extends GameSession {

    private final GameManager manager;

    GTBSession(GameManager manager, String arenaName) {
        super("gtb", arenaName);
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
