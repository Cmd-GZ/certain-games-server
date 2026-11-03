package com.hotpotato;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Sign;

/** 拿 TNT 打人传 TNT（不掉血但有正常 PvP 受击反馈）、对局免伤、离开范围直接淘汰、退出清理。 */
public class HotPotatoListener implements Listener {

    private final HotPotatoPlugin plugin;

    public HotPotatoListener(HotPotatoPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 对局中的存活玩家免疫一切伤害（含虚空、掉落、岩浆等无实体来源伤害），
     * 只能被 TNT 回合爆炸或离开区域淘汰。EntityDamageByEntityEvent 只覆盖实体造成的伤害，
     * 虚空/掉落/岩浆等伤害必须在这里单独拦截。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnyDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        String vArena = plugin.getManager().arenaOf(victim.getUniqueId());
        if (vArena == null) {
            return;
        }
        HotPotatoArena arena = plugin.getManager().getArena(vArena);
        if (arena == null) {
            return;
        }
        if (arena.getState() == HotPotatoArena.State.RUNNING && arena.getPlayers().contains(victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * 伤害处理：
     * 1. 对局中的存活玩家免疫实体造成的伤害（怪物、PvP 等）；
     * 2. 同场玩家之间互打 → 双方都不掉血，但受击方会有正常 PvP 的受击反馈（红屏 + 击退 + 音效）；
     *    只有"手里拿着 TNT 的持弹者"打人才会真正传递 TNT（判定在 HotPotatoManager#handleAttack）。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        String vArena = plugin.getManager().arenaOf(victim.getUniqueId());
        if (vArena == null) {
            return; // 受害者不在场内，按服务器默认规则处理
        }
        HotPotatoArena arena = plugin.getManager().getArena(vArena);
        if (arena == null) {
            return;
        }
        // 对局中的存活玩家：免疫一切伤害，只能被 TNT 淘汰
        if (arena.getState() == HotPotatoArena.State.RUNNING && arena.getPlayers().contains(victim.getUniqueId())) {
            event.setCancelled(true);
        }
        // 同场玩家之间互打 → 尝试传递 TNT（不造成伤害）
        if (event.getDamager() instanceof Player damager) {
            String dArena = plugin.getManager().arenaOf(damager.getUniqueId());
            if (dArena != null && dArena.equals(vArena)) {
                event.setCancelled(true);
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
                plugin.getManager().handleAttack(damager, victim);
            }
        }
    }

    /** 对局中的存活玩家不会死亡（防 /kill 等绕过伤害事件的击杀）。 */
    /** 离开圈定区域 → 直接淘汰。 */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        // 跨世界瞬间不能比距离：别的小游戏（例如雪塔大作战结束）在自己的移动事件里把玩家传回主城后，
        // event.getFrom() 还是旧世界坐标，p.getLocation() 已在新世界 → distanceSquared 抛
        // IllegalArgumentException（Cannot measure distance between ...）。跳过即可，出圈另有轮询兜底。
        if (!p.getWorld().equals(event.getFrom().getWorld())) {
            return;
        }
        if (p.getLocation().distanceSquared(event.getFrom()) < 0.0001) {
            return;
        }
        plugin.getManager().handleMove(p);
    }

    /** 退出清理。 */
    /** 加入服务器：清理观战模式残留（防止上一局淘汰后重进仍是 SPECTATOR 导致"无敌"）。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getManager().handleJoin(event.getPlayer());
    }

    /** 右键开始纸。 */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) return;
        if (item.getType() == Material.PAPER) {
            plugin.getManager().handleStartPaper(event.getPlayer());
            return;
        }
        if (item.getType() == Material.RED_BED) {
            plugin.getManager().handleLeaveBed(event.getPlayer());
        }
    }

    /** 配置模式道具 + 加入告示牌（绑定/点击加入）。 */
    @EventHandler
    public void onSetupAndSigns(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        // 配置模式：工具交互
        if (plugin.getManager().isInSetup(p.getUniqueId())) {
            if (plugin.getManager().handleSetupTool(p, event.getAction(), event.getClickedBlock())) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (!(event.getClickedBlock().getState() instanceof Sign)) {
            return;
        }
        // 待绑定加入牌
        if (plugin.getManager().handleSignBind(p, event.getClickedBlock().getLocation())) {
            event.setCancelled(true);
            return;
        }
        // 点击加入牌 → 直接加入
        if (plugin.getManager().isJoinSign(event.getClickedBlock().getLocation())) {
            event.setCancelled(true);
            plugin.getManager().onSignClick(p, event.getClickedBlock().getLocation());
        }
    }

    /** 持有 TNT 的玩家不能放置 TNT。 */
    /** 持有 TNT 的玩家不能丢弃 TNT。 */
    /** 玩家是否在运行中的对局里且正持有 TNT。 */
    private boolean isTntHolder(Player p) {
        String arenaName = plugin.getManager().arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return false;
        }
        HotPotatoArena a = plugin.getManager().getArena(arenaName);
        return a != null
                && a.getState() == HotPotatoArena.State.RUNNING
                && a.getPlayers().contains(p.getUniqueId())
                && a.isBombHolder(p.getUniqueId());
    }

    /** Paper 26.2：右键告示牌先触发此事件，cancel 后 PlayerInteractEvent 不再触发，所以在这里直接处理。 */
    @EventHandler
    public void onSignOpen(PlayerSignOpenEvent event) {
        if (event.getSign() != null && plugin.getManager().isJoinSign(event.getSign().getLocation())) {
            event.setCancelled(true);
            plugin.getManager().onSignClick(event.getPlayer(), event.getSign().getLocation());
        }
    }

    /** 场内玩家不掉饥饿（等待中或对局中：arenaOf 在加入时就登记）。 */
}
