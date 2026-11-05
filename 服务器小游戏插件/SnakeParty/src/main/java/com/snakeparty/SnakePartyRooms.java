package com.snakeparty;

import com.hub.room.RoomContext;
import com.hub.room.RoomManager;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * **每个房间一份运行时状态**（D22）——这个游戏的"状态"就是 {@link SnakePartyManager} 本身
 * （等待/对局/玻璃板/计时任务全在它的字段里），所以这里按房间各建一个实例。
 *
 * <p>为什么不把 manager 的字段拆成 ${@code Game} 对象：这个游戏是"manager 即状态"型
 * （和 GTB/TNTBattle 一样），拆字段要动 1000+ 行、每漏一个字段就是一条难查的串房 bug。
 * 按房间各用一个实例，**所有字段天然隔离**，而这一层只做"人在哪个房间 → 用哪个实例"的路由。
 *
 * <p>键的规则与框架一致：**有房间用房间 id，没有房间退回场地名**（{@link RoomContext#roomKeyFor}）。
 * 没有房间时的键就是 {@link SnakePartyManager#ARENA}，也就是老的单场地行为，逐字不变。
 */
public final class SnakePartyRooms {

    /** 与 {@code GameSession} 的 gameKey 一致（小写，派对委派用这个）。 */
    public static final String GAME_KEY = "snakeparty";

    private final SnakePartyPlugin plugin;
    /** 玩家 → 房间键（进房时记，离开时摘）。 */
    private final Map<UUID, String> playerKey = new LinkedHashMap<>();
    /** 房间键 → 场地名（配置里的场地是我们自己：单图游戏，场地名就是 ARENA）。 */
    private final Map<String, String> keyArena = new LinkedHashMap<>();
    /** 房间键 → 那一份运行时状态。 */
    private final Map<String, SnakePartyManager> liveArenas = new LinkedHashMap<>();
    /** 默认（没有房间上下文时）那一份。 */
    private final SnakePartyManager defaultManager;
    /** 最近一次有人 join 的房间键：控制台执行 {@code /snake start} 时靠它找对房间。 */
    private String lastJoinedKey;

    public SnakePartyRooms(SnakePartyPlugin plugin) {
        this.plugin = plugin;
        this.defaultManager = new SnakePartyManager(plugin);
    }

    public SnakePartyManager defaultManager() {
        return defaultManager;
    }

    public String keyOf(UUID id) {
        return id == null ? null : playerKey.get(id);
    }

    public String arenaNameOfKey(String key) {
        return keyArena.getOrDefault(key, SnakePartyManager.ARENA);
    }

    /**
     * 该房间的运行时状态；首次使用时**现场建一个**（它会自己 load 配置，
     * 此时框架压着"当前房间"上下文，所以 {@code tpl:} 坐标解析到的就是这个房间的世界）。
     */
    public SnakePartyManager liveArena(String key) {
        if (key == null) {
            // **静默 null 是最难查的 bug**：join 里 key 还没算出来就调这里会拿到 null →
            // 上层"场不存在"→ 命令返回 true、控制台无日志。让它立刻炸出来。
            throw new IllegalStateException("SnakeParty: liveArena(null) —— 调用点没有先算房间键");
        }
        SnakePartyManager live = liveArenas.get(key);
        if (live != null) {
            return live;
        }
        SnakePartyManager created = new SnakePartyManager(plugin, key);
        // 钉住房间世界：两房间同时跑时，RoomBinding 的"按游戏一个槽"会互相覆盖
        RoomManager.Room room = RoomManager.room(key);
        World roomWorld = room == null ? null : room.world;
        if (roomWorld != null) {
            created.arena().bindWorld(roomWorld);
        }
        liveArenas.put(key, created);
        // **在"当前房间"上下文里 load**：`tpl:<模板>` 必须解析到**这个**房间的世界，
        // 否则两个房间同时跑时会拿到"该模板的第一个房间"（串房）。
        if (room != null) {
            RoomContext.pushRoom(room.roomId);
        }
        try {
            created.load();
        } finally {
            if (room != null) {
                RoomContext.popRoom();
            }
        }
        created.publishRegions();
        plugin.getLogger().info("[SnakeParty] 房间 " + key + " 的运行时状态已就绪"
                + (roomWorld == null ? "（无房间世界，按场地名兜底）" : "，世界 " + roomWorld.getName()));
        return created;
    }

    /** 只按场地名找（无玩家上下文的只读路径）。 */
    public SnakePartyManager arenaByName(String arenaName) {
        for (SnakePartyManager live : liveArenas.values()) {
            if (arenaName != null && arenaName.equals(live.arenaLabel())) {
                return live;
            }
        }
        return defaultManager;
    }

    /** 这个玩家所在房间的那一份状态；没有房间记录时就是默认那一份。 */
    public SnakePartyManager forPlayer(Player p) {
        String key = p == null ? null : playerKey.get(p.getUniqueId());
        return key == null ? defaultManager : liveArena(key);
    }

    /** 控制台/无玩家上下文：最近一个有人加入的房间；都没有时是默认实例。 */
    public SnakePartyManager lastJoined() {
        return lastJoinedKey == null ? defaultManager : liveArena(lastJoinedKey);
    }

    /**
     * 玩家加入：先按房间算出键、拿到那一份状态，再让它自己走原逻辑。
     * 成功才记 playerKey（失败不记，免得之后按玩家路由到一个空实例上）。
     */
    public boolean join(Player p) {
        String key = RoomContext.roomKeyFor(p, GAME_KEY, SnakePartyManager.ARENA);
        keyArena.put(key, SnakePartyManager.ARENA);
        SnakePartyManager manager = liveArena(key);
        boolean ok = manager.join(p);
        if (ok) {
            playerKey.put(p.getUniqueId(), key);
            lastJoinedKey = key;
        } else if (!key.equals(SnakePartyManager.ARENA) && manager.isInGame(p)) {
            // 已经在里面（重复 join）：也记上，否则之后 leave 会走到默认实例上
            playerKey.put(p.getUniqueId(), key);
            lastJoinedKey = key;
        }
        return ok;
    }

    /** 玩家离开：按玩家记录路由到正确的那一份，然后摘掉记录。 */
    public void leave(Player p, boolean silent) {
        String key = playerKey.get(p.getUniqueId());
        SnakePartyManager manager = key == null ? defaultManager : liveArena(key);
        manager.leave(p, silent);
        if (key != null) {
            playerKey.remove(p.getUniqueId());
            if (!manager.isInGame(p)) {
                // 这一份已经没人了：清掉，避免下次同名房间拿到上一局的残留状态
                if (!key.equals(SnakePartyManager.ARENA) && manager.idle()) {
                    manager.shutdown();
                    liveArenas.remove(key);
                    keyArena.remove(key);
                    if (key.equals(lastJoinedKey)) {
                        lastJoinedKey = null;
                    }
                }
            }
        }
    }

    /** 关服：所有房间的实例都收尾。 */
    public void shutdownAll() {
        for (SnakePartyManager manager : liveArenas.values()) {
            manager.shutdown();
        }
        liveArenas.clear();
        keyArena.clear();
        playerKey.clear();
        defaultManager.shutdown();
    }

    /** 调试/日志用。 */
    public Map<String, SnakePartyManager> live() {
        return java.util.Collections.unmodifiableMap(liveArenas);
    }

    /**
     * 控制台（没有玩家、可能也没有房间上下文）该操作哪一份状态：
     * **有房间上下文就取那一份**（派对开局时框架会压），否则退回最近有人加入的那一份。
     *
     * <p>为什么必须看上下文：派对用 `dispatchCommand(控制台, "<game> start")` 开局，
     * 只认"最近加入"会让两场派对都去开同一间，另一间永远 WAITING。
     */
    public SnakePartyManager current() {
        String ctx = com.hub.room.RoomContext.currentRoomId();
        if (ctx != null) {
            SnakePartyManager live = liveArenas.get(ctx);
            if (live != null) {
                return live;
            }
        }
        return lastJoined();
    }
}
