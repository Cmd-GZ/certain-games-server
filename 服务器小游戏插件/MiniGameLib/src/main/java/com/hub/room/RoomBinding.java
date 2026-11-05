package com.hub.room;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * 房间换绑（R1.5）：让游戏**只改读取时看到的世界**，不动存储值。
 *
 * <p>为什么必须有这一层：早期 HillKing 的做法是"直接把 Location 的 world 改掉"，
 * 结果插件保存配置时把 `world_key: minecraft:room_sx` 写进了磁盘；房间一删，下次开服
 * `Location.deserialize` 直接抛错、还少了一块区域（R2 单一写者被破坏）。
 *
 * <p>正确做法（本类）：
 * <pre>
 *   // 读取坐标时
 *   Location where = RoomBinding.apply("hillking", arena.getCenter());
 * </pre>
 * 存储值永远是模板世界的原坐标；房间存在时读取才替换成房间世界。
 *
 * <p>换绑的目标由 {@link RoomManager#activeFor(String)} 决定：某游戏当前**恰好一个**
 * 已加载房间时，那个房间的世界就是换绑目标；0 个或多个都返回 null（多房间尚未支持，见 D22）。
 */
public final class RoomBinding {

    private RoomBinding() {
    }

    /** 该游戏当前应当绑定到的房间世界；没有（或不止一个房间）时返回 {@code null}。 */
    public static World worldFor(String gameKey) {
        // ① **父级委派优先**：子小游戏被派对叫来时，用父级给的世界（它不自己找房间）。
        World delegated = GameDelegation.worldFor(gameKey);
        if (delegated != null) {
            return delegated;
        }
        // ② **房间上下文优先**（D22 后半）：有上下文就是"这一间"，不再靠"名下有房间"猜。
        //    两房间同游戏并行时，`activeFor` 按设计返回 null，这条是唯一正确的来源。
        String roomId = RoomContext.currentRoomId();
        if (roomId != null) {
            RoomManager.Room ctx = RoomManager.room(roomId);
            if (ctx != null) {
                World w = ctx.arenasOf(gameKey).stream()
                        .map(RoomManager.ArenaSlot::world)
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(ctx.world);
                if (w != null) {
                    return w;
                }
            }
        }
        // ③ 老路径：没有上下文 → 找"名下恰好一个房间"的那个（单开普通游戏时用）。
        RoomManager.Room room = RoomManager.activeFor(gameKey);
        return room == null ? null : room.world;
    }

    /**
     * 把坐标换到房间世界（坐标不变）。
     *
     * @param gameKey 游戏 key（与 {@code GameSession} 的 gameKey 一致）
     * @param loc     存储里的坐标（可能是 null）
     * @return 换绑后的坐标；没有房间或 loc 为 null 时原样返回
     */
    public static Location apply(String gameKey, Location loc) {
        if (loc == null) {
            return null;
        }
        World w = worldFor(gameKey);
        if (w == null || w.equals(loc.getWorld())) {
            return loc;
        }
        return new Location(w, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    /** 当前是否已换绑（有活跃房间）。 */
    public static boolean isBound(String gameKey) {
        return worldFor(gameKey) != null;
    }
}
