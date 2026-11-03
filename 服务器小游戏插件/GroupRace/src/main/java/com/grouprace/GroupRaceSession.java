package com.grouprace;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * 群友竞速的对局会话（D22：会话层统一按场地键索引，单图游戏只有一个键）。
 */
final class GroupRaceSession extends GameSession {

    private final GroupRaceManager manager;

    GroupRaceSession(GroupRaceManager manager, String arenaName) {
        super("grouprace", arenaName);
        this.manager = manager;

        // I3 C1（区域裁决）：本局禁改方块。原来 listener 里的 BlockBreak/BlockPlace 两个处理器
        // 都是"玩家在本局 → 取消"，框架只把**归属本局**的这类事件派发过来，所以这里直接取消。
        arbiter()
                .on(com.hub.session.EventKind.BLOCK_BREAK, ctx -> ctx.cancel())
                .on(com.hub.session.EventKind.BLOCK_PLACE, ctx -> ctx.cancel());
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

    /** 掉落：只禁"回城床 / 开始纸"（原 listener 里那段 @EventHandler）。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!manager.isInGame(event.getPlayer())) {
            return;
        }
        org.bukkit.inventory.ItemStack item = event.getItemDrop().getItemStack();
        if (manager.isReturnItem(item) || manager.isStartItem(item)) {
            event.setCancelled(true);
        }
    }
}
