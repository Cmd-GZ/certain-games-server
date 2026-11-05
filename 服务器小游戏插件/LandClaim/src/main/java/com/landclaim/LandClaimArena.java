package com.landclaim;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 圆形场地：中心、半径、地面高度、等待点与多个出生点。 */
public final class LandClaimArena {

    private final LandClaimPlugin plugin;

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
        return com.hub.room.RoomBinding.apply("landclaim", loc);
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
    private Location center;

    /** 供区域声明用（{@code docs/框架不变量.md} I2）。 */
    public Location getCenter() {
        return apply(center);
    }
    private int radius;

    /** 供区域声明用（{@code docs/框架不变量.md} I2）。 */
    public int getRadius() {
        return radius;
    }
    private Location lobby;
    private final List<Location> spawns = new ArrayList<>();

    public LandClaimArena(LandClaimPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        worldName = plugin.getConfig().getString("arena.world", "world");
        // D22：解析出来的坐标**钉回本房间的世界**（`tpl:` 解析看上下文，可能指到别人的房间）
        center = pin(parse(plugin.getConfig().getString("arena.center", null)));
        if (center != null && center.getWorld() != null) {
            worldName = com.hub.world.Worlds.tokenOf(center.getWorld());
        }
        radius = Math.max(0, plugin.getConfig().getInt("arena.radius", 0));
        lobby = pin(parse(plugin.getConfig().getString("arena.lobby", null)));
        spawns.clear();
        for (String raw : plugin.getConfig().getStringList("arena.spawns")) {
            Location loc = pin(parse(raw));
            if (loc != null) {
                spawns.add(loc);
            }
        }
    }

    public void save() {
        // R2：磁盘上别的键保持现状，只写自己负责的 arena 段
        plugin.reloadConfig();
        // R2：写世界 token（房间实例 → tpl:模板键）；**反查不到模板就跳过不写**，
        // 否则房间一销毁，配置里就留下 room_xxx_1，下次开服该场地永远"未配置"。
        String worldToken = worldToken();
        if (worldToken != null) {
            plugin.getConfig().set("arena.world", worldToken);
        }
        String centerToken = format(center);
        if (centerToken != null) {
            plugin.getConfig().set("arena.center", centerToken);
        }
        plugin.getConfig().set("arena.radius", radius);
        String lobbyToken = format(lobby);
        if (lobbyToken != null) {
            plugin.getConfig().set("arena.lobby", lobbyToken);
        }
        List<String> out = new ArrayList<>();
        for (Location loc : spawns) {
            String token = format(loc);
            if (token != null) {
                out.add(token);
            }
        }
        if (!out.isEmpty()) {
            plugin.getConfig().set("arena.spawns", out);
        }
        plugin.saveConfig();
    }

    /**
     * 存档用的世界 token：房间实例写 {@code tpl:<模板键>}；**反查不到模板时返回 null**
     * （调用方跳过不写）。
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

    public void setCenter(Location location) {
        center = location.getBlock().getLocation();
        worldName = com.hub.world.Worlds.tokenOf(location.getWorld());
    }

    public void setRadius(int radius) {
        this.radius = Math.max(0, Math.min(128, radius));
    }

    public void setLobby(Location location) {
        lobby = location.clone();
    }

    public void addSpawn(Location location) {
        Location loc = location.clone();
        loc.setX(loc.getBlockX() + 0.5);
        loc.setZ(loc.getBlockZ() + 0.5);
        spawns.add(loc);
    }

    public void clearSpawns() {
        spawns.clear();
    }

    /** 在圆周内侧自动生成 count 个出生点（脚站在地面上一格）。 */
    public int generateSpawns(int count) {
        if (center == null) {
            return 0;
        }
        int n = Math.max(1, Math.min(16, count));
        spawns.clear();
        double r = Math.max(1, radius - 2);
        World world = world();
        if (world == null) {
            return 0;
        }
        for (int i = 0; i < n; i++) {
            double angle = Math.PI * 2 * i / n;
            double x = center.getX() + 0.5 + Math.cos(angle) * r;
            double z = center.getZ() + 0.5 + Math.sin(angle) * r;
            double y = floorY() + 1;
            spawns.add(new Location(world, x, y, z, (float) Math.toDegrees(-angle) + 90f, 0f));
        }
        return n;
    }

    /** 圆形判定：按“方块中心”落在半径 + 0.5 的圆内，边缘不会只剩孤立的一格，轮廓更圆滑。 */
    private boolean inCircle(int dx, int dz) {
        double r = radius + 0.5;
        return dx * dx + dz * dz <= r * r;
    }

