package com.hub.api;

import com.hub.diagnostics.Failures;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 供小游戏插件调用的公共 API（静态入口，无需注入）。
 * <p>
 * 本类在公共库 MiniGameLib 里，主城插件（HubCore）通过 {@link #register(HubService)} 把实现接进来，
 * 所以小游戏插件只依赖公共库、不用依赖主城插件。
 * <p>
 * 接入方式：游戏插件把 MiniGameLib.jar 加入编译依赖（provided），在关键时机调用：
 * <ul>
 *   <li>玩家进入等待室：{@link #setGame(UUID, String)} 或 {@link #setGameState(UUID, String, GameState)} → tab 显示 [等待中]；</li>
 *   <li>游戏正式开始：{@link #setPlaying(UUID, String)} → tab 显示 [已加入]；</li>
 *   <li>场地状态变化：{@link #setArena(String, String, GameState, int, String)} → 中央菜单的场地羊毛；</li>
 *   <li>游戏结束 / 玩家退出：{@link #clearGame(UUID)}；如需回主城再调 {@link #teleportToLobby(Player)}。</li>
 * </ul>
 * 玩家退出服务器时主城插件会自动清理状态，无需在 PlayerQuitEvent 里处理。
 * 主城插件缺席时所有调用都是空操作，不会抛异常。
 */
public final class HubAPI {

    private static volatile HubService service;

    private HubAPI() {
    }

    // ---------- 与主城插件对接 ----------

    /** 主城插件在 onEnable 里注册实现。 */
    public static void register(HubService impl) {
        service = impl;
    }

    /** 主城插件在 onDisable 里注销（只注销自己注册的那个）。 */
    public static void unregister(HubService impl) {
        if (service == impl) {
            service = null;
        }
    }

    /** 主城插件是否就绪。 */
    public static boolean isReady() {
        return service != null;
    }

    // ---------- 游戏状态（tab / 菜单） ----------

    /** 玩家进入某游戏等待室（tab 显示 [等待中·游戏名]）。等价 setGameState(player, game, WAITING)。 */
    public static void setGame(UUID player, String gameName) {
        HubService s = service;
        if (s != null) {
            s.setGame(player, gameName);
        }
    }

    /** 游戏正式开始，玩家已加入对局（tab 显示 [已加入·游戏名]）。 */
    public static void setPlaying(UUID player, String gameName) {
        HubService s = service;
        if (s != null) {
            s.setPlaying(player, gameName);
        }
    }

    /** 设置玩家在某游戏中的状态：WAITING = 等待中，PLAYING = 已加入。 */
    public static void setGameState(UUID player, String gameName, GameState state) {
        HubService s = service;
        if (s != null) {
            s.setGameState(player, gameName, state);
        }
    }

    /** 玩家结束游戏回大厅。 */
    public static void clearGame(UUID player) {
        HubService s = service;
        if (s != null) {
            s.clearGame(player);
        }
    }

    /**
     * 覆盖玩家 tab 列表名字的颜色（对局内按队伍染色，如绿茵足球红/蓝队）。
     *
     * @param colorCode legacy 颜色代码字符（如 "c" 红 / "9" 蓝）；null 或空串恢复默认颜色
     */
    public static void setTabNameColor(UUID player, String colorCode) {
        HubService s = service;
        if (s != null) {
            s.setTabNameColor(player, colorCode);
        }
    }

    /**
     * 全服公屏播报"某某加入了某游戏"（所有小游戏插件在玩家成功加入等待室/进入场地时调用）。
     *
     * @param gameIdOrName 游戏标识：传插件名（如 {@code plugin.getName()}）或游戏显示名都行，
     *                     主城会查注册表归一化成显示名，并套用该游戏的颜色
     * @param playerName   加入的玩家名
     */
    public static void announceJoin(String gameIdOrName, String playerName) {
        HubService s = service;
        if (s != null) {
            s.announceJoin(gameIdOrName, playerName);
        }
    }

    /**
     * 带场地名的加入播报：公屏显示成 {@code [游戏名][场地名] 玩家名 加入了游戏}。
     * <p>只有多场地的游戏（如单挑）需要传场地名；只报游戏名时用上面那个重载。
     *
     * @param gameIdOrName 游戏标识：传插件名（如 {@code plugin.getName()}）或游戏显示名都行
     * @param playerName   加入的玩家名
     * @param arenaName    场地名（为空则退化成不带场地的播报）
     */
    public static void announceJoin(String gameIdOrName, String playerName, String arenaName) {
        HubService s = service;
        if (s != null) {
            s.announceJoin(gameIdOrName, playerName, arenaName);
        }
    }

    /** 运行时登记一个游戏（一般不需要：主城插件会自动扫描带接入标记的插件）。 */
    public static void registerGame(String id, String displayName, String icon, String color) {
        HubService s = service;
        if (s != null) {
            s.registerGame(id, displayName, icon, color);
        }
    }

    /**
     * 上报某游戏一个场地的最新状态（中央菜单羊毛行数据源）。
     * 游戏插件在场地状态变化时调用：等待中=WAITING，对局中=PLAYING。
     *
     * @param gameName  与 setGame 相同的游戏名（别名会被归一化）
     * @param arenaName 场地名
     * @param state     场地状态
     * @param players   场地内人数（用于"加入人最多的场地"）
     * @param extra     对局中的附加状态（如"第 3/8 轮"），没有传 null
     */
    public static void setArena(String gameName, String arenaName, GameState state, int players, String extra) {
        HubService s = service;
        if (s != null) {
            s.setArena(gameName, arenaName, state, players, extra);
        }
    }

    /**
     * 上报某场地的观战落点（大厅玩家进观战时传送到这里）。
     * 传 null 清除，主城退回「跟着某位存活玩家」；arenaName 传空串 = 整个游戏共用一个点。
     */
    public static void setArenaSpectator(String gameName, String arenaName, Location location) {
        HubService s = service;
        if (s != null) {
            s.setArenaSpectator(gameName, arenaName, location);
        }
    }

    /** 场地被删除时通知主城移除其菜单状态。 */
    public static void removeArena(String gameName, String arenaName) {
        HubService s = service;
        if (s != null) {
            s.removeArena(gameName, arenaName);
        }
    }

    // ---------- 大厅 ----------

    /** 传送到大厅并给菜单道具。 */
    public static void teleportToLobby(Player player) {
        HubService s = service;
        if (s != null) {
            s.teleportToLobby(player);
        }
    }

    /** 给玩家菜单道具（不清背包）。 */
    public static void giveMenuItem(Player player) {
        HubService s = service;
        if (s != null) {
            s.giveMenuItem(player);
        }
    }

    /** 给玩家"回主城"道具（右键 = 退出所有游戏回主城）。 */
    public static void giveHubItem(Player player) {
        HubService s = service;
        if (s != null) {
            s.giveHubItem(player);
        }
    }

    /** 判断物品是否为"回主城"道具（带 PDC 标记，防同名物品误触发）。 */
    public static boolean isHubItem(ItemStack item) {
        HubService s = service;
        return s != null && s.isHubItem(item);
    }

    /** 大厅位置（未配置时返回 null）。 */
    public static Location getLobby() {
        HubService s = service;
        return s == null ? null : s.getLobby();
    }

    // ---------- 回主城联动（小游戏插件接入） ----------

    private static final List<Consumer<Player>> lobbyHandlers = new CopyOnWriteArrayList<>();
    private static volatile boolean notifyingLobby = false;

    /**
     * 游戏插件注册"玩家回主城"回调：玩家执行 /hub 或右键回主城道具时，
     * 主城插件会调用所有已注册回调，让游戏插件把玩家踢出自身游戏状态（再回主城）。
     * 回调内部可安全调用 HubAPI.teleportToLobby（有防递归保护）。
     */
    public static void registerLobbyHandler(Consumer<Player> handler) {
        if (handler != null) {
            lobbyHandlers.add(handler);
        }
    }

    /** 内部：玩家回主城时通知所有已注册游戏插件（防递归）。 */
    public static void notifyLobbyHandlers(Player p) {
        if (notifyingLobby) {
            return;
        }
        notifyingLobby = true;
        try {
            for (Consumer<Player> h : lobbyHandlers) {
                try {
                    h.accept(p);
                } catch (Exception ex) {
                    // 单个插件出错不能影响其它插件回主城，但**必须留痕**（R3 失败可见）。
                    // 这里原来是一个空的 catch，于是"某个游戏的 lobby handler 一直崩"
                    // 在日志里完全看不出来，只能靠玩家反馈"回主城后状态不对"。
                    Failures.recordAndWarn("HubAPI.lobbyHandler/" + handlerName(h), ex);
                }
            }
        } finally {
            notifyingLobby = false;
        }
    }

    /**
     * 尽量从 lambda 里挖出注册方的类名，让 doctor 能定位到是谁崩了。
     * lambda 的类名形如 {@code com.skypit.SkyPitPlugin$$Lambda$123/0x...}，截到 {@code $$} 之前即可。
     */
    private static String handlerName(Consumer<Player> h) {
        String n = h.getClass().getName();
        int i = n.indexOf("$$");
        return i > 0 ? n.substring(0, i) : n;
    }

    /**
     * 某个游戏登记的所有场地及实时状态（只读快照）。
     * 没装主城/旧版主城时返回空列表。
     *
     * @param gameName 游戏显示名（与 {@link #setArena} 一致）
     */
    public static java.util.List<ArenaStatus> arenas(String gameName) {
        HubService s = service;
        return s == null ? java.util.List.of() : s.arenas(gameName);
    }

}
