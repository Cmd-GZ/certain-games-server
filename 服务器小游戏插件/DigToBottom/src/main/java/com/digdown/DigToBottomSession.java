package com.digdown;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.block.BlockPhysicsEvent;
import java.util.Locale;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
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
        // I3 C1：场地里对局进行中禁放方块（`onBlockBreak` 是**玩法**：挖到的东西算进度，留在 listener）
        arbiter().on(com.hub.session.EventKind.BLOCK_PLACE, ctx -> {
            DigToBottomArena arena = manager.arenaAt(ctx.block().getLocation());
            if (arena != null && arena.getState() == DigToBottomArena.State.RUNNING) {
                ctx.cancel();
            }
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

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------

    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof BlockBreakEvent e) {
        Block block = e.getBlock();
        DigToBottomArena arena = manager.arenaAt(block.getLocation());
        if (arena == null) {
            return; // 不是本场地：GTB 的全局保护照旧
        }
        boolean debug = manager.isDebugBreak();
        Material type = block.getType();
        if (type == Material.BEDROCK || type.name().contains("GLASS")) {
            if (debug) {
                debug("BREAK deny glass/bedrock " + describeBlock(e.getPlayer(), block));
            }
            e.setCancelled(true);
            return;
        }
        Player player = e.getPlayer();
        DigToBottomManager m = manager;
        if (!m.canBreak(player, arena, block)) {
            if (debug) {
                debug("BREAK deny rules " + describeBlock(player, block));
            }
            e.setCancelled(true);
            return;
        }
        if (e.isCancelled()) {
            // GTB 的兜底取消：这是玩家自己的柱子，显式放行
            e.setCancelled(false);
            if (debug) {
                debug("BREAK un-cancel (GTB 兜底) " + describeBlock(player, block));
            }
        }
        e.setDropItems(false); // 不掉落方块
        e.setExpToDrop(0);
        m.onPillarBreak(player, arena, block);
                return;
        }
        if (event instanceof BlockBreakEvent e) {
        if (!manager.isDebugBreak()) {
            return;
        }
        if (manager.arenaAt(e.getBlock().getLocation()) == null) {
            return;
        }
        debug("BREAK final cancelled=" + e.isCancelled() + " " + describeBlock(e.getPlayer(), e.getBlock()));
                return;
        }
        if (event instanceof PlayerInteractEvent e) {
        if (!manager.isDebugBreak()) {
            return;
        }
        if (e.getAction() != Action.LEFT_CLICK_BLOCK || e.getClickedBlock() == null) {
            return;
        }
        Block block = e.getClickedBlock();
        if (manager.arenaAt(block.getLocation()) == null) {
            return;
        }
        debug("LEFT-CLICK cancelled=" + e.isCancelled() + " " + describeBlock(e.getPlayer(), block));
                return;
        }
        if (event instanceof BlockPhysicsEvent e) {
        if (manager.arenaAt(e.getBlock().getLocation()) != null) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof PlayerMoveEvent e) {
        Player player = e.getPlayer();
        DigToBottomArena arena = manager.getArena(manager.arenaOf(player.getUniqueId()));
        if (arena == null) {
            return;
        }
        Location here = player.getLocation();
        // 掉进虚空（等待室放歪了 / 玻璃没建好）：送回等待点
        if (here.getY() < here.getWorld().getMinHeight() - 10) {
            Location back = arena.getLobby() != null ? arena.getLobby() : arena.getCenter();
            if (back != null) {
                player.teleport(back);
                com.hub.util.Chat.send(player, "&c你掉出了场地，已送回等待点。");
            }
            return;
        }
        if (arena.getState() != DigToBottomArena.State.RUNNING) {
            return;
        }
        // 对局中掉到柱子底部以下：送回自己柱子顶部
        if (arena.getPlayers().contains(player.getUniqueId())
                && arena.getCenter() != null
                && here.getY() < arena.getCenter().getBlockY() - 5) {
            Integer lane = arena.getLaneOf().get(player.getUniqueId());
            Location back = lane == null ? arena.getLobby() : manager.spawnOf(arena, lane);
            if (back != null) {
                player.teleport(back);
                com.hub.util.Chat.send(player, "&c你掉出了柱子，已送回顶部。");
            }
            return;
        }
        if (!arena.isFrozen()) {
            return;
        }
        if (System.currentTimeMillis() < arena.getFreezeGraceUntil()) {
            return; // 开局传送刚落地的那一瞬间放过，避免把传送顶回去
        }
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location stay = from.clone();
            stay.setYaw(to.getYaw());
            stay.setPitch(to.getPitch());
            e.setTo(stay);
        }
                return;
        }
        if (event instanceof EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player player)) {
            return;
        }
        if (manager.arenaOf(player.getUniqueId()) != null) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = e.getPlayer();
        if (manager.arenaOf(player.getUniqueId()) == null) {
            return;
        }
        ItemStack item = e.getItem();
        if (item == null) {
            return;
        }
        if (item.getType() == Material.RED_BED) {
            e.setCancelled(true);
            manager.leave(player);
            return;
        }
        if (item.getType() == Material.PAPER) {
            e.setCancelled(true);
            manager.handleStartPaper(player);
        }
                return;
        }
    }

    // ---- 原 Listener 的私有 helper ----

    private String describeBlock(Player player, Block block) {
        DigToBottomManager m = manager;
        DigToBottomArena arena = m.arenaAt(block.getLocation());
        StringBuilder sb = new StringBuilder();
        sb.append("player=").append(player.getName())
                .append(" op=").append(player.isOp())
                .append(" mode=").append(player.getGameMode())
                .append(" permUse=").append(player.hasPermission("dig.use"))
                .append(" | block=").append(block.getType())
                .append(" @ ").append(block.getWorld().getName())
                .append(' ').append(block.getX()).append(',').append(block.getY()).append(',').append(block.getZ());
        if (arena == null) {
            sb.append(" | arena=null");
        } else {
            Integer lane = arena.getLaneOf().get(player.getUniqueId());
            int[] pt = lane == null ? null : m.lanePoint(arena, lane);
            boolean laneMatch = pt != null && block.getX() == pt[0] && block.getZ() == pt[1];
            sb.append(" | arena=").append(arena.getName())
                    .append(" state=").append(arena.getState())
                    .append(" frozen=").append(arena.isFrozen())
                    .append(" participant=").append(arena.getPlayers().contains(player.getUniqueId()))
                    .append(" finished=").append(arena.getFinished().containsKey(player.getUniqueId()))
                    .append(" lane=").append(lane)
                    .append(" laneMatch=").append(laneMatch)
                    .append(" canBreak=").append(m.canBreak(player, arena, block));
        }
        World respawn = Bukkit.getServer().getRespawnWorld();
        sb.append(" | spawnRadius=").append(Bukkit.getServer().getSpawnRadius())
                .append(" respawnWorld=").append(respawn == null ? "-" : respawn.getName());
        if (respawn != null && respawn.equals(block.getWorld())) {
            Location spawn = respawn.getSpawnLocation();
            int dx = Math.abs(block.getX() - spawn.getBlockX());
            int dz = Math.abs(block.getZ() - spawn.getBlockZ());
            sb.append(" spawnDist=").append(Math.max(dx, dz));
        } else {
            sb.append(" spawnDist=-");
        }
        return sb.toString();
    }

    private void debug(String message) {
        manager.plugin().getLogger().info("[DigDebug] " + message);
    }

    private String describe(Player player, Block block) {
        DigToBottomManager m = manager;
        DigToBottomArena arena = m.arenaAt(block.getLocation());
        StringBuilder sb = new StringBuilder();
        sb.append("player=").append(player.getName())
                .append(" op=").append(player.isOp())
                .append(" mode=").append(player.getGameMode())
                .append(" permUse=").append(player.hasPermission("dig.use"))
                .append(" | block=").append(block.getType())
                .append(" @ ").append(block.getWorld().getName())
                .append(' ').append(block.getX()).append(',').append(block.getY()).append(',').append(block.getZ());
        if (arena == null) {
            sb.append(" | arena=null");
        } else {
            Integer lane = arena.getLaneOf().get(player.getUniqueId());
            int[] pt = lane == null ? null : m.lanePoint(arena, lane);
            boolean laneMatch = pt != null && block.getX() == pt[0] && block.getZ() == pt[1];
            sb.append(" | arena=").append(arena.getName())
                    .append(" state=").append(arena.getState())
                    .append(" frozen=").append(arena.isFrozen())
                    .append(" participant=").append(arena.getPlayers().contains(player.getUniqueId()))
                    .append(" finished=").append(arena.getFinished().containsKey(player.getUniqueId()))
                    .append(" lane=").append(lane)
                    .append(" laneMatch=").append(laneMatch)
                    .append(" canBreak=").append(m.canBreak(player, arena, block));
        }
        World respawn = Bukkit.getServer().getRespawnWorld();
        sb.append(" | spawnRadius=").append(Bukkit.getServer().getSpawnRadius())
                .append(" respawnWorld=").append(respawn == null ? "-" : respawn.getName());
        if (respawn != null && respawn.equals(block.getWorld())) {
            Location spawn = respawn.getSpawnLocation();
            int dx = Math.abs(block.getX() - spawn.getBlockX());
            int dz = Math.abs(block.getZ() - spawn.getBlockZ());
            sb.append(" spawnDist=").append(Math.max(dx, dz));
        } else {
            sb.append(" spawnDist=-");
        }
        return sb.toString();
    }
}
