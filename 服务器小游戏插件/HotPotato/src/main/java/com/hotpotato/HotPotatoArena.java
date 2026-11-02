package com.hotpotato;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 一个烫手山芋场：范围 + 中央点。 */
public class HotPotatoArena {

    public enum State {
        WAITING, RUNNING
    }

    private String name;
    private Location pos1;
    private Location pos2;
    private Location center;
    private Location lobbyPoint;

    // 运行时
    private State state = State.WAITING;
    private final List<UUID> players = new ArrayList<>();       // 存活玩家
    private final List<UUID> eliminated = new ArrayList<>();     // 已淘汰（观察者）
    private final List<UUID> bombHolders = new ArrayList<>();   // 当前 TNT 持有者
    private final List<UUID> elimOrder = new ArrayList<>();     // 淘汰顺序
    private long roundEndMs;

    public HotPotatoArena(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /** 改名（场地重命名时由管理器调用；倒计时任务内 getName() 会随之返回新名）。 */
    public void setName(String name) {
        this.name = name;
    }

    public Location getPos1() {
        return  com.hub.room.RoomBinding.apply("hotpotato", pos1);
    }

    public void setPos1(Location pos1) {
        this.pos1 = pos1;
    }

    public Location getPos2() {
        return  com.hub.room.RoomBinding.apply("hotpotato", pos2);
    }

    public void setPos2(Location pos2) {
        this.pos2 = pos2;
    }

    public Location getCenter() {
        return  com.hub.room.RoomBinding.apply("hotpotato", center);
    }

    public void setCenter(Location center) {
        this.center = center;
    }

    public Location getLobbyPoint() {
        return  com.hub.room.RoomBinding.apply("hotpotato", lobbyPoint);
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

    /** D21：加入 tick 与同 tick 内随机名次。 */
    private final java.util.Map<UUID, Long> joinTicks = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> joinSeeds = new java.util.HashMap<>();

    public void recordJoin(UUID id) {
        joinTicks.put(id, (long) org.bukkit.Bukkit.getCurrentTick());
        joinSeeds.put(id, java.util.concurrent.ThreadLocalRandom.current().nextLong());
    }

    public long joinTickOf(UUID id) {
        return joinTicks.getOrDefault(id, 0L);
    }

    public long joinSeedOf(UUID id) {
        return joinSeeds.getOrDefault(id, 0L);
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

    public List<UUID> getBombHolders() {
        return bombHolders;
    }

    public boolean isBombHolder(UUID id) {
        return bombHolders.contains(id);
    }

    public long getRoundEndMs() {
        return roundEndMs;
    }

    public void setRoundEndMs(long roundEndMs) {
        this.roundEndMs = roundEndMs;
    }

    /** 保存。 */
    public void save(ConfigurationSection sec) {
        // R2：走 Worlds.writeLocation —— 房间世界的坐标写回 tpl:<模板>，绝不把房间世界名写进磁盘
        com.hub.world.Worlds.writeLocation(sec, "pos1", pos1);
        com.hub.world.Worlds.writeLocation(sec, "pos2", pos2);
        com.hub.world.Worlds.writeLocation(sec, "center", center);
        com.hub.world.Worlds.writeLocation(sec, "lobby", lobbyPoint);
    }

    public static HotPotatoArena load(String name, ConfigurationSection sec) {
        HotPotatoArena a = new HotPotatoArena(name);
        // 既认老的序列化 Location，也认 tpl:<模板>,x,y,z 令牌（模板/房间作用域）
        a.pos1 = com.hub.world.Worlds.readLocation(sec, "pos1");
        a.pos2 = com.hub.world.Worlds.readLocation(sec, "pos2");
        a.center = com.hub.world.Worlds.readLocation(sec, "center");
        a.lobbyPoint = com.hub.world.Worlds.readLocation(sec, "lobby");
        return a;
    }
}
