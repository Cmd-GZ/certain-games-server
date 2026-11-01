package com.arcade;

import com.hub.api.HubAPI;
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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

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
        // 回主城道具：派对进行中也要退出整个派对，而不是只退出当前小游戏
        if (HubAPI.isHubItem(item)) {
            manager.handleHubCommand(player);
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

    /** /hub、/lobby、/spawn（无参数）= 回主城：派对进行中直接退出整个派对。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().trim();
        if (!message.startsWith("/")) {
            return;
        }
        String[] parts = message.substring(1).split("\\s+");
        if (parts.length != 1) {
            return;
        }
        String command = parts[0].toLowerCase(Locale.ROOT);
        if (command.equals("hub") || command.equals("lobby") || command.equals("spawn")) {
            manager.handleHubCommand(event.getPlayer());
        }
    }

    /**
     * 派对进行中拦住“小游戏结算时的回城传送”：
     * 有些小游戏上报结果后还会延迟 teleportToLobby，而回城会触发所有游戏的 lobby 回调，
     * 把刚加入下一局等待区的玩家踢掉，导致派对跳过下一局。
     * 玩家主动 /hub 会先被 handleHubCommand 移出派对，不受这条影响。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (manager.state() == ArcadePartyManager.State.WAITING) {
            return;
        }
        if (!manager.isInParty(event.getPlayer())) {
            return;
        }
        Location to = event.getTo();
        Location hub = HubAPI.getLobby();
        if (to == null || hub == null || to.getWorld() == null || hub.getWorld() == null) {
            return;
        }
        if (!to.getWorld().equals(hub.getWorld())) {
            return;
        }
        Location partyLobby = manager.getLobby();
        if (partyLobby != null && partyLobby.getWorld() != null
                && to.getWorld().equals(partyLobby.getWorld())
                && to.distanceSquared(partyLobby) <= 9.0) {
            return; // 派对自己的等待区/旁观点，放行
        }
        if (to.distanceSquared(hub) <= 9.0) {
            event.setCancelled(true);
        }
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
