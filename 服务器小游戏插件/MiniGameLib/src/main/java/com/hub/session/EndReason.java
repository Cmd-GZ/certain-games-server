package com.hub.session;

/**
 * 对局为什么结束 —— {@code docs/框架不变量.md} 的 <b>I4 生命周期唯一出口</b>。
 *
 * <p>这个枚举存在的唯一理由：把「这一局正常打完了」和「玩家不想玩了」分开。
 * 重构前这两件事共用同一个 {@code teleportToLobby} 信号，于是街机派对只能二选一
 * （要么一局结束全员被踢出派对，要么 {@code /hub} 退不掉、过一会儿又被拉回下一局）。
 *
 * <p>约定哪些 reason 记成绩（各游戏在 {@code onEnd} 里按 reason 分流）：
 * <table border="1">
 *   <tr><th>reason</th><th>记成绩</th><th>典型触发</th></tr>
 *   <tr><td>{@link #ROUND_END}</td><td>是</td><td>时间到、目标达成</td></tr>
 *   <tr><td>{@link #PLAYER_QUIT}</td><td>否</td><td>唯一参与者退出了对局</td></tr>
 *   <tr><td>{@link #ADMIN_STOP}</td><td>默认否</td><td>管理员 {@code /xx stop}</td></tr>
 *   <tr><td>{@link #ABORT}</td><td>否</td><td>人数不足、最后一人离开、场地失效</td></tr>
 *   <tr><td>{@link #ERROR}</td><td>否</td><td>框架侧异常，尽力收场</td></tr>
 * </table>
 */
public enum EndReason {

    /** 正常打完：记成绩、发奖励。 */
    ROUND_END(true),

    /** 唯一参与者退出导致对局结束：不记成绩。注意多人对局里某个人退出走的是 {@code leave()}，不是这个。 */
    PLAYER_QUIT(false),

    /** 管理员手动停：默认不记成绩（各游戏可自行决定）。 */
    ADMIN_STOP(false),

    /** 人数不足 / 最后一人离开 / 场地失效：不记成绩。 */
    ABORT(false),

    /** 框架侧异常，尽力收场：不记成绩，并记 {@code Failures}。 */
    ERROR(false);

    private final boolean recordsScore;

    EndReason(boolean recordsScore) {
        this.recordsScore = recordsScore;
    }

    /** 这个 reason 是否应该记成绩（给各游戏一个统一默认，想改可以自己判断）。 */
    public boolean recordsScore() {
        return recordsScore;
    }
}
