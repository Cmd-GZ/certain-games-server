package com.football;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 绿茵足球事件层。所有事件都先问"是不是我的玩家/我的场"，不是就 return
 * （同优先级事件必须限定自己的世界，见 docs/开发须知.md 第 5 节）。
 */
public class FootballListener implements Listener {

    private final FootballPlugin plugin;

    public FootballListener(FootballPlugin plugin) {
        this.plugin = plugin;
    }

    /** 左键挥动 = 踢球（击球 / 挑高靴 / 开球靴，必须拿在主手）。 */
    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return; // 副手挥动不算
        }
        Player p = event.getPlayer();
        FootballItems.Tag tag = plugin.getManager().items().tag(p.getInventory().getItemInMainHand());
        if (tag == null) {
            return;
        }
        switch (tag.kind()) {
            case KICK_FORWARD, KICK_VERTICAL, KICKOFF ->
                    plugin.getManager().handleKick(p, tag.kind(), tag.power());
            default -> {
            }
        }
    }

    /** 右键道具：冲刺 / 高跳 / 选队 / 退出 / OP 开始纸。 */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        FootballItems.Tag tag = plugin.getManager().items().tag(item);
        if (tag == null) {
            return;
        }
        event.setCancelled(true);
        Player p = event.getPlayer();
        switch (tag.kind()) {
            case SPRINT -> plugin.getManager().useSprint(p);
            case JUMP -> plugin.getManager().useJump(p);
            case TEAM_RED -> plugin.getManager().pickTeam(p, FootballArena.Team.RED);
            case TEAM_BLUE -> plugin.getManager().pickTeam(p, FootballArena.Team.BLUE);
            case LEAVE_BED -> plugin.getManager().leave(p);
            case START_PAPER -> plugin.getManager().start(p, plugin.getManager().arenaOf(p.getUniqueId()));
            default -> {
            }
        }
    }

    /**
     * 对局玩家免伤（含摔落——高跳落地、进球后传送；也覆盖其它插件世界的误伤）。
     * 只有本插件场内玩家才取消，别的世界照常。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) {
            return;
        }
        String name = plugin.getManager().arenaOf(p.getUniqueId());
        if (name == null) {
            return;
        }
        FootballArena a = plugin.getManager().getArena(name);
        if (a != null) {
            event.setCancelled(true);
        }
    }

    /** 饿肚子关掉（比赛里不扣饥饿）。 */
    /** 带标记的功能道具不能丢弃（防止背包里没有退出床/队旗后卡住）。 */
    /** 带标记的功能道具不能放置（红床是方块，退出时点床别把床放地上——单挑踩过的坑）。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.getManager().items().tag(event.getItemInHand()) != null) {
            event.setCancelled(true);
        }
    }

    /** 场内玩家不能挖场地（把球场挖出洞球就卡住了）。等待中与对局中都保护。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        String name = plugin.getManager().arenaOf(p.getUniqueId());
        if (name == null) {
            return;
        }
        FootballArena a = plugin.getManager().getArena(name);
        if (a == null || !a.isReady()) {
            return;
        }
        if (inArenaBounds(a, event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    private boolean inArenaBounds(FootballArena a, Location loc) {
        return a.contains(loc);
    }

    /** 退出清理（一队走光直接判负另一队）。 */
    /** 进服清理（观战残留 / 滞留足球世界送回主城）。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getManager().handleJoin(event.getPlayer());
    }

    /** 配置模式道具 + 加入告示牌（绑定 / 点击加入）。 */
    @EventHandler
    public void onSetupAndSigns(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (plugin.getManager().isInSetup(p.getUniqueId())) {
            if (plugin.getManager().handleSetupTool(p, event.getAction(), event.getItem(), event.getClickedBlock())) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!(block.getState() instanceof Sign)) {
            return;
        }
        if (plugin.getManager().handleSignBind(p, block.getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (plugin.getManager().isJoinSign(block.getLocation())) {
            event.setCancelled(true);
            plugin.getManager().onSignClick(p, block.getLocation());
        }
    }

    /** Paper 26.2：右键告示牌先触发此事件，cancel 后 PlayerInteractEvent 不再触发（HotPotato 同款处理）。 */
    @EventHandler
    public void onSignOpen(PlayerSignOpenEvent event) {
        if (event.getSign() != null && plugin.getManager().isJoinSign(event.getSign().getLocation())) {
            event.setCancelled(true);
            plugin.getManager().onSignClick(event.getPlayer(), event.getSign().getLocation());
        }
    }
}
