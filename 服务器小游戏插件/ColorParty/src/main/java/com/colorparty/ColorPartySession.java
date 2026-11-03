package com.colorparty;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 色盲派对的对局会话（D22：**每个场地一个会话**）。
 */
final class ColorPartySession extends GameSession {

    private final ColorPartyManager manager;

    ColorPartySession(ColorPartyManager manager, String arenaName) {
        super("colorparty", arenaName);
        this.manager = manager;
        // I3 C1：禁改方块（OP 豁免；玩家在本场，或方块落在**正在跑的场地**里都算）
        arbiter()
                .on(com.hub.session.EventKind.BLOCK_BREAK, this::cancelBlockChange)
                .on(com.hub.session.EventKind.BLOCK_PLACE, this::cancelBlockChange);
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
        manager.doEndGame(arenaName());
    }

    // ---------- I3 A 批：从 listener 搬过来的处理器 ----------

    @Override
    protected void onPlayerQuit(Player player) {
        manager.handleQuit(player);
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        String arenaName = manager.arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        ColorPartyArena a = manager.getArena(arenaName);
        if (a == null || !a.getPlayers().contains(p.getUniqueId())) {
            return;
        }
        Location loc = a.getState() == ColorPartyArena.State.RUNNING
                ? a.getSpawn()
                : (a.getLobbyPoint() != null ? a.getLobbyPoint() : a.getSpawn());
        if (loc != null) {
            event.setRespawnLocation(loc);
        }
    }

    /** I3 C1：本场禁改方块（原 `onBlockBreak`/`onBlockPlace` 的逐字等价搬运）。 */
    private void cancelBlockChange(com.hub.session.EventContext ctx) {
        if (ctx.player() != null && ctx.player().isOp()) {
            return;   // OP 豁免：主城建造/清理
        }
        if (ctx.player() != null && manager.arenaOf(ctx.player().getUniqueId()) != null
                || (ctx.block() != null && manager.inRunningArena(ctx.block()))) {
            ctx.cancel();
        }
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------

    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof PlayerMoveEvent e) {
        manager.plugin().getManager().handleMove(e.getPlayer());
                return;
        }
        if (event instanceof EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        if (manager.plugin().getManager().arenaOf(p.getUniqueId()) != null) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof PlayerInteractEvent e) {
        Player p = e.getPlayer();
        // 配置模式：工具交互
        if (manager.plugin().getManager().isInSetup(p.getUniqueId())) {
            if (manager.plugin().getManager().handleSetupTool(p, e.getAction(), e.getClickedBlock())) {
                e.setCancelled(true);
                return;
            }
        }
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = e.getItem();
        if (item == null) {
            return;
        }
        if (item.getType() == Material.PAPER) {
            e.setCancelled(true);
            manager.plugin().getManager().handleStartPaper(p);
        } else if (item.getType() == Material.RED_BED) {
            e.setCancelled(true);
            manager.plugin().getManager().handleLeaveBed(p);
        }
                return;
        }
    }
}
