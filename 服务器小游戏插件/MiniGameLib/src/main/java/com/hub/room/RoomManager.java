package com.hub.room;

import com.hub.diagnostics.Failures;
import com.hub.region.ArenaRegion;
import com.hub.world.WorldNames;
import com.hub.region.RegionRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 房间引擎：模板 → 实例世界 → 回收。
 *
 * <p>设计见 {@code docs/房间引擎设计.md}。R1 只做引擎本身，不绑定任何游戏。
 *
 * <p><b>三条实现铁律</b>（都在 AGENTS.md §0.5 里）：
 * <ol>
 *   <li>复制用 {@code cp -a --reflink=auto}，<b>绝不能用 {@code always}</b>（生产是 ext4，会失败）；</li>
 *   <li>复制后必须删世界身份（{@code data/paper/metadata.dat} / {@code session.lock} / {@code level.dat_old}），
 *       否则 Paper 报 {@code is a duplicate of another world}；</li>
 *   <li>{@code createWorld} / {@code unloadWorld} 只能在主线程。</li>
 * </ol>
 */
public final class RoomManager {

    /**
     * 房间里的**一个世界实例**：从某个模板复制出来、按某张地图的计划初始化。
     *
     * <p>为什么房间需要多个：派对的一张地图散在 3 个世界（`world_mini_game` / `world_pvp_mini_games` /
     * `world_tr`），一个派对房间必须同时持有它们。
     */
    public static final class WorldInstance {
        public final String templateKey;
        /** 该世界要用的区域与规则（来自 {@link GameMap.WorldPlan}）；旧路径可能为 null。 */
        public final GameMap.WorldPlan plan;
        public World world;

        public WorldInstance(String templateKey, GameMap.WorldPlan plan, World world) {
            this.templateKey = templateKey;
            this.plan = plan;
            this.world = world;
        }
    }

    /** 一个房间实例。 */
    public static final class Room {
        public final String roomId;
        public WorldTemplate template;
        /**
         * 这个房间服务的是模板世界里的**哪张地图**（= 模板 regions 里的 field name）。
         *
         * <p>为什么需要：一个模板世界可能装多张地图（`world_mini_game` 就是），
         * 而一个房间只服务一张。空串表示"整个模板世界算一张图"。
         */
        public String arena = "";
        public World world;
        public final long createdAt = System.currentTimeMillis();
        public long lastActiveAt = System.currentTimeMillis();

        /** 这个房间服务的是哪个游戏 / 哪张地图（按地图造房时填；旧的按模板造房时为空）。 */
        public String game = "";
        public String map = "";

        /**
         * 房间持有的**全部世界实例**（一张地图可能散在多个世界，例如派对就有 3 个）。
         *
         * <p>{@link #world} 始终等于**第一个**实例的世界，保留给"单世界房间"的既有代码使用。
         */
        public final java.util.List<WorldInstance> instances = new java.util.ArrayList<>();

        /**
         * 入口点：{@code /hub join} 后玩家先出现在这。
         *
         * <p>**入口点 ≠ 出生点**：这里只保证"进得来"，游戏侧 init 可以再按玩家设置具体出生点。
         */
        public Location entry;

        Room(String roomId, WorldTemplate template) {
            this.roomId = roomId;
            this.template = template;
        }

        /** 某个模板键对应的世界实例（多世界房间按模板键取）。 */
        public WorldInstance instanceOf(String templateKey) {
            for (WorldInstance wi : instances) {
                if (wi.templateKey.equals(templateKey)) {
                    return wi;
                }
            }
            return null;
        }

        /** 所有世界实例里的世界（含主世界）。 */
        public java.util.List<World> allWorlds() {
            java.util.List<World> out = new java.util.ArrayList<>();
            for (WorldInstance wi : instances) {
                if (wi.world != null) {
                    out.add(wi.world);
                }
            }
            if (out.isEmpty() && world != null) {
                out.add(world);
            }
            return out;
        }

        /** 世界里的人（含旁观）。 */
        public int playerCount() {
            return world == null ? 0 : world.getPlayers().size();
        }

        public long ageSeconds() {
            return (System.currentTimeMillis() - createdAt) / 1000L;
        }

