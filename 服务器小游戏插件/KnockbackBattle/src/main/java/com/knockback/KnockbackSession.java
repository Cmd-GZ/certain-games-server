package com.knockback;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;

/** 击退大作战的会话（D22：会话层按场地键索引，单图游戏只有一个键）。 */
final class KnockbackSession extends GameSession {

    private final KnockbackManager manager;

    KnockbackSession(KnockbackManager manager, String arenaName) {
        super("knockback", arenaName);
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
        manager.handleQuit(player);
    }

    /** 掉落：只禁棍 / 风弹 / 回城床 / 开始纸（原 listener 里那段 @EventHandler）。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!manager.isInGame(event.getPlayer())) {
            return;
        }
        org.bukkit.inventory.ItemStack item = event.getItemDrop().getItemStack();
        if (manager.isStick(item) || manager.isWindCharge(item)
                || manager.isReturnItem(item) || manager.isStartItem(item)) {
            event.setCancelled(true);
        }
    }
}
