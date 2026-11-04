package com.tntrun;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.entity.Snowball;
import org.bukkit.block.Sign;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 雪塔大作战的对局会话 —— **第一个多场地游戏**接入 {@code com.hub.session}。
 *
 * <p>和单场地的 HillKing / MemoryFlip 不同：这个游戏每个场地可以**同时各开一局**，
 * 所以**一个场地一个会话**（`Sessions` 天然支持，`activeByGame()` 会显示成 `tntrun=2`）。
 * 会话名里带上场地名，doctor 里能一眼区分是哪一块场地。
 */
final class TntRunSession extends GameSession {

    private final TntRunManager manager;

    TntRunSession(TntRunManager manager, String arenaName) {
        super("tntrun", arenaName);
        this.manager = manager;
        enterWaiting();
    }

    /** WAITING → RUNNING（状态只能由会话自己推进，见 I5）。 */
    void start() {
        enterRunning();
    }

    @Override
    protected void onStart() {
        // 本轮不改玩法：计时/传送仍由 manager 负责
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
        TntRunArena a = manager.getArena(arenaName);
        if (a == null) {
            return;
        }
        if (!a.getPlayers().contains(p.getUniqueId())) {
            return;
        }
        // 对局中重生回自己开局那一格（圆边缘）；没记到（老场地/没设中心）退回出生点
        Location loc = a.getState() == TntRunArena.State.RUNNING
                ? a.getRoundSpawns().getOrDefault(p.getUniqueId(), a.getSpawn())
                : a.getLobbyPoint();
        if (loc != null) {
            event.setRespawnLocation(loc);
        }
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------

    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof PlayerMoveEvent e) {
        manager.plugin().getManager().handleMove(e.getPlayer());
                return;
        }
        if (event instanceof EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        String arenaName = manager.plugin().getManager().arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        TntRunArena a = manager.plugin().getManager().getArena(arenaName);
        if (a != null && a.getState() == TntRunArena.State.RUNNING) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!manager.plugin().getManager().isPlayingIn(p, e.getBlock().getLocation())) {
            return;
        }
        if (e.getBlock().getType() != Material.SNOW_BLOCK) {
            return;
        }
        e.setDropItems(false);
        manager.plugin().getManager().breakSnow(p, e.getBlock());
                return;
        }
        if (event instanceof ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Snowball)) {
            return;
        }
        Block hit = e.getHitBlock();
        if (hit == null || hit.getType() != Material.SNOW_BLOCK) {
            return;
        }
        manager.plugin().getManager().breakSnowByBall(hit);
                return;
        }
        if (event instanceof PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = e.getItem();
        if (item == null) return;
        if (item.getType() == Material.PAPER) {
            manager.plugin().getManager().handleStartPaper(e.getPlayer());
            return;
        }
        if (item.getType() == Material.RED_BED) {
            manager.plugin().getManager().handleLeaveBed(e.getPlayer());
        }
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
