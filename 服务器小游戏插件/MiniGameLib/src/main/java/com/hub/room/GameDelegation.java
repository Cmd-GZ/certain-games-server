package com.hub.room;

import org.bukkit.World;

import java.util.List;
import java.util.UUID;

/**
 * **父级委派**：编排器（派对）把"这一关在哪个世界、哪块场地、哪些玩家"交给子小游戏。
 *
 * <p>为什么要这个机制（用户的模型）：**子小游戏永远附属于父小游戏**，它不自己去"找房间"，
 * 而是**接收父级的任务**——顺序、开局信号、场地信息都从父级来。
 *
 * <p>用法：
 * <pre>
 *   // 父级（派对）开一关时
 *   GameDelegation.begin("hillking", world, "高台之王", participants);
 *   ...跑这一关（子游戏在这期间读到的世界就是委托的世界）...
 *   GameDelegation.end();
 * </pre>
 *
 * <p>{@link RoomBinding} 会优先读这里；没有委派时才退回"按游戏找自己房间"的旧路径，
 * 所以单开普通游戏的流程完全不受影响。
 */
public final class GameDelegation {

    /** 一次委派。 */
    public record Task(String game, World world, String arenaName, List<UUID> players,
                       com.hub.session.GameSession parentSession) {
    }

    /**
     * **按 (游戏, 房间) 记的委派表**（D22 后半，2026-11-05）。
     *
     * <p>为什么必须多槽：以前只有一个 `Task current`，两场派对同时开局时**后一场会覆盖前一场** →
     * 先开始的那场读到的是别人的世界（表现为"委派到 room_pd1"但场地坐标落在 room_pd2）。
     * 现在按房间分槽，并用 {@link RoomContext#currentRoomId()} 解析。
     */
    private static final java.util.Map<String, Task> BY_ROOM = new java.util.HashMap<>();
    /** 兜底：没有房间上下文的旧路径（单派对/单游戏）用"最后一次委派"。 */
    private static Task current;

    private GameDelegation() {
    }

    /** 开始一次委派（覆盖上一个）。 */
    public static void begin(String game, World world, String arenaName, List<UUID> players) {
        begin(game, world, arenaName, players, null);
    }

    /** 带父会话的委派：子游戏会话会挂到它下面（I1 的嵌套形态）。 */
    public static void begin(String game, World world, String arenaName, List<UUID> players,
                             com.hub.session.GameSession parentSession) {
        Task task = new Task(game, world, arenaName,
                players == null ? List.of() : List.copyOf(players), parentSession);
        current = task;
        String roomId = RoomContext.currentRoomId();
        if (roomId != null && game != null) {
            synchronized (BY_ROOM) {
                BY_ROOM.put(game + "@" + roomId, task);
            }
        }
    }

    /** 结束委派：**只清当前房间那一条**（没有房间上下文时清兜底）。 */
    public static void end() {
        String roomId = RoomContext.currentRoomId();
        Task removed = null;
        if (roomId != null) {
            synchronized (BY_ROOM) {
                java.util.Iterator<java.util.Map.Entry<String, Task>> it = BY_ROOM.entrySet().iterator();
                while (it.hasNext()) {
                    var e = it.next();
                    if (e.getKey().endsWith("@" + roomId)) {
                        removed = e.getValue();
                        it.remove();
                    }
                }
            }
        }
        if (removed == null || (current != null && current == removed)) {
            current = null;
        }
    }

    /** 按房间结束委派（派对换关时用，避免误清别场）。 */
    public static void end(String game, String roomId) {
        Task removed;
        synchronized (BY_ROOM) {
            removed = BY_ROOM.remove(game + "@" + roomId);
        }
        if (removed != null && removed == current) {
            current = null;
        }
    }

    /** 当前委派（没有则 null）。 */
    public static Task current() {
        return current;
    }

    /**
     * 该游戏当前是否被委派到某个世界。
     *
     * @return 委派的世界；没有委派、或委派的不是这个游戏，返回 null
     */
    public static World worldFor(String gameKey) {
        if (gameKey == null) {
            return null;
        }
        // ① 有房间上下文 → 只认这个房间的那一条（两场派对并行时互不干扰）
        String roomId = RoomContext.currentRoomId();
        if (roomId != null) {
            Task byRoom;
            synchronized (BY_ROOM) {
                byRoom = BY_ROOM.get(gameKey + "@" + roomId);
            }
            if (byRoom != null) {
                return byRoom.world();
            }
        }
        // ② 旧路径：没有房间上下文（或该房间没有委派）→ 退回"最后一次委派"
        Task t = current;
        if (t == null || !gameKey.equals(t.game())) {
            return null;
        }
        return t.world();
    }

    /** 是否正在为某个游戏执行委派。 */
    public static boolean isDelegated(String gameKey) {
        return worldFor(gameKey) != null;
    }
}
