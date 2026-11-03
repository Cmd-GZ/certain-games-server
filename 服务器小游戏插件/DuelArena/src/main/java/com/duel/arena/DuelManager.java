package com.duel.arena;

import com.hub.session.EndReason;

import com.hub.data.PlayerStats;
import com.hub.util.Chat;
import com.hub.util.LiveSidebar;
import com.hub.util.StartCountdown;
import com.hub.util.SetupMode;
import com.hub.util.WaitCountdown;
import com.hub.api.GameState;
import com.hub.api.HubAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.event.block.Action;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 单挑场核心管理：场配置、对战流程、战绩、告示牌。 */
public class DuelManager {

    /** D22：每个场地一个会话（多图游戏）。 */
    private final java.util.Map<String, DuelSession> sessions = new java.util.LinkedHashMap<>();
    private java.util.UUID pendingWinner;
    private boolean pendingHasWinner;

    private DuelSession ensureSession(String arenaName) {
        DuelSession s = sessions.get(arenaName);
        if (s == null || s.state().isFinal()) {
            s = new DuelSession(this, arenaName);
            sessions.put(arenaName, s);
        }
        return s;
    }


    public static final int KIT_GUI_SIZE = 54;
    public static final int BTN_SAVE = 41;
    public static final int BTN_CLEAR = 42;
    public static final int BTN_CLOSE = 43;
    private static final int INVINCIBLE_SECONDS = 3;
    private static final int COUNTDOWN_SECONDS = 3;
    private static final String SETUP_TOOL_KEY = "duel-setup-tool";
    private static final String LEAVE_BED_KEY = "duel-leave-bed";

    private final SetupMode setup;   // OP 建场模式（背包备份/工具发放/还原由公共库 MiniGameLib 负责）

    private final DuelPlugin plugin;
    private final LiveSidebar liveSidebar;
    private int boardTick;
    private final NamespacedKey leaveBedKey; // 退出床标记（只有本插件发的床才当道具用，玩家自己的床照常能放）
    private final Map<String, DuelArena> arenas = new LinkedHashMap<>();
    private final Map<UUID, String> playerArena = new LinkedHashMap<>();
    private final Map<String, WaitCountdown> waits = new LinkedHashMap<>(); // 场名 -> 等待室自动开局倒计时
    private final Map<UUID, String> editingKit = new LinkedHashMap<>(); // 正在编辑 kit 的 OP
    private final Map<String, List<Location>> signLocations = new LinkedHashMap<>(); // 场名 -> 场战绩告示牌
    private final Map<String, List<Location>> joinSigns = new LinkedHashMap<>();      // 场名 -> 加入告示牌
    private final List<Location> topSigns = new ArrayList<>();                        // 总胜场榜告示牌
    private final Map<UUID, PendingSign> pendingSign = new LinkedHashMap<>();         // OP -> 待点击绑定的告示牌类型
    private final Map<String, BukkitTask> countdownTasks = new LinkedHashMap<>(); // 场名 -> 开局读秒任务（多场并发时各管各的）
    private final Map<String, BukkitTask> fightWatchdogs = new LinkedHashMap<>(); // 场名 -> 战斗守护任务（无敌期/出界兜底拉回）
    private final Map<String, Map<Location, BlockState>> fightPlaced = new LinkedHashMap<>(); // 场名 -> 对战中玩家放置的方块（位置 -> 放置前状态）

    private FileConfiguration data;

    public DuelManager(DuelPlugin plugin) {
        this.plugin = plugin;
        this.liveSidebar = new LiveSidebar(plugin);
        this.setup = new SetupMode(plugin, SETUP_TOOL_KEY);
        this.leaveBedKey = new NamespacedKey(plugin, LEAVE_BED_KEY);
    }

