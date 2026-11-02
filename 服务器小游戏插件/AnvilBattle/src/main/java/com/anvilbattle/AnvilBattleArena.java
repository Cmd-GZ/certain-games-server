package com.anvilbattle;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** 单个铁砧掘战场地：X/Y/Z 范围 + 等待点 + 出生点 + 淘汰高度。 */
public final class AnvilBattleArena {

    private final JavaPlugin plugin;
    private Location pos1;
    private Location pos2;
    private Location lobby;
    private final List<Location> spawns = new ArrayList<>();
    private double voidY = -64.0;
    /** 铁砧开始下落的绝对 Y；0 = 自动（柱顶 + anvil.spawn-height）。 */
    private double anvilY = 0.0;

    public AnvilBattleArena(JavaPlugin plugin) {
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
        anvilY = cfg.getDouble("arena.anvil-y", 0.0);
    }

    public void save() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("arena.pos1", encode(pos1));
        cfg.set("arena.pos2", encode(pos2));
        cfg.set("arena.lobby", encode(lobby));
        List<String> encoded = new ArrayList<>();
        for (Location spawn : spawns) {
            encoded.add(encode(spawn));
        }
        cfg.set("arena.spawns", encoded);
        cfg.set("arena.void-y", voidY);
        cfg.set("arena.anvil-y", anvilY);
        plugin.saveConfig();
    }

    /** 两个角已设置且在同一个已加载的世界。 */
    public boolean hasRegion() {
        return pos1 != null && pos2 != null && pos1.getWorld() != null && pos1.getWorld().equals(pos2.getWorld());
    }

    public boolean isReady() {
        return hasRegion() && lobby != null && !spawns.isEmpty();
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
        if (pos1 != null && pos2 != null && pos1.getWorld() != null && !pos1.getWorld().equals(pos2.getWorld())) {
            missing.add("两个角必须在同一世界");
        }
        return missing.isEmpty() ? "无" : String.join("、", missing);
    }

    public Location randomSpawn() {
        if (spawns.isEmpty()) {
            return lobby == null ? null : lobby.clone();
        }
        return spawns.get(ThreadLocalRandom.current().nextInt(spawns.size())).clone();
    }

    /** 旁观者用的场地中心：区域中心，取较高角上方 8 格。 */
    public Location center() {
        if (pos1 == null || pos2 == null || pos1.getWorld() == null) {
            return lobby == null ? null : lobby.clone();
        }
        return new Location(pos1.getWorld(),
                (pos1.getX() + pos2.getX()) / 2.0,
                Math.max(pos1.getY(), pos2.getY()) + 8.0,
                (pos1.getZ() + pos2.getZ()) / 2.0);
    }

    public boolean contains(Location loc) {
        if (!hasRegion() || loc == null || loc.getWorld() == null || !loc.getWorld().equals(pos1.getWorld())) {
            return false;
        }
        int lower = Math.max(loc.getWorld().getMinHeight(), voidLimitY());
        return loc.getBlockX() >= minX() && loc.getBlockX() <= maxX()
                && loc.getBlockY() >= lower && loc.getBlockY() <= maxY()
                && loc.getBlockZ() >= minZ() && loc.getBlockZ() <= maxZ();
    }

    public boolean inColumn(int x, int z) {
        return isReady() && x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
    }

    /**
     * 从区域顶部往下找第一块不是空气、也不是液体的方块。
     * 下界一直放到 void-y 上方（而不是两个角的 minY），这样 OP 站在平台表面上圈地也不会漏掉脚下平台。
     * 整列都空了（只剩空气/液体）返回 {@link Integer#MIN_VALUE}，调用方据此跳过这个位置。
     */
    public int topBlockY(int x, int z) {
        if (!hasRegion()) {
            return Integer.MIN_VALUE;
        }
        World world = pos1.getWorld();
        int lower = Math.max(world.getMinHeight(), voidLimitY());
        if (maxY() < lower) {
            return Integer.MIN_VALUE;
        }
        for (int y = maxY(); y >= lower; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type.isAir() || isLiquid(type)) {
                continue;
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }

    /** 允许落砧/破坏方块的最低 Y（void-y 上方一层）。 */
    public int voidLimitY() {
        return (int) Math.floor(voidY) + 1;
    }

    public int minX() {
        return Math.min(pos1.getBlockX(), pos2.getBlockX());
    }

    public int maxX() {
        return Math.max(pos1.getBlockX(), pos2.getBlockX());
    }

    public int minY() {
        return Math.min(pos1.getBlockY(), pos2.getBlockY());
    }

    public int maxY() {
        return Math.max(pos1.getBlockY(), pos2.getBlockY());
    }

    public int minZ() {
        return Math.min(pos1.getBlockZ(), pos2.getBlockZ());
    }

    public int maxZ() {
        return Math.max(pos1.getBlockZ(), pos2.getBlockZ());
    }

    public World world() {
        return pos1 == null ? null : pos1.getWorld();
    }

    public Location getPos1() {
        return  com.hub.room.RoomBinding.apply("anvilbattle", pos1);
    }

    public void setPos1(Location pos1) {
        this.pos1 = pos1 == null ? null : pos1.clone();
    }

    public Location getPos2() {
        return  com.hub.room.RoomBinding.apply("anvilbattle", pos2);
    }

    public void setPos2(Location pos2) {
        this.pos2 = pos2 == null ? null : pos2.clone();
    }

    public Location getLobby() {
        return  com.hub.room.RoomBinding.apply("anvilbattle", lobby);
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

    public double getAnvilY() {
        return anvilY;
    }

    public void setAnvilY(double anvilY) {
        this.anvilY = anvilY;
    }

    /** 检测落砧位置时不把液体当成“有方块”；水草等水里植物也一并忽略。 */
    public static boolean isLiquid(Material type) {
        return type == Material.WATER || type == Material.LAVA
                || type == Material.BUBBLE_COLUMN
                || type == Material.KELP || type == Material.KELP_PLANT
                || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS;
    }

    private static String encode(Location loc) {        if (loc == null || loc.getWorld() == null) {
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
