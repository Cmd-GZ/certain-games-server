package com.luckypillar;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一个幸运之柱场地：只有两个配置点（center / lobby），其余（柱阵、中心平台、场内空间）开局现算。
 * <p>
 * 设计稿要点：
 * <ul>
 *   <li>柱数 = 开局人数，环半径 R0 = clamp(5, 32, 人数 × 间距 / 2π)，柱顶同高 = center.y；</li>
 *   <li>场内空间 = 水平半径 (R0 + 4) 的方形盒，竖直 柱底-3 ~ 柱顶+30；</li>
 *   <li>「本局方块集合」= 生成的柱阵/中心平台 + 玩家放的方块 + 流出来的水/岩浆，
 *       缩圈只吃这个集合（比全盒逐格扫快几个数量级），局末再按整盒清 AIR。</li>
 * </ul>
 */
public class LuckyPillarArena {

    public enum State {
        WAITING, RUNNING, ENDING
    }

    /** 方块坐标（本局方块集合的元素）。 */
    public record Pos(int x, int y, int z) {
    }

    /** 柱阵材质（设计稿 §2.1）。 */
    public static final Material PILLAR_MATERIAL = Material.CRYING_OBSIDIAN;
    /** 中心平台材质：铺在柱底高度的草方块台（3×3 平台 = center-platform-radius 1）。 */
    public static final Material CENTER_PLATFORM_MATERIAL = Material.GRASS_BLOCK;
    /** 中心平台默认半径（1 = 3×3 格）。 */
    public static final int DEFAULT_PLATFORM_RADIUS = 1;
    /** 等待点自动铺平台的材质。 */
    public static final Material LOBBY_PLATFORM_MATERIAL = Material.SMOOTH_STONE;
    /** 旁观点：中心上方多少格。 */
    public static final int SPECTATE_HEIGHT = 14;
    /** 场内空间水平方向比柱阵环多留几格。 */
    public static final int BOX_MARGIN = 4;
    /** 柱顶往上留几格（设计稿 §2.2）。 */
    public static final int BOX_ABOVE = 30;
    /** 柱底往下留几格（设计稿 §2.2）。 */
    public static final int BOX_BELOW = 3;

    private String name;
    /** 游戏中心点 = 圆心 + 柱顶高度（中心平台铺在柱底高度，见 config 的 center-platform-radius）。 */
    private Location center;
    /** 等待点。 */
    private Location lobby;

    private State state = State.WAITING;
    /** 局号（守卫延迟任务：上一局的残留任务必须自己失效）。 */
    private long match;
    /** 场地是否已彻底清空（上一局残留没清掉时开局前再扫一遍）。 */
    private boolean clean = true;

    private final List<UUID> players = new ArrayList<>();     // 存活
    private final List<UUID> eliminated = new ArrayList<>();  // 已淘汰（旁观光看）
    private final List<UUID> elimOrder = new ArrayList<>();    // 淘汰顺序（先淘汰的在前）
    private final List<UUID> entities = new ArrayList<>();    // 本局实体（箭/苦力怕/TNT），结算统一清
    /** 本局方块集合：缩圈时只扫这里。 */
    private final Set<Pos> blocks = new LinkedHashSet<>();
    /** 圈外计时（玩家 -> 首次出圈时刻）。 */
    private final Map<UUID, Long> outSince = new LinkedHashMap<>();
    /** 柱顶传送点（按柱序号）。 */
    private final List<Location> pillarTops = new ArrayList<>();

    // 本局形状
    private int pillarCount;
    private int pillarDepth = 10;
    private int platformRadius = DEFAULT_PLATFORM_RADIUS;
    /** 中心平台所在的 Y = 柱底高度（柱子最低一格）。 */
    private int platformY;
    private int halfSize;
    private int minY;
    private int maxY;

    // 缩圈
    private double startRadius;
    private double radius;
    private double targetRadius = 2;
    private long shrinkStartMs;
    private long shrinkDelayMs;
    private long shrinkDurationMs = 1;

