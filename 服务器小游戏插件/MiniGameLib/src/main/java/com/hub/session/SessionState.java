package com.hub.session;

/**
 * 对局状态 —— {@code docs/框架不变量.md} 的 <b>I5 状态转移封闭</b>。
 *
 * <p>状态只能按下面这张表变化，非法转移由 {@link GameSession} 抛异常：
 *
 * <pre>
 * CREATED ──► WAITING ──► RUNNING ──► ENDING ──► ENDED
 *    │           │           │          │
 *    └───────────┴───────────┴──────────┴──► ENDED（任何状态都能直接 end，但只能一次）
 * </pre>
 */
public enum SessionState {

    /** 已创建，还没进等待室。 */
    CREATED,

    /** 等待室：可以加入/退出。 */
    WAITING,

    /** 对局进行中。 */
    RUNNING,

    /** 收场中（正在还原物品、结算、传送）。 */
    ENDING,

    /** 已结束（终态，不会再变）。 */
    ENDED;

    /** 是否终态。 */
    public boolean isFinal() {
        return this == ENDED;
    }

    /** 是否已经不能加入。 */
    public boolean isClosed() {
        return this == ENDING || this == ENDED;
    }
}
