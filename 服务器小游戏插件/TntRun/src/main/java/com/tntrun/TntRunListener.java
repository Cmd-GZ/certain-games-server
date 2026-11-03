package com.tntrun;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;

/** 踩雪检测、挖雪给雪球、雪球砸雪块、退出清理。 */
public class TntRunListener implements Listener {

    private final TntRunPlugin plugin;

    public TntRunListener(TntRunPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        plugin.getManager().handleMove(event.getPlayer());
    }

    /** 重生：仍在场地对局/等待中的玩家重生回场地对应点（HubCore 现在不接管游戏内玩家的重生）。 */
    /** 游戏中（RUNNING）关闭摔落伤害，防止从上层掉到下层时摔死。 */
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) {
            return;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        String arenaName = plugin.getManager().arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        TntRunArena a = plugin.getManager().getArena(arenaName);
        if (a != null && a.getState() == TntRunArena.State.RUNNING) {
            event.setCancelled(true);
        }
    }

    /** 挖雪块：对局中（RUNNING）在场地范围内挖掉一格雪块 = 得到 1 个雪球（原版掉落被取消，避免雪球掉地上）。 */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (!plugin.getManager().isPlayingIn(p, event.getBlock().getLocation())) {
            return;
        }
        if (event.getBlock().getType() != Material.SNOW_BLOCK) {
            return;
        }
        event.setDropItems(false);
        plugin.getManager().breakSnow(p, event.getBlock());
    }

    /** 雪球砸中雪块：只让被砸中的那一格消失（不往下连带塌），是否塌陷交给玩家掉进去后触发。 */
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball)) {
            return;
        }
        Block hit = event.getHitBlock();
        if (hit == null || hit.getType() != Material.SNOW_BLOCK) {
            return;
        }
        plugin.getManager().breakSnowByBall(hit);
    }

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
