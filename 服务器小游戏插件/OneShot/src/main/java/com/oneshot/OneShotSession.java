package com.oneshot;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Bukkit;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Arrow;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** One Shot 的对局会话（D22：会话层按场地键索引，单图游戏只有一个键）。 */
final class OneShotSession extends GameSession {

    private final OneShotManager manager;

    OneShotSession(OneShotManager manager, String arenaName) {
        super("oneshot", arenaName);
        this.manager = manager;
        // 本局禁止丢任何物品（原 listener 里那段 @EventHandler，逐字等价）
        rules().noItemDrop(true);

        // I3 C1（区域裁决）：本局禁改方块，且**只在自家场地范围内**表态。
        arbiter()
                .on(com.hub.session.EventKind.BLOCK_BREAK, ctx -> ctx.cancel())
                .on(com.hub.session.EventKind.BLOCK_PLACE, ctx -> ctx.cancel());
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
        manager.handleQuit(player);
    }

    @Override
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!manager.isInGame(player)) {
            return;
        }
        event.deathMessage(null);
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        manager.handleDeath(player);
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInGame(player)) {
            return;
        }
        Location spot = manager.arena().spectatorSpot();
        if (spot != null) {
            event.setRespawnLocation(spot);
        }
        manager.respawnedNextTick(player);
    }

    @Override
    protected void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        if (manager.isReturnItem(item)) {
            event.setCancelled(true);
            manager.leave(player, false);
            return;
        }
        if (manager.isStartItem(item)) {
            event.setCancelled(true);
            manager.handleStartPaper(player);
        }
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------


    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = null;
        boolean arrow = false;
        Entity damager = e.getDamager();
        if (damager instanceof Player player) {
            attacker = player;
        } else if (damager instanceof Arrow projectile && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter;
            arrow = true;
        }
        if (!manager.isInGame(victim)) {
            // 对局内玩家不能打大厅/观战玩家
            if (attacker != null && manager.isInGame(attacker)) {
                e.setCancelled(true);
            }
            return;
        }
        if (!manager.isPlaying(victim) || attacker == null || !manager.isPlaying(attacker)
                || attacker.equals(victim)) {
            e.setCancelled(true);
            return;
        }
        // 天坑（SkyPit）会在 LOWEST 无条件取消“非天坑玩家”的 PvP 伤害，
        // 这里必须显式解除取消，否则本局玩家永远打不出伤害。
        e.setCancelled(false);
        manager.recordHit(victim, attacker);
        if (arrow) {
            // 一箭秒杀；保留击杀者信息，让死亡结算能补箭
            e.setDamage(1000.0);
        }
                return;
        }
        if (event instanceof EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = asPlayerDamager(e);
        if (attacker == null || attacker.equals(victim)
                || !manager.isPlaying(victim) || !manager.isPlaying(attacker)) {
            return;
        }
        if (e.isCancelled()) {
            e.setCancelled(false);
        }
                return;
        }
        if (event instanceof EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent) {
            return;
        }
        if (e.getEntity() instanceof Player player && manager.isInGame(player)) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof EntityRegainHealthEvent e) {
        if (e.getEntity() instanceof Player player && manager.isInGame(player)) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof PlayerAttemptPickupItemEvent e) {
        if (manager.isInGame(e.getPlayer())
                && e.getItem().getItemStack().getType() == manager.arrowMaterial()) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof PlayerPickupArrowEvent e) {
        if (manager.isInGame(e.getPlayer())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof PlayerMoveEvent e) {
        if (e.getTo() != null) {
            manager.handleMove(e.getPlayer(), e.getTo());
        }
                return;
        }
    }

    // ---- 原 Listener 的私有 helper（随处理器一起搬过来）----

    private Player asPlayerDamager(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Arrow arrow && arrow.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
