package com.hub.session;

/**
 * 对局级**环境规则**开关 —— {@code docs/框架不变量.md} 的 <b>I3（事件裁决唯一）</b>
 * 里"环境类"那一半。
 *
 * <p>为什么要这个东西：重构前 25 个游戏模块各写了一遍
 * "局内不掉饥饿"（{@code FoodLevelChangeEvent → setCancelled}），
 * 内容逐字相同，但**每加一个游戏就要再抄一遍**，漏抄的那个游戏就能在局内饿死。
 * 现在这条规则只在框架里存在一处，游戏只用声明"我这局要不要"。
 *
 * <p>开关的默认值是**按线上实际行为反推**出来的，不是拍脑袋：
 * <ul>
 *   <li>{@link #noHunger()} 默认 <b>开</b> —— 25 个模块（23 个游戏 + HubCore + 派对）
 *       里**每一个** FoodLevelChange 处理器都只是"在局内就取消"，没有例外。
 *       所以默认开 = 忠实还原现状，不是新行为。</li>
 *   <li>{@link #noItemDrop()} 默认 <b>关</b> —— 掉落的处理器**不是**清一色的"全禁"：
 *       很多游戏只禁自己的道具（回城床/开始纸/球/咸鱼…），其余物品允许丢
 *       （挖矿类要把多余的方块丢掉）。全局默认禁掉会改变玩法，所以由游戏自己声明。</li>
 * </ul>
 *
 * <p>用法（在会话构造里）：
 * <pre>
 * final class HillKingSession extends GameSession {
 *     HillKingSession(...) {
 *         super("hillking", "");
 *         rules().noItemDrop(true);   // 本局禁止丢任何物品
 *     }
 * }
 * </pre>
 *
 * <p>⚠️ 规则只对**归属在本局的玩家**生效（{@link PlayerSessions#sessionOf(java.util.UUID)}），
 * 不再按"手上拿的是什么"或"在哪个世界"猜 —— 那正是重构前 I3 混乱的根因。
 */
public final class SessionRules {

    /** 局内不掉饥饿（默认开：与重构前 25 处逐字重复的处理器保持一致）。 */
    private boolean noHunger = true;

    /** 局内禁止丢弃物品（默认关：多数游戏只禁自己的道具，其余允许丢）。 */
    private boolean noItemDrop;

    public boolean noHunger() {
        return noHunger;
    }

    /** 本局是否禁止掉饥饿。返回 this，方便链式写在构造里。 */
    public SessionRules noHunger(boolean value) {
        this.noHunger = value;
        return this;
    }

    public boolean noItemDrop() {
        return noItemDrop;
    }

    /** 本局是否禁止丢弃物品。返回 this，方便链式写在构造里。 */
    public SessionRules noItemDrop(boolean value) {
        this.noItemDrop = value;
        return this;
    }

    /** 一行摘要：启动审计 / {@code /hub doctor} 里看得见"这局到底开没开规则"。 */
    public String describe() {
        return "不掉饥饿=" + (noHunger ? "开" : "关") + " 不掉落=" + (noItemDrop ? "开" : "关");
    }
}
