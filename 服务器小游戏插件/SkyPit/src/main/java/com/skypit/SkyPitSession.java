package com.skypit;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.entity.Projectile;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.GameMode;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 天坑的会话 —— **一个"特殊会话"**（用户 2026-11-02 的定法）。
 *
 * <p>天坑不是"一局一局"的游戏：它挂在常驻世界里，**没有 start/end 轮次**。
 * 所以这里的语义是：
 * <ul>
 *   <li>玩家**进入天坑区域且处于生存/冒险模式** → 视为入局（{@code enterPit}）；</li>
 *   <li>玩家**死亡或离开区域** → 视为这一轮结束（离开会话）；</li>
 *   <li>最后一个人离开 → 会话由框架自动 {@code end(ABORT)}。</li>
 * </ul>
 *
 * <p>没有等待阶段：第一个进来的人直接把会话推到 RUNNING。
 */
final class SkyPitSession extends GameSession {

    /** 搬迁过来的代码要 manager：SkyPit 的会话不持有 plugin，统一从这里取一次。 */
    private final SkyPitManager manager = com.skypit.SkyPitPlugin.get().getManager();


    SkyPitSession() {
        super("skypit", "pit");
        enterWaiting();
    }

    void start() {
        enterRunning();
    }

    @Override
    protected void onStart() {
        // 天坑没有开局准备动作
    }

    @Override
    protected void onEnd(EndReason reason) {
        // 没有需要结算的东西：物资/背包由 SavedState 负责归还，击杀数走原有统计
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------

    private static final String KIT_GUI_TITLE = "天坑装备配置";

    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = asPlayerDamager(e);
        if (attacker == null) {
            return;
        }
        SkyPitManager m = com.skypit.SkyPitPlugin.get().getManager();
        if (!m.isPitWorld(victim.getWorld())) {
            return;                       // 不是我的地盘 —— 一个字都不说
        }
        if (!m.inPit(victim.getUniqueId()) || !m.inPit(attacker.getUniqueId())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = asPlayerDamager(e);
        if (attacker == null) {
            return;
        }
        SkyPitManager m = com.skypit.SkyPitPlugin.get().getManager();
        if (!m.isPitWorld(victim.getWorld())) {
            return;
        }
        if (m.inPit(victim.getUniqueId()) && m.inPit(attacker.getUniqueId())) {
            e.setCancelled(false);
        }
                return;
        }
        if (event instanceof PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!com.skypit.SkyPitPlugin.get().getManager().inPit(p.getUniqueId())) {
            return;
        }
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.getDrops().clear();
        e.deathMessage(null);
        com.skypit.SkyPitPlugin.get().getManager().handleDeath(p);
                return;
        }
        if (event instanceof PlayerRespawnEvent e) {
        com.skypit.SkyPitPlugin.get().getManager().handleRespawn(e.getPlayer(), e.getRespawnLocation());
                return;
        }
        if (event instanceof PlayerQuitEvent e) {
        com.skypit.SkyPitPlugin.get().getManager().handleQuit(e.getPlayer());
                return;
        }
        if (event instanceof org.bukkit.event.world.ChunkLoadEvent e) {
            // 区块加载时刷新坑内的排行榜（原来在 listener 里；搬迁脚本因"全限定类名"跳过了它，已补回）
            manager.refreshLeaderboardsInChunk(e.getChunk().getX(), e.getChunk().getZ());
            return;
        }
        if (event instanceof PlayerJoinEvent e) {
        com.skypit.SkyPitPlugin.get().getManager().handleJoin(e.getPlayer());
                return;
        }
        if (event instanceof PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        GameMode newMode = e.getNewGameMode();
        com.skypit.SkyPitPlugin.get().getManager().handleGameModeChange(p, newMode);
                return;
        }
        if (event instanceof InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        String title = PlainTextComponentSerializer.plainText().serialize(e.getView().title());
        if (!title.startsWith(KIT_GUI_TITLE)) {
            return;
        }
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) {
            return;
        }
        int slot = e.getSlot();
        if (slot >= SkyPitManager.BTN_SAVE) {
            e.setCancelled(true);
            com.skypit.SkyPitPlugin.get().getManager().handleKitClick(p, e.getInventory(), slot);
        }
                return;
        }
        if (event instanceof FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && com.skypit.SkyPitPlugin.get().getManager().contains(p.getLocation())) {
            e.setCancelled(true);
        }
                return;
        }
    }

    // ---- 原 Listener 的私有 helper ----

    private Player asPlayerDamager(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            return p;
        }
        if (event.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }
}
