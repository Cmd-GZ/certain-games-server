package com.hillking;

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

/** 高台场地：圆形红羊毛台 + 底座 + 等待点 + 出生点。 */
public final class HillKingArena {

    private final JavaPlugin plugin;
    private Location center;
    private int radius = 12;
    private int height = 3;
    private int depth = 3;
    private Location lobby;
    private final List<Location> spawns = new ArrayList<>();

    public HillKingArena(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration cfg = plugin.getConfig();
        center = parse(cfg.getString("arena.center", ""));
        radius = clampRadius(cfg.getInt("arena.radius", 12));
        height = clampHeight(cfg.getInt("arena.height", 3));
        depth = clampDepth(cfg.getInt("arena.depth", 3));
        lobby = parse(cfg.getString("arena.lobby", ""));
        spawns.clear();
        for (String raw : cfg.getStringList("arena.spawns")) {
            Location loc = parse(raw);
            if (loc != null) {
                spawns.add(loc);
            }
        }
        publishRegion();   // 声明地盘（I2）
    }

    public void save() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("arena.center", encode(center));
        cfg.set("arena.radius", radius);
        cfg.set("arena.height", height);
        cfg.set("arena.depth", depth);
        cfg.set("arena.lobby", encode(lobby));
        List<String> encoded = new ArrayList<>();
        for (Location spawn : spawns) {
            encoded.add(encode(spawn));
        }
        cfg.set("arena.spawns", encoded);
        plugin.saveConfig();
        publishRegion();   // 区域可能刚被 /hill build 改过，重新声明
    }

    /**
     * 把自己的地盘声明给框架（{@code docs/框架不变量.md} 的 I2）。
     *
     * <p>山丘之王是「某世界里一个半径 R 的圆台」，但**地盘声明统一用外接 AABB**：
     * 声明宁可偏大（多管一块空地）也不能偏小（别人溜进来我不管）。
     * 高度上按台面上下各留 12 格，覆盖跳跃与被击飞的位移。
     * 玩法判定仍走 {@link #containsXZ(Location)} 的圆形逻辑，两者互不影响。
     */
    public void publishRegion() {
        try {
            com.hub.region.RegionRegistry.unregisterGame("hillking");
            if (!hasCircle()) {
                return;         // 还没建场：不声明，也就不会和别人冲突
            }
            com.hub.region.RegionRegistry.register(
                    com.hub.region.ArenaRegion.around("hillking", "", center, radius, 24));
        } catch (RuntimeException ex) {
            com.hub.diagnostics.Failures.recordAndWarn("HillKing.声明区域", ex);
        }
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
            missing.add("高台（/hill build <半径> [台高]）");
        }
        if (lobby == null) {
            missing.add("lobby（/hill setlobby）");
        }
        if (spawns.isEmpty()) {
            missing.add("出生点（/hill build 或 /hill genspawns）");
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

    /** 是不是站在高台台面上（XZ 在台内 + 高度贴着台面，跳起来也算）。 */
    public boolean isOnPlatform(Location loc) {
        if (!hasCircle() || loc == null || loc.getWorld() == null || !loc.getWorld().equals(center.getWorld())) {
            return false;
        }
        double dx = loc.getX() - (center.getBlockX() + 0.5);
        double dz = loc.getZ() - (center.getBlockZ() + 0.5);
        // 台子最外圈是按 (半径+0.4) 铺的，判定再放宽 1 格，站边缘方块也算在台上
        double limit = radius + 1.0;
        if (dx * dx + dz * dz > limit * limit) {
            return false;
        }
        double dy = loc.getY() - center.getY();
        return dy >= -0.6 && dy <= 2.5;
    }

    /** 保护范围：台子及上方 8 格/下方台高范围内。 */
    public boolean contains(Location loc) {
        if (!containsXZ(loc)) {
            return false;
        }
        double dy = loc.getY() - center.getY();
        return dy >= -(depth + 2.0) && dy <= 8.0;
    }

    public Location randomSpawn(Random random) {
        if (spawns.isEmpty()) {
            return center == null ? lobby : center.clone();
        }
        return spawns.get(random.nextInt(spawns.size())).clone();
    }

    /** 在台面上均匀撒 count 个出生点（会先清空旧的）。 */
    public int generateSpawns(int count, Random random) {
        spawns.clear();
        if (!hasCircle() || center.getWorld() == null) {
            return 0;
        }
        int want = Math.max(1, Math.min(64, count));
        double maxDist = Math.max(0.5, radius - 2.0);
        int attempts = 0;
        int maxAttempts = Math.max(200, want * 300);
        while (spawns.size() < want && attempts++ < maxAttempts) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double dist = Math.sqrt(random.nextDouble()) * maxDist;
            Location point = new Location(center.getWorld(),
                    center.getBlockX() + 0.5 + Math.cos(angle) * dist,
                    center.getY(),
                    center.getBlockZ() + 0.5 + Math.sin(angle) * dist);
            boolean tooClose = false;
            for (Location existing : spawns) {
                if (existing.distanceSquared(point) < 1.5 * 1.5) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                spawns.add(point);
            }
        }
        return spawns.size();
    }

    /** 铺圆形红色羊毛台 + 下方 depth 层石头，并清掉台面上方 6 格。 */
    public int buildPlatform() {
        if (!hasCircle() || center.getWorld() == null) {
            return 0;
        }
        World world = center.getWorld();
        Material platform = platformMaterial();
        Material base = baseMaterial();
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int floorY = center.getBlockY() - 1;
        int[] widths = halfWidths(radius);
        int changed = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            int width = widths[Math.abs(dz)];
            for (int dx = -width; dx <= width; dx++) {
                Block top = world.getBlockAt(cx + dx, floorY, cz + dz);
                if (top.getType() != platform) {
                    top.setType(platform, false);
                    changed++;
                }
                for (int d = 1; d <= depth; d++) {
                    Block body = world.getBlockAt(cx + dx, floorY - d, cz + dz);
                    if (body.getType() != base) {
                        body.setType(base, false);
                        changed++;
                    }
                }
                for (int up = 1; up <= 6; up++) {
                    Block above = world.getBlockAt(cx + dx, floorY + up, cz + dz);
                    if (!above.getType().isAir()) {
                        above.setType(Material.AIR, false);
                    }
                }
            }
        }
        return changed;
    }

    /** 清掉上一次 build 留下的台子（只清不在新台范围内的羊毛/石头）。 */
    public int clearPlatform(Location oldCenter, int oldRadius, int oldDepth) {
        if (oldCenter == null || oldCenter.getWorld() == null || !hasCircle()) {
            return 0;
        }
        World oldWorld = oldCenter.getWorld();
        World newWorld = center.getWorld();
        boolean sameWorld = newWorld != null && oldWorld.getUID().equals(newWorld.getUID());
        Material platform = platformMaterial();
        Material base = baseMaterial();
        int oldFloorY = oldCenter.getBlockY() - 1;
        int newFloorY = center.getBlockY() - 1;
        int ox = oldCenter.getBlockX();
        int oz = oldCenter.getBlockZ();
        int r = clampRadius(oldRadius);
        int d = clampDepth(oldDepth);
        int[] oldWidths = halfWidths(r);
        int[] newWidths = halfWidths(radius);
        int cleared = 0;
        for (int dz = -r; dz <= r; dz++) {
            int width = oldWidths[Math.abs(dz)];
            for (int dx = -width; dx <= width; dx++) {
                int x = ox + dx;
                int z = oz + dz;
                for (int dy = 0; dy >= -d; dy--) {
                    int y = oldFloorY + dy;
                    if (sameWorld && insideNew(x, z, y, newFloorY, newWidths)) {
                        continue;
                    }
                    Block block = oldWorld.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type == platform || type == base) {
                        block.setType(Material.AIR, false);
                        cleared++;
                    }
                }
            }
        }
        return cleared;
    }

    private boolean insideNew(int x, int z, int y, int newFloorY, int[] newWidths) {
        int ndz = z - center.getBlockZ();
        if (Math.abs(ndz) > radius || Math.abs(x - center.getBlockX()) > newWidths[Math.abs(ndz)]) {
            return false;
        }
        int dy = y - newFloorY;
        return dy <= 0 && dy >= -depth;
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

    /** 台面中心（站台时脚踩的位置）。 */
    public Location center() {
        return center == null ? null : center.clone();
    }

    public void setCenter(Location center) {
        this.center = center == null ? null : center.clone();
    }

    /** 观战/旁观落点：台面中心上方 6 格。 */
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
        this.radius = clampRadius(radius);
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = clampDepth(depth);
    }

    /** 台面比执行建场指令时站立的方块高几格。 */
    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = clampHeight(height);
    }

    public Material platformMaterial() {
        Material material = Material.matchMaterial(plugin.getConfig().getString("arena.platform-material", "RED_WOOL"));
        return material == null || material.isAir() ? Material.RED_WOOL : material;
    }

    public Material baseMaterial() {
        Material material = Material.matchMaterial(plugin.getConfig().getString("arena.base-material", "STONE"));
        return material == null || material.isAir() ? Material.STONE : material;
    }

    public static int clampRadius(int value) {
        return Math.max(4, Math.min(64, value));
    }

    public static int clampHeight(int value) {
        return Math.max(0, Math.min(32, value));
    }

    public static int clampDepth(int value) {
        return Math.max(0, Math.min(32, value));
    }

    private static String encode(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.1f,%.1f",
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
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
        World world = Bukkit.getWorld(parts[0].trim());
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
