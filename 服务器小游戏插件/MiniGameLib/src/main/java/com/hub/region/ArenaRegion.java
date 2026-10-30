package com.hub.region;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * 一块「归某个游戏所有」的区域（轴对齐包围盒 AABB）。
 *
 * <p>对应 {@code docs/框架不变量.md} 的 <b>I2 区域归属单射</b>：
 * 任意时刻，每个区域至多属于一个对局；未被任何人认领的区域是**公共区**，归主城规则。
 *
 * <p>为什么只有 AABB、没有圆柱：这是「<b>地盘声明</b>」，不是「精确的玩法判定」。
 * 声明宁可偏大不可偏小 —— 偏大只是多管了一块空地，偏小会让别人溜进来而我不管，那才是 bug。
 * 游戏内部的判定逻辑（比如山丘之王按圆形算分）保持不变，只有声明统一成盒子。
 *
 * <p>坐标是<b>对角线两端</b>，两个点的顺序无所谓（构造时会自动取 min/max）。
 * 世界以**名字**记录，这样声明可以在世界尚未加载时给出（便于加载期自检）。
 *
 * @param gameKey   归属者的唯一标识，建议用插件名小写，例如 {@code "skypit"}
 * @param arenaName 场地名；单场游戏用 {@code ""} 或 {@code "default"}
 * @param world     世界名
 * @param x1        角 1 的 X（构造后保证小于等于 x2）
 * @param y1        角 1 的 Y
 * @param z1        角 1 的 Z
 * @param x2        角 2 的 X
 * @param y2        角 2 的 Y
 * @param z2        角 2 的 Z
 */
public record ArenaRegion(
        String gameKey,
        String arenaName,
        String world,
        double x1, double y1, double z1,
        double x2, double y2, double z2
) {

    /** 规范化：两个角取 min/max，空世界名直接拒绝。 */
    public ArenaRegion {
        if (gameKey == null || gameKey.isBlank()) {
            throw new IllegalArgumentException("ArenaRegion 必须有 gameKey");
        }
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("ArenaRegion 必须有 world（" + gameKey + "）");
        }
        arenaName = arenaName == null ? "" : arenaName;
        double ax1 = Math.min(x1, x2), ay1 = Math.min(y1, y2), az1 = Math.min(z1, z2);
        double ax2 = Math.max(x1, x2), ay2 = Math.max(y1, y2), az2 = Math.max(z1, z2);
        x1 = ax1;
        y1 = ay1;
        z1 = az1;
        x2 = ax2;
        y2 = ay2;
        z2 = az2;
    }

    /**
     * 用两个对角点（{@link Location}）声明一块区域。
     *
     * @throws IllegalArgumentException 两点不在同一世界时
     */
    public static ArenaRegion box(String gameKey, String arenaName, Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            throw new IllegalArgumentException("ArenaRegion.box 需要带世界的两个点（" + gameKey + "）");
        }
        if (!a.getWorld().equals(b.getWorld())) {
            throw new IllegalArgumentException("ArenaRegion.box 的两个点不在同一个世界（" + gameKey + "）");
        }
        return new ArenaRegion(gameKey, arenaName, a.getWorld().getName(),
                a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    /** 用中心 + 半径声明一个<b>外接</b> AABB（圆形场地用这个最省事；见类注释）。 */
    public static ArenaRegion around(String gameKey, String arenaName, Location center, double radius, double height) {
        if (center == null || center.getWorld() == null) {
            throw new IllegalArgumentException("ArenaRegion.around 需要带世界的中心点（" + gameKey + "）");
        }
        double r = Math.max(0.5, radius);
        double h = Math.max(0.5, height);
        return new ArenaRegion(gameKey, arenaName, center.getWorld().getName(),
                center.getX() - r, center.getY(), center.getZ() - r,
                center.getX() + r, center.getY() + h, center.getZ() + r);
    }

    /** 点是否在这块区域内。 */
    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null || !world.equals(loc.getWorld().getName())) {
            return false;
        }
        return loc.getX() >= x1 && loc.getX() <= x2
                && loc.getY() >= y1 && loc.getY() <= y2
                && loc.getZ() >= z1 && loc.getZ() <= z2;
    }

    /** 两块区域是否相交（同一世界且三个轴的区间都重叠）。 */
    public boolean intersects(ArenaRegion o) {
        if (o == null || !world.equals(o.world)) {
            return false;
        }
        return x1 <= o.x2 && o.x1 <= x2
                && y1 <= o.y2 && o.y1 <= y2
                && z1 <= o.z2 && o.z1 <= z2;
    }

    /** 是否同一个归属者的同一块场地（用于注册时去重/覆盖）。 */
    public boolean sameSlot(ArenaRegion o) {
        return o != null && gameKey.equals(o.gameKey) && arenaName.equals(o.arenaName) && world.equals(o.world);
    }

    /** 体积（格数），用于日志里判断"这块大得可疑"。 */
    public double volume() {
        return (x2 - x1) * (y2 - y1) * (z2 - z1);
    }

    /** 一行式描述，给启动日志和 {@code /hub doctor} 用。 */
    public String describe() {
        return gameKey + (arenaName.isEmpty() ? "" : "/" + arenaName)
                + " @" + world
                + " [" + fmt(x1) + "," + fmt(y1) + "," + fmt(z1)
                + " ~ " + fmt(x2) + "," + fmt(y2) + "," + fmt(z2) + "]";
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }

    /** 便捷：拿世界对象（世界没加载时返回 null）。 */
    public World bukkitWorld() {
        try {
            return org.bukkit.Bukkit.getWorld(world);
        } catch (Throwable ignored) {
            return null;   // 离线自检
        }
    }
}
