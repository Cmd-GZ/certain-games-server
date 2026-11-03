package com.tntbattle;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 一个 TNT 对战场地：区域范围、每队多个出生点、等待点、虚空/建筑高度。 */
public final class TNTBattleArena {

    private final TNTBattlePlugin plugin;
    private final String name;
    private String worldName = "world";
    private Location pos1;
    private Location pos2;

    /** 场地边界角 1（供区域声明用，{@code docs/框架不变量.md} I2）。 */
    public Location getPos1() {
        return  com.hub.room.RoomBinding.apply("tntbattle", pos1);
    }

    /** 场地边界角 2。 */
    public Location getPos2() {
        return  com.hub.room.RoomBinding.apply("tntbattle", pos2);
    }
    private Location lobby;
    private final List<Location> redSpawns = new ArrayList<>();
    private final List<Location> blueSpawns = new ArrayList<>();
    private int voidY = -64;
    private int buildHeightLimit = -1;
    /** 0 = 用全局 max-players。 */
    private int maxPlayers;

    public TNTBattleArena(TNTBattlePlugin plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    public String name() {
        return name;
    }

    private ConfigurationSection section() {
        return plugin.getConfig().getConfigurationSection("arenas." + name);
    }

    public void load() {
        ConfigurationSection sec = section();
        if (sec == null) {
            return;
        }
        FileConfiguration cfg = plugin.getConfig();
        worldName = sec.getString("world", cfg.getString("arena.world", "world"));
        pos1 = parse(sec.getString("pos1", ""));
        pos2 = parse(sec.getString("pos2", ""));
        lobby = parse(sec.getString("lobby", ""));
        redSpawns.clear();
        blueSpawns.clear();
        for (String raw : sec.getStringList("red-spawns")) {
            Location loc = parse(raw);
            if (loc != null) {
                redSpawns.add(loc);
            }
        }
        for (String raw : sec.getStringList("blue-spawns")) {
            Location loc = parse(raw);
            if (loc != null) {
                blueSpawns.add(loc);
            }
        }
        // 兼容旧版单出生点配置
        if (redSpawns.isEmpty()) {
            Location legacy = parse(sec.getString("red-spawn", ""));
            if (legacy != null) {
                redSpawns.add(legacy);
            }
        }
        if (blueSpawns.isEmpty()) {
            Location legacy = parse(sec.getString("blue-spawn", ""));
            if (legacy != null) {
                blueSpawns.add(legacy);
            }
        }
        voidY = sec.getInt("void-y", cfg.getInt("void-y", -64));
        buildHeightLimit = sec.getInt("build-height-limit", cfg.getInt("build-height-limit", -1));
        maxPlayers = Math.max(0, sec.getInt("max-players", 0));
        if (pos1 != null && pos1.getWorld() != null) {
            worldName = com.hub.world.Worlds.tokenOf(pos1.getWorld());
        } else if (!redSpawns.isEmpty() && redSpawns.get(0).getWorld() != null) {
            worldName = com.hub.world.Worlds.tokenOf(redSpawns.get(0).getWorld());
        }
    }

    public void save() {
        // 先读回磁盘配置，只改本场 section，避免多场之间互相覆盖
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        String path = "arenas." + name;
        // R2：写世界 token（房间实例 → tpl:模板键），别把临时房间名写进配置
        cfg.set(path + ".world",
                world() == null ? worldName : com.hub.world.Worlds.tokenOf(world()));
        cfg.set(path + ".pos1", format(pos1));
        cfg.set(path + ".pos2", format(pos2));
        cfg.set(path + ".lobby", format(lobby));
        cfg.set(path + ".red-spawn", null);
        cfg.set(path + ".blue-spawn", null);
        cfg.set(path + ".red-spawns", strings(redSpawns));
        cfg.set(path + ".blue-spawns", strings(blueSpawns));
        cfg.set(path + ".void-y", voidY);
        cfg.set(path + ".build-height-limit", buildHeightLimit);
        cfg.set(path + ".max-players", maxPlayers);
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
        worldName = com.hub.world.Worlds.tokenOf(location.getWorld());
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
        worldName = com.hub.world.Worlds.tokenOf(loc.getWorld());
    }

    public void clearSpawns(Team team) {
        spawns(team).clear();
    }

    public void setLobby(Location location) {
        lobby = location.clone();
    }

    public void setVoidY(int y) {
        voidY = y;
    }

    public int voidY() {
        return voidY;
    }

    public void setBuildHeightLimit(int y) {
        buildHeightLimit = y;
    }

    /** -1 = 不限制。 */
    public int buildHeightLimit() {
        return buildHeightLimit;
    }

    public void setMaxPlayers(int value) {
        maxPlayers = Math.max(0, value);
    }

    /** 0 = 用全局 max-players。 */
    public int maxPlayers() {
        return maxPlayers;
    }

    public boolean regionConfigured() {
        return pos1 != null && pos2 != null
                && pos1.getWorld() != null && pos2.getWorld() != null
                && pos1.getWorld().getName().equals(pos2.getWorld().getName());
    }

    public boolean ready() {
        if (!regionConfigured() || lobby == null || redSpawns.isEmpty() || blueSpawns.isEmpty()) {
            return false;
        }
        String world = pos1.getWorld().getName();
        for (Location spawn : redSpawns) {
            if (spawn.getWorld() == null || !world.equals(spawn.getWorld().getName())) {
                return false;
            }
        }
        for (Location spawn : blueSpawns) {
            if (spawn.getWorld() == null || !world.equals(spawn.getWorld().getName())) {
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
        return com.hub.world.Worlds.resolve(worldName);
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
        return  com.hub.room.RoomBinding.apply("tntbattle", lobby == null ? null : lobby.clone());
    }

    public Location pos1() {
        return  com.hub.room.RoomBinding.apply("tntbattle", pos1 == null ? null : pos1.clone());
    }

    public Location pos2() {
        return  com.hub.room.RoomBinding.apply("tntbattle", pos2 == null ? null : pos2.clone());
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
        // 房间化（R2/R1.5）：配置里的世界段可能是 `tpl:<模板键>` 令牌，
        // 只有存活房间时才解析得出来。用 Bukkit.getWorld 会直接返回 null -> 场地永远"未配置"。
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
