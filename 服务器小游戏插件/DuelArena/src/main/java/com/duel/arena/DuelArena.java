package com.duel.arena;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 单个单挑场：范围（不允许离开）、两个出生点、共用物品配置、运行时对战状态。 */
public class DuelArena {

    public enum State {
        WAITING,   // 等待玩家加入
        FIGHTING   // 对战中
    }

    private String name;
    private Location pos1;
    private Location pos2;
    private Location spawn1;
    private Location manualLobby;
    private Location spawn2;
    private Kit kit = new Kit();

    // 运行时状态（不持久化）
    private State state = State.WAITING;
    private final List<UUID> queue = new ArrayList<>();
    private UUID fighter1;
    private UUID fighter2;
    private long fightStartMs;

    public DuelArena(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /** 改名（场地重命名时由管理器调用；倒计时任务内 getName() 会随之返回新名）。 */
    public void setName(String name) {
        this.name = name;
    }

    /** D21：加入 tick 与同 tick 内随机名次。 */
    private final java.util.Map<UUID, Long> joinTicks = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> joinSeeds = new java.util.HashMap<>();

    public void recordJoin(UUID id) {
        joinTicks.put(id, (long) org.bukkit.Bukkit.getCurrentTick());
        joinSeeds.put(id, java.util.concurrent.ThreadLocalRandom.current().nextLong());
    }

    public long joinTickOf(UUID id) { return joinTicks.getOrDefault(id, 0L); }
    public long joinSeedOf(UUID id) { return joinSeeds.getOrDefault(id, 0L); }

    /**
     * 房间化（R1.5）的**临时世界覆盖**：不为 null 时，所有读取到的坐标都换成这个世界。
     *
     * <p>为什么不用"直接把 Location 的 world 改掉"：那样插件保存配置时会把房间世界名
     * 写进磁盘，房间一删，下次启动 `Location.deserialize` 就报错（真实事故：
     * `world_key: minecraft:room_sx` 的场地会让开服直接抛 4 行 ERROR、区域数从 25 掉到 24）。
     * 覆盖只影响读取，**存储值永远是原样**。
     */
    private transient org.bukkit.World boundWorld;

    /** 绑定到房间世界（传 null 解除绑定）。 */
    public void bindWorld(org.bukkit.World w) {
        this.boundWorld = w;
    }

    public org.bukkit.World boundWorld() {
        return boundWorld;
    }

    /** 读取坐标时套用临时世界覆盖。 */
    private Location apply(Location loc) {
        if (loc == null || boundWorld == null || boundWorld.equals(loc.getWorld())) {
            return loc;
        }
        return new Location(boundWorld, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    public Location getPos1() {
        return apply(pos1);
    }

    public void setPos1(Location pos1) {
        this.pos1 = pos1;
    }

    public Location getPos2() {
        return apply(pos2);
    }

    public void setPos2(Location pos2) {
        this.pos2 = pos2;
    }

    public Location getSpawn1() {
        return apply(spawn1);
    }

    public void setSpawn1(Location spawn1) {
        this.spawn1 = spawn1;
    }

    public Location getSpawn2() {
        return apply(spawn2);
    }

    public void setSpawn2(Location spawn2) {
        this.spawn2 = spawn2;
    }

    public Kit getKit() {
        return kit;
    }

    public void setKit(Kit kit) {
        this.kit = kit;
    }

    /** 配置是否齐全（范围 + 两个出生点）。 */
    public boolean isReady() {
        return pos1 != null && pos2 != null && spawn1 != null && spawn2 != null;
    }

    /** 立方体范围（含边界）。 */
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

    public Location getManualLobby() { return apply(manualLobby); }
    public void setManualLobby(Location l) { this.manualLobby = l; }

    /** 等待点：手动设置优先，否则范围中心上方。 */
    public Location getLobbyPoint() {
        if (manualLobby != null) return manualLobby;
        Location a = pos1 == null ? spawn1 : pos1;
        Location b = pos2 == null ? spawn2 : pos2;
        if (a == null) {
            a = spawn1;
            b = spawn2;
        }
        if (a == null) {
            return null;
        }
        int x = (a.getBlockX() + b.getBlockX()) / 2;
        int y = Math.max(a.getBlockY(), b.getBlockY()) + 3;
        int z = (a.getBlockZ() + b.getBlockZ()) / 2;
        return new Location(a.getWorld(), x + 0.5, y, z + 0.5, 0, 0);
    }

    // ---------- 运行时状态 ----------

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public List<UUID> getQueue() {
        return queue;
    }

    public UUID getFighter1() {
        return fighter1;
    }

    public void setFighter1(UUID fighter1) {
        this.fighter1 = fighter1;
    }

    public UUID getFighter2() {
        return fighter2;
    }

    public void setFighter2(UUID fighter2) {
        this.fighter2 = fighter2;
    }

    public long getFightStartMs() {
        return fightStartMs;
    }

    public void setFightStartMs(long fightStartMs) {
        this.fightStartMs = fightStartMs;
    }

    /** 对方玩家。 */
    public UUID opponentOf(UUID id) {
        if (id.equals(fighter1)) {
            return fighter2;
        }
        if (id.equals(fighter2)) {
            return fighter1;
        }
        return null;
    }

    /** 玩家是否在本场对战中。 */
    public boolean isFighter(UUID id) {
        return id.equals(fighter1) || id.equals(fighter2);
    }

    /** 保存场配置到配置段。 */
    public void save(ConfigurationSection sec) {
        sec.set("pos1", pos1);
        sec.set("pos2", pos2);
        sec.set("spawn1", spawn1);
        sec.set("manual-lobby", manualLobby);
        sec.set("spawn2", spawn2);
        kit.save(com.hub.world.Worlds.getOrCreate(sec, "kit"));
    }

    /** 从配置段读取。 */
    public static DuelArena load(String name, ConfigurationSection sec) {
        DuelArena a = new DuelArena(name);
        a.pos1 = (Location) sec.get("pos1");
        a.pos2 = (Location) sec.get("pos2");
        a.spawn1 = (Location) sec.get("spawn1");
        a.manualLobby = (Location) sec.get("manual-lobby");
        a.spawn2 = (Location) sec.get("spawn2");
        a.kit = Kit.load(sec.getConfigurationSection("kit"));
        return a;
    }
}
