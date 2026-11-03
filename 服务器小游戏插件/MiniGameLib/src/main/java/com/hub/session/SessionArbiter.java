package com.hub.session;

import com.hub.diagnostics.Failures;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 会话级的**裁决器** —— {@code docs/框架不变量.md} 的 I3（事件裁决唯一）在 C 类事件上的落点。
 *
 * <p>用法（在会话构造里）：
 * <pre>
 * final class HillKingSession extends GameSession {
 *     HillKingSession(...) {
 *         arbiter().on(EventKind.DAMAGE, ctx -&gt; ctx.cancel());          // 本局不掉血
 *         arbiter().on(EventKind.BLOCK_BREAK, ctx -&gt; ctx.cancel());     // 本局禁挖
 *     }
 * }
 * </pre>
 *
 * <p>约定（都是刻意的）：
 * <ul>
 *   <li><b>一种事件只许有一个处理器</b>：重复 {@link #on} 直接抛异常 —— I5 的精神，
 *       非法状态不要静默；</li>
 *   <li><b>不表态 = 放行</b>：处理器不调 {@link EventContext#cancel()} 就是允许。
 *       "默认拒绝"是目标态，不是现在的行为（翻转会一夜之间禁掉所有建造/PvP，见设计文档 §3.3）；</li>
 *   <li><b>生命周期跟着会话走</b>：会话 {@code end} 时框架自动清空，游戏不用手动注销
 *       （和 {@code ownTask} 一个套路）；</li>
 *   <li>处理器抛异常 → 记 {@link Failures}（R3），本次按"放行"处理，**不打断整局</li>
 * </ul>
 */
public final class SessionArbiter {

    private final GameSession owner;
    private final Map<EventKind, Consumer<EventContext>> handlers = new EnumMap<>(EventKind.class);

    SessionArbiter(GameSession owner) {
        this.owner = owner;
    }

    /** 声明"这类事件在本局归我裁决"。 */
    public SessionArbiter on(EventKind kind, Consumer<EventContext> handler) {
        if (kind == null || handler == null) {
            throw new IllegalArgumentException("SessionArbiter.on 需要 kind 和 handler");
        }
        if (handlers.containsKey(kind)) {
            throw new IllegalStateException("I3 违反：" + owner.gameKey() + " 对 " + kind
                    + " 声明了两次裁决 —— 一种事件只能有一个说了算的人");
        }
        handlers.put(kind, handler);
        return this;
    }

    /** 本局是否对这类事件表态。 */
    public boolean handles(EventKind kind) {
        return handlers.containsKey(kind);
    }

    /** 已声明的种类（doctor / 审计用）。 */
    public java.util.Set<EventKind> kinds() {
        return java.util.Collections.unmodifiableSet(handlers.keySet());
    }

    /** 框架内部派发入口。 */
    void fire(EventKind kind, EventContext ctx) {
        Consumer<EventContext> h = handlers.get(kind);
        if (h == null) {
            return;
        }
        try {
            h.accept(ctx);
        } catch (RuntimeException ex) {
            // 裁决器出错不能让一条伤害/一次破坏把整局拖崩；但要留响（R3）
            Failures.recordAndWarn(owner.gameKey() + ".裁决/" + kind, ex);
        }
    }

    void clear() {
        handlers.clear();
    }

    @Override
    public String toString() {
        return "arbiter[" + owner.gameKey() + ":" + handlers.keySet() + "]";
    }
}
