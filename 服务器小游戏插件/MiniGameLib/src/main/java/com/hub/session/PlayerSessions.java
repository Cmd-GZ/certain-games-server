package com.hub.session;

import com.hub.diagnostics.Failures;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家归属链 —— {@code docs/框架不变量.md} 的 <b>I1（玩家归属是一条链）</b>。
 *
 * <p>核心约定：<b>任意时刻，一个玩家的归属构成一条唯一的祖先链</b>
 * （长度 0 = 在大厅；1 = 单开一局；2 = 派对里的某一局）。
 * **不允许出现并列** —— 玩家不可能同时属于两个互不相干的对局。
 *
 * <pre>
 * 单开：  [hillking#41]             ← 链长 1
 * 派对：  [party#3] ⊃ [hillking#41]  ← 链长 2
 * 大厅：  []                        ← 链长 0
 * </pre>
 *
 * <p>这解决了重构前最普遍的一类问题：每个插件各自维护一个 {@code Set<UUID>}，
 * 同一个人可以同时躺在 N 个插件的集合里，于是"回主城后仍被算回下一局"、
 * "跨世界比较距离抛异常"、"进服刷 N 行你不在任何场中"。
 */
public final class PlayerSessions {

    /** 玩家 → 他所在的**叶子**会话。没有条目 = 在大厅。 */
    private static final Map<UUID, GameSession> LEAF = new LinkedHashMap<>();

    private PlayerSessions() {
    }

    /**
     * 把玩家挂到某个会话上。
     *
     * @throws IllegalStateException 违反 I1 时（已有并列归属）
     */
    public static void attach(Player p, GameSession session) {
        if (p == null || session == null) {
            return;
        }
        UUID id = p.getUniqueId();
        GameSession current = LEAF.get(id);
        if (current == null || !current.isActive()) {
            LEAF.put(id, session);
            return;
        }
        if (current == session) {
            return;                                   // 重复加入同一会话：幂等
        }
        if (isAncestorOf(current, session)) {
            LEAF.put(id, session);                    // 从父对局下钻到子对局：合法
            return;
        }
        if (isAncestorOf(session, current)) {
            return;                                   // 想挂到祖先上：已经在里面了
        }
        throw new IllegalStateException("I1 违反：玩家 " + p.getName()
                + " 已属于 " + current.describe() + "，不能再属于并列的 " + session.describe());
    }

    /**
     * 从归属链上摘下。reason 只用于记录（是否给玩家提示由调用方决定）。
     */
    public static void detach(UUID id, EndReason reason) {
        if (id == null) {
            return;
        }
        LEAF.remove(id);
    }

    /** 玩家当前所在的叶子会话；在大厅返回 null。 */
    public static GameSession sessionOf(UUID id) {
        if (id == null) {
            return null;
        }
        GameSession s = LEAF.get(id);
        return s != null && s.isActive() ? s : null;
    }

    /** 根 → 叶的完整链（长度 0/1/2...）。 */
    public static List<GameSession> chainOf(UUID id) {
        List<GameSession> chain = new ArrayList<>();
        GameSession cur = sessionOf(id);
        while (cur != null) {
            chain.add(0, cur);
            cur = cur.parent();
        }
        return chain;
    }

    /** 玩家是否在大厅（不属于任何对局）。 */
    public static boolean isInLobby(UUID id) {
        return sessionOf(id) == null;
    }

    /**
     * 玩家主动回大厅（{@code /hub}、右键回主城道具）。
     *
     * <p>沿链**从叶到根**逐个 {@code leave}；空了的会话由 {@link GameSession#leave} 负责
     * {@code end(ABORT)}。这一步替代了重构前的 {@code notifyLobbyHandlers} 广播 ——
     * 退出是框架动作，不再需要各插件"猜"这算不算退出。
     */
    public static void quitToLobby(Player p) {
        if (p == null) {
            return;
        }
        UUID id = p.getUniqueId();
        List<GameSession> chain = chainOf(id);
        for (int i = chain.size() - 1; i >= 0; i--) {
            GameSession s = chain.get(i);
            try {
                s.leave(p);
            } catch (RuntimeException ex) {
                Failures.recordAndWarn("PlayerSessions.退出/" + s.gameKey(), ex);
            }
        }
        LEAF.remove(id);
    }

    /** 一致性自检：返回发现的违规描述，空列表 = 全链路正常（冒烟台 / doctor 用）。 */
    public static List<String> audit() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<UUID, GameSession> e : new LinkedHashMap<>(LEAF).entrySet()) {
            GameSession s = e.getValue();
            if (s == null) {
                continue;
            }
            if (!s.isActive()) {
                problems.add("玩家 " + e.getKey() + " 仍挂在已结束的会话 " + s.describe());
            }
            if (!s.has(e.getKey())) {
                problems.add("玩家 " + e.getKey() + " 的归属会话 " + s.describe() + " 里没有这个人");
            }
            // 链上不能有并列（结构上由 attach 保证，这里再核一遍父链是否闭合）
            int depth = 0;
            for (GameSession cur = s; cur != null; cur = cur.parent()) {
                if (++depth > 8) {
                    problems.add("玩家 " + e.getKey() + " 的归属链过深（可能是环）");
                    break;
                }
            }
        }
        return problems;
    }

    /** 当前有归属的玩家数。 */
    public static int size() {
        return LEAF.size();
    }

    /** 清空（关服用）。 */
    public static void clear() {
        LEAF.clear();
    }

    private static boolean isAncestorOf(GameSession maybeAncestor, GameSession node) {
        for (GameSession cur = node == null ? null : node.parent(); cur != null; cur = cur.parent()) {
            if (cur == maybeAncestor) {
                return true;
            }
        }
        return false;
    }
}
