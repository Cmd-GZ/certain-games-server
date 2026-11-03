package com.buildrun;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** 你建我跑的事件入口：建场工具、道具菜单、场地保护、跑步判定、免伤免饥饿。 */
public class BuildRunListener implements Listener {

    private final BuildRunPlugin plugin;

    public BuildRunListener(BuildRunPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        plugin.getManager().handleMove(event);
    }

    /** 有人进来：正在"隐藏其他玩家"的跑者也要看不见他。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getManager().handleJoin(event.getPlayer());
    }

    /** 重生位置：在场里就回场地等待点 / 当前旁观点。 */
    /** 场里的人不吃任何伤害（跑酷只靠碰到地板/跑出场地判失败，不靠打死）。 */
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && plugin.getManager().isInSession(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** 场里的人不掉饥饿（全局约定）。 */
    /**
     * 建造阶段只能动自己场地里的方块；对局中一律不许动方块。
     * <p>
     * 必须挂在 HIGHEST 上并显式"取消取消"：GuessTheBuild 在 NORMAL 上对**任何非 OP 玩家**
     * 无条件 setCancelled(true)（不分世界、不分场地），而同优先级下后执行的那个赢、顺序又跟
     * 服务器平台有关，于是非 OP 的建筑者在自己场地里也挖不动方块（OP 反而正常）。
     * 这里只对「建造阶段 + 自己那块复制场地内的方块」放行，其它地方仍由猜猜乐兜底保护。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (plugin.getManager().protectMarkBlock(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        // 屏障：创造模式能秒挖穿，场地里的屏障一挖就成洞；自己当场放的那块仍可拆
        if (plugin.getManager().protectBarrierBlock(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        if (plugin.getManager().protectFloorBlock(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        if (plugin.getManager().canBuildBlockNow(event.getPlayer(), event.getBlock())) {
            event.setCancelled(false);
            return;
        }
        if (!plugin.getManager().canEditBlock(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * 地板是"踩到就淘汰"的那层，活塞也别想把它推走 / 拽走（方块被推走 = 原地出洞，不算拆但效果一样）。
     * 只取消"动了地板"这一下，别的场地/别的方块不碰。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (movesFloor(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (movesFloor(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    /** 爆炸不整场取消（别的插件的地盘不碰）：只把本场地的地板从被炸列表里摘掉。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(plugin.getManager()::isProtectedFloor);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(plugin.getManager()::isProtectedFloor);
    }

    /** 木头地板也别被火烧掉。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBurn(BlockBurnEvent event) {
        if (plugin.getManager().isProtectedFloor(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** 末影人抱走方块 / 落沙落地这类"不是玩家、也不走爆炸"的方块变化：地板那层不许变。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (plugin.getManager().isProtectedFloor(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    private boolean movesFloor(List<Block> blocks) {
        if (blocks == null) {
            return false;
        }
        for (Block b : blocks) {
            if (plugin.getManager().isProtectedFloor(b)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.getManager().forbidMarkerWool(event.getPlayer(), event.getBlockPlaced())) {
            event.setCancelled(true);
            return;
        }
        // 屏障：建造阶段自己场地里也不许放（用户口径：「自己也不能放屏障」）
        if (plugin.getManager().forbidBarrierPlace(event.getPlayer(), event.getBlockPlaced())) {
            event.setCancelled(true);
            return;
        }
        if (!plugin.getManager().canEditBlock(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** 建场工具 + 道具（菜单/起点/终点/退出床/开始纸）+ 对局中的"任何交互都不给"。 */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (plugin.getManager().isInSetup(p.getUniqueId())) {
            if (plugin.getManager().handleSetupTool(p, event.getAction(), event.getClickedBlock())) {
                event.setCancelled(true);
                return;
            }
        }
        Action action = event.getAction();
        // 踩压力板 / 踩作物这些"脚上的交互"也一起吞掉（对局中）
        if (action == Action.PHYSICAL) {
            if (plugin.getManager().forbidInteract(p)) {
                event.setCancelled(true);
            }
            return;
        }
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK
                && action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item != null && plugin.getManager().handleItemUse(p, action, event.getClickedBlock(), item)) {
            event.setCancelled(true);
            return;
        }
        // 对局中右键方块：只留"放方块"，按按钮/开门/开箱子一律吞掉（不能整套 cancel，会把放方块一起砍掉）
        if (action == Action.RIGHT_CLICK_BLOCK
                && plugin.getManager().forbidWorldInteract(p, item, event.getClickedBlock())) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    /**
     * 对局中右键实体也不许（盔甲架 / 物品展示框 / 村民 / 船 / 马……）：那同样能藏东西、能翻出东西。
     * 注册的是 {@link PlayerInteractEntityEvent}（不是 {@link PlayerInteractAtEntityEvent}）：
     * 后者是前者的子类，对盔甲架这类"点到实体身上"的交互同样会进到这里。
     */
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (plugin.getManager().forbidInteract(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** 道具菜单 / 羊毛效果图鉴子菜单：里面的展示物品一律拿不走。 */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        Object holder = top.getHolder();
        boolean kit = holder instanceof BuildRunManager.KitHolder;
        boolean wool = holder instanceof BuildRunManager.WoolHolder;
        if (!kit && !wool) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player p) {
            int slot = event.getRawSlot();
            if (slot >= 0 && slot < top.getSize()) {
                if (wool) {
                    if (slot == BuildRunManager.WOOL_BACK_SLOT) {
                        plugin.getManager().backToKit(p);
                    }
                } else {
                    plugin.getManager().handleKitClick(p, slot);
                }
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof BuildRunManager.KitHolder
                || top.getHolder() instanceof BuildRunManager.WoolHolder) {
            event.setCancelled(true);
        }
    }

    /** 本插件发的道具不许丢（防止把菜单/起点工具丢在地上或转给别人）；对局中谁的物品都不许丢。 */
    /**
     * 对局中（建造 / 跑步）不许打开任何容器：箱子 / 熔炉 / 漏斗 / 潜影盒 / 末影箱……
     * <p>
     * 目的：防止先建一个箱子把方块、水桶、鞘翅之类塞进去，轮到自己跑的时候再摸出来作弊。
     * 本插件自己的道具菜单（{@link BuildRunManager.KitHolder} / {@link BuildRunManager.WoolHolder}）放行。
     */
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player p)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Object holder = top.getHolder();
        if (holder instanceof BuildRunManager.KitHolder || holder instanceof BuildRunManager.WoolHolder) {
            return;
        }
        if (plugin.getManager().forbidContainerOpen(p)) {
            event.setCancelled(true);
        }
    }

    /**
     * 场地里不许出现掉落物：一落地就删（爆炸/漏斗/别的插件弄出来的也算），
     * 防止有人把东西丢在自己场地上、开跑时顺手捡起来。真正的兜底清扫在 1s ticker 里。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (plugin.getManager().isInsideActivePlot(event.getLocation())) {
            event.setCancelled(true);
        }
    }
}
