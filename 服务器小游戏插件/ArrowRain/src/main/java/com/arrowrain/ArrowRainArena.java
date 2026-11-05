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

    /**
     * D22：这个场地实例**钉死**的房间世界（每个房间一份实例）。
     *
     * <p>{@link com.hub.room.RoomBinding} 是"按游戏一个槽"，两房间同时跑时后一个覆盖前一个 ——
     * 甲房的实例会读到乙房的世界（人被传错房间）。钉死之后读取只认自己的世界。
     */
    private World roomWorld;

    public void bindWorld(World world) {
        this.roomWorld = world;
    }

    public World boundWorld() {
        return roomWorld;
    }

    /**
     * 读坐标时的世界解析：**钉死了就只认自己的房间世界**，没钉才退回按上下文的换绑。
     *
     * <p>钉死之后**不能**再回落 {@link com.hub.room.RoomBinding}：它先看"派对委派"、再看
     * "该游戏唯一的房间"，两者都是**按游戏一个槽**；两房间同时跑时会给出另一个房间的世界，
     * 于是同一份代码里"世界"和"坐标"对不上（抛 {@code Location cannot be in a different world}）。
     */
    private Location apply(Location loc) {
        if (loc == null) {
            return null;
        }
        if (roomWorld != null) {
            if (roomWorld.equals(loc.getWorld())) {
                return loc;
            }
            return new Location(roomWorld, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        }
        return com.hub.room.RoomBinding.apply("arrowrain", loc);
    }

    /**
     * **把解析出来的坐标重新钉回自己的房间世界**（{@code load()} 用）。
     *
     * <p>`tpl:` 的解析看"当前房间上下文"，而 `/hub join` 会让 HubCore 给所有子游戏发一次 reload；
     * 如果那次上下文指向**别的**房间，解析结果就会落到别人的世界里。钉回去之后，
     * 房间实例无论如何重载都只认自己的世界。
     */
    private Location pin(Location loc) {
        if (loc == null) {
            return null;
        }
        if (roomWorld == null || roomWorld.equals(loc.getWorld())) {
            return loc;
        }
        return new Location(roomWorld, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

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
        // D22：解析出来的坐标**钉回本房间的世界**（`tpl:` 解析看上下文，可能指到别人的房间）
        pos1 = pin(parse(cfg.getString("arena.pos1", "")));
        pos2 = pin(parse(cfg.getString("arena.pos2", "")));
        lobby = pin(parse(cfg.getString("arena.lobby", "")));
        groundY = cfg.getInt("arena.ground-y", UNSET);
        if (groundY == -999) {
            groundY = UNSET;
        }
        spawns.clear();
        for (String raw : cfg.getStringList("arena.spawns")) {
            Location location = pin(parse(raw));
            if (location != null) {
                spawns.add(location);
            }
        }
        if (pos1 != null && pos1.getWorld() != null) {
            worldName = com.hub.world.Worlds.tokenOf(pos1.getWorld());
        }
    }

    public void save() {
        // R2：磁盘上别的键保持现状，只写自己负责的 arena 段
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        // R2：写世界 token（房间实例 → tpl:模板键）；**反查不到模板就跳过不写**，
        // 否则房间一销毁，配置里就留下 room_xxx_1，下次开服该场地永远"未配置"。
        String token = worldToken();
        if (token != null) {
            cfg.set("arena.world", token);
        }
        write(cfg, "arena.pos1", pos1);
        write(cfg, "arena.pos2", pos2);
        write(cfg, "arena.lobby", lobby);
        cfg.set("arena.ground-y", groundY);
        List<String> spawnTokens = formatAll(spawns);
        if (!spawnTokens.isEmpty()) {
            cfg.set("arena.spawns", spawnTokens);
        }
        plugin.saveConfig();
    }

    /** 存档用的世界 token：房间实例写 {@code tpl:<模板键>}；反查不到时返回 null（调用方跳过）。 */
    private String worldToken() {
        World world = world();
        if (world == null) {
            return null;
        }
        String token = com.hub.world.Worlds.tokenOf(world);
        if (token == null || token.isBlank() || token.startsWith("room_")) {
            return null;
        }
        return token;
    }

    /** 写一个坐标；**写不了就什么都不做**（保留磁盘上的原值）。 */
    private static void write(FileConfiguration cfg, String path, Location loc) {
        String token = format(loc);
        if (token != null) {
            cfg.set(path, token);
        }
    }

    public void setCorner(int index, Location location) {
        Location at = location.getBlock().getLocation();
        if (index == 1) {
            pos1 = at;
            groundY = at.getBlockY();
            worldName = com.hub.world.Worlds.tokenOf(at.getWorld());
        } else {
            pos2 = at;
        }
    }

    public void setLobby(Location location) {
        lobby = location.clone();
        worldName = com.hub.world.Worlds.tokenOf(location.getWorld());
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

    /**
     * 场地所在世界：**必须和 {@link #pos1()} / {@link #pos2()} 走同一套解析**，
     * 否则会出现"世界是 A 房、坐标是 B 房"的组合（{@code different world} 就是这么来的）。
     */
    public World world() {
        if (roomWorld != null) {
            return roomWorld;
        }
        if (regionConfigured()) {
            Location resolved = apply(pos1);
            return resolved == null ? null : resolved.getWorld();
        }
        return com.hub.world.Worlds.resolve(worldName);
    }

    public String worldName() {
        World world = world();
        return world == null ? worldName : world.getName();
    }

    public Location lobby() {
        return apply(lobby == null ? null : lobby.clone());
    }

    public Location pos1() {
        return apply(pos1 == null ? null : pos1.clone());
    }

    public Location pos2() {
        return apply(pos2 == null ? null : pos2.clone());
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

    /**
     * 坐标 → 存档 token；**写不了时返回 null**（调用方跳过，保留磁盘上的原值）。
     *
     * <p>原来这里直接写 {@code world.getName()}，房间世界里设场地就会把 {@code room_xxx_1}
     * 钉进配置，房间一销毁这份配置就永远"未配置"（同款真实事故）。
     */
    public static String format(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        String token = com.hub.world.Worlds.tokenOf(location.getWorld());
        if (token == null || token.isBlank() || token.startsWith("room_")) {
            return null;
        }
        return String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.2f,%.2f",
                token, location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    private static List<String> formatAll(List<Location> locations) {
        List<String> out = new ArrayList<>();
        for (Location location : locations) {
            String token = format(location);
            if (token != null) {
                out.add(token);
            }
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
        // 支持 `tpl:<模板键>` 令牌（R2 / R1.5）：房间化后配置里不再写死世界名，
        // 而是指向模板；`Worlds.resolve` 会把它解析成**当前那个房间的世界**。
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
