package com.hub.diagnostics;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 主线程卡顿检测器 —— 回答"服务器为什么会突然卡一段时间"。
 *
 * <p>Bukkit/Paper 的 tick 循环是**单线程**的：主线程上任何一次阻塞（同步文件 IO、
 * 循环遍历所有实体、`createWorld`、写盘……）都会让所有玩家一起卡。
 * 上游代码里这类同步操作很多，而且卡完不留痕迹 —— 事后只能猜。
 *
 * <p>做法很土但有效：起一个每 tick 执行的任务，比较相邻两次执行的墙上时间。
 * 正常情况下约 50ms（1 tick）；差得越多说明中间被阻塞得越久。
 *
 * <p>不算 tick 的"正常慢"（TPS 掉），只抓**单次长阻塞**：默认超过 200ms 就记一条，
 * 超过 1s 记为严重。记录进 {@link Failures} 让 {@code /hub doctor} 能看到。
 */
public final class TickWatchdog {

    /** 超过这个毫秒数就认为是"一次异常阻塞"。 */
    private static final long WARN_MS = 200;
    /** 超过这个毫秒数记为严重。 */
    private static final long SEVERE_MS = 1000;
    private static final int KEEP = 20;

    /** 一次卡顿记录。{@code suspect} = 采样到的可疑帧（谁在卡主线程）。 */
    public record Stall(long at, long ms, String suspect) {
    }

    /** 采样间隔：卡顿期间每隔这么久抓一次主线程栈。 */
    private static final long SAMPLE_INTERVAL_MS = 50;
    /** 归因：{@code 类名.方法名 -> 命中次数}。 */
    private static final java.util.Map<String, Integer> OFFENDERS = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Deque<Stall> RECENT = new ArrayDeque<>();
    private static long worstMs;
    private static long totalStalls;
    private static BukkitTask task;
    private static volatile long lastTickNanos;

    private TickWatchdog() {
    }

    /** 在 MiniGameLib 的 onEnable 里调。 */
    private static volatile Thread mainThread;
    private static volatile boolean sampling;

    /** 在 MiniGameLib 的 onEnable 里调（此时就在主线程，正好记下它）。 */
    public static void start(JavaPlugin plugin) {
        stop();
        mainThread = Thread.currentThread();
        lastTickNanos = System.nanoTime();
        task = Bukkit.getScheduler().runTaskTimer(plugin, TickWatchdog::tick, 1L, 1L);
        startSampler();
    }