        public long idleSeconds() {
            return (System.currentTimeMillis() - lastActiveAt) / 1000L;
        }

        public String describe() {
            return roomId + " ← " + template.key()
                    + " [" + (world == null ? "未加载" : world.getName()) + "]"
                    + (arena == null || arena.isBlank() ? "" : " 地图=" + arena)
                    + " 人=" + playerCount() + " 年龄=" + ageSeconds() + "s 空置=" + idleSeconds() + "s";
        }
    }

    private static final String TEMPLATE_DIR_NAME = "dimensions/minecraft";

    private static final Map<String, WorldTemplate> TEMPLATES = new LinkedHashMap<>();
    private static final Map<String, Room> ROOMS = new LinkedHashMap<>();

    private static JavaPlugin plugin;
    private static BukkitTask reaper;

    private static int maxRooms = 10;
    private static int maxPerGame = 3;
    private static long idleSeconds = 300;
    private static boolean ready;

    private RoomManager() {
    }

    // ---------- 初始化 ----------

    /** 在 MiniGameLib 的 onEnable 里调。读 templates.yml，起回收任务。 */
    public static void init(JavaPlugin lib) {
        plugin = lib;
        TEMPLATES.clear();
        ROOMS.clear();
        WORLD_INDEX.clear();
        ready = false;
        try {
            // 地图登记表（(游戏, 地图) -> 世界清单 + 区域 + 规则）。读失败不致命：
            // 没有它只是"不能按地图造房"，已有模板/房间照常工作。
            try {
                GameMaps.load(lib);
            } catch (RuntimeException ex) {
                com.hub.diagnostics.Failures.recordAndWarn("MiniGameLib.maps.yml", ex);
            }
            File cfgFile = new File(lib.getDataFolder(), "templates.yml");
            if (!cfgFile.isFile()) {
                lib.saveResource("templates.yml", false);
            }
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(cfgFile);

            ConfigurationSection limits = cfg.getConfigurationSection("limits");
            if (limits != null) {
                maxRooms = limits.getInt("max-rooms", 0);   // <= 0 表示不限制
                maxPerGame = Math.max(1, limits.getInt("max-per-game", 3));
                idleSeconds = Math.max(60, limits.getLong("idle-seconds", 300));
            }

            ConfigurationSection ts = cfg.getConfigurationSection("templates");
            File base = worldDimDir();
            if (ts != null) {
                for (String key : ts.getKeys(false)) {
                    ConfigurationSection t = ts.getConfigurationSection(key);
                    if (t == null) {
                        continue;
                    }
                    String folder = t.getString("folder", WorldNames.TEMPLATE + WorldNames.sanitize(key));
                    File dir = new File(base, folder);
                    ConfigurationSection r = t.getConfigurationSection("rules");
                    WorldTemplate.Rules rules = r == null ? null
                            : new WorldTemplate.Rules(
                            r.getBoolean("pvp", false),
                            r.getString("difficulty", "NORMAL"),
                            r.getBoolean("mob-spawning", false),
                            r.getBoolean("keep-inventory", false),
                            r.getLong("fixed-time", 6000L));
                    List<WorldTemplate.Box> boxes = new ArrayList<>();
                    for (Map<?, ?> raw : t.getMapList("regions")) {
                        Object nameObj = raw.get("name");
                        boxes.add(new WorldTemplate.Box(
                                nameObj == null ? "" : String.valueOf(nameObj),
                                dbl(raw.get("x1")), dbl(raw.get("y1")), dbl(raw.get("z1")),
                                dbl(raw.get("x2")), dbl(raw.get("y2")), dbl(raw.get("z2"))));
                    }
                    TEMPLATES.put(key, new WorldTemplate(key,
                            t.getString("game", key), folder, dir, rules, boxes));
                    if (!dir.isDirectory()) {
                        lib.getLogger().warning("[房间] 模板 " + key + " 的目录不存在：" + dir.getPath());
                    }
                }
            }
            ready = true;
            lib.getLogger().info("[房间] 模板 " + TEMPLATES.size() + " 个，"
                    + "上限：全服 " + (maxRooms <= 0 ? "不限" : maxRooms) + " / 每游戏 " + maxPerGame
                    + "，空置 " + idleSeconds + "s 回收。世界目录：" + base.getPath());

            if (reaper == null || reaper.isCancelled()) {
                reaper = Bukkit.getScheduler().runTaskTimer(lib, RoomManager::reapQuietly, 20L * 60, 20L * 60);
            }
        } catch (Throwable ex) {
            Failures.recordAndWarn("RoomManager.init", ex);
        }
    }

