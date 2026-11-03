package com.hub.region;

import com.hub.diagnostics.Failures;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区域注册表：收集各游戏声明的「地盘」，并在启动时做冲突检测。
 *
 * <p>对应 {@code docs/框架不变量.md} 的 <b>I2 区域归属单射</b>。设计要点：
 *
 * <ol>
 *   <li><b>加载期静态声明，不做运行时抢占</b>。游戏在 {@code onEnable} 里调用
 *       {@link #register(ArenaRegion)}；重复约定在加载那一刻就能发现，不会退化成
 *       "谁先抢到算谁的"（那正是重构前的优先级战争）。</li>
 *   <li><b>按 gameKey 覆盖，不累积</b>。同一个 {@code gameKey + arenaName + world}
 *       再次注册会替换旧的，所以 {@code /reload} 不会留下幽灵区域。</li>
 *   <li><b>冲突只报警，不阻断启动</b>（当前阶段）。因为存量 23 个游戏还没迁完，
 *       直接拒绝启动会把服务器搞挂。等全部迁完再改成"冲突即拒绝启动"。</li>
 * </ol>
 *
 * <p>线程安全：内部同步，但实际只在主线程加载/重载时调用。
 */
public final class RegionRegistry {

    /** 一条冲突。 */
    public record Conflict(Type type, ArenaRegion a, ArenaRegion b) {
        public enum Type {
            /** 两个不同游戏的地盘重叠 —— 严重，会互相踩。 */
            CROSS_GAME,
            /** 同一个游戏的两块场地重叠 —— 通常是建场时手滑，也值得看一眼。 */
            SAME_GAME
        }

        public String describe() {
            String head = type == Type.CROSS_GAME ? "&c跨游戏重叠" : "&e同游戏场地重叠";
            return head + "&7: &f" + a.describe() + " &7∩ &f" + b.describe();
        }
    }

    private static final Map<String, ArenaRegion> REGIONS = new LinkedHashMap<>();
    private static volatile boolean summaryPrinted;

    private RegionRegistry() {
    }

    private static String slotKey(ArenaRegion r) {
        return r.gameKey() + "|" + r.arenaName() + "|" + r.world();
    }

    /**
     * 登记一块区域。同一 {@code gameKey+arenaName+world} 会被覆盖（重载友好）。
     *
     * @return 之前是否已存在同槽位（true = 覆盖，false = 新增）
     */
    public static synchronized boolean register(ArenaRegion r) {
        if (r == null) {
            return false;
        }
        boolean replaced = REGIONS.put(slotKey(r), r) != null;
        summaryPrinted = false;   // 有变化，允许重新打印一次汇总
        return replaced;
    }

    /** 撤销某个游戏声明的全部区域（在它的 {@code onDisable} 里调用）。 */
    public static synchronized int unregisterGame(String gameKey) {
        if (gameKey == null) {
            return 0;
        }
        int before = REGIONS.size();
        REGIONS.keySet().removeIf(k -> k.startsWith(gameKey + "|"));
        summaryPrinted = false;
        return before - REGIONS.size();
    }

    /** 撤销某个游戏在某世界的一块场地。 */
    public static synchronized boolean unregister(String gameKey, String arenaName, String world) {
        boolean removed = REGIONS.remove(gameKey + "|" + (arenaName == null ? "" : arenaName)
                + "|" + world) != null;
        if (removed) {
            summaryPrinted = false;
        }
        return removed;
    }

    /** 当前全部区域（快照）。 */
    /**
     * 某个位置**归谁**（I2 单射：返回包含该位置的那块区域的 gameKey）。
     *
     * <p>C 类事件的"位置兜底"用：爆炸、投掷物、自然伤害里没有玩家可以查归属，
     * 只能看它发生在谁的场地里。没有任何区域包含该位置 → 返回 null（= 公共区）。
     *
     * <p>重叠时取**体积最小**的那块：大区域通常是"整个世界的粗粒度声明"，
     * 小区域才是"这块地具体归谁"，见 {@link #conflicts()} 的重叠检测。
     */
    public static synchronized String ownerAt(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        ArenaRegion best = null;
        for (ArenaRegion r : REGIONS.values()) {
            if (!r.contains(loc)) {
                continue;
            }
            if (best == null || r.volume() < best.volume()) {
                best = r;
            }
        }
        return best == null ? null : best.gameKey();
    }

    public static synchronized List<ArenaRegion> all() {
        return new ArrayList<>(REGIONS.values());
    }

    public static synchronized int size() {
        return REGIONS.size();
    }

    /** 已声明区域的游戏数（用于 doctor 里显示迁移进度）。 */
    public static synchronized int owners() {
        return (int) REGIONS.values().stream().map(ArenaRegion::gameKey).distinct().count();
    }

    /**
     * 两两冲突检测。
     *
     * <p>注意：同游戏的场地**允许**相邻、但重叠通常也是错，所以单独归为 {@code SAME_GAME}。
     */
    public static synchronized List<Conflict> conflicts() {
        List<ArenaRegion> list = new ArrayList<>(REGIONS.values());
        List<Conflict> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                ArenaRegion a = list.get(i), b = list.get(j);
                if (!a.intersects(b)) {
                    continue;
                }
                Conflict.Type type = a.gameKey().equals(b.gameKey())
                        ? Conflict.Type.SAME_GAME : Conflict.Type.CROSS_GAME;
                out.add(new Conflict(type, a, b));
            }
        }
        // 跨游戏排前面
        out.sort((x, y) -> x.type() == y.type() ? 0 : (x.type() == Conflict.Type.CROSS_GAME ? -1 : 1));
        return out;
    }

    /** 只返回严重的跨游戏冲突。 */
    public static List<Conflict> crossGameConflicts() {
        return conflicts().stream().filter(c -> c.type() == Conflict.Type.CROSS_GAME).toList();
    }

    /** 空行分隔的区域表（纯文本，给控制台/日志用）。 */
    public static synchronized List<String> tableLines() {
        List<String> out = new ArrayList<>();
        for (ArenaRegion r : REGIONS.values()) {
            out.add("  " + r.describe());
        }
        return out;
    }

    /**
     * 启动时打印一次汇总：声明了多少块、谁声明的、有没有冲突。
     *
     * <p>只在区域集合发生变化后打印一次，避免重载时刷屏。
     */
    public static void printSummaryOnce() {
        if (summaryPrinted) {
            return;
        }
        summaryPrinted = true;
        try {
            var log = Bukkit.getLogger();
            List<ArenaRegion> all = all();
            if (all.isEmpty()) {
                log.info("[区域] 还没有任何游戏声明自己的地盘（I2 未落地）——"
                        + "这意味着「玩 A 游戏影响 B 游戏」这类问题仍然只能靠人记。");
                return;
            }
            log.info("[区域] 已声明 " + all.size() + " 块区域，来自 " + owners() + " 个游戏：");
            for (String line : tableLines()) {
                log.info("[区域]" + line);
            }
            List<Conflict> conflicts = conflicts();
            if (conflicts.isEmpty()) {
                log.info("[区域] 未发现重叠 ✅");
                return;
            }
            for (Conflict c : conflicts) {
                if (c.type() == Conflict.Type.CROSS_GAME) {
                    log.warning("[区域] 跨游戏重叠（会互相踩）：" + c.a().describe() + " ∩ " + c.b().describe());
                } else {
                    log.warning("[区域] 同一游戏的场地重叠：" + c.a().describe() + " ∩ " + c.b().describe());
                }
            }
        } catch (Throwable ex) {
            Failures.record("RegionRegistry.printSummary", ex);
        }
    }

    /** 清空（重载/测试用）。 */
    public static synchronized void clear() {
        REGIONS.clear();
        summaryPrinted = false;
    }

    /** 是否有任何声明 —— 给 doctor 判断"这个机制有没有在用"。 */
    public static boolean active() {
        return size() > 0;
    }
}
