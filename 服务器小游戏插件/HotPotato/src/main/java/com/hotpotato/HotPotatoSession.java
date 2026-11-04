package com.hotpotato;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Sound;
import org.bukkit.block.Sign;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.Material;

/**
 * HotPotato 的对局会话（D22：**每个场地一个会话**，多图游戏照 TntRun 的写法）。
 */
final class HotPotatoSession extends GameSession {

    private final HotPotatoManager manager;

    HotPotatoSession(HotPotatoManager manager, String arenaName) {
        super("hotpotato", arenaName);
        this.manager = manager;
        // I3 C1：手上拿着炸弹的人不许把 TNT 放下（原 onTntPlace 的逐字等价搬运）
        arbiter().on(com.hub.session.EventKind.BLOCK_PLACE, ctx -> {
            if (ctx.block() == null || ctx.block().getType() != org.bukkit.Material.TNT) {
                return;
            }
            Player p = ctx.player();
            if (p == null) {
                return;
            }
            String roomArena = manager.arenaOf(p.getUniqueId());
            if (roomArena == null) {
                return;
            }
            HotPotatoArena a = manager.getArena(roomArena);
            if (a == null || a.getState() != HotPotatoArena.State.RUNNING
                    || !a.getPlayers().contains(p.getUniqueId())
                    || !a.isBombHolder(p.getUniqueId())) {
                return;
            }
            ctx.cancel();
            p.sendMessage("§c烫手山芋不能放置！把它传给其他玩家吧！");
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
    @SuppressWarnings("deprecation")
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        String arenaName = manager.arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        HotPotatoArena a = manager.getArena(arenaName);
        if (a == null || a.getState() != HotPotatoArena.State.RUNNING) {
            return;
        }
        if (!a.getPlayers().contains(p.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.teleport(a.getCenter());
    }

    /** 掉落：手上拿着炸弹的人不许丢（否则就等于把山芋扔地上了）——原 listener 里那段 @EventHandler。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        if (event.getItemDrop().getItemStack().getType() != Material.TNT) {
            return;
        }
        Player p = event.getPlayer();
        String arenaName = manager.arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        HotPotatoArena a = manager.getArena(arenaName);
        if (a == null || a.getState() != HotPotatoArena.State.RUNNING
                || !a.getPlayers().contains(p.getUniqueId())
                || !a.isBombHolder(p.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        p.sendMessage("§c烫手山芋不能丢弃！把它传给其他玩家吧！");
    }

    /** 右键开始纸。 */
    @Override
    protected void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) return;
        if (item.getType() == Material.PAPER) {
            manager.handleStartPaper(event.getPlayer());
            return;
        }
        if (item.getType() == Material.RED_BED) {
            manager.handleLeaveBed(event.getPlayer());
        }
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------

    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        String vArena = manager.plugin().getManager().arenaOf(victim.getUniqueId());
        if (vArena == null) {
            return;
        }
        HotPotatoArena arena = manager.plugin().getManager().getArena(vArena);
        if (arena == null) {
            return;
        }
        if (arena.getState() == HotPotatoArena.State.RUNNING && arena.getPlayers().contains(victim.getUniqueId())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        String vArena = manager.plugin().getManager().arenaOf(victim.getUniqueId());
        if (vArena == null) {
            return; // 受害者不在场内，按服务器默认规则处理
        }
        HotPotatoArena arena = manager.plugin().getManager().getArena(vArena);
        if (arena == null) {
            return;
        }
        // 对局中的存活玩家：免疫一切伤害，只能被 TNT 淘汰
        if (arena.getState() == HotPotatoArena.State.RUNNING && arena.getPlayers().contains(victim.getUniqueId())) {
            e.setCancelled(true);
        }
        // 同场玩家之间互打 → 尝试传递 TNT（不造成伤害）
        if (e.getDamager() instanceof Player damager) {
            String dArena = manager.plugin().getManager().arenaOf(damager.getUniqueId());
            if (dArena != null && dArena.equals(vArena)) {
                e.setCancelled(true);
                // 对局中：正常 PvP 受击反馈（红屏闪烁 + 击退 + 音效），但不掉血
                if (arena.getState() == HotPotatoArena.State.RUNNING
                        && arena.getPlayers().contains(victim.getUniqueId())) {
                    victim.playHurtAnimation(0);
                    victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.5f, 1.1f);
                    var dir = victim.getLocation().toVector().subtract(damager.getLocation().toVector());
                    dir.setY(0);
                    if (dir.lengthSquared() > 0.0001) {
                        dir.normalize().multiply(0.35);
                        dir.setY(0.18);
                        victim.setVelocity(dir);
                    }
                }
                manager.plugin().getManager().handleAttack(damager, victim);
            }
        }
                return;
        }
        if (event instanceof PlayerMoveEvent e) {
        Player p = e.getPlayer();
        // 跨世界瞬间不能比距离：别的小游戏（例如雪塔大作战结束）在自己的移动事件里把玩家传回主城后，
        // e.getFrom() 还是旧世界坐标，p.getLocation() 已在新世界 → distanceSquared 抛
        // IllegalArgumentException（Cannot measure distance between ...）。跳过即可，出圈另有轮询兜底。
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
        if (event instanceof PlayerInteractEvent e) {
        Player p = e.getPlayer();
        // 配置模式：工具交互
        if (manager.plugin().getManager().isInSetup(p.getUniqueId())) {
            if (manager.plugin().getManager().handleSetupTool(p, e.getAction(), e.getClickedBlock())) {
                e.setCancelled(true);
                return;
            }
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) {
            return;
        }
        if (!(e.getClickedBlock().getState() instanceof Sign)) {
            return;
        }
        // 待绑定加入牌
        if (manager.plugin().getManager().handleSignBind(p, e.getClickedBlock().getLocation())) {
            e.setCancelled(true);
            return;
        }
        // 点击加入牌 → 直接加入
        if (manager.plugin().getManager().isJoinSign(e.getClickedBlock().getLocation())) {
            e.setCancelled(true);
            manager.plugin().getManager().onSignClick(p, e.getClickedBlock().getLocation());
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
    }
}
