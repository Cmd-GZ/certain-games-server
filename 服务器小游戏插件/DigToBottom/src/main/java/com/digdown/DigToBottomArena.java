package com.digdown;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个「一掘到底」场地：圆心（= 柱子最底层方块的 Y）、等待点、观战点、
 * 环形坑位数与半径，以及本局运行时数据。
 * <p>
 * 几何：{@code lanes} 个坑位均匀分布在一个半径 {@code radius} 的圆上，
 * 每个坑位是一根 1×1 的柱子，四周和上方用玻璃围住；最底下垫一格基岩。
 */
public class DigToBottomArena {

    public enum State {
        WAITING, RUNNING
    }

    /**
     * D22：这个场地实例**钉死**的房间世界（每个房间一份实例）。
     *
     * <p>{@link com.hub.room.RoomBinding} 是"按游戏一个槽"，两房间同时跑时后一个覆盖前一个 ——
     * 甲房的实例会读到乙房的世界（人被传错房间）。钉死之后读取只认自己的世界。
     */
    private World roomWorld;

    /** D22：房间键（有房间时 = 房间 id；没有房间时就是场地名本身）。 */
    private String roomKey;

    public void bindWorld(World world) {
        this.roomWorld = world;
    }

    public World boundWorld() {
        return roomWorld;
    }

    /** 房间键：场地名是"哪张地图"，它表示"哪个房间的那一局"。 */
    public String roomKey() {
        return roomKey == null || roomKey.isBlank() ? name : roomKey;
    }

    public void setRoomKey(String roomKey) {
        this.roomKey = roomKey;
    }

    /**
     * 读坐标时的世界解析：**钉死了就只认自己的房间世界**，没钉才退回按上下文的换绑。
     *
     * <p>钉死之后**不能**再回落 {@link com.hub.room.RoomBinding}：它先看"派对委派"、再看
     * "该游戏唯一的房间"，两者都是**按游戏一个槽**；两房间同时跑时会给出另一个房间的世界。
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
        return com.hub.room.RoomBinding.apply("digdown", loc);
    }

    /**
     * **把解析出来的坐标重新钉回自己的房间世界**（读 config 时用）。
     *
     * <p>`tpl:` 的解析看"当前房间上下文"，如果那次上下文指向**别的**房间，解析结果就会落到
     * 别人的世界里。钉回去之后，房间实例无论如何重载都只认自己的世界。
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

    /**
     * 克隆"配置部分"（圆心/等待点/观战点/坑位数/半径/建场标记），**本局运行时状态全新**。
     *
     * <p>为什么不是直接复用同一份：`arenas` 表是"地图定义"，同一张图可以被两个房间同时开 ——
     * 共用一份对象就等于两个房间共用玩家列表/进度/计时，必然串房。
     */
    public DigToBottomArena copyConfig() {
        DigToBottomArena c = new DigToBottomArena(name);
        c.center = this.center;
        c.lobby = this.lobby;
        c.spectator = this.spectator;
        c.lanes = this.lanes;
        c.radius = this.radius;
        c.built = this.built;
        c.builtHeight = this.builtHeight;
        return c;
    }

    private String name;
    /** 圆心；这里的 Y 就是每一根柱子最底层方块的 Y。 */
    private Location center;
    private Location lobby;
    private Location spectator;
    private int lanes = 8;
    private int radius = 8;
    /** /dig build 成功生成过玻璃与基岩。 */
    private boolean built;
    /** 建场时的柱高（config 的 pillar.height），柱高改了要重新 build。 */
    private int builtHeight;

    // ---------- 运行时 ----------
    private State state = State.WAITING;
    /** 局次；上一局残留的延迟任务靠它失效。 */
    private long round;
    /** 还在挖的玩家（等待中也在这里）。 */
    private final List<UUID> players = new ArrayList<>();
    /** 本局已挖到底的玩家：UUID -> 完成时间（毫秒）。 */
    private final Map<UUID, Long> finished = new LinkedHashMap<>();
    /** 本局每人分到的坑位序号。 */
    private final Map<UUID, Integer> laneOf = new LinkedHashMap<>();
    /** 本局每人挖掉的方块数（时间到按深度排名用）。 */
    private final Map<UUID, Integer> mined = new LinkedHashMap<>();
    /** 本局参与者的名字（离线也能播报）。 */
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private boolean frozen;
    /** 冻结刚开始的几毫秒内放过移动事件，避免把开局传送又顶回去。 */
    private long freezeGraceUntil;
    /** 本局正式计时开始的时刻（倒计时结束后），完成时间按它算用时。 */
    private long roundStartMs;
    private long gameEndMs;
    private boolean shortened;

