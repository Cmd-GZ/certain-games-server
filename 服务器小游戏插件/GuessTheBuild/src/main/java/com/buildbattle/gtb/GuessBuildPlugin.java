package com.buildbattle.gtb;

import com.buildbattle.gtb.arena.Arena;
import com.buildbattle.gtb.arena.ArenaManager;
import com.buildbattle.gtb.arena.WandSession;
import com.buildbattle.gtb.command.GTBCommand;
import com.buildbattle.gtb.game.GameManager;
import com.buildbattle.gtb.listener.GameListener;
import com.buildbattle.gtb.util.Chat;
import com.buildbattle.gtb.words.WordManager;
import com.hub.util.SetupMode;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.block.Action;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GuessBuildPlugin extends JavaPlugin {

    private static GuessBuildPlugin instance;

    private GameManager game;
    private ArenaManager arenas;
    private WordManager wordManager;
    private Villager floorNpc;
    private Location joinSignLoc;
    private Location floorSignLoc;
    private final Map<UUID, WandSession> wandSessions = new ConcurrentHashMap<>();

    /** 公共库建场模式（MiniGameLib）：备份背包 → 发放工具 → 保存/退出时还原。 */
    private SetupMode setupMode;

    /** 建场工具：木棍=选区（左右键），纸=保存，屏障=退出（退出与背包还原由公共库处理）。 */
    private static final List<SetupMode.Tool> SETUP_TOOLS = List.of(
            SetupMode.Tool.both(Material.STICK, "pos", "&e场地选区木棒",
                    "&7左键：选择第一角 &8/ &7右键：选择第二角"),
            SetupMode.Tool.right(Material.PAPER, "save", "&6保存场地",
                    "&7右键：保存选区到当前编辑场地"),
            SetupMode.Tool.right(Material.BARRIER, "exit", "&c退出配置模式",
                    "&7右键：退出并恢复背包"));

    /** 建场模式回调：选区木棍 → WandSession；保存工具 → 与 /gtb setup save 同一条路径。 */
    private final SetupMode.Handler setupHandler = new SetupMode.Handler() {

        @Override
        public void onTool(Player p, String arenaName, String toolId, Action action, Block clicked) {
            if (!"pos".equals(toolId) || clicked == null) {
                return;
            }
            WandSession session = wandSessions.computeIfAbsent(p.getUniqueId(), k -> new WandSession());
            Location block = clicked.getLocation();
            if (action == Action.LEFT_CLICK_BLOCK) {
                session.pos1 = block;
                Chat.send(p, "&a已选择第一角 &e" + coord(block) + "&a，再用右键选择对角。");
            } else if (action == Action.RIGHT_CLICK_BLOCK) {
                session.pos2 = block;
                Chat.send(p, "&a已选择第二角 &e" + coord(block) + "&a，输入 &e/gtb setup save&a 保存场地。");
            }
        }

        @Override
        public void onSave(Player p, String arenaName) {
            saveSetupArena(p);
        }
    };

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        setupMode = new SetupMode(this, "gtb-setup-tool");

        wordManager = new WordManager(this);
        arenas = new ArenaManager(this);
        arenas.loadAll();
        // 补齐生效场地屏障墙（延迟 10 tick 避免启动卡顿；场地未配置时无动作）
        Bukkit.getScheduler().runTaskLater(this, getArena()::ensureBarrierWalls, 10L);

        game = new GameManager(this);

        GTBCommand command = new GTBCommand(this);
        var gtb = getCommand("gtb");
        if (gtb != null) {
            gtb.setExecutor(command);
            gtb.setTabCompleter(command);
        }

        // 独立退出命令 /leave（等待/游戏中都可用）
        var leaveCmd = getCommand("leave");
        if (leaveCmd != null) {
            leaveCmd.setExecutor((sender, cmd, label, args) -> {
                if (!(sender instanceof org.bukkit.entity.Player p)) {
                    sender.sendMessage("\u4ec5\u73a9\u5bb6\u53ef\u7528\u3002");
                    return true;
                }
                if (game.isInGame(p.getUniqueId())) {
                    game.leave(p, false);
                } else {
                    Chat.send(p, "&c\u4f60\u5f53\u524d\u4e0d\u5728\u6e38\u620f\u4e2d\u3002");
                }
                return true;
            });
        }

        getServer().getPluginManager().registerEvents(new GameListener(this), this);

        // 世界加载完成后处理"地板商店"NPC（默认只在场地角落生成，可用 game.floor-npc 关闭）与主城加入告示牌
        Bukkit.getScheduler().runTaskLater(this, this::initFloorNpc, 20L);
        Bukkit.getScheduler().runTaskLater(this, this::spawnJoinSign, 20L);
        // 读取自定义地板商店告示牌位置（若之前用 /gtb floor sign 放置过）
        // 走 Worlds.readLocation：固定世界照旧读序列化 Location，模板/房间作用域读 tpl: 令牌。
        if (getConfig().isSet("floor-sign")) {
            floorSignLoc = com.hub.world.Worlds.readLocation(getConfig(), "floor-sign");
        }
        // 启动后上报场地给 GameHub 中央菜单（延迟等待 GameHub 就绪；场地未配置则不上报）
        Bukkit.getScheduler().runTaskLater(this, () -> game.reportArenaToHub(), 40L);
        // 玩家执行 /hub 或右键回主城道具时，GameHub 回调这里把玩家踢出游戏（防递归）
        com.hub.api.HubAPI.registerLobbyHandler(p -> game.leave(p, false));

        getLogger().info("建筑猜猜乐 v" + getPluginMeta().getVersion() + " 已启用，使用 /gtb help 查看帮助。");
    }

    @Override
    public void onDisable() {
        // 关服兜底：还停留在建场模式的 OP 背包先由公共库还原（早于游戏状态还原，避免互相覆盖）
        if (setupMode != null) {
            setupMode.exitAll();
        }
        if (game != null) {
            game.restoreAllOnDisable();
        }
        getLogger().info("建筑猜猜乐 已禁用。");
    }

    /** 重载配置、词库和场地（游戏进行中时词库/场地改动下一轮生效）。 */
    public void reloadAll() {
        reloadConfig();
        wordManager.reload();
        arenas.loadAll();
        initFloorNpc();
        getLogger().info("配置、词库、场地已重载。");
    }

    public static GuessBuildPlugin getInstance() {
        return instance;
    }

    public GameManager getGame() {
        return game;
    }

    /** 当前生效场地（未配置时返回兜底空场地，不会为 null）。 */
    public Arena getArena() {
        return arenas.getActive();
    }

    public ArenaManager getArenas() {
        return arenas;
    }

    public WordManager getWordManager() {
        return wordManager;
    }

    public Map<UUID, WandSession> getWandSessions() {
        return wandSessions;
    }

    /** 公共库建场模式实例（命令 /setup 与监听器都用它分发）。 */
    public SetupMode getSetupMode() {
        return setupMode;
    }

    /** 供选区命令使用：保存到当前编辑目标场地，返回场地名（失败为 null）。 */
    public String saveArenaSelection(Location pos1, Location pos2) {
        return arenas.saveSelection(pos1, pos2);
    }

    // ---------- 建场模式（公共库 SetupMode） ----------

    /** 进入建场模式：用当前编辑目标场地（arenaName 为 null 时取 ArenaManager 的编辑目标）。 */
    public void enterSetup(Player p) {
        enterSetup(p, null);
    }

    /**
     * 进入建场模式（备份背包 → 发放木棍/纸/屏障工具），并开始一个新的选区会话。
     * 场上编辑目标（ArenaManager.editingName）与库里的场地名保持一致，保存时写入同一场地。
     */
    public void enterSetup(Player p, String arenaName) {
        String target = arenaName != null ? arenaName : arenas.getEditingName();
        if (target == null) {
            target = "默认场地";
        }
        if (arenas.hasArena(target)) {
            arenas.setEditing(target);
        }
        if (setupMode.isEditing(p)) {
            // 已在建场模式：先退出（还原背包）再重新进入，保证换场地后工具/备份状态干净
            setupMode.exit(p);
        }
        wandSessions.put(p.getUniqueId(), new WandSession());
        setupMode.enter(p, target, SETUP_TOOLS, setupHandler);
    }

    /** 退出建场模式（公共库负责还原背包与状态），同时清掉本地选区会话。 */
    public void exitSetup(Player p) {
        wandSessions.remove(p.getUniqueId());
        setupMode.exit(p);
    }

    /**
     * 保存选区到当前编辑目标场地（/gtb setup save 与建场模式的"保存"工具共用这一条路径）：
     * 校验选区与体积 → 写配置 + 快照 → 移动地板商店 NPC → 上报主城中央菜单。
     */
    public void saveSetupArena(Player p) {
        WandSession session = wandSessions.get(p.getUniqueId());
        if (session == null || !session.complete()) {
            Chat.send(p, "&c请先用木棍左键/右键选择两个角点。");
            return;
        }
        long volume = (long) (Math.abs(session.pos1.getBlockX() - session.pos2.getBlockX()) + 1)
                * (Math.abs(session.pos1.getBlockY() - session.pos2.getBlockY()) + 1)
                * (Math.abs(session.pos1.getBlockZ() - session.pos2.getBlockZ()) + 1);
        if (volume > 200_000) {
            Chat.send(p, "&c场地过大（超过 20 万格），请缩小选区。");
            return;
        }
        String arenaName = saveArenaSelection(session.pos1, session.pos2);
        if (arenaName == null) {
            Chat.send(p, "&c场地保存失败：两个角点必须位于同一世界且体积不小于 16 格。");
            return;
        }
        wandSessions.remove(p.getUniqueId());
        Chat.send(p, "&a场地「&e" + arenaName + "&a」已保存！快照已记录，每轮结束会自动还原。");
        spawnFloorNpc(); // 场地变了，把地板商店 NPC 移到新场地角落
        game.reportArenaToHub(); // 刷新中央菜单场地列表
    }

    private static String coord(Location loc) {
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }

    /**
     * 开服/重载时处理"地板商店"NPC：
     * <ul>
     *   <li>{@code game.floor-npc: true}（默认）→ 在场地西南角重新生成一个；</li>
     *   <li>{@code false} → 不自动生成，但只清理"场地世界之外"的残留（比如以前误生成在主城的那个），
     *       场地里的 NPC 保留，玩家自己用 /gtb floor npc 放的也不会被删。</li>
     * </ul>
     */
    private void initFloorNpc() {
        if (!getConfig().getBoolean("game.floor-npc", true)) {
            int removed = removeFloorNpcs(false);
            getLogger().info("已按配置关闭地板商店 NPC 自动生成（game.floor-npc: false），清理场地外残留 "
                    + removed + " 个。");
            return;
        }
        spawnFloorNpc();
    }

    /**
     * 生成"地板商店"村民：右键打开地板选择菜单。
     * 位置：建筑场地西南角内侧，面向场地中心；场地未设置时**不生成**（旧版会退回主城放一个，已去掉）。
     */
    public void spawnFloorNpc() {
        // 先清掉所有旧的地板商店村民（重启后世界存档会恢复一个旧村民，避免场上出现两个）
        removeFloorNpcs(true);
        Arena arena = getArena();
        if (!arena.isReady()) {
            getLogger().warning("场地尚未设置，跳过地板商店 NPC 生成（需要时用 /gtb floor npc 手动放置）。");
            return;
        }
        // 场地内西南角（屏障墙内侧，建造者能交互到）
        int bx = arena.getMin().getBlockX() + 1;
        int bz = arena.getMin().getBlockZ() + 1;
        Location loc = new Location(arena.getWorld(), bx + 0.5, arena.getMax().getBlockY() + 1.0, bz + 0.5);
        double dx = arena.getCenterX() - loc.getX();
        double dz = arena.getCenterZ() - loc.getZ();
        loc.setYaw((float) Math.toDegrees(Math.atan2(dx, dz)));
        spawnFloorNpcAt(loc);
    }

    /**
     * 在主城（0,0 附近地面高度）放置"右键加入游戏"告示牌。
     * 新主城地图中心在 (0,0) 附近，地面高度用 getHighestBlockYAt 自动探测，
     * 不再硬编码旧地图的 y=-60。
     */
    public void spawnJoinSign() {
        if (getConfig().isSet("join-sign")) {
            Object raw = getConfig().get("join-sign");
            if (raw instanceof Location loc && loc.getWorld() != null) {
                joinSignLoc = loc;
                getLogger().info("加入游戏告示牌位置已从配置读取。");
                return;
            }
        }
        World world = Bukkit.getWorlds().get(0);
        int groundY = Math.max(world.getHighestBlockYAt(0, -6), 1);
        Location loc = new Location(world, 0, groundY + 1, -6);
        Block block = world.getBlockAt(loc);
        block.setType(Material.OAK_SIGN);
        Sign sign = (Sign) block.getState();
        sign.line(0, Chat.color("&6&l建筑猜猜乐"));
        sign.line(1, Chat.color("&a右键加入游戏"));
        sign.line(2, Chat.color("&7(/gtb join)"));
        sign.line(3, net.kyori.adventure.text.Component.empty());
        sign.update();
        joinSignLoc = loc.clone();
        getConfig().set("join-sign", joinSignLoc);
        saveConfig();
        getLogger().info("加入游戏告示牌已放置于主城 (0," + (groundY + 1) + ",-6)。");
    }

    public Location getJoinSignLoc() {
        return joinSignLoc;
    }

    /** OP /gtb joinsign：把玩家面前一格设为加入告示牌（方向随放置）。 */
    public void placeJoinSign(Player p) {
        Block block = p.getLocation().getBlock().getRelative(p.getFacing());
        block.setType(Material.OAK_SIGN);
        Sign sign = (Sign) block.getState();
        sign.line(0, Chat.color("&6&l建筑猜猜乐"));
        sign.line(1, Chat.color("&a右键加入游戏"));
        sign.line(2, Chat.color("&7(/gtb join)"));
        sign.line(3, net.kyori.adventure.text.Component.empty());
        sign.update();
        joinSignLoc = block.getLocation();
        getConfig().set("join-sign", joinSignLoc);
        saveConfig();
        Chat.send(p, "&a已将加入告示牌放置于 &e" + block.getX() + ", " + block.getY() + ", " + block.getZ() + "&a，右键即可加入。");
    }

    /** OP /gtb setup clear：删除当前编辑目标场地的配置与快照（含屏障墙恢复由管理员自行处理）。 */
    public void clearCurrentArena() {
        ArenaManager mgr = getArenas();
        String target = mgr.getEditingName();
        if (target == null || !mgr.removeArena(target)) {
            getLogger().info("没有可删除的场地。");
            return;
        }
        getLogger().info("场地「" + target + "」已清除。");
    }

    public Villager getFloorNpc() {
        return floorNpc;
    }

    /**
     * 清理"地板商店"村民。
     * <p>
     * 旧 NPC 是 {@code setPersistent(true)} 后随世界存档保存的，服务器重启会被恢复出来，
     * 若不清除会出现"两个村民、右键旧的无用"的问题；而按名字识别可以连内存引用丢失的旧 NPC 一起清掉。
     *
     * @param includeArenaWorld true = 连场地世界里的也一起清（准备重新生成时用）；
     *                          false = 只清场地世界之外的残留（主城那种），保留场地里的 NPC
     * @return 清掉的数量
     */
    private int removeFloorNpcs(boolean includeArenaWorld) {
        floorNpc = null;
        int removed = 0;
        Arena arena = getArena();
        World arenaWorld = arena.isReady() ? arena.getWorld() : null;
        for (World w : Bukkit.getWorlds()) {
            if (!includeArenaWorld && arenaWorld != null && w.equals(arenaWorld)) {
                continue; // 保留场地里的地板商店 NPC
            }
            for (Entity e : w.getEntities()) {
                if (isFloorNpcEntity(e)) {
                    e.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * 判断实体是否为"地板商店"村民：按名字识别。
     * 不依赖内存引用 {@link #floorNpc}，重启后由存档恢复、引用丢失的同名旧村民也能正确识别。
     */
    public boolean isFloorNpcEntity(Entity e) {
        if (!(e instanceof Villager v) || !v.isValid()) {
            return false;
        }
        if (!v.isCustomNameVisible() || v.customName() == null) {
            return false;
        }
        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(v.customName());
        return name != null && name.contains("地板商店");
    }

    /** OP /gtb floor npc：在玩家面前生成地板商店 NPC（无敌、不可推动、不会消失），替换旧的。 */
    public void placeFloorNpc(Player p) {
        // 同样先清掉残留的旧地板商店村民，避免场上出现两个
        removeFloorNpcs(true);
        Location loc = p.getLocation().clone();
        if (loc.getWorld() == null) {
            Chat.send(p, "&c无法获取所在世界。");
            return;
        }
        spawnFloorNpcAt(loc);
        Chat.send(p, "&a地板商店 NPC 已生成在你面前（无敌、不可推动、不会消失）。");
    }

    /** 在指定位置生成地板商店村民（场地角落与 /gtb floor npc 共用）。 */
    private void spawnFloorNpcAt(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        floorNpc = world.spawn(loc, Villager.class, v -> {
            v.customName(Chat.color("&e地板商店 &7· 右键更换地板"));
            v.setCustomNameVisible(true);
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setCollidable(false);
            v.setGravity(false); // 地板是水/岩浆时也不浮动
            v.setPersistent(true); // 防止被实体清理机制移除
        });
        getLogger().info("地板商店 NPC 已生成于 " + world.getName() + " ("
                + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")。");
    }

    public Location getFloorSignLoc() {
        return floorSignLoc;
    }

    /** OP /gtb floor sign：在玩家面前放置"地板商店"告示牌（右键打开地板商店，永远不会被杀死）。 */
    public void placeFloorSign(Player p) {
        Block block = p.getLocation().getBlock().getRelative(p.getFacing());
        block.setType(Material.OAK_SIGN);
        Sign sign = (Sign) block.getState();
        sign.line(0, Chat.color("&6&l地板商店"));
        sign.line(1, Chat.color("&a右键更换地板"));
        sign.line(2, Chat.color("&7建造者可用"));
        sign.line(3, net.kyori.adventure.text.Component.empty());
        sign.update();
        floorSignLoc = block.getLocation();
        // 同上：房间世界里放告示牌时写 tpl: 令牌，绝不把房间世界名写进磁盘
        com.hub.world.Worlds.writeLocation(getConfig(), "floor-sign", floorSignLoc);
        saveConfig();
        Chat.send(p, "&a地板商店告示牌已放置于 &e" + block.getX() + ", " + block.getY() + ", " + block.getZ()
                + "&a，右键即可更换地板（不会被杀死）。");
    }
}
