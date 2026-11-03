package com.luckypillar;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import com.hub.util.Chat;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** LuckyPillar 的对局会话（D22：每个场地一个会话）。 */
final class LuckyPillarSession extends GameSession {

    private final LuckyPillarManager manager;

    LuckyPillarSession(LuckyPillarManager manager, String arenaName) {
        super("luckypillar", arenaName);
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
        manager.doEndMatch(arenaName());
    }

    // ---------- I3 A 批：从 listener 搬过来的处理器 ----------

    @Override
    protected void onPlayerQuit(Player player) {
        manager.handleQuit(player);
    }

    @Override
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        LuckyPillarArena a = manager.activeArenaOf(p.getUniqueId());
        if (a == null || a.getState() == LuckyPillarArena.State.WAITING) {
            return;
        }
        if (a.getState() == LuckyPillarArena.State.RUNNING && !a.getPlayers().contains(p.getUniqueId())) {
            return;
        }
        // 死亡掉落：不 keepInventory，也**不清 drops**（原来这里清空 = 谁死了身上东西直接没）
        event.setKeepInventory(false);
        event.setDroppedExp(0);
        event.deathMessage(null);
        manager.handleDeath(p);
    }

    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        manager.handleRespawn(event.getPlayer(), event);
    }

    /** 配置模式道具 + 等待室"开始游戏"纸 / "退出游戏"床。 */
    @Override
    protected void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (manager.isInSetup(p.getUniqueId())
                && manager.handleSetupTool(p, event.getAction(), event.getClickedBlock())) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // 主手/副手会各触发一次事件，只认主手（否则开局后会多打一条"正在游戏中"）
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || manager.activeArenaOf(p.getUniqueId()) == null) {
            return;
        }
        if (manager.isStartPaper(item)) {
            event.setCancelled(true);
            manager.handleStartPaper(p);
        } else if (item.getType() == Material.RED_BED) {
            event.setCancelled(true);
            manager.handleLeaveBed(p);
        }
    }

    // ---------- I3 C 类：LuckyPillar 其余监听（全部经框架按归属派发过来） ----------

    /** 受伤：掉落伤害只保护"本局追踪的实体"（原 onFallDamage）。 */
    @Override
    protected void onEntityDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (manager.isTrackedEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** 其余事件（加入 / 苦力怕爆炸 / PvP / 点火 / 放方块 / 倒水 / 蔓延 / 结冰…）。 */
    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof org.bukkit.event.player.PlayerJoinEvent join) {
            manager.handleJoin(join.getPlayer());
            return;
        }
        if (event instanceof org.bukkit.event.entity.EntityExplodeEvent explode) {
            if (!(explode.getEntity() instanceof org.bukkit.entity.Creeper)) {
                return;
            }
            var arena = manager.runningArenaIn(explode.getLocation().getWorld());
            if (arena == null) {
                return;   // 不是本世界 / 不是本场对局：原样放行
            }
            explode.blockList().clear();
            return;
        }
        if (event instanceof org.bukkit.event.block.BlockIgniteEvent ignite) {
            if (ignite.getCause() == org.bukkit.event.block.BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL) {
                return;   // 玩家自己点的火属于玩法
            }
            if (manager.runningArenaIn(ignite.getBlock().getWorld()) == null) {
                return;
            }
            ignite.setCancelled(true);
            return;
        }
        if (event instanceof org.bukkit.event.block.BlockPlaceEvent place) {
            manager.trackBlock(place.getBlockPlaced());
            return;
        }
        if (event instanceof org.bukkit.event.player.PlayerBucketEmptyEvent bucket) {
            manager.trackBlock(bucket.getBlockClicked().getRelative(bucket.getBlockFace()));
            return;
        }
        if (event instanceof org.bukkit.event.block.BlockSpreadEvent spread) {
            manager.trackBlock(spread.getBlock());
            return;
        }
        if (event instanceof org.bukkit.event.block.BlockFromToEvent fromTo) {
            manager.trackBlock(fromTo.getToBlock());
            return;
        }
        if (event instanceof org.bukkit.event.block.BlockFormEvent form) {
            manager.trackBlock(form.getBlock());
        }
    }
}
