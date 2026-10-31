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
    private final File snapshotFile;
    private final Map<String, String> snapshot = new LinkedHashMap<>();

    private String worldName = "world";
    private Location water1;

    /** 供区域声明用（{@code docs/框架不变量.md} I2）。 */
    public Location getPos1() {
        return water1;
    }
    private Location water2;

    /** 供区域声明用（{@code docs/框架不变量.md} I2）。 */
    public Location getPos2() {
        return water2;
    }
    private Location lobby;
    private final List<Location> starts = new ArrayList<>();
    private String snapshotWorld;

    public JumpFillArena(JumpFillPlugin plugin) {
        this.plugin = plugin;
        this.snapshotFile = new File(plugin.getDataFolder(), "arena.snapshot.yml");
    }

    public void load() {
        worldName = plugin.getConfig().getString("arena.world", "world");
        water1 = parse(plugin.getConfig().getString("arena.water-pos1", null));
        water2 = parse(plugin.getConfig().getString("arena.water-pos2", null));
        if (water1 != null && water1.getWorld() != null) {
            worldName = water1.getWorld().getName();
        }
        lobby = parse(plugin.getConfig().getString("arena.lobby", null));
        starts.clear();
        for (String raw : plugin.getConfig().getStringList("arena.start-points")) {
            Location loc = parse(raw);
            if (loc != null) {
                starts.add(loc);
            }
        }
        loadSnapshot();
    }

    public void save() {
        plugin.getConfig().set("arena.world", worldName());
        plugin.getConfig().set("arena.water-pos1", format(water1));
        plugin.getConfig().set("arena.water-pos2", format(water2));
        plugin.getConfig().set("arena.lobby", format(lobby));
        List<String> out = new ArrayList<>();
        for (Location loc : starts) {
            out.add(format(loc));
        }
        plugin.getConfig().set("arena.start-points", out);
        plugin.saveConfig();
    }

    public void setWaterCorner(int index, Location location) {
        Location block = location.getBlock().getLocation();
        worldName = block.getWorld().getName();
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

    public World world() {
        if (water1 != null && water1.getWorld() != null) {
            return water1.getWorld();
        }
        return Bukkit.getWorld(worldName);
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
        return lobby == null ? null : lobby.clone();
    }

    public Location water1() {
        return water1 == null ? null : water1.clone();
    }

    public Location water2() {
        return water2 == null ? null : water2.clone();
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
        if (!snapshotFile.isFile()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(snapshotFile);
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
            data.save(snapshotFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("保存水域快照失败: " + exception.getMessage());
        }
    }

    private void deleteSnapshot() {
        try {
            Files.deleteIfExists(snapshotFile.toPath());
        } catch (IOException ignored) {
            // 下次开局会覆盖
        }
    }

    // ---------- 位置字符串 ----------

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
}
