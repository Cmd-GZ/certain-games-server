package com.murdermystery;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个密室杀手场地：范围 + 中心点 + 等待点。
 * 运行时数据：存活玩家（含杀手/侦探/猎人/无辜者）、已淘汰者、杀手/侦探/猎人列表、击杀数、
 * 飞剑冷却、近战刀冷却、侦探领石时间。
 */
public class MurderMysteryArena {

    public enum State {
        WAITING, RUNNING
    }

    public enum Role {
        MURDERER, DETECTIVE, HUNTER, INNOCENT, NONE
    }

    private String name;
    private Location pos1;
    private Location pos2;
    private Location center;
    private Location lobbyPoint;

    // 运行时
    private State state = State.WAITING;
    private final List<UUID> players = new ArrayList<>();       // 存活玩家（杀手 + 无辜者）
    private final List<UUID> eliminated = new ArrayList<>();     // 已淘汰（观战者）
    private final List<UUID> elimOrder = new ArrayList<>();      // 淘汰顺序
    private final List<UUID> murderers = new ArrayList<>();      // 本局杀手
    private final List<UUID> detectives = new ArrayList<>();     // 本局侦探（无辜者阵营，定期白得真理之石）
    private final List<UUID> hunters = new ArrayList<>();        // 本局猎人（无辜者阵营，开局 1 把无限箭的弓）
    private final Map<UUID, Integer> kills = new LinkedHashMap<>();   // 杀手击杀数
    private final Map<UUID, Long> throwCooldown = new LinkedHashMap<>(); // 杀手飞剑冷却（ms 时间戳）
    private final Map<UUID, Long> knifeCooldown = new LinkedHashMap<>(); // 杀手近战刀冷却（ms 时间戳）
    private final Map<UUID, Long> detectiveStoneAt = new LinkedHashMap<>(); // 侦探上次领真理之石的时间戳
    private final Map<UUID, UUID> compassTargets = new LinkedHashMap<>(); // 杀手 -> 指南针当前锁定的存活无辜者
    private long gameEndMs;

