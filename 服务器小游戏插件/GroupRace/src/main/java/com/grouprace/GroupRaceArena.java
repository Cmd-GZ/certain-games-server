package com.grouprace;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 群友竞速赛的赛道：等待点、起跑点、出界方框、按顺序排列的圆环。 */
public final class GroupRaceArena {

    private final JavaPlugin plugin;

    /**
     * D22：这个赛道实例**钉死**的房间世界（每个房间一份实例）。
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
        return com.hub.room.RoomBinding.apply("grouprace", loc);
    }

    private Location lobby;
    private Location start;
    private Location pos1;
    private Location pos2;
    private final List<GroupRaceRing> rings = new ArrayList<>();

    public GroupRaceArena(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration cfg = plugin.getConfig();
        lobby = readLocation(cfg, "arena.lobby");
        start = readLocation(cfg, "arena.start");
        pos1 = readLocation(cfg, "arena.pos1");
        pos2 = readLocation(cfg, "arena.pos2");
        rings.clear();
        int index = 0;
        for (Map<?, ?> map : cfg.getMapList("rings")) {
            GroupRaceRing ring = GroupRaceRing.fromMap(index, map);
            if (ring != null) {
                rings.add(ring);
                index++;
            }
        }
    }

    public void save() {
        // 先重读，避免把内存里的旧配置写回去
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        // **一律写字符串**（`tpl:模板,x,y,z`）：房间世界里编辑场地时，写 Location 对象会把
        // `room_xxx_1` 这种房间名钉进配置，房间一销毁这份配置就永远"未配置"（真实事故同款）。
        // 反查不到模板的房间世界（encode 返回 null）**什么都不写**，保留磁盘原值。
        String lobbyToken = com.hub.world.Worlds.encode(lobby);
        if (lobbyToken != null && !lobbyToken.isBlank() && !lobbyToken.startsWith("room_")) {
            cfg.set("arena.lobby", lobbyToken);
        }
        String startToken = com.hub.world.Worlds.encode(start);
        if (startToken != null && !startToken.isBlank() && !startToken.startsWith("room_")) {
            cfg.set("arena.start", startToken);
        }
        String pos1Token = com.hub.world.Worlds.encode(pos1);
        if (pos1Token != null && !pos1Token.isBlank() && !pos1Token.startsWith("room_")) {
            cfg.set("arena.pos1", pos1Token);
        }
        String pos2Token = com.hub.world.Worlds.encode(pos2);
        if (pos2Token != null && !pos2Token.isBlank() && !pos2Token.startsWith("room_")) {
            cfg.set("arena.pos2", pos2Token);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (GroupRaceRing ring : rings) {
            list.add(ring.toMap());
        }
        cfg.set("rings", list);
        plugin.saveConfig();
    }

    public void buildRings(Material normalMaterial, Material finishMaterial) {
        for (GroupRaceRing ring : rings) {
            ring.build(ring.finish() ? finishMaterial : normalMaterial);
        }
    }

    public void clearRings() {
        for (GroupRaceRing ring : rings) {
            ring.clear();
        }
        rings.clear();
    }

    public boolean isReady() {
        return lobby != null && start != null && pos1 != null && pos2 != null
                && rings.size() >= 2 && rings.get(rings.size() - 1).finish()
                && !rings.get(0).finish();
    }

    public String missing() {
        List<String> missing = new ArrayList<>();
        if (lobby == null) {
            missing.add("lobby");
        }
        if (start == null) {
            missing.add("start");
        }
        if (pos1 == null) {
            missing.add("pos1");
        }
        if (pos2 == null) {
            missing.add("pos2");
        }
        if (rings.size() < 2) {
            missing.add("至少 1 个普通环 + 1 个终点环");
        } else if (!rings.get(rings.size() - 1).finish()) {
            missing.add("终点环");
        }
        return missing.isEmpty() ? "无" : String.join("、", missing);
    }

    public boolean inside(Location loc) {
        if (loc == null || pos1 == null || pos2 == null || pos1.getWorld() == null) {
            return false;
        }
        if (loc.getWorld() == null || !loc.getWorld().equals(pos1.getWorld())) {
            return false;
        }
        double minX = Math.min(pos1.getX(), pos2.getX());
        double maxX = Math.max(pos1.getX(), pos2.getX());
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxZ = Math.max(pos1.getZ(), pos2.getZ());
        // 只比较 X/Z：Y 不做限制（等价于自动覆盖世界最低到最高）
        return loc.getX() >= minX && loc.getX() <= maxX
                && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }

    /** 起跑位置：以 start 为中心，沿垂直方向散开；并朝向第一个圆环。 */
    public Location startLocation(int index, int total, double spread) {
        if (start == null) {
            return lobby == null ? null : lobby.clone();
        }
        Location location = start.clone();
        Vector forward = location.getDirection().setY(0.0);
        if (forward.lengthSquared() < 0.001) {
            forward = new Vector(0.0, 0.0, 1.0);
        }
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0.0, forward.getX());
        location.add(right.multiply((index - (total - 1) / 2.0) * spread));
        GroupRaceRing first = firstRing();
        if (first != null) {
            Vector direction = first.center().toVector().subtract(location.toVector());
            if (direction.lengthSquared() > 0.001) {
                location.setDirection(direction);
            }
        }
        return location;
    }

    public GroupRaceRing firstRing() {
        return rings.isEmpty() ? null : rings.get(0);
    }

    public GroupRaceRing lastRing() {
        return rings.isEmpty() ? null : rings.get(rings.size() - 1);
    }

    public GroupRaceRing ring(int index) {
        if (index < 0 || index >= rings.size()) {
            return null;
        }
        return rings.get(index);
    }

    public GroupRaceRing nextRing(int index) {
        return ring(index + 1);
    }

    public List<GroupRaceRing> rings() {
        return rings;
    }

    public Location getLobby() {
        return apply(lobby);
    }

    public void setLobby(Location lobby) {
        this.lobby = lobby == null ? null : lobby.clone();
    }

    public Location getStart() {
        return apply(start);
    }

    public void setStart(Location start) {
        this.start = start == null ? null : start.clone();
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

    /** 兼容旧式 Location 配置与字符串；返回 null 表示未设置/世界未加载。 */
    private static Location readLocation(FileConfiguration cfg, String path) {
        Object value = cfg.get(path);
        if (value instanceof Location location) {
            return location.getWorld() == null ? null : location.clone();
        }
        if (value instanceof String raw && !raw.isBlank()) {
            String[] parts = raw.split(",");
            if (parts.length >= 4) {
                World world = com.hub.world.Worlds.resolve(parts[0]);   // R2：支持 tpl:模板键
                if (world != null) {
                    try {
                        double x = Double.parseDouble(parts[1].trim());
                        double y = Double.parseDouble(parts[2].trim());
                        double z = Double.parseDouble(parts[3].trim());
                        float yaw = parts.length > 4 ? Float.parseFloat(parts[4].trim()) : 0f;
                        float pitch = parts.length > 5 ? Float.parseFloat(parts[5].trim()) : 0f;
                        return new Location(world, x, y, z, yaw, pitch);
                    } catch (NumberFormatException ignored) {
                        // fall through
                    }
                }
            }
        }
        return null;
    }

    /** 用于调试/命令输出。 */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lobby", lobby == null ? "未设置" : lobby.toString());
        out.put("start", start == null ? "未设置" : start.toString());
        out.put("pos1", pos1 == null ? "未设置" : pos1.toString());
        out.put("pos2", pos2 == null ? "未设置" : pos2.toString());
        out.put("rings", rings.size());
        return out;
    }
}
