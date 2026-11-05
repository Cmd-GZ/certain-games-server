package com.buildbattle.gtb.game;

import com.buildbattle.gtb.GuessBuildPlugin;
import com.buildbattle.gtb.arena.Arena;
import com.hub.region.RegionRegistry;
import com.hub.room.RoomContext;
import com.hub.room.RoomManager;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * **每个房间一份运行时状态**（D22）。
 *
 * <p>形态判定：GuessTheBuild 的状态全在 {@link GameManager} 的字段里
 * （阶段、轮次、当前题目、猜对表、计分、建造轮换、计时任务、会话……），
 * {@link Arena} 里只有"这张图长什么样 + 一份地板快照"。所以这里按房间各建一个 manager 实例，
 * **所有字段天然隔离**；不把 1800 行字段拆成 {@code Game} 对象（每漏一个字段就是一条难查的串房 bug）。
 *
 * <p>与 OneShot 的 {@code OneShotRooms} 同构，但多一层：GTB 本来就有**多场地**（{@code arenas} 表 +
 * {@code active-arena}）。所以这里：
 * <ul>
 *   <li>{@link #keyArena} 记住"这个房间用哪张图"（房间实例各自的 {@code activeName}）；</li>
 *   <li>每个房间实例 {@code bindWorld(房间世界)} 拿到**自己那份场地拷贝**（只读共享快照）；</li>
 *   <li>{@link #defaultManager} 是**没有房间时**的那一份（场地跟随共享 {@code ArenaManager} 的生效场地，
 *       也是配置与快照文件的唯一写者 —— R2）。</li>
 * </ul>
 *
 * <p>键的规则与框架一致：**有房间用房间 id，没有房间退回场地名**（{@link RoomContext#roomKeyFor}）；
 * 退回场地名的那些路径统一落到 {@link GameManager#DEFAULT_KEY}，保持迁移前"单份状态"的行为。
 */
public final class GTBRooms {

    /** 与 {@code GameSession} 的 gameKey 一致（小写，派对委派用这个）。 */
    public static final String GAME_KEY = "gtb";

    private final GuessBuildPlugin plugin;
    /** 玩家 → 房间键（进房时记，离开时摘）。 */
    private final Map<UUID, String> playerKey = new LinkedHashMap<>();
    /** 房间键 → 这个房间用哪张图（场地名）。 */
    private final Map<String, String> keyArena = new LinkedHashMap<>();
    /** 房间键 → 那一份运行时状态。 */
    private final Map<String, GameManager> liveArenas = new LinkedHashMap<>();
    /** 默认（没有房间上下文时）那一份：老路径 + 配置/快照的唯一写者。 */
    private final GameManager defaultManager;
    /** 最近一次有人 join 的房间键：控制台执行 {@code /gtb start} 时靠它找对房间。 */
    private String lastJoinedKey;

    public GTBRooms(GuessBuildPlugin plugin) {
        this.plugin = plugin;
        this.defaultManager = new GameManager(plugin);
    }

    public GameManager defaultManager() {
        return defaultManager;
    }

    public Map<String, GameManager> live() {
        return Collections.unmodifiableMap(liveArenas);
    }

    public String keyOf(UUID id) {
        return id == null ? null : playerKey.get(id);
    }

    /** 这个房间用的是哪张图（场地名）。 */
    public String arenaNameOfKey(String key) {
        return keyArena.get(key);
    }

    // ---------- 取实例 ----------

    /**
     * 该房间的运行时状态；首次使用时**现场建一个**。
     *
     * <p>建的时候压着房间上下文，`tpl:` 坐标才解析到这个房间的世界；解析完再 `pin()` 一次 ——
     * 多世界房间（派对图 = 迷你小游戏世界 + 各游戏自己的模板）里 `tpl:` 解析到的是**第一个**世界。
     */
    public GameManager liveArena(String key) {
        if (key == null) {
            // **静默 null 是最难查的 bug**：让它立刻炸出来，而不是上层"场不存在"没有任何日志。
            throw new IllegalStateException("GuessTheBuild: liveArena(null) —— 调用点没有先算房间键");
        }
        if (GameManager.DEFAULT_KEY.equals(key)) {
            return defaultManager;
        }
        GameManager live = liveArenas.get(key);
        if (live != null) {
            return live;
        }
        return create(key);
    }

    /** 已经建过实例就返回它，不建新的（诊断/命令提示用）。 */
    public GameManager liveOf(String key) {
        return key == null ? null : liveArenas.get(key);
    }

    private GameManager create(String key) {
        String map = plugin.getArenas().resolveArenaName(keyArena.get(key));
        Arena arena = plugin.getArenas().newRoomArena(map);
        RoomManager.Room room = RoomManager.room(key);
        World roomWorld = resolveRoomWorld(room, map);
        if (roomWorld != null) {
            // 钉住房间世界：RoomBinding 是"按游戏一个槽"，两房间同时跑会互相覆盖
            arena.bindWorld(roomWorld);
        }
        if (room != null) {
            RoomContext.pushRoom(room.roomId);
        }
        try {
            // 必须在房间上下文里重读配置：插件启用时还没有房间，`tpl:` 当时解析不出来
            arena.loadFromConfig(plugin.getConfig().getConfigurationSection("arenas." + map));
        } finally {
            if (room != null) {
                RoomContext.popRoom();
            }
        }
        GameManager created = new GameManager(plugin, key, arena);
        liveArenas.put(key, created);
        keyArena.put(key, map);
        created.publishRegion();
        plugin.getLogger().info("[GuessTheBuild] 房间 " + key + " 的运行时状态已就绪（场地 " + map + "）"
                + (roomWorld == null ? "（无房间世界，按场地名兜底）" : "，世界 " + roomWorld.getName()));
        return created;
    }

    /**
     * 这个玩家该用哪一份状态。
     *
     * <p>没有记录时给默认实例 —— 老路径（静态场地、直接 {@code /gb join}）行为不变。
     */
    public GameManager forPlayer(Player player) {
        String key = player == null ? null : playerKey.get(player.getUniqueId());
        return key == null ? defaultManager : liveArena(key);
    }

    /** 控制台/无玩家上下文：最近一个有人加入的房间；都没有时是默认实例。 */
    public GameManager lastJoined() {
        if (lastJoinedKey != null) {
            GameManager live = liveArenas.get(lastJoinedKey);
            if (live != null) {
                return live;
            }
        }
        return defaultManager;
    }

    /**
     * 控制台入口的优先顺序：**当前房间上下文**（{@code /hub join} 与派对开局都会压栈）
     * → 最近有人加入的房间 → 默认实例。
     */
    public GameManager current() {
        String ctx = RoomContext.currentRoomId();
        if (ctx != null) {
            GameManager live = liveArenas.get(ctx);
            if (live != null) {
                return live;
            }
            // 有房间上下文但本游戏还没为它建状态（框架按房间 dispatch `<game> reload` 时就是这个情况）：
            // **现场建**，别退化到默认实例 —— 默认实例没钉死房间世界，`tpl:` 会解析到别人房间的世界。
            if (RoomManager.room(ctx) != null) {
                return liveArena(ctx);
            }
        }
        return lastJoined();
    }

    // ---------- 加入 / 离开 ----------

    /**
     * 玩家加入：**先算键、再拿状态、最后才让它自己走原逻辑**（D23：会话先挂再入队）。
     *
     * <p>⚠️ 这里绝不能"先按玩家查场地" —— 那正是 §0.27 第 4 次踩的坑（join 里 key 还不存在 → 恒 null）。
     *
     * @param arenaName 指令给的地图名；不认识/为空时按房间配置或当前生效场地
     * @return true = 真的进去了
     */
    public boolean join(Player player, String arenaName) {
        String map = mapFor(player, arenaName);
        String key = keyFor(player, map);
        keyArena.put(key, map);
        GameManager manager;
        boolean created = false;
        if (GameManager.DEFAULT_KEY.equals(key)) {
            manager = defaultManager;
        } else {
            created = !liveArenas.containsKey(key);
            manager = liveArena(key);
        }
        boolean ok = manager.join(player);
        if (ok) {
            if (!GameManager.DEFAULT_KEY.equals(key)) {
                playerKey.put(player.getUniqueId(), key);
            }
            lastJoinedKey = key;
            return true;
        }
        if (created && manager.idle()) {
            // 没进去（会话被 I1 拒 / 人数满 / 场地未配置）：把刚建的空实例收掉，别留垃圾
            retract(key);
        }
        return false;
    }

    /** 玩家离开：按记录路由到正确的那一份，然后回收空房间。 */
    public void leave(Player player, boolean quit) {
        if (player == null) {
            return;
        }
        String key = playerKey.get(player.getUniqueId());
        GameManager manager = key == null ? defaultManager : liveArena(key);
        manager.leave(player, quit);
        if (key != null) {
            playerKey.remove(player.getUniqueId());
            retireIfIdle(manager);
        }
    }

    /** 空房间实例回收（房间销毁 / 最后一人离开 / 一局打完）。默认实例永不回收。 */
    public void retireIfIdle(GameManager manager) {
        if (manager == null || !manager.roomScoped() || !manager.idle()) {
            return;
        }
        String key = manager.roomKey();
        if (liveArenas.get(key) != manager) {
            return;
        }
        plugin.getLogger().info("[GuessTheBuild] 房间 " + key + " 已空闲，回收运行时状态。");
        retract(key);
    }

    private void retract(String key) {
        GameManager live = liveArenas.remove(key);
        keyArena.remove(key);
        // 玩家 → 房间的记录一起摘掉：留着会在下次 forPlayer/leave 时把刚回收的房间**又建回来**
        playerKey.entrySet().removeIf(e -> e.getValue() == null || e.getValue().equals(key));
        if (key != null && key.equals(lastJoinedKey)) {
            lastJoinedKey = null;
        }
        if (live != null) {
            // 地盘声明跟着房间一起撤（房间销毁后那块世界就不存在了，别在区域表里留孤儿）
            World world = live.arena().getWorld();
            if (world != null) {
                RegionRegistry.unregister(GAME_KEY, live.roomKey(), world.getName());
            }
            live.shutdown();
        }
    }

    // ---------- 控制台入口 ----------

    /**
     * 控制台 {@code /gtb start}：把**每个有待开队列的房间实例**都开起来。
     *
     * <p>为什么要这样：派对是用 {@code dispatchCommand(控制台, "gtb start")} 开局的，
     * 控制台**没有房间上下文**；两场派对同时开局时若只开"最近加入的那一间"，
     * 两场会都去开同一间、另一间永远停在等待。
     *
     * <p>ArcadeParty 1.19.0 起委派处会压房间上下文（正解），那时只开那一间。
     *
     * @return 真的开了几间
     */
    public int startAllWaiting() {
        String ctx = RoomContext.currentRoomId();
        if (ctx != null) {
            GameManager live = liveArenas.get(ctx);
            if (live == null && RoomManager.room(ctx) != null) {
                live = liveArena(ctx);
            }
            if (live != null && live.waitingCountPublic() > 0
                    && live.getPhase() == GameManager.Phase.WAITING) {
                live.start(null);
                return 1;
            }
            return 0;
        }
        int started = 0;
        for (GameManager manager : new ArrayList<>(liveArenas.values())) {
            if (manager.getPhase() == GameManager.Phase.WAITING && manager.waitingCountPublic() > 0) {
                manager.start(null);
                started++;
            }
        }
        if (defaultManager.getPhase() == GameManager.Phase.WAITING
                && defaultManager.waitingCountPublic() > 0) {
            defaultManager.start(null);
            started++;
        }
        return started;
    }

    /** 控制台 {@code /gtb stop}：把**每个正在进行的房间实例**都结束掉。 */
    public int stopAll() {
        int stopped = 0;
        for (GameManager manager : allManagers()) {
            if (manager.getPhase() == GameManager.Phase.PLAYING) {
                manager.stop(null);
                stopped++;
            }
        }
        return stopped;
    }

    /** 有没有任何一份状态在对局中（建场/删场这类全局操作要挡住它）。 */
    public boolean anyPlaying() {
        for (GameManager manager : allManagers()) {
            if (manager.getPhase() == GameManager.Phase.PLAYING) {
                return true;
            }
        }
        return false;
    }

    /** 全部实例（默认 + 各房间），快照式返回，避免遍历时被回收改动。 */
    public List<GameManager> allManagers() {
        List<GameManager> out = new ArrayList<>();
        out.add(defaultManager);
        out.addAll(liveArenas.values());
        return out;
    }

    // ---------- 重载 / 关服 ----------

    /**
     * 重载：默认实例与各房间实例都按新配置重建场地，并各把自己的地盘**重新声明一次**
     * （同一 {@code gameKey+名+世界} 是覆盖，不是新增）。
     *
     * <p>**故意不调 {@code Regions.begin("gtb")}**：那会连框架（{@code RoomManager}）为房间地图声明的那块
     * 一起清掉，而我们只该管自己声明的槽位（回收时 {@link #retract} 会精确撤销）。
     */
    public void reloadAll() {
        defaultManager.reloadArena();
        defaultManager.publishRegion();
        for (GameManager manager : new ArrayList<>(liveArenas.values())) {
            manager.reloadArena();
            manager.publishRegion();
        }
    }

    /** 关服：所有房间的实例都收尾（先还原玩家状态，再取消计时任务）。 */
    public void shutdownAll() {
        for (GameManager manager : new ArrayList<>(liveArenas.values())) {
            manager.restoreAllOnDisable();
            manager.shutdown();
        }
        defaultManager.restoreAllOnDisable();
        defaultManager.shutdown();
        liveArenas.clear();
        keyArena.clear();
        playerKey.clear();
        lastJoinedKey = null;
    }

    // ---------- 键 / 地图 / 世界 ----------

    /**
     * 这个玩家在这个游戏里该用哪个键：**有房间用房间 id，没有房间用默认键**。
     *
     * <p>用 {@link RoomContext#roomKeyFor} 求值（框架的唯一原语），再把"退回场地名"的结果
     * 收敛到 {@link GameManager#DEFAULT_KEY}：GTB 的默认实例本来就跟着共享的生效场地走，
     * 不需要为每个场地名各造一份状态。
     */
    private String keyFor(Player player, String map) {
        String key = RoomContext.roomKeyFor(player, GAME_KEY, map);
        if (key == null || key.isBlank() || RoomManager.room(key) == null) {
            return GameManager.DEFAULT_KEY;
        }
        return key;
    }

    /** 本房间该用哪张图：指令指定 → 房间地图里这块场地 → 当前生效场地。 */
    private String mapFor(Player player, String requested) {
        if (requested != null && !requested.isBlank() && plugin.getArenas().hasArena(requested)) {
            return requested;
        }
        String roomId = player == null ? null : RoomContext.roomIdOfPlayer(player.getUniqueId());
        RoomManager.Room room = roomId == null ? null : RoomManager.room(roomId);
        if (room != null) {
            List<RoomManager.ArenaSlot> slots = room.arenasOf(GAME_KEY);
            for (RoomManager.ArenaSlot slot : slots) {
                if (slot.arenaName() != null && plugin.getArenas().hasArena(slot.arenaName())) {
                    return slot.arenaName();
                }
            }
            if (!slots.isEmpty() && slots.get(0).arenaName() != null) {
                return slots.get(0).arenaName();
            }
        }
        return plugin.getArenas().resolveArenaName(requested);
    }

    /**
     * 这个房间里**这张图**该用哪个世界。
     *
     * <p>不能直接用 {@code room.world}：多世界房间（如派对图 = 迷你小游戏世界 + 各游戏自己的模板）
     * 里 {@code room.world} 只是第一个实例，`tpl:` 也会解析到它 —— 坐标就落到别人的世界里了。
     * 顺序：房间地图里**这张图声明的那块**（maps.yml 是事实源）→ 派对委派的世界 → 房间第一个世界。
     */
    private World resolveRoomWorld(RoomManager.Room room, String map) {
        if (room == null) {
            return null;
        }
        for (RoomManager.ArenaSlot slot : room.arenasOf(GAME_KEY)) {
            if (map != null && map.equals(slot.arenaName()) && slot.world() != null) {
                return slot.world();
            }
        }
        World delegated = com.hub.room.GameDelegation.worldFor(GAME_KEY);
        if (delegated != null) {
            return delegated;
        }
        return room.world;
    }
}