    // 终局
    private boolean finalPhase;
    private long finalStartMs;
    private long nextWaveMs;
    private int finalEnterAlive;

    // 分批清场的游标
    private boolean clearing;
    private int clearX;
    private int clearY;
    private int clearZ;
    private int clearBlocksScanned;
    private int clearBlocksChanged;

    public LuckyPillarArena(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Location getCenter() {
        return center;
    }

    public void setCenter(Location center) {
        this.center = center;
    }

    public Location getLobby() {
        return lobby;
    }

    public void setLobby(Location lobby) {
        this.lobby = lobby;
    }

    public World getWorld() {
        return center == null ? null : center.getWorld();
    }

    public boolean isReady() {
        return center != null && center.getWorld() != null && lobby != null && lobby.getWorld() != null;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public long getMatch() {
        return match;
    }

    /** 开新一局：局号 +1（上一局的延迟任务随之失效）。 */
    public long nextMatch() {
        return ++match;
    }

    public boolean isClean() {
        return clean;
    }

    public void setClean(boolean clean) {
        this.clean = clean;
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

    public List<UUID> getEntities() {
        return entities;
    }

    public Set<Pos> getBlocks() {
        return blocks;
    }

    public Map<UUID, Long> getOutSince() {
        return outSince;
    }

    public List<Location> getPillarTops() {
        return pillarTops;
    }

    public int getPillarCount() {
        return pillarCount;
    }

    public int getVoidY() {
        return minY;
    }

    public int getMaxY() {
        return maxY;
    }

    public double getStartRadius() {
        return startRadius;
    }

    public double getRadius() {
        return radius;
    }

    public void setRadius(double radius) {
        this.radius = radius;
    }

    public double getTargetRadius() {
        return targetRadius;
    }

    public boolean isFinalPhase() {
        return finalPhase;
    }

    public int getFinalEnterAlive() {
        return finalEnterAlive;
    }

    public long getFinalStartMs() {
        return finalStartMs;
    }

    public long getNextWaveMs() {
        return nextWaveMs;
    }

    public void setNextWaveMs(long nextWaveMs) {
        this.nextWaveMs = nextWaveMs;
    }

    // ---------- 形状 ----------

    /** 环半径：ring-radius 配置 > 0 时固定，否则 人数 × 间距 / 2π，clamp 5~32（设计稿 §2.1）。 */
    public static double ringRadius(int count, double spacing, double fixedRadius) {
        if (fixedRadius > 0) {
            return Math.max(2.0, fixedRadius);
        }
        double r = count * spacing / (Math.PI * 2);
        return Math.max(5.0, Math.min(32.0, r));
    }

    /** 场内空间的半边长（水平方向，方形盒）。 */
    public int boxHalfSize() {
        return halfSize;
    }

    public int boxMinX() {
        return center.getBlockX() - halfSize;
    }

    public int boxMaxX() {
        return center.getBlockX() + halfSize;
    }

    public int boxMinZ() {
        return center.getBlockZ() - halfSize;
    }

    public int boxMaxZ() {
        return center.getBlockZ() + halfSize;
    }

    public int boxMinY() {
        return minY;
    }

    public int boxMaxY() {
        return maxY;
    }

    /** 圆心的实际坐标（方块中心，避免 ±0.5 的偏心）。 */
    public double circleX() {
        return center.getBlockX() + 0.5;
    }

    public double circleZ() {
        return center.getBlockZ() + 0.5;
    }

    /** 水平距圆心（格）。 */
    public double horizDist(Location loc) {
        if (loc == null || center == null) {
            return 0;
        }
        double dx = loc.getX() - circleX();
        double dz = loc.getZ() - circleZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 是否在场内空间盒内（用于判定"这个方块属于本局"）。 */
    public boolean inBox(int x, int y, int z) {
        if (center == null || halfSize <= 0) {
            return false;
        }
        return y >= minY && y <= maxY
                && x >= boxMinX() && x <= boxMaxX()
                && z >= boxMinZ() && z <= boxMaxZ();
    }

    public boolean inBox(Location loc) {
        return loc != null && loc.getWorld() != null && loc.getWorld().equals(getWorld())
                && inBox(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * 该位置是否属于中心平台（center-platform-radius = 0 时不生成，也不保护）。
     */
    private boolean isPlatform(int x, int y, int z) {
        return platformRadius > 0
                && y == platformY
                && Math.abs(x - center.getBlockX()) <= platformRadius
                && Math.abs(z - center.getBlockZ()) <= platformRadius;
    }

    /**
     * 按人数生成柱阵（设计稿 §2.1）。
     *
     * @param startGap   开局圈半径比柱环半径多出多少格（&gt;0 时柱子和边境之间留出空地，
     *                   缩圈开始前这段空地不被吃）
     * @param platformRadius 中心平台半径（默认 1 = 3×3 草方块，铺在**柱底高度**；
     *                       0 = 不铺台子）
     * @return 实际生成的柱数
     */
    public int generate(int count, int depth, double spacing, double fixedRadius, double startGap,
                        int platformRadius) {
        World w = getWorld();
        if (w == null || center == null || count <= 0) {
            return 0;
        }
        this.pillarCount = count;
        this.pillarDepth = Math.max(1, depth);
        this.platformRadius = Math.max(0, platformRadius);
        double r0 = ringRadius(count, spacing, fixedRadius);
        double gap = Math.max(0.0, startGap);
        this.startRadius = r0 + gap;
        this.radius = this.startRadius;
        this.halfSize = (int) Math.ceil(r0) + BOX_MARGIN;
        int topY = center.getBlockY();
        this.minY = topY - this.pillarDepth + 1 - BOX_BELOW;
        this.maxY = topY + BOX_ABOVE;
        // 柱底 = 柱子最低一格，中心平台铺在同一高度
        this.platformY = topY - this.pillarDepth + 1;

        blocks.clear();
        pillarTops.clear();

        int bx = center.getBlockX();
        int bz = center.getBlockZ();
        // 中心平台（柱底高度的草方块台；默认 1 = 3×3）
        if (this.platformRadius > 0) {
            for (int dx = -this.platformRadius; dx <= this.platformRadius; dx++) {
                for (int dz = -this.platformRadius; dz <= this.platformRadius; dz++) {
                    place(w, bx + dx, this.platformY, bz + dz, CENTER_PLATFORM_MATERIAL);
                }
            }
        }
        // 柱阵：圆周均分，柱顶同高
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2 * i / count;
            int x = (int) Math.round(bx + r0 * Math.cos(angle));
            int z = (int) Math.round(bz + r0 * Math.sin(angle));
            for (int y = topY; y > topY - this.pillarDepth; y--) {
                place(w, x, y, z, PILLAR_MATERIAL);
            }
            pillarTops.add(new Location(w, x + 0.5, topY + 1, z + 0.5));
        }
        return count;
    }

    private void place(World w, int x, int y, int z, Material material) {
        w.getBlockAt(x, y, z).setType(material, false);
        blocks.add(new Pos(x, y, z));
    }

    // ---------- 本局方块 / 实体集合 ----------

    /** 玩家放方块 / 倒水 / 流体蔓延时把方块记进本局集合（只记盒内的）。 */
    public void trackBlock(Block block) {
        if (block == null || block.getWorld() == null || !block.getWorld().equals(getWorld())) {
            return;
        }
        if (!inBox(block.getX(), block.getY(), block.getZ())) {
            return;
        }
        blocks.add(new Pos(block.getX(), block.getY(), block.getZ()));
    }

    public void trackEntity(Entity entity) {
        if (entity != null) {
            entities.add(entity.getUniqueId());
        }
    }

    public boolean isTrackedEntity(Entity entity) {
        return entity != null && entities.contains(entity.getUniqueId());
    }

    /**
     * 缩圈：把「本局方块集合」里水平距圆心 &gt; radius 的方块吃掉（设计稿 §3）。
     * 中心平台（若配置生成了）不参与。
     *
     * @return 本次吃掉的方块数
     */
    public int trimOutside(double radius) {
        World w = getWorld();
        if (w == null || blocks.isEmpty()) {
            return 0;
        }
        double cx = circleX();
        double cz = circleZ();
        double r2 = radius * radius;
        int removed = 0;
        Iterator<Pos> it = blocks.iterator();
        while (it.hasNext()) {
            Pos pos = it.next();
            double dx = pos.x() + 0.5 - cx;
            double dz = pos.z() + 0.5 - cz;
            if (dx * dx + dz * dz <= r2) {
                continue;
            }
            it.remove();
            if (isPlatform(pos.x(), pos.y(), pos.z())) {
                continue;
            }
            Block b = w.getBlockAt(pos.x(), pos.y(), pos.z());
            if (b.getType().isAir()) {
                continue;
            }
            b.setType(Material.AIR, false);
            removed++;
            if (removed % 3 == 0) {
                w.spawnParticle(Particle.BLOCK_CRUMBLE, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5,
                        6, 0.35, 0.35, 0.35, 0.0, PILLAR_MATERIAL.createBlockData());
            }
        }
        return removed;
    }

    // ---------- 清场（局末 / 关服 / 开局前的残留兜底） ----------

    /** 开始按整盒清场（分批调用 {@link #clearStep(int)}，避免一次 20 万方块卡服）。 */
    public void beginClear() {
        if (center == null || getWorld() == null) {
            clearing = false;
            return;
        }
        removeEntities();
        clearX = boxMinX();
        clearY = minY;
        clearZ = boxMinZ();
        clearBlocksScanned = 0;
        clearBlocksChanged = 0;
        clearing = true;
    }

    public boolean isClearing() {
        return clearing;
    }

    public int getClearBlocksChanged() {
        return clearBlocksChanged;
    }

    /**
     * 推进清场。
     *
     * @param budget 本次最多检查多少个方块
     * @return true = 已经清完
     */
    public boolean clearStep(int budget) {
        if (!clearing) {
            return true;
        }
        World w = getWorld();
        if (w == null) {
            clearing = false;
            blocks.clear();
            return true;
        }
        int maxX = boxMaxX();
        int maxZ = boxMaxZ();
        int done = 0;
        while (done < budget) {
            if (clearX > maxX) {
                clearing = false;
                blocks.clear();
                return true;
            }
            Block b = w.getBlockAt(clearX, clearY, clearZ);
            if (!b.getType().isAir() && !isLobbyProtected(b.getX(), b.getY(), b.getZ())) {
                b.setType(Material.AIR, false);
                clearBlocksChanged++;
            }
            done++;
            clearBlocksScanned++;
            if (++clearY > maxY) {
                clearY = minY;
                if (++clearZ > maxZ) {
                    clearZ = boxMinZ();
                    clearX++;
                }
            }
        }
        return false;
    }

    /** 一次性清完（关服 / /lp stop 兜底；正常结算走分批）。 */
    public void clearAllNow() {
        if (center == null || getWorld() == null) {
            blocks.clear();
            clearing = false;
            return;
        }
        beginClear();
        // 预算给死循环兜底：最多扫整盒 2 遍的方块量
        long guard = (long) (boxMaxX() - boxMinX() + 1) * (maxY - minY + 1) * (boxMaxZ() - boxMinZ() + 1) * 2L + 1000L;
        long loops = 0;
        while (!clearStep(20000) && loops++ < guard) {
            // 继续
        }
        clearing = false;
        blocks.clear();
    }

    /** 等待点平台保护（局末清场不拆它）。 */
    private boolean isLobbyProtected(int x, int y, int z) {
        if (lobby == null) {
            return false;
        }
        return Math.abs(x - lobby.getBlockX()) <= 2
                && Math.abs(z - lobby.getBlockZ()) <= 2
                && y >= lobby.getBlockY() - 3
                && y <= lobby.getBlockY() + 1;
    }

    /** 清掉本局实体（箭/苦力怕/TNT）+ 盒内掉落物 / 残留箭矢。 */
    public void removeEntities() {
        World w = getWorld();
        if (w == null) {
            entities.clear();
            return;
        }
        for (UUID id : new ArrayList<>(entities)) {
            Entity e = w.getEntity(id);
            if (e != null && !(e instanceof Player)) {
                e.remove();
            }
        }
        entities.clear();
        for (Entity e : w.getEntities()) {
            if (e instanceof Player) {
                continue;
            }
            boolean junk = e instanceof Item || e instanceof Creeper || e instanceof TNTPrimed
                    || e instanceof AbstractArrow;
            if (junk && inBox(e.getLocation())) {
                e.remove();
            }
        }
    }

    // ---------- 缩圈 / 终局 计时 ----------

    /**
     * 开局：记录缩圈起点、准备期与时长。
     * <p>前 {@code delayMs} 毫秒圈保持 startRadius 不动，之后才从 startRadius 线性收到 targetRadius。
     */
    public void beginShrink(long now, long delayMs, long durationMs, double targetRadius) {
        this.shrinkStartMs = now;
        this.shrinkDelayMs = Math.max(0L, delayMs);
        this.shrinkDurationMs = Math.max(1000L, durationMs);
        this.targetRadius = Math.max(1.0, targetRadius);
        this.radius = startRadius;
        this.finalPhase = false;
        this.outSince.clear();
    }

    /** 当前时刻的圈半径（准备期固定、之后线性插值，不累积误差）。 */
    public double radiusAt(long now) {
        if (startRadius <= targetRadius) {
            return targetRadius;
        }
        double progress = (now - shrinkStartMs - shrinkDelayMs) / (double) shrinkDurationMs;
        if (progress <= 0) {
            return startRadius;
        }
        if (progress >= 1) {
            return targetRadius;
        }
        return startRadius + (targetRadius - startRadius) * progress;
    }

    /** 进入终局：边境冻结在 targetRadius，天灾换成 TNT 雨。 */
    public void enterFinal(long now, int alive) {
        this.finalPhase = true;
        this.finalStartMs = now;
        this.radius = targetRadius;
        this.finalEnterAlive = Math.max(1, alive);
    }

    /** 旁观点：中心上方，往下看。 */
    public Location spectateLocation() {
        if (center == null) {
            return null;
        }
        return new Location(getWorld(), circleX(), center.getBlockY() + SPECTATE_HEIGHT, circleZ(), 0f, 60f);
    }

    // ---------- 配置 ----------

    /**
     * 落盘。世界没加载进来（center/lobby 解不出 Location）时**不写空值**，
     * 保留配置里原来的点，避免一次"世界没起来"就把场地写丢。
     */
    public void save(ConfigurationSection sec) {
        World w = getWorld();
        if (w != null) {
            sec.set("world", com.hub.world.Worlds.tokenOf(w));   // R2：房间实例 → tpl:模板键
        }
        if (center != null) {
            savePoint(sec, "center", center);
        }
        if (lobby != null) {
            savePoint(sec, "lobby", lobby);
        }
    }

    private static void savePoint(ConfigurationSection sec, String key, Location loc) {
        if (loc == null) {
            return;
        }
        ConfigurationSection s = sec.createSection(key);
        s.set("x", round(loc.getX()));
        s.set("y", round(loc.getY()));
        s.set("z", round(loc.getZ()));
        s.set("yaw", round(loc.getYaw()));
        s.set("pitch", round(loc.getPitch()));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static LuckyPillarArena load(String name, ConfigurationSection sec) {
        LuckyPillarArena a = new LuckyPillarArena(name);
        if (sec == null) {
            return a;
        }
        String worldName = sec.getString("world");
        a.center = loadPoint(sec, "center", worldName);
        a.lobby = loadPoint(sec, "lobby", worldName);
        return a;
    }

    private static Location loadPoint(ConfigurationSection parent, String key, String worldName) {
        ConfigurationSection s = parent.getConfigurationSection(key);
        if (s == null) {
            return null;
        }
        String wname = worldName != null ? worldName : s.getString("world");
        World w = wname == null ? null : com.hub.world.Worlds.resolve(wname);
        if (w == null) {
            return null;
        }
        return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }
}
