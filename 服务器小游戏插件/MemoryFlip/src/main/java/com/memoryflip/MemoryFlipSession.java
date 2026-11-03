package com.memoryflip;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.entity.ItemFrame;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 记忆翻牌的对局会话 —— 第 3 个接入 {@code com.hub.session} 的游戏
 * （样板见 {@code HillKingSession}）。
 *
 * <p>它只负责把框架的状态机接上：创建即 WAITING，开局推 RUNNING，
 * 收场统一走 {@link #onEnd(EndReason)} → {@code manager.doEndGame}。
 * 玩法逻辑仍留在 manager 里（这一轮不改玩法，只换生命周期外壳）。
 */
final class MemoryFlipSession extends GameSession {

    private final MemoryFlipManager manager;

    MemoryFlipSession(MemoryFlipManager manager, String arenaName) {
        super("memoryflip", arenaName);
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

    /** WAITING → RUNNING（状态只能由会话自己推进，见 I5）。 */
    void start() {
        enterRunning();
    }

    @Override
    protected void onStart() {
        // 本轮不改玩法：起计时器/传送仍由 manager 负责
    }

    @Override
    protected void onEnd(EndReason reason) {
        manager.doEndGame(reason);
    }

    // ---------- I3 A 批：从 listener 搬过来的处理器 ----------

    @Override
    @SuppressWarnings("deprecation")
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!manager.isInGame(player)) {
            return;
        }
        event.setCancelled(true);
        player.setHealth(20.0);
        player.setFoodLevel(20);
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInGame(player)) {
            return;
        }
        Location target = manager.arena().getLobby();
        if (target != null) {
            event.setRespawnLocation(target);
        }
    }

    @Override
    protected void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
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
            manager.handleStartPaper(player);
        }
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------

    private final Map<UUID, Integer> lastClickTick = new HashMap<>();

    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof PlayerInteractAtEntityEvent e) {
        if (!(e.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }
        e.setCancelled(true);
        Player player = e.getPlayer();
        if (!manager.isPlaying(player)) {
            return;
        }
        int tick = player.getTicksLived();
        Integer last = lastClickTick.put(player.getUniqueId(), tick);
        if (last != null && last == tick) {
            return;
        }
        manager.handleFrameClick(player, frame);
                return;
        }
        if (event instanceof EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof ItemFrame frame)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getDamager() instanceof Player player) || !manager.isPlaying(player)) {
            return;
        }
        int tick = player.getTicksLived();
        Integer last = lastClickTick.put(player.getUniqueId(), tick);
        if (last != null && last == tick) {
            return;
        }
        manager.handleFrameClick(player, frame);
                return;
        }
        if (event instanceof EntityDamageEvent e) {
        if (e.getEntity() instanceof Player player && manager.isInGame(player)) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof PlayerQuitEvent e) {
        lastClickTick.remove(e.getPlayer().getUniqueId());
        manager.handleQuit(e.getPlayer());
                return;
        }
    }
}
