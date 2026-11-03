package com.firejumper;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

/** 跃火者事件：等待室道具、对局保护、出界判定、退出处理。 */
public final class FireJumperListener implements Listener {

    private final FireJumperPlugin plugin;
    private final FireJumperManager manager;

    public FireJumperListener(FireJumperPlugin plugin, FireJumperManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!manager.isWaiting(player)) {
            return;
        }
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
            if (player.hasPermission("firejumper.admin")) {
                manager.startGame(true);
            }
        }
    }

    /** 对局内免疫一切伤害（火焰线才是唯一的淘汰方式）。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (manager.isInGame(victim)) {
            event.setCancelled(true);
        }
    }

    /** 出界/掉出场地：直接淘汰。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        manager.checkBounds(event.getPlayer());
    }

    /** 保险：对局中真的死亡（理论上伤害都被取消）也按淘汰处理。 */
    /** 保险：如果玩家还是死了，重生后在等待点继续旁观。 */
}