    public DigToBottomArena(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Location getCenter() {
        return apply(center);
    }

    public void setCenter(Location center) {
        this.center = center == null ? null : center.clone();
        this.built = false;
    }

    public Location getLobby() {
        return apply(lobby);
    }

    public void setLobby(Location lobby) {
        this.lobby = lobby == null ? null : lobby.clone();
    }

    public Location getSpectator() {
        return apply(spectator);
    }

    public void setSpectator(Location spectator) {
        this.spectator = spectator == null ? null : spectator.clone();
    }

    public int getLanes() {
        return lanes;
    }

    public void setLanes(int lanes) {
        this.lanes = Math.max(1, lanes);
        this.built = false;
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = Math.max(1, radius);
        this.built = false;
    }

    public boolean isBuilt() {
        return built;
    }

    public void setBuilt(boolean built) {
        this.built = built;
    }

    public int getBuiltHeight() {
        return builtHeight;
    }

    public void setBuiltHeight(int builtHeight) {
        this.builtHeight = builtHeight;
    }

    /** 圆心、坑位数、半径都齐了（玻璃可能还没建）。 */
    public boolean hasGeometry() {
        return center != null && center.getWorld() != null && lanes >= 1 && radius >= 1;
    }

    /** 可以等待/开局：几何 + 等待点 + 已建场。 */
    public boolean isReady() {
        return hasGeometry() && lobby != null && built;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public long getRound() {
        return round;
    }

    public long nextRound() {
        return ++round;
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

    public Map<UUID, Long> getFinished() {
        return finished;
    }

    public Map<UUID, Integer> getLaneOf() {
        return laneOf;
    }

    public Map<UUID, Integer> getMined() {
        return mined;
    }

    public Map<UUID, String> getNames() {
        return names;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public long getFreezeGraceUntil() {
        return freezeGraceUntil;
    }

    public void setFreezeGraceUntil(long timeMs) {
        this.freezeGraceUntil = timeMs;
    }

    public long getRoundStartMs() {
        return roundStartMs;
    }

    public void setRoundStartMs(long roundStartMs) {
        this.roundStartMs = roundStartMs;
    }

    public long getGameEndMs() {
        return gameEndMs;
    }

    public void setGameEndMs(long gameEndMs) {
        this.gameEndMs = gameEndMs;
    }

    public boolean isShortened() {
        return shortened;
    }

    public void setShortened(boolean shortened) {
        this.shortened = shortened;
    }

    /** 本局还在挖的人数 + 已完成人数（不含中途退出的）。 */
    public int totalPlayers() {
        return players.size() + finished.size();
    }

    /** 清空本局运行时数据（不动 waiting 的 players）。 */
    public void resetRoundState() {
        finished.clear();
        laneOf.clear();
        mined.clear();
        names.clear();
        frozen = false;
        freezeGraceUntil = 0L;
        roundStartMs = 0L;
        gameEndMs = 0L;
        shortened = false;
    }

    public void save(ConfigurationSection sec) {
        com.hub.world.Worlds.writeLocation(sec, "center", center);
        com.hub.world.Worlds.writeLocation(sec, "lobby", lobby);
        com.hub.world.Worlds.writeLocation(sec, "spectator", spectator);
        sec.set("lanes", lanes);
        sec.set("radius", radius);
        sec.set("built", built);
        sec.set("built-height", builtHeight);
    }

    public static DigToBottomArena load(String name, ConfigurationSection sec) {
        DigToBottomArena a = new DigToBottomArena(name);
        if (sec == null) {
            return a;
        }
        // D22：读出来的坐标**钉回本房间的世界**（`tpl:` 解析看上下文，可能指到别人的房间）
        a.center = a.pin(com.hub.world.Worlds.readLocation(sec, "center"));
        a.lobby = a.pin(com.hub.world.Worlds.readLocation(sec, "lobby"));
        a.spectator = a.pin(com.hub.world.Worlds.readLocation(sec, "spectator"));
        a.lanes = Math.max(1, sec.getInt("lanes", 8));
        a.radius = Math.max(1, sec.getInt("radius", 8));
        a.built = sec.getBoolean("built", false);
        a.builtHeight = sec.getInt("built-height", 0);
        return a;
    }
}
