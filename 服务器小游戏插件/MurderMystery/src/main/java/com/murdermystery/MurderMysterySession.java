package com.murdermystery;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/** MurderMystery 的对局会话（D22：每个场地一个会话）。 */
final class MurderMysterySession extends GameSession {

    private final MurderMysteryManager manager;

    MurderMysterySession(MurderMysteryManager manager, String arenaName) {
        super("murdermystery", arenaName);
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
