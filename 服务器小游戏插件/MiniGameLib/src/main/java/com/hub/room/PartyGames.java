package com.hub.room;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "**子小游戏**"的登记表。
 *
 * <p>范围由 **ArcadeParty 的 {@code games:} 配置**决定（它是唯一事实源），
 * 插件启用时通过 {@link #register(Iterable)} 登记进来。
 *
 * <p>为什么要区分"子小游戏"和"普通游戏"：
 * <ul>
 *   <li>**子小游戏**（hillking / tntrun / snakeparty …）本来只是派对里的一关；
 *       被单独打开时必须**套壳成派对**（{@code PARTY_OF_ONE}），流程才统一（D15）。
 *       套壳后它依然是子游戏，只是父级只有它一个。</li>
 *   <li>**普通游戏**（TNTBattle / duel / murder mystery …）**不是**子小游戏，
 *       不该套壳，自己拥有房间。</li>
 * </ul>
 *
 * <p>注意：一个模板世界可能**同时**装着这两类的地图（{@code world_mini_game} 就既有
 * 派对的大部分地图，也有 TNTBattle 的「双岛」）——所以"是否子小游戏"是**游戏**的属性，
 * 不是世界的属性。
 */
public final class PartyGames {

    private static final Set<String> SUB_GAMES = new LinkedHashSet<>();

    private PartyGames() {
    }

    /** 由 ArcadeParty 在启用时调用（它读自己的 {@code games:} 配置）。 */
    public static void register(Iterable<String> keys) {
        SUB_GAMES.clear();
        if (keys != null) {
            for (String k : keys) {
                if (k != null && !k.isBlank()) {
                    SUB_GAMES.add(k.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
    }

    /** 是否为子小游戏（需要套壳）。 */
    public static boolean isSubGame(String gameKey) {
        return gameKey != null && SUB_GAMES.contains(gameKey.toLowerCase(java.util.Locale.ROOT));
    }

    /** 全部子小游戏（doctor / 调试用）。 */
    public static List<String> all() {
        return new java.util.ArrayList<>(SUB_GAMES);
    }

    public static int size() {
        return SUB_GAMES.size();
    }

    public static void clear() {
        SUB_GAMES.clear();
    }
}
