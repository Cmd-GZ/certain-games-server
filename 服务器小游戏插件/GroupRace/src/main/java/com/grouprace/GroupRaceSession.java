package com.grouprace;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.entity.Pig;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import com.hub.util.Chat;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
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

    /** 等待室：床返回大厅，纸（仅管理员）开始游戏。 */
    @Override
    protected void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        Player player = event.getPlayer();
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
        }
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------


    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof PlayerInputEvent e) {
        manager.handleInput(e.getPlayer(), e);
                return;
        }
        if (event instanceof PlayerToggleSneakEvent e) {
        manager.handleSneak(e.getPlayer(), e.isSneaking());
                return;
        }
        if (event instanceof EntityDismountEvent e) {
        if (e.getEntity() instanceof Player player
                && manager.shouldKeepMounted(player, e.getDismounted())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof EntityDamageEvent e) {
        if (e.getEntity() instanceof Player player && manager.isInGame(player)) {
            e.setCancelled(true);
            return;
        }
        if (e.getEntity() instanceof Pig pig
                && (manager.isRacePig(pig) || manager.isLobbyPig(pig))) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof PlayerInteractEntityEvent e) {
        if (manager.isRacePig(e.getRightClicked())) {
            e.setCancelled(true);
        }
                return;
        }
    }
}