    public MurderMysteryArena(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /** 改名（场地重命名时由管理器调用）。 */
    public void setName(String name) {
        this.name = name;
    }

    public Location getPos1() {
        return pos1;
    }

    public void setPos1(Location pos1) {
        this.pos1 = pos1;
    }

    public Location getPos2() {
        return pos2;
    }

    public void setPos2(Location pos2) {
        this.pos2 = pos2;
    }

    public Location getCenter() {
        return center;
    }

    public void setCenter(Location center) {
        this.center = center;
    }

    public Location getLobbyPoint() {
        return lobbyPoint;
    }

    public void setLobbyPoint(Location lobbyPoint) {
        this.lobbyPoint = lobbyPoint;
    }

    public boolean isReady() {
        return pos1 != null && pos2 != null && center != null;
    }

    public boolean contains(Location loc) {
        if (!isReady() || loc == null || !loc.getWorld().equals(pos1.getWorld())) {
            return false;
        }
        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
        int maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
        return loc.getBlockX() >= minX && loc.getBlockX() <= maxX
                && loc.getBlockY() >= minY && loc.getBlockY() <= maxY
                && loc.getBlockZ() >= minZ && loc.getBlockZ() <= maxZ;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public List<UUID> getPlayers() {
        return players;
    }

    public List<UUID> getEliminated() {
        return eliminated;
    }

    public List<UUID> getElimOrder() {
        return elimOrder;
    }

    public List<UUID> getMurderers() {
        return murderers;
    }

    public boolean isMurderer(UUID id) {
        return murderers.contains(id);
    }

    public List<UUID> getDetectives() {
        return detectives;
    }

    /** 是不是本局侦探（侦探属于无辜者阵营，胜负判定按无辜者算）。 */
    public boolean isDetective(UUID id) {
        return detectives.contains(id);
    }

    public List<UUID> getHunters() {
        return hunters;
    }

    /** 是不是本局猎人（猎人属于无辜者阵营，胜负判定按无辜者算）。 */
    public boolean isHunter(UUID id) {
        return hunters.contains(id);
    }

    public Map<UUID, Long> getDetectiveStoneAt() {
        return detectiveStoneAt;
    }

    /** 杀手 -> 指南针当前锁定的存活无辜者（目标死亡后由管理器自动换下一个）。 */
    public Map<UUID, UUID> getCompassTargets() {
        return compassTargets;
    }

    public Map<UUID, Integer> getKills() {
        return kills;
    }

    public Map<UUID, Long> getThrowCooldown() {
        return throwCooldown;
    }

    /** 杀手近战刀冷却（UUID -> 可再次出手的时间戳 ms）。 */
    public Map<UUID, Long> getKnifeCooldown() {
        return knifeCooldown;
    }

    public long getGameEndMs() {
        return gameEndMs;
    }

    public void setGameEndMs(long gameEndMs) {
        this.gameEndMs = gameEndMs;
    }

    /** 当前存活的无辜者数量（不含杀手）。 */
    public int innocentsLeft() {
        int n = 0;
        for (UUID id : players) {
            if (!murderers.contains(id)) {
                n++;
            }
        }
        return n;
    }

    /** 保存到配置段。 */
    public void save(ConfigurationSection sec) {
        writeLoc(sec, "pos1", pos1);
        writeLoc(sec, "pos2", pos2);
        writeLoc(sec, "center", center);
        writeLoc(sec, "lobby", lobbyPoint);
    }

    /**
     * 写一个坐标（R2 坐标解耦）。
     *
     * <p>仍然用 Bukkit 原生 {@code Location} 序列化（YAML 结构不变），但把 {@code world} 字段
     * 换成世界 token：如果当前在房间实例世界里，写 {@code tpl:<模板键>} 而不是临时房间名，
     * 否则房间一删配置就指向不存在的世界。
     */
    private static void writeLoc(ConfigurationSection sec, String key, Location loc) {
        if (loc == null) {
            // ⚠️ **绝不写 null**。
            // 场地没加载成功（比如模板作用域没有存活房间）时，pos1/pos2 是 null；
            // 如果这里写 null，关服/重载时的 saveAll() 就会把磁盘上的坐标**擦掉** —— 实测会把
            // 4 个场地全写成 `{}`，配置彻底丢失。"读不出来" 必须表现为"不动它"。
            return;
        }
        sec.set(key, loc);
        if (loc.getWorld() != null) {
            String token = com.hub.world.Worlds.tokenOf(loc.getWorld());
            if (!token.equals(loc.getWorld().getName())) {
                // Paper 26.2（Moonrise）序列化成 world_key: minecraft:xxx；老版本是 world: xxx
                // 哪个存在就改哪个，别凭空造字段。
                if (sec.isSet(key + ".world_key")) {
                    sec.set(key + ".world_key", token);
                } else {
                    sec.set(key + ".world", token);
                }
            }
        }
    }

    /**
     * 读一个坐标（R2 坐标解耦）。
     *
     * <p>Bukkit 反序列化 {@code Location} 时用的是 {@code world:} 里的字符串直接查世界；
     * 若那里是 {@code tpl:<模板键>}，Bukkit 找不到世界会给回一个 world 为 null 的 Location。
     * 这里再补一次解析：拿到真实世界就绑上，拿不到（模板没有存活房间）就返回 null，
     * 让上层按"场地未就绪"处理。
     */
    private static Location readLoc(ConfigurationSection sec, String key) {
        // 先看世界 token：如果是 tpl: 就自己按子键拼 Location，
        // **绝不能** 走 sec.get(key) —— Paper 的 Location.deserialize 在找不到世界时会抛异常并刷 ERROR。
        String token = sec.getString(key + ".world_key");
        if (token == null) {
            token = sec.getString(key + ".world");   // 老格式回落
        }
        if (token != null && com.hub.world.Worlds.isTemplateToken(token)) {
            org.bukkit.World world = com.hub.world.Worlds.resolve(token);
            if (world == null) {
                return null;   // 模板还没有存活房间 → 场地未就绪
            }
            double x = sec.getDouble(key + ".x");
            double y = sec.getDouble(key + ".y");
            double z = sec.getDouble(key + ".z");
            float yaw = (float) sec.getDouble(key + ".yaw");
            float pitch = (float) sec.getDouble(key + ".pitch");
            return new Location(world, x, y, z, yaw, pitch);
        }
        // 普通世界名：交给 Bukkit 正常反序列化
        return (Location) sec.get(key);
    }

    public static MurderMysteryArena load(String name, ConfigurationSection sec) {
        MurderMysteryArena a = new MurderMysteryArena(name);
        a.pos1 = readLoc(sec, "pos1");
        a.pos2 = readLoc(sec, "pos2");
        a.center = readLoc(sec, "center");
        a.lobbyPoint = readLoc(sec, "lobby");
        return a;
    }
}
