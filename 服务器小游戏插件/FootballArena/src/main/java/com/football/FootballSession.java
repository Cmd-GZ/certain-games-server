package com.football;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.block.Sign;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;

/** 绿茵足球的会话（D22：每个场地一个会话）。 */
final class FootballSession extends GameSession {

    private final FootballManager manager;

    FootballSession(FootballManager manager, String arenaName) {
        super("football", arenaName);
        this.manager = manager;
        // I3 C1：禁破坏本场范围内的方块（`onPlace` 是"足球道具不能当方块放"，属玩法，留在 listener）
        arbiter().on(com.hub.session.EventKind.BLOCK_BREAK, ctx -> {
            if (ctx.player() == null) {
                return;
            }
            String name = manager.arenaOf(ctx.player().getUniqueId());
            if (name == null) {
                return;
            }
            FootballArena a = manager.getArena(name);
            if (a == null || !a.isReady()) {
                return;
            }
            if (a.contains(ctx.block().getLocation())) {
                ctx.cancel();
            }
        });
        enterWaiting();
    }

    /**
     * 进入 RUNNING（**幂等**）。
     *
     * <p>为什么加守卫：I5 的状态机对"RUNNING → RUNNING"会抛异常，而"开局命令被调用两次"
     * 是完全可能的（控制台重试、脚本补发、玩家连点开始）。以前这里直接 `enterRunning()`，
     * 第二次就抛 `IllegalStateException: 非法状态转移` → 整个命令报错（实测于 2026-11-06）。
     */
    void start() {
        if (state() == com.hub.session.SessionState.WAITING) {
            enterRunning();
        }
    }

    @Override
    protected void onStart() {
        // 本轮不改玩法
    }

    @Override
    protected void onEnd(EndReason reason) {
        manager.doEndMatch(arenaName());
    }

    // ---------- I3 A 批：从 listener 搬过来的处理器 ----------

    @Override
    protected void onPlayerQuit(Player player) {
        manager.handleQuit(player);
    }

    /** 掉落：只禁"足球道具"（带 tag 的那几件），并给玩家一句提示（原 listener 里那段 @EventHandler）。 */
    @Override
    protected void onPlayerDropItem(PlayerDropItemEvent event) {
        if (manager.items().tag(event.getItemDrop().getItemStack()) != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c足球道具不能丢弃！");
        }
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------

    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return; // 副手挥动不算
        }
        Player p = e.getPlayer();
        FootballItems.Tag tag = manager.plugin().getManager().items().tag(p.getInventory().getItemInMainHand());
        if (tag == null) {
            return;
        }
        switch (tag.kind()) {
            case KICK_FORWARD, KICK_VERTICAL, KICKOFF ->
                    manager.plugin().getManager().handleKick(p, tag.kind(), tag.power());
            default -> {
            }
        }
                return;
        }
        if (event instanceof PlayerInteractEvent e) {
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = e.getItem();
        FootballItems.Tag tag = manager.plugin().getManager().items().tag(item);
        if (tag == null) {
            return;
        }
        e.setCancelled(true);
        Player p = e.getPlayer();
        switch (tag.kind()) {
            case SPRINT -> manager.plugin().getManager().useSprint(p);
            case JUMP -> manager.plugin().getManager().useJump(p);
            case TEAM_RED -> manager.plugin().getManager().pickTeam(p, FootballArena.Team.RED);
            case TEAM_BLUE -> manager.plugin().getManager().pickTeam(p, FootballArena.Team.BLUE);
            case LEAVE_BED -> manager.plugin().getManager().leave(p);
            case START_PAPER -> manager.plugin().getManager().start(p, manager.plugin().getManager().arenaOf(p.getUniqueId()));
            default -> {
            }
        }
                return;
        }
        if (event instanceof EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        String name = manager.plugin().getManager().arenaOf(p.getUniqueId());
        if (name == null) {
            return;
        }
        FootballArena a = manager.plugin().getManager().getArena(name);
        if (a != null) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof BlockPlaceEvent e) {
        if (manager.plugin().getManager().items().tag(e.getItemInHand()) != null) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof PlayerJoinEvent e) {
        manager.plugin().getManager().handleJoin(e.getPlayer());
                return;
        }
        if (event instanceof PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (manager.plugin().getManager().isInSetup(p.getUniqueId())) {
            if (manager.plugin().getManager().handleSetupTool(p, e.getAction(), e.getItem(), e.getClickedBlock())) {
                e.setCancelled(true);
                return;
            }
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) {
            return;
        }
        Block block = e.getClickedBlock();
        if (!(block.getState() instanceof Sign)) {
            return;
        }
        if (manager.plugin().getManager().handleSignBind(p, block.getLocation())) {
            e.setCancelled(true);
            return;
        }
        if (manager.plugin().getManager().isJoinSign(block.getLocation())) {
            e.setCancelled(true);
            manager.plugin().getManager().onSignClick(p, block.getLocation());
        }
                return;
        }
        if (event instanceof PlayerSignOpenEvent e) {
        if (e.getSign() != null && manager.plugin().getManager().isJoinSign(e.getSign().getLocation())) {
            e.setCancelled(true);
            manager.plugin().getManager().onSignClick(e.getPlayer(), e.getSign().getLocation());
        }
                return;
        }
    }
}
