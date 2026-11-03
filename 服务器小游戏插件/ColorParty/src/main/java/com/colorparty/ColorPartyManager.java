package com.colorparty;

import com.hub.session.EndReason;

import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.data.PlayerStats;
import com.hub.api.ArcadeBridge;
import com.hub.util.Chat;
import com.hub.util.LiveSidebar;
import com.hub.util.Top3;
import com.hub.util.StartCountdown;
import com.hub.util.SetupMode;
import com.hub.util.WaitCountdown;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 色盲派对核心：每回合把地板刷成随机彩色混凝土、选一个指定颜色刷上面板，
 * 倒计时结束后不是指定颜色的地板全部消失，掉到淘汰线以下即出局，剩最后 1 人结算。
 */
public class ColorPartyManager {

    /** 游戏注册名（HubCore 菜单 / 战绩播报 / tab 都用这个显示名）。 */
    public static final String GAME = "色盲派对";

    /** 混凝土 16 色 + 中文名 + 聊天颜色代码（黑色用深灰代码，避免标题/聊天里看不见）。 */
    record ColorSpec(Material concrete, String cn, String code) {
        /** 带颜色代码的名字（聊天 / 标题里用）。 */
        String tag() {
            return "&" + code + cn;
        }
    }

    private static final ColorSpec[] COLORS = {
            new ColorSpec(Material.WHITE_CONCRETE, "白色", "f"),
            new ColorSpec(Material.ORANGE_CONCRETE, "橙色", "6"),
            new ColorSpec(Material.MAGENTA_CONCRETE, "品红色", "d"),
            new ColorSpec(Material.LIGHT_BLUE_CONCRETE, "淡蓝色", "b"),
            new ColorSpec(Material.YELLOW_CONCRETE, "黄色", "e"),
            new ColorSpec(Material.LIME_CONCRETE, "黄绿色", "a"),
            new ColorSpec(Material.PINK_CONCRETE, "粉红色", "d"),
            new ColorSpec(Material.GRAY_CONCRETE, "灰色", "8"),
            new ColorSpec(Material.LIGHT_GRAY_CONCRETE, "淡灰色", "7"),
            new ColorSpec(Material.CYAN_CONCRETE, "青色", "3"),
            new ColorSpec(Material.PURPLE_CONCRETE, "紫色", "5"),
            new ColorSpec(Material.BLUE_CONCRETE, "蓝色", "9"),
            new ColorSpec(Material.BROWN_CONCRETE, "棕色", "6"),
            new ColorSpec(Material.GREEN_CONCRETE, "绿色", "2"),
            new ColorSpec(Material.RED_CONCRETE, "红色", "c"),
            new ColorSpec(Material.BLACK_CONCRETE, "黑色", "8"),
    };

    private final ColorPartyPlugin plugin;
    private final LiveSidebar liveSidebar;
    private final Map<String, ColorPartyArena> arenas = new LinkedHashMap<>();

    /** D22：每个场地一个会话（多图游戏）。 */
    private final Map<String, ColorPartySession> sessions = new LinkedHashMap<>();

    private ColorPartySession ensureSession(String arenaName) {
        ColorPartySession s = sessions.get(arenaName);
        if (s == null || s.state().isFinal()) {
            s = new ColorPartySession(this, arenaName);
            sessions.put(arenaName, s);
        }
        return s;
    }
    private final Map<UUID, String> playerArena = new LinkedHashMap<>();
    /** 每个场一个等待倒计时实例（公共库 com.hub.util.WaitCountdown）。 */
    private final Map<String, WaitCountdown> waits = new LinkedHashMap<>();
    /** 每 10 tick 兜底检测一次「掉到淘汰线以下」的定时任务（移动事件之外的安全网）。 */
    private BukkitTask checkTask;

    // 数据与配置模式
    private YamlConfiguration data;
    private final SetupMode setup;

    /** I3：会话里的搬迁代码要用到 plugin（原 Listener 的字段）。 */
    public ColorPartyPlugin plugin() {
        return plugin;
    }

    public ColorPartyManager(ColorPartyPlugin plugin) {
        this.plugin = plugin;
        this.liveSidebar = new LiveSidebar(plugin);
        this.setup = new SetupMode(plugin, "cp-setup-tool");
    }

