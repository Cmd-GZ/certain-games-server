package com.tntbattle;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 双岛场地：区域范围、每队多个出生点、等待点、虚空高度。 */
public final class TNTBattleArena {

    private final TNTBattlePlugin plugin;
    private String worldName = "world";
    private Location pos1;
    private Location pos2;

    /** 场地边界角 1（供区域声明用，{@code docs/框架不变量.md} I2）。 */
    public Location getPos1() {
        return pos1;
    }

    /** 场地边界角 2。 */
    public Location getPos2() {
        return pos2;
    }
    private Location lobby;
    private final List<Location> redSpawns = new ArrayList<>();
    private final List<Location> blueSpawns = new ArrayList<>();

    public TNTBattleArena(TNTBattlePlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        worldName = plugin.getConfig().getString("arena.world", "world");
        pos1 = parse(plugin.getConfig().getString("arena.pos1", null));
        pos2 = parse(plugin.getConfig().getString("arena.pos2", null));
        lobby = parse(plugin.getConfig().getString("arena.lobby", null));
        redSpawns.clear();
        blueSpawns.clear();
        for (String raw : plugin.getConfig().getStringList("arena.red-spawns")) {
            Location loc = parse(raw);
            if (loc != null) {
                redSpawns.add(loc);
            }
        }
        for (String raw : plugin.getConfig().getStringList("arena.blue-spawns")) {
            Location loc = parse(raw);
            if (loc != null) {
                blueSpawns.add(loc);
            }
        }
        // 兼容旧版单出生点配置
        if (redSpawns.isEmpty()) {
            Location legacy = parse(plugin.getConfig().getString("arena.red-spawn", null));
            if (legacy != null) {
                redSpawns.add(legacy);
            }
        }
        if (blueSpawns.isEmpty()) {
            Location legacy = parse(plugin.getConfig().getString("arena.blue-spawn", null));
            if (legacy != null) {
                blueSpawns.add(legacy);
            }
        }
        if (pos1 != null && pos1.getWorld() != null) {
            worldName = pos1.getWorld().getName();
        } else if (!redSpawns.isEmpty() && redSpawns.get(0).getWorld() != null) {
            worldName = redSpawns.get(0).getWorld().getName();
        }
    }

    public void save() {
        // 先读回磁盘上的配置，避免用手里的旧内存值把玩家手动改过的键（如 debug-damage）覆盖掉
        plugin.reloadConfig();
        plugin.getConfig().set("arena.world", worldName());
        plugin.getConfig().set("arena.pos1", format(pos1));
        plugin.getConfig().set("arena.pos2", format(pos2));
        plugin.getConfig().set("arena.lobby", format(lobby));
        plugin.getConfig().set("arena.red-spawn", null);
        plugin.getConfig().set("arena.blue-spawn", null);
        plugin.getConfig().set("arena.red-spawns", strings(redSpawns));
        plugin.getConfig().set("arena.blue-spawns", strings(blueSpawns));
        plugin.saveConfig();
    }

    private List<String> strings(List<Location> locations) {
        List<String> out = new ArrayList<>();
        for (Location location : locations) {
            out.add(format(location));
        }
        return out;
    }

    public void setCorner(int index, Location location) {
        if (index == 1) {
            pos1 = location.getBlock().getLocation();
        } else {
            pos2 = location.getBlock().getLocation();
        }
        worldName = location.getWorld().getName();
    }

    public void setSpawn(Team team, Location location) {
        spawns(team).clear();
        addSpawn(team, location);
    }

    public void addSpawn(Team team, Location location) {
        Location loc = location.clone();
        loc.setX(loc.getBlockX() + 0.5);
        loc.setZ(loc.getBlockZ() + 0.5);
        spawns(team).add(loc);
        worldName = loc.getWorld().getName();
    }

    public void clearSpawns(Team team) {
        spawns(team).clear();
    }

