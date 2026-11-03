package com.grouprace;

import com.hub.util.Chat;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
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

    /** 等待室：床返回大厅，纸（仅管理员）开始游戏。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        Player player = event.getPlayer();
        if (manager.isReturnItem(item)) {
            event.setCancelled(true);
            manager.leave(player, false);
            return;
        }
        if (manager.isStartItem(item)) {
            event.setCancelled(true);
            if (!manager.isAdmin(player)) {
                Chat.send(player, "&c只有管理员可以开始游戏。");
            } else if (manager.isWaiting(player)) {
                manager.startGame(true);
            } else {
                Chat.send(player, "&e你不在等待队列中。");
            }
        }
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (manager.isInGame(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (manager.isInGame(event.getPlayer())) {
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
