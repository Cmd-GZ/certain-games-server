package com.hub.diagnostics;

import org.bukkit.Bukkit;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「被吞掉的异常」登记处。
 *
 * <p>对应 {@code docs/框架不变量.md} 的 <b>R3 失败可见</b>：
 * 框架不得静默吞异常；被捕获的异常必须带来源标识记录，并能被 {@code /hub doctor} 查到。
 *
 * <p>为什么需要它：这个项目里"某个东西悄悄坏了 → 玩家看到游戏行为诡异 → 日志里什么都没有"
 * 是最难查的一类问题。既然有些地方确实**不该**因为失败而中断主流程
 * （例如"结算已经算完了，只是存盘失败"），那就更要留下痕迹。
 *
 * <p>用法：
 * <pre>
 *   try {
 *       ...
 *   } catch (IOException ex) {
 *       Failures.record("GuessTheBuild.保存战绩", ex);   // 记录但不抛出
 *   }
 * </pre>
 *
 * <p>线程安全：内部同步，可以在任意线程调用。
 */
public final class Failures {

    /** 最多保留多少条明细（只留最近的，避免撑爆内存）。 */
    private static final int MAX_RECENT = 50;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 一条被吞掉的异常。 */
    public record Entry(String source, long at, String type, String message) {
        /** 一行式展示。 */
        public String line() {
            return TIME.format(Instant.ofEpochMilli(at)) + " [" + source + "] "
                    + type + (message == null || message.isBlank() ? "" : ": " + message);
        }
    }

    private static final Deque<Entry> RECENT = new ArrayDeque<>();
    /** 来源 → 次数，保持首次出现的顺序，方便 doctor 里排序展示。 */
    private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();
    private static volatile long total;

    private Failures() {
    }

    /**
     * 记录一条被吞掉的异常。
     *
     * @param source 来源标识，建议写成 {@code 插件名.动作}，例如 {@code "JumpFill.保存玩家记录"}
     * @param t      被吞的异常，可为 null（只记"这里出过问题"）
     */
    public static synchronized void record(String source, Throwable t) {
        String type = t == null ? "(unknown)" : t.getClass().getSimpleName();
        String msg = t == null ? null : t.getMessage();
        Entry e = new Entry(source == null ? "?" : source, System.currentTimeMillis(), type, msg);
        RECENT.addLast(e);
        while (RECENT.size() > MAX_RECENT) {
            RECENT.removeFirst();
        }
        COUNTS.merge(e.source(), 1, Integer::sum);
        total++;
    }

    /** 被吞异常的总次数。 */
    public static long total() {
        return total;
    }

    public static synchronized boolean isEmpty() {
        return total == 0;
    }

    /** 最近的明细，按时间正序。 */
    public static synchronized List<Entry> recent() {
        return new ArrayList<>(RECENT);
    }

    /** 来源 → 累计次数（按次数降序）。 */
    public static synchronized List<Map.Entry<String, Integer>> counts() {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(COUNTS.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return list;
    }

    /** 最近一条的堆栈（如果有的话），用于 doctor 的 {@code 详细} 模式。 */
    public static String stackTrace(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /** 控制台兜底：除了登记之外也往服务器日志打一条 WARNING（不带堆栈，避免刷屏）。 */
    public static void recordAndWarn(String source, Throwable t) {
        record(source, t);
        try {
            Bukkit.getLogger().warning("[被吞的异常] " + source + " -> "
                    + (t == null ? "unknown" : t.getClass().getSimpleName() + ": " + t.getMessage()));
        } catch (Throwable ignored) {
            // 还没进服务器上下文（离线自检）：只登记
        }
    }

    /** 清空（测试/重载用）。 */
    public static synchronized void clear() {
        RECENT.clear();
        COUNTS.clear();
        total = 0;
    }
}
