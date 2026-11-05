package com.knockback;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** 单个击退大战场地：X/Z 范围 + 等待点 + 出生点 + 淘汰高度。 */
public final class KnockbackArena {

    private final JavaPlugin plugin;

    /**
     * D22：这个场地实例**钉死**的房间世界（每个房间一份实例）。
     *
     * <p>{@link com.hub.room.RoomBinding} 是"按游戏一个槽"，两房间同时跑时后一个覆盖前一个 ——
     * 甲房的实例会读到乙房的世界（人被传错房间）。钉死之后读取只认自己的世界。
     */
    private org.bukkit.World roomWorld;

    public void bindWorld(org.bukkit.World world) {
        this.roomWorld = world;
    }

    public org.bukkit.World boundWorld() {
        return roomWorld;
    }

    /**
     * 读坐标的世界解析：**钉死了就只认自己的房间世界**，没钉才退回按上下文的换绑。
     *
     * <p>钉死之后**不能**再回落 {@link com.hub.room.RoomBinding}：它先看"派对委派"、再看
     * "该游戏唯一的房间"，两者都是**按游戏一个槽**；两个房间同时跑时它会给出**另一个房间**
     * 的世界，于是同一份代码里 {@code arena.world()}（自己的世界）和 {@code arena.center()}
     * （别人的世界）对不上，{@code World#getNearbyEntities(loc, …)} 直接抛
     * {@code IllegalArgumentException: Location cannot be in a different world}。
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
        return com.hub.room.RoomBinding.apply("knockback", loc);
    }

    /**
     * **把解析出来的坐标重新钉回自己的房间世界**（{@code load()} 用）。
     *
     * <p>{@code tpl:} 的解析看"当前房间上下文"，而 {@code /hub join} 会让 HubCore 给所有子游戏
     * 发一次 reload；如果那次上下文指向**别的**房间，解析结果就会落到别人的世界里。钉回去之后，
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

    private Location pos1;
    private Location pos2;
    private Location lobby;
    private final List<Location> spawns = new ArrayList<>();
    private double voidY = -64.0;

    public KnockbackArena(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration cfg = plugin.getConfig();
        // 解析完一律 pin 回**自己钉死的房间世界**（没钉世界的默认实例行为不变）
        pos1 = pin(parse(cfg.getString("arena.pos1")));
        pos2 = pin(parse(cfg.getString("arena.pos2")));
        lobby = pin(parse(cfg.getString("arena.lobby")));
        spawns.clear();
        for (String raw : cfg.getStringList("arena.spawns")) {
            Location loc = pin(parse(raw));
            if (loc != null) {
                spawns.add(loc);
            }
        }
        voidY = cfg.getDouble("arena.void-y", -64.0);
    }

    public void save() {
        // 先重读，避免把内存里的旧配置写回去
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        // R2：读不出来（场地未就绪）时**什么都不写**，绝不能把磁盘上已有的坐标擦成 ''
        String pos1Token = encode(pos1);
        if (pos1Token != null) {
            cfg.set("arena.pos1", pos1Token);
        }
        // R2：读不出来（场地未就绪）时**什么都不写**，绝不能把磁盘上已有的坐标擦成 ''
        String pos2Token = encode(pos2);
        if (pos2Token != null) {
            cfg.set("arena.pos2", pos2Token);
        }
        // R2：读不出来（场地未就绪）时**什么都不写**，绝不能把磁盘上已有的坐标擦成 ''
        String lobbyToken = encode(lobby);
        if (lobbyToken != null) {
            cfg.set("arena.lobby", lobbyToken);
        }
        List<String> encoded = new ArrayList<>();
        for (Location spawn : spawns) {
            String token = encode(spawn);
            if (token != null) {
                encoded.add(token);
            }
        }
        if (!encoded.isEmpty()) {
            cfg.set("arena.spawns", encoded);
        }
        cfg.set("arena.void-y", voidY);
        plugin.saveConfig();
    }

    public boolean isReady() {
        return pos1 != null && pos2 != null && lobby != null && !spawns.isEmpty();
    }

    public String missing() {
        List<String> missing = new ArrayList<>();
        if (pos1 == null) {
            missing.add("pos1");
        }
        if (pos2 == null) {
            missing.add("pos2");
        }
        if (lobby == null) {
            missing.add("lobby");
        }
        if (spawns.isEmpty()) {
            missing.add("spawns");
        }
        return missing.isEmpty() ? "无" : String.join("、", missing);
    }

    public Location randomSpawn() {
        if (spawns.isEmpty()) {
            Location fallback = getLobby();
            return fallback == null ? null : fallback.clone();
        }
        // 走 apply（同源解析世界）后**再 clone**：apply 在同世界时返回的是存储实例本身
        Location spawn = apply(spawns.get(ThreadLocalRandom.current().nextInt(spawns.size())));
        return spawn == null ? null : spawn.clone();
    }

    /**
     * 场地所在世界：**必须和 {@link #getPos1()} / {@link #getLobby()} / {@link #center()}
     * 走同一套解析**，否则会出现"世界是 A 房、坐标是 B 房"的组合
     * （{@code World#getNearbyEntities} 直接抛 {@code Location cannot be in a different world}）。
     */
    public World world() {
        if (roomWorld != null) {
            return roomWorld;
        }
        Location resolved = com.hub.room.RoomBinding.apply("knockback", pos1);
        return resolved == null ? null : resolved.getWorld();
    }

    /** 旁观者用的场地中心（取两个对角的 X/Z 中点，Y 取较高角上方 3 格）。 */
    public Location center() {
        Location p1 = apply(pos1);
        Location p2 = apply(pos2);
        World world = world();
        if (p1 == null || p2 == null || world == null) {
            Location fallback = apply(lobby);
            return fallback == null ? null : fallback.clone();
        }
        return new Location(world,
                (p1.getX() + p2.getX()) / 2.0,
                Math.max(p1.getY(), p2.getY()) + 3.0,
                (p1.getZ() + p2.getZ()) / 2.0);
    }

    public Location getPos1() {
        return apply(pos1);
    }

    public void setPos1(Location pos1) {
        this.pos1 = pos1 == null ? null : pos1.clone();
    }

    public Location getPos2() {
        return apply(pos2);
    }

    public void setPos2(Location pos2) {
        this.pos2 = pos2 == null ? null : pos2.clone();
    }

    public Location getLobby() {
        return apply(lobby);
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

    public double getVoidY() {
        return voidY;
    }

    public void setVoidY(double voidY) {
        this.voidY = voidY;
    }

    /**
     * 坐标 → 存档 token；**写不了时返回 null**（调用方跳过，保留磁盘上的原值）。
     *
     * <p>房间世界名（{@code room_xxx_1}）一旦写进配置，房间销毁后这份配置就永远"未配置"了
     * （真实事故同款）。反查不到模板时 {@link com.hub.world.Worlds#tokenOf} 会退回原始房间名，
     * 那种情况一律不写。
     */
    private static String encode(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        String token = com.hub.world.Worlds.tokenOf(loc.getWorld());
        if (token == null || token.isBlank() || token.startsWith("room_")) {
            return null;
        }
        return String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.1f,%.1f",
                token, loc.getX(), loc.getY(), loc.getZ(),
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
        World world = com.hub.world.Worlds.resolve(parts[0]);   // R2：支持 tpl:模板键
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
