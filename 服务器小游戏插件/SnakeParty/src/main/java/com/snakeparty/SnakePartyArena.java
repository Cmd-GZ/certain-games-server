package com.snakeparty;

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

/** 方形场地：中心 + 半径（半边长）+ 平台材质 + 等待点 + 出生点。 */
public final class SnakePartyArena {

    private final JavaPlugin plugin;
    private Location center;

    /** 供区域声明用（{@code docs/框架不变量.md} I2）。 */
    public Location getCenter() {
        return center;
    }
    private int radius = 16;
    private Material floorMaterial = Material.STONE;
    private int floorLayers = 2;
    private Location lobby;
    private final List<Location> spawns = new ArrayList<>();

    public SnakePartyArena(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration cfg = plugin.getConfig();
        center = parse(cfg.getString("arena.center"));
        radius = Math.max(4, Math.min(64, cfg.getInt("arena.radius", 16)));
        Material floor = Material.matchMaterial(cfg.getString("arena.floor-material", "STONE"), false);
        floorMaterial = floor != null && floor.isBlock() ? floor : Material.STONE;
        floorLayers = Math.max(1, Math.min(5, cfg.getInt("arena.floor-layers", 2)));
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
        cfg.set("arena.floor-material", floorMaterial.name());
        cfg.set("arena.floor-layers", floorLayers);
        cfg.set("arena.lobby", encode(lobby));
        List<String> encoded = new ArrayList<>();
        for (Location spawn : spawns) {
            encoded.add(encode(spawn));
        }
        cfg.set("arena.spawns", encoded);
        plugin.saveConfig();
    }

    public boolean hasArea() {
        return center != null && center.getWorld() != null;
    }

    public boolean isReady() {
        return hasArea() && lobby != null && !spawns.isEmpty();
    }

    public String missing() {
        List<String> missing = new ArrayList<>();
        if (center == null) {
            missing.add("center（/snake setarena <半径> 或 /snake setarea <半径>）");
        }
        if (lobby == null) {
            missing.add("lobby");
        }
        if (spawns.isEmpty()) {
            missing.add("spawns");
        }
        return missing.isEmpty() ? "无" : String.join("、", missing);
    }

    /** 平台表面所在 Y（玩家脚下那一层方块）。 */
    public int floorY() {
        return center == null ? 0 : center.getBlockY() - 1;
    }

    /** 水平方向是否在方形场地内。 */
    public boolean containsXZ(Location loc) {
        if (!hasArea() || loc == null || loc.getWorld() == null || !loc.getWorld().equals(center.getWorld())) {
            return false;
        }
        double dx = loc.getX() - (center.getBlockX() + 0.5);
        double dz = loc.getZ() - (center.getBlockZ() + 0.5);
        return Math.abs(dx) <= radius + 0.5 && Math.abs(dz) <= radius + 0.5;
    }

    public boolean contains(Block block) {
        return block != null && containsXZ(block.getLocation().add(0.5, 0, 0.5));
    }

    /** 场地内随机一个地面站位（用于出生点）。 */
    public Location groundAt(int x, int z) {
        if (!hasArea() || center.getWorld() == null) {
            return null;
        }
        World world = center.getWorld();
        int top = Math.min(world.getMaxHeight() - 2, center.getBlockY() + 8);
        int bottom = Math.max(world.getMinHeight(), center.getBlockY() - 9);
        for (int y = top; y >= bottom; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type.isAir() || isLiquid(type)) {
                continue;
            }
            return new Location(world, x + 0.5, y + 1.0, z + 0.5);
        }
        return null;
    }

    /** 铺平台（默认两层石头），并清掉平台上方 9 格。返回铺了多少格。 */
    public int buildPlatform() {
        if (!hasArea() || center.getWorld() == null) {
            return 0;
        }
        World world = center.getWorld();
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int floorY = floorY();
        int changed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int layer = 0; layer < floorLayers; layer++) {
                    Block ground = world.getBlockAt(cx + dx, floorY - layer, cz + dz);
                    if (ground.getType() != floorMaterial) {
                        ground.setType(floorMaterial, false);
                        changed++;
                    }
                }
                for (int dy = 0; dy <= 8; dy++) {
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
     * 清掉上一次 setarena 铺的旧平台（只清旧方形里、不在新方形内、且还是平台材质的方块）。
     * 返回清掉多少格。
     */
    public int clearPlatform(Location oldCenter, int oldRadius) {
        if (oldCenter == null || oldCenter.getWorld() == null || !hasArea()) {
            return 0;
        }
        World oldWorld = oldCenter.getWorld();
        World newWorld = center.getWorld();
        int floorY = oldCenter.getBlockY() - 1;
        int ox = oldCenter.getBlockX();
        int oz = oldCenter.getBlockZ();
        int oldR = Math.max(1, oldRadius);
        int newX = center.getBlockX();
        int newZ = center.getBlockZ();
        boolean sameWorld = newWorld != null && oldWorld.getUID().equals(newWorld.getUID());
        int cleared = 0;
        for (int dx = -oldR; dx <= oldR; dx++) {
            for (int dz = -oldR; dz <= oldR; dz++) {
                int x = ox + dx;
                int z = oz + dz;
                if (sameWorld && Math.abs(x - newX) <= radius && Math.abs(z - newZ) <= radius) {
                    continue;
                }
                for (int layer = 0; layer < 5; layer++) {
                    Block target = oldWorld.getBlockAt(x, floorY - layer, z);
                    if (target.getType() == floorMaterial) {
                        target.setType(Material.AIR, false);
                        cleared++;
                    }
                }
            }
        }
        return cleared;
    }

    public Location center() {
        return center == null ? null : center.clone();
    }

    public void setCenter(Location center) {
        this.center = center == null ? null : center.clone();
    }

    /** 旁观/观战视角：中心上方 8 格。 */
    public Location spectatorSpot() {
        return center == null ? null : center.clone().add(0, 8, 0);
    }

    public World world() {
        return center == null ? null : center.getWorld();
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = Math.max(4, Math.min(64, radius));
    }

    public Material getFloorMaterial() {
        return floorMaterial;
    }

    public int getFloorLayers() {
        return floorLayers;
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

    /** 沿场地内侧一圈生成出生点：均匀分布在 60% 半径的圆环上，朝向切线方向。 */
    public List<Location> ringSpawns(int count) {
        List<Location> points = new ArrayList<>();
        if (!hasArea()) {
            return points;
        }
        int want = Math.max(1, count);
        double ring = Math.max(2.0, radius * 0.6);
        for (int i = 0; i < want; i++) {
            double angle = 2.0 * Math.PI * i / want;
            double x = center.getBlockX() + 0.5 + Math.cos(angle) * ring;
            double z = center.getBlockZ() + 0.5 + Math.sin(angle) * ring;
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            Location ground = groundAt(bx, bz);
            if (ground == null) {
                ground = new Location(center.getWorld(), bx + 0.5, center.getBlockY(), bz + 0.5);
            }
            // 朝向切线方向（逆时针），开局大家并行绕圈，不会直接对撞
            ground.setYaw((float) Math.toDegrees(angle));
            ground.setPitch(0f);
            points.add(ground);
        }
        return points;
    }

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
