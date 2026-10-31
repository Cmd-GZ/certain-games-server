package com.tntbattle;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TNTBattleListener implements Listener {

    private final TNTBattleManager manager;
    /** 上一次使用道具的 tick，用来给小游戏道具的右键去重。 */
    private final Map<UUID, Integer> lastUseTick = new HashMap<>();

    public TNTBattleListener(TNTBattleManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        manager.onWorldLoad(event.getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        // 道具只在主手生效：副手使用会导致 TNT/特殊道具不消耗，直接忽略
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        // 左键也会触发 PlayerInteractEvent，道具只认右键
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        if (!manager.isPlaying(player)) {
            return;
        }
        if (!manager.isSpecialItem(item) && !manager.isTntItem(item)
                && !manager.isStartItem(item) && !manager.isReturnItem(item)
                && !manager.isMusicItem(item)) {
            return;
        }
        // 右键方块时服务端可能在同一 tick 再补发一次「右键空气」，用 tick 去重避免一次点出两个
        int tick = player.getTicksLived();
        Integer last = lastUseTick.put(player.getUniqueId(), tick);
        if (last != null && last == tick) {
            return;
        }
        event.setCancelled(true);
        if (manager.isSpecialItem(item)) {
            manager.launchSpecial(player, item);
            return;
        }
        if (manager.isTntItem(item)) {
            manager.launchTnt(player);
            return;
        }
        if (manager.isStartItem(item)) {
            if (player.hasPermission("tntbattle.admin")) {
                manager.useStartItem(player);
            }
            return;
        }
        if (manager.isMusicItem(item)) {
            manager.toggleMusic(player);
            return;
        }
        manager.useReturnItem(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt) || !manager.ownsTnt(tnt)) {
            return;
        }
        manager.onTntExplode(tnt, event.getLocation());
        event.setYield(0f); // 不产生方块掉落物
        // 只允许破坏划定区域内的方块，区域外（其他小游戏场地）完全不受伤
        event.blockList().removeIf(block -> !manager.insideArena(block));
        for (org.bukkit.block.Block block : event.blockList()) {
            manager.rememberBlock(block);
        }
        sweepWool(event.getLocation());
    }

    /** 烈焰弹用 createExplosion(无实体源) 引爆，Paper 会走 BlockExplodeEvent。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!manager.isOwnedExplosion(event.getBlock())) {
            return;
        }
        event.setYield(0f);
        event.blockList().removeIf(block -> !manager.insideArena(block));
        for (org.bukkit.block.Block block : event.blockList()) {
            manager.rememberBlock(block);
        }
        sweepWool(event.getBlock().getLocation());
    }

    /** 保险：爆炸后清掉附近掉落的羊毛（爆炸本身不产出掉落物）。 */
    private void sweepWool(Location center) {
        double radius = manager.tntDamageRadius();
        Bukkit.getScheduler().runTask(manager.plugin(), () -> {
            for (org.bukkit.entity.Entity entity : center.getWorld()
                    .getNearbyEntities(center, radius, radius, radius)) {
                if (entity instanceof org.bukkit.entity.Item item
                        && org.bukkit.Tag.WOOL.isTagged(item.getItemStack().getType())) {
                    entity.remove();
                }
            }
        });
    }

    /** 烈焰弹：火球命中方块/实体后由插件引爆，原版碰撞效果全部取消。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof SmallFireball fireball && manager.ownsFlame(fireball)) {
            event.setCancelled(true);
            Location hit = fireball.getLocation().clone();
            // 先让 manager 处理（要读 shooter），再移除实体
            manager.onFlameHit(fireball, hit);
            fireball.remove();
            return;
        }
        if (event.getEntity() instanceof Egg egg && manager.ownsBridgeEgg(egg)) {
            event.setCancelled(true);
            egg.remove();
            manager.stopBridgeEgg(egg);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFireballDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof SmallFireball fireball && manager.ownsFlame(fireball)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!manager.isPlaying(player)) {
            return;
        }
        if (!manager.isAlive(player)) {
            event.setCancelled(true);
            return;
        }
        ItemStack item = event.getItemInHand();
        if (manager.isTntItem(item) || manager.isSpecialItem(item)) {
            event.setCancelled(true);
            return;
        }
        TNTBattleArena.Team team = manager.teamOf(player);
        if (team == null || !org.bukkit.Tag.WOOL.isTagged(item.getType())
                || !manager.insideArena(event.getBlock())
                || !manager.withinBuildHeight(event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        manager.rememberBlockState(event.getBlockReplacedState());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!manager.isPlaying(player)) {
            return;
        }
        org.bukkit.block.Block block = event.getBlock();
        // 只允许破坏场地内的羊毛和玻璃类方块，其他方块仍然保护
        boolean wool = manager.isWool(block);
        boolean glass = manager.isGlass(block);
        if ((!wool && !glass) || !manager.insideArena(block)) {
            event.setCancelled(true);
            return;
        }
        manager.rememberBlock(block);
        // 羊毛正常掉落，玻璃类破坏不掉落
        event.setDropItems(wool);
        // 建筑猜猜乐对所有非 OP 玩家的方块破坏做了全局拦截，这里对场地内的羊毛/玻璃放行
        event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!manager.isPlaying(player) || manager.isEliminated(player)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(false);
        manager.eliminate(player, "死亡");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isPlaying(player) || !manager.isEliminated(player) || manager.isRestoring()) {
            return;
        }
        Location spectator = manager.arena().spectatorLocation();
        if (spectator != null) {
            event.setRespawnLocation(spectator);
        }
        Bukkit.getScheduler().runTask(manager.plugin(), () -> {
            if (player.isOnline()) {
                player.setGameMode(org.bukkit.GameMode.SPECTATOR);
                player.setAllowFlight(true);
                player.setFlying(true);
                player.setGlowing(false);
                manager.giveNightVision(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (!manager.isRunning() || event.getTo() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!manager.isAlive(player)) {
            return;
        }
        if (event.getTo().getY() < manager.arena().voidY()) {
            manager.eliminate(player, "掉入虚空");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!manager.isPlaying(player)) {
            return;
        }
        // 烈焰弹引爆瞬间的原版爆炸伤害：没有实体源 → Paper 归成 CUSTOM，只能靠时间窗口拦掉
        if (manager.isDetonatingSpecial()) {
            event.setCancelled(true);
            logDamage(player, event);
            return;
        }
        // 等待中或旁观者免伤
        if (!manager.isRunning() || !manager.isAlive(player)) {
            event.setCancelled(true);
            logDamage(player, event);
            return;
        }
        // 原版爆炸伤害事件不再使用；爆炸伤害由 manager.damagePlayersAround 主动结算
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            event.setCancelled(true);
        } else if (cause == EntityDamageEvent.DamageCause.FALL) {
            if (manager.consumeFallImmunity(player)) {
                // 自己用烈焰弹弹上天的这一次落地不算摔伤
                event.setCancelled(true);
            } else {
                double multiplier = manager.fallDamageMultiplier();
                if (multiplier <= 0) {
                    event.setCancelled(true);
                } else {
                    event.setDamage(event.getDamage() * multiplier);
                }
            }
        }
        logDamage(player, event);
    }

    /** 排查伤害异常：config 的 debug-damage 打开后，把落到参赛玩家身上的每个伤害事件打出来。 */
    private void logDamage(Player player, EntityDamageEvent event) {
        if (!manager.debugDamage()) {
            return;
        }
        manager.plugin().getLogger().info("[伤害调试] " + player.getName()
                + " 原因=" + event.getCause()
                + " 基础=" + event.getDamage()
                + " 最终=" + event.getFinalDamage()
                + " 取消=" + event.isCancelled()
                + " 血量=" + player.getHealth());
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent event) {
        // 等待室不饿；对局中允许正常消耗饥饿值，这样牛排可以吃
        if (event.getEntity() instanceof Player player
                && manager.isPlaying(player) && !manager.isRunning()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (manager.isTntItem(event.getItemDrop().getItemStack())
                || manager.isStartItem(event.getItemDrop().getItemStack())
                || manager.isReturnItem(event.getItemDrop().getItemStack())
                || manager.isMusicItem(event.getItemDrop().getItemStack())
                || manager.isSpecialItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** 只有 TNT 弹药和队伍羊毛锁位置，禁止 F 换手；其它道具可以自由换到副手使用。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        ItemStack main = event.getMainHandItem();
        ItemStack off = event.getOffHandItem();
        if (isFixedItem(main) || isFixedItem(off)) {
            event.setCancelled(true);
        }
    }

    /**
     * 只有 TNT 弹药和队伍方块（羊毛）锁位置；牛排、特殊道具、等待室道具都可以自己拖到别的格子。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !manager.isPlaying(player)) {
            return;
        }
        if (isFixedItem(event.getCurrentItem()) || isFixedItem(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    private boolean isFixedItem(ItemStack item) {
        if (item == null) {
            return false;
        }
        if (manager.isTntItem(item)) {
            return true;
        }
        return item.getType().name().endsWith("_WOOL");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastUseTick.remove(event.getPlayer().getUniqueId());
        manager.quit(event.getPlayer());
    }
}
