package com.hub.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 等待室自动开局倒计时（四个小游戏原来各抄一份 updateWaitCountdown/startWait/cancelWait）。
 * <p>
 * 规则（与原来完全一致）：
 * <ul>
 *   <li>队列里有 OP → 暂停自动倒计时，等 OP 手动开始；</li>
 *   <li>人数 &lt; min → 取消倒计时；</li>
 *   <li>人数 ≥ max → 3 秒后开始（已在跑的 30 秒倒计时会缩短为 3 秒）；</li>
 *   <li>人数达标但未满 → 30 秒倒计时；加人/退人都不重置正在跑的倒计时。</li>
 * </ul>
 * 每个场地一个实例（按场名区分）。
 */
public final class WaitCountdown {

    /** 满足最小人数后启动的自动开始倒计时（秒）。 */
    public static final int WAIT_SECONDS = 30;
    /** 人数满时立即开始所用的倒计时（秒）。 */
    public static final int FULL_SECONDS = 3;

    /** 插件侧需要提供的信息与回调。 */
    public interface Host {

        /** 当前等待中的玩家（用于发动作栏提示）。 */
        Collection<UUID> waitingPlayers();

        int minPlayers();

        int maxPlayers();

        /** 队列里是否有 OP（有则暂停自动开局）。 */
        boolean hasOpQueued();

        /** 每秒刷新提示（一般发动作栏）。 */
        void onTick(long secondsLeft);

        /** 提示性消息（开始倒计时 / OP 在队列中），可为空实现。 */
        void onMessage(String message);

        /** 倒计时归零 → 开始游戏（调用插件内部无权限校验的开局方法）。 */
        void onStart();

        // ---------- 目标流程（D21，上游已同意）的可选钩子 ----------
        // 全部**默认关闭**，所以现有 24 个游戏的行为一个字节都不变；
        // 游戏想切到 D21，只要覆盖下面这几个方法。

        /** 是否启用「主持人流程」（默认 false = 上游行为）。 */
        default boolean hostFlow() {
            return false;
        }

        /** 达标后的倒计时秒数（默认 30；D21 想用 300 就覆盖这里）。 */
        default int waitSeconds() {
            return WAIT_SECONDS;
        }

        /** 等待队列**按加入顺序**（主持人 = 第一个）。默认直接取 waitingPlayers。 */
        default List<UUID> waitingOrder() {
            return List.copyOf(waitingPlayers());
        }

        /**
         * 倒计时归零但**没有人开始**时触发（仅 hostFlow 生效）。
         *
         * <p>D21 的规定是：**把主持人踢出游戏**（而不是自动开局）。插件在这个回调里
         * 把人移出队列即可；倒计时不会自动重启，等剩下的人构成新的等待队列后再 update。
         */
        default void onHostTimeout(UUID host) {
        }

        /** 倒计时开始时的文案（默认与原实现一致，需要不同说法可以覆盖）。 */
        default String startMessage(int seconds) {
            return "&6" + seconds + "s 后自动开始…";
        }

        /** 队列里有 OP 时的文案（默认与原实现一致）。 */
        default String opQueuedMessage() {
            return "&7OP 在队列中，等待手动开始。";
        }
    }

    private final Plugin plugin;
    private final Host host;

    /** 街机派对接管时全局关掉自动开局（避免加入等待室就被游戏自己开走）。 */
    private static volatile boolean globallySuppressed;

    public static void setGloballySuppressed(boolean value) {
        globallySuppressed = value;
    }
    private BukkitTask task;
    private long endMs;

    public WaitCountdown(Plugin plugin, Host host) {
        this.plugin = plugin;
        this.host = host;
    }

    /** 人数/状态变化后调用：该启动就启动，该缩短就缩短，该取消就取消。 */
    public void update() {
        if (globallySuppressed) {
            cancel();
            return;
        }
        if (host.hasOpQueued()) {
            // 保留 OP 手动开始：每次刷新都提示一次（与各插件原来的写法一致）
            cancel();
            host.onMessage(host.opQueuedMessage());
            return;
        }
        int size = host.waitingPlayers().size();
        int min = host.minPlayers();
        int max = host.maxPlayers();
        if (size < min) {
            cancel();
        } else if (size >= max) {
            // 满员加速只在**上游流程**下成立。
            // 主持人流程下满员也**不能**把倒计时压到 3 秒：超时会踢掉主持人，
            // 等于"房间刚满 3 秒主持人就被踢"，主持人根本没机会开局（真实 bug：
            // duel max-players=2 时，第一个玩家（主持人）满员 3 秒后被踢走）。
            if (host.hostFlow()) {
                if (!isRunning()) {
                    start(Math.max(1, host.waitSeconds()));
                }
            } else if (!isRunning() || remainingSeconds() > FULL_SECONDS) {
                start(FULL_SECONDS);
            }
        } else if (!isRunning()) {
            start(host.hostFlow() ? Math.max(1, host.waitSeconds()) : WAIT_SECONDS);
        }
    }

    /** 启动/重启倒计时。 */
    public void start(int seconds) {
        if (globallySuppressed) {
            return;
        }
        cancel();
        endMs = System.currentTimeMillis() + seconds * 1000L;
        host.onMessage(host.startMessage(seconds));
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long left = remainingSeconds();
            if (left <= 0) {
                cancel();
                if (host.hostFlow()) {
                    // D21：倒计时结束还没人开始 -> 主持人被踢，**不自动开局**
                    UUID hostId = currentHost();
                    if (hostId != null) {
                        host.onHostTimeout(hostId);
                    }
                    return;
                }
                host.onStart();
                return;
            }
            host.onTick(left);
        }, 20L, 20L);
    }

    public void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        endMs = 0L;
    }

    /**
     * 当前主持人 = 等待队列里**最早加入**的那个人；队列空则 null。
     *
     * <p>依赖 {@link Host#waitingOrder()} 的顺序（各游戏用 LinkedHashSet 存队列，天然有序）。
     */
    /** 调试用：当前是否主持人流程。 */
    public boolean hostFlowForDebug() {
        return host.hostFlow();
    }

    public UUID currentHost() {
        List<UUID> order = host.waitingOrder();
        return order == null || order.isEmpty() ? null : order.get(0);
    }

    public boolean isRunning() {
        return task != null;
    }

    /** 剩余秒数（不小于 0）。 */
    public long remainingSeconds() {
        if (endMs <= 0L) {
            return 0L;
        }
        return Math.max(0L, (endMs - System.currentTimeMillis()) / 1000L);
    }
}
