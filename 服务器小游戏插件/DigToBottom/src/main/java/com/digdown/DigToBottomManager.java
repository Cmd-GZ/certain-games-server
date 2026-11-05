package com.digdown;

import com.hub.session.EndReason;

import com.hub.api.ArcadeBridge;
import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.data.PlayerStats;
import com.hub.util.Chat;
import com.hub.util.LiveSidebar;
import com.hub.util.Top3;
import com.hub.util.StartCountdown;
import com.hub.util.WaitCountdown;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 一掘到底核心：
 * <ul>
 *   <li>场地建好后（/dig build）用指令生成环形坑位的玻璃外壳与基岩；</li>
 *   <li>每局开始随机生成一条柱子方块序列，所有坑位使用完全相同的序列；</li>
 *   <li>3 秒冻结倒计时 → 计时开始 → 最先挖到底部基岩者获胜；</li>
 *   <li>过半玩家到达底部时把剩余时间缩短到配置值；</li>
 *   <li>时间到后未完成者按已挖深度排名；前三名写入玩家战绩。</li>
 * </ul>
 */
public class DigToBottomManager {

    /** 游戏注册名（HubCore 菜单 / 播报 / 战绩都用它）。 */
    public static final String GAME = "一掘到底";
    /** 玩家记录命名空间（players.yml 里的 games.digdown）。 */
    private static final String NAMESPACE = "digdown";
    /** 与 {@code GameSession} 的 gameKey 一致（小写，派对委派/房间路由都用它）。 */
    public static final String GAME_KEY = "digtobottom";

    private static final List<String> MATERIAL_KEYS = List.of("wood", "pickaxe", "shovel");

    /** 配置里一个方块都没读出来时的兜底池。 */
    private static final Material[] FALLBACK = {
            Material.OAK_LOG, Material.STONE, Material.GRANITE, Material.DIORITE,
            Material.ANDESITE, Material.TUFF, Material.DEEPSLATE, Material.DIRT,
            Material.ROOTED_DIRT, Material.SAND, Material.GRAVEL
    };

    private final DigToBottomPlugin plugin;
    private final LiveSidebar liveSidebar;
    private final Random random = new Random();

    private final Map<String, DigToBottomArena> arenas = new LinkedHashMap<>();

    /** D22：每个房间一个会话（键 = 房间键；没有房间时就是场地名）。 */
    private final Map<String, DigToBottomSession> sessions = new LinkedHashMap<>();

    /** D22：玩家 → 房间键（进房时记，离开时摘）。 */
    private final Map<UUID, String> playerKey = new LinkedHashMap<>();
    /** D22：房间键 → 场地名（房间建起来时记）。 */
    private final Map<String, String> keyArena = new LinkedHashMap<>();
    /**
     * D22：房间键 → **那一份运行时场地**。
     *
     * <p>{@link #arenas} 是"地图定义"（config.yml 的 arenas 段，一个名字一张图）；
     * 同一张图可以被两个房间同时开，所以每局的状态必须落在**按房间克隆**的实例上，
     * 否则两个房间共用玩家列表/进度/计时，必然串房。
     */
    private final Map<String, DigToBottomArena> liveArenas = new LinkedHashMap<>();

    private DigToBottomSession ensureSession(String key) {
        DigToBottomSession s = sessions.get(key);
        if (s == null || s.state().isFinal()) {
            s = new DigToBottomSession(this, key);
            sessions.put(key, s);
        }
        return s;
    }
    private final Map<UUID, String> playerArena = new LinkedHashMap<>();

    // ---------- 房间路由（D22） ----------

    /** 房间键：有房间时 = 房间 id；没有房间时就是场地名。 */
    public String keyOf(UUID id) {
        if (id == null) {
            return null;
        }
        String key = playerKey.get(id);
        if (key != null) {
            return key;
        }
        return playerArena.get(id);   // 老路径：键就是场地名
    }

    /** 房间键 → 场地名；默认（没有房间）时键本身就是场地名。 */
    public String arenaNameOfKey(String key) {
        return key == null ? null : keyArena.getOrDefault(key, key);
    }

    /** 房间实例（首次使用时现场克隆一份并钉死房间世界）；没有这张图时返回 null。 */
    public DigToBottomArena liveArena(String key) {
        if (key == null) {
            // **静默 null 是最难查的 bug**：让它立刻炸出来，而不是上层"场不存在"没有任何日志。
            throw new IllegalStateException("DigToBottom: liveArena(null) —— 调用点没有先算房间键");
        }
        DigToBottomArena live = liveArenas.get(key);
        if (live != null) {
            return live;
        }
        String arenaName = arenaNameOfKey(key);
        DigToBottomArena tpl = arenas.get(arenaName);
        if (tpl == null) {
            return null;
        }
        DigToBottomArena copy = tpl.copyConfig();
        copy.setRoomKey(key);
        com.hub.room.RoomManager.Room room = com.hub.room.RoomManager.room(key);
        World roomWorld = room == null ? null : room.world;
        if (roomWorld != null) {
            copy.bindWorld(roomWorld);
            // 插件启用时（还没有房间）`tpl:` 解析不出世界 → 那张图的坐标是空的。
            // 这里在**房间上下文**里重读一次配置，拿到这张图在本房间世界里的坐标。
            if (!copy.hasGeometry()) {
                com.hub.room.RoomContext.pushRoom(room.roomId);
                try {
                    DigToBottomArena fresh = DigToBottomArena.load(arenaName,
                            plugin.getConfig().getConfigurationSection("arenas." + arenaName));
                    fresh.setRoomKey(key);
                    fresh.bindWorld(roomWorld);
                    if (fresh.hasGeometry()) {
                        copy = fresh;
                    }
                } finally {
                    com.hub.room.RoomContext.popRoom();
                }
            }
        }
        liveArenas.put(key, copy);
        publishRegions();
        plugin.getLogger().info("[DigToBottom] 房间 " + key + " 的运行时场地已就绪（图 " + arenaName + "）"
                + (roomWorld == null ? "（无房间世界，按场地名兜底）" : "，世界 " + roomWorld.getName()));
        return copy;
    }

    /** 只按场地名找**运行时实例**（无玩家上下文的只读路径）；找不到再退回地图定义。 */
    public DigToBottomArena arenaByName(String arenaName) {
        if (arenaName == null) {
            return null;
        }
        for (DigToBottomArena live : liveArenas.values()) {
            if (arenaName.equals(live.getName())) {
                return live;
            }
        }
        return arenas.get(arenaName);
    }

    /** 按"房间键或场地名"取运行时场地：房间键优先。 */
    public DigToBottomArena arenaByKey(String key) {
        if (key == null) {
            return null;
        }
        DigToBottomArena live = liveArenas.get(key);
        return live != null ? live : arenas.get(key);
    }

    /**
     * 命令入口用：**当前房间上下文优先** → 运行时实例 → 地图定义。
     *
     * <p>为什么上下文优先：同一张图可以同时被两个房间开（两份运行时实例同名），
     * 光按名字取会取到别人房间那一份。
     */
    public DigToBottomArena resolveArena(String keyOrName) {
        String ctx = com.hub.room.RoomContext.currentRoomId();
        if (ctx != null) {
            DigToBottomArena live = liveArenas.get(ctx);
            if (live != null && (keyOrName == null || live.getName().equals(keyOrName)
                    || live.roomKey().equals(keyOrName))) {
                return live;
            }
        }
        return arenaByKey(keyOrName);
    }

