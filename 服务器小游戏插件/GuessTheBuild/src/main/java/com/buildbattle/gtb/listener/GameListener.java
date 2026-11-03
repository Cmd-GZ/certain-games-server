package com.buildbattle.gtb.listener;

import com.buildbattle.gtb.GuessBuildPlugin;
import com.buildbattle.gtb.util.Chat;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;

import java.util.UUID;

/** 聊天猜词、木棍选区、方块保护、加入告示牌、退出/丢弃事件。 */
public class GameListener implements Listener {

    private final GuessBuildPlugin plugin;

    public GameListener(GuessBuildPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent event) {
        var game = plugin.getGame();
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        if (!game.isInGame(id)) {
            return;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        boolean isBuilder = game.isBuilder(id);
        if (isBuilder && game.isBuilderChoosing(id)) {
            // 建造者选题目期间：消息全屏蔽（防候选泄露）
            event.setCancelled(true);
            event.viewers().clear();
        } else if (!isBuilder && game.isCorrectAnswer(text)) {
            // 猜中答案：只保留发送者本人可见，屏蔽其他所有玩家（防答案泄露）
            event.viewers().removeIf(v -> !v.equals(p));
        } else if (game.isEvalScoreMessage(text)) {
            // 评分消息：拦截原始公屏，改由评分逻辑广播“XX 打了 X 分”
            event.setCancelled(true);
            event.viewers().clear();
        }
        // 其余（错误答案、正常聊天、建造者闲聊）照常公屏显示
        game.handleChat(p, text);
    }

    /**
     * 兜底：legacy 桥接事件。部分环境下 AsyncChatEvent 取消可能不联动
     * AsyncPlayerChatEvent，这里再确认一次并拦截公屏。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        if (event.isCancelled()) {
            return;
        }
        var game = plugin.getGame();
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        if (!game.isInGame(id)) {
            return;
        }
        boolean isBuilder = game.isBuilder(id);
        if (isBuilder && game.isBuilderChoosing(id)) {
            event.setCancelled(true);
            event.getRecipients().clear();
        } else if (!isBuilder && game.isCorrectAnswer(event.getMessage())) {
            event.getRecipients().removeIf(r -> !r.equals(p));
        } else if (game.isEvalScoreMessage(event.getMessage())) {
            // 评分消息：拦截原始公屏，改由评分逻辑广播“XX 打了 X 分”
            event.setCancelled(true);
            event.getRecipients().clear();
        }
        game.handleChat(p, event.getMessage());
    }

    /** 爆炸管理：评价阶段且爆炸中心在场地内时放行 TNT，但只允许破坏"上轮建造物"，场地外与场地原有结构不炸。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        if (!inArenaWorld(event.getLocation())) {
            return; // 别的世界（单挑场的风弹、其它游戏）一律不插手：一插手就会把它们的爆炸取消掉
        }
        if (allowEvalExplosion(event.getLocation())) {
            filterEvalBlocks(event.blockList());
        } else {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) {
        if (!inArenaWorld(event.getBlock().getLocation())) {
            return;
        }
        if (allowEvalExplosion(event.getBlock().getLocation())) {
            filterEvalBlocks(event.blockList());
        } else {
            event.setCancelled(true);
        }
    }

    /**
     * 爆炸是不是发生在自家场地所在的世界。
     * <p>
     * 关键：本插件的爆炸保护必须**限定在自己的世界**里。之前两个处理器在 HIGHEST 上无条件取消
     * 一切爆炸，而单挑（Duel）也在 HIGHEST 上放行风弹爆炸——同优先级下后注册的那个赢，
     * 注册顺序又取决于插件 jar 的读取顺序（Windows 与 Linux 不同），于是出现
     * "本地风弹能推人、传到面板就不能"这种随环境变化的怪问题。
     */
    private boolean inArenaWorld(org.bukkit.Location loc) {
        var arena = plugin.getArena();
        if (arena == null || !arena.isReady() || arena.getMin() == null || loc == null) {
            return false;
        }
        return loc.getWorld() != null && loc.getWorld().equals(arena.getMin().getWorld());
    }

    private boolean allowEvalExplosion(org.bukkit.Location loc) {
        if (!plugin.getGame().isEvaluating()) {
            return false;
        }
        var arena = plugin.getArena();
        return arena.isReady() && arena.containsXZ(loc, 0);
    }

    /**
     * 过滤评价爆炸可破坏的方块：只保留"场地内 + 建造高度内 + 不属于场地原始快照"的方块，
     * 即上轮建造者新放置的东西。场地原有结构（地板/墙/快照方块）和场地外的地图一律不炸。
     */
    private void filterEvalBlocks(java.util.List<org.bukkit.block.Block> blocks) {
        var arena = plugin.getArena();
        if (!arena.isReady()) {
            blocks.clear();
            return;
        }
        int buildHeight = plugin.getConfig().getInt("game.max-build-height", 40);
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

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // 等待道具（开始纸/退出床）
        if ((event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                || event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)
                && event.getItem() != null) {
            if (plugin.getGame().handleWaitingItem(event.getPlayer(), event.getItem())) {
                event.setCancelled(true);
                return;
            }
        }
        // 建场模式（公共库 SetupMode）：木棍左/右键=两个角点，纸=保存，屏障=退出并还原背包
        if (plugin.getSetupMode().handleInteract(event.getPlayer(), event.getAction(),
                event.getItem(), event.getClickedBlock())) {
            event.setCancelled(true);
            return;
        }
        // 主城告示牌：右键加入游戏
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getState() instanceof Sign
                && sameBlock(event.getClickedBlock().getLocation(), plugin.getJoinSignLoc())) {
            event.setCancelled(true); // 阻止打开告示牌编辑界面
            plugin.getGame().join(event.getPlayer());
            return;
        }
        // 地板商店告示牌：右键打开地板商店（不会被杀死）
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getState() instanceof Sign
                && sameBlock(event.getClickedBlock().getLocation(), plugin.getFloorSignLoc())) {
            event.setCancelled(true);
            plugin.getArena().openFloorMenu(event.getPlayer());
            return;
        }
        // 等待界面道具：床=退出；开始工具三级确认（纸→绿宝石→钻石→开始）
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (plugin.getGame().handleWaitingItem(event.getPlayer(), event.getItem())) {
                event.setCancelled(true); // 阻止放置/交互方块
            }
        }
    }

    private boolean sameBlock(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) return false;
        return a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    /** Paper 26.2：右键告示牌先触发此事件，cancel 后 PlayerInteractEvent 不再触发，所以在这里直接处理。 */
    @EventHandler
    public void onSignOpen(PlayerSignOpenEvent event) {
        if (plugin.getJoinSignLoc() != null
                && event.getSign() != null
                && sameBlock(event.getSign().getLocation(), plugin.getJoinSignLoc())) {
            event.setCancelled(true);
            plugin.getGame().join(event.getPlayer());
            return;
        }
        if (plugin.getFloorSignLoc() != null
                && event.getSign() != null
                && sameBlock(event.getSign().getLocation(), plugin.getFloorSignLoc())) {
            event.setCancelled(true);
            plugin.getArena().openFloorMenu(event.getPlayer());
        }
    }

    /**
     * 方块破坏保护：
     * 未加入游戏的玩家不能破坏任何方块；
     * 游戏内非建造者不能破坏（旁观者双保险）；建造者不能挖场地地板层。
     */
    /**
     * 破坏：**只在 GTB 场地范围内**表态（I3）。
     *
     * <p>改之前这两处 `setCancelled(true)` 是**无条件**的 —— 不在局里的玩家、
     * 甚至别的游戏的世界里，挖方块都会被 GTB 先吃掉。真实后果：
     * ① 别的游戏"局内禁破坏"到底生不生效根本验不出来（GTB 先取消了）；
     * ② 它属于不变量里点名的"全局否定"那一类（和 SkyPit 的伤害事故同型）。
     *
     * <p>现在：不在局内的玩家只在自己**场地范围内**受保护；局内非建造者同样只限场地内。
     */
    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        // OP 豁免：管理员可以自由破坏（主城建造/清理等）
        if (event.getPlayer().isOp()) {
            return;
        }
        Block block = event.getBlock();
        var game = plugin.getGame();
        UUID id = event.getPlayer().getUniqueId();
        if (!game.isInGame(id)) {
            if (inArena(block)) {
                event.setCancelled(true);
            }
            return;
        }
        if (!game.isBuilder(id)) {
            if (inArena(block)) {
                event.setCancelled(true);
            }
            return;
        }
        if (isFloorBlock(block)) {
            Chat.send(event.getPlayer(), "&c不能破坏场地地板！");
            event.setCancelled(true);
        }
    }

    /** 这块方块是否落在 GTB 场地范围内（用于把保护限制在自己地盘，见 {@link #onBreak}）。 */
    private boolean inArena(Block block) {
        var arena = plugin.getArena();
        if (!arena.isReady()) {
            return false;
        }
        Location min = arena.getMin();
        Location max = arena.getMax();
        return block.getX() >= min.getBlockX() && block.getX() <= max.getBlockX()
                && block.getY() >= min.getBlockY() && block.getY() <= max.getBlockY()
                && block.getZ() >= min.getBlockZ() && block.getZ() <= max.getBlockZ();
    }

    /**
     * 评价期间投掷物命中建造者：手动补上"受击变红"+ 击退 + 受击音效。
     * 原版鸡蛋/雪球对玩家零伤害，既不会红也不会响，所以这里手工模拟反馈。
     */
    @EventHandler
    public void onProjHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Projectile proj)) {
            return;
        }
        if (!(proj.getShooter() instanceof Player)) {
            return;
        }
        if (!(event.getHitEntity() instanceof Player victim)) {
            return;
        }
        if (!plugin.getGame().isEvaluating()) {
            return;
        }
        if (!plugin.getGame().isBuilder(victim.getUniqueId())) {
            return;
        }
        victim.playHurtAnimation(0f); // 受击动画（红屏），零伤害的投掷物不会自动触发
        org.bukkit.util.Vector dir = proj.getVelocity().clone();
        if (dir.lengthSquared() < 0.0001) {
            return;
        }
        victim.setVelocity(dir.normalize().multiply(0.7).setY(0.45));
        victim.getWorld().playSound(victim.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_HURT, 1.0f, 1.0f);
    }

    /**
     * 评价期间允许鸡蛋/雪球命中建造者（即使 pvp=false 也放行）。
     * 只放行投掷物：观众手里的 TNT 不属于这一类，保持被取消，避免把建造者炸死。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEvalHurt(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!(event.getDamager() instanceof org.bukkit.entity.Projectile)) {
            return;
        }
        if (!plugin.getGame().isEvaluating()) {
            return;
        }
        if (plugin.getGame().isBuilder(victim.getUniqueId())) {
            event.setCancelled(false);
        }
    }

    /** 评价道具无限：投掷鸡蛋/雪球、放烟花后自动补满 16 个。 */
    @EventHandler
    public void onEggThrow(PlayerEggThrowEvent event) {
        if (plugin.getGame().isEvaluating()) {
            event.getPlayer().getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.EGG, 16));
        }
    }

    @EventHandler
    public void onProjectile(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player p && plugin.getGame().isEvaluating()) {
            if (event.getEntity() instanceof Snowball) {
                p.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.SNOWBALL, 16));
            }
        }
    }

    @EventHandler
    public void onEvalInteract(PlayerInteractEvent event) {
        if (!plugin.getGame().isEvaluating()) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        var item = event.getItem();
        if (item != null && item.getType() == org.bukkit.Material.FIREWORK_ROCKET) {
            event.getPlayer().getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.FIREWORK_ROCKET, 16));
        }
    }

    /** 游戏内玩家免受跌落/窒息/爆炸伤害（评价阶段会掉到建筑顶、卡方块内，或被观众的 TNT 炸）。 */
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) {
            return;
        }
        if (!plugin.getGame().isInGame(p.getUniqueId())) {
            return;
        }
        switch (event.getCause()) {
            case FALL, SUFFOCATION, VOID -> {
                // 免摔落/窒息/虚空伤害（掉虚空由 enforceStates 拉回场地）
                event.setCancelled(true);
            }
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> {
                // 评价阶段观众会往建造者身上扔 TNT：挡掉爆炸伤害，避免被炸死
                // （击退和鸡蛋/雪球的受击动画不走伤害事件，不受影响）
                if (plugin.getGame().isEvaluating()) {
                    event.setCancelled(true);
                }
            }
            default -> {
            }
        }
    }

    /**
     * 游戏内玩家死亡：取消死亡、原地满血复活。
     * 若不处理，玩家死后重生会被 GameHub 拉回主城并清掉游戏状态，
     * tab 里就从 [已加入] 消失了；评价阶段被炸/岩浆等意外死亡也一律保命。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getPlayer();
        if (!plugin.getGame().isInGame(p.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        // 建造者回场地中央，其他人留在原地（掉虚空由 enforceStates 拉回）
        if (plugin.getGame().isBuilder(p.getUniqueId())) {
            var arena = plugin.getArena();
            if (arena.isReady()) {
                p.teleport(arena.getBuildStart().add(0, 2, 0));
            }
        }
        Chat.send(p, "&a你复活了！（小游戏中不会死亡）");
        // 双保险：无论死亡过程中 tab 状态是否被意外清掉，复活后立即重设为"在游戏中"，
        // 下一轮 tab 刷新（≤1s）就会恢复显示 [已加入·猜猜乐] / [等待中·猜猜乐]
        if (plugin.getGame().getPhase() == com.buildbattle.gtb.game.GameManager.Phase.PLAYING) {
            com.hub.api.HubAPI.setPlaying(p.getUniqueId(), "猜猜乐");
        } else {
            com.hub.api.HubAPI.setGame(p.getUniqueId(), "猜猜乐");
        }
    }

    /**
     * 兜底：玩家意外重生（取消死亡未生效）且仍在游戏中时，
     * 重新标记游戏状态并拉回场地，覆盖 GameHub 重生时拉回主城的处理。
     */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!plugin.getGame().isInGame(id)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player pp = Bukkit.getPlayer(id);
            if (pp == null || !pp.isOnline()) {
                return;
            }
            var game = plugin.getGame();
            if (!game.isInGame(id)) {
                return;
            }
            // 恢复游戏状态（tab 重新显示）：对局中=[已加入]，等待中=[等待中]
            if (game.getPhase() == com.buildbattle.gtb.game.GameManager.Phase.PLAYING) {
                com.hub.api.HubAPI.setPlaying(id, "猜猜乐");
            } else {
                com.hub.api.HubAPI.setGame(id, "猜猜乐");
            }
            var arena = plugin.getArena();
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
    }

    /** 是否为场地地板层方块（y = 场地最低层，且水平范围在场地内）。 */
    private boolean isFloorBlock(Block block) {
        var arena = plugin.getArena();
        if (!arena.isReady()) {
            return false;
        }
        Location min = arena.getMin();
        Location max = arena.getMax();
        return block.getY() == min.getBlockY()
                && block.getX() >= min.getBlockX() && block.getX() <= max.getBlockX()
                && block.getZ() >= min.getBlockZ() && block.getZ() <= max.getBlockZ();
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        // 按名字识别地板商店村民（兼容重启后由存档恢复、内存引用丢失的旧 NPC），不再只对比内存引用
        if (!plugin.isFloorNpcEntity(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true); // 阻止打开村民交易界面
        var game = plugin.getGame();
        UUID id = event.getPlayer().getUniqueId();
        if (!game.isInGame(id) || !game.isBuilder(id)) {
            Chat.send(event.getPlayer(), "&c只有当前建造者能更换地板！");
            return;
        }
        plugin.getArena().openFloorMenu(event.getPlayer());
    }

    /** 地板商店 GUI 点击：只有当前建造者可以切换地板。 */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!"地板商店".equals(title)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player p)) {
            return;
        }
        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        var game = plugin.getGame();
        UUID id = p.getUniqueId();
        if (!game.isInGame(id) || !game.isBuilder(id)) {
            Chat.send(p, "&c只有当前建造者能更换地板！");
            p.closeInventory();
            return;
        }
        p.closeInventory();
        plugin.getArena().applyFloorSelection(p, String.valueOf(event.getSlot() + 1));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        plugin.getGame().leave(event.getPlayer(), true);
        plugin.getWandSessions().remove(id);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.getGame().isInGame(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
        // 建场工具不允许丢弃（公共库按 PDC 标记识别）
        if (plugin.getSetupMode().isTool(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** 游戏内玩家不掉饥饿（等待与对局都在 players 里）。 */
    @EventHandler
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player p && plugin.getGame().isInGame(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
