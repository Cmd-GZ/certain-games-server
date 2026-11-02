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
        current = new Task(game, world, arenaName,
                players == null ? List.of() : List.copyOf(players), parentSession);
    }

    /** 结束委派。 */
    public static void end() {
        current = null;
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
        Task t = current;
        if (t == null || gameKey == null || !gameKey.equals(t.game())) {
            return null;
        }
        return t.world();
    }

    /** 是否正在为某个游戏执行委派。 */
    public static boolean isDelegated(String gameKey) {
        return worldFor(gameKey) != null;
    }
}
