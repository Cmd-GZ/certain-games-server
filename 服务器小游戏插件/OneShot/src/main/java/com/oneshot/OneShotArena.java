package com.oneshot;

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

/** 圆形高台场地：台面 + 底座 + 等待点 + 出生点。 */
public final class OneShotArena {

    private final JavaPlugin plugin;
    private Location center;
    private int radius = 20;
    private int height = 3;
    private int depth = 3;
    private Location pos1;
    private Location pos2;
    /** 手动区域的纵向范围：由 XZ 矩形内最低/最高非空气方块自动算出。 */
    private int boxMinY = Integer.MIN_VALUE;
    private int boxMaxY = Integer.MIN_VALUE;
    private Location lobby;
    private final List<Location> spawns = new ArrayList<>();

    public OneShotArena(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration cfg = plugin.getConfig();
        center = parse(cfg.getString("arena.center", ""));
        radius = clampRadius(cfg.getInt("arena.radius", 20));
        height = clamp(cfg.getInt("arena.height", 3));
        depth = clamp(cfg.getInt("arena.depth", 3));
        pos1 = parse(cfg.getString("arena.pos1", ""));
        pos2 = parse(cfg.getString("arena.pos2", ""));
        boxMinY = cfg.getInt("arena.box-min-y", Integer.MIN_VALUE);
        boxMaxY = cfg.getInt("arena.box-max-y", Integer.MIN_VALUE);
        lobby = parse(cfg.getString("arena.lobby", ""));
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
        cfg.set("arena.height", height);
        cfg.set("arena.depth", depth);
        cfg.set("arena.pos1", encode(pos1));
        cfg.set("arena.pos2", encode(pos2));
        if (hasBox()) {
            ensureBounds();
            cfg.set("arena.box-min-y", boxMinY);
            cfg.set("arena.box-max-y", boxMaxY);
        } else {
            cfg.set("arena.box-min-y", null);
            cfg.set("arena.box-max-y", null);
        }
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

    /** 手动区域：pos1/pos2 两个角（同一世界）。 */
    public boolean hasBox() {
        return pos1 != null && pos2 != null && pos1.getWorld() != null
                && pos1.getWorld().equals(pos2.getWorld());
    }

    public boolean hasArea() {
        return hasBox() || hasCircle();
    }

    public boolean isReady() {
        return hasArea() && lobby != null && !spawns.isEmpty();
    }

    public String missing() {
        List<String> missing = new ArrayList<>();
        if (!hasArea()) {
            missing.add("场地（/oneshot build <半径> [台高] 或 /oneshot pos1 + pos2）");
        }
        if (lobby == null) {
            missing.add("lobby（/oneshot setlobby）");
        }
        if (spawns.isEmpty()) {
            missing.add("出生点（/oneshot build、/oneshot genspawns 或 /oneshot addspawn）");
        }
        return missing.isEmpty() ? "无" : String.join("、", missing);
    }

    public boolean containsXZ(Location loc) {
        if (!hasCircle() || loc == null || loc.getWorld() == null || !loc.getWorld().equals(center.getWorld())) {
            return false;
        }
        double dx = loc.getX() - (center.getBlockX() + 0.5);
        double dz = loc.getZ() - (center.getBlockZ() + 0.5);
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    /** 是否还在场地内（跳起来也算）。 */
    public boolean isOnPlatform(Location loc) {
        if (hasBox()) {
            return insideBox(loc);
        }
        if (!hasCircle() || loc == null || loc.getWorld() == null || !loc.getWorld().equals(center.getWorld())) {
            return false;
        }
        double dx = loc.getX() - (center.getBlockX() + 0.5);
        double dz = loc.getZ() - (center.getBlockZ() + 0.5);
        double limit = radius + 1.0;
        if (dx * dx + dz * dz > limit * limit) {
            return false;
        }
        double dy = loc.getY() - center.getY();
        return dy >= -0.6 && dy <= 2.5;
    }

    private boolean insideBox(Location loc) {
        if (!hasBox() || loc == null || loc.getWorld() == null || !loc.getWorld().equals(pos1.getWorld())) {
            return false;
        }
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ()
                && loc.getY() >= minY() - 0.6;
    }

    /** 掉出场地：手动区域走出 XZ 范围或低于地面；圆形掉到台面以下。 */
    public boolean isFallenOff(Location loc) {
        if (hasBox()) {
            if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(pos1.getWorld())) {
                return false;
            }
            int x = loc.getBlockX();
            int z = loc.getBlockZ();
            boolean outside = x < minX() || x > maxX() || z < minZ() || z > maxZ();
            return outside || loc.getY() < minY() - fallKillBelow();
        }
        return hasCircle() && loc != null && center.getWorld() != null
                && center.getWorld().equals(loc.getWorld())
                && loc.getY() < center.getY() - fallKillBelow();
    }

    private int minX() {
        return Math.min(pos1.getBlockX(), pos2.getBlockX());
    }

    private int maxX() {
        return Math.max(pos1.getBlockX(), pos2.getBlockX());
    }

    private int minZ() {
        return Math.min(pos1.getBlockZ(), pos2.getBlockZ());
    }

    private int maxZ() {
        return Math.max(pos1.getBlockZ(), pos2.getBlockZ());
    }

    private int minY() {
        ensureBounds();
        return boxMinY;
    }

    private int maxY() {
        ensureBounds();
        return boxMaxY;
    }

    /** 手动区域只认 XZ；Y 轴取矩形内最低/最高的非空气方块（多层立体地图也适用）。 */
    private void ensureBounds() {
        if (!hasBox()) {
            return;
        }
        if (boxMinY != Integer.MIN_VALUE && boxMaxY != Integer.MIN_VALUE) {
            return;
        }
        World world = pos1.getWorld();
        int fromY = world.getMinHeight();
        int toY = world.getMaxHeight() - 1;
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int x = minX(); x <= maxX(); x++) {
            for (int z = minZ(); z <= maxZ(); z++) {
                for (int y = toY; y >= fromY; y--) {
                    if (world.getBlockAt(x, y, z).getType().isAir()) {
                        continue;
                    }
                    if (y < lowest) {
                        lowest = y;
                    }
                    if (y > highest) {
                        highest = y;
                    }
                }
            }
        }
        if (highest < lowest) {
            lowest = pos1.getBlockY();
            highest = lowest;
        }
        boxMinY = lowest;
        boxMaxY = highest;
    }

