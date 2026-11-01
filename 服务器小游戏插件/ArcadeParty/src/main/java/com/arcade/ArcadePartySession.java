package com.arcade;

import com.hub.session.EndReason;
import com.hub.session.GameSession;

/**
 * 一次「派对」= 一个对局会话（对应 {@code docs/框架不变量.md} 的 I1 / I4 / I5）。
 *
 * <p><b>为什么只有一个 session、没有 children（决策 D17）</b>：
 * 判断只有一条 —— <b>父 session 是否必须比子 session 活得久</b>。
 * <ul>
 *   <li>{@code flow: single}（<b>PARTY_OF_ONE</b>，单局包装成派对）：只打一个小游戏，
 *       游戏结束 = 整局结束 → <b>一个 session，和单局小游戏完全同一条路径</b>，
 *       差别只在展示层（没有领奖台、没有"下一局"）。</li>
 *   <li>{@code flow: party}（多局串烧）：结构上最终会变成 COMPOSITE（父 + 每局一个子），
 *       但那是**阶段二**的事：要等被包装的小游戏自己接入 {@code GameSession} 之后，
 *       父才能 {@code adopt} 它们。在那之前这里只建根会话、children 恒为空。</li>
 * </ul>
 *
 * <p>所以本类在阶段一只做三件事：把状态机接上、把参与者挂到归属链上（I1）、
 * 并把收场收敛到唯一的 {@link #end(EndReason)}。玩法流程仍留在 {@link ArcadePartyManager}
 * （这一轮不改玩法，只换生命周期外壳 —— 与 HillKing 的迁法一致）。
 *
 * <p>注意：{@code end()} 是 {@code final} 且幂等，收场顺序由框架写死；
 * 本类只能实现 {@link #onStart()} / {@link #onEnd(EndReason)}。
 */
final class ArcadePartySession extends GameSession {

    /** 会话 key（{@code /hub doctor} 的 [对局] 段用它显示，如 {@code {arcade=1}}）。 */
    public static final String KEY = "arcade";

    private final ArcadePartyManager manager;

    ArcadePartySession(ArcadePartyManager manager) {
        // arenaName 用派对的场地名（"主场地"），和 HubAPI 上报的场地保持一致
        super(KEY, ArcadePartyManager.ARENA);
        this.manager = manager;
        enterWaiting();   // 一创建就进等待室（protected 只能自己调）
    }

    /**
     * 开始派对（WAITING → RUNNING）。
     *
     * <p>{@code enterRunning()} 在框架里是 {@code protected}，只有会话自己能动自己的状态（I5），
     * 所以在这里开一个小口子给 manager 用。
     */
    void start() {
        enterRunning();
    }

    /** 进入 RUNNING 之前调一次；起计时器/传送仍由 manager 的原流程做（本轮行为零变化）。 */
    @Override
    protected void onStart() {
        // 见类注释
    }

    /** 收场：把控制权交回 manager 的唯一收场逻辑，由框架保证只执行一次。 */
    @Override
    protected void onEnd(EndReason reason) {
        manager.doEndParty(reason);
    }
}
