package com.animalslaughter;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

/** 动物屠宰事件：计分、逃跑、掉落物保护、玩家无伤、场地保护、等待室道具。 */
public final class AnimalSlaughterListener implements Listener {

    private final AnimalSlaughterManager manager;

    public AnimalSlaughterListener(AnimalSlaughterManager manager) {
        this.manager = manager;
    }

    /**
     * 玩家在场内不掉血；动物只吃玩家 1 点伤害（无武器加成），不挨其它伤害。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (manager.isInGame(player)) {
                event.setCancelled(true);
            }
            return;
        }
        Entity victim = event.getEntity();
        if (!manager.isOurMob(victim)) {
            return;
        }
        // 横扫之刃的溅射伤害直接丢掉，避免误伤旁边的干扰项
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player attacker) {
            if (manager.isPlaying(attacker) && manager.isRunning()) {
                event.setDamage(1.0);
                manager.onMobHit((LivingEntity) victim, attacker);
                return;
            }
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!manager.isOurMob(entity)) {
            return;
        }
        event.setDroppedExp(0);
        manager.onMobDeath(entity, entity.getKiller());
    }

    /** 动物的掉落物不能被捡，登记后到点自动清理。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(EntityDropItemEvent event) {
        if (manager.isOurMob(event.getEntity())) {
            manager.markDrop(event.getItemDrop());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        if (manager.isGameDrop(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null) {
            manager.handleMove(event.getPlayer(), event.getTo());
        }
    }

}