    /** 这个玩家所在房间的那一份场地；没有房间记录时按老路径（场地名 → 地图定义）。 */
    public DigToBottomArena forPlayer(Player p) {
        if (p == null) {
            return null;
        }
        String key = keyOf(p.getUniqueId());
        if (key == null) {
            return null;
        }
        DigToBottomArena live = liveArenas.get(key);
        if (live != null) {
            return live;
        }
        return arenaByName(key);
    }

    /**
     * 控制台开局（派对就是 {@code dispatchCommand(控制台, "dig start {arena}")}）：
     * 把**每个有待开队列的房间实例**都开起来，返回真的开了几间。
     */
    public int startAllWaiting() {
        String ctx = com.hub.room.RoomContext.currentRoomId();
        if (ctx != null) {
            DigToBottomArena live = liveArenas.get(ctx);
            if (live != null && canAutoStart(live)) {
                start(null, live.roomKey());
                return 1;
            }
        }
        int started = 0;
        for (DigToBottomArena live : new ArrayList<>(liveArenas.values())) {
            if (canAutoStart(live)) {
                start(null, live.roomKey());
                started++;
            }
        }
        if (started == 0) {
            for (DigToBottomArena a : new ArrayList<>(arenas.values())) {
                if (canAutoStart(a)) {
                    start(null, a.getName());
                    started++;
                }
            }
        }
        return started;
    }

    private boolean canAutoStart(DigToBottomArena a) {
        return a.getState() == DigToBottomArena.State.WAITING && !a.getPlayers().isEmpty();
    }

    /** 参与区域声明/方块归属判定的所有场地：运行时实例优先，没被房间代表的图退回地图定义。 */
    private List<DigToBottomArena> candidates() {
        List<DigToBottomArena> out = new ArrayList<>(liveArenas.values());
        for (DigToBottomArena a : arenas.values()) {
            boolean represented = false;
            for (DigToBottomArena live : liveArenas.values()) {
                if (live.getName().equals(a.getName())) {
                    represented = true;
                    break;
                }
            }
            if (!represented) {
                out.add(a);
            }
        }
        return out;
    }
    private final Map<String, WaitCountdown> waits = new LinkedHashMap<>();
    private final Map<String, BukkitTask> timers = new LinkedHashMap<>();

    /** 本局发下去的钻石工具标记（防止玩家把工具丢出去 / 被别的插件误判）。 */
    private final NamespacedKey toolKey = new NamespacedKey("gamehub", "dig-tool");

    private YamlConfiguration data;
    /** 诊断日志开关：/dig debug 切换；config.yml 的 debug-break-logs 决定启动时的默认值。 */
    private volatile boolean debugBreak;
    /** I3：会话里的搬迁代码要用到 plugin。 */
    public DigToBottomPlugin plugin() {
        return plugin;
    }


    public DigToBottomManager(DigToBottomPlugin plugin) {
        this.plugin = plugin;
        this.liveSidebar = new LiveSidebar(plugin);
    }

    // ---------- 加载 / 保存 ----------

