package com.animalslaughter;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/**
 * 动物屠宰的对局会话（D22：会话层统一按场地键索引，单图游戏只有一个键）。
 * 样板：{@code MemoryFlipSession}。
 */
final class AnimalSlaughterSession extends GameSession {

    private final AnimalSlaughterManager manager;

    AnimalSlaughterSession(AnimalSlaughterManager manager, String arenaName) {
        super("animalslaughter", arenaName);
        this.manager = manager;
        enterWaiting();
    }

    void start() {
        enterRunning();
    }

    @Override
    protected void onStart() {
        // 本轮不改玩法：计时/传送仍由 manager 负责
    }

    @Override
    protected void onEnd(EndReason reason) {
        manager.doEndGame();
    }
}
