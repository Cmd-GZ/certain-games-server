package com.duel.arena;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import org.bukkit.GameMode;
import org.bukkit.World;
import com.duel.arena.DuelArena;
import java.util.UUID;
import org.bukkit.block.Sign;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** 单挑 PvP 的会话（D22：每个场地一个会话）。 */
final class DuelSession extends GameSession {

    private final DuelManager manager;

    DuelSession(DuelManager manager, String arenaName) {
        super("duel", arenaName);
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
        manager.doEndFight(arenaName());
    }

    // ---------- I3 A 批：从 DuelListener 搬过来的处理器 ----------

    @Override
    protected void onPlayerQuit(Player player) {
        manager.handleQuit(player);
    }

    @Override
    protected void onPlayerDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (manager.arenaOf(p.getUniqueId()) == null) {
            return;
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.deathMessage(null);
        manager.handleDeath(p);
    }

    /** 在场内重生 → 回本场等待点。（"不在场内却站在决斗世界"那半段留在 listener 里当全服兜底） */
    @Override
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        String name = manager.arenaOf(p.getUniqueId());
        if (name == null) {
            return;
        }
        DuelArena a = manager.getArena(name);
        if (a != null && a.getLobbyPoint() != null) {
            event.setRespawnLocation(a.getLobbyPoint());
        }
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------

    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof PlayerMoveEvent e) {
        Player p = e.getPlayer();
        // 只转头不算移动：用 from/to 判定（不要用 p.getLocation()，部分版本事件触发时其仍是旧位置，会导致拦截永不生效）
        if (e.getFrom().distanceSquared(e.getTo()) < 0.0001) {
            return;
        }
        DuelManager m = manager.plugin().getManager();
        // 无敌倒计时内：禁止移动，强制拉回出生点
        if (m.invincible(p.getUniqueId())) {
            Location spawn = m.getFighterSpawn(p.getUniqueId());
            if (spawn != null) {
                e.setTo(spawn);
            }
            return;
        }
        // 战斗中离开决斗场范围：视为死亡，对方获胜（立刻结算，不拦截移动）
        String an = m.playerArenaOf(p.getUniqueId());
        if (an != null) {
            DuelArena a = m.getArena(an);
            if (a != null && a.getState() == DuelArena.State.FIGHTING && a.isFighter(p.getUniqueId()) && !a.contains(e.getTo())) {
                m.handleOutOfBounds(p, e.getTo());
            }
        }
                return;
        }
        if (event instanceof PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (e.getTo() == null) {
            return;
        }
        DuelManager m = manager.plugin().getManager();
        String an = m.playerArenaOf(p.getUniqueId());
        if (an == null) {
            return;
        }
        DuelArena a = m.getArena(an);
        if (a == null || a.getState() != DuelArena.State.FIGHTING || !a.isFighter(p.getUniqueId())) {
            return;
        }
        if (!a.contains(e.getTo())) {
            if (m.invincible(p.getUniqueId())) {
                e.setCancelled(true); // 无敌倒计时内：保护，禁止传送离开
            } else {
                m.handleOutOfBounds(p, e.getTo());
            }
        }
                return;
        }
        if (event instanceof PlayerSignOpenEvent e) {
        if (e.getSign() != null && manager.plugin().getManager().isDuelSign(e.getSign().getLocation())) {
            e.setCancelled(true);
            manager.plugin().getManager().onSignClick(e.getPlayer(), e.getSign().getLocation());
        }
                return;
        }
        if (event instanceof EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        DuelManager m = manager.plugin().getManager();
        UUID v = victim.getUniqueId();
        if (!m.inFight(v)) {
            return;
        }
        UUID a;
        if (e.getDamager() instanceof Player attacker) {
            a = attacker.getUniqueId();
        } else if (e.getDamager() instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Player shooter) {
            a = shooter.getUniqueId();
        } else {
            return;
        }
        if (m.inFight(a) && m.sameArena(v, a)) {
            e.setCancelled(false);
        }
                return;
        }
        if (event instanceof EntityExplodeEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.WindCharge wc
                && wc.getShooter() instanceof Player shooter
                && manager.plugin().getManager().inFight(shooter.getUniqueId())) {
            e.setCancelled(false);
        }
                return;
        }
        if (event instanceof BlockPlaceEvent e) {
        Player p = e.getPlayer();
        DuelManager m = manager.plugin().getManager();
        // 兜底：退出床永远不能当方块放下（等待中也一样，不经过"对战中"分支）
        if (m.isLeaveBedItem(e.getItemInHand())) {
            e.setCancelled(true);
            return;
        }
        if (!m.inFight(p.getUniqueId())) {
            return;
        }
        DuelArena a = m.getArena(m.playerArenaOf(p.getUniqueId()));
        if (a == null || !a.contains(e.getBlock().getLocation())) {
            e.setCancelled(true);
            return;
        }
        m.recordFightPlacement(a.getName(), e.getBlockReplacedState());
                return;
        }
        if (event instanceof BlockBreakEvent e) {
        Player p = e.getPlayer();
        DuelManager m = manager.plugin().getManager();
        if (!m.inFight(p.getUniqueId())) {
            return;
        }
        if (!m.isFightPlaced(p.getUniqueId(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            return;
        }
        m.removeFightPlaced(p.getUniqueId(), e.getBlock().getLocation());
                return;
        }
        if (event instanceof PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        // 世界判定（AGENTS.md §5-5）：PlayerRespawnEvent 是全服事件，别的小游戏也会重生
        // （例如幸运之柱淘汰后变旁观）。无条件 teleportToLobby 会把人从别人的游戏里踢回主城，
        // 而且 HubAPI.teleportToLobby 还会回调各游戏的 lobby handler，把对方的状态也清掉。
        if (!isDuelWorld(p.getWorld()) && !isDuelWorld(e.getRespawnLocation().getWorld())) {
            return;
        }
        com.hub.api.HubAPI.teleportToLobby(p);
                return;
        }
        if (event instanceof PlayerInteractEvent e) {
        if ((e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)
                && e.getItem() != null) {
            if (e.getItem().getType() == Material.PAPER) {
                manager.plugin().getManager().handleStartPaper(e.getPlayer());
                return;
            }
            if (e.getItem().getType() == Material.RED_BED && manager.plugin().getManager().isLeaveBedItem(e.getItem())) {
                // 退出床只是道具：必须吃掉这次交互（含手持物品/被点方块），否则床会被真的放在地上（非 OP 也放得出来）
                e.setCancelled(true);
                e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                manager.plugin().getManager().handleLeaveBed(e.getPlayer());
                return;
            }
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null || !(b.getState() instanceof Sign)) {
            return;
        }
        // 待绑定模式：把告示牌变为对应内容
        if (manager.plugin().getManager().handleSignBind(e.getPlayer(), b.getLocation())) {
            e.setCancelled(true);
            return;
        }
        if (!manager.plugin().getManager().isDuelSign(b.getLocation())) {
            return;
        }
        e.setCancelled(true);
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        manager.plugin().getManager().onSignClick(e.getPlayer(), b.getLocation());
                return;
        }
        if (event instanceof PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!manager.plugin().getManager().isInSetup(p.getUniqueId())) {
            return;
        }
        if (manager.plugin().getManager().handleSetupTool(p, e.getAction(), e.getClickedBlock())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        String title = PlainTextComponentSerializer.plainText().serialize(e.getView().title());
        if (!title.startsWith("配置物品 - ")) {
            return;
        }
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) {
            return;
        }
        int slot = e.getSlot();
        if (slot >= DuelManager.BTN_SAVE) {
            e.setCancelled(true);
            manager.plugin().getManager().handleKitClick(p, e.getInventory(), slot);
        }
                return;
        }
    }

    // ---- 原 Listener 的私有 helper ----

    private boolean isDuelWorld(World w) {
        if (w == null) {
            return false;
        }
        for (DuelArena a : manager.plugin().getManager().getArenas().values()) {
            if (a.getPos1() != null && w.equals(a.getPos1().getWorld())) {
                return true;
            }
            Location lobby = a.getLobbyPoint();
            if (lobby != null && w.equals(lobby.getWorld())) {
                return true;
            }
        }
        return false;
    }
}
