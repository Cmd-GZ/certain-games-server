package com.football;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;

/** 绿茵足球的会话（D22：每个场地一个会话）。 */
final class FootballSession extends GameSession {

    private final FootballManager manager;

    FootballSession(FootballManager manager, String arenaName) {
        super("football", arenaName);
        this.manager = manager;
        // I3 C1：禁破坏本场范围内的方块（`onPlace` 是"足球道具不能当方块放"，属玩法，留在 listener）
        arbiter().on(com.hub.session.EventKind.BLOCK_BREAK, ctx -> {
            if (ctx.player() == null) {
                return;
            }
            String name = manager.arenaOf(ctx.player().getUniqueId());
            if (name == null) {
                return;
            }
            FootballArena a = manager.getArena(name);
            if (a == null || !a.isReady()) {
                return;
            }
            if (a.contains(ctx.block().getLocation())) {
                ctx.cancel();
            }
        });
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
        manager.doEndMatch(arenaName());
    }

    // ---------- I3 A 批：从 listener 搬过来的处理器 ----------

    @Override
    protected void onPlayerQuit(Player player) {
        manager.handleQuit(player);
    }

    /** 掉落：只禁"足球道具"（带 tag 的那几件），并给玩家一句提示（原 listener 里那段 @EventHandler）。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        if (manager.items().tag(event.getItemDrop().getItemStack()) != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c足球道具不能丢弃！");
        }
    }
}
