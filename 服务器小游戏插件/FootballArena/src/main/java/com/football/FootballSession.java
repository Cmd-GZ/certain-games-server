package com.football;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/** 绿茵足球的会话（D22：每个场地一个会话）。 */
final class FootballSession extends GameSession {

    private final FootballManager manager;

    FootballSession(FootballManager manager, String arenaName) {
        super("football", arenaName);
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
        manager.doEndMatch(arenaName());
    }
}