    public void load() {
        startCountdown();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("arenas");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                ColorPartyArena a = ColorPartyArena.load(name, sec.getConfigurationSection(name));
                loadArenaSnapshot(a);
                arenas.put(name, a);
            }
        }
        // 战绩数据（唯一文件 plugins/MiniGameLib/players.yml，命名空间 colorparty）
        data = PlayerStats.load("colorparty", new File(plugin.getDataFolder(), "data.yml"));
        // 启动后把已有场地上报给 HubCore 中央菜单（延迟等待 HubCore 就绪）
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // 兜底登记：线上 HubCore 的 games 段还没有本游戏时，菜单里也能出现"色盲派对"
            HubAPI.registerGame(GAME, GAME, "MAGENTA_CONCRETE", "d");
            for (ColorPartyArena a : arenas.values()) {
                reportArena(a);
            }
        }, 40L);
        // 淘汰线兜底检测（跳伞 / 传送等移动事件覆盖不到的情况）
        startCheckTask();
    }

    private void loadArenaSnapshot(ColorPartyArena a) {
        File f = a.snapshotFile(plugin.getDataFolder());
        if (!f.isFile()) {
            return;
        }
        try {
            a.loadSnapshotFile(f);
            plugin.getLogger().info("场 " + a.getName() + " 地图快照已载入：" + a.getSnapshotSize() + " 格");
        } catch (IllegalStateException ex) {
            plugin.getLogger().warning("场 " + a.getName() + " 读取地图快照失败：" + ex.getMessage());
        }
    }

    /** 启动淘汰线兜底检测：每 10 tick 检测一次所有对局中玩家的 Y。 */
    private void startCheckTask() {
        checkTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (ColorPartyArena a : arenas.values()) {
                if (a.getState() != ColorPartyArena.State.RUNNING) {
                    continue;
                }
                for (UUID id : new ArrayList<>(a.getPlayers())) {
                    // 一次检测可能淘汰玩家并结束游戏，避免继续对已结束的场做检测
                    if (a.getState() != ColorPartyArena.State.RUNNING) {
                        break;
                    }
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        checkFall(p, a);
                    }
                }
            }
        }, 10L, 10L);
    }

    /** 插件卸载：停任务 + 还原所有场地 + 兜底退出配置模式。 */
    public void shutdown() {
        liveSidebar.hideAll();
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        restoreAllBlocks();
        setup.exitAll();
    }

    /** 每局结束 / 关服兜底：按快照把地板和面板重置回保存那一刻。 */
    private void restoreBlocks(ColorPartyArena a) {
        int changed = a.restoreSnapshot();
        if (changed > 0) {
            plugin.getLogger().fine("场 " + a.getName() + " 已按地图快照重置（重写 " + changed + " 格）。");
        } else if (changed < 0) {
            plugin.getLogger().warning("场 " + a.getName() + " 没有地图快照，地板保持最后的颜色"
                    + "（用绿宝石或 /cp save 重新保存一次即可生成快照）。");
        }
    }

    private void restoreAllBlocks() {
        for (ColorPartyArena a : arenas.values()) {
            restoreBlocks(a);
        }
    }

    /** 保存场地：配置落盘 + 抓地图快照写 arena-&lt;场名&gt;.snap（对局中跳过快照，防止把彩色地板存成"原样"）。 */
    public void saveWithSnapshot(Player p, ColorPartyArena a) {
        saveAll();
        if (a.getPos1() == null || a.getPos2() == null || a.getPos1().getWorld() == null) {
            msg(p, "&a已保存场 &e" + a.getName() + "&a。&7（还没圈地板范围 pos1/pos2，未生成地图快照）");
            return;
        }
        if (a.getState() == ColorPartyArena.State.RUNNING) {
            msg(p, "&a已保存场 &e" + a.getName() + "&a。&7（对局中，跳过地图快照；这局结束后再保存一次就会写入）");
            return;
        }
        int n = a.captureSnapshot();
        if (n < 0) {
            msg(p, "&c地图快照失败：范围的世界读不到（pos1/pos2 的世界是不是被卸载了？）。");
            return;
        }
        try {
            a.saveSnapshotFile(plugin.getDataFolder());
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("保存场 " + a.getName() + " 的地图快照失败：" + ex.getMessage());
            msg(p, "&c地图快照写盘失败：" + ex.getMessage());
            return;
        }
        msg(p, "&a已保存场 &e" + a.getName() + "&a：地图快照 &e" + n + " &a格（每局结束按它重置地板+面板）。");
        if (!a.hasPanel()) {
            msg(p, "&7提示：还没设颜色面板（品红染料工具 / &e/cp panel1、/cp panel2&7），开局只靠聊天和标题提示颜色。");
        }
    }

    /**
     * 声明地盘（{@code docs/框架不变量.md} 的 I2）：把每个场地的边界报给框架。
     * 只在内存里读字段，不碰配置；还没建场的场地会被 {@code Regions} 静默跳过。
     */
    public void publishRegions() {
        com.hub.region.Regions.begin("colorparty");
        arenas.forEach((name, a) ->
                com.hub.region.Regions.box("colorparty", name, a.getPos1(), a.getPos2()));
    }

    public void saveAll() {
                    // **未就绪的场地跳过回写**：世界缺失/没加载成功时，内存里的坐标是空的，
            // 写回去等于把磁盘上的场地坐标抹掉（真实事故：删了 world_tr 后 TntRun 的场地被清空）。
            // 房间只替换"世界"，坐标仍以这里为准，所以绝不能丢。
publishRegions();   // 场地可能刚被改过，重新声明（I2）
        ConfigurationSection sec = com.hub.world.Worlds.getOrCreate(plugin.getConfig(), "arenas");
        for (Map.Entry<String, ColorPartyArena> e : arenas.entrySet()) {
            if (!e.getValue().isReady()) {
                plugin.getLogger().warning("[保存] 场地 " + e.getKey()
                        + " 未就绪（世界缺失？），跳过回写以免丢坐标");
                continue;
            }
            e.getValue().save(com.hub.world.Worlds.getOrCreate(sec, e.getKey()));
        }
        plugin.saveConfig();
        saveData();
    }

    private void saveData() {
        if (data == null) {
            return;
        }
        try {
            PlayerStats.save("colorparty", data);
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("保存战绩失败：" + ex.getMessage());
        }
    }

    public ColorPartyArena getArena(String name) {
        return arenas.get(name);
    }

    public Map<String, ColorPartyArena> getArenas() {
        return arenas;
    }

    public boolean createArena(String name) {
        if (arenas.containsKey(name)) {
            return false;
        }
        arenas.put(name, new ColorPartyArena(name));
        return true;
    }

    /** 删除场（仅等待且无人时），连同其地图快照。 */
    public boolean removeArena(String name) {
        ColorPartyArena a = arenas.get(name);
        if (a == null) {
            return false;
        }
        if (a.getState() != ColorPartyArena.State.WAITING || !a.getPlayers().isEmpty()
                || !a.getEliminated().isEmpty()) {
            return false;
        }
        arenas.remove(name);
        waits.remove(name); // 丢掉该场的倒计时实例，避免同名重建后复用旧实例
        File snap = a.snapshotFile(plugin.getDataFolder());
        if (snap.isFile() && !snap.delete()) {
            plugin.getLogger().warning("删除场 " + name + " 的地图快照失败：" + snap.getAbsolutePath());
        }
        plugin.getConfig().set("arenas." + name, null);
        plugin.saveConfig();
        HubAPI.removeArena(GAME, name);
        return true;
    }

    /** 场地改名：同步配置、快照文件、玩家/倒计时引用与中央菜单；对局中禁止。 */
    public boolean renameArena(String oldName, String newName) {
        if (oldName == null || oldName.equals(newName) || !validName(newName) || arenas.containsKey(newName)) {
            return false;
        }
        ColorPartyArena a = arenas.get(oldName);
        if (a == null || a.getState() == ColorPartyArena.State.RUNNING) {
            return false;
        }
        arenas.remove(oldName);
        a.setName(newName);
        arenas.put(newName, a);
        // 地图快照跟着改名（arena-旧名.snap → arena-新名.snap），否则改名后"没快照"、每局不再重置地图
        File oldSnap = new File(plugin.getDataFolder(), "arena-" + oldName + ".snap");
        if (oldSnap.isFile()) {
            File newSnap = new File(plugin.getDataFolder(), "arena-" + newName + ".snap");
            if (!oldSnap.renameTo(newSnap)) {
                plugin.getLogger().warning("地图快照改名失败：" + oldSnap.getName() + " → " + newSnap.getName());
            }
        }
        for (Map.Entry<UUID, String> e : playerArena.entrySet()) {
            if (e.getValue().equals(oldName)) {
                e.setValue(newName);
            }
        }
        // 配置模式：公共库内部按玩家记录场名，没有改名接口 → 退场再进场（背包快照内容不变，工具重新发放）
        for (Player editing : Bukkit.getOnlinePlayers()) {
            if (oldName.equals(setup.editingArena(editing))) {
                setup.exit(editing);
                setup.enter(editing, newName, setupTools(), setupHandler());
            }
        }
        WaitCountdown wc = waits.remove(oldName);
        if (wc != null) {
            waits.put(newName, wc);
        }
        saveAll();
        HubAPI.removeArena(GAME, oldName);
        reportArena(a);
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

    /** 上报场地状态到 HubCore 中央菜单（羊毛行：绿=等待，红=游戏中）。 */
    private void reportArena(ColorPartyArena a) {
        boolean playing = a.getState() == ColorPartyArena.State.RUNNING;
        HubAPI.setArena(GAME, a.getName(),
                playing ? GameState.PLAYING : GameState.WAITING, a.getPlayers().size(), null);
        HubAPI.setArenaSpectator(GAME, a.getName(), a.getLobbyPoint());
    }

    public String arenaOf(UUID id) {
        return playerArena.get(id);
    }

    // ---------- 会话 ----------

    public boolean join(Player p, String arenaName) {
        ColorPartyArena a = arenas.get(arenaName);
        if (a == null) {
            msg(p, "&c场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        UUID id = p.getUniqueId();
        if (playerArena.containsKey(id)) {
            msg(p, "&c你已在其他场。");
            return false;
        }
        if (a.getState() == ColorPartyArena.State.RUNNING) {
            msg(p, "&c该场正在游戏中。");
            return false;
        }
        if (!a.isReady()) {
            msg(p, "&c该场未配置完成（需要地板范围 pos1/pos2 + 中心出生点 spawn）。");
            return false;
        }
        int max = cfg("max-players", 12);
        if (a.getPlayers().size() >= max) {
            msg(p, "&c人数已满（&e" + max + "&c）。");
            return false;
        }
        playerArena.put(id, arenaName);
        a.getPlayers().add(id);
        a.recordJoin(id);   // D21
        if (!ensureSession(arenaName).join(p)) {
            plugin.getLogger().warning("[ColorParty] " + p.getName() + " 加入归属链失败（场 " + arenaName + "）");
        }
        p.getInventory().clear();
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(20);
        p.setFoodLevel(20);
        Location lobby = a.getLobbyPoint() != null ? a.getLobbyPoint() : a.getSpawn();
        p.teleport(lobby);
        HubAPI.setGame(id, GAME);
        giveLeaveBed(p);
        if (p.isOp()) {
            giveStartPaper(p);
        }
        msg(p, "&a你已加入 &e" + arenaName + "&a，等待开始…");
        broadcast(a, "&e" + p.getName() + "&f 加入了（&e" + a.getPlayers().size() + "&f 人）");
        HubAPI.announceJoin(GAME, p.getName());
        updateWaitCountdown(a);
        reportArena(a);
        return true;
    }

    public void leave(Player p) {
        leave(p, false);
    }

    /**
     * 离开场地。{@code silent=true} 用于主城"复原大厅状态"的回调（进服复原 / 右键回主城道具 / 回到大厅）：
     * 玩家本来就不在场中时直接返回，不打任何提示（否则进服一次刷一堆"你不在任何场中"）。
     */
    public void leave(Player p, boolean silent) {
        UUID id = p.getUniqueId();
        String name = playerArena.remove(id);
        // I1：从该场地的会话上摘掉自己（漏了会让玩家卡在"已在等待中"）
        if (name != null) {
            ColorPartySession sess = sessions.get(name);
            if (sess != null && !sess.state().isFinal()) {
                sess.leave(p);
            }
        }
        if (name == null) {
            if (!silent) {
                msg(p, "&c你不在任何场中。");
            }
            return;
        }
        ColorPartyArena a = arenas.get(name);
        if (a != null) {
            a.getPlayers().remove(id);
            a.getEliminated().remove(id);
            HubAPI.clearGame(id);
            broadcast(a, "&e" + p.getName() + "&f 离开了（剩 &e" + a.getPlayers().size() + "&f 人）");
            if (a.getState() == ColorPartyArena.State.RUNNING && a.getPlayers().size() <= 1) {
                endGame(a, a.getPlayers().isEmpty() ? null : a.getPlayers().get(0));
            }
            reportArena(a);
        }
        p.setGameMode(GameMode.SURVIVAL);
        HubAPI.teleportToLobby(p);
        msg(p, "&7你已离开。");
        if (a != null && a.getState() == ColorPartyArena.State.WAITING) {
            updateWaitCountdown(a);
        }
    }

    public void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        String name = playerArena.remove(id);
        if (name == null) {
            return;
        }
        ColorPartyArena a = arenas.get(name);
        if (a == null) {
            return;
        }
        a.getPlayers().remove(id);
        a.getEliminated().remove(id);
        HubAPI.clearGame(id);
        reportArena(a);
        if (a.getState() == ColorPartyArena.State.RUNNING && a.getPlayers().size() <= 1) {
            endGame(a, a.getPlayers().isEmpty() ? null : a.getPlayers().get(0));
        }
    }

    // ---------- 流程 ----------

    private StartCountdown startCountdown;

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

    public boolean start(Player op, String arenaName) {
        ColorPartyArena a = arenas.get(arenaName);
        if (a == null) {
            msg(op, "&c场不存在。");
            return false;
        }
        int min = cfg("min-players", 2);
        if (a.getPlayers().size() < min) {
            msg(op, "&c人数不足（至少 &e" + min + "&c 人）。");
            return false;
        }
        if (startCountdown().begin(arenaName, new ArrayList<>(a.getPlayers()), () -> start(op, arenaName))) {
            return true;
        }
        a.setState(ColorPartyArena.State.RUNNING);
        ColorPartySession started = sessions.get(a.getName());
        if (started != null && !started.state().isFinal()) {
            started.start();
        }
        a.cancelTasks();
        a.nextRound(); // 守卫回合 +1：上一局残留的调度任务全部失效
        wait(a).cancel();
        reportArena(a);
        a.getEliminated().clear();
        a.getElimOrder().clear();
        a.setTarget(null);
        a.setLastTarget(null);
        for (UUID id : new ArrayList<>(a.getPlayers())) {
            HubAPI.setPlaying(id, GAME); // tab 从 [等待中] 变为 [已加入]
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.getInventory().clear();
                p.setGameMode(GameMode.SURVIVAL);
                p.setHealth(20);
                p.setFoodLevel(20);
                p.teleport(a.getSpawn()); // 全员站中心出生点
            }
        }
        broadcast(a, "&d&l— 色盲派对开始！盯住面板颜色，站到对应的混凝土上！—");
        // 等玩家落地后开第一回合
        BukkitTask first = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (a.getState() == ColorPartyArena.State.RUNNING) {
                beginRound(a);
            }
        }, 20L);
        a.setNextTask(first);
        return true;
    }

    // ---------- 回合引擎 ----------

    /** 第 round 回合该铺几种颜色：start-colors 起步、每回合 +color-step、封顶 max-colors（1~16）。 */
    private int colorsForRound(long round) {
        int start = clamp16(cfg("start-colors", 4));
        int max = clamp16(cfg("max-colors", 16));
        int step = Math.max(0, cfg("color-step", 2));
        int k = start + (int) Math.max(0, round - 1) * step;
        return Math.max(1, Math.min(Math.min(k, max), 16));
    }

    private static int clamp16(int v) {
        return Math.max(1, Math.min(16, v));
    }

    /** 从 16 色里随机抽 k 种作为本回合的候选池。 */
    private List<ColorSpec> pickPool(int k) {
        List<ColorSpec> pool = new ArrayList<>(List.of(COLORS));
        Collections.shuffle(pool, ThreadLocalRandom.current());
        return new ArrayList<>(pool.subList(0, k));
    }

    /** 从候选池里抽本回合指定颜色（池子 >1 种时避开上一回合的指定色，防止原地白赢）。 */
    private ColorSpec pickTarget(List<ColorSpec> pool, Material lastTarget) {
        List<ColorSpec> cands = pool;
        if (lastTarget != null && pool.size() > 1) {
            cands = pool.stream().filter(c -> c.concrete() != lastTarget).collect(Collectors.toList());
            if (cands.isEmpty()) {
                cands = pool;
            }
        }
        return cands.get(ThreadLocalRandom.current().nextInt(cands.size()));
    }

    /** 开一个新回合：地板刷随机色 → 面板刷指定色 → 广播+标题 → 倒计时，到点塌陷。 */
    private void beginRound(ColorPartyArena a) {
        if (a.getState() != ColorPartyArena.State.RUNNING) {
            return;
        }
        a.cancelTasks();
        long round = a.nextRound();
        long shown = a.nextPlayed();
        int seconds = Math.max(2, Math.min(60, cfg("round-seconds", 5)));
        List<ColorSpec> pool = pickPool(colorsForRound(round));
        ColorSpec target = pickTarget(pool, a.getLastTarget());
        a.setLastTarget(target.concrete());
        a.setTarget(target.concrete());
        paintFloor(a, pool, target);

        broadcast(a, "&d&l第 " + shown + " 回合 &8» " + target.tag() + "&f！站上去，&e" + seconds
                + "s &7后其他颜色塌陷！");
        for (UUID id : participants(a)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.title(p, target.tag(), "&f站到对应的混凝土上！", 2);
            }
        }
        long[] left = {seconds};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (a.getState() != ColorPartyArena.State.RUNNING || a.getRound() != round) {
                a.cancelTasks();
                return;
            }
            left[0]--;
            if (left[0] <= 0) {
                vanish(a, round, target);
                return;
            }
            for (UUID id : participants(a)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) {
                    Chat.actionBar(p, "&f本回合：" + target.tag() + " &7还有 &e" + left[0] + "s &7塌陷");
                }
            }
            updateLiveBoard(a);
        }, 20L, 20L);
        a.setRoundTask(task);
    }

    /** 地板按「网格色块 + 叠加形状」铺候选池颜色（见 ColorPartyFloor，物理更新关闭），面板整块刷指定颜色。 */
    private void paintFloor(ColorPartyArena a, List<ColorSpec> pool, ColorSpec target) {
        int[] b = a.floorBounds();
        if (b != null && a.getPos1().getWorld() != null) {
            World w = a.getPos1().getWorld();
            List<Material> mats = new ArrayList<>(pool.size());
            for (ColorSpec c : pool) {
                mats.add(c.concrete());
            }
            ColorPartyFloor.paint(w, b, mats, target.concrete());
        }
        int[] pb = a.panelBounds();
        if (pb != null && a.getPanelPos1() != null && a.getPanelPos1().getWorld() != null) {
            World w = a.getPanelPos1().getWorld();
            for (int x = pb[0]; x <= pb[3]; x++) {
                for (int y = pb[1]; y <= pb[4]; y++) {
                    for (int z = pb[2]; z <= pb[5]; z++) {
                        w.getBlockAt(x, y, z).setType(target.concrete(), false);
                    }
                }
            }
        }
    }

    /** 倒计时归零：不是指定颜色的地板全部变空气，掉落的玩家交给淘汰线检测；稍后开下一回合。 */
    private void vanish(ColorPartyArena a, long round, ColorSpec target) {
        if (a.getState() != ColorPartyArena.State.RUNNING || a.getRound() != round) {
            return;
        }
        a.cancelTasks();
        int[] b = a.floorBounds();
        if (b != null && a.getPos1().getWorld() != null) {
            World w = a.getPos1().getWorld();
            Material keep = target.concrete();
            for (int x = b[0]; x <= b[3]; x++) {
                for (int y = b[1]; y <= b[4]; y++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        Block blk = w.getBlockAt(x, y, z);
                        if (blk.getType() != Material.AIR && blk.getType() != keep) {
                            blk.setType(Material.AIR, false);
                        }
                    }
                }
            }
        }
        broadcast(a, "&c&l塌！&7不是 " + target.tag() + " &7的都掉了！");
        int gap = Math.max(1, Math.min(30, cfg("round-gap-seconds", 3)));
        BukkitTask next = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (a.getState() == ColorPartyArena.State.RUNNING) {
                beginRound(a);
            }
        }, gap * 20L);
        a.setNextTask(next);
    }

    /** 淘汰线检测（移动事件 + 兜底定时任务共用）：低于淘汰线就出局。 */
    private void checkFall(Player p, ColorPartyArena a) {
        if (p.getLocation().getY() < a.effectiveCutoff()) {
            eliminate(a, p);
        }
    }

    /** 淘汰：转旁观 + 传到中心上空旁观 + 广播 + 剩 1 人时结算。 */
    private void eliminate(ColorPartyArena a, Player p) {
        UUID id = p.getUniqueId();
        a.getPlayers().remove(id);
        a.getEliminated().add(id);
        a.getElimOrder().add(id);
        p.setGameMode(GameMode.SPECTATOR);
        // 传到中心出生点上方旁观，别让旁观者卡在地板下面的地里
        Location spawn = a.getSpawn();
        if (spawn != null && spawn.getWorld() != null) {
            p.teleport(spawn.clone().add(0.5, 4, 0.5));
        }
        HubAPI.clearGame(id);
        broadcast(a, "&c" + p.getName() + " &f站错颜色掉下去了！（剩 &e" + a.getPlayers().size() + "&f 人）");
        reportArena(a);
        if (a.getPlayers().size() <= 1) {
            endGame(a, a.getPlayers().isEmpty() ? null : a.getPlayers().get(0));
        }
    }

    /** 结束：前三名 + 按快照恢复地板/面板 + 全员回主城。 */
    private void updateLiveBoard(ColorPartyArena a) {
        List<UUID> online = new ArrayList<>(participants(a));
        online.sort(Comparator.comparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        lines.add(new LiveSidebar.Line("§7存活 §f" + online.size(), 1));
        for (UUID id : online) {
            lines.add(new LiveSidebar.Line("§e" + nameOf(id), 0));
        }
        liveSidebar.update("colorparty:" + a.getName(), "&d&l色盲派对 · 存活", online, lines);
    }

    /** 收场入口（I4）：先让该场地的会话 end()，再由 onEnd 回到 doEndGame。 */
    private void endGame(ColorPartyArena a, UUID winner) {
        pendingWinner = winner;
        ColorPartySession sess = sessions.get(a.getName());
        if (sess == null || sess.state().isFinal()) {
            doEndGame(a.getName());
            return;
        }
        sess.end(EndReason.ROUND_END);
    }

    /** endGame 与 doEndGame 之间传递赢家（玩家看到的结算不能变）。 */
    private UUID pendingWinner;

    /** 真正的收场逻辑（由 session.onEnd 回调）。 */
    void doEndGame(String arenaName) {
        ColorPartyArena a = arenas.get(arenaName);
        if (a == null) {
            return;
        }
        UUID winner = pendingWinner;
        pendingWinner = null;
        liveSidebar.hide("colorparty:" + a.getName());
        a.setState(ColorPartyArena.State.WAITING);
        a.cancelTasks();
        reportArena(a);
        broadcast(a, "&d&l========== 色盲派对 结束 ==========");
        List<UUID> firsts = new ArrayList<>();
        List<UUID> seconds = new ArrayList<>();
        List<UUID> thirds = new ArrayList<>();
        if (winner != null) {
            firsts.add(winner);
        }
        List<UUID> elim = a.getElimOrder();
        if (elim.size() >= 1) {
            seconds.add(elim.get(elim.size() - 1));
        }
        if (elim.size() >= 2) {
            thirds.add(elim.get(elim.size() - 2));
        }
        Top3.broadcast(msg -> broadcast(a, msg), this::nameOf, firsts, seconds, thirds);
        broadcast(a, "&d===================================");
        ArcadeBridge.report("colorparty", firsts, seconds, thirds);
        Top3.award(data, firsts, seconds, thirds);
        saveData();
        restoreBlocks(a);
        for (List<UUID> group : List.of(a.getPlayers(), a.getEliminated())) {
            for (UUID id : new ArrayList<>(group)) {
                Player pp = Bukkit.getPlayer(id);
                if (pp != null && pp.isOnline()) {
                    pp.setGameMode(GameMode.SURVIVAL);
                    HubAPI.teleportToLobby(pp);
                } else {
                    HubAPI.clearGame(id);
                }
                playerArena.remove(id);
            }
            group.clear();
        }
        broadcast(a, "&7场地已恢复。");
    }

    // ---------- 道具 ----------

    /** 退出床。 */
    public void giveLeaveBed(Player p) {
        ItemStack bed = new ItemStack(Material.RED_BED);
        ItemMeta meta = bed.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color("&c退出游戏 &7(右键)")));
            bed.setItemMeta(meta);
        }
        p.getInventory().setItem(8, bed);
    }

    /** OP 的开始纸。 */
    public void giveStartPaper(Player p) {
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color("&a&l开始游戏 &7(右键)")));
            paper.setItemMeta(meta);
        }
        p.getInventory().setItem(4, paper);
    }

    /** 玩家右键开始纸。 */
    public void handleStartPaper(Player p) {
        String arenaName = playerArena.get(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        ColorPartyArena a = arenas.get(arenaName);
        if (a == null || a.getState() != ColorPartyArena.State.WAITING) {
            return;
        }
        if (!p.isOp() && !p.hasPermission("cp.admin")) {
            msg(p, "&c只有管理员（OP 或 cp.admin）可以开始。");
            return;
        }
        start(p, arenaName);
    }

    /** 玩家右键退出床。 */
    public void handleLeaveBed(Player p) {
        leave(p);
    }

    /** 对局中玩家移动：只做淘汰线检测。 */
    public void handleMove(Player p) {
        UUID id = p.getUniqueId();
        String arenaName = playerArena.get(id);
        if (arenaName == null) {
            return;
        }
        ColorPartyArena a = arenas.get(arenaName);
        if (a == null || a.getState() != ColorPartyArena.State.RUNNING) {
            return;
        }
        if (!a.getPlayers().contains(id)) {
            return;
        }
        checkFall(p, a);
    }

    /** 该方块是否落在某个对局中场地的地板/面板范围内（保护彩色地板不被路人挖）。 */
    public boolean inRunningArena(Block block) {
        for (ColorPartyArena a : arenas.values()) {
            if (a.getState() != ColorPartyArena.State.RUNNING) {
                continue;
            }
            if (a.contains(block.getLocation())) {
                return true;
            }
            int[] pb = a.panelBounds();
            if (pb != null && a.getPanelPos1().getWorld() != null
                    && block.getWorld().equals(a.getPanelPos1().getWorld())) {
                int x = block.getX();
                int y = block.getY();
                int z = block.getZ();
                if (x >= pb[0] && x <= pb[3] && y >= pb[1] && y <= pb[4] && z >= pb[2] && z <= pb[5]) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------- 等待倒计时 ----------

    public int waitingCountOf(String arenaName) {
        ColorPartyArena a = arenas.get(arenaName);
        return a == null ? 0 : a.getPlayers().size();
    }

    public int minPlayersOf(String arenaName) {
        return plugin.getConfig().getInt("min-players", 2);
    }

    public boolean isHostOf(Player p, String arenaName) {
        if (!"host".equalsIgnoreCase(plugin.getConfig().getString("flow", "upstream"))) {
            return false;
        }
        ColorPartyArena a = arenas.get(arenaName);
        if (a == null || p == null) {
            return false;
        }
        UUID host = waits.computeIfAbsent(a.getName(), n -> null) == null ? null : waits.get(a.getName()).currentHost();
        return host != null && host.equals(p.getUniqueId());
    }

    private void updateWaitCountdown(ColorPartyArena a) {
        wait(a).update();
    }

    /** 取（或创建）该场的等待倒计时实例：规则（OP 暂停 / 人数不足取消 / 满人 3s / 达标 30s）在库里。 */
    private WaitCountdown wait(ColorPartyArena a) {
        return waits.computeIfAbsent(a.getName(), n -> new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return a.getPlayers();
            }

            @Override
            public int minPlayers() {
                return cfg("min-players", 2);
            }

            @Override
            public int maxPlayers() {
                return cfg("max-players", 12);
            }

            @Override
            public boolean hasOpQueued() {
                for (UUID id : a.getPlayers()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOp()) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onTick(long secondsLeft) {
                for (UUID id : a.getPlayers()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        Chat.actionBar(p, "&6开始倒计时：&e" + secondsLeft + "s");
                    }
                }
            }

            @Override
            public void onMessage(String message) {
                broadcast(a, message);
            }

            // ---------- D21 主持人流程（配置 flow: host 才启用，默认走上游） ----------

            @Override

            public boolean hostFlow() {

                return "host".equalsIgnoreCase(plugin.getConfig().getString("flow", "upstream"));

            }

            @Override

            public int waitSeconds() {

                return Math.max(5, plugin.getConfig().getInt("wait-seconds", 300));

            }

            @Override

            public java.util.List<UUID> waitingOrder() {

                java.util.List<UUID> order = new ArrayList<>(a.getPlayers());

                order.sort(java.util.Comparator

                        .comparingLong((UUID id) -> a.joinTickOf(id))

                        .thenComparingLong(id -> a.joinSeedOf(id)));

                return order;

            }

            @Override

            public void onHostTimeout(UUID host) {

                Player hp = Bukkit.getPlayer(host);

                String hname = hp == null ? host.toString() : hp.getName();

                a.getPlayers().remove(host);

                playerArena.remove(host);

                ColorPartySession s2 = sessions.get(a.getName());

                if (s2 != null && hp != null && !s2.state().isFinal()) {

                    s2.leave(hp);

                }

                if (hp != null && hp.isOnline()) {

                    msg(hp, "&c倒计时结束还没开始，你被移出本局（主持人超时）。");

                    com.hub.api.HubAPI.teleportToLobby(hp);

                }

                plugin.getLogger().info("[ColorParty] 场 " + a.getName() + "：主持人 " + hname

                        + " 超时未开始，已移出（剩余 " + a.getPlayers().size() + " 人）");

                broadcast(a, "&e主持人 &f" + hname + " &e超时未开始，已移出本局。");

                updateWaitCountdown(a);

            }


            @Override
            public void onStart() {
                // 调内部无权限校验的开局方法（权限校验只在命令层 / 开始纸里做）
                Player any = a.getPlayers().isEmpty() ? null : Bukkit.getPlayer(a.getPlayers().get(0));
                if (any != null) {
                    start(any, a.getName());
                }
            }
        }));
    }

    // ---------- 战绩（第一名次数） ----------

    public void recordFirst(UUID id) {
        if (data == null) {
            return;
        }
        data.set("firsts." + id, getFirsts(id) + 1);
        saveData();
    }

    public int getFirsts(UUID id) {
        return data == null ? 0 : data.getInt("firsts." + id, 0);
    }

    public void resetFirsts() {
        if (data != null) {
            data.set("firsts", null);
            saveData();
        }
    }

    // ---------- 配置模式（道具快速配置） ----------

    public boolean isInSetup(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null && setup.isEditing(p);
    }

    /** OP 进入某场的配置模式：清背包并发配置工具。 */
    public boolean enterSetup(Player p, String arenaName) {
        ColorPartyArena a = arenas.get(arenaName);
        if (a == null) {
            msg(p, "&c场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        if (setup.isEditing(p)) {
            msg(p, "&c你已在配置模式中，先退出（屏障工具）再进入其他场。");
            return false;
        }
        setup.enter(p, arenaName, setupTools(), setupHandler());
        msg(p, "&a已进入 &e" + arenaName + "&a 的配置模式：");
        msg(p, "&b木棍&7 左键=地板角1 右键=地板角2（每回合随机彩色的区域）");
        msg(p, "&d品红染料&7 左键=面板角1 右键=面板角2   &e金锄头&7 右键=中心出生点   &f纸&7 右键=等待点");
        msg(p, "&6绿宝石&7 保存（配置 + 地图快照）   &c屏障&7 退出（淘汰线用 /cp cutoff 设置）");
        return true;
    }

    public void exitSetup(Player p) {
        setup.exit(p);
        msg(p, "&7已退出配置模式，背包已恢复。");
    }

    /** 配置工具清单（顺序 = 快捷栏 0~5）。 */
    private List<SetupMode.Tool> setupTools() {
        return List.of(
                SetupMode.Tool.both(Material.STICK, "pos",
                        "&b地板范围工具", "&7左键=地板角1&8/&7右键=地板角2（每回合随机彩色的区域）"),
                SetupMode.Tool.both(Material.MAGENTA_DYE, "panel",
                        "&d颜色面板工具", "&7左键=面板角1&8/&7右键=面板角2（显示本回合指定颜色）"),
                SetupMode.Tool.right(Material.GOLDEN_HOE, "spawn",
                        "&e中心出生点工具", "&7右键=中心出生点（开局全员站这里）"),
                SetupMode.Tool.right(Material.PAPER, "lobby",
                        "&f等待点工具", "&7右键=等待点（加入后等待的位置）"),
                SetupMode.Tool.right(Material.EMERALD, "save",
                        "&6保存场", "&7右键=保存配置 + 抓地图快照（每局结束按它重置）"),
                SetupMode.Tool.right(Material.BARRIER, "exit",
                        "&c退出配置模式", "&7右键=结束配置并恢复背包"));
    }

    /** 配置工具回调。 */
    private SetupMode.Handler setupHandler() {
        return new SetupMode.Handler() {
            @Override
            public void onTool(Player p, String arenaName, String toolId, Action action, Block clicked) {
                ColorPartyArena a = arenas.get(arenaName);
                if (a == null) {
                    exitSetup(p);
                    return;
                }
                switch (toolId) {
                    case "pos" -> {
                        if (action == Action.LEFT_CLICK_BLOCK && clicked != null) {
                            a.setPos1(clicked.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 地板角1：&f" + shortLoc(clicked.getLocation())
                                    + (a.getPos2() != null ? "（范围已定）" : "（接着右键定地板角2）"));
                        } else if (action == Action.RIGHT_CLICK_BLOCK && clicked != null) {
                            a.setPos2(clicked.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 地板角2：&f" + shortLoc(clicked.getLocation())
                                    + (a.getPos1() != null ? "（范围已定）" : "（还差地板角1）"));
                        }
                    }
                    case "panel" -> {
                        if (action == Action.LEFT_CLICK_BLOCK && clicked != null) {
                            a.setPanelPos1(clicked.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 面板角1：&f" + shortLoc(clicked.getLocation())
                                    + (a.getPanelPos2() != null ? "（面板已定）" : "（接着右键定面板角2）"));
                        } else if (action == Action.RIGHT_CLICK_BLOCK && clicked != null) {
                            a.setPanelPos2(clicked.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 面板角2：&f" + shortLoc(clicked.getLocation())
                                    + (a.getPanelPos1() != null ? "（面板已定）" : "（还差面板角1）"));
                        }
                    }
                    case "spawn" -> {
                        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                            a.setSpawn(p.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 中心出生点：&f" + shortLoc(p.getLocation()));
                        }
                    }
                    case "lobby" -> {
                        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                            a.setLobbyPoint(p.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 等待点：&f" + shortLoc(p.getLocation()));
                        }
                    }
                    default -> {
                    }
                }
            }

            @Override
            public void onSave(Player p, String arenaName) {
                ColorPartyArena a = arenas.get(arenaName);
                if (a == null) {
                    return;
                }
                saveWithSnapshot(p, a);
            }
        };
    }

    /** 配置工具交互处理。返回是否被本插件消费。 */
    public boolean handleSetupTool(Player p, Action action, Block clicked) {
        return setup.handleInteract(p, action, null, clicked);
    }

    // ---------- 辅助 ----------

    private int cfg(String key, int def) {
        return plugin.getConfig().getInt(key, def);
    }

    /** 场上所有人（存活 + 已淘汰旁观）：广播 / 标题 / 动作栏都用它。 */
    private List<UUID> participants(ColorPartyArena a) {
        List<UUID> all = new ArrayList<>(a.getPlayers());
        all.addAll(a.getEliminated());
        return all;
    }

    private void broadcast(ColorPartyArena a, String msg) {
        for (UUID id : participants(a)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                msg(p, msg);
            }
        }
    }

    private void msg(Player p, String msg) {
        Chat.sendTitled(p, msg);
    }

    private String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p == null ? id.toString().substring(0, 6) : p.getName();
    }

    private String shortLoc(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
    }
}
