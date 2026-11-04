package com.hub.region;

import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * **区域进出钩子** —— 给"进区域才建会话"的游戏用（目前只有 SkyPit，见 D26）。
 *
 * <p>它解决的是一个鸡生蛋问题：SkyPit 的会话是"玩家走进天坑区域"时才创建的，而 I3 的
 * 事件派发是"先找到会话、再派发"—— 会话都还没有，就没法派发。所以这里给一个
 * **不依赖会话**的通道：框架自己每 tick 算"这个玩家现在落在哪块区域里"，
 * 进出变化时回调游戏注册的监听器。
 *
 * <p>约定：
 * <ul>
 *   <li>按 **gameKey** 注册，同一 gameKey 只留一个（重复注册会覆盖并告警）；</li>
 *   <li>只在该区域**属于这个 gameKey** 时才回调 —— 别的游戏的区域不会误送到你这里；</li>
 *   <li>回调发生在主线程、玩家移动之后；**不要在里面做重活**（每 tick 可能触发）。</li>
 * </ul>
 *
 * <p>由框架的 `RegionEvents` 每 tick 驱动，游戏侧不用注册任何监听器。
 */
public final class RegionHooks {

    /** 游戏要实现的回调。 */
    public interface Listener {
        /** 玩家进入了你的区域（此时**还没有**会话，需要你自己建）。 */
        void onRegionEnter(Player player, ArenaRegion region);

        /** 玩家离开了你的区域（会话可能已结束，实现里要容错）。 */
        void onRegionLeave(Player player, ArenaRegion region);
    }

    private static final Map<String, Listener> HOOKS = new LinkedHashMap<>();

    private RegionHooks() {
    }

    /** 注册（同一 gameKey 覆盖旧的）。 */
    public static synchronized void register(String gameKey, Listener listener) {
        if (gameKey == null || listener == null) {
            return;
        }
        Listener old = HOOKS.put(gameKey.toLowerCase(java.util.Locale.ROOT), listener);
        if (old != null) {
            org.bukkit.Bukkit.getLogger().warning("[区域钩子] " + gameKey + " 重复注册，已替换为新的");
        }
    }

    public static synchronized void unregister(String gameKey) {
        if (gameKey != null) {
            HOOKS.remove(gameKey.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** 某个 gameKey 是否注册过（doctor 用）。 */
    public static synchronized boolean has(String gameKey) {
        return gameKey != null && HOOKS.containsKey(gameKey.toLowerCase(java.util.Locale.ROOT));
    }

    public static synchronized java.util.Set<String> games() {
        return java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(HOOKS.keySet()));
    }

    /** 框架内部（RegionEvents）用：把一次"进出"派发给对应游戏的监听器。声明为 public 是因为
     *  分发者在 {@code com.hub.session} 包，跨包访问需要。 */
    public static void fireEnter(String gameKey, Player player, ArenaRegion region) {
        Listener l = hookFor(gameKey);
        if (l != null) {
            l.onRegionEnter(player, region);
        }
    }

    public static void fireLeave(String gameKey, Player player, ArenaRegion region) {
        Listener l = hookFor(gameKey);
        if (l != null) {
            l.onRegionLeave(player, region);
        }
    }

    private static synchronized Listener hookFor(String gameKey) {
        return gameKey == null ? null : HOOKS.get(gameKey.toLowerCase(java.util.Locale.ROOT));
    }

    /** 关服清理。 */
    public static synchronized void clear() {
        HOOKS.clear();
    }
}
