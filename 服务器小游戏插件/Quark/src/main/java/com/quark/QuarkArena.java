package com.quark;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** 单个场地：X/Z 范围 + 等待点 + 出生点。 */
public final class QuarkArena {

    private final JavaPlugin plugin;
    private Location pos1;

    /** 供区域声明用（{@code docs/框架不变量.md} I2）。 */
    public Location getPos1() {
        return pos1;
    }
    private Location pos2;

    /** 供区域声明用（{@code docs/框架不变量.md} I2）。 */
    public Location getPos2() {
        return pos2;
    }
    private Location lobby;
    private final List<Location> spawns = new ArrayList<>();
    private double voidY = -64.0;

    public QuarkArena(JavaPlugin plugin) {
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
        // 先重读，避免把内存里的旧配置写回去（和 TNTBattle 的踩坑一致）
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        // R2：读不出来（场地未就绪）时**什么都不写**，绝不能把磁盘上已有的坐标擦成 ''
        if (pos1 != null) {
            cfg.set("arena.pos1", encode(pos1));
        }
        // R2：读不出来（场地未就绪）时**什么都不写**，绝不能把磁盘上已有的坐标擦成 ''
        if (pos2 != null) {
            cfg.set("arena.pos2", encode(pos2));
        }
        // R2：读不出来（场地未就绪）时**什么都不写**，绝不能把磁盘上已有的坐标擦成 ''
        if (lobby != null) {
            cfg.set("arena.lobby", encode(lobby));
        }
        List<String> encoded = new ArrayList<>();
        for (Location spawn : spawns) {
            encoded.add(encode(spawn));
        }
        cfg.set("arena.spawns", encoded);
        plugin.saveConfig();
    }

    public boolean isReady() {
        return pos1 != null && pos2 != null && lobby != null && !spawns.isEmpty();
    }

    /** 还缺哪些必须项，用于提示管理员。 */
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

    public boolean inside(Location loc) {
        if (loc == null || pos1 == null || pos2 == null) {
            return false;
        }
        World world = pos1.getWorld();
        if (world == null || loc.getWorld() == null || !world.equals(loc.getWorld())) {
            return false;
        }
        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
        return loc.getX() >= minX && loc.getX() <= maxX + 1.0
                && loc.getZ() >= minZ && loc.getZ() <= maxZ + 1.0;
    }

    public Location randomSpawn() {
        if (spawns.isEmpty()) {
            return lobby == null ? null : lobby.clone();
        }
        return spawns.get(ThreadLocalRandom.current().nextInt(spawns.size())).clone();
    }

    public void setPos1(Location pos1) {
        this.pos1 = pos1 == null ? null : pos1.clone();
    }

    public void setPos2(Location pos2) {
        this.pos2 = pos2 == null ? null : pos2.clone();
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

    public double voidY() {
        return voidY;
    }

    private static String encode(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.1f,%.1f",
                com.hub.world.Worlds.tokenOf(loc.getWorld()), loc.getX(), loc.getY(), loc.getZ(),
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
