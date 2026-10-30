package com.skypit;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** 事件监听：区域进出、PvP 开关、死亡结算、重生归还、配置窗口。 */
public class SkyPitListener implements Listener {

    private static final String KIT_GUI_TITLE = "天坑装备配置";

    private final SkyPitPlugin plugin;

    public SkyPitListener(SkyPitPlugin plugin) {
        this.plugin = plugin;
    }

    /** 区域进出检测：进入自动发放装备，离开自动归还。（传送事件同样继承自移动事件） */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        // 只转头不算移动
        if (event.getFrom().distanceSquared(event.getTo()) < 0.0001) {
            return;
        }
        Player p = event.getPlayer();
        SkyPitManager m = plugin.getManager();
        if (m.contains(event.getTo()) && !m.inPit(p.getUniqueId())) {
            m.enterPit(p);
        } else if (!m.contains(event.getTo()) && m.inPit(p.getUniqueId())) {
            m.exitPit(p, false);
        }
    }

    /**
     * PvP 守卫（LOWEST）：天坑**所在世界里**、且双方不都在坑内时，禁止互伤。
     * <p>
     * 2026-10-31 修改（依据 {@code docs/框架不变量.md} 的 I2 / I3）：
     * 原来这里对**所有世界**无条件 {@code setCancelled(true)}，等于替全世界做决定 ——
     * 于是 {@code world_duel} / {@code world_lp} 的 {@code pvp: true} 被打掉，
     * 那些游戏只能反向在 HIGHEST 且 {@code ignoreCancelled=false} 抢回来。
     * 现在只在自己的世界里表态，别的地盘一律不插手。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageGuard(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = asPlayerDamager(event);
        if (attacker == null) {
            return;
        }
        SkyPitManager m = plugin.getManager();
        if (!m.isPitWorld(victim.getWorld())) {
            return;                       // 不是我的地盘 —— 一个字都不说
        }
        if (!m.inPit(victim.getUniqueId()) || !m.inPit(attacker.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * PvP 放行（HIGHEST）：双方都在天坑内时允许伤害（覆盖服务器 pvp=false，与决斗插件同机制）。
     * <p>
     * 同样只在坑所在世界生效：别的世界不需要也不应该由本插件来放行。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageAllow(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = asPlayerDamager(event);
        if (attacker == null) {
            return;
        }
        SkyPitManager m = plugin.getManager();
        if (!m.isPitWorld(victim.getWorld())) {
            return;
        }
        if (m.inPit(victim.getUniqueId()) && m.inPit(attacker.getUniqueId())) {
            event.setCancelled(false);
        }
    }

    /** 取造成伤害的玩家：直接攻击或弓箭/投掷物射手。 */
    private Player asPlayerDamager(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            return p;
        }
        if (event.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    /** 死亡：天坑内不掉落物品、不清等级，结算击杀数。 */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (!plugin.getManager().inPit(p.getUniqueId())) {
            return;
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.deathMessage(null);
        plugin.getManager().handleDeath(p);
    }

    /** 重生：归还装备并传送到复活点/入口/主城。 */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.getManager().handleRespawn(event.getPlayer(), event.getRespawnLocation());
    }

    /** 退出服务器：归还背包（防止天坑装备被写进玩家存档）。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getManager().handleQuit(event.getPlayer());
    }

    /** 重新登录：若还在区域内自动再次入坑。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getManager().handleJoin(event.getPlayer());
    }

    /** 切到创造/旁观：强制离场。 */
    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player p = event.getPlayer();
        GameMode newMode = event.getNewGameMode();
        plugin.getManager().handleGameModeChange(p, newMode);
    }

    /** 排行榜所在区块加载时刷新文字（重启后实体文字可能为旧内容）。 */
    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        plugin.getManager().refreshLeaderboardsInChunk(event.getChunk().getX(), event.getChunk().getZ());
    }

    /** 装备配置窗口点击：按钮处理，其余槽位自由摆放。 */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!title.startsWith(KIT_GUI_TITLE)) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (slot >= SkyPitManager.BTN_SAVE) {
            event.setCancelled(true);
            plugin.getManager().handleKitClick(p, event.getInventory(), slot);
        }
    }

    /** 天坑区域内不掉饥饿（与进出区域的判定同一把尺子：contains）。 */
    @EventHandler
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player p && plugin.getManager().contains(p.getLocation())) {
            event.setCancelled(true);
        }
    }
}
