package com.arrowrain;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/** ArrowRain 的对局会话（D22：会话层按场地键索引）。 */
final class ArrowRainSession extends GameSession {

    private final ArrowRainManager manager;

    ArrowRainSession(ArrowRainManager manager, String arenaName) {
        super("arrowrain", arenaName);
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
