package com.hotpotato;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import com.hub.util.Chat;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.Material;

/**
 * HotPotato 的对局会话（D22：**每个场地一个会话**，多图游戏照 TntRun 的写法）。
 */
final class HotPotatoSession extends GameSession {

    private final HotPotatoManager manager;

    HotPotatoSession(HotPotatoManager manager, String arenaName) {
        super("hotpotato", arenaName);
        this.manager = manager;
        // I3 C1：手上拿着炸弹的人不许把 TNT 放下（原 onTntPlace 的逐字等价搬运）
        arbiter().on(com.hub.session.EventKind.BLOCK_PLACE, ctx -> {
            if (ctx.block() == null || ctx.block().getType() != org.bukkit.Material.TNT) {
                return;
            }
            Player p = ctx.player();
            if (p == null) {
                return;
            }
            String roomArena = manager.arenaOf(p.getUniqueId());
            if (roomArena == null) {
                return;
            }
            HotPotatoArena a = manager.getArena(roomArena);
            if (a == null || a.getState() != HotPotatoArena.State.RUNNING
                    || !a.getPlayers().contains(p.getUniqueId())
                    || !a.isBombHolder(p.getUniqueId())) {
                return;
            }
            ctx.cancel();
            p.sendMessage("§c烫手山芋不能放置！把它传给其他玩家吧！");
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
        manager.doEndGame(arenaName());
    }

    // ---------- I3 A 批：从 listener 搬过来的处理器 ----------

    @Override
    protected void onPlayerQuit(Player player) {
        manager.handleQuit(player);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        String arenaName = manager.arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        HotPotatoArena a = manager.getArena(arenaName);
        if (a == null || a.getState() != HotPotatoArena.State.RUNNING) {
            return;
        }
        if (!a.getPlayers().contains(p.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.teleport(a.getCenter());
    }

    /** 掉落：手上拿着炸弹的人不许丢（否则就等于把山芋扔地上了）——原 listener 里那段 @EventHandler。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        if (event.getItemDrop().getItemStack().getType() != Material.TNT) {
            return;
        }
        Player p = event.getPlayer();
        String arenaName = manager.arenaOf(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        HotPotatoArena a = manager.getArena(arenaName);
        if (a == null || a.getState() != HotPotatoArena.State.RUNNING
                || !a.getPlayers().contains(p.getUniqueId())
                || !a.isBombHolder(p.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        p.sendMessage("§c烫手山芋不能丢弃！把它传给其他玩家吧！");
    }

    /** 右键开始纸。 */
    @Override
    protected void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) return;
        if (item.getType() == Material.PAPER) {
            manager.handleStartPaper(event.getPlayer());
            return;
        }
        if (item.getType() == Material.RED_BED) {
            manager.handleLeaveBed(event.getPlayer());
        }
    }
}
