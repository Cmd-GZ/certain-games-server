package com.hub.region;

import org.bukkit.Location;

/**
 * 声明「地盘」的便利入口 —— 把 {@link RegionRegistry} 的样板代码收成一行。
 *
 * <p>对应 {@code docs/框架不变量.md} 的 <b>I2 区域归属单射</b>。
 * 典型用法（每个游戏在自己 manager 里加一个小方法即可）：
 *
 * <pre>
 * private void publishRegions() {
 *     Regions.begin("buildrun");                          // 先清掉上一轮的声明（重载友好）
 *     arenas.forEach((name, a) -&gt;
 *         Regions.box("buildrun", name, a.getPos1(), a.getPos2()));
 * }
 * </pre>
 *
 * <p><b>容错原则</b>：声明失败绝不能影响游戏加载 —— 还没建场（边界为空）是**正常状态**，
 * 静默跳过；只有真正异常（比如两点不在同一个世界）才记进 {@code Failures} 让人看见。
 */
public final class Regions {

    /**
     * 外接盒子在垂直方向上额外留的高度（格）。
     *
     * <p>圆形场地只给中心 + 半径时，垂直范围没有信息，这里按台面上下各留 12 格，
     * 覆盖跳跃、击飞、掉落到场地下方几格的情况。宁可偏大不可偏小。
     */
    public static final double DEFAULT_DISC_HEIGHT = 24;

    private Regions() {
    }

    /** 开始为一个游戏重新声明地盘：先撤销它上一轮的全部声明。 */
    public static void begin(String gameKey) {
        RegionRegistry.unregisterGame(gameKey);
    }

    /** 卸载/关闭时撤销声明。 */
    public static void end(String gameKey) {
        RegionRegistry.unregisterGame(gameKey);
    }

    /**
     * 声明一个用两个对角点界定的场地（AABB）。
     *
     * <p>任一点为空、世界未加载时静默跳过（= 这个场地还没建好）。
     * 两点跨世界属于真异常，记进 {@link com.hub.diagnostics.Failures}。
     */
    public static void box(String gameKey, String arenaName, Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return;
        }
        try {
            RegionRegistry.register(ArenaRegion.box(gameKey, arenaName, a, b));
        } catch (RuntimeException ex) {
            com.hub.diagnostics.Failures.recordAndWarn(gameKey + ".声明区域/" + arenaName, ex);
        }
    }

    /**
     * 声明一个圆形场地（转成外接 AABB）。
     *
     * @param center 圆心（世界取它），null 时静默跳过
     * @param radius 半径；小于 1 时静默跳过
     * @param height 垂直范围；小于 1 时用 {@link #DEFAULT_DISC_HEIGHT}
     */
    public static void disc(String gameKey, String arenaName, Location center, double radius, double height) {
        if (center == null || center.getWorld() == null || radius < 1) {
            return;
        }
        try {
            RegionRegistry.register(ArenaRegion.around(gameKey, arenaName, center, radius,
                    height < 1 ? DEFAULT_DISC_HEIGHT : height));
        } catch (RuntimeException ex) {
            com.hub.diagnostics.Failures.recordAndWarn(gameKey + ".声明区域/" + arenaName, ex);
        }
    }
}
