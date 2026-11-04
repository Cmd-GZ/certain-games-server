package com.buildrun;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.List;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * 你建我跑的对局会话（D22：**每个场地一个会话**，多图游戏照 TntRun 的写法）。
 */
final class BuildRunSession extends GameSession {

    private final BuildRunManager manager;

    BuildRunSession(BuildRunManager manager, String arenaName) {
        super("buildrun", arenaName);
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
        manager.doEndGame(arenaName());
    }

    // ---------- I3 A 批：从 listener 搬过来的处理器 ----------

    @Override
    protected void onPlayerQuit(Player player) {
        manager.handleQuit(player);
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        String arenaName = manager.arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        BuildRunArena a = manager.getArena(arenaName);
        if (a == null) {
            return;
        }
        Location loc = a.getLobby() != null ? a.getLobby() : a.getSpawn();
        if (a.getPhase() == BuildRunArena.Phase.RUN) {
            BuildRunPlot plot = a.currentPlot();
            if (plot != null && plot.getSpawn() != null) {
                loc = plot.getSpawn();
            }
        }
        if (loc != null) {
            event.setRespawnLocation(loc);
        }
    }

    /** 掉落：自家道具一律不许丢；对局中其余物品也不许丢（原 listener 里那段 @EventHandler）。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        if (manager.isOurItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            return;
        }
        if (manager.forbidItemDrop(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------

    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof PlayerMoveEvent e) {
        manager.plugin().getManager().handleMove(e);
                return;
        }
        if (event instanceof PlayerJoinEvent e) {
        manager.plugin().getManager().handleJoin(e.getPlayer());
                return;
        }
        if (event instanceof EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && manager.plugin().getManager().isInSession(p.getUniqueId())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof BlockBreakEvent e) {
        if (manager.plugin().getManager().protectMarkBlock(e.getPlayer(), e.getBlock())) {
            e.setCancelled(true);
            return;
        }
        // 屏障：创造模式能秒挖穿，场地里的屏障一挖就成洞；自己当场放的那块仍可拆
        if (manager.plugin().getManager().protectBarrierBlock(e.getPlayer(), e.getBlock())) {
            e.setCancelled(true);
            return;
        }
        if (manager.plugin().getManager().protectFloorBlock(e.getPlayer(), e.getBlock())) {
            e.setCancelled(true);
            return;
        }
        if (manager.plugin().getManager().canBuildBlockNow(e.getPlayer(), e.getBlock())) {
            e.setCancelled(false);
            return;
        }
        if (!manager.plugin().getManager().canEditBlock(e.getPlayer(), e.getBlock())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof BlockPistonExtendEvent e) {
        if (movesFloor(e.getBlocks())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof BlockPistonRetractEvent e) {
        if (movesFloor(e.getBlocks())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof EntityExplodeEvent e) {
        e.blockList().removeIf(manager.plugin().getManager()::isProtectedFloor);
                return;
        }
        if (event instanceof BlockExplodeEvent e) {
        e.blockList().removeIf(manager.plugin().getManager()::isProtectedFloor);
                return;
        }
        if (event instanceof BlockBurnEvent e) {
        if (manager.plugin().getManager().isProtectedFloor(e.getBlock())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof EntityChangeBlockEvent e) {
        if (manager.plugin().getManager().isProtectedFloor(e.getBlock())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof BlockPlaceEvent e) {
        if (manager.plugin().getManager().forbidMarkerWool(e.getPlayer(), e.getBlockPlaced())) {
            e.setCancelled(true);
            return;
        }
        // 屏障：建造阶段自己场地里也不许放（用户口径：「自己也不能放屏障」）
        if (manager.plugin().getManager().forbidBarrierPlace(e.getPlayer(), e.getBlockPlaced())) {
            e.setCancelled(true);
            return;
        }
        if (!manager.plugin().getManager().canEditBlock(e.getPlayer(), e.getBlock())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getHand() != null && e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (manager.plugin().getManager().isInSetup(p.getUniqueId())) {
            if (manager.plugin().getManager().handleSetupTool(p, e.getAction(), e.getClickedBlock())) {
                e.setCancelled(true);
                return;
            }
        }
        Action action = e.getAction();
        // 踩压力板 / 踩作物这些"脚上的交互"也一起吞掉（对局中）
        if (action == Action.PHYSICAL) {
            if (manager.plugin().getManager().forbidInteract(p)) {
                e.setCancelled(true);
            }
            return;
        }
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK
                && action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = e.getItem();
        if (item != null && manager.plugin().getManager().handleItemUse(p, action, e.getClickedBlock(), item)) {
            e.setCancelled(true);
            return;
        }
        // 对局中右键方块：只留"放方块"，按按钮/开门/开箱子一律吞掉（不能整套 cancel，会把放方块一起砍掉）
        if (action == Action.RIGHT_CLICK_BLOCK
                && manager.plugin().getManager().forbidWorldInteract(p, item, e.getClickedBlock())) {
            e.setUseInteractedBlock(Event.Result.DENY);
        }
                return;
        }
        if (event instanceof PlayerInteractEntityEvent e) {
        if (manager.plugin().getManager().forbidInteract(e.getPlayer())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        Object holder = top.getHolder();
        boolean kit = holder instanceof BuildRunManager.KitHolder;
        boolean wool = holder instanceof BuildRunManager.WoolHolder;
        if (!kit && !wool) {
            return;
        }
        e.setCancelled(true);
        if (e.getWhoClicked() instanceof Player p) {
            int slot = e.getRawSlot();
            if (slot >= 0 && slot < top.getSize()) {
                if (wool) {
                    if (slot == BuildRunManager.WOOL_BACK_SLOT) {
                        manager.plugin().getManager().backToKit(p);
                    }
                } else {
                    manager.plugin().getManager().handleKitClick(p, slot);
                }
            }
        }
                return;
        }
        if (event instanceof InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof BuildRunManager.KitHolder
                || top.getHolder() instanceof BuildRunManager.WoolHolder) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) {
            return;
        }
        Inventory top = e.getView().getTopInventory();
        Object holder = top.getHolder();
        if (holder instanceof BuildRunManager.KitHolder || holder instanceof BuildRunManager.WoolHolder) {
            return;
        }
        if (manager.plugin().getManager().forbidContainerOpen(p)) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof ItemSpawnEvent e) {
        if (manager.plugin().getManager().isInsideActivePlot(e.getLocation())) {
            e.setCancelled(true);
        }
                return;
        }
    }

    // ---- 原 Listener 的私有 helper ----

    private boolean movesFloor(List<Block> blocks) {
        if (blocks == null) {
            return false;
        }
        for (Block b : blocks) {
            if (manager.plugin().getManager().isProtectedFloor(b)) {
                return true;
            }
        }
        return false;
    }
}
