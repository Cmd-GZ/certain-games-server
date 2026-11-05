package com.hub.room;

/**
 * **房间上下文**：玩家从 `/hub join <房间>` 进来时，框架告诉那个游戏的实例"你在哪个房间"。
 *
 * <p>为什么要它：{@link RoomManager#activeFor(String)} 只在"该游戏**恰好一个**房间"时才返回，
 * 有两个房间就返回 null。而现实中同一个游戏可以同时开多个房间（D2 允许 8 个）——
 * 此时"让游戏自己去搜"必然搜不到（真实事故：两个假玩家各建了一个派对房间，
 * 派对拿到的 `partyRoom=null`，于是既不按房间取场地、也不委派子游戏）。
 *
 * <p>正确的关系是**框架告诉游戏**：`/hub join` 知道玩家进的是哪个房间，直接交过去。
 */
public final class RoomContext {

    private static final java.util.Map<String, RoomManager.Room> BY_GAME =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * **玩家 → 他实际进的那个房间**（D22 后半的入口，也是"同一游戏多房间"唯一不会错的信息）。
     *
     * <p>为什么必须有：{@link #BY_GAME} 是"每个游戏一个槽"，同一游戏开两个房间时**后者覆盖前者**。
     * 按玩家的记录不会覆盖 —— 框架在 `/hub join` 时就知道"这个人进的是哪个房间"。
     * 游戏想支持多房间时，用 {@link #roomOfPlayer(java.util.UUID)} 而不是按 gameKey 查。
     */
    private static final java.util.Map<java.util.UUID, RoomManager.Room> BY_PLAYER =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * **当前房间**（线程内上下文）：框架在执行"某个房间的"操作时压栈，
     * 让 `tpl:<模板>` 这种令牌解析到**这个房间**的世界，而不是靠"该游戏恰好只有一个房间"。
     *
     * <p>为什么必须有：{@link RoomManager#activeFor(String)} 在两个房间时返回 null，
     * 于是所有 `tpl:` 坐标都解析不出来（真实事故：两个密室房间 → 场地全"未就绪"）。
     * 房间是**操作发起者已知**的信息，不该事后去猜。
     */
    private static final ThreadLocal<java.util.Deque<String>> CURRENT_ROOM =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

    /**
     * 压入"当前正在操作哪个房间"，务必在 finally 里 {@link #popRoom()}。
     *
     * <p>**是栈，不是单值**（2026-11-05 自检抓出来的）：原来是 `set/remove`，
     * 嵌套压栈时内层 `pop` 会把上下文清成 null，外层后续代码就读不到房间了
     * （真实场景：`/hub join` 已压房间 → 派对 `startRound` 再压一次 → 内层 pop 后上下文丢失）。
     */
    public static void pushRoom(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return;
        }
        CURRENT_ROOM.get().push(roomId);
    }

    /** 弹出最近压入的房间（外层还在的话会**恢复外层**）。 */
    public static void popRoom() {
        java.util.Deque<String> stack = CURRENT_ROOM.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    /** 当前房间 id（栈顶）；没有上下文时返回 null。 */
    public static String currentRoomId() {
        java.util.Deque<String> stack = CURRENT_ROOM.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    private RoomContext() {
    }

    /** 玩家进房间时由框架调用：登记"这个游戏当前跑在哪个房间"。 */
    public static void bind(String gameKey, RoomManager.Room room) {
        if (gameKey == null || room == null) {
            return;
        }
        RoomManager.Room old = BY_GAME.put(gameKey, room);
        // **同游戏多房间**：按游戏的这个槽只能存一个，后者会覆盖前者（D22 后半还没做）。
        // 不静默：打一条警告，让运维在日志里看得见"这个游戏正在同时被两个房间用"。
        if (old != null && !old.roomId.equals(room.roomId)) {
            org.bukkit.Bukkit.getLogger().warning("[房间] 游戏 " + gameKey
                    + " 同时被两个房间使用：" + old.roomId + " 与 " + room.roomId
                    + "（按游戏的上下文会以后者为准；多房间会话是 D22 后半，尚未支持）");
        }
    }

    /** 玩家进房间时同时登记"这个人进的是哪个房间"（多房间不会互相覆盖）。 */
    public static void bindPlayer(java.util.UUID playerId, RoomManager.Room room) {
        if (playerId == null || room == null) {
            return;
        }
        BY_PLAYER.put(playerId, room);
    }

    /** 这个玩家实际进的房间；没进过房间返回 null。 */
    public static RoomManager.Room roomOfPlayer(java.util.UUID playerId) {
        return playerId == null ? null : BY_PLAYER.get(playerId);
    }

    /** 这个玩家所在房间的 id（没有则 null）——给"多房间"用得上的地方。 */
    public static String roomIdOfPlayer(java.util.UUID playerId) {
        RoomManager.Room r = roomOfPlayer(playerId);
        return r == null ? null : r.roomId;
    }

    /**
     * 这个玩家在某个游戏里应该用的**键**：有房间就用房间 id，否则用场地名。
     *
     * <p>与 {@link #sessionKeyFor} 同义，只是给"手上只有 Player"的调用点用（少写一个 getUniqueId）。
     * 游戏把 `sessions` / `waits` / 计时器 等运行时状态的键换成它，就能支持同游戏多房间（D22）。
     */
    public static String roomKeyFor(org.bukkit.entity.Player player, String gameKey, String arenaName) {
        return sessionKeyFor(player == null ? null : player.getUniqueId(), gameKey, arenaName);
    }

    /**
     * **会话键**（D22 后半的唯一原语）：这个玩家在这个游戏里，会话应该用哪个键索引。
     *
     * <p>规则：玩家有"玩家→房间"记录时用**房间 id**，否则退回**场地名**（老路径 / 静态场地）。
     *
     * <p>迁移要点（见 `docs/多房间会话.md`）：把 `ensureSession(arenaName)` 换成
     * `ensureSession(RoomContext.sessionKeyFor(player, gameKey, arenaName))`，
     * 并把所有 `sessions.get(arenaName)` 换成"先求键再取"。**一次只迁一个游戏，迁完就跑回归。**
     */
    public static String sessionKeyFor(java.util.UUID playerId, String gameKey, String arenaName) {
        String roomId = roomIdOfPlayer(playerId);
        if (roomId != null && !roomId.isBlank()) {
            return roomId;
        }
        // 没有按玩家的记录时（静态场地 / 直接 `/xxx join` 的路径）退回场地名，
        // 与迁移前的行为一致 —— 迁移是**加法**，不是替换。
        return arenaName == null ? "" : arenaName;
    }

    /** 玩家离开/对局结束时清掉（房间销毁时也会统一清）。 */
    public static void clearPlayer(java.util.UUID playerId) {
        if (playerId != null) {
            BY_PLAYER.remove(playerId);
        }
    }

    /** 该游戏当前绑定的房间 id；没有则 null（给 GameSession 盖章用）。 */
    public static String roomIdOf(String gameKey) {
        RoomManager.Room r = roomOf(gameKey);
        return r == null ? null : r.roomId;
    }

    /** 该游戏当前绑定的房间；没有则 null。 */
    public static RoomManager.Room roomOf(String gameKey) {
        return gameKey == null ? null : BY_GAME.get(gameKey);
    }

    /** 房间被删除时调用。 */
    public static void unbind(String roomId) {
        BY_GAME.entrySet().removeIf(e -> e.getValue() == null || roomId.equals(e.getValue().roomId));
        BY_PLAYER.entrySet().removeIf(e -> e.getValue() == null || roomId.equals(e.getValue().roomId));
    }

    /** 全部绑定（doctor 用）。 */
    public static java.util.Map<String, String> snapshot() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        BY_GAME.forEach((k, v) -> out.put(k, v == null ? "-" : v.roomId));
        return out;
    }

    public static void clear() {
        BY_GAME.clear();
        BY_PLAYER.clear();
    }
}
