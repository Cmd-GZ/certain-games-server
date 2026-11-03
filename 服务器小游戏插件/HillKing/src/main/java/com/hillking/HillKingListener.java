package com.hillking;

import com.hub.util.Chat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

/** 高台之王事件：等待室道具、咸鱼击退、无伤、掉台回场、场地保护。 */
public final class HillKingListener implements Listener {

    private final HillKingManager manager;

    public HillKingListener(HillKingManager manager) {
        this.manager = manager;
    }

    /** 等待室：右键床退出、右键开始纸由管理员开局。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
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
            manager.handleStartPaper(player);
        }
    }

    /** 场内玩家不掉血；咸鱼按配置击退。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFishHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)
                || !(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (manager.isInGame(victim)) {
            event.setCancelled(true);
        }
        if (!manager.isPlaying(victim) || !manager.isPlaying(attacker)) {
            return;
        }
        ItemStack main = attacker.getInventory().getItemInMainHand();
        ItemStack off = attacker.getInventory().getItemInOffHand();
        if (!manager.isFish(main) && !manager.isFish(off)) {
            Chat.actionBar(attacker, "&e请手持咸鱼再左键拍人");
            return;
        }
        if (manager.fishHit(attacker, victim)) {
            Chat.actionBar(attacker, "&a拍飞！");
        } else {
            Chat.actionBar(attacker, "&c咸鱼冷却中…");
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null) {
            manager.handleMove(event.getPlayer(), event.getTo());
        }
    }

}
