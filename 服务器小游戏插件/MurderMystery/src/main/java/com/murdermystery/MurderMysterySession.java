package com.murdermystery;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.block.Sign;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import com.hub.util.Chat;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/** MurderMystery 的对局会话（D22：每个场地一个会话）。 */
final class MurderMysterySession extends GameSession {

    private final MurderMysteryManager manager;

    MurderMysterySession(MurderMysteryManager manager, String arenaName) {
        super("murdermystery", arenaName);
        this.manager = manager;
        // I3 C1：局内禁改方块（原 `onBreak`/`onPlace` 的逐字等价搬运）
        arbiter()
                .on(com.hub.session.EventKind.BLOCK_BREAK, ctx -> {
                    if (ctx.player() != null && manager.arenaOf(ctx.player().getUniqueId()) != null) {
                        ctx.cancel();
                    }
                })
                .on(com.hub.session.EventKind.BLOCK_PLACE, ctx -> {
                    if (ctx.player() != null && manager.arenaOf(ctx.player().getUniqueId()) != null) {
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

    /** 交互：配置工具（左右键都认）、金锭兑换、开始纸、退出床、杀手飞剑、加入告示牌。 */
    @Override
    protected void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        Player p = event.getPlayer();
        MurderMysteryManager m = manager;
        ItemStack item = event.getItem();

        // 配置模式工具：木棍是"左键=范围角1、右键=范围角2"，所以这里必须先于右键过滤处理
        if (m.isInSetup(p.getUniqueId())) {
            Action action = event.getAction();
            if ((action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK
                    || action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)
                    && m.handleSetupTool(p, action, event.getClickedBlock())) {
                event.setCancelled(true);
                return;
            }
        }

        // 下面是右键玩法（兑换/道具/告示牌），只处理右键
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // 对局内交互
        String arenaName = m.arenaOf(p.getUniqueId());
        if (arenaName != null) {
            MurderMysteryArena a = m.getArena(arenaName);
            if (a != null && a.getState() == MurderMysteryArena.State.RUNNING) {
                // 商店弩不在这里拦截：交给原版弩自己装填/发射；猎人弓的无限箭由 EntityShootBowEvent 处理
                if (item != null && m.isShopItem(item)) {
                    m.openShop(p);
                    event.setCancelled(true);
                    return;
                }
                if (item != null && m.isTruthStone(item)) {
                    event.setCancelled(true);
                    m.openTruthMenu(p);
                    return;
                }
                if (item != null && m.isDeathNote(item)) {
                    event.setCancelled(true);
                    m.useDeathNote(p);
                    return;
                }
                if (item != null && m.isTrackCompass(item)) {
                    event.setCancelled(true);
                    m.handleTrackCompass(p);
                    return;
                }
                if (item != null && item.getType() == Material.IRON_SWORD) {
                    m.handleSwordThrow(p);
                    event.setCancelled(true);
                    return;
                }
            }
            if (item != null && item.getType() == Material.PAPER) {
                m.handleStartPaper(p);
                return;
            }
            if (item != null && item.getType() == Material.RED_BED) {
                m.handleLeaveBed(p);
                return;
            }
        }

        // 加入告示牌（绑定 / 点击加入）
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            if (!(event.getClickedBlock().getState() instanceof Sign)) {
                return;
            }
            if (m.handleSignBind(p, event.getClickedBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }
            if (m.isJoinSign(event.getClickedBlock().getLocation())) {
                event.setCancelled(true);
                m.onSignClick(p, event.getClickedBlock().getLocation());
            }
        }
    }
}
