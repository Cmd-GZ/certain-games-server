package com.colorparty;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
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
}
