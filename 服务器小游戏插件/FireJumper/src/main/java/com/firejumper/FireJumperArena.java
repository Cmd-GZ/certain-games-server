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
     * "该游戏唯一的房间"，两者都是**按游戏一个槽**；两房间同时跑时它会给出另一个房间的世界，
     * 于是同一份代码里"世界"和"坐标"对不上（AnimalSlaughter 1.5.0 实测会抛
     * {@code Location cannot be in a different world}）。
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
        return com.hub.room.RoomBinding.apply("firejumper", loc);
    }

    /**
     * **把解析出来的坐标重新钉回自己的房间世界**（`load()` 用）。
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
        pos1 = pin(parse(cfg.getString("arena.pos1", "")));
        pos2 = pin(parse(cfg.getString("arena.pos2", "")));
        lobby = pin(parse(cfg.getString("arena.lobby", "")));
        spawns.clear();
        for (String raw : cfg.getStringList("arena.spawns")) {
            Location location = pin(parse(raw));
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
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        // R2：写不了（反查不到模板 / 房间世界名）时**什么都不写**，保留磁盘上的原值
        String worldToken = worldToken();
        if (worldToken != null) {
            cfg.set("arena.world", worldToken);
        }
        String pos1Token = format(pos1);
        if (pos1Token != null) {
            cfg.set("arena.pos1", pos1Token);
        }
        String pos2Token = format(pos2);
        if (pos2Token != null) {
            cfg.set("arena.pos2", pos2Token);
        }
        String lobbyToken = format(lobby);
        if (lobbyToken != null) {
            cfg.set("arena.lobby", lobbyToken);
        }
        List<String> spawnTokens = formatAll(spawns);
        if (!spawnTokens.isEmpty()) {
            cfg.set("arena.spawns", spawnTokens);
        }
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

    /**
     * 场地所在世界：**必须和 {@link #pos1()} / {@link #pos2()} 走同一套解析**，
     * 否则会出现"世界是 A 房、坐标是 B 房"的组合。
     */
    public World world() {
        if (roomWorld != null) {
            return roomWorld;
        }
        if (regionConfigured()) {
            Location resolved = com.hub.room.RoomBinding.apply("firejumper", pos1);
            return resolved == null ? null : resolved.getWorld();
        }
        return com.hub.world.Worlds.resolve(worldName);
    }

    /**
     * 存档用的世界 token：房间实例写 {@code tpl:<模板键>}；**反查不到模板时返回 null**
     * （调用方跳过不写）—— 否则房间一销毁，配置里就留下 {@code room_xxx_1}，下次开服永远"未配置"。
     */
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
        return apply(lobby == null ? null : lobby.clone());
    }

    public Location pos1() {
        return apply(pos1 == null ? null : pos1.clone());
    }

    public Location pos2() {
        return apply(pos2 == null ? null : pos2.clone());
    }

    public Location spectatorSpot() {
        if (lobby != null) {
            return apply(lobby.clone());
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

    /**
     * 坐标 → 存档 token；**写不了时返回 null**（调用方跳过，保留磁盘上的原值）。
     *
     * <p>原来这里直接写 {@code world.getName()}，房间世界里设场地就会把
     * {@code room_xxx_1} 钉进配置，房间一销毁这份配置就永远"未配置"（同款真实事故）。
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
