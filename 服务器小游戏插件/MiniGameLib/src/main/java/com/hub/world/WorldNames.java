package com.hub.world;

import java.util.Locale;

/**
 * 世界命名的**唯一权威**（{@code docs/世界命名与前缀.md}，决策 D19）。
 *
 * <p>为什么要单独一个类：在此之前 `/gw create` 和 `RoomManager` **各自实现了一套前缀规则**，
 * 这是最典型的"同一规则两处实现"——迟早漂移，而且漂移后表现为"某些世界的命名不被识别"。
 *
 * <h2>三段命名空间</h2>
 * <table>
 *   <tr><th>前缀</th><th>是什么</th><th>谁产出</th><th>开服加载</th></tr>
 *   <tr><td>{@code tmpl_}</td><td>模板：设计者编辑的地图源</td><td>{@code /gw create}</td><td>否</td></tr>
 *   <tr><td>{@code room_}</td><td>房间实例：模板的副本，会被自动回收</td><td>{@code RoomManager}</td><td>否（按需）</td></tr>
 *   <tr><td>无前缀</td><td>持久世界：主城这类永久世界</td><td>{@code /gw create --persist}</td><td>是</td></tr>
 * </table>
 *
 * <p>注意 {@code world_} 是**历史遗留**的持久世界前缀，只用于识别，不再作为新建世界的默认。
 */
public final class WorldNames {

    /** 模板世界前缀。 */
    public static final String TEMPLATE = "tmpl_";
    /** 房间实例前缀（房间引擎独占）。 */
    public static final String ROOM = "room_";
    /** 历史遗留的持久世界前缀（只识别，不新建）。 */
    public static final String LEGACY = "world_";

    /** 一份世界的"种类"。 */
    public enum Kind {
        /** 模板：设计者编辑，永不当场地加载。 */
        TEMPLATE,
        /** 房间实例：引擎管生管死。 */
        ROOM,
        /** 持久世界：主城这类，开服加载。 */
        PERSISTENT
    }

    private WorldNames() {
    }

    /** 名字的种类。判定顺序：模板 > 房间 > 持久（其它一律算持久）。 */
    public static Kind classify(String name) {
        if (name == null || name.isBlank()) {
            return Kind.PERSISTENT;
        }
        if (name.startsWith(TEMPLATE)) {
            return Kind.TEMPLATE;
        }
        if (name.startsWith(ROOM)) {
            return Kind.ROOM;
        }
        return Kind.PERSISTENT;
    }

    public static boolean isTemplate(String name) {
        return classify(name) == Kind.TEMPLATE;
    }

    public static boolean isRoom(String name) {
        return classify(name) == Kind.ROOM;
    }

    /** 名字是否已经带了**任何**已知前缀（含历史 `world_` 与裸名 {@code world}）。 */
    public static boolean hasKnownPrefix(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return name.startsWith(TEMPLATE)
                || name.startsWith(ROOM)
                || name.startsWith(LEGACY)
                || name.equals("world");
    }

    /** 把一个裸名字规范成模板名（已带 `tmpl_` 则原样）。 */
    public static String templateName(String raw) {
        String n = sanitize(raw);
        return n.startsWith(TEMPLATE) ? n : TEMPLATE + n;
    }

    /** 房间世界名：`room_<游戏>_<序号>`。序号由调用方决定（引擎负责不重名）。 */
    public static String roomName(String gameKey, int index) {
        return ROOM + sanitize(gameKey) + "_" + index;
    }

    /** 把任意输入洗成合法世界名（小写、只留 `[a-z0-9_-]`）。 */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
    }

    /** 给玩家/运维看的种类名。 */
    public static String describe(String name) {
        return switch (classify(name)) {
            case TEMPLATE -> "模板";
            case ROOM -> "房间";
            case PERSISTENT -> "持久世界";
        };
    }
}
