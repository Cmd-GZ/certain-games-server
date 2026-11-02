package com.buildrun;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 你建我跑的场地：一块「模板」长方体（含地板/建造空间）+ 衍生方向/间距 + 出生点/等待点，
 * 同时存放一局游戏的全部运行期状态（与 ColorParty 一样，配置和状态放一个类里）。
 * <p>
 * 游戏开始时按玩家人数沿 {@code direction} 依次复制出 N 块场地：第 i 块（i 从 1 开始）
 * 偏移 i × (该方向长度 + gap) 格；模板本身永远不参与建造，保持原样。
 */
public class BuildRunArena {

    /** 场地阶段：等待 / 复制场地 / 建造 / 轮流跑 / 收尾清理。 */
    public enum Phase {
        WAITING, COPYING, BUILDING, RUN, ENDING
    }

    /** 合法衍生方向。 */
    public static final String[] DIRECTIONS = {"+x", "-x", "+z", "-z"};

    private String name;
    private Location pos1;
    private Location pos2;
    /** 衍生方向：复制体朝这个方向依次排开。 */
    private String direction = "+x";
    /** 相邻两块场地之间的额外间隔（格）。 */
    private int gap;
    /** 每块场地的出生点（模板坐标，复制体按偏移量平移）。 */
    private Location spawn;
    /** 等待点（加入后站的地方）。 */
    private Location lobby;

    // ---------- 一局运行期状态 ----------
    private Phase phase = Phase.WAITING;
    /** 本局玩家（加入顺序 = 轮流跑的建造顺序）。 */
    private final List<UUID> players = new ArrayList<>();
    /** 本局每人的场地：uuid -> 复制体。 */
    private final Map<UUID, BuildRunPlot> plots = new LinkedHashMap<>();
    /** 本局积分。 */
    private final Map<UUID, Integer> scores = new LinkedHashMap<>();
    /** 下一位要跑的建造者在 {@link #players} 里的下标。 */
    private int turn;
    /** 本条跑酷的建造者。 */
    private UUID builder;
    /** 当前是在"其他选手跑"阶段（false = 建造者自己跑）。 */
    private boolean othersRun;
    /** 当前这条跑酷还活着（正在跑）的人。 */
    private final Set<UUID> active = new LinkedHashSet<>();
    /** 本条跑酷已经通过的其他选手。 */
    private final Set<UUID> passed = new LinkedHashSet<>();
    /** 本条跑酷已经失败的其他选手。 */
    private final Set<UUID> failed = new LinkedHashSet<>();
    /** 本条跑酷有资格跑的其他人数量（建造者通过、其他人开跑那一刻定下来）= 建造者计分的分母。 */
    private int runners;
    /** 建造阶段已点「完成建造」的人（点完就在旁观，不再造；全员点完提前进轮流跑）。 */
    private final Set<UUID> buildDone = new LinkedHashSet<>();
    /** 当前尝试的截止时间（毫秒）。 */
    private long deadline;
    /** 建造者开跑前「所有人（含本人）先旁观这条跑酷」的截止时间（毫秒；0 = 不在展示期）。 */
    private long previewUntil;
    /** 建造者没通过后「留在原地让观众多看几秒」的截止时间（毫秒；0 = 不在收尾等待）。 */
    private long lingerUntil;
    /** 守卫轮次：延迟任务 / 回调靠它判断"是不是上一局/上一阶段残留的"。 */
    private long guard;
    private BukkitTask task;
    private PlotCopier copier;
    /**
     * 终点高亮实体的 UUID（「其他选手一起跑」时挂在终点方块上的发光方块显示）；null = 当前没有。
     * <p>
     * 只存 id，实体本身由 {@code BuildRunManager} 管；{@link #clearCourse()} 不动它，
     * 删实体统一走 {@code clearEndGlow}（否则轮下一条时会漏一个发光方块在场地里）。
     */
    private UUID endGlow;

    public BuildRunArena(String name) {
        this.name = name;
    }

    // ---------- 配置字段 ----------

    public String getName() {
        return name;
    }

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

    public String getDirection() {
        return direction;
    }

