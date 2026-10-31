package com.oneshot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

/** One Shot 事件：一箭秒杀、击杀判定、摔死、道具与场地保护。 */
public final class OneShotListener implements Listener {

    private final OneShotPlugin plugin;
    private final OneShotManager manager;

    public OneShotListener(OneShotPlugin plugin, OneShotManager manager) {
        this.plugin = plugin;
        this.manager = manager;
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
            manager.handleStartPaper(player);
        }
    }

    /** 玩家之间的伤害：弓箭直接秒杀，木剑走原版伤害；其它来源一律取消。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = null;
        boolean arrow = false;
        Entity damager = event.getDamager();
        if (damager instanceof Player player) {
            attacker = player;
        } else if (damager instanceof Arrow projectile && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter;
            arrow = true;
        }
        if (!manager.isInGame(victim)) {
            // 对局内玩家不能打大厅/观战玩家
            if (attacker != null && manager.isInGame(attacker)) {
                event.setCancelled(true);
            }
            return;
        }
        if (!manager.isPlaying(victim) || attacker == null || !manager.isPlaying(attacker)
                || attacker.equals(victim)) {
            event.setCancelled(true);
            return;
        }
        // 天坑（SkyPit）会在 LOWEST 无条件取消“非天坑玩家”的 PvP 伤害，
        // 这里必须显式解除取消，否则本局玩家永远打不出伤害。
        event.setCancelled(false);
        manager.recordHit(victim, attacker);
        if (arrow) {
            // 一箭秒杀；保留击杀者信息，让死亡结算能补箭
            event.setDamage(1000.0);
        }
    }

    /**
     * 最后一道保险：其它插件在 HIGHEST 取消本局玩家互伤时，这里在 MONITOR 再放行一次。
     * （MONITOR 通常不该改事件，但为了不被别的插件吃掉 PvP 伤害，只能在这里兜底。）
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDamageMonitor(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = asPlayerDamager(event);
        if (attacker == null || attacker.equals(victim)
                || !manager.isPlaying(victim) || !manager.isPlaying(attacker)) {
            return;
        }
        if (event.isCancelled()) {
            event.setCancelled(false);
        }
    }

    private Player asPlayerDamager(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Arrow arrow && arrow.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /** 非玩家来源的伤害（摔落、火焰、虚空等）全部取消；摔台由管理器判定。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEnvironmentDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }
        if (event.getEntity() instanceof Player player && manager.isInGame(player)) {
            event.setCancelled(true);
        }
    }

    /** 关闭自然回血，否则木剑砍的伤害会很快回满。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRegen(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player && manager.isInGame(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!manager.isInGame(player)) {
            return;
        }
        event.deathMessage(null);
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        manager.handleDeath(player);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInGame(player)) {
            return;
        }
        Location spot = manager.arena().spectatorSpot();
        if (spot != null) {
            event.setRespawnLocation(spot);
        }
        Bukkit.getScheduler().runTask(plugin, () -> manager.onRespawned(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (manager.isInGame(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (manager.isInGame(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && manager.isInGame(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (manager.isInGame(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** 对局中禁止捡箭：箭只有开局那一支，靠击杀补充。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickupItem(PlayerAttemptPickupItemEvent event) {
        if (manager.isInGame(event.getPlayer())
                && event.getItem().getItemStack().getType() == manager.arrowMaterial()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickupArrow(PlayerPickupArrowEvent event) {
        if (manager.isInGame(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null) {
            manager.handleMove(event.getPlayer(), event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.handleQuit(event.getPlayer());
    }
}
