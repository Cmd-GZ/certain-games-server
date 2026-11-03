package com.luckypillar;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 事件监听：死亡/掉线淘汰、圈外与虚空兜底、苦力怕爆炸只伤人不破方块、PvP 开关、
 * 圈外方块判定（玩家放的方块/流体）、配置模式道具、等待室退出道具。
 * <p>
 * <b>爆炸拦截铁律</b>（AGENTS.md §5-5）：{@code HIGHEST} 拦截必须先判「本世界 + 本场对局」，
 * 否则会吃掉别的小游戏（例如单挑的风弹）的爆炸。
 */
public class LuckyPillarListener implements Listener {

    private final LuckyPillarPlugin plugin;

    public LuckyPillarListener(LuckyPillarPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 死亡：RUNNING = 淘汰（物品照常掉落）；ENDING（结算展示期）= 不淘汰，只转旁观。
     * 两种情况都交给 {@code handleDeath} 去「跳过死亡界面 → 旁观」。
     */
    /** 淘汰后重生 → 旁观点 + 旁观模式。 */
    /** 掉线 = 淘汰（设计稿 §6）。 */
    /** 进服兜底：旁观模式残留还原、不在对局却留在对局世界则送回主城。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getManager().handleJoin(event.getPlayer());
    }

    /**
     * 苦力怕爆炸：清空方块列表（只伤人不破方块，设计稿 §4、§0-5）。
     * TNT 雨要炸方块，这里不拦；但仍先判世界，绝不碰别的小游戏。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof Creeper)) {
            return;
        }
        LuckyPillarArena a = plugin.getManager().runningArenaIn(event.getLocation().getWorld());
        if (a == null) {
            return; // 不是本世界 / 不是本场对局：原样放行
        }
        event.blockList().clear();
        event.setYield(0f);
    }

    /** 苦力怕雨从 30 格落下：取消它们自己的摔落伤害，否则落地就摔死炸不出来。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (plugin.getManager().isTrackedEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * PvP：本场对局中的存活玩家互伤「放行」，config 的 pvp 关掉时取消。
     * <p>
     * <b>为什么必须显式 {@code setCancelled(false)}</b>：{@code SkyPitListener#onDamageGuard}（LOWEST）
     * 是全服的 PvP 守卫——「双方不都在天坑内」就无条件取消玩家互伤（主城防误伤）。幸运之柱的玩家
     * 当然不在天坑里，所以伤害会被那一层吃掉，表现就是「砍对方完全没伤害」。单挑 PvP 也是用
     * 同样的办法在自己的 HIGHEST 里 {@code setCancelled(false)} 覆盖回来的（{@code DuelListener#onDamage}）。
     * <p>
     * 同理这里<b>不能</b>加 {@code ignoreCancelled = true}：事件已经被上一层的守卫取消了，
     * 加了就根本收不到，放行也无从谈起。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player damager = asPlayer(event.getDamager());
        if (damager == null) {
            return;
        }
        LuckyPillarArena a = plugin.getManager().activeArenaOf(victim.getUniqueId());
        if (a == null || a.getState() != LuckyPillarArena.State.RUNNING) {
            return;
        }
        if (a.getPlayers().contains(victim.getUniqueId()) && a.getPlayers().contains(damager.getUniqueId())) {
            event.setCancelled(!plugin.getConfig().getBoolean("pvp", true));
        }
    }

    /** 取造成伤害的玩家：直接攻击或箭矢/投掷物射手。 */
    private Player asPlayer(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    /** 场内不点火（火蔓延由 GameWorlds 关闭，这里拦爆炸/岩浆引燃的兜底）。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getCause() == BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL) {
            return; // 玩家自己点的火属于玩法
        }
        if (plugin.getManager().runningArenaIn(event.getBlock().getWorld()) == null) {
            return;
        }
        event.setCancelled(true);
    }

    /** 玩家放的方块进本局方块集合（缩圈只吃这个集合）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        plugin.getManager().trackBlock(event.getBlockPlaced());
    }

    /** 倒水 / 倒岩浆：流体方块进本局方块集合。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        plugin.getManager().trackBlock(target);
    }

    /** 流体蔓延（水/岩浆流出来的格子）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        plugin.getManager().trackBlock(event.getBlock());
    }

    /** 流体流向其他方块（BlockFromTo）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent event) {
        plugin.getManager().trackBlock(event.getToBlock());
    }

    /** 结冰 / 生成雪等方块变化。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        plugin.getManager().trackBlock(event.getBlock());
    }

}
