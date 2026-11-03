package com.hub.session;

import com.hub.diagnostics.Failures;
import com.hub.region.RegionRegistry;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * **区域裁决的分发者** —— {@code docs/框架不变量.md} 的 I3（事件裁决唯一）在 C 类事件上的落点，
 * 设计见 {@code docs/区域裁决设计.md}。
 *
 * <p>它只做一件事：算出这条事件**归谁的**，然后交给那个会话的 {@link SessionArbiter}。
 * 判断一律不做 —— 判断属于游戏。
 *
 * <p>归属判定顺序（和 A 类一致，见设计文档 §2）：
 * <ol>
 *   <li>有玩家 → 该玩家归属链的**叶子会话**（最深那层，派对 ⊃ 小游戏 时是小游戏）；</li>
 *   <li>没玩家（爆炸、投掷物、自然伤害）→ 按**发生位置**查 I2 区域表；</li>
 *   <li>都不命中 → **公共区**，本类返回、不碰事件（世界规则说了算）。</li>
 * </ol>
 *
 * <p>优先级固定在 <b>LOWEST</b>：抢在游戏现存处理器前面，让"框架放行、游戏却取消"的混合态
 * **暴露成 bug** 而不是被事件顺序掩盖。迁移要求：**一个事件类型一次性迁完**。
 */
public final class RegionEvents implements Listener {

    // ---------- 伤害 ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        GameSession owner = ownerOfDamage(event);
        if (owner == null) {
            return;
        }
        // 先给游戏自己的原始事件钩子（既有逻辑可以逐字搬过来），再套语义裁决器
        owner.fireEntityDamage(event);
        if (event.isCancelled() || !owner.arbiter().handles(EventKind.DAMAGE)) {
            return;
        }
        Player victim = event.getEntity() instanceof Player p ? p : null;
        Player damager = event instanceof EntityDamageByEntityEvent by
                && by.getDamager() instanceof Player dp ? dp : null;
        EventContext ctx = new EventContext(EventKind.DAMAGE, victim, damager, null,
                event.getEntity().getLocation(), victim == null ? null : victim.getInventory().getItemInMainHand(),
                event);
        dispatch(owner, ctx, () -> event.setCancelled(true), event);
    }

    // ---------- 方块 ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        GameSession owner = ownerOfPlayer(event.getPlayer());
        if (owner == null) {
            return;
        }
        owner.fireBlockBreak(event);
        if (event.isCancelled()) {
            return;   // 游戏自己的钩子已经决定了
        }
        Block block = event.getBlock();
        EventContext ctx = new EventContext(EventKind.BLOCK_BREAK, event.getPlayer(), null, block,
                block.getLocation(), event.getPlayer().getInventory().getItemInMainHand(), event);
        dispatch(owner, ctx, () -> event.setCancelled(true), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        GameSession owner = ownerOfPlayer(event.getPlayer());
        if (owner == null) {
            return;
        }
        owner.fireBlockPlace(event);
        if (event.isCancelled()) {
            return;
        }
        Block block = event.getBlock();
        EventContext ctx = new EventContext(EventKind.BLOCK_PLACE, event.getPlayer(), null, block,
                block.getLocation(), event.getItemInHand(), event);
        dispatch(owner, ctx, () -> event.setCancelled(true), event);
    }

    // ---------- 交互 ----------

    /**
     * 交互（右键/左键，空气或方块）。
     *
     * <p>绝大多数是"右键自家道具 → 开菜单 / 退出 / 开始"，属于 B 类（自家 GUI），
     * 但同样需要"只有归属本局的玩家才派发"——否则一个人在别人的场地里右键也会触发。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        GameSession owner = ownerOfPlayer(event.getPlayer());
        if (owner == null) {
            return;
        }
        owner.firePlayerInteract(event);
        if (event.isCancelled()) {
            return;
        }
        Block clicked = event.getClickedBlock();
        EventContext ctx = new EventContext(EventKind.INTERACT, event.getPlayer(), null, clicked,
                clicked == null ? event.getPlayer().getLocation() : clicked.getLocation(),
                event.getItem(), event);
        dispatch(owner, ctx, () -> event.setCancelled(true), event);
    }

    // ---------- 移动 ----------

    /**
     * 移动：**每 tick 每个玩家一条**，所以这里刻意只做"查归属 + 转发"，
     * 区域查表等重活由游戏自己按需缓存（见 docs/区域裁决设计.md §5）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent event) {
        GameSession owner = ownerOfPlayer(event.getPlayer());
        if (owner == null) {
            return;
        }
        owner.firePlayerMove(event);
    }

    // ---------- 归属判定 ----------

    /** 玩家 → 他归属链的叶子会话（大厅玩家返回 null）。 */
    private static GameSession ownerOfPlayer(Player p) {
        return p == null ? null : PlayerSessions.sessionOf(p.getUniqueId());
    }

    /**
     * 伤害的归属者：**先看受害者，再看施加者**，最后按位置。
     *
     * <p>为什么受害者优先：一条伤害"发生在谁身上"决定了谁最该说话；
     * 一个在自己场地里的人被外面的人打，应该由**他所处的那一局**判断（而不是打人者的）。
     */
    private static GameSession ownerOfDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player victim) {
            GameSession s = ownerOfPlayer(victim);
            if (s != null) {
                return s;
            }
        }
        if (event instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player damager) {
            GameSession s = ownerOfPlayer(damager);
            if (s != null) {
                return s;
            }
        }
        // 位置兜底：无主爆炸/投掷物/自然伤害 → 谁的区域谁说话
        Location where = event.getEntity().getLocation();
        String gameKey = RegionRegistry.ownerAt(where);
        if (gameKey == null) {
            return null;
        }
        for (GameSession s : GameSession.activeSessions()) {
            if (gameKey.equalsIgnoreCase(s.gameKey()) && s.isActive()) {
                return s;
            }
        }
        return null;
    }

    /** 派发 + 按裁决结果落实（唯一的"框架动手"的地方）。 */
    private static void dispatch(GameSession owner, EventContext ctx, Runnable cancelAction, Object raw) {
        try {
            // fire 是同包的包级方法：SessionArbiter#fire 不在公共 API 里
            owner.arbiter().fire(ctx.kind(), ctx);
        } catch (RuntimeException ex) {
            Failures.recordAndWarn(owner.gameKey() + ".分发/" + ctx.kind(), ex);
            return;
        }
        if (ctx.isCancelled()) {
            cancelAction.run();
        }
    }
}
