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

    private RoomContext() {
    }

    /** 玩家进房间时由框架调用：登记"这个游戏当前跑在哪个房间"。 */
    public static void bind(String gameKey, RoomManager.Room room) {
        if (gameKey == null || room == null) {
            return;
        }
        BY_GAME.put(gameKey, room);
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
    }

    /** 全部绑定（doctor 用）。 */
    public static java.util.Map<String, String> snapshot() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        BY_GAME.forEach((k, v) -> out.put(k, v == null ? "-" : v.roomId));
        return out;
    }

    public static void clear() {
        BY_GAME.clear();
    }
}
