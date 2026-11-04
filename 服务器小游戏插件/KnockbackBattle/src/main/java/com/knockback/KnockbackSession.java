package com.knockback;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.entity.Snowball;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import com.hub.util.Chat;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;

/** 击退大作战的会话（D22：会话层按场地键索引，单图游戏只有一个键）。 */
final class KnockbackSession extends GameSession {

    private final KnockbackManager manager;

    KnockbackSession(KnockbackManager manager, String arenaName) {
        super("knockback", arenaName);
        this.manager = manager;

        // I3 C1（区域裁决）：本局禁改方块。原来 listener 里的 BlockBreak/BlockPlace 两个处理器
        // 都是"玩家在本局 → 取消"，框架只把**归属本局**的这类事件派发过来，所以这里直接取消。
        arbiter()
                .on(com.hub.session.EventKind.BLOCK_BREAK, ctx -> ctx.cancel())
                .on(com.hub.session.EventKind.BLOCK_PLACE, ctx -> ctx.cancel());
        enterWaiting();
    }

    void start() {
        enterRunning();
    }

    @Override
    protected void onStart() {
        // 本轮不改玩法
    }

    @Override
    protected void onEnd(EndReason reason) {
        manager.doEndGame();
    }

    // ---------- I3 A 批：从 listener 搬过来的处理器 ----------

    @Override
    protected void onPlayerQuit(Player player) {
        manager.handleQuit(player);
    }

    /** 掉落：只禁棍 / 风弹 / 回城床 / 开始纸（原 listener 里那段 @EventHandler）。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!manager.isInGame(event.getPlayer())) {
            return;
        }
        org.bukkit.inventory.ItemStack item = event.getItemDrop().getItemStack();
        if (manager.isStick(item) || manager.isWindCharge(item)
                || manager.isReturnItem(item) || manager.isStartItem(item)) {
            event.setCancelled(true);
        }
    }

    /** 右键：返回大厅床、管理员开始纸、风弹。 */
    @Override
    protected void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        if (manager.isReturnItem(item)) {
            event.setCancelled(true);
            manager.leave(player, false);
            return;
        }
        if (manager.isStartItem(item)) {
            event.setCancelled(true);
            if (!manager.isAdmin(player)) {
                Chat.send(player, "&c只有管理员可以开始游戏。");
            } else if (manager.isWaiting(player)) {
                manager.startGame(true);
            } else {
                Chat.send(player, "&e你不在等待队列中。");
            }
            return;
        }
        if (manager.isWindCharge(item)) {
            event.setCancelled(true);
            manager.useWindCharge(player);
        }
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------


    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof PlayerInteractEntityEvent e) {
        Player player = e.getPlayer();
        ItemStack held = player.getInventory().getItem(e.getHand());
        if (!manager.isWindCharge(held)) {
            return;
        }
        e.setCancelled(true);
        manager.useWindCharge(player);
                return;
        }
        if (event instanceof EntityDamageEvent e) {
        if (e.getEntity() instanceof Player player && manager.isInGame(player)) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)
                || !(e.getDamager() instanceof Player attacker)) {
            return;
        }
        if (!manager.isPlaying(victim)) {
            if (manager.isInGame(attacker)) {
                e.setCancelled(true);
            }
            return;
        }
        e.setCancelled(true);
        ItemStack main = attacker.getInventory().getItemInMainHand();
        ItemStack off = attacker.getInventory().getItemInOffHand();
        if (!manager.isStick(main) && !manager.isStick(off)) {
            Chat.actionBar(attacker, "&e请手持击退镐再左键敲人");
            return;
        }
        if (manager.stickHit(attacker, victim)) {
            Chat.actionBar(attacker, "&a击飞！");
        } else {
            Chat.actionBar(attacker, "&c击退镐冷却中…");
        }
                return;
        }
        if (event instanceof ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Snowball ball)) {
            return;
        }
        if (!manager.isOurWindCharge(ball.getUniqueId())) {
            return;
        }
        e.setCancelled(true);
        manager.detonateWindCharge(ball);
                return;
        }
        if (event instanceof PlayerMoveEvent e) {
        if (e.getTo() == null) {
            return;
        }
        manager.handleMove(e);
                return;
        }
    }
}
