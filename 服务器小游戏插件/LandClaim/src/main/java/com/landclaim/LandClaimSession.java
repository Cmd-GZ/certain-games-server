package com.landclaim;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/**
 * LandClaim 的对局会话（D22：会话层统一按场地键索引，单图游戏只有一个键）。
 */
final class LandClaimSession extends GameSession {

    private final LandClaimManager manager;

    LandClaimSession(LandClaimManager manager, String arenaName) {
        super("landclaim", arenaName);
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
