package com.grouprace;

import com.hub.util.Chat;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;

/** 群友竞速赛事件入口。 */
public final class GroupRaceListener implements Listener {

    private final GroupRaceManager manager;

    public GroupRaceListener(GroupRaceManager manager) {
        this.manager = manager;
    }

    /** 记录跳跃 / 下蹲键，供飞猪每 tick 控制升降。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent event) {
        manager.handleInput(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        manager.handleSneak(event.getPlayer(), event.isSneaking());
    }

    /** 下蹲键用来下降，不能让它把玩家从猪上弄下来。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player
                && manager.shouldKeepMounted(player, event.getDismounted())) {
            event.setCancelled(true);
        }
    }

    /** 比赛中的玩家和飞猪都免伤。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && manager.isInGame(player)) {
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof Pig pig
                && (manager.isRacePig(pig) || manager.isLobbyPig(pig))) {
            event.setCancelled(true);
        }
    }

    /** 防止其他玩家摆弄/骑走比赛用的飞猪。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (manager.isRacePig(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

}