    public void setLobby(Location location) {
        lobby = location.clone();
    }

    public void setVoidY(int y) {
        plugin.getConfig().set("void-y", y);
        plugin.saveConfig();
    }

    public int voidY() {
        return plugin.getConfig().getInt("void-y", -64);
    }

    public boolean regionConfigured() {
        return pos1 != null && pos2 != null
                && pos1.getWorld() != null && pos2.getWorld() != null
                && pos1.getWorld().getName().equals(pos2.getWorld().getName());
    }

    public boolean ready() {
        if (!regionConfigured() || redSpawns.isEmpty() || blueSpawns.isEmpty()) {
            return false;
        }
        String world = pos1.getWorld().getName();
        for (Location spawn : redSpawns) {
            if (!world.equals(spawn.getWorld().getName())) {
                return false;
            }
        }
        for (Location spawn : blueSpawns) {
            if (!world.equals(spawn.getWorld().getName())) {
                return false;
            }
        }
        return true;
    }

    public boolean contains(Location location) {
        if (!regionConfigured() || location == null || location.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().getName().equals(pos1.getWorld().getName())) {
            return false;
        }
        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
        // pos1/pos2 只决定 X/Z 对角线；Y 覆盖整个世界高度（从世界最低到最高）
        int minY = location.getWorld().getMinHeight();
        int maxY = location.getWorld().getMaxHeight() - 1;
        return location.getBlockX() >= minX && location.getBlockX() <= maxX
                && location.getBlockY() >= minY && location.getBlockY() <= maxY
                && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
    }

    public World world() {
        if (regionConfigured()) {
            return pos1.getWorld();
        }
        if (!redSpawns.isEmpty()) {
            return redSpawns.get(0).getWorld();
        }
        return Bukkit.getWorld(worldName);
    }

    public String worldName() {
        World world = world();
        return world == null ? worldName : world.getName();
    }

    public Location spawn(Team team, int index) {
        List<Location> list = spawns(team);
        if (list.isEmpty()) {
            return null;
        }
        return list.get(Math.floorMod(index, list.size())).clone();
    }

    public List<Location> spawns(Team team) {
        return team == Team.RED ? redSpawns : blueSpawns;
    }

    public Location lobby() {
        return lobby == null ? null : lobby.clone();
    }

    public Location pos1() {
        return pos1 == null ? null : pos1.clone();
    }

    public Location pos2() {
        return pos2 == null ? null : pos2.clone();
    }

    public String sizeText() {
        if (!regionConfigured()) {
            return "未设置";
        }
        int dx = Math.abs(pos1.getBlockX() - pos2.getBlockX()) + 1;
        int dz = Math.abs(pos1.getBlockZ() - pos2.getBlockZ()) + 1;
        int dy = pos1.getWorld().getMaxHeight() - pos1.getWorld().getMinHeight();
        return dx + "x" + dy + "x" + dz + "（Y 覆盖整个世界高度）";
    }

    /** 淘汰后的旁观位置：优先等待点，否则第一个红队出生点上方。 */
    public Location spectatorLocation() {
        if (lobby != null) {
            return lobby.clone();
        }
        if (!redSpawns.isEmpty()) {
            return redSpawns.get(0).clone().add(0, 8, 0);
        }
        return null;
    }

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

    public enum Team {
        RED("红队", "c", org.bukkit.Material.RED_WOOL),
        BLUE("蓝队", "9", org.bukkit.Material.BLUE_WOOL);

        private final String display;
        private final String tabColor;
        private final org.bukkit.Material wool;

        Team(String display, String tabColor, org.bukkit.Material wool) {
            this.display = display;
            this.tabColor = tabColor;
            this.wool = wool;
        }

        public String display() {
            return display;
        }

        public String tabColor() {
            return tabColor;
        }

        public org.bukkit.Material wool() {
            return wool;
        }

        public Team other() {
            return this == RED ? BLUE : RED;
        }
    }
}
