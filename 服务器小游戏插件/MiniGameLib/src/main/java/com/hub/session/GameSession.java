package com.hub.session;

import com.hub.diagnostics.Failures;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 一局对局的基类 —— {@code docs/框架不变量.md} 的 <b>I4（生命周期唯一出口）</b> 与
 * <b>I5（状态转移封闭）</b> 的落地。
 *
 * <p>设计要点（都是刻意的）：
 *
 * <ol>
 *   <li>{@link #end(EndReason)} 是 <b>final</b>：子类没法"忘记"取消任务、忘记摘归属链。
 *       子类只能实现 {@link #onStart()} 与 {@link #onEnd(EndReason)}。</li>
 *   <li>{@code end} <b>幂等</b>：第二次调用只记一条警告，不重复结算（"时间到"和"最后一人退出"
 *       可能同时触发，抛异常会让玩家看到报错）。</li>
 *   <li>计时器/倒计时一律经 {@link #ownTask(BukkitTask)} 登记，{@code end} 时统一取消。
 *       对应 {@code nonebot-plugin-shitbot/docs/命令框架.md} 3.5 的 {@code unlock()} 取消 {@code _inner_tasks}。</li>
 *   <li>状态私有，只能经 {@link #enterWaiting()} / {@link #enterRunning()} / {@link #enterEnding()} 推进，
 *       非法转移抛异常而不是静默。</li>
 * </ol>
 *
 * <p>典型用法：
 * <pre>
 * final class HillKingSession extends GameSession {
 *     HillKingSession(...) { super("hillking", ""); }
 *     &#64;Override protected void onStart() { ownTask(scheduler.runTaskTimer(plugin, this::tick, 0L, 20L)); }
 *     &#64;Override protected void onEnd(EndReason reason) { if (reason.recordsScore()) award(); }
 * }
 * </pre>
 */
public abstract class GameSession {

    private final String gameKey;
    private final String arenaName;
    private final GameSession parent;
    private final List<GameSession> children = new ArrayList<>();
    private final Set<UUID> players = new LinkedHashSet<>();
    private final Set<BukkitTask> tasks = new LinkedHashSet<>();
    private final long createdAt = System.currentTimeMillis();

    private SessionState state = SessionState.CREATED;
    private EndReason endReason;
    private long endedAt;

    protected GameSession(String gameKey, String arenaName) {
        if (gameKey == null || gameKey.isBlank()) {
            throw new IllegalArgumentException("GameSession 必须有 gameKey");
        }
        this.gameKey = gameKey;
        this.arenaName = arenaName == null ? "" : arenaName;
        this.parent = null;
        Sessions.track(this);
    }

    /** 创建子对局（框架内部用，负责挂 parent）。 */
    protected GameSession(String gameKey, String arenaName, GameSession parent) {
        if (gameKey == null || gameKey.isBlank()) {
            throw new IllegalArgumentException("GameSession 必须有 gameKey");
        }
        this.gameKey = gameKey;
        this.arenaName = arenaName == null ? "" : arenaName;
        this.parent = parent;
        Sessions.track(this);
    }

    // ---------- 身份 / 查询 ----------

    public final String gameKey() {
        return gameKey;
    }

    public final String arenaName() {
        return arenaName;
    }

    public final GameSession parent() {
        return parent;
    }

    public final List<GameSession> children() {
        return Collections.unmodifiableList(children);
    }

    public final SessionState state() {
        return state;
    }

    /** 还在进行（未结束）。 */
    public final boolean isActive() {
        return state != SessionState.ENDED;
    }

    /** 结束原因；还没结束时为 null。 */
    public final EndReason endReason() {
        return endReason;
    }

    public final long createdAt() {
        return createdAt;
    }

    public final long endedAt() {
        return endedAt;
    }

    /** 参与者（只读）。 */
    public final Set<UUID> players() {
        return Collections.unmodifiableSet(players);
    }

    /** 给人看的一行描述：{@code hillking @world_mini_game [RUNNING] 3 人}。 */
    public String describe() {
        return gameKey + (arenaName.isEmpty() ? "" : "/" + arenaName)
                + " [" + state + "] " + players.size() + " 人"
                + (endReason == null ? "" : " 结束原因=" + endReason);
    }

    // ---------- 状态推进（受转移表约束）----------

    /** CREATED → WAITING。 */
    protected final void enterWaiting() {
        transition(SessionState.WAITING);
    }

    /** WAITING → RUNNING，并调用 {@link #onStart()}。 */
    protected final void enterRunning() {
        transition(SessionState.RUNNING);
        try {
            onStart();
        } catch (RuntimeException ex) {
            Failures.recordAndWarn(gameKey + ".onStart/" + arenaName, ex);
            end(EndReason.ERROR);
        }
    }

    /** RUNNING → ENDING。 */
    protected final void enterEnding() {
        transition(SessionState.ENDING);
    }

    /** 转移表：不在表里的转移抛异常（I5）。 */
    private void transition(SessionState to) {
        if (!allowed(state, to)) {
            throw new IllegalStateException("非法状态转移：" + gameKey + " " + state + " -> " + to);
        }
        state = to;
    }

    private static boolean allowed(SessionState from, SessionState to) {
        return switch (from) {
            case CREATED -> to == SessionState.WAITING;
            case WAITING -> to == SessionState.RUNNING;
            case RUNNING -> to == SessionState.ENDING;
            case ENDING -> to == SessionState.ENDED;
            case ENDED -> false;   // 终态不可再变；end() 自己处理幂等
        };
    }

    // ---------- 生命周期：唯一出口 ----------

    /**
     * 结束这个对局。**幂等**：已结束的再调只记一条警告。
     *
     * <p>收场顺序固定（不允许子类改变）：
     * <ol>
     *   <li>递归 end 所有子对局</li>
     *   <li>取消本会话持有的全部 {@link BukkitTask}</li>
     *   <li>把所有参与者从归属链上摘下</li>
     *   <li>调 {@link #onEnd(EndReason)}</li>
     *   <li>置 ENDED，并通知父对局</li>
     * </ol>
     */
    public final void end(EndReason reason) {
        EndReason why = reason == null ? EndReason.ABORT : reason;
        if (state == SessionState.ENDED) {
            Failures.record(gameKey + ".重复end/" + arenaName,
                    new IllegalStateException("已经结束（" + endReason + "），忽略本次 " + why));
            return;
        }
        this.endReason = why;

        // 1) 父死子必死
        for (GameSession child : new ArrayList<>(children)) {
            try {
                child.end(why);
            } catch (RuntimeException ex) {
                Failures.recordAndWarn(gameKey + ".结束子对局/" + child.gameKey(), ex);
            }
        }

        // 2) 取消自己持有的任务
        cancelOwnedTasks();

        // 3) 摘归属链
        for (UUID id : new ArrayList<>(players)) {
            PlayerSessions.detach(id, why);
        }
        players.clear();

        // 4) 子类收场
        if (state != SessionState.ENDING && allowed(state, SessionState.ENDING)) {
            state = SessionState.ENDING;
        }
        try {
            onEnd(why);
        } catch (RuntimeException ex) {
            Failures.recordAndWarn(gameKey + ".onEnd/" + arenaName, ex);
        }

        // 5) 终态 + 通知父
        state = SessionState.ENDED;
        endedAt = System.currentTimeMillis();
        if (parent != null) {
            try {
                parent.onChildEnded(this, why);
            } catch (RuntimeException ex) {
                Failures.recordAndWarn(gameKey + ".通知父对局/" + arenaName, ex);
            }
        }
    }

    /** 进入 RUNNING 之前调一次（发装备、传送、起计时器）。实现里应当用 {@link #ownTask} 登记任务。 */
    protected abstract void onStart();

    /**
     * 收场。**必须按 reason 分流**：{@code reason.recordsScore()} 为 true 时才记成绩。
     * 这里不要再碰 {@code state}，也不要再调 {@link #end}。
     */
    protected abstract void onEnd(EndReason reason);

    /** 子对局结束时回调（默认什么都不做；编排器覆盖它来推进下一轮）。 */
    protected void onChildEnded(GameSession child, EndReason reason) {
    }

    // ---------- 参与者 ----------

    /** other 是否与本会话存在父子关系（派对嵌套是允许的，不算冲突）。 */
    private boolean isAncestorOrDescendant(GameSession other) {
        for (GameSession p = this.parent; p != null; p = p.parent) {
            if (p == other) {
                return true;
            }
        }
        for (GameSession p = other.parent; p != null; p = p.parent) {
            if (p == this) {
                return true;
            }
        }
        return false;
    }

    /**
     * 加入。会把玩家挂到归属链；冲突时不把异常抛给玩家看，而是记 {@code Failures} 并 return false。
     *
     * @return true = 成功加入；false = 状态不允许 / 归属冲突
     */
    public final boolean join(Player p) {
        if (p == null) {
            return false;
        }
        if (state.isClosed() || state == SessionState.CREATED) {
            return false;
        }
        // 玩家已经在**另一个并列对局**里 —— 这是正常的玩家操作（在 GUI 里点了别的游戏），
        // 不是程序错误，所以**不记 Failures**（那是给真异常用的，混进去会把 doctor 刷满、
        // 让真正的问题被淹掉）。这里留一行 INFO 足够定位。
        List<GameSession> chain = PlayerSessions.chainOf(p.getUniqueId());
        for (GameSession other : chain) {
            if (other != this && !isAncestorOrDescendant(other)) {
                org.bukkit.Bukkit.getLogger().info("[" + gameKey + "] " + p.getName()
                        + " 已在 " + other.gameKey() + " 里（" + other.state() + "），本次加入被拒绝");
                return false;
            }
        }
        try {
            PlayerSessions.attach(p, this);
        } catch (IllegalStateException ex) {
            // 走到这里说明上面那段没拦住 —— 真的不一致了，才值得记 Failures
            Failures.recordAndWarn(gameKey + ".加入归属冲突", ex);
            return false;
        }
        players.add(p.getUniqueId());
        return true;
    }

    /**
     * 个人退出（不影响其他人）。从归属链摘下；**最后一个人走了就 {@code end(ABORT)}**。
     */
    public final void leave(Player p) {
        if (p == null) {
            return;
        }
        UUID id = p.getUniqueId();
        if (!players.remove(id)) {
            return;
        }
        PlayerSessions.detach(id, EndReason.PLAYER_QUIT);
        if (players.isEmpty() && !state.isClosed()) {
            end(EndReason.ABORT);
        }
    }

    /** 参与者是否在本局（含等待室阶段）里。 */
    public final boolean has(UUID id) {
        return id != null && players.contains(id);
    }

    // ---------- 资源持有 ----------

    /** 登记一个任务：{@code end} 时统一取消。游戏里别再自己存 task 字段。 */
    protected final BukkitTask ownTask(BukkitTask task) {
        if (task != null) {
            tasks.add(task);
        }
        return task;
    }

    /** 取消本会话登记的全部任务（{@code end} 会自动调；一般不用手动调）。 */
    protected final void cancelOwnedTasks() {
        for (BukkitTask t : new ArrayList<>(tasks)) {
            try {
                t.cancel();
            } catch (RuntimeException ex) {
                Failures.record(gameKey + ".取消任务", ex);
            }
        }
        tasks.clear();
    }

    // ---------- 子对局 ----------

    /** 登记一个子对局（不负责创建，由编排器 new 出来后调这里挂上）。 */
    protected final void adopt(GameSession child) {
        if (child != null && !children.contains(child)) {
            children.add(child);
        }
    }

    // ---------- 供 doctor / 冒烟台 ----------

    /** 当前所有活动会话（静态快照）。 */
    public static List<GameSession> activeSessions() {
        return Sessions.active();
    }

    @Override
    public String toString() {
        return "GameSession{" + describe() + "}";
    }
}