    /** 关服：把所有房间都卸载掉（不删目录，留给下次启动回收/手工清）。 */
    public static void shutdown() {
        if (reaper != null) {
            reaper.cancel();
            reaper = null;
        }
        for (Room room : new ArrayList<>(ROOMS.values())) {
            try {
                if (room.world != null) {
                    Bukkit.unloadWorld(room.world, false);
                }
            } catch (RuntimeException ignored) {
                // 关服阶段尽力而为
            }
        }
        ROOMS.clear();
        WORLD_INDEX.clear();
        TEMPLATES.clear();
        ready = false;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    // ---------- 查询 ----------

    public static boolean isReady() {
        return ready;
    }

    public static List<WorldTemplate> templates() {
        return new ArrayList<>(TEMPLATES.values());
    }

    public static WorldTemplate template(String key) {
        return key == null ? null : TEMPLATES.get(key);
    }

    public static List<Room> rooms() {
        return new ArrayList<>(ROOMS.values());
    }

    /** 世界名 -> 它所属的房间（房间名与世界名已解耦，按世界名也要能查到）。 */
    private static final java.util.Map<String, Room> WORLD_INDEX = new LinkedHashMap<>();

    /**
     * 按**房间名**或**世界名**取房间。
     *
     * <p>为什么两者都接受：房间名（{@code room_x_1}）与世界名（{@code room_x_1_1}）现在是分开的，
     * 而既有代码/命令有些地方还在传世界名。
     */
    public static Room room(String roomIdOrWorld) {
        if (roomIdOrWorld == null) {
            return null;
        }
        Room r = ROOMS.get(roomIdOrWorld);
        return r != null ? r : WORLD_INDEX.get(roomIdOrWorld);
    }

    public static int roomCount() {
        return ROOMS.size();
    }

    public static int roomCountOf(String gameKey) {
        int n = 0;
        for (Room r : ROOMS.values()) {
            if (r.template.serves(gameKey)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 世界目录：Paper Moonrise 布局下是 {@code <server>/world/dimensions/minecraft}。
     *
     * <p>不能直接拼 {@code Bukkit.getWorldContainer()} —— 它返回的是 {@code "."}（server 根），
     * 真正的维度目录还多一层 level 名。最稳的办法是**从已加载世界的目录反推**：
     * 若某个世界的目录父级叫 {@code minecraft}，那它就是我们要的目录。
     */
    /**
     * 某个模板当前**存活房间**的世界（R2 坐标解耦用）。
     *
     * <p>同一模板可能有多个房间同时在跑，这里返回"第一个"；多房间并行该用哪个由 session 决定（R3 之后）。
     * 没有房间返回 {@code null} —— 调用方必须当成"场地未就绪"。
     */
    public static World worldOfTemplate(String templateKey) {
        if (templateKey == null) {
            return null;
        }
        for (Room room : ROOMS.values()) {
            if (room.world != null && room.template.key().equals(templateKey)) {
                return room.world;
            }
        }
        return null;
    }

    /**
     * 某个游戏**当前唯一活跃的房间**（房间化 R1.5 用）。
     *
     * <p>为什么是"唯一"：现在的游戏都还是**单局结构**（一份等待队列、一块场地），
     * 同时开两个房间它们会互相覆盖。所以先按"一个游戏同时只接一个房间"来做；
     * 等游戏内部状态按房间拆开（D22 的后半段）之后，这里换成"按房间 id 取"。
     *
     * @return 恰好有一个房间时返回它；0 个或多个都返回 null（多个时调用方必须显式指定房间）
     */
    public static Room activeFor(String gameKey) {
        if (gameKey == null) {
            return null;
        }
        Room only = null;
        int n = 0;
        for (Room room : ROOMS.values()) {
            if (room.world != null && room.template.serves(gameKey)) {
                only = room;
                n++;
            }
        }
        return n == 1 ? only : null;
    }

    /** 房间世界名 → 模板键；不是房间世界则返回 {@code null}。 */
    public static String templateKeyOfRoomWorld(String worldName) {
        if (worldName == null) {
            return null;
        }
        Room room = room(worldName);
        return room == null ? null : room.template.key();
    }

    private static File worldDimDir() {
        for (World w : Bukkit.getWorlds()) {
            File folder = w.getWorldFolder();
            File parent = folder == null ? null : folder.getParentFile();
            if (parent != null && "minecraft".equals(parent.getName())
                    && parent.getParentFile() != null
                    && "dimensions".equals(parent.getParentFile().getName())) {
                if (parent.isDirectory()) {
                    return parent;
                }
            }
        }
        // 回落：<worldContainer>/<levelName>/dimensions/minecraft，再回落 <worldContainer>/dimensions/minecraft
        File container = Bukkit.getWorldContainer();
        String level = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
        for (File candidate : new File[]{
                new File(container, level + "/" + TEMPLATE_DIR_NAME),
                new File(container, TEMPLATE_DIR_NAME)}) {
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        File fallback = new File(container, level + "/" + TEMPLATE_DIR_NAME);
        fallback.mkdirs();
        return fallback;
    }

    // ---------- 创建 ----------

    /**
     * 按**地图**建一个房间：一张地图可能需要**多个世界实例**（派对就有 3 个）。
     *
     * <p>流程与 {@link #create(String, String)} 相同，只是对 {@link GameMap#worlds()} 里每个计划
     * 各做一次"复制 → 删世界身份 → 加载 → 套规则 → 注册区域"，然后合成**一个** Room：
     * <ul>
     *   <li>第一个世界实例的世界就是 {@code room.world}（兼容单世界的既有代码）；</li>
     *   <li>第 2 个以后的世界名是 {@code <roomId>_2}、{@code <roomId>_3}…（房间名与世界名解耦）；</li>
     *   <li>区域登记用 {@code map.game()}，这样"一个模板世界被多个游戏引用"时归属不会串。</li>
     * </ul>
     *
     * @param map       地图定义（来自 maps.yml）
     * @param desiredId 期望的房间名；为空则自动生成
     * @return 成功返回 Room；失败返回 null（原因写进日志/Failures）
     */
    public static synchronized Room createForMap(GameMap map, String desiredId) {
        if (!ready) {
            return fail("房间引擎未就绪");
        }
        if (map == null || map.worlds().isEmpty()) {
            return fail("地图没有声明任何世界");
        }
        if (maxRooms > 0 && ROOMS.size() >= maxRooms) {
            reap();
            if (ROOMS.size() >= maxRooms) {
                return fail("全服房间数已达上限 " + maxRooms);
            }
        }
        if (roomCountOf(map.game()) >= maxPerGame) {
            return fail("游戏 " + map.game() + " 的房间数已达上限 " + maxPerGame);
        }
        String roomId = desiredId == null || desiredId.isBlank()
                ? nextRoomId(map.game()) : sanitize(desiredId);
        if (Bukkit.getWorld(roomId) != null || ROOMS.containsKey(roomId)) {
            return fail("房间名已被占用：" + roomId);
        }

        Room room = new Room(roomId, null);
        room.game = map.game();
        room.map = map.name();
        java.util.List<File> created = new java.util.ArrayList<>();
        long t0 = System.nanoTime();

        int index = 0;
        for (GameMap.WorldPlan plan : map.worlds()) {
            WorldTemplate tpl = TEMPLATES.get(plan.templateKey());
            if (tpl == null || !tpl.dir().isDirectory()) {
                rollbackWorlds(room, created);
                return fail("地图 " + map.name() + " 引用的模板不存在：" + plan.templateKey());
            }
            // 命名统一：<房间名>_1_<世界序号>（第一个世界也是 _1_1，不再特殊）
            String worldName = roomId + "_1_" + (index + 1);
            File dest = new File(worldDimDir(), worldName);
            try {
                copyDirectory(tpl.dir(), dest);
            } catch (Exception ex) {
                Failures.recordAndWarn("RoomManager.copy/" + worldName, ex);
                deleteDir(dest);
                rollbackWorlds(room, created);
                return fail("复制模板失败：" + ex.getMessage());
            }
            created.add(dest);
            deleteQuietly(new File(dest, "data/paper/metadata.dat"));
            deleteQuietly(new File(dest, "session.lock"));
            deleteQuietly(new File(dest, "level.dat_old"));

            World w = null;
            try {
                w = Bukkit.createWorld(new WorldCreator(worldName));
            } catch (RuntimeException ex) {
                Failures.recordAndWarn("RoomManager.load/" + worldName, ex);
            }
            if (w == null) {
                rollbackWorlds(room, created);
                return fail("世界加载失败（已回滚）：" + worldName);
            }
            if (plan.rules() != null) {
                applyRules(w, plan.rules());
            }
            for (GameMap.RegionPlan region : plan.regions()) {
                try {
                    RegionRegistry.register(new ArenaRegion(map.game(), region.name(), roomId,
                            region.x1(), region.y1(), region.z1(), region.x2(), region.y2(), region.z2()));
                } catch (RuntimeException ex) {
                    Failures.recordAndWarn("RoomManager.region/" + roomId, ex);
                }
            }
            room.instances.add(new WorldInstance(plan.templateKey(), plan, w));
            if (index == 0) {
                room.world = w;
                room.template = tpl;
            }
            index++;
        }
        if (room.template == null) {
            rollbackWorlds(room, created);
            return fail("地图 " + map.name() + " 没有可用的世界");
        }

        // 入口点：map 里写了就用它（定位到对应世界实例）；否则用第一块区域的中心。
        room.entry = resolveEntry(room, map);

        long tLoad = System.nanoTime();
        ROOMS.put(roomId, room);
        for (WorldInstance wi : room.instances) {
            if (wi.world != null) {
                WORLD_INDEX.put(wi.world.getName(), room);
            }
        }
        log("已创建房间 " + room.describe() + "（" + room.instances.size() + " 个世界 / 地图 "
                + map.name() + "，总耗时 " + (tLoad - t0) / 1_000_000 + "ms）");
        return room;
    }

    /** 把 map 的入口点解析成 Location（找不到就退回第一块区域中心）。 */
    private static Location resolveEntry(Room room, GameMap map) {
        GameMap.Entry e = map.entry();
        if (e != null) {
            WorldInstance wi = room.instanceOf(e.templateKey());
            World w = wi != null && wi.world != null ? wi.world : room.world;
            if (w != null) {
                return new Location(w, e.x(), e.y(), e.z());
            }
        }
        if (!room.instances.isEmpty() && !room.instances.get(0).plan.regions().isEmpty()) {
            GameMap.RegionPlan r = room.instances.get(0).plan.regions().get(0);
            World w = room.instances.get(0).world;
            if (w != null) {
                return new Location(w, r.centerX(), r.y1(), r.centerZ());
            }
        }
        return room.world != null ? room.world.getSpawnLocation() : null;
    }

    /** 建到一半失败：把已经造出来的世界和区域都回滚掉。 */
    private static void rollbackWorlds(Room room, java.util.List<File> created) {
        for (WorldInstance wi : room.instances) {
            if (wi.world != null) {
                try {
                    Bukkit.unloadWorld(wi.world, false);
                } catch (RuntimeException ex) {
                    Failures.recordAndWarn("RoomManager.rollback-unload/" + wi.templateKey, ex);
                }
            }
        }
        room.instances.clear();
        for (File dir : created) {
            deleteDir(dir);
        }
    }

    /**
     * 从模板建一个房间。**同步**（R1 简化：复制 50–115ms，管理员命令可接受）。
     *
     * @return 成功返回 Room；失败返回 null（原因已写进日志/Failures）
     */
    public static synchronized Room create(String templateKey, String desiredId) {
        if (!ready) {
            return fail("房间引擎未就绪");
        }
        WorldTemplate tpl = TEMPLATES.get(templateKey);
        if (tpl == null) {
            return fail("没有这个模板：" + templateKey);
        }
        if (!tpl.dir().isDirectory()) {
            return fail("模板目录不存在：" + tpl.dir().getPath());
        }
        if (maxRooms > 0 && ROOMS.size() >= maxRooms) {
            reap();
            if (ROOMS.size() >= maxRooms) {
                return fail("全服房间数已达上限 " + maxRooms + "（先 /hub room delete 或等回收）");
            }
        }
        if (roomCountOf(tpl.gameKey()) >= maxPerGame) {
            return fail("游戏 " + tpl.gameKey() + " 的房间数已达上限 " + maxPerGame);
        }

        String roomId = desiredId == null || desiredId.isBlank()
                ? nextRoomId(tpl.gameKey()) : sanitize(desiredId);
        if (Bukkit.getWorld(roomId) != null || ROOMS.containsKey(roomId)) {
            return fail("房间名已被占用：" + roomId);
        }

        File dest = new File(worldDimDir(), roomId);

        long t0 = System.nanoTime();
        // 2) 复制模板（--reflink=auto：btrfs 白拿 0 拷贝，ext4 自动回落）
        try {
            copyDirectory(tpl.dir(), dest);
        } catch (Exception ex) {
            Failures.recordAndWarn("RoomManager.copy/" + roomId, ex);
            deleteDir(dest);
            return fail("复制模板失败：" + ex.getMessage());
        }

        // 3) 删世界身份（不删就会被 Paper 当成重复世界拒绝加载）
        deleteQuietly(new File(dest, "data/paper/metadata.dat"));
        deleteQuietly(new File(dest, "session.lock"));
        deleteQuietly(new File(dest, "level.dat_old"));

        long tCopy = System.nanoTime();
        // 4) 加载世界（必须主线程）
        Room room = new Room(roomId, tpl);
        try {
            room.world = Bukkit.createWorld(new WorldCreator(roomId));
        } catch (RuntimeException ex) {
            Failures.recordAndWarn("RoomManager.load/" + roomId, ex);
        }
        if (room.world == null) {
            deleteDir(dest);
            return fail("世界加载失败（已回滚目录）：" + roomId);
        }

        // 5) 套规则 —— 只有模板显式声明了 rules 才覆盖。
        //    模板文件里的 data/minecraft/game_rules.dat 本身就有规则，拷贝时已经带过来；
        //    无条件再套一遍等于让配置盖掉设计者的 /gamerule（违反"模板即事实"）。
        if (tpl.rules() != null) {
            applyRules(room.world, tpl.rules());
        }

        // 6) 注册区域（模板内坐标 + 房间世界名）
        for (WorldTemplate.Box box : tpl.regions()) {
            try {
                RegionRegistry.register(new ArenaRegion(tpl.gameKey(), box.arenaName(), roomId,
                        box.x1(), box.y1(), box.z1(), box.x2(), box.y2(), box.z2()));
            } catch (RuntimeException ex) {
                Failures.recordAndWarn("RoomManager.region/" + roomId, ex);
            }
        }

        long tLoad = System.nanoTime();
        ROOMS.put(roomId, room);
        log("已创建房间 " + room.describe()
                + String.format("（复制 %dms / 加载 %dms / 总计 %dms）",
                (tCopy - t0) / 1_000_000, (tLoad - tCopy) / 1_000_000, (tLoad - t0) / 1_000_000));
        return room;
    }

    /**
     * 异步创建：**复制在异步线程，{@code createWorld} 回主线程**。
     *
     * <p>为什么要这样：Bukkit 的 tick 循环是单线程的，主线程上一次几十毫秒的同步文件操作
     * 就会让所有玩家一起卡。复制模板是 8–11ms（冷缓存可达上百毫秒），挪走它能让主线程只承担
     * {@code createWorld} 的约 24ms。
     *
     * @param done 创建完成（成功返回 Room，失败返回 null）后**在主线程**回调
     */
    /** 建房间并记下它服务的是哪张地图（arena = 模板 regions 里的 name）。 */
    public static void createAsync(String templateKey, String desiredRoomId, String arenaName,
                                   java.util.function.Consumer<Room> done) {
        createAsync(templateKey, desiredRoomId, room -> {
            if (room != null && arenaName != null && !arenaName.isBlank()) {
                room.arena = arenaName;
            }
            done.accept(room);
        });
    }

    public static void createAsync(String templateKey, String desiredRoomId,
                                   java.util.function.Consumer<Room> done) {
        if (!ready || plugin == null) {
            Failures.record("RoomManager.createAsync", new IllegalStateException("房间引擎未就绪"));
            done.accept(null);
            return;
        }
        WorldTemplate tpl = TEMPLATES.get(templateKey);
        if (tpl == null || !tpl.dir().isDirectory()) {
            log("[房间] 创建失败：模板不存在或目录缺失 " + templateKey);
            done.accept(null);
            return;
        }
        final String roomId;
        synchronized (RoomManager.class) {
            if (roomCountOf(tpl.gameKey()) >= maxPerGame) {
                log("[房间] 创建失败：游戏 " + tpl.gameKey() + " 房间数已达上限 " + maxPerGame);
                done.accept(null);
                return;
            }
            roomId = desiredRoomId == null || desiredRoomId.isBlank()
                    ? nextRoomId(tpl.gameKey()) : sanitize(desiredRoomId);
            if (Bukkit.getWorld(roomId) != null || ROOMS.containsKey(roomId)) {
                log("[房间] 创建失败：房间名已被占用 " + roomId);
                done.accept(null);
                return;
            }
        }

        // ---- 异步：复制 + 删世界身份 ----
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long t0 = System.nanoTime();
            File dest = new File(worldDimDir(), roomId);
            try {
                copyDirectory(tpl.dir(), dest);
                deleteQuietly(new File(dest, "data/paper/metadata.dat"));
                deleteQuietly(new File(dest, "session.lock"));
                deleteQuietly(new File(dest, "level.dat_old"));
            } catch (Exception ex) {
                Failures.recordAndWarn("RoomManager.copyAsync/" + roomId, ex);
                deleteDir(dest);
                Bukkit.getScheduler().runTask(plugin, () -> done.accept(null));
                return;
            }
            long tCopy = System.nanoTime();
            // ---- 回主线程：加载世界 + 后续 ----
            Bukkit.getScheduler().runTask(plugin, () -> {
                Room room = finishCreate(roomId, tpl, tCopy - t0);
                done.accept(room);
            });
        });
    }

    /** 主线程侧的收尾：createWorld → 套规则 → 注册区域 → 记账。 */
    private static Room finishCreate(String roomId, WorldTemplate tpl, long copyNanos) {
        long t1 = System.nanoTime();
        File dest = new File(worldDimDir(), roomId);
        Room room = new Room(roomId, tpl);
        try {
            room.world = Bukkit.createWorld(new WorldCreator(roomId));
        } catch (RuntimeException ex) {
            Failures.recordAndWarn("RoomManager.load/" + roomId, ex);
        }
        if (room.world == null) {
            deleteDir(dest);
            log("[房间] 世界加载失败（已回滚目录）：" + roomId);
            return null;
        }
        if (tpl.rules() != null) {
            applyRules(room.world, tpl.rules());
        }
        for (WorldTemplate.Box box : tpl.regions()) {
            try {
                RegionRegistry.register(new ArenaRegion(tpl.gameKey(), box.arenaName(), roomId,
                        box.x1(), box.y1(), box.z1(), box.x2(), box.y2(), box.z2()));
            } catch (RuntimeException ex) {
                Failures.recordAndWarn("RoomManager.region/" + roomId, ex);
            }
        }
        long t2 = System.nanoTime();
        ROOMS.put(roomId, room);
        log("已创建房间 " + room.describe()
                + String.format("（异步复制 %dms / 主线程加载 %dms）",
                copyNanos / 1_000_000, (t2 - t1) / 1_000_000));
        return room;
    }

    /** 删一个房间：卸载 → 删目录 → 撤销区域 → 记账。 */
    public static synchronized boolean delete(String roomId) {
        Room room = ROOMS.get(roomId);
        if (room == null) {
            return false;
        }
        Room gone = ROOMS.remove(roomId);
        if (gone != null) {
            for (WorldInstance wi : gone.instances) {
                if (wi.world != null) {
                    WORLD_INDEX.remove(wi.world.getName());
                }
            }
        }

        if (room.world != null) {
            // 世界里还有人：先送回主城，否则 unload 会失败
            for (Player p : new ArrayList<>(room.world.getPlayers())) {
                try {
                    com.hub.api.HubAPI.teleportToLobby(p);
                } catch (RuntimeException ignored) {
                    p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
                }
            }
            try {
                Bukkit.unloadWorld(room.world, false);
            } catch (RuntimeException ex) {
                Failures.recordAndWarn("RoomManager.unload/" + roomId, ex);
            }
        }
        deleteDir(new File(worldDimDir(), roomId));
        // 只撤这个房间声明的区域（别用 unregisterGame，那会把该游戏的所有区域都清掉）
        for (WorldTemplate.Box box : room.template.regions()) {
            RegionRegistry.unregister(room.template.gameKey(), box.arenaName(), roomId);
        }
        log("已删除房间 " + roomId);
        return true;
    }

    /** 空置回收。返回本次回收的房间名。 */
    public static synchronized List<String> reap() {
        List<String> reaped = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Room room : new ArrayList<>(ROOMS.values())) {
            boolean empty = room.playerCount() == 0;
            if (!empty) {
                room.lastActiveAt = now;   // 有人在就不算空置
                continue;
            }
            if (room.idleSeconds() >= idleSeconds) {
                if (delete(room.roomId)) {
                    reaped.add(room.roomId);
                }
            }
        }
        return reaped;
    }

    private static void reapQuietly() {
        try {
            List<String> reaped = reap();
            if (!reaped.isEmpty()) {
                log("[房间] 空置回收 " + reaped.size() + " 个：" + String.join(", ", reaped));
            }
        } catch (Throwable ex) {
            Failures.recordAndWarn("RoomManager.reap", ex);
        }
    }

    // ---------- 工具 ----------

    private static void applyRules(World w, WorldTemplate.Rules r) {
        w.setPVP(r.pvp());
        try {
            w.setDifficulty(Difficulty.valueOf(r.difficulty().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            w.setDifficulty(Difficulty.NORMAL);
        }
        w.setGameRule(GameRule.DO_MOB_SPAWNING, r.mobSpawning());
        w.setGameRule(GameRule.KEEP_INVENTORY, r.keepInventory());
        if (r.fixedTime() >= 0) {
            w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            w.setTime(r.fixedTime());
        }
    }

    /**
     * 复制目录：优先用 {@code cp -a --reflink=auto}（外部命令，btrfs 上 0 拷贝、ext4 上自动回落）。
     * 没有 cp 的极端情况下回落到 Java 逐字节复制。
     */
    private static void copyDirectory(File src, File dst) throws IOException, InterruptedException {
        deleteDir(dst);
        if (!dst.mkdirs() && !dst.isDirectory()) {
            throw new IOException("建不出目录 " + dst.getPath());
        }
        ProcessBuilder pb = new ProcessBuilder(
                "cp", "-a", "--reflink=auto", src.getAbsolutePath() + "/.", dst.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        int code = p.waitFor();
        if (code != 0) {
            // 回落：Java 逐字节复制（慢但一定能成）
            copyJava(src, dst);
        }
    }

    private static void copyJava(File src, File dst) throws IOException {
        File[] kids = src.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            File target = new File(dst, k.getName());
            if (k.isDirectory()) {
                target.mkdirs();
                copyJava(k, target);
            } else {
                Files.copy(k.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteDir(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteDir(k);
            }
        }
        deleteQuietly(f);
    }

    private static void deleteQuietly(File f) {
        try {
            Files.deleteIfExists(f.toPath());
        } catch (IOException ex) {
            Failures.record("RoomManager.deleteFile/" + f.getName(), ex);
        }
    }

    private static String nextRoomId(String gameKey) {
        for (int i = 1; i < 1000; i++) {
            String id = WorldNames.roomName(gameKey, i);
            if (!ROOMS.containsKey(id) && Bukkit.getWorld(id) == null) {
                return id;
            }
        }
        return WorldNames.roomName(gameKey, (int) (System.currentTimeMillis() % 100000));
    }

    private static String sanitize(String s) {
        String t = WorldNames.sanitize(s);
        return WorldNames.isRoom(t) ? t : WorldNames.ROOM + t;
    }

    private static Room fail(String why) {
        Failures.record("RoomManager.create", new IllegalStateException(why));
        log("[房间] 创建失败：" + why);
        return null;
    }

    private static void log(String msg) {
        try {
            Bukkit.getLogger().info(msg);
        } catch (Throwable ignored) {
            System.out.println(msg);
        }
    }
}
