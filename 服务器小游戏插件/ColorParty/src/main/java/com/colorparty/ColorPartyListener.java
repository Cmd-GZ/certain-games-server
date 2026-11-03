package com.colorparty;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

/** 淘汰线检测、场内免伤/免饥饿、地板保护、道具交互、配置模式工具。 */
public class ColorPartyListener implements Listener {

    private final ColorPartyPlugin plugin;

    public ColorPartyListener(ColorPartyPlugin plugin) {
        this.plugin = plugin;
    }

    /** 对局中移动：淘汰线检测。 */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        plugin.getManager().handleMove(event.getPlayer());
    }

    /** 重生：仍在场中的玩家回等待点 / 中心出生点。 */
    /** 场内玩家（等待中或对局中）不吃任何伤害：淘汰只靠"掉出地板"，不靠打死。 */
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) {
            return;
        }
        if (plugin.getManager().arenaOf(p.getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }

    /** 场内玩家不掉饥饿（全局约定：各插件 Listener 在自己的域内取消 FoodLevelChangeEvent）。 */
    /** 对局中保护地板/面板：场内玩家不许挖/放，路人在彩色地板上挖/放也拦下（OP 不拦，方便调试）。 */
    /** 配置模式工具 + 开始纸 / 退出床。 */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        // 配置模式：工具交互
        if (plugin.getManager().isInSetup(p.getUniqueId())) {
            if (plugin.getManager().handleSetupTool(p, event.getAction(), event.getClickedBlock())) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        if (item.getType() == Material.PAPER) {
            event.setCancelled(true);
            plugin.getManager().handleStartPaper(p);
        } else if (item.getType() == Material.RED_BED) {
            event.setCancelled(true);
            plugin.getManager().handleLeaveBed(p);
        }
    }
}
