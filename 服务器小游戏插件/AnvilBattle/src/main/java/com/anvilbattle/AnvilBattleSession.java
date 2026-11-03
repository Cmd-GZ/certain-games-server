package com.anvilbattle;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.entity.FallingBlock;
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
 * 铁砧掘战的对局会话（D22：会话层统一按场地键索引，单图游戏只有一个键）。
 * 样板：{@code MemoryFlipSession} / {@code TntRunSession}。
 */
final class AnvilBattleSession extends GameSession {

    private final AnvilBattleManager manager;

    AnvilBattleSession(AnvilBattleManager manager, String arenaName) {
        super("anvilbattle", arenaName);
        this.manager = manager;
        // 本局禁止丢任何物品（原 listener 里那段 @EventHandler，逐字等价）
        rules().noItemDrop(true);

        // I3 C1（区域裁决）：本局禁改方块，且**只在自家场地范围内**表态。
        arbiter()
                .on(com.hub.session.EventKind.BLOCK_BREAK,
                        ctx -> cancelIfInArena(ctx, ctx.block().getLocation()))
                .on(com.hub.session.EventKind.BLOCK_PLACE,
                        ctx -> cancelIfInArena(ctx, ctx.block().getLocation()));
        enterWaiting();
    }

    void start() {
        enterRunning();
    }

    @Override
    protected void onStart() {
        // 本轮不改玩法：计时/传送仍由 manager 负责
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
        if (manager.isPlaying(player)) {
            manager.handleDeath(player);
        } else {
            player.setHealth(20.0);
            player.setFoodLevel(20);
        }
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInGame(player)) {
            return;
        }
        Location target = manager.isWaiting(player) || manager.isPlaying(player)
                ? manager.arena().getLobby()
                : manager.spectatorSpot();
        if (target != null) {
            event.setRespawnLocation(target);
        }
    }

    /** I3 C1：本局禁改方块 —— 只在自家场地范围内表态（原 listener 里的 `arena().contains(...)` 判断）。 */
    private void cancelIfInArena(com.hub.session.EventContext ctx, org.bukkit.Location where) {
        if (ctx.player() != null && manager.isInGame(ctx.player())
                && manager.arena().contains(where)) {
            ctx.cancel();
        }
    }

    /** 等待室：右键床退出、右键开始纸由管理员开局。 */
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
        if (event instanceof EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player player)) {
            return;
        }
        if (!manager.isInGame(player)) {
            return;
        }
        e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent by
                && by.getDamager() instanceof FallingBlock falling
                && manager.isOurAnvil(falling.getUniqueId())) {
            manager.handleAnvilHit(player);
        }
                return;
        }
        if (event instanceof EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof FallingBlock falling
                && manager.isOurAnvil(falling.getUniqueId())) {
            e.setCancelled(true);
            manager.onAnvilLand(falling);
        }
                return;
        }
        if (event instanceof PlayerMoveEvent e) {
        if (e.getTo() != null) {
            manager.handleMove(e.getPlayer(), e.getTo());
        }
                return;
        }
    }
}
