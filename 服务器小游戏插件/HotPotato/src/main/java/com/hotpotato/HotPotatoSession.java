package com.hotpotato;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/**
 * HotPotato 的对局会话（D22：**每个场地一个会话**，多图游戏照 TntRun 的写法）。
 */
final class HotPotatoSession extends GameSession {

    private final HotPotatoManager manager;

    HotPotatoSession(HotPotatoManager manager, String arenaName) {
        super("hotpotato", arenaName);
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
