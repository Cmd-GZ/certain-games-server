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
        // 先给通用钩子，再给"原始事件钩子"，最后才套语义裁决器
        owner.fireGameEvent(event);
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
        owner.fireGameEvent(event);
        if (event.isCancelled()) {
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
        owner.fireGameEvent(event);
        if (event.isCancelled()) {
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
            // **公共区兜底**：建场工具这类"还没有对局"的交互，派给声明 wantsPublicEvents 的会话
            for (GameSession s : GameSession.activeSessions()) {
                if (s.isActive()) {
                    s.firePublicEvent(event);
                }
            }
            return;
        }
        owner.fireGameEvent(event);
        if (event.isCancelled()) {
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
    /** 玩家 → 上一次所在区域的 gameKey（区域进出检测用）。 */
    private static final java.util.Map<java.util.UUID, String> LAST_REGION_GAME = new java.util.HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent event) {
        // ① 区域进出检测（**不依赖会话**）：给"进区域才建会话"的游戏用（SkyPit / D26）
        trackRegion(event.getPlayer(), event.getTo() == null ? event.getPlayer().getLocation() : event.getTo());
        // ② 归属会话派发
        GameSession owner = ownerOfPlayer(event.getPlayer());
        if (owner == null) {
            return;
        }
        owner.fireGameEvent(event);
        owner.firePlayerMove(event);
    }

    /** 比较"上次所在区域"和"现在所在区域"，变化时回调 {@link com.hub.region.RegionHooks}。 */
    private static void trackRegion(Player player, org.bukkit.Location to) {
        com.hub.region.ArenaRegion now = com.hub.region.RegionRegistry.regionAt(to);
        String nowKey = now == null ? null : now.gameKey();
        java.util.UUID id = player.getUniqueId();
        String wasKey = LAST_REGION_GAME.get(id);
        if (java.util.Objects.equals(wasKey, nowKey)) {
            return;
        }
        if (nowKey == null) {
            LAST_REGION_GAME.remove(id);
        } else {
            LAST_REGION_GAME.put(id, nowKey);
        }
        try {
            if (wasKey != null) {
                com.hub.region.ArenaRegion was = com.hub.region.RegionRegistry.regionAt(player.getLocation());
                com.hub.region.RegionHooks.fireLeave(wasKey, player, was == null ? now : was);
            }
            if (nowKey != null) {
                com.hub.region.RegionHooks.fireEnter(nowKey, player, now);
            }
        } catch (RuntimeException ex) {
            Failures.recordAndWarn("RegionEvents.区域进出", ex);
        }
    }

    /** 玩家离线/被清理时把"上次所在区域"也清掉，免得复用时误判。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitForRegion(org.bukkit.event.player.PlayerQuitEvent event) {
        LAST_REGION_GAME.remove(event.getPlayer().getUniqueId());
    }


    // ---------- 兜底桥接：孤立事件类型 ----------
    //
    // 这里**只做"算归属 + 转发"**，判断永远在游戏侧。注册的都是"没有玩家主体的位置类事件"
    // 以及少数"有玩家但类型很冷门"的事件；有玩家主体的走 ownerOfPlayer。

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        bridgeLocation(event, event.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) {
        bridgeLocation(event, event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(org.bukkit.event.block.BlockFromToEvent event) {
        bridgeLocation(event, event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(org.bukkit.event.block.BlockBurnEvent event) {
        bridgeLocation(event, event.getBlock().getLocation());
    }

    /**
     * 区块加载：按**区块所在位置**找归属者（I2 区域表）。
     *
     * <p>真实漏点（2026-11-04）：SkyPit 的 `onChunkLoad`（区块加载时刷新排行榜）被搬迁脚本跳过了
     * —— 它的签名是**全限定类名**（`org.bukkit.event.world.ChunkLoadEvent`），脚本的正则只认简单名。
     * 补上这条桥接，这类"世界级、按位置"的事件才有归属者。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        bridgeLocation(event, new Location(event.getWorld(),
                (event.getChunk().getX() << 4) + 8, 64, (event.getChunk().getZ() << 4) + 8));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockIgnite(org.bukkit.event.block.BlockIgniteEvent event) {
        bridgeLocation(event, event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(org.bukkit.event.block.BlockSpreadEvent event) {
        bridgeLocation(event, event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(org.bukkit.event.block.BlockFormEvent event) {
        bridgeLocation(event, event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(org.bukkit.event.player.PlayerBucketEmptyEvent event) {
        bridgePlayer(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(org.bukkit.event.entity.EntityChangeBlockEvent event) {
        bridgeLocation(event, event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(org.bukkit.event.entity.ItemSpawnEvent event) {
        bridgeLocation(event, event.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        // 抛射物：先看射手是不是某个会话的人（谁打出去的谁管），否则看落点
        if (event.getEntity().getShooter() instanceof Player shooter) {
            GameSession s = ownerOfPlayer(shooter);
            if (s != null) {
                s.fireGameEvent(event);
                return;
            }
        }
        bridgeLocation(event, event.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(org.bukkit.event.entity.ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player shooter) {
            bridgePlayer(event, shooter);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        bridgeLocation(event, event.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        // 刚进服还没有归属 —— 派发给**所有**活动会话，各自判断要不要管（每人自己看条件）
        for (GameSession s : GameSession.activeSessions()) {
            if (s.isActive()) {
                s.fireGameEvent(event);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignOpen(org.bukkit.event.player.PlayerSignOpenEvent event) {
        bridgePlayer(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p) {
            GameSession s = ownerOfPlayer(p);
            if (s != null) {
                s.fireGameEvent(event);
                return;
            }
            bridgeLocation(event, p.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player p) {
            GameSession s = ownerOfPlayer(p);
            if (s != null) {
                s.fireGameEvent(event);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        bridgePlayer(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommandPreprocess(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        bridgePlayer(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        bridgePlayer(event, event.getPlayer());
    }

    /**
     * 桥接里的**重入闸**：游戏在事件里做的事可能再次触发同类事件。
     *
     * <p>真实事故（2026-11-04，BuildRun）：`PlayerTeleportEvent` → 游戏 `confine()` →
     * `player.teleport(...)` → 又一条 `PlayerTeleportEvent` → 再派发 → **StackOverflowError**
     * （服务器刷了 370 条 `Could not pass event`）。闸门只挡住"派发过程中再派发"，
     * 游戏自己的第二次传送照常执行 —— 那是它想要的结果。
     */
    private static final ThreadLocal<Boolean> DISPATCHING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static void bridgePlayer(org.bukkit.event.Event event, Player player) {
        if (Boolean.TRUE.equals(DISPATCHING.get())) {
            return;   // 派发过程中再触发的事件不再派发（防重入）
        }
        GameSession s = ownerOfPlayer(player);
        if (s == null) {
            return;
        }
        DISPATCHING.set(Boolean.TRUE);
        try {
            s.fireGameEvent(event);
        } finally {
            DISPATCHING.set(Boolean.FALSE);
        }
    }

    /** 位置类事件：按 I2 区域表算归属，只发给那一个会话。 */
    private static void bridgeLocation(org.bukkit.event.Event event, Location where) {
        if (where == null) {
            return;
        }
        String gameKey = RegionRegistry.ownerAt(where);
        if (gameKey == null) {
            return;
        }
        for (GameSession s : GameSession.activeSessions()) {
            if (s.isActive() && gameKey.equalsIgnoreCase(s.gameKey())) {
                s.fireGameEvent(event);
                return;
            }
        }
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
