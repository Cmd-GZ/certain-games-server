package com.landclaim;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import com.hub.util.Chat;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * LandClaim 的对局会话（D22：会话层统一按场地键索引，单图游戏只有一个键）。
 */
final class LandClaimSession extends GameSession {

    private final LandClaimManager manager;

    LandClaimSession(LandClaimManager manager, String arenaName) {
        super("landclaim", arenaName);
        this.manager = manager;
        enterWaiting();
    }

    void start() {
        enterRunning();
    }

    @Override
    protected void onStart() {
        // 本轮不改玩法
    }

    @Override
    protected void onEnd(EndReason reason) {
        manager.doEndGame();
    }

    // ---------- I3 A 批：从 listener 搬过来的处理器 ----------

    @Override
    protected void onPlayerQuit(Player player) {
        manager.quit(player);
    }

    /** 掉落：只禁锄头 / 开始纸 / 回城床（原 listener 里那段 @EventHandler）。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        org.bukkit.inventory.ItemStack item = event.getItemDrop().getItemStack();
        if (manager.isHoe(item) || manager.isStartItem(item) || manager.isLeaveItem(item)) {
            event.setCancelled(true);
        }
    }

    @Override
    protected void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (manager.isStartItem(event.getItem())) {
            event.setCancelled(true);
            if (player.hasPermission("landclaim.admin")) {
                manager.useStartItem(player);
            }
            return;
        }
        if (manager.isLeaveItem(event.getItem())) {
            event.setCancelled(true);
            manager.useLeaveItem(player);
            return;
        }
        if (!manager.isHoe(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        if (player.hasPermission("landclaim.play")) {
            manager.claim(player, event.getClickedBlock());
        }
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------


    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof EntityDamageEvent e) {
        if (e.getEntity() instanceof Player player && manager.isPlaying(player)) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof BlockBreakEvent e) {
        if (manager.isPlaying(e.getPlayer())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof BlockPlaceEvent e) {
        if (manager.isPlaying(e.getPlayer())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player) || !manager.isPlaying(player)) {
            return;
        }
        if (isGameItem(e.getCurrentItem()) || isGameItem(e.getCursor())) {
            e.setCancelled(true);
        }
                return;
        }
    }

    // ---- 随处理器一起搬过来的私有 helper ----

    private boolean isGameItem(org.bukkit.inventory.ItemStack item) {
        return manager.isHoe(item) || manager.isStartItem(item) || manager.isLeaveItem(item);
    }
}
