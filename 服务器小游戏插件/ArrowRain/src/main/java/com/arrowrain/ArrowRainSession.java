package com.arrowrain;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Arrow;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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

/** ArrowRain 的对局会话（D22：会话层按场地键索引）。 */
final class ArrowRainSession extends GameSession {

    private final ArrowRainManager manager;

    ArrowRainSession(ArrowRainManager manager, String arenaName) {
        super("arrowrain", arenaName);
        this.manager = manager;
        // 本局禁止丢任何物品（原 listener 里那段 @EventHandler，逐字等价）
        rules().noItemDrop(true);
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
        if (!manager.isPlaying(player)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        manager.eliminate(player, "被其他玩家击杀");
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInGame(player)) {
            return;
        }
        Location lobby = manager.arena().lobby();
        if (lobby != null) {
            event.setRespawnLocation(lobby);
        }
        manager.spectateNextTick(player);
    }

    @Override
    protected void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!manager.isWaiting(player)) {
            return;
        }
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
            if (player.hasPermission("arrowrain.admin")) {
                manager.startGame(true);
            }
        }
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------


    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        if (!manager.isInGame(victim)) {
            if (e instanceof EntityDamageByEntityEvent byEntity
                    && byEntity.getDamager() instanceof Player attacker
                    && manager.isInGame(attacker)) {
                e.setCancelled(true);
            }
            return;
        }
        if (e instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Arrow arrow && manager.isRainArrow(arrow)) {
                e.setCancelled(true);
                return;
            }
            if (damager instanceof Player attacker && manager.isPlaying(attacker)) {
                if (manager.isPlaying(victim) && manager.pvpEnabled()) {
                    e.setCancelled(false);
                } else {
                    e.setCancelled(true);
                }
                return;
            }
        }
        e.setCancelled(true);
                return;
        }
        if (event instanceof ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Arrow arrow) || !manager.isRainArrow(arrow)) {
            return;
        }
        Entity hit = e.getHitEntity();
        if (hit instanceof Player player) {
            manager.hitByArrow(player);
            manager.forgetArrow(arrow);
            arrow.remove();
            return;
        }
        // 命中方块：让箭插在平台上，直到本轮箭雨结束由 removeArrows 统一清理（不可捡起）
                return;
        }
        if (event instanceof BlockPlaceEvent e) {
        if (manager.isInGame(e.getPlayer())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof BlockBreakEvent e) {
        if (manager.isInGame(e.getPlayer())) {
            e.setCancelled(true);
        }
                return;
        }
    }
}