    public void load() {
        startCountdown();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("arenas");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                arenas.put(name, DigToBottomArena.load(name, sec.getConfigurationSection(name)));
            }
        }
        data = PlayerStats.load(NAMESPACE, new File(plugin.getDataFolder(), "data.yml"));
        debugBreak = plugin.getConfig().getBoolean("debug-break-logs", true);
        plugin.getLogger().info("已载入 " + arenas.size() + " 个场。");
        warnSpawnProtection();

        // 等 HubCore 注册表准备好后再上报，避免首启时游戏名还没登记
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HubAPI.registerGame(GAME, GAME, "DIAMOND_PICKAXE", "6");
            for (DigToBottomArena a : arenas.values()) {
                reportArena(a);
            }
        }, 40L);
    }

    public void shutdown() {
        for (BukkitTask task : timers.values()) {
            task.cancel();
        }
        timers.clear();
        for (DigToBottomArena a : candidates()) {
            if (a.getState() == DigToBottomArena.State.RUNNING) {
                clearLanes(a);
            }
        }
        for (WaitCountdown countdown : waits.values()) {
            countdown.cancel();
        }
        saveAll();
    }

    /** 把 arenas 段整体写回 config.yml（其余全局配置保持内存里的值）。 */
    /**
     * 声明地盘（{@code docs/框架不变量.md} 的 I2）：圆形场地转成外接 AABB。
     * 只在内存里读字段，不碰配置；还没建场的场地会被 {@code Regions} 静默跳过。
     */
    public void publishRegions() {
        com.hub.region.Regions.begin("digdown");
        // 声明的名字用**房间键**：同一张图被两个房间同时开时，两块地盘各自独立
        //（都用场地名的话，后一个声明会把前一个覆盖掉 → I2 冲突/静默失效）。
        for (DigToBottomArena a : candidates()) {
            com.hub.region.Regions.disc("digdown", a.roomKey(), a.getCenter(), a.getRadius(),
                    com.hub.region.Regions.DEFAULT_DISC_HEIGHT);
        }
    }

    public void saveAll() {
        publishRegions();   // 场地可能刚被改过，重新声明（I2）
        ConfigurationSection sec = com.hub.world.Worlds.getOrCreate(plugin.getConfig(), "arenas");
        // ⚠ 不能 `set("arenas", null)` 再整段重写：当前**解析不出世界**的坐标
        // （比如模板世界还没有存活房间）会被 writeLocation 跳过 → 磁盘上的坐标永久丢失（R2）。
        // 只删"内存里已经没有的场地"，其余原地写回。
        for (String key : new java.util.ArrayList<>(sec.getKeys(false))) {
            if (!arenas.containsKey(key)) {
                sec.set(key, null);
            }
        }
        for (DigToBottomArena a : arenas.values()) {
            // 房间实例优先：OP 在房间里改的配置（圆心/等待点/建场）才算数，
            // 否则改完再 saveAll 会被"地图定义"那份覆盖回去。
            DigToBottomArena source = a;
            for (DigToBottomArena live : liveArenas.values()) {
                if (live.getName().equals(a.getName())) {
                    source = live;
                    break;
                }
            }
            source.save(com.hub.world.Worlds.getOrCreate(sec, a.getName()));
        }
        plugin.saveConfig();
    }

    public Map<String, DigToBottomArena> getArenas() {
        return arenas;
    }

    /** 地图定义（config.yml 的 arenas 段）。 */
    public DigToBottomArena getArena(String name) {
        return name == null ? null : arenas.get(name);
    }

    /** 房间实例（doctor / 命令显示用）。 */
    public Map<String, DigToBottomArena> getLiveArenas() {
        return liveArenas;
    }

    /** 命令 list 用：运行时实例优先（同名的房间实例只显示最后建的那一份）。 */
    public List<DigToBottomArena> displayArenas() {
        return candidates();
    }

    public String arenaOf(UUID id) {
        return playerArena.get(id);
    }

    // ---------- 场地几何 ----------

    /** 柱子高度（格）。 */
    public int height() {
        return Math.max(3, Math.min(250, plugin.getConfig().getInt("pillar.height", 60)));
    }

    private int countdownSeconds() {
        return Math.max(0, Math.min(10, plugin.getConfig().getInt("countdown-seconds", 3)));
    }

    /** 环形坑位的 (x,z) 列表，按序号排列。 */
    public List<int[]> lanePoints(DigToBottomArena a) {
        List<int[]> out = new ArrayList<>();
        if (!a.hasGeometry()) {
            return out;
        }
        int cx = a.getCenter().getBlockX();
        int cz = a.getCenter().getBlockZ();
        int n = a.getLanes();
        int r = a.getRadius();
        if (n == 1) {
            // 只有一根柱子时直接放在圆心，避免半径把它甩到一边
            out.add(new int[]{cx, cz});
            return out;
        }
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n;
            out.add(new int[]{
                    cx + (int) Math.round(r * Math.cos(angle)),
                    cz + (int) Math.round(r * Math.sin(angle))
            });
        }
        return out;
    }

    public int[] lanePoint(DigToBottomArena a, int lane) {
        List<int[]> pts = lanePoints(a);
        if (pts.isEmpty() || lane < 0 || lane >= pts.size()) {
            return null;
        }
        return pts.get(lane);
    }

    /** 这一格是不是某个场地的玻璃/基岩/柱子范围（用于玻璃保护与事件限定世界）。 */
    public DigToBottomArena arenaAt(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        int h = height();
        for (DigToBottomArena a : candidates()) {
            if (!a.hasGeometry() || !loc.getWorld().equals(a.getCenter().getWorld())) {
                continue;
            }
            int y0 = a.getCenter().getBlockY();
            if (loc.getBlockY() < y0 - 1 || loc.getBlockY() > y0 + h + 3) {
                continue;
            }
            for (int[] pt : lanePoints(a)) {
                if (Math.abs(loc.getBlockX() - pt[0]) <= 1 && Math.abs(loc.getBlockZ() - pt[1]) <= 1) {
                    return a;
                }
            }
        }
        return null;
    }

    /** 玩家自己的柱子最上层（脚底）位置。 */
    public Location spawnOf(DigToBottomArena a, int lane) {
        int[] pt = lanePoint(a, lane);
        if (pt == null) {
            return a.getLobby();
        }
        Location loc = new Location(a.getCenter().getWorld(), pt[0] + 0.5, a.getCenter().getBlockY() + height(), pt[1] + 0.5);
        double dx = a.getCenter().getBlockX() + 0.5 - loc.getX();
        double dz = a.getCenter().getBlockZ() + 0.5 - loc.getZ();
        if (dx * dx + dz * dz > 0.001) {
            loc.setDirection(new Vector(dx, 0, dz));
        }
        loc.setPitch(90f);
        return loc;
    }

    // ---------- 建场：玻璃 + 基岩 ----------

    /**
     * 按圆心/坑位数/半径生成环形坑位：每格 1×1 柱子预留空位，四周玻璃、底部基岩。
     * 柱体方块不在这里生成，开局时再随机填充。
     */
    public boolean build(Player p, DigToBottomArena a) {
        if (!a.hasGeometry()) {
            msg(p, "&c先设置圆心：站在柱子最底层方块所在的位置输入 &e/dig center&c。");
            return false;
        }
        if (a.getState() == DigToBottomArena.State.RUNNING) {
            msg(p, "&c对局进行中，不能重建场地。");
            return false;
        }
        List<int[]> pts = lanePoints(a);
        if (pts.isEmpty()) {
            msg(p, "&c坑位计算失败：检查 /dig lanes 与 /dig radius。");
            return false;
        }
        for (int i = 0; i < pts.size(); i++) {
            for (int j = i + 1; j < pts.size(); j++) {
                int d = Math.max(Math.abs(pts.get(i)[0] - pts.get(j)[0]),
                        Math.abs(pts.get(i)[1] - pts.get(j)[1]));
                if (d < 3) {
                    msg(p, "&c坑位太挤（有坑中心距离小于 3 格，玻璃会重叠）。把 &e/dig radius&c 调大或把 &e/dig lanes&c 调小。");
                    return false;
                }
            }
        }

        World w = a.getCenter().getWorld();
        int y0 = a.getCenter().getBlockY();
        int h = height();
        if (y0 - 1 < w.getMinHeight() || y0 + h + 2 >= w.getMaxHeight()) {
            msg(p, "&c高度超出世界范围：柱子最底层 Y=&e" + y0 + "&c、柱高 &e" + h
                    + "&c，世界范围是 &e" + w.getMinHeight() + "~" + (w.getMaxHeight() - 1) + "&c。");
            return false;
        }
        for (int[] pt : pts) {
            w.getChunkAt(pt[0] >> 4, pt[1] >> 4).load();
        }
        for (int[] pt : pts) {
            int x = pt[0];
            int z = pt[1];
            // 柱体 + 顶部站立区（到玻璃顶那一层）全部清空，建在地下也不会卡住玩家
            for (int y = y0; y <= y0 + h + 2; y++) {
                w.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
            w.getBlockAt(x, y0 - 1, z).setType(Material.BEDROCK, false);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    for (int y = y0 - 1; y <= y0 + h + 2; y++) {
                        w.getBlockAt(x + dx, y, z + dz).setType(Material.GLASS, false);
                    }
                }
            }
        }
        a.setBuilt(true);
        a.setBuiltHeight(h);
        saveAll();
        reportArena(a);
        msg(p, "&a已生成 &e" + pts.size() + " &a个坑位的玻璃与基岩（柱高 &e" + h + "&a 格）。");
        msg(p, "&7记得 /dig lobby 设置等待点，然后 /dig save。");
        return true;
    }

    /** 把每个坑位内部的柱子方块清成空气。 */
    public void clearLanes(DigToBottomArena a) {
        if (!a.hasGeometry()) {
            return;
        }
        World w = a.getCenter().getWorld();
        int y0 = a.getCenter().getBlockY();
        int h = height();
        for (int[] pt : lanePoints(a)) {
            for (int y = y0; y <= y0 + h + 2; y++) {
                w.getBlockAt(pt[0], y, pt[1]).setType(Material.AIR, false);
            }
        }
    }

    /**
     * 随机一条柱子序列；所有坑位共用同一份。
     * 先把材料池洗牌后循环取，再整体洗一次，保证尽量用到每一种方块。
     */
    private List<Material> rollPillar() {
        List<Material> pool = materials();
        int h = height();
        List<Material> bag = new ArrayList<>(h);
        while (bag.size() < h) {
            List<Material> copy = new ArrayList<>(pool);
            Collections.shuffle(copy, random);
            bag.addAll(copy);
        }
        bag = new ArrayList<>(bag.subList(0, h));
        Collections.shuffle(bag, random);
        for (int i = 1; i < bag.size(); i++) {
            if (bag.get(i) == bag.get(i - 1)) {
                for (int j = i + 1; j < bag.size(); j++) {
                    if (bag.get(j) != bag.get(i - 1)) {
                        Material tmp = bag.get(j);
                        bag.set(j, bag.get(i));
                        bag.set(i, tmp);
                        break;
                    }
                }
            }
        }
        return bag;
    }

    private List<Material> materials() {
        List<Material> out = new ArrayList<>();
        for (String key : MATERIAL_KEYS) {
            for (String raw : plugin.getConfig().getStringList("pillar.materials." + key)) {
                Material m = Material.matchMaterial(raw);
                if (m != null && m.isBlock() && m.isSolid() && !m.isAir()) {
                    out.add(m);
                }
            }
        }
        if (out.isEmpty()) {
            Collections.addAll(out, FALLBACK);
        }
        return out;
    }

    private void fillPillars(DigToBottomArena a, List<Material> pillar) {
        clearLanes(a);
        World w = a.getCenter().getWorld();
        int y0 = a.getCenter().getBlockY();
        for (int[] pt : lanePoints(a)) {
            for (int i = 0; i < pillar.size(); i++) {
                w.getBlockAt(pt[0], y0 + i, pt[1]).setType(pillar.get(i), false);
            }
        }
    }

    // ---------- 会话 ----------

    public boolean isDebugBreak() {
        return debugBreak;
    }

    public boolean toggleDebugBreak() {
        debugBreak = !debugBreak;
        return debugBreak;
    }

    /**
     * 原版出生点保护（server.properties 的 spawn-protection）会让非 OP 在出生点半径内挖不动任何方块，
     * 而且这道检查在 BlockBreakEvent 之前，插件收不到事件、也拦不住。启动时直接提醒。
     */
    private void warnSpawnProtection() {
        int radius = Bukkit.getServer().getSpawnRadius();
        if (radius <= 0) {
            return;
        }
        World respawn = Bukkit.getServer().getRespawnWorld();
        String where = respawn == null ? "主世界" : respawn.getName();
        plugin.getLogger().warning("服务器 spawn-protection=" + radius
                + "：非 OP 玩家在 " + where + " 出生点 " + radius + " 格内无法破坏任何方块（原版行为，OP 免疫）。");
        if (respawn == null) {
            return;
        }
        Location spawn = respawn.getSpawnLocation();
        for (DigToBottomArena a : arenas.values()) {
            if (a.getCenter() == null || !respawn.equals(a.getCenter().getWorld())) {
                continue;
            }
            int dx = Math.abs(a.getCenter().getBlockX() - spawn.getBlockX());
            int dz = Math.abs(a.getCenter().getBlockZ() - spawn.getBlockZ());
            if (Math.max(dx, dz) <= radius) {
                plugin.getLogger().warning("场 " + a.getName() + " 的圆心离 " + where + " 出生点只有 "
                        + Math.max(dx, dz) + " 格（<= " + radius + "），非 OP 玩家会挖不动柱子！"
                        + "请把 spawn-protection 设为 0，或把场地建到别的世界/远离出生点。");
            }
        }
    }

    /** /dig debug：把出生点保护与目标方块的信息直接打印给玩家（不依赖方块事件）。 */
    public void debugInfo(Player p) {
        int radius = Bukkit.getServer().getSpawnRadius();
        World respawn = Bukkit.getServer().getRespawnWorld();
        Block target = p.getTargetBlockExact(6);
        Location at = target != null ? target.getLocation() : p.getLocation();
        String dist = "-";
        boolean protectedHere = false;
        if (respawn != null && respawn.equals(at.getWorld())) {
            Location spawn = respawn.getSpawnLocation();
            int dx = Math.abs(at.getBlockX() - spawn.getBlockX());
            int dz = Math.abs(at.getBlockZ() - spawn.getBlockZ());
            dist = Math.max(dx, dz) + " 格";
            protectedHere = radius > 0 && Math.max(dx, dz) <= radius;
        }
        Chat.send(p, "&7[DigDebug] spawn-protection=&e" + radius
                + "&7  respawn-world=&e" + (respawn == null ? "-" : respawn.getName())
                + "&7  目标方块=&e" + (target == null ? "无" : target.getType().name())
                + "&7 @ &e" + at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ());
        Chat.send(p, "&7[DigDebug] 距出生点=&e" + dist
                + "&7  受出生点保护=&e" + (protectedHere ? "&c是（非 OP 挖不动任何方块，OP 免疫）" : "&a否")
                + "&7  你的 OP=&e" + p.isOp() + "  游戏模式=&e" + p.getGameMode()
                + "&7  权限 dig.use=&e" + p.hasPermission("dig.use"));
        if (protectedHere && !p.isOp()) {
            Chat.send(p, "&c这就是挖不动的原因：原版 spawn-protection 在 BlockBreakEvent 之前就拦掉了，插件无法取消。");
            Chat.send(p, "&7处理：把 server.properties 的 &espawn-protection&7 设为 &e0&7 后重启，或把场地建到别的世界/远离出生点。");
        }
        if (!p.isOp()) {
            Chat.send(p, "&7[DigDebug] 非 OP 还有一层：&e建筑猜猜乐（GTB）&7 会在 NORMAL 全局取消方块破坏（不分世界）。"
                    + "DigToBottom 只在自己柱子内用 HIGHEST 显式放行，场地外的方块仍会被 GTB 拦。");
        }
    }

    public void updateWaitCountdown(DigToBottomArena a) {
        wait(a).update();
    }

    public int waitingCountOf(String arenaName) {
        DigToBottomArena a = resolveArena(arenaName);
        return a == null ? 0 : a.getPlayers().size();
    }

    public int minPlayersOf(String arenaName) {
        return plugin.getConfig().getInt("min-players", 2);
    }

    public boolean isHostOf(Player p, String arenaName) {
        if (!"host".equalsIgnoreCase(plugin.getConfig().getString("flow", "upstream"))) {
            return false;
        }
        DigToBottomArena a = resolveArena(arenaName);
        if (a == null || p == null) {
            return false;
        }
        UUID host = wait(a).currentHost();
        return host != null && host.equals(p.getUniqueId());
    }

    private WaitCountdown wait(DigToBottomArena a) {
        return waits.computeIfAbsent(a.roomKey(), n -> new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return a.getPlayers();
            }

            @Override
            public int minPlayers() {
                return Math.max(1, plugin.getConfig().getInt("min-players", 1));
            }

            @Override
            public int maxPlayers() {
                return Math.max(1, Math.min(a.getLanes(), plugin.getConfig().getInt("max-players", 8)));
            }

            @Override
            public boolean hasOpQueued() {
                for (UUID id : a.getPlayers()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOp()) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onTick(long secondsLeft) {
                for (UUID id : a.getPlayers()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) {
                        Chat.actionBar(p, "&6开始倒计时：&e" + secondsLeft + "s");
                    }
                }
            }

            @Override
            public void onMessage(String message) {
                broadcast(a, message);
            }

            // ---------- D21 主持人流程（配置 flow: host 才启用，默认走上游） ----------

            @Override

            public boolean hostFlow() {

                return "host".equalsIgnoreCase(plugin.getConfig().getString("flow", "upstream"));

            }

            @Override

            public int waitSeconds() {

                return Math.max(5, plugin.getConfig().getInt("wait-seconds", 300));

            }

            @Override

            public java.util.List<UUID> waitingOrder() {

                java.util.List<UUID> order = new ArrayList<>(a.getPlayers());

                order.sort(java.util.Comparator

                        .comparingLong((UUID id) -> a.joinTickOf(id))

                        .thenComparingLong(id -> a.joinSeedOf(id)));

                return order;

            }

            @Override

            public void onHostTimeout(UUID host) {

                Player hp = Bukkit.getPlayer(host);

                String hname = hp == null ? host.toString() : hp.getName();

                a.getPlayers().remove(host);

                playerArena.remove(host);

                DigToBottomSession s2 = sessions.get(a.roomKey());

                if (s2 != null && hp != null && !s2.state().isFinal()) {

                    s2.leave(hp);

                }

                if (hp != null && hp.isOnline()) {

                    msg(hp, "&c倒计时结束还没开始，你被移出本局（主持人超时）。");

                    com.hub.api.HubAPI.teleportToLobby(hp);

                }

                plugin.getLogger().info("[DigToBottom] 场 " + a.getName() + "：主持人 " + hname

                        + " 超时未开始，已移出（剩余 " + a.getPlayers().size() + " 人）");

                broadcast(a, "&e主持人 &f" + hname + " &e超时未开始，已移出本局。");

                updateWaitCountdown(a);

            }


            @Override
            public void onStart() {
                // 倒计时归零：按**这个房间键**开局（两房间同名场地时不能按名字找）
                start(null, a.roomKey());
            }
        }));
    }

    /**
     * 拒绝加入时**同时**写控制台。
     *
     * <p>R3（失败可见）真实教训：这个游戏在房间里一直开不了局，而拒绝文案只 sendMessage 给玩家，
     * 控制台一个字都没有，害我查了三轮（对照 LandClaim/JumpFill 的 `tpl:` 读侧 bug）。
     */
    private void rejectJoin(Player p, String arenaName, String why) {
        msg(p, why);
        plugin.getLogger().info("[DigToBottom] 拒绝 " + p.getName() + " 加入 \"" + arenaName + "\"："
                + org.bukkit.ChatColor.stripColor(why.replace('&', '\u00a7')));
    }

    public boolean join(Player p, String arenaName) {
        // D22：键 = 房间 id（有房间时）；本局状态落在**本房间的**运行时场地实例上
        String key = com.hub.room.RoomContext.roomKeyFor(p, GAME_KEY, arenaName);
        keyArena.put(key, arenaName);
        DigToBottomArena a = liveArena(key);
        if (a == null) {
            rejectJoin(p, arenaName, "&c场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        UUID id = p.getUniqueId();
        if (playerArena.containsKey(id)) {
            rejectJoin(p, arenaName, "&c你已在其他场。");
            return false;
        }
        if (a.getState() == DigToBottomArena.State.RUNNING) {
            rejectJoin(p, arenaName, "&c该场正在游戏中。");
            return false;
        }
        if (!a.isReady()) {
            rejectJoin(p, arenaName, "&c该场还没建好（OP 需要 /dig center、/dig build、/dig lobby）。");
            return false;
        }
        if (a.getBuiltHeight() != height()) {
            rejectJoin(p, arenaName, "&c柱高配置改过了（场内玻璃还是 &e" + a.getBuiltHeight() + " &c格），请 OP 重新 &e/dig build&c。");
            return false;
        }
        int max = Math.max(1, Math.min(a.getLanes(), plugin.getConfig().getInt("max-players", 8)));
        if (a.totalPlayers() >= max) {
            rejectJoin(p, arenaName, "&c人数已满（坑位 &e" + a.getLanes() + "&c，人数上限 &e" + max + "&c）。");
            return false;
        }
        // I1（D23）：先挂会话再入队；冲突直接拒绝，避免半加入状态
        DigToBottomSession sess = ensureSession(key);
        if (!sess.join(p)) {
            plugin.getLogger().info("[DigToBottom] 拒绝 " + p.getName() + " 加入 \"" + arenaName
                    + "\"：会话归属链挂接失败（" + sess.describe() + "）");
            // 刚建出来的空会话不能留着 —— 否则 doctor 里会多出一个"0 人的等待中场地"，
            // 看起来像场地被占了，其实谁也没进去
            if (sess.players().isEmpty()) {
                sessions.remove(key);
            }
            return false;
        }
        playerArena.put(id, arenaName);
        playerKey.put(id, key);
        a.getPlayers().add(id);
        a.recordJoin(id);   // D21：记加入 tick + 随机名次
        a.getNames().put(id, p.getName());
        p.getInventory().clear();
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.teleport(a.getLobby());
        p.setVelocity(new Vector(0, 0, 0));
        HubAPI.setGame(id, GAME);
        giveLeaveBed(p);
        if (p.isOp()) {
            giveStartPaper(p);
        }
        msg(p, "&a你已加入 &e" + arenaName + "&a，等待开始…");
        broadcast(a, "&e" + p.getName() + "&f 加入了（&e" + a.totalPlayers() + "&f 人）");
        HubAPI.announceJoin(GAME, p.getName(), arenaName);
        updateWaitCountdown(a);
        reportArena(a);
        return true;
    }

    public void leave(Player p) {
        leave(p, false);
    }

    /**
     * 离开场地。silent=true 用于主城「回大厅」回调：玩家本来就不在场中时静默返回，
     * 也不重复传送（由 HubCore 负责）。
     */
    public void leave(Player p, boolean silent) {
        UUID id = p.getUniqueId();
        String arenaName = playerArena.remove(id);
        String key = playerKey.remove(id);
        if (key == null) {
            key = arenaName;   // 老路径（没有房间）：键就是场地名
        }
        // I1：从该房间的会话上摘掉自己（漏了会让玩家卡在"已在等待中"）
        if (key != null) {
            DigToBottomSession sess = sessions.get(key);
            if (sess != null && !sess.state().isFinal()) {
                sess.leave(p);
            }
        }
        if (arenaName == null) {
            retireRoom(key);
            if (!silent) {
                msg(p, "&c你不在任何场中。");
            }
            return;
        }
        DigToBottomArena a = key == null ? null : arenaByKey(key);
        if (a != null) {
            boolean wasActive = a.getPlayers().remove(id);
            a.getLaneOf().remove(id);
            a.getMined().remove(id);
            HubAPI.clearGame(id);
            if (wasActive) {
                broadcast(a, "&e" + p.getName() + "&f 离开了（剩 &e" + a.totalPlayers() + "&f 人）");
            }
            if (a.getState() == DigToBottomArena.State.RUNNING) {
                checkHalf(a);
                if (a.getPlayers().isEmpty()) {
                    endGame(a, false);
                } else {
                    reportArena(a);
                }
            } else {
                updateWaitCountdown(a);
                reportArena(a);
            }
        }
        retireRoom(key);
        if (!silent) {
            HubAPI.teleportToLobby(p);
            msg(p, "&7你已离开。");
        }
    }

    /**
     * 房间实例空了就回收（不回收的话两个房间跑几十局会留一堆已死的 World 引用在
     * 区域声明和侧边栏里）。只回收"没有房间会话、没人、没在跑"的那一份。
     */
    private void retireRoom(String key) {
        if (key == null) {
            return;
        }
        DigToBottomArena live = liveArenas.get(key);
        if (live == null || key.equals(live.getName())) {
            return;   // 默认那一份（键就是场地名）不回收
        }
        if (live.getState() != DigToBottomArena.State.WAITING
                || !live.getPlayers().isEmpty() || !live.getFinished().isEmpty()) {
            return;
        }
        DigToBottomSession sess = sessions.get(key);
        if (sess != null && !sess.state().isFinal()) {
            return;
        }
        if (timers.containsKey(key)) {
            return;
        }
        WaitCountdown countdown = waits.remove(key);
        if (countdown != null) {
            countdown.cancel();
        }
        sessions.remove(key);
        keyArena.remove(key);
        liveArenas.remove(key);
        publishRegions();
    }

    public void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        String arenaName = playerArena.remove(id);
        String key = playerKey.remove(id);
        if (key == null) {
            key = arenaName;
        }
        if (arenaName == null) {
            return;
        }
        DigToBottomArena a = key == null ? null : arenaByKey(key);
        if (a == null) {
            return;
        }
        boolean wasActive = a.getPlayers().remove(id);
        a.getLaneOf().remove(id);
        a.getMined().remove(id);
        HubAPI.clearGame(id);
        if (wasActive) {
            broadcast(a, "&e" + p.getName() + "&f 离开了（剩 &e" + a.totalPlayers() + "&f 人）");
        }
        if (a.getState() == DigToBottomArena.State.RUNNING) {
            checkHalf(a);
            if (a.getPlayers().isEmpty()) {
                endGame(a, false);
            } else {
                reportArena(a);
            }
        } else {
            updateWaitCountdown(a);
            reportArena(a);
        }
    }

    // ---------- 开局 ----------

    private StartCountdown startCountdown;

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

    public boolean start(Player op, String arenaName) {
        DigToBottomArena a = resolveArena(arenaName);
        if (a == null) {
            msg(op, "&c场不存在。");
            return false;
        }
        if (a.getState() == DigToBottomArena.State.RUNNING) {
            msg(op, "&c该场已经在游戏中了。");
            return false;
        }
        if (!a.isReady()) {
            msg(op, "&c场地未建好：需要 /dig center、/dig build、/dig lobby。");
            return false;
        }
        if (a.getBuiltHeight() != height()) {
            msg(op, "&c柱高配置改过了（场内玻璃还是 &e" + a.getBuiltHeight() + " &c格），请先 &e/dig build&c。");
            return false;
        }
        int min = Math.max(1, plugin.getConfig().getInt("min-players", 1));
        if (a.getPlayers().size() < min) {
            msg(op, "&c人数不足（至少 &e" + min + "&c 人）。");
            return false;
        }
        if (a.getPlayers().size() > a.getLanes()) {
            msg(op, "&c人数超过坑位数。");
            return false;
        }

        // D22：倒计时的键用房间键，否则同名的两个房间会互相取消
        if (startCountdown().begin(a.roomKey(), new ArrayList<>(a.getPlayers()), () -> start(op, a.roomKey()))) {
            return true;
        }
        a.setState(DigToBottomArena.State.RUNNING);
        long round = a.nextRound();
        a.resetRoundState();
        wait(a).cancel();
        reportArena(a);

        List<UUID> ids = new ArrayList<>(a.getPlayers());
        Collections.shuffle(ids, random);
        List<Integer> laneOrder = new ArrayList<>();
        for (int i = 0; i < a.getLanes(); i++) {
            laneOrder.add(i);
        }
        Collections.shuffle(laneOrder, random);
        for (int i = 0; i < ids.size(); i++) {
            a.getLaneOf().put(ids.get(i), laneOrder.get(i));
            a.getMined().put(ids.get(i), 0);
        }

        fillPillars(a, rollPillar());

        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                continue;
            }
            a.getNames().put(id, p.getName());
            HubAPI.setPlaying(id, GAME);
            p.getInventory().clear();
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20);
            p.setFoodLevel(20);
            p.setFireTicks(0);
            p.setFallDistance(0f);
            for (org.bukkit.potion.PotionEffect effect : p.getActivePotionEffects()) {
                p.removePotionEffect(effect.getType());
            }
            int lane = a.getLaneOf().getOrDefault(id, 0);
            p.teleport(spawnOf(a, lane));
            p.setVelocity(new Vector(0, 0, 0));
            giveTools(p);
        }

        broadcast(a, "&6&l— 一掘到底 开始！&e 柱子高 " + height() + " 格，挖到最下面的基岩！—");
        startFreeze(a, round);
        reportArena(a);
        return true;
    }

    private boolean running(DigToBottomArena a, long round) {
        return a.getState() == DigToBottomArena.State.RUNNING && a.getRound() == round;
    }

    /** 3 秒冻结：发完道具后不能移动、不能挖方块。 */
    private void startFreeze(DigToBottomArena a, long round) {
        int seconds = countdownSeconds();
        a.setFrozen(true);
        a.setFreezeGraceUntil(System.currentTimeMillis() + 250L);
        for (int i = 0; i < seconds; i++) {
            final int left = seconds - i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!running(a, round)) {
                    return;
                }
                for (UUID id : a.getPlayers()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) {
                        Chat.actionBar(p, "&e&l开始倒计时 &f" + left);
                    }
                }
            }, i * 20L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!running(a, round)) {
                return;
            }
            a.setFrozen(false);
            broadcast(a, "&a&l开始！&e 挖到最下面的基岩！");
            for (UUID id : a.getPlayers()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Chat.title(p, "&a&l开始！", "&7按住左键往下挖", 2);
                }
            }
            startTimer(a, round);
        }, seconds * 20L);
    }

    private void startTimer(DigToBottomArena a, long round) {
        int seconds = Math.max(5, plugin.getConfig().getInt("game-seconds", 180));
        long now = System.currentTimeMillis();
        a.setRoundStartMs(now);
        a.setGameEndMs(now + seconds * 1000L);
        cancelTimer(a.roomKey());
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!running(a, round)) {
                cancelTimer(a.roomKey());
                return;
            }
            long left = remainingSeconds(a);
            if (left <= 0) {
                endGame(a, true);
                return;
            }
            for (UUID id : a.getPlayers()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Chat.actionBar(p, "&e剩余 &f" + left + "s &7| &f已完成 &e"
                            + a.getFinished().size() + "&7/&f" + a.totalPlayers());
                }
            }
            for (UUID id : a.getFinished().keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Chat.actionBar(p, "&7观战中 &7| &e剩余 " + left + "s");
                }
            }
            updateLiveBoard(a);
            reportArena(a);
        }, 20L, 20L);
        timers.put(a.roomKey(), task);
    }

    private void updateLiveBoard(DigToBottomArena a) {
        List<UUID> online = new ArrayList<>(a.getPlayers());
        online.sort(Comparator.comparingInt((UUID id) -> scoreOf(a, id)).reversed()
                .thenComparing(id -> a.getNames().getOrDefault(id, "?"), String.CASE_INSENSITIVE_ORDER));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        for (UUID id : online) {
            boolean done = a.getFinished().containsKey(id);
            lines.add(new LiveSidebar.Line("§e" + a.getNames().getOrDefault(id, "?")
                    + (done ? " §a✓" : ""), scoreOf(a, id)));
        }
        liveSidebar.update("digtobottom:" + a.roomKey(), "&6&l一掘到底 · 进度", online, lines);
    }

    private int scoreOf(DigToBottomArena a, UUID id) {
        int mined = a.getMined().getOrDefault(id, 0);
        return mined + (a.getFinished().containsKey(id) ? 100000 : 0);
    }

    private void cancelTimer(String arenaName) {
        BukkitTask task = timers.remove(arenaName);
        if (task != null) {
            task.cancel();
        }
    }

    public long remainingSeconds(DigToBottomArena a) {
        if (a.getGameEndMs() <= 0L) {
            return 0L;
        }
        return Math.max(0L, (a.getGameEndMs() - System.currentTimeMillis() + 999L) / 1000L);
    }

    // ---------- 挖掘 / 完成 ----------

    /** 玩家能不能挖这一格：对局中、未冻结、自己的坑位、柱体范围内。 */
    public boolean canBreak(Player p, DigToBottomArena a, Block block) {
        if (a.getState() != DigToBottomArena.State.RUNNING || a.isFrozen()) {
            return false;
        }
        UUID id = p.getUniqueId();
        if (!a.getPlayers().contains(id)) {
            return false;
        }
        Integer lane = a.getLaneOf().get(id);
        if (lane == null) {
            return false;
        }
        int[] pt = lanePoint(a, lane);
        if (pt == null || block.getX() != pt[0] || block.getZ() != pt[1]) {
            return false;
        }
        int y0 = a.getCenter().getBlockY();
        return block.getY() >= y0 && block.getY() < y0 + height();
    }

    /** 记录深度；挖掉最底层那一格时判定到达底部。 */
    public void onPillarBreak(Player p, DigToBottomArena a, Block block) {
        UUID id = p.getUniqueId();
        a.getMined().merge(id, 1, Integer::sum);
        if (block.getY() == a.getCenter().getBlockY()) {
            finish(a, p);
        }
    }

    private void finish(DigToBottomArena a, Player p) {
        UUID id = p.getUniqueId();
        if (a.getFinished().containsKey(id)) {
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - a.getRoundStartMs());
        a.getFinished().put(id, elapsed);
        a.getPlayers().remove(id);
        p.setGameMode(GameMode.SPECTATOR);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        HubAPI.clearGame(id);
        broadcast(a, "&6" + nameOf(a, id) + " &f挖到底了！第 &e" + a.getFinished().size() + " &f个完成");
        msg(p, "&7你已完成，可以先观战；输入 &f/dig leave &7退出。");
        Chat.actionBar(p, "&7已完成 &7| &e输入 /dig leave 退出");
        checkHalf(a);
        reportArena(a);
        if (a.getPlayers().isEmpty()) {
            endGame(a, false);
        }
    }

    /** 到达底部的人数达到一半时，把剩余时间缩短到配置值。 */
    private void checkHalf(DigToBottomArena a) {
        if (a.isShortened()) {
            return;
        }
        int total = a.totalPlayers();
        if (total <= 0) {
            return;
        }
        int need = (total + 1) / 2;
        if (a.getFinished().size() < need) {
            return;
        }
        a.setShortened(true);
        int shorten = Math.max(1, plugin.getConfig().getInt("shorten-seconds", 20));
        long left = remainingSeconds(a);
        if (left > shorten) {
            a.setGameEndMs(System.currentTimeMillis() + shorten * 1000L);
            broadcast(a, "&c&l过半玩家已到达底部！&e剩余时间缩短为 &e" + shorten + " &e秒");
        }
    }

    // ---------- 结算 ----------

    /** 一条排名结果。 */
    private static final class Placing {
        final UUID id;
        final boolean finished;
        final long timeMs;
        final int mined;
        int rank;

        Placing(UUID id, boolean finished, long timeMs, int mined) {
            this.id = id;
            this.finished = finished;
            this.timeMs = timeMs;
            this.mined = mined;
        }
    }

    private List<Placing> rank(DigToBottomArena a) {
        List<Placing> all = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : a.getFinished().entrySet()) {
            all.add(new Placing(e.getKey(), true, e.getValue(), height()));
        }
        for (UUID id : a.getPlayers()) {
            all.add(new Placing(id, false, 0L, a.getMined().getOrDefault(id, 0)));
        }
        all.sort((x, y) -> {
            if (x.finished != y.finished) {
                return x.finished ? -1 : 1;
            }
            if (x.finished) {
                return Long.compare(x.timeMs, y.timeMs);
            }
            return Integer.compare(y.mined, x.mined);
        });
        int rank = 0;
        for (int i = 0; i < all.size(); i++) {
            if (i == 0 || !samePlace(all.get(i), all.get(i - 1))) {
                rank = i + 1;
            }
            all.get(i).rank = rank;
        }
        return all;
    }

    private boolean samePlace(Placing x, Placing y) {
        return x.finished == y.finished && (x.finished ? x.timeMs == y.timeMs : x.mined == y.mined);
    }

    /**
     * 结束本局：广播前三名（平局同一名次）、写入战绩、送所有人回大厅、清空柱子。
     *
     * @param timeUp true = 时间到（未完成者按已挖深度排名）
     */
        /** 收场入口（I4）：先让该场地的会话 end()，再由 onEnd 回到 doEndGame。 */
    private void endGame(DigToBottomArena a, boolean timeUp) {
        pendingTimeUp = timeUp;
        DigToBottomSession sess = sessions.get(a.roomKey());
        if (sess == null || sess.state().isFinal()) {
            doEndGame(a.roomKey());
            return;
        }
        sess.end(EndReason.ROUND_END);
    }

    /** 收场参数暂存（玩家看到的结算不能变）。 */
    private boolean pendingTimeUp;

    /** 真正的收场逻辑（由 session.onEnd 回调）。 */
    void doEndGame(String arenaKey) {
        DigToBottomArena a = arenaByKey(arenaKey);
        if (a == null) {
            return;
        }
        boolean timeUp = pendingTimeUp;
        liveSidebar.hide("digtobottom:" + a.roomKey());
        if (a.getState() != DigToBottomArena.State.RUNNING) {
            return;
        }
        a.setState(DigToBottomArena.State.WAITING);
        a.setFrozen(false);
        cancelTimer(a.roomKey());
        a.setGameEndMs(0L);

        List<Placing> places = rank(a);
        broadcast(a, "&b&l========== 一掘到底 结束 ==========");
        if (timeUp) {
            broadcast(a, "&7时间到：未挖到底的按已挖深度排名。");
        }
        for (int line = 1; line <= 3; line++) {
            List<Placing> group = new ArrayList<>();
            for (Placing pl : places) {
                if (pl.rank == line) {
                    group.add(pl);
                }
            }
            if (group.isEmpty()) {
                continue;
            }
            String label = line == 1 ? "&e&l第一名" : line == 2 ? "&e第二名" : "&e第三名";
            StringBuilder names = new StringBuilder();
            for (Placing pl : group) {
                if (names.length() > 0) {
                    names.append("&7、&f");
                }
                names.append(nameOf(a, pl.id));
            }
            Placing sample = group.get(0);
            String detail = sample.finished
                    ? "&7(" + formatTime(sample.timeMs) + ")"
                    : "&7(未挖到底·已挖 " + sample.mined + " 格)";
            broadcast(a, label + "：&f" + names + " " + detail + " &6"
                    + Top3.stars(line == 1 ? 3 : (line == 2 ? 2 : 1)));
        }
        broadcast(a, "&b===================================");
        List<UUID> firsts = new ArrayList<>();
        List<UUID> seconds = new ArrayList<>();
        List<UUID> thirds = new ArrayList<>();
        for (Placing pl : places) {
            if (pl.rank == 1) {
                addStat("firsts", pl.id);
                firsts.add(pl.id);
            } else if (pl.rank == 2) {
                addStat("seconds", pl.id);
                seconds.add(pl.id);
            } else if (pl.rank == 3) {
                addStat("thirds", pl.id);
                thirds.add(pl.id);
            }
        }
        ArcadeBridge.report("digtobottom", firsts, seconds, thirds);
        saveData();

        Set<UUID> everyone = new LinkedHashSet<>(a.getFinished().keySet());
        everyone.addAll(a.getPlayers());
        for (UUID id : everyone) {
            playerArena.remove(id);
            playerKey.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.setGameMode(GameMode.SURVIVAL);
                p.setHealth(20);
                p.setFoodLevel(20);
                HubAPI.teleportToLobby(p);
            } else {
                HubAPI.clearGame(id);
            }
        }
        // 先送人再清柱子，避免还有人站在柱子里时清场把他们埋住
        clearLanes(a);
        a.getPlayers().clear();
        a.resetRoundState();
        reportArena(a);
        updateWaitCountdown(a);
    }

    private String formatTime(long ms) {
        long total = ms / 1000L;
        long minutes = total / 60L;
        long seconds = total % 60L;
        if (minutes > 0) {
            return minutes + "分" + seconds + "秒";
        }
        return String.format(Locale.ROOT, "%.1f秒", ms / 1000.0);
    }

    public String nameOf(DigToBottomArena a, UUID id) {
        String name = a == null ? null : a.getNames().get(id);
        if (name != null) {
            return name;
        }
        String offline = Bukkit.getOfflinePlayer(id).getName();
        return offline != null ? offline : id.toString().substring(0, 8);
    }

    // ---------- 道具 ----------

    private static final int TOOL_SLOT_PICKAXE = 0;
    private static final int TOOL_SLOT_AXE = 1;
    private static final int TOOL_SLOT_SHOVEL = 2;

    public void giveTools(Player p) {
        p.getInventory().setItem(TOOL_SLOT_PICKAXE, tool(Material.DIAMOND_PICKAXE, "&b&l钻石镐 &7(无限耐久)"));
        p.getInventory().setItem(TOOL_SLOT_AXE, tool(Material.DIAMOND_AXE, "&b&l钻石斧 &7(无限耐久)"));
        p.getInventory().setItem(TOOL_SLOT_SHOVEL, tool(Material.DIAMOND_SHOVEL, "&b&l钻石铲 &7(无限耐久)"));
    }

    private ItemStack tool(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color(name)));
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 是不是本插件发出去的钻石工具（用于禁止丢弃）。 */
    public boolean isTool(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(toolKey, PersistentDataType.BYTE);
    }

    public void giveLeaveBed(Player p) {
        ItemStack bed = new ItemStack(Material.RED_BED);
        ItemMeta meta = bed.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color("&c退出游戏 &7(右键)")));
            bed.setItemMeta(meta);
        }
        p.getInventory().setItem(8, bed);
    }

    public void giveStartPaper(Player p) {
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color("&a&l开始游戏 &7(右键)")));
            paper.setItemMeta(meta);
        }
        p.getInventory().setItem(4, paper);
    }

    public void handleStartPaper(Player p) {
        DigToBottomArena a = forPlayer(p);
        if (a == null || a.getState() != DigToBottomArena.State.WAITING) {
            return;
        }
        if (!p.isOp() && !p.hasPermission("dig.admin")) {
            msg(p, "&c只有管理员（OP 或 dig.admin）可以开始。");
            return;
        }
        start(p, a.roomKey());
    }

    // ---------- 上报主城 ----------

    /**
     * 上报给主城。
     *
     * <p>场地名**保留地图名**（{@code a.getName()}）而不是房间键：派对是靠"游戏注册的场地名"
     * 选图、再把 {@code {arena}} 填进 join/start 命令的，换成房间 id 会让 `dig join <房间id>`
     * 找不到地图。房间隔离靠的是 `RoomContext`（按玩家/按房间），不靠这个名字。
     */
    public void reportArena(DigToBottomArena a) {
        boolean playing = a.getState() == DigToBottomArena.State.RUNNING;
        String extra = null;
        if (playing) {
            extra = a.isFrozen()
                    ? "准备中 · 完成 " + a.getFinished().size() + "/" + a.totalPlayers()
                    : "剩余 " + remainingSeconds(a) + "s · 完成 " + a.getFinished().size() + "/" + a.totalPlayers();
        }
        HubAPI.setArena(GAME, a.getName(), playing ? GameState.PLAYING : GameState.WAITING, a.totalPlayers(), extra);
        Location spot = a.getSpectator();
        if (spot == null && a.getCenter() != null) {
            spot = a.getCenter().clone().add(0, height() + 6, 0);
        }
        HubAPI.setArenaSpectator(GAME, a.getName(), spot);
    }

    // ---------- 场地增删改 ----------

    public boolean createArena(String name) {
        if (!validName(name) || arenas.containsKey(name)) {
            return false;
        }
        DigToBottomArena a = new DigToBottomArena(name);
        arenas.put(name, a);
        saveAll();
        reportArena(a);
        return true;
    }

    public boolean removeArena(String name) {
        DigToBottomArena a = arenas.get(name);
        if (a == null || a.getState() == DigToBottomArena.State.RUNNING || a.totalPlayers() > 0) {
            return false;
        }
        WaitCountdown countdown = waits.remove(name);
        if (countdown != null) {
            countdown.cancel();
        }
        cancelTimer(name);
        arenas.remove(name);
        liveArenas.entrySet().removeIf(e -> e.getValue().getName().equals(name));
        HubAPI.removeArena(GAME, name);
        saveAll();
        return true;
    }

    public boolean renameArena(String oldName, String newName) {
        DigToBottomArena a = arenas.get(oldName);
        if (a == null || !validName(newName) || arenas.containsKey(newName)
                || a.getState() == DigToBottomArena.State.RUNNING || a.totalPlayers() > 0) {
            return false;
        }
        WaitCountdown countdown = waits.remove(oldName);
        if (countdown != null) {
            countdown.cancel();
        }
        cancelTimer(oldName);
        arenas.remove(oldName);
        a.setName(newName);
        arenas.put(newName, a);
        for (DigToBottomArena live : liveArenas.values()) {
            if (live.getName().equals(oldName)) {
                live.setName(newName);
            }
        }
        HubAPI.removeArena(GAME, oldName);
        saveAll();
        reportArena(a);
        return true;
    }

    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > 20) {
            return false;
        }
        for (char c : name.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }

    // ---------- 战绩 ----------

    public int getStat(String key, UUID id) {
        return data == null ? 0 : data.getInt(key + "." + id, 0);
    }

    private void addStat(String key, UUID id) {
        if (data == null || id == null) {
            return;
        }
        data.set(key + "." + id, getStat(key, id) + 1);
    }

    /** OP 清空本游戏全部前三名记录。 */
    public void resetStats() {
        if (data != null) {
            data.set("firsts", null);
            data.set("seconds", null);
            data.set("thirds", null);
            saveData();
        }
    }

    private void saveData() {
        if (data == null) {
            return;
        }
        try {
            PlayerStats.save(NAMESPACE, data);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存玩家记录失败：" + ex.getMessage());
        }
    }

    private void broadcast(DigToBottomArena a, String message) {
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.send(p, message);
            }
        }
        for (UUID id : a.getFinished().keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.send(p, message);
            }
        }
    }

    private void msg(Player p, String message) {
        if (p == null) {
            // 控制台/倒计时开局没有"发起人"：照样留一条控制台可查的记录
            plugin.getLogger().info("[DigToBottom] " + org.bukkit.ChatColor.stripColor(
                    org.bukkit.ChatColor.translateAlternateColorCodes('&', message)));
            return;
        }
        Chat.send(p, message);
    }
}