    /** 只接受 +x / -x / +z / -z（大小写不敏感）。返回是否设置成功。 */
    public boolean setDirection(String dir) {
        if (dir == null) {
            return false;
        }
        String d = dir.trim().toLowerCase();
        for (String ok : DIRECTIONS) {
            if (ok.equals(d)) {
                direction = ok;
                return true;
            }
        }
        return false;
    }

    public int getGap() {
        return gap;
    }

    public void setGap(int gap) {
        this.gap = Math.max(0, gap);
    }

    public Location getSpawn() {
        return spawn;
    }

    public void setSpawn(Location spawn) {
        this.spawn = spawn;
    }

    public Location getLobby() {
        return lobby;
    }

    public void setLobby(Location lobby) {
        this.lobby = lobby;
    }

    /** 配置是否齐全：圈了范围 + 设了出生点。 */
    public boolean isReady() {
        return pos1 != null && pos2 != null && pos1.getWorld() != null && spawn != null;
    }

    /** 模板范围 {minX,minY,minZ,maxX,maxY,maxZ}；没圈好返回 null。 */
    public int[] templateBounds() {
        return bounds(pos1, pos2);
    }

    /** 第 index 块复制体的范围（index 从 1 开始；0 = 模板本身，不参与建造）。 */
    public int[] plotBounds(int index) {
        int[] b = templateBounds();
        if (b == null) {
            return null;
        }
        int s = step();
        int dx = 0;
        int dz = 0;
        switch (direction) {
            case "-x" -> dx = -index * s;
            case "+z" -> dz = index * s;
            case "-z" -> dz = -index * s;
            default -> dx = index * s;
        }
        return new int[]{b[0] + dx, b[1], b[2] + dz, b[3] + dx, b[4], b[5] + dz};
    }

    /** 单块场地在衍生方向上的步长 = 该方向长度 + gap。 */
    public int step() {
        int[] b = templateBounds();
        if (b == null) {
            return 1;
        }
        int g = Math.max(0, gap);
        if (direction.endsWith("x")) {
            return (b[3] - b[0] + 1) + g;
        }
        return (b[5] - b[2] + 1) + g;
    }

    /** 第 index 块复制体对应的出生点（模板 spawn 按同一偏移平移）。 */
    public Location plotSpawn(int index) {
        return offset(spawn, index);
    }

    /** 把模板坐标按第 index 块复制体平移。 */
    public Location offset(Location loc, int index) {
        if (loc == null) {
            return null;
        }
        int s = step();
        Location out = loc.clone();
        switch (direction) {
            case "-x" -> out.add(-index * s, 0, 0);
            case "+z" -> out.add(0, 0, index * s);
            case "-z" -> out.add(0, 0, -index * s);
            default -> out.add(index * s, 0, 0);
        }
        return out;
    }

    public World getWorld() {
        return pos1 == null ? null : pos1.getWorld();
    }

    /** 场地名 + 方向 + 块数，用于配置/提示文案。 */
    public String describe() {
        int[] b = templateBounds();
        String size = b == null ? "未圈选"
                : (b[3] - b[0] + 1) + "×" + (b[4] - b[1] + 1) + "×" + (b[5] - b[2] + 1);
        return "&e" + name + " &7[" + size + "，方向 " + direction.toUpperCase()
                + "，间隔 " + gap + "]";
    }

    // ---------- 运行期状态 ----------

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    /** D21：加入 tick 与同 tick 内随机名次（同时进入的几人随机决出主持人）。 */
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

    public Map<UUID, BuildRunPlot> getPlots() {
        return plots;
    }

    public Map<UUID, Integer> getScores() {
        return scores;
    }

    public int getTurn() {
        return turn;
    }

    public void setTurn(int turn) {
        this.turn = turn;
    }

    public UUID getBuilder() {
        return builder;
    }

    public void setBuilder(UUID builder) {
        this.builder = builder;
    }

    public boolean isOthersRun() {
        return othersRun;
    }

    public void setOthersRun(boolean othersRun) {
        this.othersRun = othersRun;
    }

    public Set<UUID> getActive() {
        return active;
    }

    public Set<UUID> getPassed() {
        return passed;
    }

    public Set<UUID> getFailed() {
        return failed;
    }

    public int getRunners() {
        return runners;
    }

