package com.jumpfill;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 单场地的水域 / 高台出发点 / 等待点，以及水域方块快照。 */
public final class JumpFillArena {

    private final JumpFillPlugin plugin;
    private final Map<String, String> snapshot = new LinkedHashMap<>();

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
        return com.hub.room.RoomBinding.apply("jumpfill", loc);
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
    private Location water1;

    /** 供区域声明用（{@code docs/框架不变量.md} I2）。 */
    public Location getPos1() {
        return apply(water1);
    }
    private Location water2;

    /** 供区域声明用（{@code docs/框架不变量.md} I2）。 */
    public Location getPos2() {
        return apply(water2);
    }
    private Location lobby;
    private final List<Location> starts = new ArrayList<>();
    private String snapshotWorld;

    public JumpFillArena(JumpFillPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 水域快照文件：**按世界名一份**（D22，2026-11-05）。
     *
     * <p>以前是每插件一份 `arena.snapshot.yml`：两场派对同时开局时，后一局建快照会把前一局那份覆盖，
     * 于是 A 房收场时按 B 房的方块数据还原 → 把别人的水域改掉。房间世界的名字各不相同
     * （`room_xxx_1`），按世界名分文件即可彻底隔开；没有世界名时退回老文件名（兼容单机/老配置）。
     */
    private File snapshotFile() {
        String name = worldName();
        if (name == null || name.isBlank()) {
            return new File(plugin.getDataFolder(), "arena.snapshot.yml");
        }
        String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        return new File(plugin.getDataFolder(), "arena.snapshot." + safe + ".yml");
    }

    public void load() {
        worldName = plugin.getConfig().getString("arena.world", "world");
        // D22：解析出来的坐标**钉回本房间的世界**（`tpl:` 解析看上下文，可能指到别人的房间）
        water1 = pin(parse(plugin.getConfig().getString("arena.water-pos1", null)));
        water2 = pin(parse(plugin.getConfig().getString("arena.water-pos2", null)));
        if (water1 != null && water1.getWorld() != null) {
            worldName = com.hub.world.Worlds.tokenOf(water1.getWorld());
        }
        lobby = pin(parse(plugin.getConfig().getString("arena.lobby", null)));
        starts.clear();
        for (String raw : plugin.getConfig().getStringList("arena.start-points")) {
            Location loc = pin(parse(raw));
            if (loc != null) {
                starts.add(loc);
            }
        }
        loadSnapshot();
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
        String water1Token = format(water1);
        if (water1Token != null) {
            plugin.getConfig().set("arena.water-pos1", water1Token);
        }
        String water2Token = format(water2);
        if (water2Token != null) {
            plugin.getConfig().set("arena.water-pos2", water2Token);
        }
        String lobbyToken = format(lobby);
        if (lobbyToken != null) {
            plugin.getConfig().set("arena.lobby", lobbyToken);
        }
        List<String> out = new ArrayList<>();
        for (Location loc : starts) {
            String token = format(loc);
            if (token != null) {
                out.add(token);
            }
        }
        if (!out.isEmpty()) {
            plugin.getConfig().set("arena.start-points", out);
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

    public void setWaterCorner(int index, Location location) {
        Location block = location.getBlock().getLocation();
        worldName = com.hub.world.Worlds.tokenOf(block.getWorld());
        if (index == 1) {
            water1 = block;
        } else {
            water2 = block;
        }
    }

    public void setLobby(Location location) {
        lobby = location.clone();
    }

    public void addStart(Location location) {
        Location loc = location.clone();
        loc.setX(loc.getBlockX() + 0.5);
        loc.setZ(loc.getBlockZ() + 0.5);
        starts.add(loc);
    }

    public void clearStarts() {
        starts.clear();
    }

    public boolean waterConfigured() {
        return world() != null && water1 != null && water2 != null
                && water1.getWorld().getName().equals(water2.getWorld().getName())
                && water1.getBlockY() == water2.getBlockY();
    }

    public boolean ready() {
        return waterConfigured() && !starts.isEmpty();
    }

    /**
     * 场地所在世界：**必须和 {@link #water1()} / {@link #water2()} / {@link #lobby()} 走同一套解析**，
     * 否则会出现"世界是 A 房、坐标是 B 房"的组合（{@code different world} 就是这么来的）。
     */
    public World world() {
        if (roomWorld != null) {
            return roomWorld;
        }
        if (water1 != null) {
            Location resolved = com.hub.room.RoomBinding.apply("jumpfill", water1);
            return resolved == null ? null : resolved.getWorld();
        }
        return com.hub.world.Worlds.resolve(worldName);
    }

    public String worldName() {
        World world = world();
        return world == null ? worldName : world.getName();
    }

    public int waterY() {
        return water1 == null ? 0 : water1.getBlockY();
    }

    public int minX() {
        return Math.min(water1.getBlockX(), water2.getBlockX());
    }

    public int maxX() {
        return Math.max(water1.getBlockX(), water2.getBlockX());
    }

    public int minZ() {
        return Math.min(water1.getBlockZ(), water2.getBlockZ());
    }

    public int maxZ() {
        return Math.max(water1.getBlockZ(), water2.getBlockZ());
    }

    public int width() {
        return waterConfigured() ? maxX() - minX() + 1 : 0;
    }

    public int depth() {
        return waterConfigured() ? maxZ() - minZ() + 1 : 0;
    }

    public int volume() {
        return width() * depth();
    }

    public int missY() {
        return waterY() - 3;
    }

    public boolean containsBlock(Location location) {
        if (location == null || location.getWorld() == null || !waterConfigured()) {
            return false;
        }
        return location.getWorld().getName().equals(worldName())
                && location.getBlockY() == waterY()
                && location.getBlockX() >= minX() && location.getBlockX() <= maxX()
                && location.getBlockZ() >= minZ() && location.getBlockZ() <= maxZ();
    }

    /** 水域内的水不允许流动，否则被填掉的格子会被邻格水重新灌满。 */
    public boolean affectsWaterFlow(Location from, Location to) {
        return containsBlock(from) || containsBlock(to);
    }

    public List<Location> starts() {
        return List.copyOf(starts);
    }

    public Location lobby() {
        return apply(lobby == null ? null : lobby.clone());
    }

    public Location water1() {
        return apply(water1 == null ? null : water1.clone());
    }

    public Location water2() {
        return apply(water2 == null ? null : water2.clone());
    }

    /** 等待/开局时把水域刷成水；第一次会先把原始方块存快照。 */
    public void fillWater() {
        if (!waterConfigured()) {
            return;
        }
        ensureSnapshot();
        World world = world();
        BlockData water = Material.WATER.createBlockData();
        for (int x = minX(); x <= maxX(); x++) {
            for (int z = minZ(); z <= maxZ(); z++) {
                world.getBlockAt(x, waterY(), z).setBlockData(water, false);
            }
        }
    }

    public void setGlass(Location location, Material material) {
        Block block = location.getBlock();
        block.setType(material, false);
    }

    /** 游戏结束/停服时把水域恢复成开局前的样子。 */
    public void restore() {
        if (snapshot.isEmpty()) {
            return;
        }
        World world = snapshotWorld == null ? world() : Bukkit.getWorld(snapshotWorld);
        if (world != null) {
            for (Map.Entry<String, String> entry : snapshot.entrySet()) {
                String[] parts = entry.getKey().split(",");
                if (parts.length != 3) {
                    continue;
                }
                try {
                    int x = Integer.parseInt(parts[0]);
                    int y = Integer.parseInt(parts[1]);
                    int z = Integer.parseInt(parts[2]);
                    world.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(entry.getValue()), false);
                } catch (IllegalArgumentException ignored) {
                    // 损坏的快照行跳过
                }
            }
        }
        snapshot.clear();
        snapshotWorld = null;
        deleteSnapshot();
    }

    /** 场地被重新配置时丢弃旧快照，避免把新场地恢复成旧方块。 */
    public void discardSnapshot() {
        snapshot.clear();
        snapshotWorld = null;
        deleteSnapshot();
    }

    // ---------- 快照 ----------

    private void ensureSnapshot() {
        if (!snapshot.isEmpty()) {
            return;
        }
        loadSnapshot();
        if (!snapshot.isEmpty()) {
            if (snapshotWorld != null && !snapshotWorld.equals(worldName())) {
                snapshot.clear();
                snapshotWorld = null;
                deleteSnapshot();
            } else {
                return;
            }
        }
        snapshotWorld = worldName();
        World world = world();
        if (world == null) {
            return;
        }
        for (int x = minX(); x <= maxX(); x++) {
            for (int z = minZ(); z <= maxZ(); z++) {
                Block block = world.getBlockAt(x, waterY(), z);
                snapshot.put(x + "," + waterY() + "," + z, block.getBlockData().getAsString());
            }
        }
        saveSnapshot();
    }

    private void loadSnapshot() {
        if (!snapshotFile().isFile()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(snapshotFile());
        snapshotWorld = data.getString("world");
        ConfigurationSection blocks = data.getConfigurationSection("blocks");
        if (blocks == null) {
            return;
        }
        for (String key : blocks.getKeys(false)) {
            snapshot.put(key, blocks.getString(key, "minecraft:air"));
        }
    }

    private void saveSnapshot() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("world", snapshotWorld);
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            data.set("blocks." + entry.getKey(), entry.getValue());
        }
        try {
            data.save(snapshotFile());
        } catch (IOException exception) {
            plugin.getLogger().warning("保存水域快照失败: " + exception.getMessage());
        }
    }

    private void deleteSnapshot() {
        try {
            Files.deleteIfExists(snapshotFile().toPath());
        } catch (IOException ignored) {
            // 下次开局会覆盖
        }
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
