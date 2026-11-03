package com.hub.session;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * 交给游戏裁决的那一条事件 —— 只给**语义**，不给 Bukkit 事件对象。
 *
 * <p>为什么这样设计：C 类事件有 {@code EntityDamageEvent} / {@code EntityDamageByEntityEvent} /
 * {@code PlayerInteractEvent} … 一大堆；游戏真正需要的只是"谁打谁 / 谁动了哪块方块"。
 * 把语义抽出来之后，游戏侧的处理器不用再 `instanceof` 一堆分支，也不可能顺手把别人的
 * 事件对象存下来以后乱用。
 *
 * <p>确实需要原始事件时用 {@link #raw()}，但**每用一次都要在注释里写清为什么** ——
 * 那是这条路唯一的漏点（见 `docs/区域裁决设计.md` §5）。
 */
public final class EventContext {

    private final EventKind kind;
    /** 事件的"主语"：伤害的受害者、破坏/放置者、交互者、移动者。公共区事件里可能为 null。 */
    private final Player player;
    /** 伤害的施加者（其它种类为 null）。 */
    private final Player damager;
    /** 涉及的方块（伤害/移动为 null）。 */
    private final Block block;
    /** 事件发生位置（永远非 null，便于做区域判断）。 */
    private final Location where;
    private final Object raw;
    private boolean cancelled;

    EventContext(EventKind kind, Player player, Player damager, Block block, Location where, Object raw) {
        this.kind = kind;
        this.player = player;
        this.damager = damager;
        this.block = block;
        this.where = where;
        this.raw = raw;
    }

    public EventKind kind() {
        return kind;
    }

    /** 主语玩家（可能是 null：例如无主爆炸）。 */
    public Player player() {
        return player;
    }

    /** 伤害施加者（只有 {@link EventKind#DAMAGE} 可能有）。 */
    public Player damager() {
        return damager;
    }

    public Block block() {
        return block;
    }

    public Location where() {
        return where;
    }

    /** 原始 Bukkit 事件 —— 只在语义字段不够用时用，用完请注释理由。 */
    public Object raw() {
        return raw;
    }

    /** 是否"两个玩家之间"的伤害（PvP 判定最常用的一条）。 */
    public boolean isPlayerVersusPlayer() {
        return kind == EventKind.DAMAGE && player != null && damager != null && !player.equals(damager);
    }

    /** 我说了算：取消 / 拒绝这一条事件。 */
    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public String toString() {
        return kind + "(" + (player == null ? "-" : player.getName())
                + (damager == null ? "" : " ← " + damager.getName()) + ")";
    }
}
