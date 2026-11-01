package com.skypit;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/**
 * 天坑的会话 —— **一个"特殊会话"**（用户 2026-11-02 的定法）。
 *
 * <p>天坑不是"一局一局"的游戏：它挂在常驻世界里，**没有 start/end 轮次**。
 * 所以这里的语义是：
 * <ul>
 *   <li>玩家**进入天坑区域且处于生存/冒险模式** → 视为入局（{@code enterPit}）；</li>
 *   <li>玩家**死亡或离开区域** → 视为这一轮结束（离开会话）；</li>
 *   <li>最后一个人离开 → 会话由框架自动 {@code end(ABORT)}。</li>
 * </ul>
 *
 * <p>没有等待阶段：第一个进来的人直接把会话推到 RUNNING。
 */
final class SkyPitSession extends GameSession {

    SkyPitSession() {
        super("skypit", "pit");
        enterWaiting();
    }

    void start() {
        enterRunning();
    }

    @Override
    protected void onStart() {
        // 天坑没有开局准备动作
    }

    @Override
    protected void onEnd(EndReason reason) {
        // 没有需要结算的东西：物资/背包由 SavedState 负责归还，击杀数走原有统计
    }
}
