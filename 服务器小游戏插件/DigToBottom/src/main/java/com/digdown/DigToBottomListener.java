package com.digdown;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

import java.util.Locale;

/** 方块破坏（玻璃/基岩保护、只挖自己柱子、无掉落）、冻结、退出与受伤拦截。 */
public class DigToBottomListener implements Listener {

    private final DigToBottomPlugin plugin;

    public DigToBottomListener(DigToBottomPlugin plugin) {
        this.plugin = plugin;
    }

    private DigToBottomManager manager() {
        return plugin.getManager();
    }

    private void debug(String message) {
        plugin.getLogger().info("[DigDebug] " + message);
    }

    /** 诊断用：把玩家、方块、场地状态、出生点保护距离全部拼成一行。 */
    private String describe(Player player, Block block) {
        DigToBottomManager m = manager();
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

    /**
     * HIGHEST 优先级，但先判断是不是自己场地的方块：不是就直接 return，
     * 绝不影响别的世界/别的游戏（AGENTS.md 5.5）。
     */
    /**
     * HIGHEST 优先级，但先判断是不是自己场地的方块：不是就直接 return，
     * 绝不影响别的世界/别的游戏（AGENTS.md 5.5）。
     * <p>
     * 必须在自己的柱子上显式 {@code setCancelled(false)}：GuessTheBuild 在 NORMAL 优先级上
     * 对**任何非 OP 且不在 GTB 局内**的玩家无条件取消方块破坏（不分世界），
     * 不“取消取消”的话非 OP 玩家在自己柱子也会挖不动，OP 反而正常（BuildRun 同样处理）。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        DigToBottomArena arena = manager().arenaAt(block.getLocation());
        if (arena == null) {
            return; // 不是本场地：GTB 的全局保护照旧
        }
        boolean debug = manager().isDebugBreak();
        Material type = block.getType();
        if (type == Material.BEDROCK || type.name().contains("GLASS")) {
            if (debug) {
                debug("BREAK deny glass/bedrock " + describe(event.getPlayer(), block));
            }
            event.setCancelled(true);
            return;
        }
        Player player = event.getPlayer();
        DigToBottomManager m = manager();
        if (!m.canBreak(player, arena, block)) {
            if (debug) {
                debug("BREAK deny rules " + describe(player, block));
            }
            event.setCancelled(true);
            return;
        }
        if (event.isCancelled()) {
            // GTB 的兜底取消：这是玩家自己的柱子，显式放行
            event.setCancelled(false);
            if (debug) {
                debug("BREAK un-cancel (GTB 兜底) " + describe(player, block));
            }
        }
        event.setDropItems(false); // 不掉落方块
        event.setExpToDrop(0);
        m.onPillarBreak(player, arena, block);
    }

    /** 诊断：MONITOR 能看到最终是否被别的插件取消。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreakMonitor(BlockBreakEvent event) {
        if (!manager().isDebugBreak()) {
            return;
        }
        if (manager().arenaAt(event.getBlock().getLocation()) == null) {
            return;
        }
        debug("BREAK final cancelled=" + event.isCancelled() + " " + describe(event.getPlayer(), event.getBlock()));
    }

    /** 诊断：左键点击（即使方块最终没被破坏也会触发；出生点保护下这个事件也不会发出）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeftClickMonitor(PlayerInteractEvent event) {
        if (!manager().isDebugBreak()) {
            return;
        }
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (manager().arenaAt(block.getLocation()) == null) {
            return;
        }
        debug("LEFT-CLICK cancelled=" + event.isCancelled() + " " + describe(event.getPlayer(), block));
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        DigToBottomArena arena = manager().arenaAt(event.getBlock().getLocation());
        if (arena != null && arena.getState() == DigToBottomArena.State.RUNNING) {
            event.setCancelled(true);
        }
    }

    /**
     * 场地内禁止方块物理更新：沙子/沙砾不会因为下面被挖空而塌下来，
     * 柱子始终是一根固定形状的竖井（需求里的「不掉落方块」）。
     * 只拦自己场地的方块，不影响别处。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPhysics(BlockPhysicsEvent event) {
        if (manager().arenaAt(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    /** 冻结倒计时：只取消位移，不取消转头；另外兜底掉出柱子的情况。 */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        DigToBottomArena arena = manager().getArena(manager().arenaOf(player.getUniqueId()));
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
            Location back = lane == null ? arena.getLobby() : manager().spawnOf(arena, lane);
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
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location stay = from.clone();
            stay.setYaw(to.getYaw());
            stay.setPitch(to.getPitch());
            event.setTo(stay);
        }
    }

    /** 场内玩家不掉血、不掉饥饿（避免摔死/被打断节奏）。 */
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (manager().arenaOf(player.getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }

    /** 对局中禁止丢道具；等待时也不许把发下去的钻石工具丢掉。 */
    /** 万一人死了：回到等待点，别重生在柱子里。 */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (manager().arenaOf(player.getUniqueId()) == null) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        if (item.getType() == Material.RED_BED) {
            event.setCancelled(true);
            manager().leave(player);
            return;
        }
        if (item.getType() == Material.PAPER) {
            event.setCancelled(true);
            manager().handleStartPaper(player);
        }
    }
}
