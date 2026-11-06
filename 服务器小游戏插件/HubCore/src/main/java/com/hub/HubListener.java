package com.hub;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/** 大厅传送、菜单道具交互。菜单 GUI 的点击/编辑在 MenuEditor 里。 */
public class HubListener implements Listener {

    private final HubCorePlugin plugin;

    public HubListener(HubCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // 延迟一 tick 传送，避免世界未加载完
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) {
                plugin.getManager().teleportToLobby(p);
            }
        }, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getManager().clearGame(event.getPlayer().getUniqueId());
    }

    /**
     * 重生处理：只把"未在游戏中"（大厅）的玩家拉回主城重新给菜单道具；
     * 游戏内玩家重生由各游戏插件自己处理——这里不再无条件清状态，避免
     * 玩家实际还在游戏里、tab 却显示不在（如 GTB 取消死亡未生效时的重生兜底路径）。
     */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && plugin.getManager().isInLobby(p.getUniqueId())) {
                plugin.getManager().teleportToLobby(p);
            }
        }, 1L);
    }

    /** 右键菜单道具打开菜单；右键"回主城"道具退出所有游戏并回主城（任何游戏内都能用）。 */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player p = event.getPlayer();
        // 回主城道具：退出所有游戏并回主城（在所有游戏中都能使用）
        if (plugin.getManager().isHubItem(p.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            plugin.getManager().teleportToLobby(p);
            return;
        }
        // 菜单道具：优先认 PDC 标记（只有主城发出来的那个指南针会开菜单）；
        // 对没有标记的同材质物品（旧道具/别处拿到的指南针）只在大厅里才当菜单用，
        // 否则游戏里的指南针（例如杀手的追踪指南针）会误开主城菜单。
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!plugin.getManager().isMenuItem(hand)) {
            if (hand.getType() != plugin.getManager().getMenuItemMaterial()
                    || !plugin.getManager().isInLobby(p.getUniqueId())) {
                return;
            }
        }
        event.setCancelled(true);
        plugin.getManager().openMenu(p);
    }

    /** 大厅玩家不能捡东西（可通过 /hub lock 关闭）。 */
    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (plugin.getConfig().getBoolean("lock-inventory", true) && plugin.getManager().isInLobby(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * 丢东西：大厅整包锁定时照旧全禁（/hub lock 关闭后仍然禁止丢菜单道具）。
     * 菜单道具一旦被丢出去就会掉在主城地上被别人捡走，所以它单独硬禁，不受 lock-inventory 影响。
     */
    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.getManager().isMenuItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            return;
        }
        if (plugin.getConfig().getBoolean("lock-inventory", true)
                && plugin.getManager().isInLobby(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * 背包点击：菜单道具既不能被挪位置，也不能被别的物品顶掉/覆盖
     * （包括拖到别的格子、和光标物品交换、数字键替换、SHIFT+点击搬走）。
     * 同样不受 lock-inventory 影响（线上那份配置就是 false）。
     */
    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        // 选房 UI 的点击（R3）
        if (event.getView().getTopInventory().getHolder() instanceof com.hub.gui.RoomGui.Holder rh) {
            event.setCancelled(true);
            if (event.getClickedInventory() != null
                    && event.getClickedInventory().equals(event.getView().getTopInventory())) {
                com.hub.gui.RoomGui.onClick((Player) event.getWhoClicked(), rh, event.getRawSlot(),
                        event.isRightClick());
            }
            return;
        }
        // 菜单 GUI / 编辑 GUI 的点击由 MenuEditor 处理，这里不插手
        if (event.getView().getTopInventory().getHolder() instanceof com.hub.menu.MenuHolder) return;
        if (plugin.getManager().isMenuItem(event.getCurrentItem())
                || plugin.getManager().isMenuItem(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    /** F 键换手也会把菜单道具挪到副手，一并禁止。 */
    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (plugin.getManager().isMenuItem(event.getMainHandItem())
                || plugin.getManager().isMenuItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    /** 观战列表 GUI：点哪个图标就观战哪个游戏。 */
    @EventHandler
    public void onSpectateMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof HubManager.SpectateHolder holder)) {
            return;
        }
        event.setCancelled(true);
        HubManager.SpectateTarget target = holder.targetAt(event.getRawSlot());
        if (target == null) {
            return;
        }
        p.closeInventory();
        plugin.getManager().spectateGame(p, target);
    }

    /** 大厅玩家不能移动物品（菜单 GUI 的点击由 MenuEditor 处理；可通过 /hub lock 关闭）。 */
    @EventHandler
    public void onInvClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (!plugin.getManager().isInLobby(p.getUniqueId())) return;
        if (!plugin.getConfig().getBoolean("lock-inventory", true)) return;
        // 菜单 GUI（holder 标记）放行；player's own inventory 也放行（否则整个背包都被锁住）
        if (event.getView().getTopInventory().getHolder() instanceof com.hub.menu.MenuHolder) {
            return;
        }
        if (event.getClickedInventory() != null && event.getClickedInventory() == event.getView().getBottomInventory()) {
            return;
        }
        event.setCancelled(true);
    }

    /** 大厅不掉饥饿：只管大厅世界，各游戏世界由各游戏插件自己管。 */
    @EventHandler
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player p
                && p.getWorld().getName().equals(plugin.getConfig().getString("lobby.world", "world"))) {
            event.setCancelled(true);
        }
    }
}
