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

    /** 一次卡顿记录。 */
    public record Stall(long at, long ms) {
    }

    private static final Deque<Stall> RECENT = new ArrayDeque<>();
    private static long worstMs;
    private static long totalStalls;
    private static BukkitTask task;
    private static volatile long lastTickNanos;

    private TickWatchdog() {
    }

    /** 在 MiniGameLib 的 onEnable 里调。 */
    public static void start(JavaPlugin plugin) {
        stop();
        lastTickNanos = System.nanoTime();
        task = Bukkit.getScheduler().runTaskTimer(plugin, TickWatchdog::tick, 1L, 1L);
    }

    public static void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
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
            RECENT.addLast(new Stall(System.currentTimeMillis(), deltaMs));
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
            worstMs = 0;
            totalStalls = 0;
        }
    }
}
