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
