package com.knockback;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/** 击退大作战的会话（D22：会话层按场地键索引，单图游戏只有一个键）。 */
final class KnockbackSession extends GameSession {

    private final KnockbackManager manager;

    KnockbackSession(KnockbackManager manager, String arenaName) {
        super("knockback", arenaName);
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
