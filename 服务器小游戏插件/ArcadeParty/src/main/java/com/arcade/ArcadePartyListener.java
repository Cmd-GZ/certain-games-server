package com.arcade;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** 街机派对事件：等待室道具、设置菜单、等待/颁奖阶段的保护。 */
public final class ArcadePartyListener implements Listener {

    private final ArcadePartyManager manager;

    public ArcadePartyListener(ArcadePartyManager manager) {
        this.manager = manager;
    }

    /** 只有不在小游戏里（等待/过渡/颁奖）时才接管玩家，避免影响子游戏。 */
    private boolean inPartyLobby(Player player) {
        return manager.isInParty(player) && manager.state() != ArcadePartyManager.State.ROUND;
    }

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
            if (manager.isAdmin(player) && manager.state() == ArcadePartyManager.State.WAITING) {
                manager.startParty(true);
            }
            return;
        }
        if (manager.isMenuOpener(item) && manager.isAdmin(player) && manager.isInParty(player)) {
            event.setCancelled(true);
            new ArcadeMenu(manager.plugin(), manager).open(player);
        }
    }

    /** 过渡阶段可配置冻结移动（仍可转头）；颁奖台上未揭晓的前三名也不能动。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        if (manager.state() == ArcadePartyManager.State.PODIUM
                && manager.isPodiumFrozen(event.getPlayer())) {
            Location from = event.getFrom();
            Location to = event.getTo();
            event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(),
                    to.getYaw(), to.getPitch()));
            return;
        }
        if (manager.state() != ArcadePartyManager.State.TRANSITION || !manager.transitionFreeze()) {
            return;
        }
        if (!manager.isInParty(event.getPlayer()) || event.getTo() == null) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(),
                to.getYaw(), to.getPitch()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof ArcadeMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            menu.handleClick(player, event.getRawSlot(),
                    event.isRightClick(), event.isShiftClick());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && inPartyLobby(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!inPartyLobby(player)) {
            return;
        }
        event.setCancelled(true);
        player.setHealth(20.0);
        player.setFoodLevel(20);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && inPartyLobby(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (inPartyLobby(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!inPartyLobby(player)) {
            return;
        }
        Location target = manager.getLobby();
        if (target != null) {
            event.setRespawnLocation(target);
        }
    }
}
