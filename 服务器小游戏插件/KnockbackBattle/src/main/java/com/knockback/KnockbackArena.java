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

    /** 读坐标的世界解析：钉死的房间世界优先，没钉才退回按上下文的换绑。 */
    private Location apply(Location loc) {
        if (loc == null) {
            return null;
        }
        if (roomWorld != null && !roomWorld.equals(loc.getWorld())) {
            return new Location(roomWorld, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        }
        return com.hub.room.RoomBinding.apply("knockback", loc);
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
        pos1 = parse(cfg.getString("arena.pos1"));
        pos2 = parse(cfg.getString("arena.pos2"));
        lobby = parse(cfg.getString("arena.lobby"));
        spawns.clear();
        for (String raw : cfg.getStringList("arena.spawns")) {
            Location loc = parse(raw);
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
            return lobby == null ? null : lobby.clone();
        }
        return spawns.get(ThreadLocalRandom.current().nextInt(spawns.size())).clone();
    }

    /** 旁观者用的场地中心（取两个对角的 X/Z 中点，Y 取较高角上方 3 格）。 */
    public Location center() {
        if (pos1 == null || pos2 == null || pos1.getWorld() == null) {
            return lobby == null ? null : lobby.clone();
        }
        return new Location(pos1.getWorld(),
                (pos1.getX() + pos2.getX()) / 2.0,
                Math.max(pos1.getY(), pos2.getY()) + 3.0,
                (pos1.getZ() + pos2.getZ()) / 2.0);
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