    /** 开局前确保纵向范围已算好（避免第一次 move 事件卡顿）。 */
    public void ensureArea() {
        ensureBounds();
    }

    private void resetBounds() {
        boxMinY = Integer.MIN_VALUE;
        boxMaxY = Integer.MIN_VALUE;
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
        if (hasBox()) {
            return generateBoxSpawns(Math.max(1, Math.min(64, count)), random);
        }
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
                if (existing.distanceSquared(point) < 2.0 * 2.0) {
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

    private int generateBoxSpawns(int want, Random random) {
        World world = pos1.getWorld();
        int minX = minX();
        int maxX = maxX();
        int minZ = minZ();
        int maxZ = maxZ();
        int fromY = Math.max(world.getMinHeight(), minY() - 2);
        int toY = Math.min(world.getMaxHeight() - 1, maxY() + 3);
        int attempts = 0;
        int maxAttempts = Math.max(200, want * 300);
        while (spawns.size() < want && attempts++ < maxAttempts) {
            int x = minX + random.nextInt(maxX - minX + 1);
            int z = minZ + random.nextInt(maxZ - minZ + 1);
            Location ground = groundAt(world, x, z, fromY, toY);
            if (ground == null) {
                continue;
            }
            boolean tooClose = false;
            for (Location existing : spawns) {
                if (existing.distanceSquared(ground) < 2.0 * 2.0) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                spawns.add(ground);
            }
        }
        return spawns.size();
    }

    /** 从 toY 往下找第一个可站立的地面（跳过空气/液体）。 */
    private Location groundAt(World world, int x, int z, int fromY, int toY) {
        for (int y = toY; y >= fromY; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type.isAir() || type == Material.WATER || type == Material.LAVA) {
                continue;
            }
            if (!type.isSolid()) {
                continue;
            }
            return new Location(world, x + 0.5, y + 1.0, z + 0.5);
        }
        return null;
    }

    /** 铺圆形台面 + 下方 depth 层底座，并清掉台面上方 6 格。 */
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

    /** 清掉上一次 build 留下的方块（只清不在新台范围内的台面/底座材料）。 */
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
        int d = clamp(oldDepth);
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

    /** 场地所在世界：手动区域取 pos1 的世界，自动圆形取圆心世界。 */
    public World world() {
        if (hasBox()) {
            return pos1.getWorld();
        }
        return center == null ? null : center.getWorld();
    }

    public void setCenter(Location center) {
        this.center = center == null ? null : center.clone();
    }

    public void setPos1(Location pos1) {
        this.pos1 = pos1 == null ? null : pos1.clone();
        resetBounds();
    }

    public void setPos2(Location pos2) {
        this.pos2 = pos2 == null ? null : pos2.clone();
        resetBounds();
    }

    public Location getPos1() {
        return pos1 == null ? null : pos1.clone();
    }

    public Location getPos2() {
        return pos2 == null ? null : pos2.clone();
    }

    /** 切回自动建场（圆形）时清掉手动区域。 */
    public void clearPos() {
        pos1 = null;
        pos2 = null;
        resetBounds();
    }

    public Location spectatorSpot() {
        if (hasBox()) {
            double cx = (minX() + maxX() + 1) / 2.0;
            double cz = (minZ() + maxZ() + 1) / 2.0;
            return new Location(pos1.getWorld(), cx, maxY() + 6.0, cz);
        }
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

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = clamp(height);
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = clamp(depth);
    }

    public double fallKillBelow() {
        return Math.max(0.5, Math.min(64.0, plugin.getConfig().getDouble("arena.fall-kill-below", 1.5)));
    }

    public Material platformMaterial() {
        Material material = Material.matchMaterial(plugin.getConfig().getString("arena.platform-material", "STONE"));
        return material == null || material.isAir() ? Material.STONE : material;
    }

    public Material baseMaterial() {
        Material material = Material.matchMaterial(plugin.getConfig().getString("arena.base-material", "STONE"));
        return material == null || material.isAir() ? Material.STONE : material;
    }

    public static int clampRadius(int value) {
        return Math.max(6, Math.min(64, value));
    }

    public static int clamp(int value) {
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
