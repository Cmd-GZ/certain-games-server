package com.murdermystery;

import com.hub.util.Chat;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 密室杀手事件：杀手一击必杀、商店武器（石剑/弩）与猎人的弓、同归于尽、友伤拦截、
 * 金锭商店点击购买、飞剑投掷、出界淘汰、退出清理、配置工具与加入告示牌，
 * 以及**地图保护**（对局中不能脱盔甲架、动展示框、打坏装饰实体）。
 */
public class MurderMysteryListener implements Listener {

    private final MurderMysteryPlugin plugin;

    public MurderMysteryListener(MurderMysteryPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 非实体伤害（虚空/掉落/岩浆/窒息等）对局中存活玩家一律取消；
     * 实体攻击/箭矢由 onDamage 统一处理，避免双重拦截。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnyDamage(EntityDamageEvent event) {
        // 地图保护：对局中的玩家（或 TA 的弹射物）不能打坏地图上的盔甲架/展示框/装饰实体
        if (!(event.getEntity() instanceof Player) && attackerInArena(event)) {
            event.setCancelled(true);
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || cause == EntityDamageEvent.DamageCause.PROJECTILE) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        // 开局身份倒数期间禁止一切伤害（免得有人抢跑）
        if (plugin.getManager().isRoleCountdown(victim)) {
            event.setCancelled(true);
            return;
        }
        String vArena = plugin.getManager().arenaOf(victim.getUniqueId());
        if (vArena == null) {
            return;
        }
        MurderMysteryArena arena = plugin.getManager().getArena(vArena);
        if (arena == null || arena.getState() != MurderMysteryArena.State.RUNNING) {
            return;
        }
        if (arena.getPlayers().contains(victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * 核心战斗规则（杀手不隐身，全体玩家可用商店武器；猎人开局自带弓）：
     * 1. 杀手铁剑/飞剑/石剑命中任何人 → 一击必杀（掉金锭 + 尸体 + 剑升级）；
     * 2. 无辜者石剑命中杀手 → 一击必杀，无辜者获胜；误伤无辜者 → 两人同归于尽；
     * 3. 商店弩 / 猎人弓（箭矢）命中杀手 → 一击必杀；误伤好人 → 两人同归于尽；
     * 4. 无辜者徒手反击杀手 → 正常掉血（死亡事件结算）；
     * 5. 无辜者之间其他伤害 → 取消。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        MurderMysteryManager m = plugin.getManager();
        // 开局身份倒数期间禁止一切伤害（免得有人抢跑）
        if (m.isRoleCountdown(victim)) {
            event.setCancelled(true);
            return;
        }
        String vArena = m.arenaOf(victim.getUniqueId());
        if (vArena == null) {
            return;
        }
        MurderMysteryArena arena = m.getArena(vArena);
        if (arena == null || arena.getState() != MurderMysteryArena.State.RUNNING) {
            return;
        }
        // 观战者无敌
        if (!arena.getPlayers().contains(victim.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        // 弹射物（杀手飞剑 / 箭矢）
        if (event.getDamager() instanceof Projectile proj) {
            Player shooter = proj.getShooter() instanceof Player sp ? sp : null;
            if (shooter == null) {
                event.setCancelled(true);
                return;
            }
            String sArena = m.arenaOf(shooter.getUniqueId());
            if (sArena == null || !sArena.equals(vArena)) {
                event.setCancelled(true); // 场外弹射物无效
                return;
            }
            event.setCancelled(true);
            // 杀手飞剑（由铁剑投出）
            if (m.isThrownProjectile(proj)) {
                if (arena.isMurderer(shooter.getUniqueId()) && !arena.isMurderer(victim.getUniqueId())) {
                    m.handleMurdererKill(arena, shooter, victim);
                }
                return;
            }
            // 箭矢（商店弩 / 猎人的弓）：杀手 → 不生效（杀手只有铁剑能秒人，且普通伤害会被 onDeath 兜底复活，所以直接拦掉）
            //       无辜者阵营 → 射中杀手一击必杀，误伤好人两人同归于尽
            String weapon = projectileWeaponName(m, proj, shooter);
            if (arena.isMurderer(shooter.getUniqueId())) {
                return;
            } else if (arena.isMurderer(victim.getUniqueId())) {
                m.handleInnocentKill(arena, shooter, victim, weapon);
            } else {
                m.handleMutualDeath(arena, shooter, victim, weapon);
            }
            return;
        }

        // 近战
        if (event.getDamager() instanceof Player attacker) {
            String dArena = m.arenaOf(attacker.getUniqueId());
            if (dArena == null || !dArena.equals(vArena)) {
                event.setCancelled(true);
                return;
            }
            // 真理之石：左键探测身份（杀手→红石，无辜者→钻石），不造成伤害
            if (m.isTruthStone(attacker.getInventory().getItemInMainHand())) {
                event.setCancelled(true);
                m.handleTruthStone(arena, attacker, victim);
                return;
            }
            // 商店石剑
            if (m.isSwordItem(attacker.getInventory().getItemInMainHand())) {
                event.setCancelled(true);
                if (arena.isMurderer(attacker.getUniqueId())) {
                    if (!arena.isMurderer(victim.getUniqueId())
                            && m.startKnifeCooldown(arena, attacker)) { // 近战刀 2 秒冷却
                        m.handleMurdererKill(arena, attacker, victim);
                    }
                } else if (arena.isMurderer(victim.getUniqueId())) {
                    m.handleInnocentKill(arena, attacker, victim);
                } else {
                    m.handleMutualDeath(arena, attacker, victim, "石剑");
                }
                return;
            }
            // 杀手：**只有手持铁剑**命中才一击必杀；其他武器（弓/石剑/徒手/捡到的东西）一律不生效
            // （不能放普通伤害过去：onDeath 会把被打死的好人满血传送回去，变成"打死了又复活"）
            if (arena.isMurderer(attacker.getUniqueId())) {
                event.setCancelled(true);
                if (attacker.getInventory().getItemInMainHand().getType() == Material.IRON_SWORD
                        && !arena.isMurderer(victim.getUniqueId())
                        && m.startKnifeCooldown(arena, attacker)) { // 近战刀 2 秒冷却
                    m.handleMurdererKill(arena, attacker, victim);
                }
                return;
            }
            if (arena.isMurderer(victim.getUniqueId())) {
                // 无辜者徒手反击杀手：正常掉血，死亡事件结算（不取消伤害）
                return;
            }
            // 无辜者之间禁伤
            event.setCancelled(true);
            return;
        }

        // 非玩家伤害来源
        event.setCancelled(true);
    }

    /**
     * 死亡结算：杀手死亡 → 无辜者获胜；无辜者被环境/命令击杀则兜底复活。
     */
    /**
     * 弹射物的武器名：射出的箭带武器（弩 → 「弩」，弓 → 「弓」）；读不到时退回看射手手上拿着什么。
     * <p>
     * 死亡提示已整体删除（2026-10-16），所以这个名字目前只作为参数传下去，不再出现在任何给玩家看的文案里。
     */
    private String projectileWeaponName(MurderMysteryManager m, Projectile proj, Player shooter) {
        if (proj instanceof AbstractArrow arrow && arrow.getWeapon() != null) {
            return arrow.getWeapon().getType() == Material.CROSSBOW ? "弩" : "弓";
        }
        return "弓";
    }

    /**
     * 猎人之弓的无限箭：弓射出去会消耗一支箭（背包里没有箭则根本射不出去），
     * 这里把消耗关掉，那一支无限箭弹就永远留在背包里。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShootBow(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player && plugin.getManager().isHunterBow(event.getBow())) {
            event.setConsumeArrow(false);
            event.setConsumeItem(false);
        }
    }

    /**
     * 右键尸体：在聊天框告诉玩家这具尸体是谁的。
     * <p>
     * 注册的是 {@link PlayerInteractEntityEvent} 而不是它的子类，因为对盔甲架服务端发的是
     * {@code PlayerInteractAtEntityEvent}（子类），Bukkit 会一并派发给父类监听器，两种都能收到。
     */
    @EventHandler
    public void onCorpseInteract(PlayerInteractEntityEvent event) {
        String owner = plugin.getManager().corpseOwner(event.getRightClicked());
        if (owner == null) {
            return;
        }
        event.setCancelled(true); // 顺便防止别人摆弄尸体
        Chat.send(event.getPlayer(), "&7这具尸体是 &e" + owner + "&7 的！");
    }

    // ---------- 地图保护（对局中不许改动地图） ----------

    /** 伤害来源是不是"正在对局中的玩家"（直接攻击，或 TA 射出的弹射物）。 */
    private boolean attackerInArena(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return false;
        }
        org.bukkit.entity.Entity damager = byEntity.getDamager();
        if (damager instanceof Projectile proj && proj.getShooter() instanceof org.bukkit.entity.Entity shooter) {
            damager = shooter;
        }
        return damager instanceof Player p && inArena(p);
    }

    /** 地图保护：对局中不能把盔甲架身上的装备/手里的方块拿下来（"脱衣服"）。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (inArena(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** 地图保护：对局中不能拿走/旋转物品展示框里的东西。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame && inArena(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** 地图保护：对局中不能打掉画/物品展示框（打掉会掉落里面的东西）。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player p && inArena(p)) {
            event.setCancelled(true);
        }
    }

    /** 地图保护：对局中不能往墙上挂新的画/物品展示框。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player p = event.getPlayer();
        if (p != null && inArena(p)) {
            event.setCancelled(true);
        }
    }

    /** 出界淘汰（另有 0.5s 定时兜底，防服务器端移动事件不触发）。 */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        // 跨世界瞬间不能比距离：别的小游戏（例如雪塔大作战结束）在自己的移动事件里把玩家传回主城后，
        // 本监听器拿到的 event.getFrom() 还是旧世界坐标，而 p.getLocation() 已经在新世界，
        // distanceSquared 会抛 IllegalArgumentException（Cannot measure distance between world 和 world_tr）。
        // 这种事件直接跳过即可：出界淘汰另有 0.5s 定时兜底。
        if (!p.getWorld().equals(event.getFrom().getWorld())) {
            return;
        }
        if (p.getLocation().distanceSquared(event.getFrom()) < 0.0001) {
            return;
        }
        plugin.getManager().handleMove(p);
    }

    /** 退出清理。 */
    /** 加入服务器：清理观战模式残留，防止滞留对局世界。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getManager().handleJoin(event.getPlayer());
    }

    /** 丢物品：等待阶段全禁；运行阶段只允许丢金锭（金币分享），职业装备与商店道具一律不可丢。 */
    /**
     * 猎人弓掉在地上后：杀手不能捡，其他存活玩家可以捡；
     * 场外玩家和观战者也不能把对局道具带出去。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        MurderMysteryManager m = plugin.getManager();
        if (!m.isHunterBow(event.getItem().getItemStack())) {
            return;
        }
        // 除了玩家，也拦住狐狸/村民等会捡东西的生物，避免弓被 NPC 拿走
        if (!(event.getEntity() instanceof Player p)) {
            event.setCancelled(true);
            return;
        }
        String arenaName = m.arenaOf(p.getUniqueId());
        MurderMysteryArena a = arenaName == null ? null : m.getArena(arenaName);
        if (a == null || a.getState() != MurderMysteryArena.State.RUNNING
                || !a.getPlayers().contains(p.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (a.isMurderer(p.getUniqueId())) {
            event.setCancelled(true);
            Chat.actionBar(p, "&c杀手不能捡起猎人的弓。");
            return;
        }
        // 捡到弓的人也要有无限箭弹，否则原版弓射不出去。
        // 捡起事件在物品真正进背包之前触发，所以延后一 tick 再检查。
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                m.ensureHunterAmmo(p);
            }
        });
    }

    /** 对局中禁止破坏方块。 */
    /** 对局中禁止放置方块。 */
    /** 飞剑命中后移除弹射物（无论命中玩家还是方块）和跟着它飞的铁剑外形。 */
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (plugin.getManager().isThrownProjectile(event.getEntity())) {
            plugin.getManager().removeFlyingSwordDisplay(event.getEntity().getUniqueId());
            event.getEntity().remove();
        }
    }

    /** 金锭商店点击：购买并取消移动（防拿走物品）。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShopClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        if (event.getView().getTitle() == null || !event.getView().getTitle().equals(plugin.getManager().shopTitle())) {
            return;
        }
        event.setCancelled(true);
        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw >= 0 && raw < 9) {
            plugin.getManager().onShopBuy(p, raw, event.getInventory());
        }
    }

    /** Paper：右键告示牌先触发本事件，cancel 后 PlayerInteractEvent 不再触发。 */
    @EventHandler
    public void onSignOpen(PlayerSignOpenEvent event) {
        if (event.getSign() != null && plugin.getManager().isJoinSign(event.getSign().getLocation())) {
            event.setCancelled(true);
            plugin.getManager().onSignClick(event.getPlayer(), event.getSign().getLocation());
        }
    }

    /** 存活玩家菜单点击（观战者）：点头像传送到该玩家那里，并取消拿物品。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWatchClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof WatchMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= event.getView().getTopInventory().getSize()) {
            return; // 点在自己背包里：只取消，不传送
        }
        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) {
            return;
        }
        plugin.getManager().onWatchClick(p, holder, raw);
    }

    /** 存活玩家菜单里也不许拖拽（防把头颅拖走）。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWatchDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof WatchMenuHolder) {
            event.setCancelled(true);
        }
    }

    /** 真理之石菜单点击：探测点中的那名玩家（消耗一颗石头），并取消拿物品。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTruthClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TruthMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= event.getView().getTopInventory().getSize()) {
            return; // 点在自己背包里：只取消，不探测
        }
        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) {
            return;
        }
        plugin.getManager().onTruthMenuClick(p, holder, raw);
    }

    /** 真理之石菜单里也不许拖拽（防把头颅拖走）。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTruthDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TruthMenuHolder) {
            event.setCancelled(true);
        }
    }

    /** 玩家在某个密杀场地里（等待中或对局中）——地图保护用这个，等待区也不许改地图。 */
    private boolean inArena(Player p) {
        String arenaName = plugin.getManager().arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return false;
        }
        MurderMysteryArena a = plugin.getManager().getArena(arenaName);
        return a != null && a.getPlayers().contains(p.getUniqueId());
    }

    /** 场内玩家不掉饥饿（等待中或对局中，复用 inArena）。 */
}
