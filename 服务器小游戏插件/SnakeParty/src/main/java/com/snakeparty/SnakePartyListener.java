package com.snakeparty;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;

/** 蛇行派对事件：倒计时锁定、禁止跳跃、场地保护、等待室道具。 */
public final class SnakePartyListener implements Listener {

    private final SnakePartyManager manager;

    public SnakePartyListener(SnakePartyManager manager) {
        this.manager = manager;
    }

    /** 倒计时期间锁位置、正式游戏禁止跳跃。 */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        manager.handleMove(event);
    }

    /** 禁用原版疾跑（加速改用体力机制）。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onToggleSprint(PlayerToggleSprintEvent event) {
        if (event.isSprinting() && manager.isInGame(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && manager.isInGame(player)) {
            event.setCancelled(true);
        }
    }

}
