package com.anvilbattle;

import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

/** 铁砧掘战事件：铁砧命中/落地、虚空淘汰、地图保护、等待室道具。 */
public final class AnvilBattleListener implements Listener {

    private final AnvilBattleManager manager;

    public AnvilBattleListener(AnvilBattleManager manager) {
        this.manager = manager;
    }

    /**
     * 场内的伤害全部取消；如果伤害来源是本插件生成的铁砧，直接淘汰被砸中的玩家。
     * 其它爆炸/摔落/近战等一律不掉血。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!manager.isInGame(player)) {
            return;
        }
        event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent by
                && by.getDamager() instanceof FallingBlock falling
                && manager.isOurAnvil(falling.getUniqueId())) {
            manager.handleAnvilHit(player);
        }
    }

    /** 铁砧落地：取消变成铁砧方块，改为破坏周围方块。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvilLand(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof FallingBlock falling
                && manager.isOurAnvil(falling.getUniqueId())) {
            event.setCancelled(true);
            manager.onAnvilLand(falling);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null) {
            manager.handleMove(event.getPlayer(), event.getTo());
        }
    }

}
