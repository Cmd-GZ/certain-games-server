package com.murdermystery;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.block.Sign;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import com.hub.util.Chat;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/** MurderMystery 的对局会话（D22：每个场地一个会话）。 */
final class MurderMysterySession extends GameSession {

    private final MurderMysteryManager manager;

    MurderMysterySession(MurderMysteryManager manager, String arenaName) {
        super("murdermystery", arenaName);
        this.manager = manager;
        // I3 C1：局内禁改方块（原 `onBreak`/`onPlace` 的逐字等价搬运）
        arbiter()
                .on(com.hub.session.EventKind.BLOCK_BREAK, ctx -> {
                    if (ctx.player() != null && manager.arenaOf(ctx.player().getUniqueId()) != null) {
                        ctx.cancel();
                    }
                })
                .on(com.hub.session.EventKind.BLOCK_PLACE, ctx -> {
                    if (ctx.player() != null && manager.arenaOf(ctx.player().getUniqueId()) != null) {
                        ctx.cancel();
                    }
                });
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
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        String arenaName = manager.arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        MurderMysteryArena a = manager.getArena(arenaName);
        if (a == null || a.getState() != MurderMysteryArena.State.RUNNING) {
            return;
        }
        event.setCancelled(true);
        if (a.isMurderer(p.getUniqueId())) {
            manager.murdererEliminated(a, p);
        } else {
            // 正常路径已被拦截（环境伤/友伤都取消），这里兜底防 /kill 等
            p.setHealth(20);
            p.setFoodLevel(20);
            p.teleport(a.getCenter());
        }
    }

    /** 掉落：四段分流（淘汰全禁 / 等待全禁 / 非运行放行 / 运行期只禁商店与职业道具）——原 listener 里那段。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        String arenaName = manager.arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        MurderMysteryArena a = manager.getArena(arenaName);
        if (a == null) {
            return;
        }
        if (a.getEliminated().contains(p.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (a.getState() == MurderMysteryArena.State.WAITING) {
            event.setCancelled(true);
            return;
        }
        if (a.getState() != MurderMysteryArena.State.RUNNING) {
            return;
        }
        org.bukkit.inventory.ItemStack dropped = event.getItemDrop().getItemStack();
        if (manager.isShopItem(dropped)) {
            event.setCancelled(true);
            manager.notifyShopLocked(p);
            return;
        }
        // 职业装备（杀手铁剑/追踪指南针、猎人弓）局内一律不可丢弃：
        // 落到地上别人能捡，等于把职业武器送给对手（铁剑被捡走还能当飞剑扔）。
        if (manager.isRoleItem(dropped)) {
            event.setCancelled(true);
            manager.notifyRoleItemLocked(p);
        }
    }

    /** 交互：配置工具（左右键都认）、金锭兑换、开始纸、退出床、杀手飞剑、加入告示牌。 */
    @Override
    protected void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        Player p = event.getPlayer();
        MurderMysteryManager m = manager;
        ItemStack item = event.getItem();

        // 配置模式工具：木棍是"左键=范围角1、右键=范围角2"，所以这里必须先于右键过滤处理
        if (m.isInSetup(p.getUniqueId())) {
            Action action = event.getAction();
            if ((action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK
                    || action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)
                    && m.handleSetupTool(p, action, event.getClickedBlock())) {
                event.setCancelled(true);
                return;
            }
        }

        // 下面是右键玩法（兑换/道具/告示牌），只处理右键
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // 对局内交互
        String arenaName = m.arenaOf(p.getUniqueId());
        if (arenaName != null) {
            MurderMysteryArena a = m.getArena(arenaName);
            if (a != null && a.getState() == MurderMysteryArena.State.RUNNING) {
                // 商店弩不在这里拦截：交给原版弩自己装填/发射；猎人弓的无限箭由 EntityShootBowEvent 处理
                if (item != null && m.isShopItem(item)) {
                    m.openShop(p);
                    event.setCancelled(true);
                    return;
                }
                if (item != null && m.isTruthStone(item)) {
                    event.setCancelled(true);
                    m.openTruthMenu(p);
                    return;
                }
                if (item != null && m.isDeathNote(item)) {
                    event.setCancelled(true);
                    m.useDeathNote(p);
                    return;
                }
                if (item != null && m.isTrackCompass(item)) {
                    event.setCancelled(true);
                    m.handleTrackCompass(p);
                    return;
                }
                if (item != null && item.getType() == Material.IRON_SWORD) {
                    m.handleSwordThrow(p);
                    event.setCancelled(true);
                    return;
                }
            }
            if (item != null && item.getType() == Material.PAPER) {
                m.handleStartPaper(p);
                return;
            }
            if (item != null && item.getType() == Material.RED_BED) {
                m.handleLeaveBed(p);
                return;
            }
        }