    public Set<UUID> getBuildDone() {
        return buildDone;
    }

    public void setRunners(int runners) {
        this.runners = Math.max(0, runners);
    }

    public long getDeadline() {
        return deadline;
    }

    public void setDeadline(long deadline) {
        this.deadline = deadline;
    }

    public long getPreviewUntil() {
        return previewUntil;
    }

    public void setPreviewUntil(long previewUntil) {
        this.previewUntil = previewUntil;
    }

    public long getLingerUntil() {
        return lingerUntil;
    }

    public void setLingerUntil(long lingerUntil) {
        this.lingerUntil = lingerUntil;
    }

    /** 展示还剩多少毫秒（没在展示返回 0）。 */
    public long previewLeftMs() {
        long left = previewUntil - System.currentTimeMillis();
        return left <= 0L ? 0L : left;
    }

    /** 失败后收尾等待还剩多少毫秒（没在等待返回 0）。 */
    public long lingerLeftMs() {
        long left = lingerUntil - System.currentTimeMillis();
        return left <= 0L ? 0L : left;
    }

    public long getGuard() {
        return guard;
    }

    /** 守卫 +1（开新局 / 新阶段时调，让残留任务失效）。 */
    public long nextGuard() {
        return ++guard;
    }

    public BukkitTask getTask() {
        return task;
    }

    public void setTask(BukkitTask task) {
        this.task = task;
    }

    public PlotCopier getCopier() {
        return copier;
    }

    public void setCopier(PlotCopier copier) {
        this.copier = copier;
    }

    /** 终点高亮实体的 UUID（没有时 null）。 */
    public UUID getEndGlow() {
        return endGlow;
    }

    public void setEndGlow(UUID endGlow) {
        this.endGlow = endGlow;
    }

    /** 清掉当前这条跑酷的临时状态（建造者 / 谁在跑 / 谁通过 / 谁失败 / 谁完成了建造 / 两个看跑酷的时间窗）。 */
    public void clearCourse() {
        builder = null;
        othersRun = false;
        active.clear();
        passed.clear();
        failed.clear();
        buildDone.clear();
        runners = 0;
        deadline = 0L;
        previewUntil = 0L;
        lingerUntil = 0L;
    }

    /** 取消所有调度任务（局终 / 关服）。 */
    public void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 当前这条跑酷的场地；建造者不在时返回 null。 */
    public BuildRunPlot currentPlot() {
        return builder == null ? null : plots.get(builder);
    }

    /** 截止时间还剩几秒（向上取整，最小 0）。 */
    public long secondsLeft() {
        if (deadline <= 0L) {
            return 0L;
        }
        long ms = deadline - System.currentTimeMillis();
        return ms <= 0L ? 0L : (ms + 999L) / 1000L;
    }

    // ---------- 配置读写 ----------

    public void save(ConfigurationSection sec) {
        com.hub.world.Worlds.writeLocation(sec, "pos1", pos1);
        com.hub.world.Worlds.writeLocation(sec, "pos2", pos2);
        sec.set("direction", direction);
        sec.set("gap", gap);
        com.hub.world.Worlds.writeLocation(sec, "spawn", spawn);
        com.hub.world.Worlds.writeLocation(sec, "lobby", lobby);
    }

    public static BuildRunArena load(String name, ConfigurationSection sec) {
        BuildRunArena a = new BuildRunArena(name);
        a.pos1 = com.hub.world.Worlds.readLocation(sec, "pos1");
        a.pos2 = com.hub.world.Worlds.readLocation(sec, "pos2");
        a.setDirection(sec.getString("direction", "+x"));
        a.gap = Math.max(0, sec.getInt("gap", 0));
        a.spawn = com.hub.world.Worlds.readLocation(sec, "spawn");
        a.lobby = com.hub.world.Worlds.readLocation(sec, "lobby");
        return a;
    }

    // ---------- 工具 ----------

    /** 两个角的范围；世界不同 / 缺一个都返回 null。 */
    public static int[] bounds(Location l1, Location l2) {
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

    /** 范围格数。 */
    public static long volume(int[] b) {
        if (b == null) {
            return 0L;
        }
        return (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
    }
}
