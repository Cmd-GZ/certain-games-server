package com.hub.session;

/**
 * 需要"裁决"的事件种类 —— {@code docs/区域裁决设计.md} 里 C 类要收口的那些。
 *
 * <p>刻意用**语义名**而不是 Bukkit 事件类名：游戏只关心"伤害/破坏/放置/交互/移动"，
 * 不该被"是 {@code EntityDamageEvent} 还是 {@code EntityDamageByEntityEvent}"这种区分绑住。
 * 具体的 Bukkit 事件 → 种类的映射，由框架的 {@link RegionEvents} 一处负责。
 */
public enum EventKind {

    /** 任何来源的伤害（含实体互伤、爆炸、环境伤）。 */
    DAMAGE,

    /** 玩家破坏了方块。 */
    BLOCK_BREAK,

    /** 玩家放置了方块。 */
    BLOCK_PLACE,

    /** 玩家右键/左键（空气或方块）。 */
    INTERACT,

    /** 玩家移动（**每 tick 都触发**，实现里不要做重活）。 */
    MOVE
}