    /**
     * 起一个守护线程，在**主线程被卡住时**抓它的调用栈。
     *
     * <p>这是"谁卡了主线程"的唯一可靠答案：主线程自己被阻塞时无法自采样，
     * 只能让另一个线程去问它"你现在在哪一行"。
     */
    private static void startSampler() {
        if (sampling) {
            return;
        }
        sampling = true;
        Thread t = new Thread(() -> {
            while (sampling) {
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MS);
                } catch (InterruptedException ex) {
                    return;
                }
                Thread target = mainThread;
                if (target == null) {
                    continue;
                }
                long stuckMs = (System.nanoTime() - lastTickNanos) / 1_000_000L;
                if (stuckMs <= WARN_MS) {
                    continue;   // 主线程转得正常，不采样
                }
                StackTraceElement[] stack = target.getStackTrace();
                String suspect = traceLine(stack, 3);   // 记 3 层，便于判断在等什么
                if (suspect != null) {
                    OFFENDERS.merge(suspect, 1, Integer::sum);
                }
            }
        }, "minigame-stall-sampler");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 从栈里挑出"最像罪魁"的那一帧：跳过 JVM / Paper / NMS 的实现细节，
     * 取第一个属于业务代码的帧（插件、公共库、HubCore 都算）。
     */
    private static String attribute(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) {
            return null;
        }
        for (StackTraceElement e : stack) {
            String c = e.getClassName();
            if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.")
                    || c.startsWith("com.sun.") || c.startsWith("net.minecraft.")
                    || c.startsWith("org.bukkit.craftbukkit")
                    || c.startsWith("io.papermc.paper")
                    || c.startsWith("ca.spottedleaf")
                    || c.startsWith("com.hub.diagnostics.TickWatchdog")
                    || c.startsWith("org.bukkit.scheduler")) {
                continue;
            }
            return c + "#" + e.getMethodName() + ":" + e.getLineNumber();
        }
        StackTraceElement e = stack[0];
        return e.getClassName() + "#" + e.getMethodName() + ":" + e.getLineNumber();
    }

    /** 调试点：把栈里前 N 个业务帧拼成一行（排查"到底卡在哪"时用）。 */
    static String traceLine(StackTraceElement[] stack, int limit) {
        if (stack == null) {
            return "?";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : stack) {
            String c = e.getClassName();
            if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.")
                    || c.startsWith("com.sun.") || c.startsWith("net.minecraft.")
                    || c.startsWith("org.bukkit.craftbukkit")
                    || c.startsWith("io.papermc.paper") || c.startsWith("ca.spottedleaf")
                    || c.startsWith("org.bukkit.scheduler")) {
                continue;
            }
            if (n++ > 0) {
                sb.append(" ← ");
            }
            sb.append(c).append('#').append(e.getMethodName()).append(':').append(e.getLineNumber());
            if (n >= limit) {
                break;
            }
        }
        if (sb.length() == 0) {
            // 整条栈都在 JVM/Paper 内部（说明不是插件代码在卡，而是框架自己在等）
            // → 把原始前三帧打出来，好判断到底是哪一段（区块 IO？世界加载？）
            StringBuilder raw = new StringBuilder("[框架] ");   // 8 帧，够看到是谁调的 .get()/.join()
            for (int i = 0; i < Math.min(8, stack.length); i++) {
                if (i > 0) {
                    raw.append(" ← ");
                }
                raw.append(stack[i].getClassName()).append('#').append(stack[i].getMethodName())
                        .append(':').append(stack[i].getLineNumber());
            }
            return raw.toString();
        }
        return sb.toString();
    }

    /** 归因结果（命中次数降序）。 */
    public static java.util.List<java.util.Map.Entry<String, Integer>> offenders() {
        java.util.List<java.util.Map.Entry<String, Integer>> list =
                new java.util.ArrayList<>(OFFENDERS.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return list;
    }

    public static void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        sampling = false;
    }

    private static void tick() {
        long now = System.nanoTime();
        long deltaMs = (now - lastTickNanos) / 1_000_000L;
        lastTickNanos = now;
        if (deltaMs <= WARN_MS) {
            return;
        }
        synchronized (RECENT) {
            totalStalls++;
            if (deltaMs > worstMs) {
                worstMs = deltaMs;
            }
            RECENT.addLast(new Stall(System.currentTimeMillis(), deltaMs, latestSuspect()));
            while (RECENT.size() > KEEP) {
                RECENT.removeFirst();
            }
        }
        // 记进统一的失败登记处，doctor 里能直接看到
        Failures.record("主线程卡顿",
                new IllegalStateException(deltaMs + "ms（" + (deltaMs >= SEVERE_MS ? "严重" : "警告") + "）"));
        try {
            if (deltaMs >= SEVERE_MS) {
                Bukkit.getLogger().warning("[主线程卡顿] 单次阻塞 " + deltaMs + "ms"
                        + "（正常一 tick 约 50ms；查一下是不是有同步文件 IO / 重循环）");
            }
        } catch (Throwable ignored) {
            // 还没进服务器上下文
        }
    }

    /** 最近一次采样到的可疑帧（可能为 null，例如卡在纯 JVM 代码里）。 */
    private static String latestSuspect() {
        var list = offenders();
        return list.isEmpty() ? null : list.get(0).getKey();
    }

    public static long worstMs() {
        return worstMs;
    }

    public static long totalStalls() {
        return totalStalls;
    }

    /** 最近的卡顿（时间正序）。 */
    public static List<Stall> recent() {
        synchronized (RECENT) {
            return new ArrayList<>(RECENT);
        }
    }

    public static void reset() {
        synchronized (RECENT) {
            RECENT.clear();
            OFFENDERS.clear();
            worstMs = 0;
            totalStalls = 0;
        }
    }
}
