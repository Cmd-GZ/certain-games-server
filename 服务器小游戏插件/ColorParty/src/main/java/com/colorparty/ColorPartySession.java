package com.colorparty;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/**
 * 色盲派对的对局会话（D22：**每个场地一个会话**）。
 */
final class ColorPartySession extends GameSession {

    private final ColorPartyManager manager;

    ColorPartySession(ColorPartyManager manager, String arenaName) {
        super("colorparty", arenaName);
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
