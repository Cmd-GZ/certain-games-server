package com.snakeparty;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.block.Action;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 蛇行派对的会话（D22：会话层按场地键索引，单图游戏只有一个键）。
 */
final class SnakePartySession extends GameSession {

    private final SnakePartyManager manager;

    SnakePartySession(SnakePartyManager manager, String arenaName) {
        super("snakeparty", arenaName);
        this.manager = manager;
        // 本局禁止丢任何物品（原 listener 里那段 @EventHandler，逐字等价）
        rules().noItemDrop(true);

        // I3 C1（区域裁决）：本局禁改方块，且**只在自家场地范围内**表态。
        arbiter()
                .on(com.hub.session.EventKind.BLOCK_BREAK, ctx -> cancelIfInArena(ctx, ctx.block()))
                .on(com.hub.session.EventKind.BLOCK_PLACE, ctx -> cancelIfInArena(ctx, ctx.block()));
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
    @SuppressWarnings("deprecation")
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!manager.isInGame(player)) {
            return;
        }
        event.setCancelled(true);
        player.setHealth(20.0);
        player.setFoodLevel(20);
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInGame(player)) {
            return;
        }
        Location target = manager.isWaiting(player) || manager.isPlaying(player)
                ? manager.arena().getLobby()
                : manager.arena().spectatorSpot();
        if (target != null) {
            event.setRespawnLocation(target);
        }
    }

    /** I3 C1：本局禁改方块 —— 只在自家场地范围内表态（原 listener 里的 `arena().contains(...)` 判断）。 */
    private void cancelIfInArena(com.hub.session.EventContext ctx, org.bukkit.block.Block block) {
        if (ctx.player() != null && manager.isInGame(ctx.player())
                && manager.arena().contains(block)) {
            ctx.cancel();
        }
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
        if (event instanceof PlayerMoveEvent e) {
        manager.handleMove(e);
                return;
        }
        if (event instanceof PlayerToggleSprintEvent e) {
        if (e.isSprinting() && manager.isInGame(e.getPlayer())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof EntityDamageEvent e) {
        if (e.getEntity() instanceof Player player && manager.isInGame(player)) {
            e.setCancelled(true);
        }
                return;
        }
    }
}
