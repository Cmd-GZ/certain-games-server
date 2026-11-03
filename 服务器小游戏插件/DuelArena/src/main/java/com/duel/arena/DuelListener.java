package com.duel.arena;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import com.duel.arena.DuelArena;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;

/** 事件监听：范围拉回、伤害、死亡结算、退出判负、重生、告示牌、配置窗口。 */
public class DuelListener implements Listener {

    private final DuelPlugin plugin;

    public DuelListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    /** 对战中玩家离开范围：拉回出生点；无敌倒计时内禁止移动（可转头）。 */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        // 只转头不算移动：用 from/to 判定（不要用 p.getLocation()，部分版本事件触发时其仍是旧位置，会导致拦截永不生效）
        if (event.getFrom().distanceSquared(event.getTo()) < 0.0001) {
            return;
        }
        DuelManager m = plugin.getManager();
        // 无敌倒计时内：禁止移动，强制拉回出生点
        if (m.invincible(p.getUniqueId())) {
            Location spawn = m.getFighterSpawn(p.getUniqueId());
            if (spawn != null) {
                event.setTo(spawn);
            }
            return;
        }
        // 战斗中离开决斗场范围：视为死亡，对方获胜（立刻结算，不拦截移动）
        String an = m.playerArenaOf(p.getUniqueId());
        if (an != null) {
            DuelArena a = m.getArena(an);
            if (a != null && a.getState() == DuelArena.State.FIGHTING && a.isFighter(p.getUniqueId()) && !a.contains(event.getTo())) {
                m.handleOutOfBounds(p, event.getTo());
            }
        }
    }

    /** 传送（末影珍珠/其他插件等不走移动事件）：传送目的地出界同样视为死亡；无敌期内保持保护（拉回出生点）。 */
    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Player p = event.getPlayer();
        if (event.getTo() == null) {
            return;
        }
        DuelManager m = plugin.getManager();
        String an = m.playerArenaOf(p.getUniqueId());
        if (an == null) {
            return;
        }
        DuelArena a = m.getArena(an);
        if (a == null || a.getState() != DuelArena.State.FIGHTING || !a.isFighter(p.getUniqueId())) {
            return;
        }
        if (!a.contains(event.getTo())) {
            if (m.invincible(p.getUniqueId())) {
                event.setCancelled(true); // 无敌倒计时内：保护，禁止传送离开
            } else {
                m.handleOutOfBounds(p, event.getTo());
            }
        }
    }

    /** Paper 26.2：右键告示牌先触发此事件，cancel 后 PlayerInteractEvent 不再触发，所以在这里直接处理。 */
    @EventHandler
    public void onSignOpen(PlayerSignOpenEvent event) {
        if (event.getSign() != null && plugin.getManager().isDuelSign(event.getSign().getLocation())) {
            event.setCancelled(true);
            plugin.getManager().onSignClick(event.getPlayer(), event.getSign().getLocation());
        }
    }

    /** 伤害：本场对战中允许（覆盖服务器 pvp=false）。攻击者可为玩家或玩家发射的投射物（风弹/箭矢等）。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        DuelManager m = plugin.getManager();
        UUID v = victim.getUniqueId();
        if (!m.inFight(v)) {
            return;
        }
        UUID a;
        if (event.getDamager() instanceof Player attacker) {
            a = attacker.getUniqueId();
        } else if (event.getDamager() instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Player shooter) {
            a = shooter.getUniqueId();
        } else {
            return;
        }
        if (m.inFight(a) && m.sameArena(v, a)) {
            event.setCancelled(false);
        }
    }

    /** 风弹爆炸：本场对战中允许（覆盖服务器/保护插件对爆炸的拦截），保证炸地面、自炸起飞（脚底雷）也有效果。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplode(EntityExplodeEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.WindCharge wc
                && wc.getShooter() instanceof Player shooter
                && plugin.getManager().inFight(shooter.getUniqueId())) {
            event.setCancelled(false);
        }
    }

    /** 对战中放置方块：允许，并记录放置前的方块用于结束后还原；范围外禁止。 */
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        DuelManager m = plugin.getManager();
        // 兜底：退出床永远不能当方块放下（等待中也一样，不经过"对战中"分支）
        if (m.isLeaveBedItem(event.getItemInHand())) {
            event.setCancelled(true);
            return;
        }
        if (!m.inFight(p.getUniqueId())) {
            return;
        }
        DuelArena a = m.getArena(m.playerArenaOf(p.getUniqueId()));
        if (a == null || !a.contains(event.getBlock().getLocation())) {
            event.setCancelled(true);
            return;
        }
        m.recordFightPlacement(a.getName(), event.getBlockReplacedState());
    }

    /** 对战中破坏方块：只允许破坏玩家放置的方块，地图方块不可破坏。 */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        DuelManager m = plugin.getManager();
        if (!m.inFight(p.getUniqueId())) {
            return;
        }
        if (!m.isFightPlaced(p.getUniqueId(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            return;
        }
        m.removeFightPlaced(p.getUniqueId(), event.getBlock().getLocation());
    }

    /** 死亡：判定对方获胜，不掉落物品。 */
    /** 重生：在场的回等待点；只有「死在单挑世界、却已不在场」的才兜底回大厅。 */
    /**
     * 重生：**只保留"世界兜底"这一半**（I3 A 批）。
     *
     * <p>"在场内就把重生点设成等待点"那一半已经搬进 {@code DuelSession#onPlayerRespawn} ——
     * 它只对**本局玩家**有意义，交给框架按归属派发即可。
     *
     * <p>这一半不能搬：它是**全服世界级**的兜底 —— 玩家已经不在任何会话里（例如局散了、
     * 会话已 ENDED）却还站在决斗世界，需要把人送回主城。搬进会话钩子后永远走不到。
     * 同理，`HubAPI.teleportToLobby` 会回调各游戏的 lobby handler，所以必须先用
     * `isDuelWorld` 把自己限制在自家世界（AGENTS.md §5-5）。
     */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        // 世界判定（AGENTS.md §5-5）：PlayerRespawnEvent 是全服事件，别的小游戏也会重生
        // （例如幸运之柱淘汰后变旁观）。无条件 teleportToLobby 会把人从别人的游戏里踢回主城，
        // 而且 HubAPI.teleportToLobby 还会回调各游戏的 lobby handler，把对方的状态也清掉。
        if (!isDuelWorld(p.getWorld()) && !isDuelWorld(event.getRespawnLocation().getWorld())) {
            return;
        }
        com.hub.api.HubAPI.teleportToLobby(p);
    }

    /** 这个玩家是否位于某个决斗场所在的世界（重生兜底只在该世界生效）。 */
    private boolean isDuelWorld(World w) {
        if (w == null) {
            return false;
        }
        for (DuelArena a : plugin.getManager().getArenas().values()) {
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

    /** 退出：对战中对方获胜。 */
    /** 右键告示牌：绑定模式变内容 / 查看战绩 / 点击加入。 */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if ((event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
                && event.getItem() != null) {
            if (event.getItem().getType() == Material.PAPER) {
                plugin.getManager().handleStartPaper(event.getPlayer());
                return;
            }
            if (event.getItem().getType() == Material.RED_BED && plugin.getManager().isLeaveBedItem(event.getItem())) {
                // 退出床只是道具：必须吃掉这次交互（含手持物品/被点方块），否则床会被真的放在地上（非 OP 也放得出来）
                event.setCancelled(true);
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                plugin.getManager().handleLeaveBed(event.getPlayer());
                return;
            }
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block b = event.getClickedBlock();
        if (b == null || !(b.getState() instanceof Sign)) {
            return;
        }
        // 待绑定模式：把告示牌变为对应内容
        if (plugin.getManager().handleSignBind(event.getPlayer(), b.getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (!plugin.getManager().isDuelSign(b.getLocation())) {
            return;
        }
        event.setCancelled(true);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        plugin.getManager().onSignClick(event.getPlayer(), b.getLocation());
    }

    /** 配置模式：道具快速圈范围/定出生点/打开物品窗口/保存/退出。 */
    @EventHandler
    public void onSetupInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.getManager().isInSetup(p.getUniqueId())) {
            return;
        }
        if (plugin.getManager().handleSetupTool(p, event.getAction(), event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }

    /** 配置窗口点击：按钮处理，其余槽位自由摆放。 */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!title.startsWith("配置物品 - ")) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (slot >= DuelManager.BTN_SAVE) {
            event.setCancelled(true);
            plugin.getManager().handleKitClick(p, event.getInventory(), slot);
        }
    }

    /** 场内玩家不掉饥饿（等待中或对局中：饱食度保持满，回血也正常）。 */
}
