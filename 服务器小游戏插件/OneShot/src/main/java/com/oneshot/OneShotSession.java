package com.oneshot;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/** One Shot 的对局会话（D22：会话层按场地键索引，单图游戏只有一个键）。 */
final class OneShotSession extends GameSession {

    private final OneShotManager manager;

    OneShotSession(OneShotManager manager, String arenaName) {
        super("oneshot", arenaName);
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
