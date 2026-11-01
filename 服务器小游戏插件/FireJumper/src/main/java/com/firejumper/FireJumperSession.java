package com.firejumper;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/** 跃火者的对局会话（D22：会话层按场地键索引）。 */
final class FireJumperSession extends GameSession {

    private final FireJumperManager manager;

    FireJumperSession(FireJumperManager manager, String arenaName) {
        super("firejumper", arenaName);
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
