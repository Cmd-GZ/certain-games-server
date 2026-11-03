package com.knockback;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import com.hub.util.Chat;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;

/** 击退大作战的会话（D22：会话层按场地键索引，单图游戏只有一个键）。 */
final class KnockbackSession extends GameSession {

    private final KnockbackManager manager;

    KnockbackSession(KnockbackManager manager, String arenaName) {
        super("knockback", arenaName);
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

    /** 右键：返回大厅床、管理员开始纸、风弹。 */
    @Override
    protected void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
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
            if (!manager.isAdmin(player)) {
                Chat.send(player, "&c只有管理员可以开始游戏。");
            } else if (manager.isWaiting(player)) {
                manager.startGame(true);
            } else {
                Chat.send(player, "&e你不在等待队列中。");
            }
            return;
        }
        if (manager.isWindCharge(item)) {
            event.setCancelled(true);
            manager.useWindCharge(player);
        }
    }
}
