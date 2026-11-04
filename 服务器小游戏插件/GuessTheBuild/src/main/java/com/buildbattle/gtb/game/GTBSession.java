package com.buildbattle.gtb.game;

import com.hub.session.EndReason;
import com.hub.session.GameSession;
import com.buildbattle.gtb.GuessBuildPlugin;
import com.buildbattle.gtb.util.Chat;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/** 建筑猜猜乐的对局会话（D22：会话层按场地键索引）。 */
final class GTBSession extends GameSession {

    private final GameManager manager;

    GTBSession(GameManager manager, String arenaName) {
        super("gtb", arenaName);
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
        manager.doEndGame();
    }

    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------

    @Override
    protected void onGameEvent(org.bukkit.event.Event event) {
        if (event instanceof AsyncChatEvent e) {
        var game = manager.plugin().getGame();
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!game.isInGame(id)) {
            return;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        boolean isBuilder = game.isBuilder(id);
        if (isBuilder && game.isBuilderChoosing(id)) {
            // 建造者选题目期间：消息全屏蔽（防候选泄露）
            e.setCancelled(true);
            e.viewers().clear();
        } else if (!isBuilder && game.isCorrectAnswer(text)) {
            // 猜中答案：只保留发送者本人可见，屏蔽其他所有玩家（防答案泄露）
            e.viewers().removeIf(v -> !v.equals(p));
        } else if (game.isEvalScoreMessage(text)) {
            // 评分消息：拦截原始公屏，改由评分逻辑广播“XX 打了 X 分”
            e.setCancelled(true);
            e.viewers().clear();
        }
        // 其余（错误答案、正常聊天、建造者闲聊）照常公屏显示
        game.handleChat(p, text);
                return;
        }
        if (event instanceof AsyncPlayerChatEvent e) {
        if (e.isCancelled()) {
            return;
        }
        var game = manager.plugin().getGame();
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!game.isInGame(id)) {
            return;
        }
        boolean isBuilder = game.isBuilder(id);
        if (isBuilder && game.isBuilderChoosing(id)) {
            e.setCancelled(true);
            e.getRecipients().clear();
        } else if (!isBuilder && game.isCorrectAnswer(e.getMessage())) {
            e.getRecipients().removeIf(r -> !r.equals(p));
        } else if (game.isEvalScoreMessage(e.getMessage())) {
            // 评分消息：拦截原始公屏，改由评分逻辑广播“XX 打了 X 分”
            e.setCancelled(true);
            e.getRecipients().clear();
        }
        game.handleChat(p, e.getMessage());
                return;
        }
        if (event instanceof PlayerInteractEvent e) {
        // 等待道具（开始纸/退出床）
        if ((e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                || e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)
                && e.getItem() != null) {
            if (manager.plugin().getGame().handleWaitingItem(e.getPlayer(), e.getItem())) {
                e.setCancelled(true);
                return;
            }
        }
        // 建场模式（公共库 SetupMode）：木棍左/右键=两个角点，纸=保存，屏障=退出并还原背包
        if (manager.plugin().getSetupMode().handleInteract(e.getPlayer(), e.getAction(),
                e.getItem(), e.getClickedBlock())) {
            e.setCancelled(true);
            return;
        }
        // 主城告示牌：右键加入游戏
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                && e.getClickedBlock().getState() instanceof Sign
                && sameBlock(e.getClickedBlock().getLocation(), manager.plugin().getJoinSignLoc())) {
            e.setCancelled(true); // 阻止打开告示牌编辑界面
            manager.plugin().getGame().join(e.getPlayer());
            return;
        }
        // 地板商店告示牌：右键打开地板商店（不会被杀死）
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                && e.getClickedBlock().getState() instanceof Sign
                && sameBlock(e.getClickedBlock().getLocation(), manager.plugin().getFloorSignLoc())) {
            e.setCancelled(true);
            manager.plugin().getArena().openFloorMenu(e.getPlayer());
            return;
        }
        // 等待界面道具：床=退出；开始工具三级确认（纸→绿宝石→钻石→开始）
        if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (manager.plugin().getGame().handleWaitingItem(e.getPlayer(), e.getItem())) {
                e.setCancelled(true); // 阻止放置/交互方块
            }
        }
                return;
        }
        if (event instanceof PlayerSignOpenEvent e) {
        if (manager.plugin().getJoinSignLoc() != null
                && e.getSign() != null
                && sameBlock(e.getSign().getLocation(), manager.plugin().getJoinSignLoc())) {
            e.setCancelled(true);
            manager.plugin().getGame().join(e.getPlayer());
            return;
        }
        if (manager.plugin().getFloorSignLoc() != null
                && e.getSign() != null
                && sameBlock(e.getSign().getLocation(), manager.plugin().getFloorSignLoc())) {
            e.setCancelled(true);
            manager.plugin().getArena().openFloorMenu(e.getPlayer());
        }
                return;
        }
        if (event instanceof BlockBreakEvent e) {
        // OP 豁免：管理员可以自由破坏（主城建造/清理等）
        if (e.getPlayer().isOp()) {
            return;
        }
        Block block = e.getBlock();
        var game = manager.plugin().getGame();
        UUID id = e.getPlayer().getUniqueId();
        if (!game.isInGame(id)) {
            if (inArena(block)) {
                e.setCancelled(true);
            }
            return;
        }
        if (!game.isBuilder(id)) {
            if (inArena(block)) {
                e.setCancelled(true);
            }
            return;
        }
        if (isFloorBlock(block)) {
            Chat.send(e.getPlayer(), "&c不能破坏场地地板！");
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Projectile proj)) {
            return;
        }
        if (!(proj.getShooter() instanceof Player)) {
            return;
        }
        if (!(e.getHitEntity() instanceof Player victim)) {
            return;
        }
        if (!manager.plugin().getGame().isEvaluating()) {
            return;
        }
        if (!manager.plugin().getGame().isBuilder(victim.getUniqueId())) {
            return;
        }
        victim.playHurtAnimation(0f); // 受击动画（红屏），零伤害的投掷物不会自动触发
        org.bukkit.util.Vector dir = proj.getVelocity().clone();
        if (dir.lengthSquared() < 0.0001) {
            return;
        }
        victim.setVelocity(dir.normalize().multiply(0.7).setY(0.45));
        victim.getWorld().playSound(victim.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_HURT, 1.0f, 1.0f);
                return;
        }
        if (event instanceof EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        if (!(e.getDamager() instanceof org.bukkit.entity.Projectile)) {
            return;
        }
        if (!manager.plugin().getGame().isEvaluating()) {
            return;
        }
        if (manager.plugin().getGame().isBuilder(victim.getUniqueId())) {
            e.setCancelled(false);
        }
                return;
        }
        if (event instanceof PlayerEggThrowEvent e) {
        if (manager.plugin().getGame().isEvaluating()) {
            e.getPlayer().getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.EGG, 16));
        }
                return;
        }
        if (event instanceof ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Player p && manager.plugin().getGame().isEvaluating()) {
            if (e.getEntity() instanceof Snowball) {
                p.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.SNOWBALL, 16));
            }
        }
                return;
        }
        if (event instanceof PlayerInteractEvent e) {
        if (!manager.plugin().getGame().isEvaluating()) {
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        var item = e.getItem();
        if (item != null && item.getType() == org.bukkit.Material.FIREWORK_ROCKET) {
            e.getPlayer().getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.FIREWORK_ROCKET, 16));
        }
                return;
        }
        if (event instanceof EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        if (!manager.plugin().getGame().isInGame(p.getUniqueId())) {
            return;
        }
        switch (e.getCause()) {
            case FALL, SUFFOCATION, VOID -> {
                // 免摔落/窒息/虚空伤害（掉虚空由 enforceStates 拉回场地）
                e.setCancelled(true);
            }
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> {
                // 评价阶段观众会往建造者身上扔 TNT：挡掉爆炸伤害，避免被炸死
                // （击退和鸡蛋/雪球的受击动画不走伤害事件，不受影响）
                if (manager.plugin().getGame().isEvaluating()) {
                    e.setCancelled(true);
                }
            }
            default -> {
            }
        }
                return;
        }
        if (event instanceof PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (!manager.plugin().getGame().isInGame(p.getUniqueId())) {
            return;
        }
        e.setCancelled(true);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.getActivePotionEffects().forEach(effect -> p.removePotionEffect(effect.getType()));
        // 建造者回场地中央，其他人留在原地（掉虚空由 enforceStates 拉回）
        if (manager.plugin().getGame().isBuilder(p.getUniqueId())) {
            var arena = manager.plugin().getArena();
            if (arena.isReady()) {
                p.teleport(arena.getBuildStart().add(0, 2, 0));
            }
        }
        Chat.send(p, "&a你复活了！（小游戏中不会死亡）");
        // 双保险：无论死亡过程中 tab 状态是否被意外清掉，复活后立即重设为"在游戏中"，
        // 下一轮 tab 刷新（≤1s）就会恢复显示 [已加入·猜猜乐] / [等待中·猜猜乐]
        if (manager.plugin().getGame().getPhase() == com.buildbattle.gtb.game.GameManager.Phase.PLAYING) {
            com.hub.api.HubAPI.setPlaying(p.getUniqueId(), "猜猜乐");
        } else {
            com.hub.api.HubAPI.setGame(p.getUniqueId(), "猜猜乐");
        }
                return;
        }
        if (event instanceof PlayerRespawnEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!manager.plugin().getGame().isInGame(id)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(manager.plugin(), () -> {
            Player pp = Bukkit.getPlayer(id);
            if (pp == null || !pp.isOnline()) {
                return;
            }
            var game = manager.plugin().getGame();
            if (!game.isInGame(id)) {
                return;
            }
            // 恢复游戏状态（tab 重新显示）：对局中=[已加入]，等待中=[等待中]
            if (game.getPhase() == com.buildbattle.gtb.game.GameManager.Phase.PLAYING) {
                com.hub.api.HubAPI.setPlaying(id, "猜猜乐");
            } else {
                com.hub.api.HubAPI.setGame(id, "猜猜乐");
            }
            var arena = manager.plugin().getArena();
            pp.setInvulnerable(true);
            if (game.isBuilder(id)) {
                pp.setGameMode(GameMode.CREATIVE);
                if (arena.isReady()) {
                    pp.teleport(arena.getBuildStart().add(0, 2, 0));
                }
            } else {
                pp.setGameMode(GameMode.SPECTATOR);
                if (arena.isReady()) {
                    pp.teleport(arena.getBuildStart());
                }
            }
            if (game.getPhase() == com.buildbattle.gtb.game.GameManager.Phase.WAITING) {
                game.giveWaitingItems(pp);
            }
        }, 2L);
                return;
        }
        if (event instanceof PlayerInteractEntityEvent e) {
        // 按名字识别地板商店村民（兼容重启后由存档恢复、内存引用丢失的旧 NPC），不再只对比内存引用
        if (!manager.plugin().isFloorNpcEntity(e.getRightClicked())) {
            return;
        }
        e.setCancelled(true); // 阻止打开村民交易界面
        var game = manager.plugin().getGame();
        UUID id = e.getPlayer().getUniqueId();
        if (!game.isInGame(id) || !game.isBuilder(id)) {
            Chat.send(e.getPlayer(), "&c只有当前建造者能更换地板！");
            return;
        }
        manager.plugin().getArena().openFloorMenu(e.getPlayer());
                return;
        }
        if (event instanceof InventoryClickEvent e) {
        String title = PlainTextComponentSerializer.plainText().serialize(e.getView().title());
        if (!"地板商店".equals(title)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof org.bukkit.entity.Player p)) {
            return;
        }
        if (e.getClickedInventory() == null
                || e.getClickedInventory() != e.getView().getTopInventory()) {
            return;
        }
        var game = manager.plugin().getGame();
        UUID id = p.getUniqueId();
        if (!game.isInGame(id) || !game.isBuilder(id)) {
            Chat.send(p, "&c只有当前建造者能更换地板！");
            p.closeInventory();
            return;
        }
        p.closeInventory();
        manager.plugin().getArena().applyFloorSelection(p, String.valueOf(e.getSlot() + 1));
                return;
        }
        if (event instanceof PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        manager.plugin().getGame().leave(e.getPlayer(), true);
        manager.plugin().getWandSessions().remove(id);
                return;
        }
        if (event instanceof PlayerDropItemEvent e) {
        if (manager.plugin().getGame().isInGame(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
        // 建场工具不允许丢弃（公共库按 PDC 标记识别）
        if (manager.plugin().getSetupMode().isTool(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
        }
                return;
        }
        if (event instanceof FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && manager.plugin().getGame().isInGame(p.getUniqueId())) {
            e.setCancelled(true);
        }
                return;
        }
    }

    // ---- 原 GameListener 的私有 helper ----

    private boolean inArenaWorld(org.bukkit.Location loc) {
        var arena = manager.plugin().getArena();
        if (arena == null || !arena.isReady() || arena.getMin() == null || loc == null) {
            return false;
        }
        return loc.getWorld() != null && loc.getWorld().equals(arena.getMin().getWorld());
    }

    private boolean allowEvalExplosion(org.bukkit.Location loc) {
        if (!manager.plugin().getGame().isEvaluating()) {
            return false;
        }
        var arena = manager.plugin().getArena();
        return arena.isReady() && arena.containsXZ(loc, 0);
    }

    private void filterEvalBlocks(java.util.List<org.bukkit.block.Block> blocks) {
        var arena = manager.plugin().getArena();
        if (!arena.isReady()) {
            blocks.clear();
            return;
        }
        int buildHeight = manager.plugin().getConfig().getInt("game.max-build-height", 40);
        int topY = arena.getMax().getBlockY() + buildHeight;
        int floorY = arena.getMin().getBlockY();
        blocks.removeIf(b -> {
            org.bukkit.Location loc = b.getLocation();
            if (!arena.containsXZ(loc, 0) || loc.getY() > topY) {
                return true; // 场地外 / 超出建造高度 → 不炸
            }
            if (loc.getBlockY() <= floorY) {
                return true; // 地板那一层（及以下）永远不炸：地板材质可被玩家换过，不能只靠快照比对
            }
            // 场地原始快照里有该位置且类型未变 → 场地原有结构 → 不炸
            org.bukkit.block.data.BlockData snap = arena.getSnapshotAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            return snap != null && snap.getMaterial() == b.getType();
        });
    }

    private boolean sameBlock(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) return false;
        return a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private boolean inArena(Block block) {
        var arena = manager.plugin().getArena();
        if (!arena.isReady()) {
            return false;
        }
        Location min = arena.getMin();
        Location max = arena.getMax();
        return block.getX() >= min.getBlockX() && block.getX() <= max.getBlockX()
                && block.getY() >= min.getBlockY() && block.getY() <= max.getBlockY()
                && block.getZ() >= min.getBlockZ() && block.getZ() <= max.getBlockZ();
    }

    private boolean isFloorBlock(Block block) {
        var arena = manager.plugin().getArena();
        if (!arena.isReady()) {
            return false;
        }
        Location min = arena.getMin();
        Location max = arena.getMax();
        return block.getY() == min.getBlockY()
                && block.getX() >= min.getBlockX() && block.getX() <= max.getBlockX()
                && block.getZ() >= min.getBlockZ() && block.getZ() <= max.getBlockZ();
    }
}
