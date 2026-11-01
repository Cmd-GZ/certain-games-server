package com.arrowrain;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

/** 万箭齐发事件：等待室道具、箭雨命中、PvP 放行（优先级高于天坑）、死亡与退出。 */
public final class ArrowRainListener implements Listener {

    private final ArrowRainPlugin plugin;
    private final ArrowRainManager manager;

    public ArrowRainListener(ArrowRainPlugin plugin, ArrowRainManager manager) {
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
            if (player.hasPermission("arrowrain.admin")) {
                manager.startGame(true);
            }
        }
    }

    /**
     * 伤害总控：
     * - 箭雨伤害由 ProjectileHit 处理，这里一律取消，避免原版伤害把人直接打死；
     * - 只有「第 pvp-start-round 回合之后、双方都在场」的玩家互殴才放行；
     * - 用 MONITOR 放行，优先级高于天坑（SkyPit 在 LOWEST 全局取消、HIGHEST 只放行自己场内）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!manager.isInGame(victim)) {
            if (event instanceof EntityDamageByEntityEvent byEntity
                    && byEntity.getDamager() instanceof Player attacker
                    && manager.isInGame(attacker)) {
                event.setCancelled(true);
            }
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Arrow arrow && manager.isRainArrow(arrow)) {
                event.setCancelled(true);
                return;
            }
            if (damager instanceof Player attacker && manager.isPlaying(attacker)) {
                if (manager.isPlaying(victim) && manager.pvpEnabled()) {
                    event.setCancelled(false);
                } else {
                    event.setCancelled(true);
                }
                return;
            }
        }
        event.setCancelled(true);
    }

    /** 箭雨命中：在掩体外就淘汰；掩体下则安全。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow) || !manager.isRainArrow(arrow)) {
            return;
        }
        Entity hit = event.getHitEntity();
        if (hit instanceof Player player) {
            manager.hitByArrow(player);
            manager.forgetArrow(arrow);
            arrow.remove();
            return;
        }
        // 命中方块：让箭插在平台上，直到本轮箭雨结束由 removeArrows 统一清理（不可捡起）
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!manager.isPlaying(player)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        manager.eliminate(player, "被其他玩家击杀");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInGame(player)) {
            return;
        }
        Location lobby = manager.arena().lobby();
        if (lobby != null) {
            event.setRespawnLocation(lobby);
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && manager.isInGame(player)) {
                player.setGameMode(GameMode.SPECTATOR);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (manager.isInGame(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (manager.isInGame(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (manager.isInGame(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && manager.isInGame(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.handleQuit(event.getPlayer());
    }
}
