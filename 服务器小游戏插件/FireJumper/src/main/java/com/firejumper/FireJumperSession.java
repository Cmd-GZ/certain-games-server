package com.firejumper;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** 跃火者的对局会话（D22：会话层按场地键索引）。 */
final class FireJumperSession extends GameSession {

    private final FireJumperManager manager;

    FireJumperSession(FireJumperManager manager, String arenaName) {
        super("firejumper", arenaName);
        this.manager = manager;
        // 本局禁止丢任何物品（原 listener 里那段 @EventHandler，逐字等价）
        rules().noItemDrop(true);

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

    @Override
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!manager.isAlive(player)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        manager.eliminate(player, "死亡");
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInGame(player)) {
            return;
        }
        Location lobby = manager.arena().lobby();
        if (lobby != null) {
            event.setRespawnLocation(lobby);
        }
        manager.spectateNextTick(player);
    }

    @Override
    protected void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!manager.isWaiting(player)) {
            return;
        }
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
            if (player.hasPermission("firejumper.admin")) {
                manager.startGame(true);
            }
        }
    }
}
