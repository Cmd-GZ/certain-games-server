package com.tntbattle;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/** TNT 大战的会话（D22：会话层按场地键索引；本游戏目前是单场地）。 */
final class TNTBattleSession extends GameSession {

    private final TNTBattleManager manager;

    TNTBattleSession(TNTBattleManager manager, String arenaName) {
        super("tntbattle", arenaName);
        this.manager = manager;
        // 框架默认"局内不掉饥饿"（与其它 24 个游戏的处理器一致），但**本局是例外**：
        // TNT 大战在对局中故意允许掉饥饿（玩家吃牛排回血是玩法的一部分），
        // 所以这里显式关掉这条环境规则，仍旧由 TNTBattleListener.onHunger 按
        // "isPlaying && !isRunning" 自己分流。
        rules().noHunger(false);
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
