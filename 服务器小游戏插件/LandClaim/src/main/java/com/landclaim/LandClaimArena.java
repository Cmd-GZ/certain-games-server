package com.landclaim;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 圆形场地：中心、半径、地面高度、等待点与多个出生点。 */
public final class LandClaimArena {

    private final LandClaimPlugin plugin;
    private String worldName = "world";
    private Location center;

    /** 供区域声明用（{@code docs/框架不变量.md} I2）。 */
    public Location getCenter() {
        return  com.hub.room.RoomBinding.apply("landclaim", center);
    }
    private int radius;

    /** 供区域声明用（{@code docs/框架不变量.md} I2）。 */
    public int getRadius() {
        return radius;
    }
    private Location lobby;
    private final List<Location> spawns = new ArrayList<>();

    public LandClaimArena(LandClaimPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        worldName = plugin.getConfig().getString("arena.world", "world");
        center = parse(plugin.getConfig().getString("arena.center", null));
        if (center != null && center.getWorld() != null) {
            worldName = com.hub.world.Worlds.tokenOf(center.getWorld());
        }
        radius = Math.max(0, plugin.getConfig().getInt("arena.radius", 0));
        lobby = parse(plugin.getConfig().getString("arena.lobby", null));
        spawns.clear();
        for (String raw : plugin.getConfig().getStringList("arena.spawns")) {
            Location loc = parse(raw);
            if (loc != null) {
                spawns.add(loc);
            }
        }
    }

    public void save() {
        // R2：写世界 token（房间实例 → tpl:模板键），别把临时房间名写进配置
        com.hub.world.Worlds.tokenOf(world());
        plugin.getConfig().set("arena.world",
                world() == null ? worldName : com.hub.world.Worlds.tokenOf(world()));
        plugin.getConfig().set("arena.center", format(center));
        plugin.getConfig().set("arena.radius", radius);
        plugin.getConfig().set("arena.lobby", format(lobby));
        List<String> out = new ArrayList<>();
        for (Location loc : spawns) {
            out.add(format(loc));
        }
        plugin.getConfig().set("arena.spawns", out);
        plugin.saveConfig();
    }

    public void setCenter(Location location) {
        center = location.getBlock().getLocation();
        worldName = com.hub.world.Worlds.tokenOf(location.getWorld());
    }

    public void setRadius(int radius) {
        this.radius = Math.max(0, Math.min(128, radius));
    }

    public void setLobby(Location location) {
        lobby = location.clone();
    }

    public void addSpawn(Location location) {
        Location loc = location.clone();
        loc.setX(loc.getBlockX() + 0.5);
        loc.setZ(loc.getBlockZ() + 0.5);
        spawns.add(loc);
    }

    public void clearSpawns() {
        spawns.clear();
    }

    /** 在圆周内侧自动生成 count 个出生点（脚站在地面上一格）。 */
    public int generateSpawns(int count) {
        if (center == null) {
            return 0;
        }
        int n = Math.max(1, Math.min(16, count));
        spawns.clear();
        double r = Math.max(1, radius - 2);
        World world = world();
        if (world == null) {
            return 0;
        }
        for (int i = 0; i < n; i++) {
            double angle = Math.PI * 2 * i / n;
            double x = center.getX() + 0.5 + Math.cos(angle) * r;
            double z = center.getZ() + 0.5 + Math.sin(angle) * r;
            double y = floorY() + 1;
            spawns.add(new Location(world, x, y, z, (float) Math.toDegrees(-angle) + 90f, 0f));
        }
        return n;
    }

    /** 圆形判定：按“方块中心”落在半径 + 0.5 的圆内，边缘不会只剩孤立的一格，轮廓更圆滑。 */
    private boolean inCircle(int dx, int dz) {
        double r = radius + 0.5;
        return dx * dx + dz * dz <= r * r;
    }

    /** 把圆形区域铺成草方块，并清空上方若干格。 */
    public void resetFloor() {
        if (!configured()) {
            return;
        }
        World world = world();
        if (world == null) {
            return;
        }
        Material floor = floorMaterial();
        int clear = Math.max(0, plugin.getConfig().getInt("clear-height", 3));
        int r = radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!inCircle(dx, dz)) {
                    continue;
                }
                int x = center.getBlockX() + dx;
                int z = center.getBlockZ() + dz;
                world.getBlockAt(x, floorY(), z).setType(floor, false);
                for (int dy = 1; dy <= clear; dy++) {
                    world.getBlockAt(x, floorY() + dy, z).setType(Material.AIR, false);
                }
            }
        }
    }

    public boolean configured() {
        return center != null && radius >= 1;
    }

    public boolean ready() {
        return configured() && !spawns.isEmpty();
    }

    public World world() {
        return center != null && center.getWorld() != null
                ? center.getWorld() : com.hub.world.Worlds.resolve(worldName);
    }

    public String worldName() {
        World world = world();
        return world == null ? worldName : world.getName();
    }

    public Location center() {
        return  com.hub.room.RoomBinding.apply("landclaim", center == null ? null : center.clone());
    }

    public int radius() {
        return radius;
    }

    public int floorY() {
        return center == null ? 0 : center.getBlockY();
    }

    public Location lobby() {
        return  com.hub.room.RoomBinding.apply("landclaim", lobby == null ? null : lobby.clone());
    }

    public List<Location> spawns() {
        return List.copyOf(spawns);
    }

    public int totalBlocks() {
        if (!configured()) {
            return 0;
        }
        int total = 0;
        int r = radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (inCircle(dx, dz)) {
                    total++;
                }
            }
        }
        return total;
    }

    /** 方块是否在圆形场地的地面上（y 必须等于地面高度）。 */
    public boolean isFloorBlock(Location location) {
        if (location == null || location.getWorld() == null || center == null) {
            return false;
        }
        if (!location.getWorld().getName().equals(worldName())) {
            return false;
        }
        if (location.getBlockY() != floorY()) {
            return false;
        }
        int dx = location.getBlockX() - center.getBlockX();
        int dz = location.getBlockZ() - center.getBlockZ();
        return inCircle(dx, dz);
    }

    public boolean isClaimedBlock(Block block) {
        Material type = block.getType();
        return type == Material.WHITE_WOOL || type == Material.ORANGE_WOOL
                || type == Material.MAGENTA_WOOL || type == Material.LIGHT_BLUE_WOOL
                || type == Material.YELLOW_WOOL || type == Material.LIME_WOOL
                || type == Material.PINK_WOOL || type == Material.GRAY_WOOL
                || type == Material.LIGHT_GRAY_WOOL || type == Material.CYAN_WOOL
                || type == Material.PURPLE_WOOL || type == Material.BLUE_WOOL
                || type == Material.BROWN_WOOL || type == Material.GREEN_WOOL
                || type == Material.RED_WOOL || type == Material.BLACK_WOOL;
    }

    public Material floorMaterial() {
        Material material = Material.matchMaterial(
                plugin.getConfig().getString("floor-block", "GRASS_BLOCK"), false);
        return material == null || !material.isBlock() ? Material.GRASS_BLOCK : material;
    }

    // ---------- 位置字符串 ----------

    public static String format(Location location) {
        if (location == null || location.getWorld() == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.2f,%.2f",
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    public static Location parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = parts.length > 4 ? Float.parseFloat(parts[4]) : 0f;
            float pitch = parts.length > 5 ? Float.parseFloat(parts[5]) : 0f;
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
