package com.tntbattle;

import com.hub.room.RoomContext;
import com.hub.room.RoomManager;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * **每个房间一份运行时状态**（D22）。
 *
 * <p>形态判定：本局的**状态全在 {@link TNTBattleManager} 的字段里**
 * （等待队列、队伍、淘汰、TNT/道具冷却、被改方块、计时任务、侧边栏、倒计时……），
 * {@link TNTBattleArena} 里只有"这张图长什么样"（区域角、两队出生点、等待点、虚空/建筑高度）。
 * 所以这里按房间各建一个 manager 实例，**所有字段天然隔离**，不去拆那 2900 行的字段表。
 *
 * <p>为什么不改成"每房间克隆一份 arena"（{@code DigToBottom} 那种）：那样状态还在 manager 里，
 * 两个房间仍会共用同一份等待队列/队伍表 —— 等于没做。
 *
 * <p>键的规则与框架一致：**有房间用房间 id，没有房间退回地图名**（{@link RoomContext#roomKeyFor}）。
 * 没有房间时用的就是 {@code core.matches} 里那张图的**地图定义实例**（老路径，行为不变）。
 */
public final class TNTBattleRooms {

    /** 与 {@code GameSession} 的 gameKey 一致（小写，派对委派用这个）。 */
    public static final String GAME_KEY = "tntbattle";

    private final TNTBattlePlugin plugin;
    private final TNTBattleCore core;
    /** 房间键 → 那一份运行时状态（**只**放房间实例；地图定义实例在 core.matches 里）。 */
    private final Map<String, TNTBattleManager> liveArenas = new LinkedHashMap<>();
    /** 房间键 → 这个房间用的是哪张地图（派对选图 / `{arena}` 令牌用它）。 */
    private final Map<String, String> keyArena = new LinkedHashMap<>();
    /** 最近一次有人 join 的房间键：控制台执行 {@code /tntbattle start} 时靠它找对房间。 */
    private String lastJoinedKey;

    public TNTBattleRooms(TNTBattlePlugin plugin, TNTBattleCore core) {
        this.plugin = plugin;
        this.core = core;
    }

    public Map<String, TNTBattleManager> live() {
        return Collections.unmodifiableMap(liveArenas);
    }

    /** 这个房间用的地图（场地）名。 */
    public String arenaNameOfKey(String key) {
        String map = keyArena.get(key);
        if (map != null) {
            return map;
        }
        TNTBattleManager definition = core.match(key);
        return definition == null ? defaultMapName() : definition.arena().name();
    }

    /**
     * 该房间的运行时状态；首次使用时**现场建一个**（压着房间上下文，`tpl:` 坐标才解析到这个房间的世界）。
     *
     * <p>没有房间的静态路径（`/tntbattle join 双岛` 且玩家不在任何房间里）直接返回
     * 那张图的**地图定义实例** —— 与迁移前完全一致，不另建一份。
     */
    public TNTBattleManager liveArena(String key) {
        if (key == null) {
            // **静默 null 是最难查的 bug**：让它立刻炸出来，而不是上层"场不存在"没有任何日志。
            throw new IllegalStateException("TNTBattle: liveArena(null) —— 调用点没有先算房间键");
        }
        TNTBattleManager live = liveArenas.get(key);
        if (live != null) {
            return live;
        }
        TNTBattleManager definition = core.match(key);
        if (definition != null) {
            return definition;
        }
        return liveArena(key, keyArena.get(key));
    }

    /** 为这个房间建一份运行时状态（一张图一份 arena 配置副本 + 钉死房间世界）。 */
    public TNTBattleManager liveArena(String key, String arenaName) {
        if (key == null) {
            throw new IllegalStateException("TNTBattle: liveArena(null) —— 调用点没有先算房间键");
        }
        TNTBattleManager existing = liveArenas.get(key);
        if (existing != null) {
            return existing;
        }
        String map = arenaName == null || arenaName.isBlank() || core.match(arenaName) == null
                ? defaultMapName() : arenaName;
        TNTBattleArena arena = new TNTBattleArena(plugin, map);
        TNTBattleManager created = new TNTBattleManager(plugin, core, arena, key);
        RoomManager.Room room = RoomManager.room(key);
        World roomWorld = resolveRoomWorld(room, map);
        if (roomWorld != null) {
            // 钉住房间世界：RoomBinding 是"按游戏一个槽"，两房间同时跑会互相覆盖
            arena.bindWorld(roomWorld);
        }
        liveArenas.put(key, created);
        keyArena.put(key, map);
        if (room != null) {
            RoomContext.pushRoom(room.roomId);
        }
        try {
            created.load(false);
        } finally {
            if (room != null) {
                RoomContext.popRoom();
            }
        }
        plugin.getLogger().info("[TNTBattle] 房间 " + key + " 的运行时状态已就绪（场地 " + map + "）"
                + (roomWorld == null ? "（无房间世界，按场地名兜底）" : "，世界 " + roomWorld.getName()));
        return created;
    }

    /** 这个玩家所在房间的那一份状态；不在局内返回 null。 */
    public TNTBattleManager forPlayer(Player player) {
        return player == null ? null : core.managerFor(player.getUniqueId());
    }

    /** 控制台/无玩家上下文：最近一个有人加入的房间；都没有时是主场地。 */
    public TNTBattleManager lastJoined() {
        if (lastJoinedKey != null) {
            TNTBattleManager live = liveArenas.get(lastJoinedKey);
            if (live != null) {
                return live;
            }
            TNTBattleManager definition = core.match(lastJoinedKey);
            if (definition != null) {
                return definition;
            }
        }
        return core.primary();
    }

    /**
     * 控制台入口的优先顺序：**当前房间上下文**（{@code /hub join} 与派对开局都会压栈）
     * → 最近有人加入的房间 → 主场地（地图定义实例）。
     */
    public TNTBattleManager current() {
        String ctx = RoomContext.currentRoomId();
        if (ctx != null) {
            TNTBattleManager live = liveArenas.get(ctx);
            if (live != null) {
                return live;
            }
            TNTBattleManager definition = core.match(ctx);
            if (definition != null) {
                return definition;
            }
            // 有房间上下文但本游戏还没为它建状态（`/hub join` 时框架按房间 dispatch `<game> reload`）：
            // **现场建**，别退化到地图定义实例 —— 那个实例没钉死房间世界，`tpl:` 会解析到别人房间的世界。
            if (RoomManager.room(ctx) != null) {
                return liveArena(ctx);
            }
        }
        return lastJoined();
    }

    /** 有房间上下文时是否已经有本游戏的实例（诊断/命令提示用，不建实例）。 */
    public TNTBattleManager liveOf(String key) {
        return key == null ? null : liveArenas.get(key);
    }

    /**
     * 玩家加入：先按房间算出键、拿到那一份状态，再让它自己走原逻辑（D23：会话先挂再入队）。
     *
     * @param arenaName 指令/派对给的地图名；不认识时用主场地
     */
    public boolean join(Player player, String arenaName) {
        String map = arenaName == null || arenaName.isBlank() || core.match(arenaName) == null
                ? defaultMapName() : arenaName;
        // **先求键，再拿状态**（绝不能在算键之前碰 liveArena —— 那就是 §0.27 第 4 次踩的坑）
        String key = RoomContext.roomKeyFor(player, GAME_KEY, map);
        keyArena.put(key, map);
        boolean created = !liveArenas.containsKey(key) && core.match(key) == null;
        TNTBattleManager manager = liveArena(key);
        boolean ok = manager.join(player);
        if (ok || manager.isInGame(player.getUniqueId())) {
            lastJoinedKey = key;
            return ok;
        }
        if (created && manager.idle()) {
            // 没进去（会话被 I1 拒 / 人数满 / 场地未配置）：把刚建的空实例收掉，别留垃圾
            retract(key);
        }
        return false;
    }

    /** 玩家离开：按归属链路由到正确的那一份，然后回收空房间。 */
    public void leave(Player player, boolean fromLobby) {
        TNTBattleManager manager = core.managerFor(player);
        if (manager != null) {
            manager.leave(player, fromLobby);
            retireIfIdle(manager);
        }
    }

    /** 空房间实例回收（房间销毁/最后一人离开时）。地图定义实例永不回收。 */
    public void retireIfIdle(TNTBattleManager manager) {
        if (manager == null || !manager.roomScoped() || !manager.idle()) {
            return;
        }
        String key = manager.roomKey();
        if (liveArenas.get(key) != manager) {
            return;
        }
        plugin.getLogger().info("[TNTBattle] 房间 " + key + " 已空闲，回收运行时状态。");
        retract(key);
    }

    private void retract(String key) {
        TNTBattleManager live = liveArenas.remove(key);
        keyArena.remove(key);
        if (key != null && key.equals(lastJoinedKey)) {
            lastJoinedKey = null;
        }
        if (live != null) {
            // 地盘声明跟着房间一起撤（房间销毁后那块世界就不存在了，别在区域表里留孤儿）
            World world = live.arena().world();
            if (world != null) {
                com.hub.region.RegionRegistry.unregister("tntbattle", live.roomKey(), world.getName());
            }
            live.shutdown();
            live.releaseSidebar();
        }
    }

    /**
     * 控制台 {@code /tntbattle start}：把**每个有待开队列的房间实例**都开起来。
     *
     * <p>为什么要这样：派对是用 {@code dispatchCommand(控制台, "tntbattle start ...")} 开局的，
     * 控制台**没有**房间上下文；两场派对同时开局时若只开"最近加入的那一间"，
     * 两场会都去开同一间、另一间永远停在等待。
     *
     * <p>ArcadeParty 1.19.0 起委派处会压房间上下文（正解），那时只开那一间。
     */
    public int startAllWaiting(String arenaName) {
        String ctx = RoomContext.currentRoomId();
        if (ctx != null) {
            TNTBattleManager live = liveArenas.get(ctx);
            if (live == null) {
                live = core.match(ctx);
            }
            if (live != null && live.startGame(true)) {
                return 1;
            }
            return 0;
        }
        int started = 0;
        for (TNTBattleManager manager : new ArrayList<>(liveArenas.values())) {
            if (manager.roomScoped() && manager.waitingCountPublic() > 0
                    && !manager.isRunning() && manager.startGame(true)) {
                started++;
            }
        }
        for (TNTBattleManager manager : core.matches()) {
            if (arenaName != null && !arenaName.equals(manager.arena().name())) {
                continue;
            }
            if (manager.waitingCountPublic() > 0 && !manager.isRunning() && manager.startGame(true)) {
                started++;
            }
        }
        return started;
    }

    /** 关服：所有房间的实例都收尾。 */
    public void shutdownAll() {
        for (TNTBattleManager manager : new ArrayList<>(liveArenas.values())) {
            manager.shutdown();
            manager.releaseSidebar();
        }
        liveArenas.clear();
        keyArena.clear();
        lastJoinedKey = null;
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

    private String defaultMapName() {
        TNTBattleManager primary = core.primary();
        return primary == null ? TNTBattleCore.DEFAULT_ARENA : primary.arena().name();
    }
}
