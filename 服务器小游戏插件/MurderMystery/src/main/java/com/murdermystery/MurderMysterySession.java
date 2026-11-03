package com.murdermystery;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/** MurderMystery 的对局会话（D22：每个场地一个会话）。 */
final class MurderMysterySession extends GameSession {

    private final MurderMysteryManager manager;

    MurderMysterySession(MurderMysteryManager manager, String arenaName) {
        super("murdermystery", arenaName);
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
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        String arenaName = manager.arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        MurderMysteryArena a = manager.getArena(arenaName);
        if (a == null || a.getState() != MurderMysteryArena.State.RUNNING) {
            return;
        }
        event.setCancelled(true);
        if (a.isMurderer(p.getUniqueId())) {
            manager.murdererEliminated(a, p);
        } else {
            // 正常路径已被拦截（环境伤/友伤都取消），这里兜底防 /kill 等
            p.setHealth(20);
            p.setFoodLevel(20);
            p.teleport(a.getCenter());
        }
    }

    /** 掉落：四段分流（淘汰全禁 / 等待全禁 / 非运行放行 / 运行期只禁商店与职业道具）——原 listener 里那段。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        String arenaName = manager.arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        MurderMysteryArena a = manager.getArena(arenaName);
        if (a == null) {
            return;
        }
        if (a.getEliminated().contains(p.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (a.getState() == MurderMysteryArena.State.WAITING) {
            event.setCancelled(true);
            return;
        }
        if (a.getState() != MurderMysteryArena.State.RUNNING) {
            return;
        }
        org.bukkit.inventory.ItemStack dropped = event.getItemDrop().getItemStack();
        if (manager.isShopItem(dropped)) {
            event.setCancelled(true);
            manager.notifyShopLocked(p);
            return;
        }
        // 职业装备（杀手铁剑/追踪指南针、猎人弓）局内一律不可丢弃：
        // 落到地上别人能捡，等于把职业武器送给对手（铁剑被捡走还能当飞剑扔）。
        if (manager.isRoleItem(dropped)) {
            event.setCancelled(true);
            manager.notifyRoleItemLocked(p);
        }
    }
}
