package com.colorparty;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;

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
 * 色盲派对的场地：地板范围（每回合随机铺彩色混凝土）+ 中心出生点 + 颜色面板 + 淘汰线 + 地图快照。
 * <p>
 * 快照在 OP「保存」时抓（地板范围 + 面板范围内所有非空气方块），格式 `x,y,z;blockdata` 每行一格，
 * 与 TntRun / 建筑猜猜乐一致；每局结束按它把地板和面板重置回保存那一刻。
 */
public class ColorPartyArena {

    public enum State {
        WAITING, RUNNING
    }

    /** 淘汰线未设置的哨兵值：实际淘汰线取「地板最低点 - 2」。 */
    public static final int CUTOFF_UNSET = Integer.MIN_VALUE;

    private String name;
    private Location pos1;
    private Location pos2;
    /** 中心出生点：开局全员站这里（回合中不传送，玩家原地跑向目标颜色）。 */
    private Location spawn;
    private Location lobbyPoint;
    /** 颜色面板两个对角：每回合把面板方块全刷成本回合指定颜色的混凝土。 */
    private Location panelPos1;
    private Location panelPos2;
    private int cutoffY = CUTOFF_UNSET;

    private State state = State.WAITING;
    /** 回合编号（守卫用，单调递增）：让上一回合残留的延迟任务失效。 */
    private long round;
    /** 已进行的回合数（展示用，开局归零）。 */
    private long playedRounds;
    private final List<UUID> players = new ArrayList<>();
    private final List<UUID> eliminated = new ArrayList<>();
    /** 淘汰顺序（先淘汰的在前）：结算时最后淘汰的 = 亚军、倒数第二 = 季军。 */
    private final List<UUID> elimOrder = new ArrayList<>();
    /** 本回合指定颜色（混凝土材质）。 */
    private Material target;
    /** 上一回合指定颜色：选下一回合时尽量避开连续重复。 */
    private Material lastTarget;

    /** OP「保存」时抓的地图快照：key "x,y,z" -> 原方块。 */
    private final Map<String, BlockData> snapshot = new LinkedHashMap<>();

    /** 本回合倒计时任务（每秒动作栏 + 到点塌陷）。 */
    private BukkitTask roundTask;
    /** 「开局首回合 / 塌陷后下一回合」的延迟任务。 */
    private BukkitTask nextTask;

    public ColorPartyArena(String name) {
        this.name = name;
    }

    /** 房间键（D22）：场地名是"哪张地图"，它表示"哪个房间的那一局"。 */
    private String roomKey;

    public String roomKey() {
        return roomKey == null ? getName() : roomKey;
    }

    public void setRoomKey(String roomKey) {
        this.roomKey = roomKey;
    }

