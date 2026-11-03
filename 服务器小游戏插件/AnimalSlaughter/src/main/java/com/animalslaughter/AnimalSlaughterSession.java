package com.animalslaughter;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 动物屠宰的对局会话（D22：会话层统一按场地键索引，单图游戏只有一个键）。
 * 样板：{@code MemoryFlipSession}。
 */
final class AnimalSlaughterSession extends GameSession {

    private final AnimalSlaughterManager manager;

    AnimalSlaughterSession(AnimalSlaughterManager manager, String arenaName) {
        super("animalslaughter", arenaName);
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
        player.setHealth(20.0);
        player.setFoodLevel(20);
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInGame(player)) {
            return;
        }
        Location target = manager.arena().getLobby();
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
}
