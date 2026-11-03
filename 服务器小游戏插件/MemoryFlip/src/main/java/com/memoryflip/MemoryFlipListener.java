package com.memoryflip;

import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 记忆翻牌事件：翻牌点击、场地保护、等待室道具。 */
public final class MemoryFlipListener implements Listener {

    private final MemoryFlipManager manager;
    private final Map<UUID, Integer> lastClickTick = new HashMap<>();

    public MemoryFlipListener(MemoryFlipManager manager) {
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

    /** 右键点展示框：翻牌。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractFrame(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!manager.isPlaying(player)) {
            return;
        }
        int tick = player.getTicksLived();
        Integer last = lastClickTick.put(player.getUniqueId(), tick);
        if (last != null && last == tick) {
            return;
        }
        manager.handleFrameClick(player, frame);
    }

    /** 左键点展示框：翻牌（同时取消对展示框的伤害/破坏）。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageFrame(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player player) || !manager.isPlaying(player)) {
            return;
        }
        int tick = player.getTicksLived();
        Integer last = lastClickTick.put(player.getUniqueId(), tick);
        if (last != null && last == tick) {
            return;
        }
        manager.handleFrameClick(player, frame);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && manager.isInGame(player)) {
            event.setCancelled(true);
        }
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastClickTick.remove(event.getPlayer().getUniqueId());
        manager.handleQuit(event.getPlayer());
    }

}
