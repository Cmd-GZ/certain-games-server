package com.grouprace;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/**
 * 群友竞速的对局会话（D22：会话层统一按场地键索引，单图游戏只有一个键）。
 */
final class GroupRaceSession extends GameSession {

    private final GroupRaceManager manager;

    GroupRaceSession(GroupRaceManager manager, String arenaName) {
        super("grouprace", arenaName);
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
