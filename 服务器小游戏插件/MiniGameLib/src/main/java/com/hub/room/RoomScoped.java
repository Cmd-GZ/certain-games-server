package com.hub.room;

import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * **按房间分桶的一份状态**（D22 的最小工具）。
 *
 * <p>解决的问题：很多游戏的"一局状态"直接摊在 manager 里（`running` / `states` / `phase` …），
 * 键是硬编码的 `"main"` 或场地名 —— 同一游戏开第二个房间时两份状态就撞在一起。
 *
 * <p>用法（把原来的一堆字段收进一个 `Game` 对象，然后每房间一份）：
 * <pre>
 *   private final RoomScoped&lt;GtbGame&gt; games = new RoomScoped&lt;&gt;("gtb");
 *
 *   GtbGame gameFor(Player p, String arenaName) {
 *       return games.of(p, arenaName, key -&gt; new GtbGame(arenaName));
 *   }
 * </pre>
 *
 * <p>键的规则与框架其它地方一致：**有房间用房间 id，没有就用场地名**（单房间/静态场地行为不变）。
 * 另外提供按玩家 UUID 取键的版本，给"手上只有 UUID"的调用点用。
 */
public final class RoomScoped<T> {

    private final String gameKey;
    private final Map<String, T> byKey = new LinkedHashMap<>();

    public RoomScoped(String gameKey) {
        this.gameKey = gameKey;
    }

    /** 这个玩家应该落在哪个桶（房间 id 优先，否则场地名）。 */
    public String keyOf(Player p, String arenaName) {
        return RoomContext.roomKeyFor(p, gameKey, arenaName);
    }

    public String keyOf(UUID id, String arenaName) {
        String roomId = RoomContext.roomIdOfPlayer(id);
        return roomId == null || roomId.isBlank() ? arenaName : roomId;
    }

    /** 取（必要时创建）这个玩家所在房间的那份状态。 */
    public T of(Player p, String arenaName, Function<String, T> factory) {
        return ofKey(keyOf(p, arenaName), factory);
    }

    public T of(UUID id, String arenaName, Function<String, T> factory) {
        return ofKey(keyOf(id, arenaName), factory);
    }

    public T ofKey(String key, Function<String, T> factory) {
        T cur = byKey.get(key);
        if (cur == null) {
            cur = factory.apply(key);
            byKey.put(key, cur);
        }
        return cur;
    }

    /** 只读获取（不存在返回 null）。 */
    public T peek(String key) {
        return byKey.get(key);
    }

    /** 移除（对局结束时清掉，避免泄漏）。 */
    public T remove(String key) {
        return byKey.remove(key);
    }

    /** 当前所有桶（doctor / 遍历用）。 */
    public Map<String, T> all() {
        return java.util.Collections.unmodifiableMap(byKey);
    }

    public int size() {
        return byKey.size();
    }
}
