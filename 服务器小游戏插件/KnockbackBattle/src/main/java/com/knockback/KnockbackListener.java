package com.knockback;

import com.hub.util.Chat;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

/** 击退大战事件入口。 */
public final class KnockbackListener implements Listener {

    private final KnockbackManager manager;

    public KnockbackListener(KnockbackManager manager) {
        this.manager = manager;
    }

    /** 直接右键实体时走这个事件，风弹同样能发射。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItem(event.getHand());
        if (!manager.isWindCharge(held)) {
            return;
        }
        event.setCancelled(true);
        manager.useWindCharge(player);
    }

    /** 击退镐：左键敲人，取消伤害并只给速度；其他伤害一律取消。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && manager.isInGame(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onStickHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)
                || !(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (!manager.isPlaying(victim)) {
            if (manager.isInGame(attacker)) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
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
    }

    /** 风弹命中：取消原版雪球效果，由管理器按配置击退。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball ball)) {
            return;
        }
        if (!manager.isOurWindCharge(ball.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        manager.detonateWindCharge(ball);
    }

    /** 对局中禁止破坏/放置方块。 */
    /** 禁止丢弃对局道具。 */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        manager.handleMove(event);
    }

}
