package com.grouprace;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 一个竖着的方块圆环。center 在圆心上，normal 是赛道前进方向（水平）。 */
public final class GroupRaceRing {

    private final int index;
    private final Location center;
    private final Vector normal;
    private final double radius;
    private final boolean finish;

    public GroupRaceRing(int index, Location center, Vector normal, double radius, boolean finish) {
        this.index = index;
        this.center = center.clone();
        Vector flat = normal.clone().setY(0.0);
        if (flat.lengthSquared() < 0.001) {
            flat = new Vector(0.0, 0.0, 1.0);
        }
        // 只允许沿 X 或 Z 一条轴，保证所有环都是同方向的竖直平面，不做立体倾斜
        if (Math.abs(flat.getX()) >= Math.abs(flat.getZ())) {
            flat = new Vector(Math.signum(flat.getX()), 0.0, 0.0);
        } else {
            flat = new Vector(0.0, 0.0, Math.signum(flat.getZ()));
        }
        this.normal = flat.normalize();
        this.radius = Math.max(1.0, radius);
        this.finish = finish;
    }

    public int index() {
        return index;
    }

    public Location center() {
        return center.clone();
    }

    public Vector normal() {
        return normal.clone();
    }

    public double radius() {
        return radius;
    }

    public boolean finish() {
        return finish;
    }

    /** 生成/刷新方块圆环。 */
    public void build(Material material) {
        for (Location loc : blockLocations()) {
            loc.getBlock().setType(material, false);
        }
    }

    /** 清除圆环方块。 */
    public void clear() {
        for (Location loc : blockLocations()) {
            loc.getBlock().setType(Material.AIR, false);
        }
    }

    /** 圆环上所有方块的位置：在圆环平面内按整数格扫描，生成一格厚、边缘平滑的圆。 */
    private List<Location> blockLocations() {
        List<Location> out = new ArrayList<>();
        Vector up = new Vector(0.0, 1.0, 0.0);
        Vector right = normal.clone().crossProduct(up);
        if (right.lengthSquared() < 0.001) {
            right = new Vector(1.0, 0.0, 0.0);
        }
        right.normalize();
        int max = (int) Math.ceil(radius) + 1;
        double inner = Math.max(0.0, radius - 0.6);
        double outer = radius + 0.6;
        Set<String> seen = new HashSet<>();
        for (int dx = -max; dx <= max; dx++) {
            for (int dy = -max; dy <= max; dy++) {
                double distance = Math.sqrt(dx * dx + dy * dy);
                if (distance < inner || distance > outer) {
                    continue;
                }
                Location loc = center.clone()
                        .add(right.clone().multiply(dx))
                        .add(up.clone().multiply(dy));
                String key = loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
                if (seen.add(key)) {
                    out.add(loc);
                }
            }
        }
        return out;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("world", center.getWorld() == null ? "" : com.hub.world.Worlds.tokenOf(center.getWorld()));
        map.put("x", center.getX());
        map.put("y", center.getY());
        map.put("z", center.getZ());
        map.put("nx", normal.getX());
        map.put("nz", normal.getZ());
        map.put("radius", radius);
        map.put("finish", finish);
        return map;
    }

    public static GroupRaceRing fromMap(int index, Map<?, ?> map) {
        if (map == null) {
            return null;
        }
        World world = com.hub.world.Worlds.resolve(String.valueOf(map.get("world")));
        if (world == null) {
            return null;
        }
        try {
            double x = number(map.get("x"));
            double y = number(map.get("y"));
            double z = number(map.get("z"));
            double nx = number(map.get("nx"));
            double nz = number(map.get("nz"));
            double radius = number(map.get("radius"));
            boolean finish = Boolean.parseBoolean(String.valueOf(map.get("finish")));
            return new GroupRaceRing(index, new Location(world, x, y, z),
                    new Vector(nx, 0.0, nz), radius, finish);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }
}
