package com.firejumper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 跃火者场地：矩形区域、等待点、地面高度、线的方向/长度/范围。 */
public final class FireJumperArena {

    public static final int UNSET = Integer.MIN_VALUE;

    private final FireJumperPlugin plugin;
    private String worldName = "world";
    private Location pos1;
    private Location pos2;
    private Location lobby;
    private final List<Location> spawns = new ArrayList<>();
    /** 玩家站立时的脚部 Y；/firejumper pos1 时自动记录。 */
    private int groundY = UNSET;
    /** x+ / x- / z+ / z-。 */
    private String direction = "x+";
    /** 0 = 用场地垂直方向总长。 */
    private double lineLength;
    /** 0 = 用场地移动方向总长。 */
    private double travelRange;
    private double lineHeight = 1.0;

    public FireJumperArena(FireJumperPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration cfg = plugin.getConfig();
        worldName = cfg.getString("arena.world", "world");
        pos1 = parse(cfg.getString("arena.pos1", ""));
        pos2 = parse(cfg.getString("arena.pos2", ""));
        lobby = parse(cfg.getString("arena.lobby", ""));
        spawns.clear();
        for (String raw : cfg.getStringList("arena.spawns")) {
            Location location = parse(raw);
            if (location != null) {
                spawns.add(location);
            }
        }
        groundY = cfg.getInt("arena.ground-y", UNSET);
        // 模板里的 -999 是"未设置"的占位值，不能当成真的 Y=-999（会让线画到世界外）
        if (groundY == -999) {
            groundY = UNSET;
        }
        direction = normalizeDirection(cfg.getString("arena.direction", "x+"));
        lineLength = Math.max(0, cfg.getDouble("arena.line-length", 0));
        travelRange = Math.max(0, cfg.getDouble("arena.travel-range", 0));
        lineHeight = Math.max(0.1, cfg.getDouble("arena.line-height", 1.0));
        if (pos1 != null && pos1.getWorld() != null) {
            worldName = pos1.getWorld().getName();
        }
    }

    public void save() {
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("arena.world", worldName);
        cfg.set("arena.pos1", format(pos1));
        cfg.set("arena.pos2", format(pos2));
        cfg.set("arena.lobby", format(lobby));
        cfg.set("arena.spawns", formatAll(spawns));
        cfg.set("arena.ground-y", groundY);
        cfg.set("arena.direction", direction);
        cfg.set("arena.line-length", lineLength);
        cfg.set("arena.travel-range", travelRange);
        cfg.set("arena.line-height", lineHeight);
        plugin.saveConfig();
    }

    public void setCorner(int index, Location location) {
        Location at = location.getBlock().getLocation();
        if (index == 1) {
            pos1 = at;
            groundY = at.getBlockY();
            worldName = at.getWorld().getName();
        } else {
            pos2 = at;
        }
    }

    public void setLobby(Location location) {
        lobby = location.clone();
        worldName = location.getWorld().getName();
    }

    /** 把当前位置设为唯一出生点（清空原有出生点）。 */
    public void setSpawn(Location location) {
        spawns.clear();
        addSpawn(location);
    }

    public void addSpawn(Location location) {
        Location loc = location.clone();
        loc.setX(loc.getBlockX() + 0.5);
        loc.setZ(loc.getBlockZ() + 0.5);
        spawns.add(loc);
        // 出生点是玩家站立位置，拿它当地面最可靠（避免手改 ground-y 差一格）
        if (groundY == UNSET) {
            groundY = loc.getBlockY();
        }
    }

    public void clearSpawns() {
        spawns.clear();
    }

    /** 返回副本，避免调用方改动内部列表。 */
    public List<Location> spawns() {
        return new ArrayList<>(spawns);
    }

    public void setGround(int y) {
        groundY = y;
    }

    public boolean setDirection(String raw) {
        String normalized = normalizeDirection(raw);
        if (!normalized.equals("x+") && !normalized.equals("x-")
                && !normalized.equals("z+") && !normalized.equals("z-")) {
            return false;
        }
        direction = normalized;
        return true;
    }

    public void setLineLength(double value) {
        lineLength = Math.max(0, value);
    }

    public void setTravelRange(double value) {
        travelRange = Math.max(0, value);
    }

    public void setLineHeight(double value) {
        lineHeight = Math.max(0.1, value);
    }

    public String direction() {
        return direction;
    }

    /** 线沿 X 轴移动（线本身沿 Z 展开）。 */
    public boolean travelX() {
        return direction.charAt(0) == 'x';
    }

    public int sign() {
        return direction.endsWith("-") ? -1 : 1;
    }