    /** 把圆形区域铺成草方块，并清空上方若干格。 */
    public void resetFloor() {
        if (!configured()) {
            return;
        }
        World world = world();
        if (world == null) {
            return;
        }
        Material floor = floorMaterial();
        int clear = Math.max(0, plugin.getConfig().getInt("clear-height", 3));
        int r = radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!inCircle(dx, dz)) {
                    continue;
                }
                int x = center.getBlockX() + dx;
                int z = center.getBlockZ() + dz;
                world.getBlockAt(x, floorY(), z).setType(floor, false);
                for (int dy = 1; dy <= clear; dy++) {
                    world.getBlockAt(x, floorY() + dy, z).setType(Material.AIR, false);
                }
            }
        }
    }

    public boolean configured() {
        return center != null && radius >= 1;
    }

    public boolean ready() {
        return configured() && !spawns.isEmpty();
    }

    /**
     * 场地所在世界：**必须和 {@link #center()} / {@link #lobby()} 走同一套解析**，
     * 否则会出现"世界是 A 房、坐标是 B 房"的组合（{@code different world} 就是这么来的）。
     */
    public World world() {
        if (roomWorld != null) {
            return roomWorld;
        }
        if (center != null && center.getWorld() != null) {
            Location resolved = com.hub.room.RoomBinding.apply("landclaim", center);
            return resolved == null ? null : resolved.getWorld();
        }
        return com.hub.world.Worlds.resolve(worldName);
    }

    public String worldName() {
        World world = world();
        return world == null ? worldName : world.getName();
    }

    public Location center() {
        return apply(center == null ? null : center.clone());
    }

    public int radius() {
        return radius;
    }

    public int floorY() {
        return center == null ? 0 : center.getBlockY();
    }

    public Location lobby() {
        return apply(lobby == null ? null : lobby.clone());
    }

    public List<Location> spawns() {
        return List.copyOf(spawns);
    }

    public int totalBlocks() {
        if (!configured()) {
            return 0;
        }
        int total = 0;
        int r = radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (inCircle(dx, dz)) {
                    total++;
                }
            }
        }
        return total;
    }

    /** 方块是否在圆形场地的地面上（y 必须等于地面高度）。 */
    public boolean isFloorBlock(Location location) {
        if (location == null || location.getWorld() == null || center == null) {
            return false;
        }
        if (!location.getWorld().getName().equals(worldName())) {
            return false;
        }
        if (location.getBlockY() != floorY()) {
            return false;
        }
        int dx = location.getBlockX() - center.getBlockX();
        int dz = location.getBlockZ() - center.getBlockZ();
        return inCircle(dx, dz);
    }

    public boolean isClaimedBlock(Block block) {
        Material type = block.getType();
        return type == Material.WHITE_WOOL || type == Material.ORANGE_WOOL
                || type == Material.MAGENTA_WOOL || type == Material.LIGHT_BLUE_WOOL
                || type == Material.YELLOW_WOOL || type == Material.LIME_WOOL
                || type == Material.PINK_WOOL || type == Material.GRAY_WOOL
                || type == Material.LIGHT_GRAY_WOOL || type == Material.CYAN_WOOL
                || type == Material.PURPLE_WOOL || type == Material.BLUE_WOOL
                || type == Material.BROWN_WOOL || type == Material.GREEN_WOOL
                || type == Material.RED_WOOL || type == Material.BLACK_WOOL;
    }

    public Material floorMaterial() {
        Material material = Material.matchMaterial(
                plugin.getConfig().getString("floor-block", "GRASS_BLOCK"), false);
        return material == null || !material.isBlock() ? Material.GRASS_BLOCK : material;
    }

    // ---------- 位置字符串 ----------

    /**
     * 坐标 → 存档 token；**写不了时返回 null**（调用方跳过，保留磁盘上的原值）。
     *
     * <p>原来这里直接写 {@code world.getName()}，房间世界里设场地就会把
     * {@code room_xxx_1} 钉进配置，房间一销毁这份配置就永远"未配置"（真实事故同款）。
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

    public static Location parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 4) {
            return null;
        }
        // R2 读侧（2026-11-04 修）：配置里存的是 tpl:<模板键> 令牌，必须交给 Worlds.resolve
        // 解析成"当前房间实例的世界"；直接 Bukkit.getWorld("tpl:xxx") 永远是 null，
        // 表现就是"房间里这个游戏死活开不了局"（LandClaim/JumpFill 一直卡在这）。
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