    /** 克隆配置部分（范围/出生点/等待点/面板），运行时状态全新。 */
    public ColorPartyArena copyConfig() {
        ColorPartyArena c = new ColorPartyArena(getName());
        c.pos1 = this.pos1;
        c.pos2 = this.pos2;
        c.spawn = this.spawn;
        c.lobbyPoint = this.lobbyPoint;
        c.panelPos1 = this.panelPos1;
        c.panelPos2 = this.panelPos2;
        return c;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Location getPos1() {
        return  com.hub.room.RoomBinding.apply("colorparty", pos1);
    }

    public void setPos1(Location pos1) {
        this.pos1 = pos1;
    }

    public Location getPos2() {
        return  com.hub.room.RoomBinding.apply("colorparty", pos2);
    }

    public void setPos2(Location pos2) {
        this.pos2 = pos2;
    }

    public Location getSpawn() {
        return  com.hub.room.RoomBinding.apply("colorparty", spawn);
    }

    public void setSpawn(Location spawn) {
        this.spawn = spawn;
    }

    public Location getLobbyPoint() {
        return  com.hub.room.RoomBinding.apply("colorparty", lobbyPoint);
    }

    public void setLobbyPoint(Location lobbyPoint) {
        this.lobbyPoint = lobbyPoint;
    }

    public Location getPanelPos1() {
        return  com.hub.room.RoomBinding.apply("colorparty", panelPos1);
    }

    public void setPanelPos1(Location panelPos1) {
        this.panelPos1 = panelPos1;
    }

    public Location getPanelPos2() {
        return  com.hub.room.RoomBinding.apply("colorparty", panelPos2);
    }

    public void setPanelPos2(Location panelPos2) {
        this.panelPos2 = panelPos2;
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

    public boolean hasPanel() {
        return panelPos1 != null && panelPos2 != null;
    }

    /** 实际淘汰线：没单独设置时取地板最低点 - 2（掉出地板 2 格即淘汰）。 */
    public int effectiveCutoff() {
        if (cutoffY != CUTOFF_UNSET) {
            return cutoffY;
        }
        int[] b = floorBounds();
        return b == null ? CUTOFF_UNSET : b[1] - 2;
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

    /** 守卫回合 +1（上一回合残留的延迟任务随之失效）。 */
    public long nextRound() {
        return ++round;
    }

    public long getPlayedRounds() {
        return playedRounds;
    }

    /** 展示回合数 +1（开局归零）。 */
    public long nextPlayed() {
        return ++playedRounds;
    }

    private final java.util.Map<UUID, Long> joinTicks = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> joinSeeds = new java.util.HashMap<>();

    public void recordJoin(UUID id) {
        joinTicks.put(id, (long) org.bukkit.Bukkit.getCurrentTick());
        joinSeeds.put(id, java.util.concurrent.ThreadLocalRandom.current().nextLong());
    }

    public long joinTickOf(UUID id) { return joinTicks.getOrDefault(id, 0L); }
    public long joinSeedOf(UUID id) { return joinSeeds.getOrDefault(id, 0L); }

    public List<UUID> getPlayers() {
        return players;
    }

    public List<UUID> getEliminated() {
        return eliminated;
    }

    public List<UUID> getElimOrder() {
        return elimOrder;
    }

    public Material getTarget() {
        return target;
    }

    public void setTarget(Material target) {
        this.target = target;
    }

    public Material getLastTarget() {
        return lastTarget;
    }

    public void setLastTarget(Material lastTarget) {
        this.lastTarget = lastTarget;
    }

    public BukkitTask getRoundTask() {
        return roundTask;
    }

    public void setRoundTask(BukkitTask roundTask) {
        this.roundTask = roundTask;
    }

    public BukkitTask getNextTask() {
        return nextTask;
    }

    public void setNextTask(BukkitTask nextTask) {
        this.nextTask = nextTask;
    }

    /** 取消本场的两个调度任务（开局 / 结束 / 关服时都调，残留任务由此不可能改地图）。 */
    public void cancelTasks() {
        if (roundTask != null) {
            roundTask.cancel();
            roundTask = null;
        }
        if (nextTask != null) {
            nextTask.cancel();
            nextTask = null;
        }
    }

    // ---------- 范围 ----------

    private static int[] bounds(Location l1, Location l2) {
        if (l1 == null || l2 == null || l1.getWorld() == null || l2.getWorld() == null
                || !l1.getWorld().equals(l2.getWorld())) {
            return null;
        }
        return new int[]{
                Math.min(l1.getBlockX(), l2.getBlockX()),
                Math.min(l1.getBlockY(), l2.getBlockY()),
                Math.min(l1.getBlockZ(), l2.getBlockZ()),
                Math.max(l1.getBlockX(), l2.getBlockX()),
                Math.max(l1.getBlockY(), l2.getBlockY()),
                Math.max(l1.getBlockZ(), l2.getBlockZ())
        };
    }

    /** 地板范围 {minX,minY,minZ,maxX,maxY,maxZ}；没圈好返回 null。 */
    public int[] floorBounds() {
        return bounds(pos1, pos2);
    }

    /** 面板范围；没圈好返回 null。 */
    public int[] panelBounds() {
        return bounds(panelPos1, panelPos2);
    }

    /** 是否在地板范围内（含边界，同世界）。 */
    public boolean contains(Location loc) {
        int[] b = floorBounds();
        if (b == null || loc == null) {
            return false;
        }
        World w = pos1.getWorld();
        if (w == null || !w.equals(loc.getWorld())) {
            return false;
        }
        return loc.getBlockX() >= b[0] && loc.getBlockX() <= b[3]
                && loc.getBlockY() >= b[1] && loc.getBlockY() <= b[4]
                && loc.getBlockZ() >= b[2] && loc.getBlockZ() <= b[5];
    }

    public static String blockKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    // ---------- 地图快照（保存时抓 / 每局结束重置） ----------

    /** 把地板范围 + 面板范围内的非空气方块抓成快照。地板没圈好 / 世界丢了返回 -1。 */
    public int captureSnapshot() {
        int[] b = floorBounds();
        if (b == null || pos1.getWorld() == null) {
            return -1;
        }
        snapshot.clear();
        captureRegion(b, pos1.getWorld());
        if (hasPanel()) {
            int[] pb = panelBounds();
            if (pb != null && panelPos1.getWorld() != null) {
                captureRegion(pb, panelPos1.getWorld());
            }
        }
        return snapshot.size();
    }

    private void captureRegion(int[] b, World w) {
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
    }

    /** 快照文件：plugins/ColorParty/arena-&lt;场名&gt;.snap。 */
    public File snapshotFile(File dataFolder) {
        return new File(dataFolder, "arena-" + name + ".snap");
    }

    public int getSnapshotSize() {
        return snapshot.size();
    }

    public void saveSnapshotFile(File dataFolder) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, BlockData> e : snapshot.entrySet()) {
            sb.append(e.getKey()).append(';').append(e.getValue().getAsString()).append('\n');
        }
        Files.writeString(snapshotFile(dataFolder).toPath(), sb.toString(), StandardCharsets.UTF_8);
    }

    /** 读回快照文件（每行 `x,y,z;blockdata`）。坏了抛 IllegalStateException，由调用方记日志。 */
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
     * 按快照重置地板 + 面板：快照里没有的格子（本局刷的混凝土）一律清成空气。
     * 只写"现在和快照不一致"的格子。
     *
     * @return 实际重写的格数；没有快照 / 范围没配好时返回 -1
     */
    public int restoreSnapshot() {
        int[] b = floorBounds();
        if (snapshot.isEmpty() || b == null || pos1.getWorld() == null) {
            return -1;
        }
        // 世界已经卸载（房间在对局结束时被销毁）时不能碰方块：chunk system 已关，
        // 读方块会抛 IllegalStateException（真实事故：关服时 1 条 ERROR 把冒烟断言搅浑，
        // 这条在 TntRun 上修过一次，ColorParty 是同一个坑）。
        if (org.bukkit.Bukkit.getWorld(pos1.getWorld().getName()) == null) {
            return 0;
        }
        BlockData air = Material.AIR.createBlockData();
        int changed = restoreRegion(b, pos1.getWorld(), air);
        if (hasPanel()) {
            int[] pb = panelBounds();
            if (pb != null && panelPos1.getWorld() != null) {
                changed += restoreRegion(pb, panelPos1.getWorld(), air);
            }
        }
        return changed;
    }

    private int restoreRegion(int[] b, World w, BlockData air) {
        int changed = 0;
        for (int x = b[0]; x <= b[3]; x++) {
            for (int y = b[1]; y <= b[4]; y++) {
                for (int z = b[2]; z <= b[5]; z++) {
                    BlockData t = snapshot.getOrDefault(blockKey(x, y, z), air);
                    Block block = w.getBlockAt(x, y, z);
                    if (!block.getBlockData().equals(t)) {
                        block.setBlockData(t, false);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    // ---------- 配置 ----------

    public void save(ConfigurationSection sec) {
        com.hub.world.Worlds.writeLocation(sec, "pos1", pos1);
        com.hub.world.Worlds.writeLocation(sec, "pos2", pos2);
        com.hub.world.Worlds.writeLocation(sec, "spawn", spawn);
        com.hub.world.Worlds.writeLocation(sec, "lobby", lobbyPoint);
        com.hub.world.Worlds.writeLocation(sec, "panel-pos1", panelPos1);
        com.hub.world.Worlds.writeLocation(sec, "panel-pos2", panelPos2);
        if (cutoffY != CUTOFF_UNSET) {
            sec.set("cutoff-y", cutoffY);
        }
    }

    public static ColorPartyArena load(String name, ConfigurationSection sec) {
        ColorPartyArena a = new ColorPartyArena(name);
        a.pos1 = com.hub.world.Worlds.readLocation(sec, "pos1");
        a.pos2 = com.hub.world.Worlds.readLocation(sec, "pos2");
        a.spawn = com.hub.world.Worlds.readLocation(sec, "spawn");
        a.lobbyPoint = com.hub.world.Worlds.readLocation(sec, "lobby");
        a.panelPos1 = com.hub.world.Worlds.readLocation(sec, "panel-pos1");
        a.panelPos2 = com.hub.world.Worlds.readLocation(sec, "panel-pos2");
        a.cutoffY = sec.contains("cutoff-y") ? sec.getInt("cutoff-y") : CUTOFF_UNSET;
        return a;
    }
}
