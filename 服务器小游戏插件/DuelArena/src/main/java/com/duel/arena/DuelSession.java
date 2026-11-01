package com.duel.arena;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/** 单挑 PvP 的会话（D22：每个场地一个会话）。 */
final class DuelSession extends GameSession {

    private final DuelManager manager;

    DuelSession(DuelManager manager, String arenaName) {
        super("duel", arenaName);
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
        manager.doEndFight(arenaName());
    }
}
