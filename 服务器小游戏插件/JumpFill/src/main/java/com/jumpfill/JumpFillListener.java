package com.jumpfill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class JumpFillListener implements Listener {

    private final JumpFillPlugin plugin;
    private final JumpFillManager manager;

    public JumpFillListener(JumpFillPlugin plugin, JumpFillManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!manager.isRunning()) {
            return;
        }
        Location to = event.getTo();
        if (to == null || !manager.isPlaying(event.getPlayer()) || manager.isProcessing(event.getPlayer())) {
            return;
        }
        manager.onMove(event.getPlayer(), event.getFrom(), to);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && manager.isPlaying(player)) {
            event.setCancelled(true);
        }
    }

    /** 被填掉的格子不能被旁边的水重新灌满。 */
    @EventHandler(ignoreCancelled = true)
    public void onWaterFlow(BlockFromToEvent event) {
        if (event.getBlock().getType() != Material.WATER) {
            return;
        }
        if (manager.arena().affectsWaterFlow(event.getBlock().getLocation(), event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !manager.isStartItem(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        if (event.getPlayer().hasPermission("jumpfill.admin")) {
            manager.useStartItem(event.getPlayer());
        }
    }

}
