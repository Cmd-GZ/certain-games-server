package com.tntrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个雪塔大作战场地：范围 + 出生点 + 淘汰线高度 + 最上层中心 + 地图快照。
 * <p>
 * 「圈地后保存地图」= {@link #captureSnapshot()} 把 pos1..pos2 内的非空气方块存成
 * {@code arena-<场名>.snap}；每局结束 {@link #restoreSnapshot()} 按它把地形重置回保存那一刻
 * （挖掉的雪补回、本局新放的方块清成空气）。快照格式与建筑猜猜乐一致（`x,y,z;blockdata` 每行一格）。
 */
public class TntRunArena {

    public enum State {
        WAITING, RUNNING
    }

    /** 半径 5 的圆上的 12 个整点（按角度升序）：MC 里只有这些格子的中心正好落在半径 5 上。 */
    private static final int[][] CIRCLE_R5 = {
            {5, 0}, {4, 3}, {3, 4}, {0, 5}, {-3, 4}, {-4, 3},
            {-5, 0}, {-4, -3}, {-3, -4}, {0, -5}, {3, -4}, {4, -3}
    };

    /** 开局站位圆半径（格）。 */
    public static final int SPAWN_RADIUS = 5;

    private String name;
    private Location pos1;
    private Location pos2;
    private Location spawn;
    private Location lobbyPoint;
    /** 场地最上层中心（配置模式"中心点"工具 / /tr center 设置）：开局站位的圆心。 */
    private Location center;
    private int cutoffY;

    /** 建场"保存"时抓的地图快照：key "x,y,z" -> 原方块；每局结束用它重置地图。 */
    private final Map<String, BlockData> snapshot = new LinkedHashMap<>();
    /** 本局每名玩家的开局点（圆边缘那一格），重生也回自己那一格；只在内存里。 */
    private final Map<UUID, Location> roundSpawns = new LinkedHashMap<>();

    // 运行时
    private State state = State.WAITING;
    /** 局次编号：每局 +1，用来让上一局残留的"延迟消失"任务不再改地图。 */
    private long round;
    private final List<UUID> players = new ArrayList<>();
    private final List<UUID> eliminated = new ArrayList<>();
    private final List<UUID> elimOrder = new ArrayList<>();
    /** 本局消失的方块：key "x,y,z" -> 原材质（SNOW_BLOCK）。没有快照的老场地用它兜底恢复。 */
    private final Map<String, Material> broken = new LinkedHashMap<>();
    /** 已调度即将消失的方块 key（踩塌的雪柱 + 雪球砸出的洞共用），避免重复调度。 */
    private final java.util.Set<String> pendingFade = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** 房间键（D22）：场地名是"哪张地图"，它表示"哪个房间的那一局"。 */
    private String roomKey;

    public String roomKey() {
        return roomKey == null ? getName() : roomKey;
    }

    public void setRoomKey(String roomKey) {
        this.roomKey = roomKey;
    }

    /**
     * 克隆"配置部分"（D22 多房间）：键 + 坐标 + 剪断层 + **共享同一份地图快照**（只读）。
     * 本局状态（玩家/淘汰/踩掉的方块）全部是新的。
     */
    public TntRunArena copyConfig() {
        TntRunArena c = new TntRunArena(getName());
        c.pos1 = this.pos1;
        c.pos2 = this.pos2;
        c.spawn = this.spawn;
        c.lobbyPoint = this.lobbyPoint;
        c.center = this.center;
        c.cutoffY = this.cutoffY;
        c.snapshot.putAll(this.snapshot);   // 快照只读，共享内容即可
        return c;
    }

    public TntRunArena(String name) {
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
        return  com.hub.room.RoomBinding.apply("tntrun", pos1);
    }

    public void setPos1(Location pos1) {
        this.pos1 = pos1;
    }

    public Location getPos2() {
        return  com.hub.room.RoomBinding.apply("tntrun", pos2);
    }

    public void setPos2(Location pos2) {
        this.pos2 = pos2;
    }

    public Location getSpawn() {
        return  com.hub.room.RoomBinding.apply("tntrun", spawn);
    }

    public void setSpawn(Location spawn) {
        this.spawn = spawn;
    }

    public Location getLobbyPoint() {
        return  com.hub.room.RoomBinding.apply("tntrun", lobbyPoint);
    }

    public void setLobbyPoint(Location lobbyPoint) {
        this.lobbyPoint = lobbyPoint;
    }

    /** 最上层中心（开局站位圆心）；没设置时返回 null，开局退回出生点。 */
    public Location getCenter() {
        return  com.hub.room.RoomBinding.apply("tntrun", center);
    }

    public void setCenter(Location center) {
        this.center = center == null ? null : center.clone();
    }

    public int getCutoffY() {
        return cutoffY;
    }

    public void setCutoffY(int cutoffY) {
        this.cutoffY = cutoffY;
    }

    public boolean isReady() {
        return pos1 != null && pos2 != null && spawn != null;
    }

    /** 垂直范围内判定（水平+垂直都在 pos1..pos2 内）。 */
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

    public long getRound() {
        return round;
    }

    /** 开新一局：局次 +1（上一局残留的延迟任务随之失效）。 */
    public long nextRound() {
        return ++round;
    }

    /** D21：加入时的 tick 与同 tick 内的随机名次（用来在同时进入时随机决出主持人）。 */
    private final java.util.Map<UUID, Long> joinTicks = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> joinSeeds = new java.util.HashMap<>();

    /** 记下这次加入的 tick 与随机名次（由 manager 在入队时调用）。 */
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

    public Map<String, Material> getBroken() {
        return broken;
    }

    public java.util.Set<String> getPendingFade() {
        return pendingFade;
    }

    /** 本局每名玩家的开局站位（圆边缘）。 */
    public Map<UUID, Location> getRoundSpawns() {
        return roundSpawns;
    }

    public static String blockKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    // ---------- 开局站位：半径 5 的圆 ----------

    /**
     * 以「最上层中心」为圆心、半径 {@value #SPAWN_RADIUS} 格的圆边缘，把 count 名玩家尽量平均分配：
     * 按角度均分后吸附到最近的圆上整点（保证每个人都正好站在半径 5 的格子上，不会互相重叠）；
     * 超过 12 人时圆上整点不够，退化为按角度四舍五入。
     * <p>
     * 中心没设置 / 世界丢了 / count ≤ 0 → 返回空表，调用方回退到出生点。
     */
    public List<Location> circleSpawns(int count) {
        List<Location> out = new ArrayList<>();
        if (center == null || center.getWorld() == null || count <= 0) {
            return out;
        }
        World w = center.getWorld();
        int cx = center.getBlockX();
        // 中心点记的是"玩家脚下那一格"，所以它的 Y 就是圆上玩家的脚底高度（站在最上层雪面上）
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        boolean[] used = new boolean[CIRCLE_R5.length];
        for (int i = 0; i < count; i++) {
            double ideal = 2 * Math.PI * i / count;
            int dx;
            int dz;
            if (count <= CIRCLE_R5.length) {
                int best = -1;
                double bestDiff = Double.MAX_VALUE;
                for (int j = 0; j < CIRCLE_R5.length; j++) {
                    if (used[j]) {
                        continue;
                    }
                    double diff = Math.abs(angleGap(Math.atan2(CIRCLE_R5[j][1], CIRCLE_R5[j][0]), ideal));
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        best = j;
                    }
                }
                used[best] = true;
                dx = CIRCLE_R5[best][0];
                dz = CIRCLE_R5[best][1];
            } else {
                dx = (int) Math.round(SPAWN_RADIUS * Math.cos(ideal));
                dz = (int) Math.round(SPAWN_RADIUS * Math.sin(ideal));
            }
            Location loc = new Location(w, cx + 0.5 + dx, cy, cz + 0.5 + dz);
            loc.setDirection(new Vector(-dx, 0, -dz)); // 面向圆心，开局看得到雪塔中央
            out.add(loc);
        }
        return out;
    }

    /**
     * 半径 5 的圆上有几个点落在圈地范围的水平之外（>0 = 场地太小，开局的玩家会一脚踩空）。
     * 范围没配好 / 中心没设时返回 0。
     */
    public int circleOutOfRange() {
        if (center == null || pos1 == null || pos2 == null) {
            return 0;
        }
        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
        int bad = 0;
        for (int[] o : CIRCLE_R5) {
            int x = center.getBlockX() + o[0];
            int z = center.getBlockZ() + o[1];
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                bad++;
            }
        }
        return bad;
    }

    /** 两个角度的最小夹角（弧度，恒为非负）。 */
    private static double angleGap(double a, double b) {
        double d = (a - b) % (2 * Math.PI);
        if (d > Math.PI) {
            d -= 2 * Math.PI;
        }
        if (d < -Math.PI) {
            d += 2 * Math.PI;
        }
        return Math.abs(d);
    }

    // ---------- 地图快照（圈地后保存地图 / 每局结束重置地图） ----------

    private boolean hasRegion() {
        return pos1 != null && pos2 != null && pos1.getWorld() != null;
    }

    /** {minX,minY,minZ,maxX,maxY,maxZ}；范围没配好返回 null。 */
    private int[] bounds() {
        if (!hasRegion()) {
            return null;
        }
        return new int[]{
                Math.min(pos1.getBlockX(), pos2.getBlockX()),
                Math.min(pos1.getBlockY(), pos2.getBlockY()),
                Math.min(pos1.getBlockZ(), pos2.getBlockZ()),
                Math.max(pos1.getBlockX(), pos2.getBlockX()),
                Math.max(pos1.getBlockY(), pos2.getBlockY()),
                Math.max(pos1.getBlockZ(), pos2.getBlockZ())
        };
    }

    /** 快照文件：plugins/TntRun/arena-&lt;场名&gt;.snap。 */
    public File snapshotFile(File dataFolder) {
        return new File(dataFolder, "arena-" + name + ".snap");
    }

    public boolean hasSnapshot() {
        return !snapshot.isEmpty();
    }

    public int getSnapshotSize() {
        return snapshot.size();
    }

    /**
     * 把 pos1..pos2 内所有非空气方块抓成快照（圈地后"保存"时调用）。
     *
     * @return 快照格数；范围没配好 / 世界丢了返回 -1
     */
    public int captureSnapshot() {
        snapshot.clear();
        int[] b = bounds();
        if (b == null) {
            return -1;
        }
        World w = pos1.getWorld();
        for (int x = b[0]; x <= b[3]; x++) {
            for (int y = b[1]; y <= b[4]; y++) {
                for (int z = b[2]; z <= b[5]; z++) {
                    BlockData data = w.getBlockAt(x, y, z).getBlockData();
                    if (!data.getMaterial().isAir()) {
                        snapshot.put(blockKey(x, y, z), data);
                    }
                }
            }
        }
        return snapshot.size();
    }

    public void saveSnapshotFile(File dataFolder) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, BlockData> e : snapshot.entrySet()) {
            sb.append(e.getKey()).append(';').append(e.getValue().getAsString()).append('\n');
        }
        Files.writeString(snapshotFile(dataFolder).toPath(), sb.toString(), StandardCharsets.UTF_8);
    }

    /** 读回快照文件（格式：每行 `x,y,z;blockdata`）。文件坏了会抛 IllegalStateException，由调用方记日志。 */
    public void loadSnapshotFile(File file) {
        snapshot.clear();
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int semi = line.indexOf(';');
                if (semi <= 0) {
                    continue;
                }
                String[] xyz = line.substring(0, semi).split(",");
                if (xyz.length != 3) {
                    continue;
                }
                String key = blockKey(Integer.parseInt(xyz[0].trim()),
                        Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
                snapshot.put(key, Bukkit.createBlockData(line.substring(semi + 1)));
            }
        } catch (Exception ex) {
            snapshot.clear();
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    /**
     * 按快照重置地图：快照里没有的格子（本局塌出来的洞、玩家新放的方块）一律清成空气。
     * 只写"现在和快照不一致"的格子，所以地图没被破坏时几乎不产生写入。
     *
     * @return 实际重写的格数；没有快照 / 范围没配好时返回 -1（调用方回退到 broken 逐格还原）
     */
    public int restoreSnapshot() {
        int[] b = bounds();
        if (snapshot.isEmpty() || b == null) {
            return -1;
        }
        World w = pos1.getWorld();
        // 世界已经卸载（房间在对局结束时被销毁）时不能碰方块：chunk system 已关，
        // 读方块会抛 IllegalStateException（真实事故：关服时 1 条 ERROR 把回归断言搅浑）。
        if (w == null || org.bukkit.Bukkit.getWorld(w.getName()) == null) {
            return 0;
        }
        BlockData air = Material.AIR.createBlockData();
        int changed = 0;
        for (int x = b[0]; x <= b[3]; x++) {
            for (int z = b[2]; z <= b[5]; z++) {
                for (int y = b[1]; y <= b[4]; y++) {
                    BlockData target = snapshot.get(blockKey(x, y, z));
                    if (target == null) {
                        target = air;
                    }
                    Block block = w.getBlockAt(x, y, z);
                    if (block.getBlockData().equals(target)) {
                        continue;
                    }
                    block.setBlockData(target, false);
                    changed++;
                }
            }
        }
        return changed;
    }

    public void save(ConfigurationSection sec) {
        com.hub.world.Worlds.writeLocation(sec, "pos1", pos1);
        com.hub.world.Worlds.writeLocation(sec, "pos2", pos2);
        com.hub.world.Worlds.writeLocation(sec, "spawn", spawn);
        com.hub.world.Worlds.writeLocation(sec, "lobby", lobbyPoint);
        com.hub.world.Worlds.writeLocation(sec, "center", center);
        sec.set("cutoff-y", cutoffY);
    }

    public static TntRunArena load(String name, ConfigurationSection sec) {
        TntRunArena a = new TntRunArena(name);
        a.pos1 = com.hub.world.Worlds.readLocation(sec, "pos1");
        a.pos2 = com.hub.world.Worlds.readLocation(sec, "pos2");
        a.spawn = com.hub.world.Worlds.readLocation(sec, "spawn");
        a.lobbyPoint = com.hub.world.Worlds.readLocation(sec, "lobby");
        a.center = com.hub.world.Worlds.readLocation(sec, "center");
        a.cutoffY = sec.getInt("cutoff-y");
        return a;
    }
}
