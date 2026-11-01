package com.animalslaughter;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** 圆形场地：圆心 + 半径 + 等待点 + 出生点。 */
public final class AnimalSlaughterArena {

    private final JavaPlugin plugin;
    private Location center;

    /** 供区域声明用（{@code docs/框架不变量.md} I2）。 */
    public Location getCenter() {
        return center;
    }
    private int radius = 16;
    private Location lobby;
    private final List<Location> spawns = new ArrayList<>();

    public AnimalSlaughterArena(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration cfg = plugin.getConfig();
        center = parse(cfg.getString("arena.center"));
        radius = Math.max(4, Math.min(64, cfg.getInt("arena.radius", 16)));
        lobby = parse(cfg.getString("arena.lobby"));
        spawns.clear();
        for (String raw : cfg.getStringList("arena.spawns")) {
            Location loc = parse(raw);
            if (loc != null) {
                spawns.add(loc);
            }
        }
    }

    public void save() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("arena.center", encode(center));
        cfg.set("arena.radius", radius);
        cfg.set("arena.lobby", encode(lobby));
        List<String> encoded = new ArrayList<>();
        for (Location spawn : spawns) {
            encoded.add(encode(spawn));
        }
        cfg.set("arena.spawns", encoded);
        plugin.saveConfig();
    }

    public boolean hasCircle() {
        return center != null && center.getWorld() != null;
    }

    public boolean isReady() {
        return hasCircle() && lobby != null && !spawns.isEmpty();
    }

    public String missing() {
        List<String> missing = new ArrayList<>();
        if (center == null) {
            missing.add("center（/animal setarena <半径>）");
        }
        if (lobby == null) {
            missing.add("lobby");
        }
        if (spawns.isEmpty()) {
            missing.add("spawns");
        }
        return missing.isEmpty() ? "无" : String.join("、", missing);
    }

    /** 水平方向是否在圆内（不看高度）。 */
    public boolean containsXZ(Location loc) {
        if (!hasCircle() || loc == null || loc.getWorld() == null || !loc.getWorld().equals(center.getWorld())) {
            return false;
        }
        double dx = loc.getX() - (center.getBlockX() + 0.5);
        double dz = loc.getZ() - (center.getBlockZ() + 0.5);
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    /** 保护范围：圆内、且高度在圆心上下各 24 格内。 */
    public boolean contains(Location loc) {
        if (!containsXZ(loc)) {
            return false;
        }
        double dy = loc.getY() - center.getY();
        return dy >= -24.0 && dy <= 24.0;
    }

    public Location randomPoint(Random random) {
        if (!hasCircle()) {
            return null;
        }
        double angle = random.nextDouble() * Math.PI * 2.0;
        double dist = Math.sqrt(random.nextDouble()) * (radius - 1.0);
        return new Location(center.getWorld(),
                center.getBlockX() + 0.5 + Math.cos(angle) * dist,
                center.getY(),
                center.getBlockZ() + 0.5 + Math.sin(angle) * dist);
    }

    /** 取某一列的地面站位（最高非空气/非液体方块的上方）；找不到返回 null。 */
    public Location groundAt(int x, int z) {
        if (!hasCircle() || center.getWorld() == null) {
            return null;
        }
        World world = center.getWorld();
        int top = Math.min(world.getMaxHeight() - 2, center.getBlockY() + 8);
        int bottom = Math.max(world.getMinHeight(), center.getBlockY() - 12);
        for (int y = top; y >= bottom; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type.isAir() || isLiquid(type)) {
                continue;
            }
            return new Location(world, x + 0.5, y + 1.0, z + 0.5);
        }
        return null;
    }

    /**
     * 造一块一格厚的草方块圆形平台（圆心脚下那一层），并清掉平台上方 6 格。
     *
     * @return 铺了多少格
     */
    public int buildPlatform() {
        if (!hasCircle() || center.getWorld() == null) {
            return 0;
        }
        World world = center.getWorld();
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int floorY = center.getBlockY() - 1;
        int changed = 0;
        int r = radius;
        int[] widths = halfWidths(r);
        for (int dz = -r; dz <= r; dz++) {
            int width = widths[Math.abs(dz)];
            for (int dx = -width; dx <= width; dx++) {
                Block ground = world.getBlockAt(cx + dx, floorY, cz + dz);
                if (ground.getType() != Material.GRASS_BLOCK) {
                    ground.setType(Material.GRASS_BLOCK, false);
                    changed++;
                }
                for (int dy = 0; dy <= 6; dy++) {
                    Block above = world.getBlockAt(cx + dx, floorY + 1 + dy, cz + dz);
                    if (!above.getType().isAir()) {
                        above.setType(Material.AIR, false);
                    }
                }
            }
        }
        return changed;
    }

    /**
     * 清掉上一次 setarena 铺的旧圆盘（只清旧圆盘里、不在新圆盘内的草方块那一层），
     * 避免换位置/换半径后两个圆叠在一起看起来不对称。
     *
     * @return 清掉了多少格
     */
    public int clearPlatform(Location oldCenter, int oldRadius) {
        if (oldCenter == null || oldCenter.getWorld() == null || !hasCircle()) {
            return 0;
        }
        World oldWorld = oldCenter.getWorld();
        World newWorld = center.getWorld();
        int floorY = oldCenter.getBlockY() - 1;
        int ox = oldCenter.getBlockX();
        int oz = oldCenter.getBlockZ();
        int oldR = Math.max(1, oldRadius);
        int[] oldWidths = halfWidths(oldR);
        int[] newWidths = halfWidths(radius);
        int newX = center.getBlockX();
        int newZ = center.getBlockZ();
        boolean sameWorld = newWorld != null && oldWorld.getUID().equals(newWorld.getUID());
        int cleared = 0;
        for (int dz = -oldR; dz <= oldR; dz++) {
            int width = oldWidths[Math.abs(dz)];
            for (int dx = -width; dx <= width; dx++) {
                int x = ox + dx;
                int z = oz + dz;
                if (sameWorld) {
                    int ndz = z - newZ;
                    if (Math.abs(ndz) <= radius && Math.abs(x - newX) <= newWidths[Math.abs(ndz)]) {
                        continue; // 在新圆盘范围内，保留（马上会重新铺）
                    }
                }
                Block block = oldWorld.getBlockAt(x, floorY, z);
                if (block.getType() == Material.GRASS_BLOCK) {
                    block.setType(Material.AIR, false);
                    cleared++;
                }
            }
        }
        return cleared;
    }

    /**
     * 每一行（dz）的半宽。按真实圆的距离算（额外放 0.4 格让最外圈不至于只剩一块），
     * 四个方向的边缘长度一致，左右/上下/斜向都对称。
     */
    private static int[] halfWidths(int r) {
        int[] widths = new int[r + 1];
        double limit = (r + 0.4) * (r + 0.4);
        for (int dz = r; dz >= 0; dz--) {
            int width = (int) Math.floor(Math.sqrt(limit - (double) dz * dz));
            widths[dz] = Math.max(1, Math.min(r, width));
        }
        return widths;
    }

    public Location center() {
        return center == null ? null : center.clone();
    }

    /** 旁观/观战视角：圆心上方 6 格。 */
    public Location spectatorSpot() {
        return center == null ? null : center.clone().add(0, 6, 0);
    }

    public Location getLobby() {
        return lobby;
    }

    public void setLobby(Location lobby) {
        this.lobby = lobby == null ? null : lobby.clone();
    }

    public List<Location> getSpawns() {
        return spawns;
    }

    public void addSpawn(Location loc) {
        if (loc != null) {
            spawns.add(loc.clone());
        }
    }

    public void clearSpawns() {
        spawns.clear();
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = Math.max(4, Math.min(64, radius));
    }

    public void setCenter(Location center) {
        this.center = center == null ? null : center.clone();
    }

    public World world() {
        return center == null ? null : center.getWorld();
    }

    /** 液体不算地面（水面/岩浆面不能站动物）。 */
    public static boolean isLiquid(Material type) {
        return type == Material.WATER || type == Material.LAVA
                || type == Material.BUBBLE_COLUMN
                || type == Material.KELP || type == Material.KELP_PLANT
                || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS;
    }

    private static String encode(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.1f,%.1f",
                com.hub.world.Worlds.tokenOf(loc.getWorld()), loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch());
    }

    private static Location parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 4) {
            return null;
        }
        // 房间化（R2）：坐标里的世界段可能是 tpl: 令牌，交给 Worlds 解析成实际房间世界
        World world = com.hub.world.Worlds.resolve(parts[0].trim());
        if (world == null) {
            return null;
        }
        try {
            double x = Double.parseDouble(parts[1].trim());
            double y = Double.parseDouble(parts[2].trim());
            double z = Double.parseDouble(parts[3].trim());
            float yaw = parts.length > 4 ? Float.parseFloat(parts[4].trim()) : 0.0f;
            float pitch = parts.length > 5 ? Float.parseFloat(parts[5].trim()) : 0.0f;
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
