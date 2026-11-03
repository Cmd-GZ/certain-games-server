package com.hub.session;

import com.hub.diagnostics.Failures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * **单一事件裁决入口**（第一批：生命周期 + 环境）—— {@code docs/框架不变量.md} 的
 * <b>I3（事件裁决唯一）</b>。
 *
 * <p>重构前每个游戏模块都自己监听这几类事件，于是同一件事有 25 个"说了算的人"：
 * 谁先谁后取决于注册顺序，某个插件忘了处理就漏（真实事故：20 多个游戏只接
 * {@code session.join} 没接 {@code leave}，玩家用床退出后归属链上还挂着他，
 * 之后点任何游戏都被 I1 拒绝，只能重启服务器）。
 *
 * <p>现在这些事件**只在这里**处理一次：
 * <ul>
 *   <li>{@code PlayerQuitEvent}（MONITOR）→ 先派发给所属会话的
 *       {@link GameSession#onPlayerQuit(Player)}，再由框架
 *       {@link PlayerSessions#quitToLobby(Player)} <b>兜底摘链</b>。
 *       也就是说"下线不摘链"这一类泄漏在结构上不可能再发生。</li>
 *   <li>{@code PlayerDeathEvent} / {@code PlayerRespawnEvent} → 只派发给
 *       "这个玩家所属的那个会话"。游戏不必再自己判断"他在不在我的场地里"。</li>
 *   <li>{@code FoodLevelChangeEvent} / {@code PlayerDropItemEvent} → 读该会话的
 *       {@link SessionRules}（默认：局内不掉饥饿；掉落由游戏声明）。</li>
 * </ul>
 *
 * <p>优先级为什么这么选：
 * <ul>
 *   <li>下线 / 死亡 / 重生用 <b>MONITOR</b>（最后跑）：还没迁移过来的游戏，它自己的
 *       {@code @EventHandler} 照旧先执行，行为零变化；框架的摘链只是**补最后一道保险**。</li>
 *   <li>饥饿 / 掉落用 <b>LOWEST</b>（最先跑）：这两条是"不许发生"，越早取消越省事，
 *       迁移期间和游戏自己的处理器同时取消也无害（幂等）。</li>
 * </ul>
 *
 * <p>由 {@code MiniGameLibPlugin.onEnable} 注册，游戏侧不需要任何注册动作。
 */
public final class SessionEvents implements Listener {

    /**
     * 下线**派发**（LOWEST = 最先跑）。
     *
     * <p>为什么派发要抢在所有人前面：游戏自己的 {@code @EventHandler(PlayerQuitEvent)}
     * 往往在 HIGHEST/NORMAL —— 派对那种父级一旦先跑，它会把整棵子树 {@code end} 掉（父死子必死），
     * 等轮到框架时归属链已经空了，子游戏的 {@code onPlayerQuit} 就再也收不到。
     * 所以派发放在最前面，**兜底摘链**放在最后（见 {@link #onQuitSafetyNet}）。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        for (GameSession s : leafFirst(p.getUniqueId())) {
            // 叶子 → 根：先让子游戏（派对里的那一局）处理，再让父级（派对）处理。
            // 这一行是 R3（失败可见）的延伸：排查"下线没摘干净"时，日志里能直接看到派发给了谁。
            Bukkit.getLogger().info("[会话] " + p.getName() + " 下线 → 已通知 "
                    + s.gameKey() + " [" + s.state() + "]"
                    + (s.roomId() == null ? "" : " 房间 " + s.roomId()));
            s.firePlayerQuit(p);
        }
    }

    /**
     * 下线**兜底摘链**（MONITOR = 最后跑）：不管游戏有没有调 {@code leave}，一律摘干净。
     *
     * <p>这条就是硬规则 9 的机器化 —— 重构前 20 多个游戏只接了 {@code join} 没接 {@code leave}，
     * 玩家下线后归属链上还挂着他，之后点任何游戏都被 I1 拒绝（只能重启服务器）。
     * 现在这类泄漏在结构上不可能再发生，新游戏也不用再操心。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitSafetyNet(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        int depth = PlayerSessions.chainOf(id).size();
        if (depth == 0) {
            return;
        }
        try {
            PlayerSessions.quitToLobby(p);
            Bukkit.getLogger().info("[会话] " + p.getName() + " 下线 → 归属链已摘除（" + depth + " 层）");
        } catch (RuntimeException ex) {
            Failures.recordAndWarn("SessionEvents.下线摘链", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!(event.getEntity() instanceof Player p)) {
            return;
        }
        GameSession s = PlayerSessions.sessionOf(p.getUniqueId());
        if (s != null) {
            s.firePlayerDeath(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        GameSession s = PlayerSessions.sessionOf(p.getUniqueId());
        if (s != null) {
            s.firePlayerRespawn(event);
        }
    }

    /** 局内不掉饥饿（{@link SessionRules#noHunger()}，默认开）。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFood(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player p)) {
            return;
        }
        GameSession s = PlayerSessions.sessionOf(p.getUniqueId());
        if (s != null && s.rules().noHunger()) {
            event.setCancelled(true);
        }
    }

    /** 局内禁止丢弃物品（{@link SessionRules#noItemDrop()}，由游戏声明）。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDropItem(PlayerDropItemEvent event) {
        GameSession s = PlayerSessions.sessionOf(event.getPlayer().getUniqueId());
        if (s != null && s.rules().noItemDrop()) {
            event.setCancelled(true);
        }
    }

    /** 叶子 → 根（{@link PlayerSessions#chainOf} 是根 → 叶子，这里翻过来）。 */
    private static List<GameSession> leafFirst(UUID id) {
        List<GameSession> chain = PlayerSessions.chainOf(id);
        List<GameSession> out = new ArrayList<>(chain.size());
        for (int i = chain.size() - 1; i >= 0; i--) {
            out.add(chain.get(i));
        }
        return out;
    }
}
