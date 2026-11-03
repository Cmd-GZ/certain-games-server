package com.digdown;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * DigToBottom 的对局会话（D22：**每个场地一个会话**，多图游戏照 TntRun 的写法）。
 */
final class DigToBottomSession extends GameSession {

    private final DigToBottomManager manager;

    DigToBottomSession(DigToBottomManager manager, String arenaName) {
        // R1（单点事实源）：这里的 key 必须与 HubCore 的 registry / games.yml / maps.yml 一致。
        // **曾经是 "digdown"**：配置与注册表重命名成 digtobottom 之后，插件内部还留着旧 key，
        // 后果是派对委派时 GameDelegation 匹配不上 → 会话挂不到父对局 → 加入被 I1 拒绝，
        // 而且那条路径没有提示，表现就是"这个游戏在房间里怎么都开不了局"。
        super("digtobottom", arenaName);
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
        manager.doEndGame(arenaName());
    }

    // ---------- I3 A 批：从 listener 搬过来的处理器 ----------

    @Override
    protected void onPlayerQuit(Player player) {
        manager.handleQuit(player);
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        DigToBottomArena arena = manager.getArena(manager.arenaOf(player.getUniqueId()));
        if (arena == null) {
            return;
        }
        Location spot = arena.getLobby() != null ? arena.getLobby() : arena.getCenter();
        if (spot != null) {
            event.setRespawnLocation(spot);
        }
    }

    /** 掉落：对局中全禁；不在跑也禁自己的挖掘工具（原 listener 里那段 @EventHandler）。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        DigToBottomArena arena = manager.getArena(manager.arenaOf(player.getUniqueId()));
        if (arena == null) {
            return;
        }
        if (arena.getState() == DigToBottomArena.State.RUNNING
                || manager.isTool(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }
}
