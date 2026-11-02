package com.arrowrain;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 万箭齐发场地：矩形区域、等待点、固定出生点、地面高度。 */
public final class ArrowRainArena {

    public static final int UNSET = Integer.MIN_VALUE;

    private final ArrowRainPlugin plugin;
    private String worldName = "world";
    private Location pos1;
    private Location pos2;
    private Location lobby;
    private final List<Location> spawns = new ArrayList<>();
    private int groundY = UNSET;

    public ArrowRainArena(ArrowRainPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration cfg = plugin.getConfig();
        worldName = cfg.getString("arena.world", "world");
        pos1 = parse(cfg.getString("arena.pos1", ""));
        pos2 = parse(cfg.getString("arena.pos2", ""));
        lobby = parse(cfg.getString("arena.lobby", ""));
        groundY = cfg.getInt("arena.ground-y", UNSET);
        if (groundY == -999) {
            groundY = UNSET;
        }
        spawns.clear();
        for (String raw : cfg.getStringList("arena.spawns")) {
            Location location = parse(raw);
            if (location != null) {
                spawns.add(location);
            }
        }
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
        cfg.set("arena.ground-y", groundY);
        cfg.set("arena.spawns", formatAll(spawns));
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

    public void setGround(int y) {
        groundY = y;
    }

    public void setSpawn(Location location) {
        spawns.clear();
        addSpawn(location);
    }

    public void addSpawn(Location location) {
        Location loc = location.clone();
        loc.setX(loc.getBlockX() + 0.5);
        loc.setZ(loc.getBlockZ() + 0.5);
        spawns.add(loc);
        if (groundY == UNSET) {
            groundY = loc.getBlockY();
        }
    }

    public void clearSpawns() {
        spawns.clear();
    }

    public List<Location> spawns() {
        return new ArrayList<>(spawns);
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
        return Bukkit.getWorld(worldName);
    }

    public String worldName() {
        World world = world();
        return world == null ? worldName : world.getName();
    }

    public Location lobby() {
        return  com.hub.room.RoomBinding.apply("arrowrain", lobby == null ? null : lobby.clone());
    }

    public Location pos1() {
        return  com.hub.room.RoomBinding.apply("arrowrain", pos1 == null ? null : pos1.clone());
    }

    public Location pos2() {
        return  com.hub.room.RoomBinding.apply("arrowrain", pos2 == null ? null : pos2.clone());
    }

    public Location center() {
        if (!regionConfigured()) {
            return null;
        }
        return new Location(world(), (minX() + maxX() + 1) / 2.0, groundY(), (minZ() + maxZ() + 1) / 2.0);
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
        return (maxX() - minX() + 1) + "x" + (maxZ() - minZ() + 1) + "（地面 Y " + groundY() + "）";
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