        // 加入告示牌（绑定 / 点击加入）
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            if (!(event.getClickedBlock().getState() instanceof Sign)) {
                return;
            }
            if (m.handleSignBind(p, event.getClickedBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }
            if (m.isJoinSign(event.getClickedBlock().getLocation())) {
                event.setCancelled(true);
                m.onSignClick(p, event.getClickedBlock().getLocation());
            }
        }
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------

    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof EntityDamageEvent e) {
        // 地图保护：对局中的玩家（或 TA 的弹射物）不能打坏地图上的盔甲架/展示框/装饰实体
        if (!(e.getEntity() instanceof Player) && attackerInArena(e)) {
            e.setCancelled(true);
            return;
        }
        EntityDamageEvent.DamageCause cause = e.getCause();
        if (cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || cause == EntityDamageEvent.DamageCause.PROJECTILE) {
            return;
        }
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        // 开局身份倒数期间禁止一切伤害（免得有人抢跑）
        if (manager.plugin().getManager().isRoleCountdown(victim)) {
            e.setCancelled(true);
            return;
        }
        String vArena = manager.plugin().getManager().arenaOf(victim.getUniqueId());
        if (vArena == null) {
            return;
        }
        MurderMysteryArena arena = manager.plugin().getManager().getArena(vArena);
        if (arena == null || arena.getState() != MurderMysteryArena.State.RUNNING) {
            return;
        }
        if (arena.getPlayers().contains(victim.getUniqueId())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        MurderMysteryManager m = manager.plugin().getManager();
        // 开局身份倒数期间禁止一切伤害（免得有人抢跑）
        if (m.isRoleCountdown(victim)) {
            e.setCancelled(true);
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
            e.setCancelled(true);
            return;
        }

        // 弹射物（杀手飞剑 / 箭矢）
        if (e.getDamager() instanceof Projectile proj) {
            Player shooter = proj.getShooter() instanceof Player sp ? sp : null;
            if (shooter == null) {
                e.setCancelled(true);
                return;
            }
            String sArena = m.arenaOf(shooter.getUniqueId());
            if (sArena == null || !sArena.equals(vArena)) {
                e.setCancelled(true); // 场外弹射物无效
                return;
            }
            e.setCancelled(true);
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
        if (e.getDamager() instanceof Player attacker) {
            String dArena = m.arenaOf(attacker.getUniqueId());
            if (dArena == null || !dArena.equals(vArena)) {
                e.setCancelled(true);
                return;
            }
            // 真理之石：左键探测身份（杀手→红石，无辜者→钻石），不造成伤害
            if (m.isTruthStone(attacker.getInventory().getItemInMainHand())) {
                e.setCancelled(true);
                m.handleTruthStone(arena, attacker, victim);
                return;
            }
            // 商店石剑
            if (m.isSwordItem(attacker.getInventory().getItemInMainHand())) {
                e.setCancelled(true);
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
                e.setCancelled(true);
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
            e.setCancelled(true);
            return;
        }

        // 非玩家伤害来源
        e.setCancelled(true);
                return;
        }
        if (event instanceof EntityShootBowEvent e) {
        if (e.getEntity() instanceof Player && manager.plugin().getManager().isHunterBow(e.getBow())) {
            e.setConsumeArrow(false);
            e.setConsumeItem(false);
        }
                return;
        }
        if (event instanceof PlayerInteractEntityEvent e) {
        String owner = manager.plugin().getManager().corpseOwner(e.getRightClicked());
        if (owner == null) {
            return;
        }
        e.setCancelled(true); // 顺便防止别人摆弄尸体
        Chat.send(e.getPlayer(), "&7这具尸体是 &e" + owner + "&7 的！");
                return;
        }
        if (event instanceof PlayerArmorStandManipulateEvent e) {
        if (inArena(e.getPlayer())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof ItemFrame && inArena(e.getPlayer())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof HangingBreakByEntityEvent e) {
        if (e.getRemover() instanceof Player p && inArena(p)) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof HangingPlaceEvent e) {
        Player p = e.getPlayer();
        if (p != null && inArena(p)) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof PlayerMoveEvent e) {
        Player p = e.getPlayer();
        // 跨世界瞬间不能比距离：别的小游戏（例如雪塔大作战结束）在自己的移动事件里把玩家传回主城后，
        // 本监听器拿到的 e.getFrom() 还是旧世界坐标，而 p.getLocation() 已经在新世界，
        // distanceSquared 会抛 IllegalArgumentException（Cannot measure distance between world 和 world_tr）。
        // 这种事件直接跳过即可：出界淘汰另有 0.5s 定时兜底。
        if (!p.getWorld().equals(e.getFrom().getWorld())) {
            return;
        }
        if (p.getLocation().distanceSquared(e.getFrom()) < 0.0001) {
            return;
        }
        manager.plugin().getManager().handleMove(p);
                return;
        }
        if (event instanceof PlayerJoinEvent e) {
        manager.plugin().getManager().handleJoin(e.getPlayer());
                return;
        }
        if (event instanceof EntityPickupItemEvent e) {
        MurderMysteryManager m = manager.plugin().getManager();
        if (!m.isHunterBow(e.getItem().getItemStack())) {
            return;
        }
        // 除了玩家，也拦住狐狸/村民等会捡东西的生物，避免弓被 NPC 拿走
        if (!(e.getEntity() instanceof Player p)) {
            e.setCancelled(true);
            return;
        }
        String arenaName = m.arenaOf(p.getUniqueId());
        MurderMysteryArena a = arenaName == null ? null : m.getArena(arenaName);
        if (a == null || a.getState() != MurderMysteryArena.State.RUNNING
                || !a.getPlayers().contains(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (a.isMurderer(p.getUniqueId())) {
            e.setCancelled(true);
            Chat.actionBar(p, "&c杀手不能捡起猎人的弓。");
            return;
        }
        // 捡到弓的人也要有无限箭弹，否则原版弓射不出去。
        // 捡起事件在物品真正进背包之前触发，所以延后一 tick 再检查。
        org.bukkit.Bukkit.getScheduler().runTask(manager.plugin(), () -> {
            if (p.isOnline()) {
                m.ensureHunterAmmo(p);
            }
        });
                return;
        }
        if (event instanceof ProjectileHitEvent e) {
        if (manager.plugin().getManager().isThrownProjectile(e.getEntity())) {
            manager.plugin().getManager().removeFlyingSwordDisplay(e.getEntity().getUniqueId());
            e.getEntity().remove();
        }
                return;
        }
        if (event instanceof InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        if (e.getView().getTitle() == null || !e.getView().getTitle().equals(manager.plugin().getManager().shopTitle())) {
            return;
        }
        e.setCancelled(true);
        if (e.getCurrentItem() == null || e.getCurrentItem().getType().isAir()) {
            return;
        }
        int raw = e.getRawSlot();
        if (raw >= 0 && raw < 9) {
            manager.plugin().getManager().onShopBuy(p, raw, e.getInventory());
        }
                return;
        }
        if (event instanceof PlayerSignOpenEvent e) {
        if (e.getSign() != null && manager.plugin().getManager().isJoinSign(e.getSign().getLocation())) {
            e.setCancelled(true);
            manager.plugin().getManager().onSignClick(e.getPlayer(), e.getSign().getLocation());
        }
                return;
        }
        if (event instanceof InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof WatchMenuHolder holder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= e.getView().getTopInventory().getSize()) {
            return; // 点在自己背包里：只取消，不传送
        }
        if (e.getCurrentItem() == null || e.getCurrentItem().getType().isAir()) {
            return;
        }
        manager.plugin().getManager().onWatchClick(p, holder, raw);
                return;
        }
        if (event instanceof InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof WatchMenuHolder) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof TruthMenuHolder holder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= e.getView().getTopInventory().getSize()) {
            return; // 点在自己背包里：只取消，不探测
        }
        if (e.getCurrentItem() == null || e.getCurrentItem().getType().isAir()) {
            return;
        }
        manager.plugin().getManager().onTruthMenuClick(p, holder, raw);
                return;
        }
        if (event instanceof InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof TruthMenuHolder) {
            e.setCancelled(true);
        }
                return;
        }
    }

    // ---- 原 Listener 的私有 helper ----

    private String projectileWeaponName(MurderMysteryManager m, Projectile proj, Player shooter) {
        if (proj instanceof AbstractArrow arrow && arrow.getWeapon() != null) {
            return arrow.getWeapon().getType() == Material.CROSSBOW ? "弩" : "弓";
        }
        return "弓";
    }

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

    private boolean inArena(Player p) {
        String arenaName = manager.plugin().getManager().arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return false;
        }
        MurderMysteryArena a = manager.plugin().getManager().getArena(arenaName);
        return a != null && a.getPlayers().contains(p.getUniqueId());
    }
}
