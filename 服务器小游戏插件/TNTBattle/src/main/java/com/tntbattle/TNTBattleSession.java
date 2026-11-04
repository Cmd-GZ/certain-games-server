package com.tntbattle;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Bukkit;
import org.bukkit.event.world.WorldLoadEvent;
import java.util.UUID;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.SmallFireball;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.Map;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.InventoryClickEvent;
import java.util.HashMap;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.entity.Egg;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/** TNT 大战的会话（D22：会话层按场地键索引；本游戏目前是单场地）。 */
final class TNTBattleSession extends GameSession {

    private final TNTBattleManager manager;

    TNTBattleSession(TNTBattleManager manager, String arenaName) {
        super("tntbattle", arenaName);
        this.manager = manager;
        // 框架默认"局内不掉饥饿"（与其它 24 个游戏的处理器一致），但**本局是例外**：
        // TNT 大战在对局中故意允许掉饥饿（玩家吃牛排回血是玩法的一部分），
        // 所以这里显式关掉这条环境规则，仍旧由会话里的饥食物钩子按
        // "isPlaying && !isRunning" 自己分流。
        rules().noHunger(false);
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
    // 注意：onHunger（等待室不饿/局内允许掉饥饿）与 onQuit（要清 listener 里的 lastUseTick 冷却表）
    // 仍留在 listener 里 —— 前者是本局特有的语义，后者要碰游戏自己的局部状态。

    @Override
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!manager.isPlaying(player) || manager.isEliminated(player)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(false);
        manager.eliminate(player, "死亡");
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isPlaying(player) || !manager.isEliminated(player) || manager.isRestoring()) {
            return;
        }
        Location spectator = manager.arena().spectatorLocation();
        if (spectator != null) {
            event.setRespawnLocation(spectator);
        }
        manager.spectateNextTick(player);
    }

    /** 掉落：炸弹/开始纸/回城床/音乐盒/特殊道具一律不许丢（原 listener 里那段 @EventHandler）。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        if (manager.isTntItem(event.getItemDrop().getItemStack())
                || manager.isStartItem(event.getItemDrop().getItemStack())
                || manager.isReturnItem(event.getItemDrop().getItemStack())
                || manager.isMusicItem(event.getItemDrop().getItemStack())
                || manager.isSpecialItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------

    private final Map<UUID, Integer> lastUseTick = new HashMap<>();

    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof WorldLoadEvent e) {
        manager.onWorldLoad(e.getWorld());
                return;
        }
        if (event instanceof PlayerInteractEvent e) {
        // 道具只在主手生效：副手使用会导致 TNT/特殊道具不消耗，直接忽略
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        // 左键也会触发 PlayerInteractEvent，道具只认右键
        if (e.getAction() != Action.RIGHT_CLICK_AIR
                && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = e.getItem();
        Player player = e.getPlayer();
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
        e.setCancelled(true);
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
                return;
        }
        if (event instanceof EntityExplodeEvent e) {
        if (!(e.getEntity() instanceof TNTPrimed tnt) || !manager.ownsTnt(tnt)) {
            return;
        }
        manager.onTntExplode(tnt, e.getLocation());
        e.setYield(0f); // 不产生方块掉落物
        // 只允许破坏划定区域内的方块，区域外（其他小游戏场地）完全不受伤
        e.blockList().removeIf(block -> !manager.insideArena(block));
        for (org.bukkit.block.Block block : e.blockList()) {
            manager.rememberBlock(block);
        }
        sweepWool(e.getLocation());
                return;
        }
        if (event instanceof BlockExplodeEvent e) {
        if (!manager.isOwnedExplosion(e.getBlock())) {
            return;
        }
        e.setYield(0f);
        e.blockList().removeIf(block -> !manager.insideArena(block));
        for (org.bukkit.block.Block block : e.blockList()) {
            manager.rememberBlock(block);
        }
        sweepWool(e.getBlock().getLocation());
                return;
        }
        if (event instanceof ProjectileHitEvent e) {
        if (e.getEntity() instanceof SmallFireball fireball && manager.ownsFlame(fireball)) {
            e.setCancelled(true);
            Location hit = fireball.getLocation().clone();
            // 先让 manager 处理（要读 shooter），再移除实体
            manager.onFlameHit(fireball, hit);
            fireball.remove();
            return;
        }
        if (e.getEntity() instanceof Egg egg && manager.ownsBridgeEgg(egg)) {
            e.setCancelled(true);
            egg.remove();
            manager.stopBridgeEgg(egg);
        }
                return;
        }
        if (event instanceof EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof SmallFireball fireball && manager.ownsFlame(fireball)) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof BlockPlaceEvent e) {
        Player player = e.getPlayer();
        if (!manager.isPlaying(player)) {
            return;
        }
        if (!manager.isAlive(player)) {
            e.setCancelled(true);
            return;
        }
        ItemStack item = e.getItemInHand();
        if (manager.isTntItem(item) || manager.isSpecialItem(item)) {
            e.setCancelled(true);
            return;
        }
        TNTBattleArena.Team team = manager.teamOf(player);
        if (team == null || !org.bukkit.Tag.WOOL.isTagged(item.getType())
                || !manager.insideArena(e.getBlock())
                || !manager.withinBuildHeight(e.getBlock())) {
            e.setCancelled(true);
            return;
        }
        manager.rememberBlockState(e.getBlockReplacedState());
                return;
        }
        if (event instanceof BlockBreakEvent e) {
        Player player = e.getPlayer();
        if (!manager.isPlaying(player)) {
            return;
        }
        org.bukkit.block.Block block = e.getBlock();
        // 只允许破坏场地内的羊毛和玻璃类方块，其他方块仍然保护
        boolean wool = manager.isWool(block);
        boolean glass = manager.isGlass(block);
        if ((!wool && !glass) || !manager.insideArena(block)) {
            e.setCancelled(true);
            return;
        }
        manager.rememberBlock(block);
        // 羊毛正常掉落，玻璃类破坏不掉落
        e.setDropItems(wool);
        // 建筑猜猜乐对所有非 OP 玩家的方块破坏做了全局拦截，这里对场地内的羊毛/玻璃放行
        e.setCancelled(false);
                return;
        }
        if (event instanceof PlayerMoveEvent e) {
        if (!manager.isRunning() || e.getTo() == null) {
            return;
        }
        Player player = e.getPlayer();
        if (!manager.isAlive(player)) {
            return;
        }
        if (e.getTo().getY() < manager.arena().voidY()) {
            manager.eliminate(player, "掉入虚空");
        }
                return;
        }
        if (event instanceof EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player player)) {
            return;
        }
        if (!manager.isPlaying(player)) {
            return;
        }
        // 烈焰弹引爆瞬间的原版爆炸伤害：没有实体源 → Paper 归成 CUSTOM，只能靠时间窗口拦掉
        if (manager.isDetonatingSpecial()) {
            e.setCancelled(true);
            logDamage(player, e);
            return;
        }
        // 等待中或旁观者免伤
        if (!manager.isRunning() || !manager.isAlive(player)) {
            e.setCancelled(true);
            logDamage(player, e);
            return;
        }
        // 原版爆炸伤害事件不再使用；爆炸伤害由 manager.damagePlayersAround 主动结算
        EntityDamageEvent.DamageCause cause = e.getCause();
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            e.setCancelled(true);
        } else if (cause == EntityDamageEvent.DamageCause.FALL) {
            if (manager.consumeFallImmunity(player)) {
                // 自己用烈焰弹弹上天的这一次落地不算摔伤
                e.setCancelled(true);
            } else {
                double multiplier = manager.fallDamageMultiplier();
                if (multiplier <= 0) {
                    e.setCancelled(true);
                } else {
                    e.setDamage(e.getDamage() * multiplier);
                }
            }
        }
        logDamage(player, e);
                return;
        }
        if (event instanceof FoodLevelChangeEvent e) {
        // 等待室不饿；对局中允许正常消耗饥饿值，这样牛排可以吃
        if (e.getEntity() instanceof Player player
                && manager.isPlaying(player) && !manager.isRunning()) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof PlayerSwapHandItemsEvent e) {
        ItemStack main = e.getMainHandItem();
        ItemStack off = e.getOffHandItem();
        if (isFixedItem(main) || isFixedItem(off)) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player) || !manager.isPlaying(player)) {
            return;
        }
        if (isFixedItem(e.getCurrentItem()) || isFixedItem(e.getCursor())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof PlayerQuitEvent e) {
        lastUseTick.remove(e.getPlayer().getUniqueId());
        manager.quit(e.getPlayer());
                return;
        }
    }

    // ---- 原 Listener 的私有 helper ----

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

    private boolean isFixedItem(ItemStack item) {
        if (item == null) {
            return false;
        }
        if (manager.isTntItem(item)) {
            return true;
        }
        return item.getType().name().endsWith("_WOOL");
    }
}
