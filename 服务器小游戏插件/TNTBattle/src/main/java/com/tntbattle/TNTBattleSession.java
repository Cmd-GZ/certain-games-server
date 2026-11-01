package com.tntbattle;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/** TNT 大战的会话（D22：会话层按场地键索引；本游戏目前是单场地）。 */
final class TNTBattleSession extends GameSession {

    private final TNTBattleManager manager;

    TNTBattleSession(TNTBattleManager manager, String arenaName) {
        super("tntbattle", arenaName);
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
