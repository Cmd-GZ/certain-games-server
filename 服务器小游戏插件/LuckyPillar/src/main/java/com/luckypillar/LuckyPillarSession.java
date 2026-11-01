package com.luckypillar;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/** LuckyPillar 的对局会话（D22：每个场地一个会话）。 */
final class LuckyPillarSession extends GameSession {

    private final LuckyPillarManager manager;

    LuckyPillarSession(LuckyPillarManager manager, String arenaName) {
        super("luckypillar", arenaName);
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
