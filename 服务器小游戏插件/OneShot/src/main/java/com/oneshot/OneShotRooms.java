package com.oneshot;

import com.hub.room.RoomContext;
import com.hub.room.RoomManager;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * **每个房间一份运行时状态**（D22）——这个游戏的"状态"就是 {@link OneShotManager} 本身
 * （等待/对局/存活/箭/计分/计时任务全在它的字段里），所以这里按房间各建一个实例。
 *
 * <p>为什么不把 manager 的字段拆成 {@code Game} 对象：它是"manager 即状态"型（和 FireJumper/LandClaim 一样），
 * 拆字段要动上千行、每漏一个字段就是一条难查的串房 bug。按房间各用一个实例，**所有字段天然隔离**。
 *
 * <p>键的规则与框架一致：**有房间用房间 id，没有房间退回场地名**（{@link RoomContext#roomKeyFor}）。
 */
public final class OneShotRooms {

    /** 与 {@code GameSession} 的 gameKey 一致（小写，派对委派用这个）。 */
    public static final String GAME_KEY = "oneshot";

    private final OneShotPlugin plugin;
    /** 玩家 → 房间键（进房时记，离开时摘）。 */
    private final Map<UUID, String> playerKey = new LinkedHashMap<>();
    /** 房间键 → 场地名（单图游戏：场地名就是我们自己）。 */
    private final Map<String, String> keyArena = new LinkedHashMap<>();
    /** 房间键 → 那一份运行时状态。 */
    private final Map<String, OneShotManager> liveArenas = new LinkedHashMap<>();
    /** 默认（没有房间上下文时）那一份。 */
    private final OneShotManager defaultManager;
    /** 最近一次有人 join 的房间键：控制台执行 {@code /oneshot start} 时靠它找对房间。 */
    private String lastJoinedKey;

    public OneShotRooms(OneShotPlugin plugin) {
        this.plugin = plugin;
        this.defaultManager = new OneShotManager(plugin);
    }

    public OneShotManager defaultManager() {
        return defaultManager;
    }

    public String keyOf(UUID id) {
        return id == null ? null : playerKey.get(id);
    }

    public String arenaNameOfKey(String key) {
        return keyArena.getOrDefault(key, OneShotManager.ARENA);
    }

    /**
     * 该房间的运行时状态；首次使用时**现场建一个**（load 时压着房间上下文，
     * 所以 {@code tpl:} 坐标解析到这个房间的世界）。
     */
    public OneShotManager liveArena(String key) {
        if (key == null) {
            // **静默 null 是最难查的 bug**：让它立刻炸出来，而不是上层"场不存在"没有任何日志。
            throw new IllegalStateException("OneShot: liveArena(null) —— 调用点没有先算房间键");
        }
        OneShotManager live = liveArenas.get(key);
        if (live != null) {
            return live;
        }
        OneShotManager created = new OneShotManager(plugin, key);
        RoomManager.Room room = RoomManager.room(key);
        World roomWorld = room == null ? null : room.world;
        if (roomWorld != null) {
            // 钉住房间世界：RoomBinding 是"按游戏一个槽"，两房间同时跑会互相覆盖
            created.arena().bindWorld(roomWorld);
        }
        liveArenas.put(key, created);
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
        plugin.getLogger().info("[OneShot] 房间 " + key + " 的运行时状态已就绪"
                + (roomWorld == null ? "（无房间世界，按场地名兜底）" : "，世界 " + roomWorld.getName()));
        return created;
    }

    /** 只按场地名找（无玩家上下文的只读路径）。 */
    public OneShotManager arenaByName(String arenaName) {
        for (OneShotManager live : liveArenas.values()) {
            if (arenaName != null && arenaName.equals(live.arenaLabel())) {
                return live;
            }
        }
        return defaultManager;
    }

    /** 这个玩家所在房间的那一份状态；没有房间记录时就是默认那一份。 */
    public OneShotManager forPlayer(Player p) {
        String key = p == null ? null : playerKey.get(p.getUniqueId());
        return key == null ? defaultManager : liveArena(key);
    }

    /** 控制台/无玩家上下文：最近一个有人加入的房间；都没有时是默认实例。 */
    public OneShotManager lastJoined() {
        return lastJoinedKey == null ? defaultManager : liveArena(lastJoinedKey);
    }

    /**
     * 控制台入口的优先顺序：**当前房间上下文**（{@code /hub join} 时框架压了栈，
     * 例如 HubCore 顺手发的那几条 {@code /oneshot reload}）→ 最近有人加入的房间 → 默认实例。
     */
    public OneShotManager current() {
        String ctx = RoomContext.currentRoomId();
        if (ctx != null) {
            OneShotManager live = liveArenas.get(ctx);
            if (live != null) {
                return live;
            }
            // 有房间上下文但本游戏还没为它建状态（框架按房间 dispatch `<game> reload` 时就是这个情况）：
            // **现场建**，别退化到默认实例 —— 默认实例没有钉死房间世界，`tpl:` 会解析到别人房间的世界。
            if (RoomManager.room(ctx) != null) {
                return liveArena(ctx);
            }
        }
        return lastJoined();
    }

    /**
     * 控制台 {@code /oneshot start}：把**每个有待开队列的房间实例**都开起来。
     *
     * <p>为什么要这样：派对是用 {@code dispatchCommand(控制台, "oneshot start")} 开局的，
     * 控制台**没有房间上下文**；两场派对同时开局时若只开"最近加入的那一间"，
     * 两场会都去开同一间、另一间永远停在等待。
     *
     * @return 真的开了几间
     */
    public int startAllWaiting() {
        // 框架/派对**压了房间上下文**时（ArcadeParty 1.19.0 的正解），只开那一间；
        // 没有上下文才退化到"开所有有待开队列的房间"。
        String ctx = RoomContext.currentRoomId();
        if (ctx != null) {
            OneShotManager live = liveArenas.get(ctx);
            if (live != null && live.state() == OneShotManager.State.WAITING && live.waitingCount() > 0) {
                live.startGame(true);
                return 1;
            }
        }
        int started = 0;
        for (OneShotManager manager : new ArrayList<>(liveArenas.values())) {
            if (manager.state() == OneShotManager.State.WAITING && manager.waitingCount() > 0) {
                manager.startGame(true);
                started++;
            }
        }
        if (defaultManager.state() == OneShotManager.State.WAITING && defaultManager.waitingCount() > 0) {
            defaultManager.startGame(true);
            started++;
        }
        return started;
    }

    /** 玩家加入：先按房间算出键、拿到那一份状态，再让它自己走原逻辑（D23：会话先挂）。 */
    public boolean join(Player p) {
        String key = RoomContext.roomKeyFor(p, GAME_KEY, OneShotManager.ARENA);
        keyArena.put(key, OneShotManager.ARENA);
        OneShotManager manager = liveArena(key);
        boolean ok = manager.join(p);
        if (ok || manager.isInGame(p)) {
            playerKey.put(p.getUniqueId(), key);
            lastJoinedKey = key;
        }
        return ok;
    }

    /** 玩家离开：按玩家记录路由到正确的那一份，然后摘掉记录。 */
    public void leave(Player p, boolean silent) {
        String key = playerKey.get(p.getUniqueId());
        OneShotManager manager = key == null ? defaultManager : liveArena(key);
        manager.leave(p, silent);
        if (key != null) {
            playerKey.remove(p.getUniqueId());
            if (!key.equals(OneShotManager.ARENA) && manager.idle()) {
                manager.shutdown();
                liveArenas.remove(key);
                keyArena.remove(key);
                if (key.equals(lastJoinedKey)) {
                    lastJoinedKey = null;
                }
            }
        }
    }

    /** 关服：所有房间的实例都收尾。 */
    public void shutdownAll() {
        for (OneShotManager manager : liveArenas.values()) {
            manager.shutdown();
        }
        liveArenas.clear();
        keyArena.clear();
        playerKey.clear();
        defaultManager.shutdown();
    }

    public Map<String, OneShotManager> live() {
        return java.util.Collections.unmodifiableMap(liveArenas);
    }
}
