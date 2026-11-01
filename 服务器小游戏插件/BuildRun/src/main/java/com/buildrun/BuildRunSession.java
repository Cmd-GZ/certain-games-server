package com.buildrun;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/**
 * 你建我跑的对局会话（D22：**每个场地一个会话**，多图游戏照 TntRun 的写法）。
 */
final class BuildRunSession extends GameSession {

    private final BuildRunManager manager;

    BuildRunSession(BuildRunManager manager, String arenaName) {
        super("buildrun", arenaName);
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
