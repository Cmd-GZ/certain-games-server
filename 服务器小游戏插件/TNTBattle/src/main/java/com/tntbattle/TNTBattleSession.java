package com.tntbattle;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/** TNT 大战的会话（D22：会话层按场地键索引；本游戏目前是单场地）。 */
final class TNTBattleSession extends GameSession {

    private final TNTBattleManager manager;

    TNTBattleSession(TNTBattleManager manager, String arenaName) {
        super("tntbattle", arenaName);
        this.manager = manager;
        // 框架默认"局内不掉饥饿"（与其它 24 个游戏的处理器一致），但**本局是例外**：
        // TNT 大战在对局中故意允许掉饥饿（玩家吃牛排回血是玩法的一部分），
        // 所以这里显式关掉这条环境规则，仍旧由 TNTBattleListener.onHunger 按
        // "isPlaying && !isRunning" 自己分流。
        rules().noHunger(false);
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
    // 注意：onHunger（等待室不饿/局内允许掉饥饿）与 onQuit（要清 listener 里的 lastUseTick 冷却表）
    // 仍留在 listener 里 —— 前者是本局特有的语义，后者要碰游戏自己的局部状态。

    @Override
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!manager.isPlaying(player) || manager.isEliminated(player)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(false);
        manager.eliminate(player, "死亡");
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isPlaying(player) || !manager.isEliminated(player) || manager.isRestoring()) {
            return;
        }
        Location spectator = manager.arena().spectatorLocation();
        if (spectator != null) {
            event.setRespawnLocation(spectator);
        }
        manager.spectateNextTick(player);
    }

    /** 掉落：炸弹/开始纸/回城床/音乐盒/特殊道具一律不许丢（原 listener 里那段 @EventHandler）。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        if (manager.isTntItem(event.getItemDrop().getItemStack())
                || manager.isStartItem(event.getItemDrop().getItemStack())
                || manager.isReturnItem(event.getItemDrop().getItemStack())
                || manager.isMusicItem(event.getItemDrop().getItemStack())
                || manager.isSpecialItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }
}