    public void load() {
        startCountdown();
        // 场配置
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("arenas");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                DuelArena a = DuelArena.load(name, sec.getConfigurationSection(name));
                arenas.put(name, a);
            }
        }
        // 告示牌位置
        ConfigurationSection signSec = plugin.getConfig().getConfigurationSection("signs");
        if (signSec != null) {
            for (String key : signSec.getKeys(false)) {
                String arena = signSec.getString(key);
                Location loc = parseLocKey(key);
                if (arena != null && loc != null) {
                    signLocations.computeIfAbsent(arena, k -> new ArrayList<>()).add(loc);
                }
            }
        }
        // 加入告示牌
        ConfigurationSection joinSec = plugin.getConfig().getConfigurationSection("joinsigns");
        if (joinSec != null) {
            for (String key : joinSec.getKeys(false)) {
                String arena = joinSec.getString(key);
                Location loc = parseLocKey(key);
                if (arena != null && loc != null) {
                    joinSigns.computeIfAbsent(arena, k -> new ArrayList<>()).add(loc);
                }
            }
        }
        // 总胜场榜告示牌
        ConfigurationSection topSec = plugin.getConfig().getConfigurationSection("topsigns");
        if (topSec != null) {
            for (String key : topSec.getKeys(false)) {
                Location loc = parseLocKey(key);
                if (loc != null) {
                    topSigns.add(loc);
                }
            }
        }
        // 战绩
        // 玩家记录（唯一文件 plugins/MiniGameLib/players.yml，命名空间 duel）
        data = PlayerStats.load("duel", new File(plugin.getDataFolder(), "data.yml"));
        plugin.getLogger().info("已加载 " + arenas.size() + " 个决斗场。");
        // 启动后把配置中已有场地全部上报给 HubCore 中央菜单（延迟等待 HubCore 就绪）
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (DuelArena a : arenas.values()) {
                reportArena(a);
            }
        }, 40L);
    }

    /** 声明地盘（{@code docs/框架不变量.md} 的 I2）。多场地，每个场地一块 AABB。 */
    public void publishRegions() {
        com.hub.region.Regions.begin("duel");
        arenas.forEach((name, a) ->
                com.hub.region.Regions.box("duel", name, a.getPos1(), a.getPos2()));
    }

    public void saveAll() {
                    // **未就绪的场地跳过回写**：世界缺失/没加载成功时，内存里的坐标是空的，
            // 写回去等于把磁盘上的场地坐标抹掉（真实事故：删了 world_tr 后 TntRun 的场地被清空）。
            // 房间只替换"世界"，坐标仍以这里为准，所以绝不能丢。
publishRegions();   // 场地可能刚被改过，重新声明（I2）
        // R2 单一写者（docs/框架不变量.md）：先把磁盘上的当前值读进内存，再只重写自己负责的段。
        // 否则关服时（onDisable -> saveAll -> saveConfig）会用内存里的旧值整份覆盖 config.yml：
        // 线上手改了别的键，传上去又被旧值写回去，表现为"改了配置没生效"（和 BuildRun 同一个坑）。
        plugin.reloadConfig();

        ConfigurationSection sec = com.hub.world.Worlds.getOrCreate(plugin.getConfig(), "arenas");
        for (Map.Entry<String, DuelArena> e : arenas.entrySet()) {
            DuelArena a = e.getValue();
            // **未加载成功的场地跳过回写**：世界缺失时它内存里没有坐标，
            // 写回去等于把磁盘上的坐标抹掉（真实事故：删掉世界后场地配置被清空）。
            if (a.getPos1() == null || a.getPos1().getWorld() == null) {
                plugin.getLogger().warning("[保存] 场地 " + e.getKey()
                        + " 未加载（世界缺失？），跳过回写以免丢坐标");
                continue;
            }
            a.save(com.hub.world.Worlds.getOrCreate(sec, e.getKey()));
        }
        ConfigurationSection signSec = com.hub.world.Worlds.getOrCreate(plugin.getConfig(), "signs");
        for (Map.Entry<String, List<Location>> e : signLocations.entrySet()) {
            for (Location loc : e.getValue()) {
                signSec.set(locKey(loc), e.getKey());
            }
        }
        ConfigurationSection joinSec = com.hub.world.Worlds.getOrCreate(plugin.getConfig(), "joinsigns");
        for (Map.Entry<String, List<Location>> e : joinSigns.entrySet()) {
            for (Location loc : e.getValue()) {
                joinSec.set(locKey(loc), e.getKey());
            }
        }
        ConfigurationSection topSec = com.hub.world.Worlds.getOrCreate(plugin.getConfig(), "topsigns");
        for (Location loc : topSigns) {
            topSec.set(locKey(loc), true);
        }
        plugin.saveConfig();
        saveData();
    }

    private void saveData() {
        try {
            PlayerStats.save("duel", data);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存战绩失败: " + ex.getMessage());
        }
    }

    // ---------- 场管理 ----------

    public DuelArena getArena(String name) {
        return arenas.get(name);
    }

    public Map<String, DuelArena> getArenas() {
        return arenas;
    }

    public boolean createArena(String name) {
        if (arenas.containsKey(name)) {
            return false;
        }
        arenas.put(name, new DuelArena(name));
        return true;
    }

    public boolean removeArena(String name) {
        if (!arenas.containsKey(name)) {
            return false;
        }
        DuelArena a = arenas.get(name);
        if (a.getState() == DuelArena.State.FIGHTING) {
            return false;
        }
        for (UUID id : new ArrayList<>(a.getQueue())) {
            leaveArena(id);
        }
        arenas.remove(name);
        // 场已删除：终止并丢弃该场的等待倒计时
        WaitCountdown w = waits.remove(name);
        if (w != null) {
            w.cancel();
        }
        // 移除该场告示牌数据（场榜 + 加入牌）
        for (List<Location> group : new List[]{signLocations.remove(name), joinSigns.remove(name)}) {
            if (group == null) {
                continue;
            }
            for (Location loc : group) {
                if (loc.getBlock().getState() instanceof Sign) {
                    loc.getBlock().setType(Material.AIR);
                }
            }
        }
        HubAPI.removeArena("决斗", name);
        return true;
    }

    /** 场地改名：同步配置、告示牌、玩家/倒计时引用、场胜战绩与中央菜单；对战中禁止。 */
    public boolean renameArena(String oldName, String newName) {
        if (oldName == null || oldName.equals(newName) || !validName(newName) || arenas.containsKey(newName)) {
            return false;
        }
        DuelArena a = arenas.get(oldName);
        if (a == null || a.getState() == DuelArena.State.FIGHTING) {
            return false;
        }
        arenas.remove(oldName);
        a.setName(newName);
        arenas.put(newName, a);
        // 玩家 / kit 编辑 / 待绑定告示牌引用同步
        for (Map.Entry<UUID, String> e : playerArena.entrySet()) {
            if (e.getValue().equals(oldName)) {
                e.setValue(newName);
            }
        }
        for (Map.Entry<UUID, String> e : editingKit.entrySet()) {
            if (e.getValue().equals(oldName)) {
                e.setValue(newName);
            }
        }
        for (Map.Entry<UUID, PendingSign> e : pendingSign.entrySet()) {
            if (e.getValue().arena != null && e.getValue().arena.equals(oldName)) {
                e.setValue(new PendingSign(e.getValue().type, newName));
            }
        }
        // 倒计时实例按新名重挂（Host 回调直接引用场地对象，改名后 getName() 已返回新名）
        WaitCountdown wt = waits.remove(oldName);
        if (wt != null) {
            waits.put(newName, wt);
        }
        // 告示牌：场榜 + 加入牌
        List<Location> sl = signLocations.remove(oldName);
        if (sl != null) {
            signLocations.put(newName, sl);
            refreshArenaSigns(newName);
        }
        List<Location> jl = joinSigns.remove(oldName);
        if (jl != null) {
            joinSigns.put(newName, jl);
        }
        refreshJoinSigns();
        // 场胜战绩迁移（wins.per-arena.<场名>）
        ConfigurationSection oldWins = data.getConfigurationSection("wins.per-arena." + oldName);
        if (oldWins != null) {
            ConfigurationSection newWins = data.createSection("wins.per-arena." + newName);
            for (String k : oldWins.getKeys(false)) {
                newWins.set(k, oldWins.get(k));
            }
            data.set("wins.per-arena." + oldName, null);
        }
        saveAll(); // 配置与战绩落盘
        HubAPI.removeArena("决斗", oldName); // 菜单移除旧名
        reportArena(a); // 菜单上报新名
        return true;
    }

    /** 场地名校验：1~20 字符，仅中英文/数字/下划线/短横线（与 GTB 规则一致）。 */
    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > 20) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }

    /** 上报场地状态到 HubCore 中央菜单（羊毛行：绿=等待，红=对战中）。 */
    private void reportArena(DuelArena a) {
        boolean fighting = a.getState() == DuelArena.State.FIGHTING;
        HubAPI.setArena("决斗", a.getName(),
                fighting ? GameState.PLAYING : GameState.WAITING, a.getQueue().size(),
                fighting ? "对战中" : null);
        HubAPI.setArenaSpectator("决斗", a.getName(), a.getLobbyPoint());
    }

    // ---------- 配置模式（道具快速配置；背包备份/工具发放/还原由 MiniGameLib 的 SetupMode 负责） ----------

    public boolean isInSetup(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null && setup.isEditing(p);
    }

    public DuelArena getSetupArena(UUID id) {
        Player p = Bukkit.getPlayer(id);
        String name = p == null ? null : setup.editingArena(p);
        return name == null ? null : arenas.get(name);
    }

    /**
     * 配置工具回调：公共库按工具 id 分发，这里只改本插件自己的场地字段。
     * 单挑专属的 kit 工具在 "kit" 分支里打开原有的物品配置窗口。
     */
    private final SetupMode.Handler setupHandler = new SetupMode.Handler() {
        @Override
        public void onTool(Player p, String arenaName, String toolId, Action action, Block clicked) {
            DuelArena a = arenas.get(arenaName);
            if (a == null) {
                exitSetup(p);
                return;
            }
            switch (toolId) {
                case "pos" -> {
                    if (action == Action.LEFT_CLICK_BLOCK && clicked != null) {
                        a.setPos1(clicked.getLocation());
                        Chat.send(p, "&a已设置 &e" + arenaName + "&a 范围角1：&f" + shortLoc(clicked.getLocation())
                                + (a.getPos2() != null ? "（范围已定）" : "（接着右键定范围角2）"));
                        saveAll(); // 配置改动立即落盘，防止重启丢失
                    } else if (action == Action.RIGHT_CLICK_BLOCK && clicked != null) {
                        a.setPos2(clicked.getLocation());
                        Chat.send(p, "&a已设置 &e" + arenaName + "&a 范围角2：&f" + shortLoc(clicked.getLocation())
                                + (a.getPos1() != null ? "（范围已定）" : "（还差范围角1）"));
                        saveAll();
                    }
                }
                case "spawn" -> {
                    if (action == Action.LEFT_CLICK_BLOCK && clicked != null) {
                        a.setSpawn2(p.getLocation());
                        Chat.send(p, "&a已设置 &e" + arenaName + "&a 出生点2：&f" + shortLoc(p.getLocation()));
                        saveAll();
                    } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                        a.setSpawn1(p.getLocation());
                        Chat.send(p, "&a已设置 &e" + arenaName + "&a 出生点1：&f" + shortLoc(p.getLocation()));
                        saveAll();
                    }
                }
                case "kit" -> {
                    // 单挑专属：绿宝石工具打开本插件原有的物品配置窗口（两位玩家共用）
                    if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                        openKitEditor(p, a);
                    }
                }
                default -> {
                }
            }
        }

        @Override
        public void onSave(Player p, String arenaName) {
            saveAll();
            Chat.send(p, "&a已保存场 &e" + arenaName + "&a。");
        }
    };

    /** OP 进入某场的配置模式：发配置工具（背包备份与还原由公共库负责）。 */
    public boolean enterSetup(Player p, String arenaName) {
        if (!arenas.containsKey(arenaName)) {
            Chat.send(p, "&c决斗场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        if (setup.isEditing(p)) {
            Chat.send(p, "&c你已在配置模式中，先退出（屏障工具）再进入其他场。");
            return false;
        }
        // 工具顺序 = 快捷栏 0~4；save / exit 两个 id 由公共库自己处理
        setup.enter(p, arenaName, List.of(
                SetupMode.Tool.both(Material.STICK, "pos",
                        "&b范围工具", "&7左键=范围角1（pos1）&8/&7右键=范围角2（pos2）"),
                SetupMode.Tool.both(Material.GOLDEN_HOE, "spawn",
                        "&e出生点工具", "&7右键=出生点1（当前位置）&8/&7左键方块=出生点2"),
                SetupMode.Tool.right(Material.EMERALD, "kit",
                        "&a物品配置", "&7右键=打开物品配置窗口（两位玩家共用）"),
                SetupMode.Tool.right(Material.PAPER, "save",
                        "&6保存场", "&7右键=保存当前配置"),
                SetupMode.Tool.right(Material.BARRIER, "exit",
                        "&c退出配置模式", "&7右键=结束配置并恢复背包")
        ), setupHandler);
        Chat.send(p, "&a已进入 &e" + arenaName + "&a 的配置模式：");
        Chat.send(p, "&b木棍&7 左键=pos1 右键=pos2 圈范围");
        Chat.send(p, "&e金锄头&7 右键=出生点1 左键方块=出生点2（以你站位为准）");
        Chat.send(p, "&a绿宝石&7 物品配置窗口  &6纸&7 保存  &c屏障&7 退出");
        return true;
    }

    /** 退出配置模式并恢复背包。 */
    public void exitSetup(Player p) {
        setup.exit(p);
        Chat.send(p, "&7已退出配置模式，背包已恢复。");
    }

    /** 配置工具交互处理。返回是否被本插件消费（工具识别与 save/exit 在公共库）。 */
    public boolean handleSetupTool(Player p, Action action, Block clicked) {
        return setup.handleInteract(p, action, p.getInventory().getItemInMainHand(), clicked);
    }

    private String shortLoc(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
    }

    // ---------- 玩家会话 ----------

    /** 玩家是否在某场（等待或对战中）。 */
    public String arenaOf(UUID id) {
        return playerArena.get(id);
    }

    /** 玩家是否在任一场的对战中。 */
    public boolean inFight(UUID id) {
        String name = playerArena.get(id);
        if (name == null) {
            return false;
        }
        DuelArena a = arenas.get(name);
        return a != null && a.getState() == DuelArena.State.FIGHTING && a.isFighter(id);
    }

    /** 两名玩家是否在同一场。 */
    public boolean sameArena(UUID a, UUID b) {
        String na = playerArena.get(a);
        return na != null && na.equals(playerArena.get(b));
    }

    /** 位置是否为本插件的任意告示牌（场榜/加入牌/总榜）。 */
    private boolean sameBlock(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) return false;
        return a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    public boolean isDuelSign(Location loc) {
        for (List<Location> locs : signLocations.values()) {
            for (Location l : locs) {
                if (sameBlock(l, loc)) {
                    return true;
                }
            }
        }
        for (List<Location> locs : joinSigns.values()) {
            for (Location l : locs) {
                if (sameBlock(l, loc)) {
                    return true;
                }
            }
        }
        for (Location l : topSigns) {
            if (sameBlock(l, loc)) {
                return true;
            }
        }
        return false;
    }

    /** 玩家加入场。 */
    public boolean join(Player p, String arenaName) {
        DuelArena a = arenas.get(arenaName);
        if (a == null) {
            Chat.send(p, "&c决斗场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        UUID id = p.getUniqueId();
        if (playerArena.containsKey(id)) {
            Chat.send(p, "&c你已经在其他决斗场中。");
            return false;
        }
        if (a.getState() == DuelArena.State.FIGHTING) {
            Chat.send(p, "&c该决斗场正在进行对战，请稍后再来。");
            return false;
        }
        if (a.getQueue().contains(id)) {
            Chat.send(p, "&c你已经在该场排队了。");
            return false;
        }
        int max = plugin.getConfig().getInt("max-players", 2);
        if (a.getQueue().size() >= max) {
            Chat.send(p, "&c该决斗场已满员（&e" + a.getQueue().size() + "/" + max + "&c），请等待对局结束或加入其他决斗场。");
            return false;
        }
        if (!a.isReady()) {
            Chat.send(p, "&c该决斗场尚未配置完成（需要范围与两个出生点）。");
            return false;
        }
        DuelSession sess = ensureSession(arenaName);
        if (!sess.join(p)) {
            if (sess.players().isEmpty()) {
                sessions.remove(arenaName);
            }
            return false;
        }
        playerArena.put(id, arenaName);
        bindRoomIfAny();
        a.getQueue().add(id);
        a.recordJoin(id);   // D21

        p.getInventory().clear();
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        Location lobby = a.getLobbyPoint();
        if (lobby != null) {
            p.teleport(lobby);
        }
        giveLeaveBed(p);
        if (p.isOp()) {
            giveStartPaper(p);
        }
        Chat.send(p, "&a你已加入决斗场 &e" + arenaName + "&a，等待开始…（右键床退出）");
        broadcastToArena(a, "&e" + p.getName() + "&f 加入了决斗场（&e" + a.getQueue().size() + "&f/2）");
        com.hub.api.HubAPI.announceJoin(plugin.getName(), p.getName(), arenaName); // 全服公屏播报（带场名）
        HubAPI.setGame(id, "决斗");
        refreshJoinSigns();
        updateWaitCountdown(a);
        reportArena(a);
        return true;
    }

    /** 玩家离开场（等待中或结束后手动离开）。 */
    public boolean leaveArena(UUID id) {
        String name = playerArena.remove(id);
        // I1：从该场地的会话上摘掉自己（漏了会让玩家卡在"已在等待中"）
        if (name != null) {
            DuelSession sess = sessions.get(name);
            org.bukkit.entity.Player quitter = org.bukkit.Bukkit.getPlayer(id);
            if (sess != null && quitter != null && !sess.state().isFinal()) {
                sess.leave(quitter);
            }
        }
        if (name == null) {
            return false;
        }
        DuelArena a = arenas.get(name);
        if (a == null) {
            return true;
        }
        a.getQueue().remove(id);
        if (a.isFighter(id)) {
            // 对战中离开：对方获胜
            UUID opp = a.opponentOf(id);
            if (opp != null) {
                endFight(a, opp);
            }
        }
        Player p = Bukkit.getPlayer(id);
        if (p != null && p.isOnline()) {
            p.setGameMode(GameMode.SURVIVAL);
            HubAPI.teleportToLobby(p); // 回主城并拿菜单道具
            Chat.send(p, "&7你已离开决斗场。");
        } else {
        }
        if (a.getQueue().isEmpty()) {
            a.setState(DuelArena.State.WAITING);
        }
        HubAPI.clearGame(id);
        if (a.getState() == DuelArena.State.WAITING) {
            updateWaitCountdown(a);
        }
        refreshJoinSigns();
        reportArena(a);
        return true;
    }

    // ---------- 等待倒计时 ----------

    /** 退出床。 */
    public void giveLeaveBed(Player p) {
        org.bukkit.inventory.ItemStack bed = new org.bukkit.inventory.ItemStack(org.bukkit.Material.RED_BED);
        var meta = bed.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                    .deserialize("&c退出游戏 &7(右键)")));
            meta.getPersistentDataContainer().set(leaveBedKey, PersistentDataType.BYTE, (byte) 1);
            bed.setItemMeta(meta);
        }
        p.getInventory().setItem(8, bed);
    }

    /** 是否本插件发的退出床（按 PDC 标记判定；玩家自己拿的普通床不受影响）。 */
    public boolean isLeaveBedItem(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType() != Material.RED_BED) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(leaveBedKey, PersistentDataType.BYTE);
    }

    /** 玩家右键退出床。 */
    public void handleLeaveBed(Player p) {
        leaveArena(p.getUniqueId());
    }

    /** OP 的开始纸。 */
    public void giveStartPaper(Player p) {
        org.bukkit.inventory.ItemStack paper = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
        var meta = paper.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                    .deserialize("&a&l开始游戏 &7(右键)")));
            paper.setItemMeta(meta);
        }
        p.getInventory().setItem(4, paper);
    }

    /**
     * 房间化（R1.5 / D25）：如果这个游戏当前挂着**一个**房间，就把该房间对应的场地
     * **换绑到房间世界**（坐标不变，只换世界实例）。
     *
     * <p>为什么按"房间对应的地图"来绑：一个房间只服务一张地图（`Room.arena`），
     * 所以只有那张地图换绑，其它场地保持原样。
     */
    private void bindRoomIfAny() {
        // ① **父级委派优先**：派对叫我们来的，世界与场地名都由父级给（子游戏不自己搜房间）。
        com.hub.room.GameDelegation.Task task = com.hub.room.GameDelegation.current();
        if (task != null && "duel".equals(task.game()) && task.world() != null) {
            DuelArena a = arenas.get(task.arenaName());
            if (a != null && (a.getPos1() == null || !task.world().equals(a.getPos1().getWorld()))) {
                a.bindWorld(task.world());   // 只覆盖读取，不动存储
                plugin.getLogger().info("[DuelArena] 场地 " + a.getName() + " 已按父级委派换绑到世界 "
                        + task.world().getName());
            }
            return;
        }
        // ② 普通路径：房间上下文（/hub join 时框架告诉我们的），再退回"搜唯一房间"
        com.hub.room.RoomManager.Room room = com.hub.room.RoomContext.roomOf("duel");
        if (room == null) {
            room = com.hub.room.RoomManager.activeFor("duel");
        }
        if (room == null || room.world == null) {
            return;
        }
        DuelArena a = arenas.get(room.arena());
        if (a == null) {
            return;
        }
        if (a.getPos1() != null && room.world.equals(a.getPos1().getWorld())) {
            return;   // 已绑过
        }
        a.bindWorld(room.world);
        plugin.getLogger().info("[DuelArena] 场地 " + a.getName() + " 已换绑到房间 "
                + room.roomId + "（世界 " + room.world.getName() + "）");
    }



    /** D25：对局结束销毁房间（延迟 3 秒让结算先发出去）。 */
    private void destroyRoomAfterMatch() {
        // 只有"这个房间就是 duel 自己的"才销毁。被派对委派时房间属于派对，不能由我们删。
        com.hub.room.RoomManager.Room room = com.hub.room.RoomContext.roomOf("duel");
        if (room == null) {
            room = com.hub.room.RoomManager.activeFor("duel");
        }
        if (room == null || !"duel".equals(room.game())) {
            return;
        }
        final String roomId = room.roomId;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            boolean ok = com.hub.room.RoomManager.delete(roomId);
            plugin.getLogger().info("[DuelArena] 对局结束，销毁房间 " + roomId
                    + (ok ? "（成功）" : "（失败：房间已不存在）"));
        }, 60L);
    }

    /** 玩家右键开始纸。 */
    public void handleStartPaper(Player p) {
        String arenaName = playerArena.get(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        DuelArena a = arenas.get(arenaName);
        if (a == null || a.getState() != DuelArena.State.WAITING) {
            return;
        }
        // D21：管理员随时可开；**主持人**也可以开，但不能少于 2 人
        boolean isAdmin = p.isOp() || p.hasPermission("duel.admin");
        if (!isAdmin) {
            UUID host = wait(a).currentHost();
            if (host == null || !host.equals(p.getUniqueId())) {
                Chat.send(p, "&c只有本场**主持人**或管理员可以开始。");
                return;
            }
            if (a.getQueue().size() < 2) {
                Chat.send(p, "&c需要 &f2 名玩家&c才能开始（现在是 " + a.getQueue().size() + " 人）。");
                return;
            }
        }
        startFight(a);
    }

    /** 该场的等待倒计时（每场一份；OP 暂停/人数不足取消/满人 3 秒/达标 30 秒的规则在公共库）。 */
    public int waitingCountOf(String arenaName) {
        DuelArena a = arenas.get(arenaName);
        return a == null ? 0 : a.getQueue().size();
    }

    public int minPlayersOf(String arenaName) {
        return plugin.getConfig().getInt("min-players", 2);
    }

    public boolean isHostOf(Player p, String arenaName) {
        if (!"host".equalsIgnoreCase(plugin.getConfig().getString("flow", "upstream"))) {
            return false;
        }
        DuelArena a = arenas.get(arenaName);
        if (a == null || p == null) {
            return false;
        }
        UUID host = wait(a).currentHost();
        return host != null && host.equals(p.getUniqueId());
    }

    private WaitCountdown wait(DuelArena a) {
        return waits.computeIfAbsent(a.getName(), n -> new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return a.getQueue();
            }

            @Override
            public int minPlayers() {
                return 2; // 单挑场最少 2 人
            }

            @Override
            public int maxPlayers() {
                return plugin.getConfig().getInt("max-players", 2);
            }

            @Override
            public boolean hasOpQueued() {
                // 保留 OP 手动开始：OP 在队列中时暂停自动倒计时
                for (UUID id : a.getQueue()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOp()) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onTick(long secondsLeft) {
                for (UUID id : a.getQueue()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        Chat.actionBar(p, "&6开始倒计时：&e" + secondsLeft + "s");
                    }
                }
            }

            @Override
            public void onMessage(String message) {
                broadcastToArena(a, message);
            }

            // ---------- D21 主持人流程（配置 flow: host 才启用，默认走上游） ----------

                        @Override
            public boolean hostFlow() {
                // Duel 也走主持人流程（用户定）：**必须手动开始**（右键"开始纸"或 /duel start），
                // 倒计时超时则踢掉主持人 —— 目的是防止主持人恶意占着房间不开局。
                return "host".equalsIgnoreCase(plugin.getConfig().getString("flow", "upstream"));
            }

            @Override

            public int waitSeconds() {

                return Math.max(5, plugin.getConfig().getInt("wait-seconds", 300));

            }

            @Override

            public java.util.List<UUID> waitingOrder() {

                java.util.List<UUID> order = new ArrayList<>(a.getQueue());

                order.sort(java.util.Comparator

                        .comparingLong((UUID id) -> a.joinTickOf(id))

                        .thenComparingLong(id -> a.joinSeedOf(id)));

                return order;

            }

            @Override

            public void onHostTimeout(UUID host) {

                Player hp = Bukkit.getPlayer(host);

                String hname = hp == null ? host.toString() : hp.getName();

                a.getQueue().remove(host);

                playerArena.remove(host);

                DuelSession sess = sessions.get(a.getName());

                if (sess != null && hp != null && !sess.state().isFinal()) {

                    sess.leave(hp);

                }

                if (hp != null && hp.isOnline()) {

                    Chat.send(hp, "&c倒计时结束还没开始，你被移出本局（主持人超时）。");

                    com.hub.api.HubAPI.teleportToLobby(hp);

                }

                plugin.getLogger().info("[DuelArena] 场 " + a.getName() + "：主持人 " + hname

                        + " 超时未开始，已移出（剩余 " + a.getQueue().size() + " 人）");

                broadcastToArena(a, "&e主持人 &f" + hname + " &e超时未开始，已移出本局。");

                DuelManager.this.wait(a).update();

            }


            @Override
            public void onStart() {
                // 走内部开局方法（无权限校验）
                if (a.getQueue().size() >= 2) {
                    startFight(a);
                }
            }
        }));
    }

    /** 人数/状态变化后刷新该场的自动开局倒计时。 */
    private void updateWaitCountdown(DuelArena a) {
        wait(a).update();
    }

    /** 终止某场的自动开局倒计时。 */
    private void cancelWait(DuelArena a) {
        WaitCountdown w = waits.get(a.getName());
        if (w != null) {
            w.cancel();
        }
    }

    /** 倒计时后开战。 */
    private StartCountdown startCountdown;

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

    private void startFight(DuelArena a) {
        cancelWait(a);
        if (a.getQueue().size() < 2 || a.getState() == DuelArena.State.FIGHTING) {
            return;
        }
        UUID f1 = a.getQueue().get(0);
        UUID f2 = a.getQueue().get(1);
        if (startCountdown().begin(a.getName(), new ArrayList<>(a.getQueue()), () -> startFight(a))) {
            return;
        }
        a.getQueue().clear();
        a.setFighter1(f1);
        a.setFighter2(f2);
        a.setState(DuelArena.State.FIGHTING);
        // I5/I4：会话状态跟着走（之前只改了场地状态，会话一直停在 WAITING）
        DuelSession live = sessions.get(a.getName());
        if (live != null && live.state() == com.hub.session.SessionState.WAITING) {
            live.start();
        }
        a.setFightStartMs(System.currentTimeMillis());
        reportArena(a);
        // 对战开始：tab 从 [等待中] 变为 [已加入]
        HubAPI.setPlaying(f1, "决斗");
        HubAPI.setPlaying(f2, "决斗");

        Player p1 = Bukkit.getPlayer(f1);
        Player p2 = Bukkit.getPlayer(f2);
        broadcastToArena(a, "&6" + (p1 == null ? "?" : p1.getName()) + " &fvs &6"
                + (p2 == null ? "?" : p2.getName()) + "&f —— 对战开始！");
        for (Player p : new Player[]{p1, p2}) {
            if (p != null) {
                p.setInvulnerable(true);
            }
        }
        prepareFighter(a, f1, a.getSpawn1());
        prepareFighter(a, f2, a.getSpawn2());
        // 战斗守护：兜底强制无敌期站在原地、出界拉回出生点
        startFightWatchdog(a);

        // 屏幕中间 3/2/1 倒计时，结束后解除无敌
        cancelCountdown(a);
        BukkitTask countdown = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int left = INVINCIBLE_SECONDS;

            @Override
            public void run() {
                if (a.getState() != DuelArena.State.FIGHTING) {
                    cancelCountdown(a);
                    return;
                }
                if (left <= 0) {
                    for (UUID id : new UUID[]{f1, f2}) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null && p.isOnline()) {
                            p.setInvulnerable(false);
                            Chat.title(p, "&a开始！", "&f击杀对手即可获胜");
                        }
                    }
                    cancelCountdown(a);
                    return;
                }
                for (UUID id : new UUID[]{f1, f2}) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        Chat.title(p, "&e" + left, "&7无敌中，不能移动");
                    }
                }
                left--;
            }
        }, 0L, 20L);
        countdownTasks.put(a.getName(), countdown);
    }

    /** 取消某场地的开局读秒任务（按场名存放，多场同时开局不会互相打断）。 */
    private void cancelCountdown(DuelArena a) {
        BukkitTask t = countdownTasks.remove(a.getName());
        if (t != null) {
            t.cancel();
        }
    }

    /** 备战：清背包、给 kit、传送出生点、回满状态。 */
    private void prepareFighter(DuelArena a, UUID id, Location spawn) {
        Player p = Bukkit.getPlayer(id);
        if (p == null || !p.isOnline()) {
            return;
        }
        p.getInventory().clear();
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        if (!a.getKit().isEmpty()) {
            a.getKit().apply(p);
        }
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(20);
        p.setFoodLevel(20);
        if (spawn != null) {
            p.teleport(spawn);
        }
        Chat.send(p, "&6你获得了物品配置，去击败对手吧！");
    }

    /** 死亡处理：对方获胜。 */
    public void handleDeath(Player victim) {
        UUID id = victim.getUniqueId();
        String name = playerArena.get(id);
        if (name == null) {
            return;
        }
        DuelArena a = arenas.get(name);
        if (a == null || !a.isFighter(id)) {
            return;
        }
        UUID winner = a.opponentOf(id);
        endFight(a, winner);
    }

    /** 结束对战：记录胜场、恢复双方、刷新告示牌。 */
    private void updateLiveBoard(DuelArena a) {
        if (++boardTick % 20 != 0) {
            return;
        }
        List<UUID> players = new ArrayList<>();
        List<LiveSidebar.Line> lines = new ArrayList<>();
        for (UUID id : new UUID[]{a.getFighter1(), a.getFighter2()}) {
            if (id == null) {
                continue;
            }
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                continue;
            }
            players.add(id);
            lines.add(new LiveSidebar.Line("§e" + p.getName(), (int) Math.ceil(p.getHealth())));
        }
        liveSidebar.update("duel:" + a.getName(), "&c&l单挑 · 血量", players, lines);
    }

    /** 收场入口（I4）：先让会话 end()，再由 onEnd 回到 doEndFight。 */
    private void endFight(DuelArena a, UUID winnerId) {
        pendingWinner = winnerId;
        pendingHasWinner = winnerId != null;
        DuelSession sess = sessions.get(a.getName());
        if (sess == null || sess.state().isFinal()) {
            doEndFight(a.getName());
            return;
        }
        sess.end(EndReason.ROUND_END);
    }

    /** 真正的收场逻辑。赢家透传，玩家看到的结算不变。 */
    void doEndFight(String arenaName) {
        // 房间销毁已**收敛到框架**（GameSession.end → RoomManager.deleteOwnedBy），
        // 游戏不再自己删：否则同一份"房间归属"有两个写者（R2），且子游戏会误删父级房间。
        DuelArena a = arenas.get(arenaName);
        if (a == null) {
            return;
        }
        UUID winnerId = pendingHasWinner ? pendingWinner : null;
        pendingWinner = null;
        pendingHasWinner = false;
        liveSidebar.hide("duel:" + a.getName());
        UUID f1 = a.getFighter1();
        UUID f2 = a.getFighter2();
        Player w = winnerId == null ? null : Bukkit.getPlayer(winnerId);
        Player l1 = f1 == null ? null : Bukkit.getPlayer(f1);
        Player l2 = f2 == null ? null : Bukkit.getPlayer(f2);

        if (w != null && winnerId != null) {
            addWin(a.getName(), winnerId);
            String loserName = winnerId.equals(f1) ? (l2 == null ? "?" : l2.getName())
                    : (l1 == null ? "?" : l1.getName());
            // 只发给本场玩家（大厅/其他场看不到局内播报）
            broadcastToArena(a, "&6[" + a.getName() + "] &e" + w.getName() + "&a 击败了 &e" + loserName + "&a！");
            Chat.title(w, "&a胜利！", "&f你赢得了这场对决");
            if (l1 != null && l1.getUniqueId().equals(winnerId)) {
                Chat.title(l2 == null ? w : l2, "&c失败", "&f再接再厉");
            } else if (l2 != null && l2.getUniqueId().equals(winnerId)) {
                Chat.title(l1 == null ? w : l1, "&c失败", "&f再接再厉");
            }
        } else {
            broadcastToArena(a, "&7对战结束。");
        }

        // 对局结束：双方自动离场（恢复加入前的位置与状态）
        for (UUID id : new UUID[]{f1, f2}) {
            if (id != null) {
                afterFightLeave(id);
            }
        }
        a.setFighter1(null);
        a.setFighter2(null);
        a.setState(DuelArena.State.WAITING);
        reportArena(a);
        cancelCountdown(a);
        stopFightWatchdog(a);
        restoreFightBlocks(a.getName());
        refreshArenaSigns(a.getName());
        refreshJoinSigns();
        refreshTopSigns();
    }

    /** 对局结束后自动离场：恢复加入前位置、模式与背包。 */
    private void afterFightLeave(UUID id) {
        String name = playerArena.remove(id);
        if (name == null) {
            return;
        }
        HubAPI.clearGame(id);
        DuelArena a = arenas.get(name);
        if (a != null) {
            a.getQueue().remove(id);
        }
        Player p = Bukkit.getPlayer(id);
        if (p != null && p.isOnline()) {
            boolean dead = p.isDead() || p.getHealth() <= 0;
            p.getInventory().clear();
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            p.setInvulnerable(false);
            p.setGameMode(GameMode.SURVIVAL);
            if (!dead) {
                p.setHealth(20);
                p.setFoodLevel(20);
                HubAPI.teleportToLobby(p); // 回主城并拿菜单道具
            }
            // 死亡玩家：重生后由 HubCore（HubListener.onRespawn）自动送回主城并给菜单道具
            Chat.send(p, "&7对战结束，你已离开决斗场。");
        } else {
        }
    }

    /** 玩家当前所在场地名。 */
    public String playerArenaOf(UUID id) { return playerArena.get(id); }

    /** 玩家对战出生点（用于无敌期拉回）。 */
    public Location getFighterSpawn(UUID id) {
        String name = playerArena.get(id);
        if (name == null) return null;
        DuelArena a = arenas.get(name);
        if (a == null) return null;
        if (id.equals(a.getFighter1())) return a.getSpawn1();
        if (id.equals(a.getFighter2())) return a.getSpawn2();
        return null;
    }

    /** 玩家是否处于无敌倒计时中（战斗开始后 3 秒内）。 */
    public boolean invincible(UUID id) {
        String name = playerArena.get(id);
        if (name == null) {
            return false;
        }
        DuelArena a = arenas.get(name);
        return a != null && a.getState() == DuelArena.State.FIGHTING && a.isFighter(id)
                && System.currentTimeMillis() - a.getFightStartMs() < INVINCIBLE_SECONDS * 1000L;
    }

    /** 战斗守护任务（兜底）：无敌期内强制回出生点，出界强制拉回；事件监听漏触发时也能拦住。 */
    private void startFightWatchdog(DuelArena a) {
        stopFightWatchdog(a);
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (a.getState() != DuelArena.State.FIGHTING) {
                stopFightWatchdog(a);
                return;
            }
            updateLiveBoard(a);
            for (UUID id : new UUID[]{a.getFighter1(), a.getFighter2()}) {
                if (id == null) {
                    continue;
                }
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.isOnline()) {
                    continue;
                }
                if (invincible(id)) {
                    Location spawn = getFighterSpawn(id);
                    if (spawn != null && p.getLocation().distanceSquared(spawn) > 0.0001) {
                        p.teleport(spawn);
                    }
                } else if (!a.contains(p.getLocation())) {
                    handleOutOfBounds(p, p.getLocation());
                }
            }
        }, 2L, 2L); // 每 2 tick（约 0.1s）轮询，出界瞬间就拉回，不给外部判负留窗口
        fightWatchdogs.put(a.getName(), t);
    }

    private void stopFightWatchdog(DuelArena a) {
        BukkitTask t = fightWatchdogs.remove(a.getName());
        if (t != null) {
            t.cancel();
        }
    }

    /** 对战中玩家离开范围：视为死亡，对方获胜。loc 为该玩家所在/将到的位置。 */
    public void handleOutOfBounds(Player p, Location loc) {
        UUID id = p.getUniqueId();
        String name = playerArena.get(id);
        if (name == null) {
            return;
        }
        DuelArena a = arenas.get(name);
        if (a == null || a.getState() != DuelArena.State.FIGHTING || !a.isFighter(id)) {
            return;
        }
        if (a.contains(loc)) {
            return;
        }
        UUID opp = a.opponentOf(id);
        if (opp == null) {
            return;
        }
        broadcastToArena(a, "&c" + p.getName() + "&c 离开了决斗场范围，视为死亡，&e" + nameOf(opp) + "&a 获胜！");
        endFight(a, opp);
    }

    // ---------- 战斗中建造/还原 ----------

    /** 记录对战中玩家放置的方块（保存放置前的原方块，结束后还原）。 */
    public void recordFightPlacement(String arena, BlockState replaced) {
        if (replaced == null) {
            return;
        }
        Location key = blockKey(replaced.getLocation());
        fightPlaced.computeIfAbsent(arena, k -> new LinkedHashMap<>()).putIfAbsent(key, replaced);
    }

    /** 该方块是否为本次战斗中玩家放置的（地图方块不可破坏）。 */
    public boolean isFightPlaced(UUID id, Location loc) {
        String name = playerArena.get(id);
        if (name == null) {
            return false;
        }
        Map<Location, BlockState> placed = fightPlaced.get(name);
        return placed != null && placed.containsKey(blockKey(loc));
    }

    /** 玩家破坏了自己放置的方块：从记录中移除（该位置已还原为空气，无需再还原）。 */
    public void removeFightPlaced(UUID id, Location loc) {
        String name = playerArena.get(id);
        if (name == null) {
            return;
        }
        Map<Location, BlockState> placed = fightPlaced.get(name);
        if (placed != null) {
            placed.remove(blockKey(loc));
        }
    }

    /** 对局结束（含中途退出/关闭服务器）：把玩家放置的方块还原为放置前的状态。 */
    public void restoreFightBlocks(String arena) {
        Map<Location, BlockState> placed = fightPlaced.remove(arena);
        if (placed == null) {
            return;
        }
        for (BlockState st : placed.values()) {
            st.update(true, false);
        }
    }

    /** 方块坐标统一为整数坐标的 Location（避免 yaw/pitch 影响 Map 键相等性）。 */
    private Location blockKey(Location loc) {
        Location l = loc.clone();
        l.setX(l.getBlockX());
        l.setY(l.getBlockY());
        l.setZ(l.getBlockZ());
        l.setYaw(0);
        l.setPitch(0);
        return l;
    }

    /** 战斗中的玩家中途退出（quit）：对方获胜。 */
    public void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        if (setup.isEditing(p)) {
            // 退出服务器时一并退出配置模式（公共库会还原背包，避免下次上线卡在配置模式）
            setup.exit(p);
        }
        pendingSign.remove(id);
        HubAPI.clearGame(id);
        String name = playerArena.remove(id);
        if (name == null) {
            return;
        }
        DuelArena a = arenas.get(name);
        if (a == null) {
            return;
        }
        if (a.isFighter(id)) {
            UUID opp = a.opponentOf(id);
            broadcastToArena(a, "&c" + p.getName() + " 中途退出，&e" + (opp == null ? "?" : Bukkit.getPlayer(opp) == null ? "?" : Bukkit.getPlayer(opp).getName()) + "&c 获胜！");
            endFight(a, opp);
        } else {
            a.getQueue().remove(id);
            reportArena(a);
        }
    }

    // ---------- 战绩 ----------

    public int getWins(String arena, UUID id) {
        return data.getInt("wins.per-arena." + arena + "." + id, 0);
    }

    public int getTotalWins(UUID id) {
        return data.getInt("wins.total." + id, 0);
    }

    private void addWin(String arena, UUID id) {
        data.set("wins.per-arena." + arena + "." + id, getWins(arena, id) + 1);
        data.set("wins.total." + id, getTotalWins(id) + 1);
        saveData();
    }

    /** OP /duel stats reset：清空全部战绩（总胜场 + 分场胜场）。 */
    public void resetWins() {
        data.set("wins", null);
        saveData();
    }

    /** 某场战绩榜（场胜场降序），返回 [玩家名, 场胜场, 总胜场]。 */
    public List<String[]> topWins(String arena) {
        ConfigurationSection sec = data.getConfigurationSection("wins.per-arena." + arena);
        List<String[]> out = new ArrayList<>();
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            int n = sec.getInt(key);
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            out.add(new String[]{nameOf(id), String.valueOf(n), String.valueOf(getTotalWins(id))});
        }
        out.sort((x, y) -> Integer.compare(Integer.parseInt(y[1]), Integer.parseInt(x[1])));
        return out;
    }

    private String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p == null ? id.toString().substring(0, 6) : p.getName();
    }

    // ---------- Kit 配置 GUI ----------

    /** 打开物品配置窗口（OP 正在编辑的场）。 */
    public void openKitEditor(Player op, DuelArena a) {
        Inventory inv = Bukkit.createInventory(null, KIT_GUI_SIZE, Chat.color("&0配置物品 - " + a.getName()));
        Kit kit = a.getKit();
        for (int i = 0; i < Kit.SLOTS; i++) {
            if (kit.getSlot(i) != null) {
                inv.setItem(i, kit.getSlot(i).clone());
            }
        }
        if (kit.getOffhand() != null) {
            inv.setItem(36, kit.getOffhand().clone());
        }
        inv.setItem(37, kit.getArmor(0));
        inv.setItem(38, kit.getArmor(1));
        inv.setItem(39, kit.getArmor(2));
        inv.setItem(40, kit.getArmor(3));
        inv.setItem(BTN_SAVE, btn(Material.EMERALD, "&a保存此物品配置", "&7点击保存给两位玩家使用"));
        inv.setItem(BTN_CLEAR, btn(Material.REDSTONE_BLOCK, "&c清空配置", "&7清空所有物品"));
        inv.setItem(BTN_CLOSE, btn(Material.BARRIER, "&7关闭窗口", "&7不保存直接关闭"));
        inv.setItem(44, btn(Material.ORANGE_STAINED_GLASS_PANE, "&6装备与副手位置说明", "&7（装饰格，不会保存）"));
        inv.setItem(45, btn(Material.ORANGE_STAINED_GLASS_PANE, "&6副手 = 本行第1格（36）", "&7装备：37头盔 38胸甲 39护腿 40靴子"));
        inv.setItem(46, btn(Material.YELLOW_STAINED_GLASS_PANE, "&e装备：37头盔 38胸甲 39护腿 40靴子", ""));
        inv.setItem(47, btn(Material.CYAN_STAINED_GLASS_PANE, "&b第1行 = 快捷栏（第1格=主手）", ""));
        inv.setItem(48, btn(Material.CYAN_STAINED_GLASS_PANE, "&b第2-4行 = 物品栏 27格", ""));
        inv.setItem(49, btn(Material.GRAY_STAINED_GLASS_PANE, "&7按钮：41保存 42清空 43关闭", ""));
        inv.setItem(50, btn(Material.LIME_STAINED_GLASS_PANE, "&a此配置两位玩家共用", ""));
        inv.setItem(51, btn(Material.GRAY_STAINED_GLASS_PANE, "", ""));
        inv.setItem(52, btn(Material.GRAY_STAINED_GLASS_PANE, "", ""));
        inv.setItem(53, btn(Material.GRAY_STAINED_GLASS_PANE, "", ""));
        op.openInventory(inv);
        editingKit.put(op.getUniqueId(), a.getName());
    }

    private ItemStack btn(Material m, String name, String lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color(name)));
            meta.lore(List.of(Chat.color(lore)));
            it.setItemMeta(meta);
        }
        return it;
    }

    /** GUI 点击处理：按钮操作 / 记录物品。 */
    public void handleKitClick(Player p, Inventory inv, int slot) {
        if (slot > BTN_CLOSE) {
            return; // 装饰标签格
        }
        if (slot == BTN_SAVE) {
            saveKitFromGui(p, inv);
            return;
        }
        if (slot == BTN_CLEAR) {
            for (int i = 0; i < 41; i++) {
                inv.setItem(i, null);
            }
            Chat.send(p, "&a已清空窗口，可重新摆放物品。");
            return;
        }
        if (slot == BTN_CLOSE) {
            p.closeInventory();
            editingKit.remove(p.getUniqueId());
            Chat.send(p, "&7已关闭（未保存）。");
        }
    }

    private void saveKitFromGui(Player p, Inventory inv) {
        String name = editingKit.remove(p.getUniqueId());
        if (name == null) {
            return;
        }
        DuelArena a = arenas.get(name);
        if (a == null) {
            Chat.send(p, "&c场不存在。");
            return;
        }
        Kit kit = new Kit();
        for (int i = 0; i < Kit.SLOTS; i++) {
            kit.setSlot(i, inv.getItem(i));
        }
        kit.setOffhand(inv.getItem(36));
        kit.setArmor(0, inv.getItem(37));
        kit.setArmor(1, inv.getItem(38));
        kit.setArmor(2, inv.getItem(39));
        kit.setArmor(3, inv.getItem(40));
        a.setKit(kit);
        saveAll(); // kit 修改立即落盘，防止重启丢失
        p.closeInventory();
        Chat.send(p, "&a已保存 &e" + a.getName() + "&a 的物品配置（" + (kit.isEmpty() ? "空配置" : "含装备/物品") + "）。");
    }

    // ---------- 告示牌 ----------

    /** 进入场战绩告示牌绑定模式：随后右键点击一个告示牌，把它变为场战绩榜（方向随你放置）。 */
    public void placeSign(Player p, DuelArena a) {
        pendingSign.put(p.getUniqueId(), new PendingSign("arena", a.getName()));
        Chat.send(p, "&a请放置/选择一个告示牌，然后&e右键点击它&a，将其变为 &e" + a.getName() + "&a 的战绩告示牌（方向按你放置时朝向）。");
    }

    /** 刷新某场的所有战绩告示牌。 */
    public void refreshArenaSigns(String arena) {
        List<Location> locs = signLocations.get(arena);
        if (locs == null) {
            return;
        }
        List<String[]> top = topWins(arena);
        for (Location loc : locs) {
            if (!(loc.getBlock().getState() instanceof Sign sign)) {
                continue;
            }
            sign.setLine(0, Chat.toLegacy(Chat.color("&6[决斗] &e" + arena)));
            sign.setLine(1, Chat.toLegacy(Chat.color("&7战绩（场胜/总胜）：")));
            if (top.isEmpty()) {
                sign.setLine(2, Chat.toLegacy(Chat.color("&8暂无战绩")));
                sign.setLine(3, Chat.toLegacy(Chat.color("&7加入 /duel join " + arena)));
            } else {
                int line = 2;
                for (int i = 0; i < Math.min(2, top.size()); i++) {
                    String[] row = top.get(i);
                    sign.setLine(line++, Chat.toLegacy(Chat.color("&f" + row[0] + "&7 " + row[1] + "/" + row[2])));
                }
                if (line <= 3) {
                    sign.setLine(line, Chat.toLegacy(Chat.color("&7右键查看我的战绩")));
                }
            }
            sign.update();
        }
    }

    /** 进入加入告示牌绑定模式：随后右键点击一个告示牌，把它变为加入牌（方向随你放置）。 */
    public void placeJoinSign(Player p, DuelArena a) {
        pendingSign.put(p.getUniqueId(), new PendingSign("join", a.getName()));
        Chat.send(p, "&a请放置/选择一个告示牌，然后&e右键点击它&a，将其变为 &e" + a.getName() + "&a 的加入牌（方向按你放置时朝向）。");
    }

    /** 进入总胜场榜绑定模式：随后右键点击一个告示牌，把它变为总榜。 */
    public void placeTopSign(Player p) {
        pendingSign.put(p.getUniqueId(), new PendingSign("top", null));
        Chat.send(p, "&a请放置/选择一个告示牌，然后&e右键点击它&a，将其变为总胜场排行榜（方向按你放置时朝向）。");
    }

    /** 待绑定告示牌。 */
    private static class PendingSign {
        final String type; // arena / join / top
        final String arena;

        PendingSign(String type, String arena) {
            this.type = type;
            this.arena = arena;
        }
    }

    /** 点击告示牌绑定（玩家处于待绑定状态时）。 */
    public boolean handleSignBind(Player p, Location loc) {
        PendingSign ps = pendingSign.remove(p.getUniqueId());
        if (ps == null) {
            return false;
        }
        // 若该告示牌已属于其他类型，先移除
        removeFromAllSigns(loc);
        switch (ps.type) {
            case "join" -> {
                joinSigns.computeIfAbsent(ps.arena, k -> new ArrayList<>()).add(loc);
                refreshJoinSigns();
                Chat.send(p, "&a已将告示牌变为 &e" + ps.arena + "&a 的加入牌，右键点击即可加入。");
            }
            case "arena" -> {
                signLocations.computeIfAbsent(ps.arena, k -> new ArrayList<>()).add(loc);
                refreshArenaSigns(ps.arena);
                Chat.send(p, "&a已将告示牌变为 &e" + ps.arena + "&a 的战绩告示牌。");
            }
            case "top" -> {
                topSigns.add(loc);
                refreshTopSigns();
                Chat.send(p, "&a已将告示牌变为总胜场排行榜。");
            }
            default -> {
                return true;
            }
        }
        saveAll(); // 告示牌绑定立即落盘（场配置 + 告示牌一并保存）
        return true;
    }

    /** 从所有告示牌类型中移除该位置（按方块坐标比较，兼容重启前后两种来源的 Location）。 */
    private void removeFromAllSigns(Location loc) {
        signLocations.values().forEach(l -> l.removeIf(x -> sameBlock(x, loc)));
        joinSigns.values().forEach(l -> l.removeIf(x -> sameBlock(x, loc)));
        topSigns.removeIf(x -> sameBlock(x, loc));
    }

    /** 刷新所有加入告示牌（显示等待人数）。 */
    public void refreshJoinSigns() {
        for (Map.Entry<String, List<Location>> e : joinSigns.entrySet()) {
            DuelArena a = arenas.get(e.getKey());
            int waiting = a == null ? 0 : a.getQueue().size();
            boolean fighting = a != null && a.getState() == DuelArena.State.FIGHTING;
            for (Location loc : e.getValue()) {
                if (!(loc.getBlock().getState() instanceof Sign sign)) {
                    continue;
                }
                sign.setLine(0, Chat.toLegacy(Chat.color("&a[加入] &e" + e.getKey())));
                sign.setLine(1, Chat.toLegacy(Chat.color("&7右键点击加入")));
                sign.setLine(2, Chat.toLegacy(Chat.color(fighting ? "&c对战中" : "&7等待：" + waiting + "/2")));
                sign.setLine(3, Chat.toLegacy(Chat.color("&7战绩：/duel stats")));
                sign.update();
            }
        }
    }

    /** 全局总胜场 Top3。 */
    private List<String[]> topTotal() {
        ConfigurationSection sec = data.getConfigurationSection("wins.total");
        List<String[]> out = new ArrayList<>();
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            int n = sec.getInt(key);
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            out.add(new String[]{nameOf(id), String.valueOf(n)});
        }
        out.sort((x, y) -> Integer.compare(Integer.parseInt(y[1]), Integer.parseInt(x[1])));
        return out;
    }

    /** 刷新总胜场榜告示牌。 */
    public void refreshTopSigns() {
        if (topSigns.isEmpty()) {
            return;
        }
        List<String[]> top = topTotal();
        for (Location loc : topSigns) {
            if (!(loc.getBlock().getState() instanceof Sign sign)) {
                continue;
            }
            sign.setLine(0, Chat.toLegacy(Chat.color("&6[排行榜] 总胜场")));
            if (top.isEmpty()) {
                sign.setLine(1, Chat.toLegacy(Chat.color("&8暂无战绩")));
                sign.setLine(2, Chat.toLegacy(Chat.color("&7加入决斗场赢取胜利！")));
                sign.setLine(3, "");
            } else {
                int line = 1;
                for (int i = 0; i < Math.min(3, top.size()); i++) {
                    String[] row = top.get(i);
                    String prefix = switch (i) {
                        case 0 -> "&e1&f. ";
                        case 1 -> "&7 2&f. ";
                        default -> "&7 3&f. ";
                    };
                    sign.setLine(line++, Chat.toLegacy(Chat.color(prefix + "&f" + row[0] + " &e" + row[1] + "胜")));
                }
            }
            sign.update();
        }
    }

    public void onSignClick(Player p, Location signLoc) {
        // 加入牌：点击直接加入
        for (Map.Entry<String, List<Location>> e : joinSigns.entrySet()) {
            for (Location loc : e.getValue()) {
                if (sameBlock(loc, signLoc)) {
                    join(p, e.getKey());
                    return;
                }
            }
        }
        // 总胜场榜：查看自己的总战绩
        for (Location loc : topSigns) {
            if (sameBlock(loc, signLoc)) {
                Chat.send(p, "&6你的总胜场：&e" + getTotalWins(p.getUniqueId()));
                return;
            }
        }
        // 场战绩榜
        for (Map.Entry<String, List<Location>> e : signLocations.entrySet()) {
            for (Location loc : e.getValue()) {
                if (sameBlock(loc, signLoc)) {
                    Chat.send(p, "&6你在决斗场 &e" + e.getKey() + "&6 的场胜场：&e" + getWins(e.getKey(), p.getUniqueId())
                            + "&6；总胜场：&e" + getTotalWins(p.getUniqueId()));
                    return;
                }
            }
        }
    }

    // ---------- 辅助 ----------

    private void broadcastToArena(DuelArena a, String msg) {
        for (UUID id : new ArrayList<>(a.getQueue())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.sendTitled(p, msg);
            }
        }
        for (UUID id : new UUID[]{a.getFighter1(), a.getFighter2()}) {
            if (id == null) {
                continue;
            }
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.sendTitled(p, msg);
            }
        }
    }

    private String locKey(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Location parseLocKey(String key) {
        String[] parts = key.split(",");
        if (parts.length != 4) {
            return null;
        }
        try {
            // R2：支持 tpl: 令牌
            var world = com.hub.world.Worlds.resolve(parts[0]);
            if (world == null) {
                return null;
            }
            // 必须用方块坐标（与 Block/Sign 的 getLocation() 一致），不能加 0.5，
            // 否则重启后加载的坐标与点击事件坐标在 Location.equals 精确比较时永不相等。
            return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