    public double lineLength() {
        return lineLength;
    }

    public double travelRange() {
        return travelRange;
    }

    public double lineHeight() {
        return lineHeight;
    }

    public boolean regionConfigured() {
        return pos1 != null && pos2 != null
                && pos1.getWorld() != null && pos2.getWorld() != null
                && pos1.getWorld().getName().equals(pos2.getWorld().getName());
    }

    public boolean ready() {
        if (!regionConfigured() || lobby == null || world() == null || spawns.isEmpty()) {
            return false;
        }
        String name = pos1.getWorld().getName();
        for (Location spawn : spawns) {
            if (spawn.getWorld() == null || !name.equals(spawn.getWorld().getName())) {
                return false;
            }
        }
        return true;
    }

    public String missing() {
        StringBuilder out = new StringBuilder();
        if (!regionConfigured()) {
            out.append("pos1/pos2 ");
        }
        if (lobby == null) {
            out.append("等待点 ");
        }
        if (spawns.isEmpty()) {
            out.append("出生点 ");
        }
        if (world() == null) {
            out.append("世界未加载 ");
        }
        return out.toString().trim();
    }

    public boolean contains(Location location) {
        if (!regionConfigured() || location == null || location.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().getName().equals(pos1.getWorld().getName())) {
            return false;
        }
        return location.getBlockX() >= minX() && location.getBlockX() <= maxX()
                && location.getBlockZ() >= minZ() && location.getBlockZ() <= maxZ();
    }

    public int minX() {
        return Math.min(pos1.getBlockX(), pos2.getBlockX());
    }

    public int maxX() {
        return Math.max(pos1.getBlockX(), pos2.getBlockX());
    }

    public int minZ() {
        return Math.min(pos1.getBlockZ(), pos2.getBlockZ());
    }

    public int maxZ() {
        return Math.max(pos1.getBlockZ(), pos2.getBlockZ());
    }

    /** 玩家站立高度（脚部 Y）。 */
    public int groundY() {
        if (groundY != UNSET) {
            return groundY;
        }
        if (!spawns.isEmpty()) {
            return spawns.get(0).getBlockY();
        }
        return pos1 == null ? 64 : Math.min(pos1.getBlockY(), pos2 == null ? pos1.getBlockY() : pos2.getBlockY());
    }

    public World world() {
        if (regionConfigured()) {
            return pos1.getWorld();
        }
        return com.hub.world.Worlds.resolve(worldName);
    }

    public String worldName() {
        World world = world();
        return world == null ? worldName : world.getName();
    }

    public Location center() {
        if (!regionConfigured()) {
            return null;
        }
        return new Location(world(), (minX() + maxX() + 1) / 2.0, groundY(), (minZ() + maxZ() + 1) / 2.0);
    }

    public Location lobby() {
        return  com.hub.room.RoomBinding.apply("firejumper", lobby == null ? null : lobby.clone());
    }

    public Location pos1() {
        return  com.hub.room.RoomBinding.apply("firejumper", pos1 == null ? null : pos1.clone());
    }

    public Location pos2() {
        return  com.hub.room.RoomBinding.apply("firejumper", pos2 == null ? null : pos2.clone());
    }

    public Location spectatorSpot() {
        if (lobby != null) {
            return lobby.clone();
        }
        Location center = center();
        return center == null ? null : center.clone().add(0, 8, 0);
    }

    public String sizeText() {
        if (!regionConfigured()) {
            return "未设置";
        }
        int dx = maxX() - minX() + 1;
        int dz = maxZ() - minZ() + 1;
        return dx + "x" + dz + "（地面 Y " + groundY() + "）";
    }

    private static String normalizeDirection(String raw) {
        if (raw == null) {
            return "x+";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("x") || value.equals("x+") || value.equals("east") || value.equals("右")) {
            return "x+";
        }
        if (value.equals("x-") || value.equals("west") || value.equals("左")) {
            return "x-";
        }
        if (value.equals("z") || value.equals("z+") || value.equals("south") || value.equals("前")) {
            return "z+";
        }
        if (value.equals("z-") || value.equals("north") || value.equals("后")) {
            return "z-";
        }
        return value;
    }

    public static String format(Location location) {
        if (location == null || location.getWorld() == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.2f,%.2f",
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    private static List<String> formatAll(List<Location> locations) {
        List<String> out = new ArrayList<>();
        for (Location location : locations) {
            out.add(format(location));
        }
        return out;
    }

    public static Location parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 4) {
            return null;
        }
        // 支持 tpl:<模板键> 令牌（房间化）：房间存在时解析成房间世界
        World world = com.hub.world.Worlds.resolve(parts[0]);
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
