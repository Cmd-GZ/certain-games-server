package com.landclaim;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * LandClaim 的对局会话（D22：会话层统一按场地键索引，单图游戏只有一个键）。
 */
final class LandClaimSession extends GameSession {

    private final LandClaimManager manager;

    LandClaimSession(LandClaimManager manager, String arenaName) {
        super("landclaim", arenaName);
        this.manager = manager;
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
        manager.quit(player);
    }

    /** 掉落：只禁锄头 / 开始纸 / 回城床（原 listener 里那段 @EventHandler）。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        org.bukkit.inventory.ItemStack item = event.getItemDrop().getItemStack();
        if (manager.isHoe(item) || manager.isStartItem(item) || manager.isLeaveItem(item)) {
            event.setCancelled(true);
        }
    }
}
