package com.hub.room;

import com.hub.diagnostics.Failures;
import com.hub.region.ArenaRegion;
import com.hub.region.RegionRegistry;
import org.bukkit.Bukkit;
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

    /** 一个房间实例。 */
    public static final class Room {
        public final String roomId;
        public final WorldTemplate template;
        public World world;
        public final long createdAt = System.currentTimeMillis();
        public long lastActiveAt = System.currentTimeMillis();

        Room(String roomId, WorldTemplate template) {
            this.roomId = roomId;
            this.template = template;
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
                    + " 人=" + playerCount() + " 年龄=" + ageSeconds() + "s 空置=" + idleSeconds() + "s";
        }
    }

    private static final String TEMPLATE_DIR_NAME = "dimensions/minecraft";
    private static final String ROOM_PREFIX = "room_";
    private static final String TEMPLATE_PREFIX = "tmpl_";

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
        ready = false;
        try {
            File cfgFile = new File(lib.getDataFolder(), "templates.yml");
            if (!cfgFile.isFile()) {
                lib.saveResource("templates.yml", false);
            }
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(cfgFile);

            ConfigurationSection limits = cfg.getConfigurationSection("limits");
            if (limits != null) {
                maxRooms = Math.max(1, limits.getInt("max-rooms", 10));
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
                    String folder = t.getString("folder", TEMPLATE_PREFIX + key);
                    File dir = new File(base, folder);
                    ConfigurationSection r = t.getConfigurationSection("rules");
                    WorldTemplate.Rules rules = r == null ? WorldTemplate.Rules.defaults()
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
                    + "上限：全服 " + maxRooms + " / 每游戏 " + maxPerGame
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

    public static Room room(String roomId) {
        return roomId == null ? null : ROOMS.get(roomId);
    }

    public static int roomCount() {
        return ROOMS.size();
    }

    public static int roomCountOf(String gameKey) {
        int n = 0;
        for (Room r : ROOMS.values()) {
            if (r.template.gameKey().equals(gameKey)) {
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
        if (ROOMS.size() >= maxRooms) {
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

        // 5) 套规则
        applyRules(room.world, tpl.rules());

        // 6) 注册区域（模板内坐标 + 房间世界名）
        for (WorldTemplate.Box box : tpl.regions()) {
            try {
                RegionRegistry.register(new ArenaRegion(tpl.gameKey(), box.arenaName(), roomId,
                        box.x1(), box.y1(), box.z1(), box.x2(), box.y2(), box.z2()));
            } catch (RuntimeException ex) {
                Failures.recordAndWarn("RoomManager.region/" + roomId, ex);
            }
        }

        ROOMS.put(roomId, room);
        log("已创建房间 " + room.describe());
        return room;
    }

    /** 删一个房间：卸载 → 删目录 → 撤销区域 → 记账。 */
    public static synchronized boolean delete(String roomId) {
        Room room = ROOMS.get(roomId);
        if (room == null) {
            return false;
        }
        ROOMS.remove(roomId);

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
            String id = ROOM_PREFIX + gameKey + "_" + i;
            if (!ROOMS.containsKey(id) && Bukkit.getWorld(id) == null) {
                return id;
            }
        }
        return ROOM_PREFIX + gameKey + "_" + Long.toHexString(System.currentTimeMillis());
    }

    private static String sanitize(String s) {
        String t = s.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
        return t.startsWith(ROOM_PREFIX) ? t : ROOM_PREFIX + t;
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
