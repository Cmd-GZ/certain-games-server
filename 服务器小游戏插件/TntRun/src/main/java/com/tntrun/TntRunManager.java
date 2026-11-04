package com.tntrun;

import com.hub.session.EndReason;

import com.hub.api.GameState;
import com.hub.data.PlayerStats;
import com.hub.api.HubAPI;
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
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 雪塔大作战核心：踩雪层层塌陷、挖雪得雪球、雪球砸雪块、掉线淘汰、方块恢复。 */
public class TntRunManager {

    /** 游戏注册名（HubCore 菜单 / 战绩播报 / tab 都用这个显示名）。 */
    public static final String GAME = "雪塔大作战";
    /** 旧显示名：只作兼容用（万一线上 HubCore 的 games 段还没改，注册表里仍是这个名字）。 */
    private static final String LEGACY_GAME = "TNT Run";

    private final TntRunPlugin plugin;
    private final LiveSidebar liveSidebar;
    private final Map<String, TntRunArena> arenas = new LinkedHashMap<>();

    /**
     * 每个场地一个会话（多场地游戏：不同场地可以同时各开一局）。
     *
     * <p>会话是**一次性**的：end() 之后必须换新的，所以取值时用 ensureSession 保证可用。
     */
    private final Map<String, TntRunSession> sessions = new LinkedHashMap<>();

    private TntRunSession ensureSession(String arenaName) {
        TntRunSession s = sessions.get(arenaName);
        if (s == null || s.state().isFinal()) {
            s = new TntRunSession(this, arenaName);
            sessions.put(arenaName, s);
        }
        return s;
    }
    private final Map<UUID, String> playerArena = new LinkedHashMap<>();
    /** 每个场一个等待倒计时实例（公共库 com.hub.util.WaitCountdown）。 */
    private final Map<String, WaitCountdown> waits = new LinkedHashMap<>();
    /** 防蹲人定时检测任务（站着不动也会触发踩雪）。 */
    private org.bukkit.scheduler.BukkitTask campTask;
    /** 给每把雪铲打的不毁标记（persistent data，重启后依然认得出是"本场发的铲子"）。 */
    private final NamespacedKey shovelKey = new NamespacedKey("gamehub", "snow-shovel");

    // 数据与配置模式
    private YamlConfiguration data;
    /** 公共库建场模式（背包备份、工具发放、工具识别都在库里）。 */
    private final SetupMode setup;
    private final Map<String, List<Location>> joinSigns = new LinkedHashMap<>();
    private final Map<UUID, String> pendingSign = new LinkedHashMap<>();
    /** I3：会话里的搬迁代码要用到 plugin。 */
    public TntRunPlugin plugin() {
        return plugin;
    }


    public TntRunManager(TntRunPlugin plugin) {
        this.plugin = plugin;
        this.liveSidebar = new LiveSidebar(plugin);
        this.setup = new SetupMode(plugin, "tr-setup-tool");
    }

    public void load() {
        startCountdown();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("arenas");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                TntRunArena a = TntRunArena.load(name, sec.getConfigurationSection(name));
                loadArenaSnapshot(a);
                arenas.put(name, a);
            }
        }
        // 战绩数据
        // 战绩数据（唯一文件 plugins/MiniGameLib/players.yml，命名空间 tntrun）
        data = PlayerStats.load("tntrun", new File(plugin.getDataFolder(), "data.yml"));
        // 加入告示牌
        ConfigurationSection js = plugin.getConfig().getConfigurationSection("join-signs");
        if (js != null) {
            for (String name : js.getKeys(false)) {
                List<?> list = js.getList(name);
                if (list == null) {
                    continue;
                }
                List<Location> locs = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Location loc) {
                        locs.add(loc);
                    }
                }
                if (!locs.isEmpty()) {
                    joinSigns.put(name, locs);
                }
            }
        }
        // 启动后把配置中已有场地全部上报给 HubCore 中央菜单（延迟等待 HubCore 就绪）
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // 兜底登记：线上 HubCore 的 games 段还没改成新显示名时，菜单里也能出现"雪塔大作战"
            // （已经登记过同名游戏时 HubCore 会跳过，不会覆盖它自己的颜色/图标）
            HubAPI.registerGame(GAME, GAME, "SNOWBALL", "b");
            for (TntRunArena a : arenas.values()) {
                reportArena(a);
            }
        }, 40L);
        // 防蹲人：定时对场内所有玩家做踩雪检测，站着不动也会塌
        startCampTask();
    }

    /** 读回建场时保存的地图快照（arena-&lt;场名&gt;.snap）；没有文件就跳过（老场地照旧用 broken 逐格还原）。 */
    private void loadArenaSnapshot(TntRunArena a) {
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

    /** 启动防蹲人定时检测：每 camp-check-ticks 检测一次所有正在游戏中玩家的脚下方块。 */
    private void startCampTask() {
        int ticks = Math.max(1, plugin.getConfig().getInt("camp-check-ticks", 20));
        campTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (TntRunArena a : arenas.values()) {
                if (a.getState() != TntRunArena.State.RUNNING) {
                    continue;
                }
                for (UUID id : new ArrayList<>(a.getPlayers())) {
                    // 一次检测可能淘汰玩家并结束游戏，避免继续对已结束的场做检测
                    if (a.getState() != TntRunArena.State.RUNNING) {
                        break;
                    }
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        checkFade(p, a);
                    }
                }
                updateLiveBoard(a);
            }
        }, ticks, ticks);
    }

    /** 停止定时检测并还原所有场地地板（插件卸载时调用）。 */
    public void shutdown() {
        liveSidebar.hideAll();
        if (campTask != null) {
            campTask.cancel();
            campTask = null;
        }
        restoreAllBlocks();
        setup.exitAll(); // 兜底：还在配置模式的玩家恢复背包（公共库关服时也会统一还原）
    }

    /**
     * 把场地重置回原样（endGame 与关服兜底共用）：
     * 有地图快照（建场"保存"时抓的）就按快照整份重置——挖掉的雪补回、本局新放的方块清成空气；
     * 没快照的老场地退回"只把本局挖掉/砸掉的 broken 格子补回去"。
     */
    private void restoreBlocks(TntRunArena a) {
        int changed = a.restoreSnapshot();
        if (changed < 0) {
            // 世界已被卸载（getWorld() 为 null）时跳过，别在关服路径上抛 NPE
            if (!a.getBroken().isEmpty() && a.getPos1() != null && a.getPos1().getWorld() != null) {
                World w = a.getPos1().getWorld();
                for (Map.Entry<String, Material> e : a.getBroken().entrySet()) {
                    String[] parts = e.getKey().split(",");
                    if (parts.length != 3) {
                        continue;
                    }
                    try {
                        Block b = w.getBlockAt(Integer.parseInt(parts[0]),
                                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                        b.setType(e.getValue());
                    } catch (NumberFormatException ignored) {
                        // 脏数据：跳过这一格
                    }
                }
            }
        } else if (changed > 0) {
            plugin.getLogger().fine("场 " + a.getName() + " 已按地图快照重置（重写 " + changed + " 格）。");
        }
        a.getBroken().clear();
        a.getPendingFade().clear();
        a.getRoundSpawns().clear();
    }

    /**
     * 保存场地：先落盘配置，再把 pos1..pos2 的地图抓成快照写 arena-&lt;场名&gt;.snap（"圈地后保存地图"）。
     * 范围没圈好或正在对局时不抓快照（对局中抓会把已经塌掉的地形存成"原样"），只保存配置。
     */
    public void saveWithSnapshot(Player p, TntRunArena a) {
        saveAll();
        if (a.getPos1() == null || a.getPos2() == null || a.getPos1().getWorld() == null) {
            msg(p, "&a已保存场 &e" + a.getName() + "&a。&7（还没圈范围 pos1/pos2，未生成地图快照）");
            return;
        }
        if (a.getState() == TntRunArena.State.RUNNING) {
            msg(p, "&a已保存场 &e" + a.getName() + "&a。&7（对局中，跳过地图快照；等这局结束再保存一次就会写入）");
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
        msg(p, "&a已保存场 &e" + a.getName() + "&a：地图快照 &e" + n + " &a格（每局结束按它重置地图）。");
        if (a.getCenter() == null) {
            msg(p, "&7提示：还没设最上层中心（配置模式指南针 / &e/tr center&7），开局仍用出生点，不会围成圆。");
        } else {
            int bad = a.circleOutOfRange();
            if (bad > 0) {
                msg(p, "&c警告：以中心为圆心、半径 5 格的圆有 &e" + bad + " &c个点落在圈地范围外，那些玩家开局会踩空。");
            }
        }
    }

    /**
     * 关服兜底：所有场地踩掉的方块全部还原。
     * broken 只存在内存里，不还原就会在世界里留下永久空洞。
     */
    public void restoreAllBlocks() {
        for (TntRunArena a : arenas.values()) {
            restoreBlocks(a);
        }
    }

    /**
     * 声明地盘（{@code docs/框架不变量.md} 的 I2）：把每个场地的边界报给框架。
     * 只在内存里读字段，不碰配置；还没建场的场地会被 {@code Regions} 静默跳过。
     */
    public void publishRegions() {
        com.hub.region.Regions.begin("tntrun");
        arenas.forEach((name, a) ->
                com.hub.region.Regions.box("tntrun", name, a.getPos1(), a.getPos2()));
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
        for (Map.Entry<String, TntRunArena> e : arenas.entrySet()) {
            if (!e.getValue().isReady()) {
                plugin.getLogger().warning("[保存] 场地 " + e.getKey()
                        + " 未就绪（世界缺失？），跳过回写以免丢坐标");
            } else {
                e.getValue().save(com.hub.world.Worlds.getOrCreate(sec, e.getKey()));
            }
        }
        ConfigurationSection js = com.hub.world.Worlds.getOrCreate(plugin.getConfig(), "join-signs");
        for (Map.Entry<String, List<Location>> e : joinSigns.entrySet()) {
            js.set(e.getKey(), e.getValue());
        }
        plugin.saveConfig();
        saveData();
    }

    private void saveData() {
        if (data == null) {
            return;
        }
        try {
            PlayerStats.save("tntrun", data);
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("保存战绩失败：" + ex.getMessage());
        }
    }

    public TntRunArena getArena(String name) {
        return arenas.get(name);
    }

    public Map<String, TntRunArena> getArenas() {
        return arenas;
    }

    public boolean createArena(String name) {
        if (arenas.containsKey(name)) {
            return false;
        }
        arenas.put(name, new TntRunArena(name));
        return true;
    }

    /** 删除场（仅等待且无人时），连同其加入告示牌。 */
    public boolean removeArena(String name) {
        TntRunArena a = arenas.get(name);
        if (a == null) {
            return false;
        }
        if (a.getState() != TntRunArena.State.WAITING || !a.getPlayers().isEmpty() || !a.getEliminated().isEmpty()) {
            return false;
        }
        arenas.remove(name);
        waits.remove(name); // 丢掉该场的倒计时实例，避免同名重建后复用旧实例
        File snap = a.snapshotFile(plugin.getDataFolder());
        if (snap.isFile() && !snap.delete()) {
            plugin.getLogger().warning("删除场 " + name + " 的地图快照失败：" + snap.getAbsolutePath());
        }
        plugin.getConfig().set("arenas." + name, null);
        joinSigns.remove(name);
        plugin.getConfig().set("join-signs." + name, null);
        plugin.saveConfig();
        HubAPI.removeArena(GAME, name);
        HubAPI.removeArena(LEGACY_GAME, name); // 线上 config 的游戏名还没改时，菜单里那条旧登记也要一起撤
        return true;
    }

    /** 场地改名：同步配置、告示牌、玩家/倒计时引用与中央菜单；对局中禁止。 */
    public boolean renameArena(String oldName, String newName) {
        if (oldName == null || oldName.equals(newName) || !validName(newName) || arenas.containsKey(newName)) {
            return false;
        }
        TntRunArena a = arenas.get(oldName);
        if (a == null || a.getState() == TntRunArena.State.RUNNING) {
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
        // 玩家 / 配置模式 / 待绑定告示牌引用同步
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
        for (Map.Entry<UUID, String> e : pendingSign.entrySet()) {
            if (e.getValue().equals(oldName)) {
                e.setValue(newName);
            }
        }
        // 倒计时实例按新名重挂（实例内闭包持有的是同一个 arena 对象，getName() 已返回新名）
        WaitCountdown wc = waits.remove(oldName);
        if (wc != null) {
            waits.put(newName, wc);
        }
        // 加入告示牌：重挂并刷新文字
        List<Location> locs = joinSigns.remove(oldName);
        if (locs != null) {
            joinSigns.put(newName, locs);
            refreshJoinSigns(newName);
        }
        saveAll(); // 配置（场 + 加入告示牌）立即落盘
        HubAPI.removeArena(GAME, oldName); // 菜单移除旧名
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

    /** 上报场地状态到 HubCore 中央菜单（羊毛行：绿=等待，红=游戏中）。 */
    private void reportArena(TntRunArena a) {
        boolean playing = a.getState() == TntRunArena.State.RUNNING;
        HubAPI.setArena(GAME, a.getName(),
                playing ? GameState.PLAYING : GameState.WAITING, a.getPlayers().size(), null);
        HubAPI.setArenaSpectator(GAME, a.getName(), a.getCenter());
    }

    public String arenaOf(UUID id) {
        return playerArena.get(id);
    }

    // ---------- 会话 ----------

    public boolean join(Player p, String arenaName) {
        TntRunArena a = arenas.get(arenaName);
        if (a == null) {
            msg(p, "&c场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        UUID id = p.getUniqueId();
        if (playerArena.containsKey(id)) {
            msg(p, "&c你已在其他场。");
            return false;
        }
        if (a.getState() == TntRunArena.State.RUNNING) {
            msg(p, "&c该场正在游戏中。");
            return false;
        }
        if (!a.isReady()) {
            msg(p, "&c该场未配置完成。");
            return false;
        }
        int max = plugin.getConfig().getInt("max-players", 12);
        if (a.getPlayers().size() >= max) {
            msg(p, "&c人数已满（&e" + max + "&c）。");
            return false;
        }
        playerArena.put(id, arenaName);
        a.getPlayers().add(id);
        a.recordJoin(id);   // D21：记下加入 tick + 随机名次（同 tick 随机选主持人）
        TntRunSession sess = ensureSession(arenaName);
        if (!sess.join(p)) {
            plugin.getLogger().warning("[TntRun] " + p.getName() + " 加入归属链失败（场 " + arenaName + "）");
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
        com.hub.api.HubAPI.announceJoin(GAME, p.getName()); // 全服公屏播报
        updateWaitCountdown(a);
        reportArena(a);
        return true;
    }

    public void leave(Player p) {
        leave(p, false);
    }

    /**
     * 离开场地。{@code silent=true} 用于主城"复原大厅状态"的回调（进服复原 / 右键回主城道具 / 回到大厅）：
     * 玩家本来就不在场中时直接返回，不打任何提示、也不再回调 HubAPI.teleportToLobby——
     * 否则每个注册了 lobby handler 的游戏插件都会各喊一句"你不在任何场中"，进服一次刷三行。
     */
    public void leave(Player p, boolean silent) {
        UUID id = p.getUniqueId();
        String name = playerArena.remove(id);
        if (name == null) {
            if (!silent) {
                msg(p, "&c你不在任何场中。");
            }
            return;
        }
        TntRunArena a = arenas.get(name);
        if (a != null) {
            a.getPlayers().remove(id);
            a.getEliminated().remove(id);
            // I1：摘掉该场的会话归属（注意要放在下面"人走空自动收场"之前，
            // 否则 session.leave 的自动 end(ABORT) 会和那套逻辑撞车）
            TntRunSession sess = sessions.get(name);
            if (sess != null && !sess.state().isFinal()) {
                sess.leave(p);
            }
            HubAPI.clearGame(id);
            broadcast(a, "&e" + p.getName() + "&f 离开了（剩 &e" + a.getPlayers().size() + "&f 人）");
            if (a.getState() == TntRunArena.State.RUNNING && a.getPlayers().size() <= 1) {
                endGame(a, a.getPlayers().isEmpty() ? null : a.getPlayers().get(0));
            }
            reportArena(a);
        }
        p.setGameMode(GameMode.SURVIVAL);
        HubAPI.teleportToLobby(p); // 回主城并拿菜单道具
        msg(p, "&7你已离开。");
        if (a != null && a.getState() == TntRunArena.State.WAITING) {
            updateWaitCountdown(a);
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
        TntRunArena a = arenas.get(arenaName);
        if (a == null) {
            msg(op, "&c场不存在。");
            return false;
        }
        int min = plugin.getConfig().getInt("min-players", 2);
        if (a.getPlayers().size() < min) {
            msg(op, "&c人数不足（至少 &e" + min + "&c 人）。");
            return false;
        }
        if (startCountdown().begin(arenaName, new ArrayList<>(a.getPlayers()), () -> start(op, arenaName))) {
            return true;
        }
        a.setState(TntRunArena.State.RUNNING);
        a.nextRound(); // 新局次：上一局残留的"延迟消失"任务由此失效，不会再改已经重置的地图
        wait(a).cancel();
        reportArena(a);
        a.getEliminated().clear();
        a.getElimOrder().clear();
        a.getBroken().clear();
        a.getPendingFade().clear();
        a.getRoundSpawns().clear();
        // 开局站位：以「最上层中心」为圆心、半径 5 格的圆边缘，按在场人数平均分配（没设中心则全部用出生点）
        List<UUID> ids = new ArrayList<>(a.getPlayers());
        List<Location> ring = a.circleSpawns(ids.size());
        if (ring.isEmpty() && a.getCenter() == null) {
            plugin.getLogger().info("场 " + a.getName() + " 没设最上层中心，开局仍用出生点（/tr center 或配置模式指南针可设置）。");
        }
        for (int i = 0; i < ids.size(); i++) {
            UUID id = ids.get(i);
            HubAPI.setPlaying(id, GAME); // 游戏开始：tab 从 [等待中] 变为 [已加入]
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Location spot = ring.isEmpty() ? a.getSpawn() : ring.get(i % ring.size());
                a.getRoundSpawns().put(id, spot);
                p.getInventory().clear();
                p.setGameMode(GameMode.SURVIVAL);
                p.setHealth(20);
                p.setFoodLevel(20);
                p.teleport(spot);
                giveSnowShovel(p); // 每人一把效率5不毁的铁铲（背包清空后再发）
            }
        }
        broadcast(a, "&b&l— 游戏开始！层层塌的雪塔，掉下去就出局！—");
        return true;
    }

    // ---------- 等待倒计时 ----------

    /** 每人一把铁铲：效率 V + 不毁（铲子只用来挖雪块，挖一格给一个雪球）。 */
    public void giveSnowShovel(Player p) {
        ItemStack shovel = new ItemStack(Material.IRON_SHOVEL);
        ItemMeta meta = shovel.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color("&b雪铲 &7(挖雪块得雪球)")));
            meta.setUnbreakable(true);
            meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
            meta.getPersistentDataContainer().set(shovelKey, PersistentDataType.BYTE, (byte) 1);
            shovel.setItemMeta(meta);
        }
        p.getInventory().setItem(0, shovel);
    }

    /** 这把铲子是不是本局发的（persistent data 标记，重启后仍认得出）。 */
    public boolean isSnowShovel(ItemStack item) {
        if (item == null || item.getType() != Material.IRON_SHOVEL || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(shovelKey, PersistentDataType.BYTE);
    }

    /** 退出床。 */
    public void giveLeaveBed(Player p) {
        org.bukkit.inventory.ItemStack bed = new org.bukkit.inventory.ItemStack(Material.RED_BED);
        var meta = bed.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color("&c退出游戏 &7(右键)")));
            bed.setItemMeta(meta);
        }
        p.getInventory().setItem(8, bed);
    }

    /** 玩家右键退出床。 */
    public void handleLeaveBed(Player p) {
        leave(p);
    }

    /** OP 的开始纸。 */
    public void giveStartPaper(Player p) {
        org.bukkit.inventory.ItemStack paper = new org.bukkit.inventory.ItemStack(Material.PAPER);
        var meta = paper.getItemMeta();
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
        TntRunArena a = arenas.get(arenaName);
        if (a == null || a.getState() != TntRunArena.State.WAITING) {
            return;
        }
        if (!p.isOp() && !p.hasPermission("tr.admin")) {
            msg(p, "&c只有管理员（OP 或 tr.admin）可以开始。");
            return;
        }
        start(p, arenaName);
    }

    /** 人数变化后刷新该场的等待倒计时（内部交给公共库 WaitCountdown，保留原方法名给各调用点用）。 */
    private void updateWaitCountdown(TntRunArena a) {
        wait(a).update();
    }

    /** 取（或创建）该场的等待倒计时实例：每个场一个，规则（OP 暂停 / 人数不足取消 / 满人 3s / 达标 30s）在库里。 */
    /** 某场地当前等待人数。 */
    public int waitingCountOf(String arenaName) {
        TntRunArena a = arenas.get(arenaName);
        return a == null ? 0 : a.getPlayers().size();
    }

    /** 某场地的最小开局人数。 */
    public int minPlayersOf(String arenaName) {
        return plugin.getConfig().getInt("min-players", 2);
    }

    /** 发送者是否是某场地当前的主持人（D21；仅 flow: host 时成立）。 */
    public boolean isHostOf(Player p, String arenaName) {
        if (!"host".equalsIgnoreCase(plugin.getConfig().getString("flow", "upstream"))) {
            return false;
        }
        TntRunArena a = arenas.get(arenaName);
        if (a == null || p == null) {
            return false;
        }
        UUID host = wait(a).currentHost();
        return host != null && host.equals(p.getUniqueId());
    }

    private WaitCountdown wait(TntRunArena a) {
        return waits.computeIfAbsent(a.getName(), n -> new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return a.getPlayers();
            }

            @Override
            public int minPlayers() {
                return plugin.getConfig().getInt("min-players", 2);
            }

            @Override
            public int maxPlayers() {
                return plugin.getConfig().getInt("max-players", 12);
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
                // 顺序 = (加入 tick, 同 tick 内随机种子)：同 tick 进来的人随机当主持人
                java.util.List<UUID> order = new ArrayList<>(a.getPlayers());
                order.sort(java.util.Comparator
                        .comparingLong((UUID id) -> a.joinTickOf(id))
                        .thenComparingLong(id -> a.joinSeedOf(id)));
                return order;
            }

            @Override
            public void onHostTimeout(UUID host) {
                Player p = Bukkit.getPlayer(host);
                String name = p == null ? host.toString() : p.getName();
                a.getPlayers().remove(host);
                playerArena.remove(host);
                TntRunSession sess = sessions.get(a.getName());
                if (sess != null && p != null && !sess.state().isFinal()) {
                    sess.leave(p);
                }
                if (p != null && p.isOnline()) {
                    msg(p, "&c倒计时结束还没开始，你被移出本局（主持人超时）。");
                    com.hub.api.HubAPI.teleportToLobby(p);
                }
                plugin.getLogger().info("[TntRun] 场 " + a.getName() + "：主持人 " + name
                        + " 超时未开始，已移出（剩余 " + a.getPlayers().size() + " 人）");
                broadcast(a, "&e主持人 &f" + name + " &e超时未开始，已移出本局。");
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

    /** 移动事件：踩雪检测 + 淘汰线检测。 */
    public void handleMove(Player p) {
        UUID id = p.getUniqueId();
        String arenaName = playerArena.get(id);
        if (arenaName == null) {
            return;
        }
        TntRunArena a = arenas.get(arenaName);
        if (a == null || a.getState() != TntRunArena.State.RUNNING) {
            return;
        }
        if (!a.getPlayers().contains(id)) {
            return;
        }
        checkFade(p, a);
    }

    /** 该玩家是否正在对局中（RUNNING）且这一格在他的场地范围内 —— 挖雪 / 砸雪块 / 给雪球都先过这一关。 */
    public boolean isPlayingIn(Player p, Location loc) {
        String arenaName = playerArena.get(p.getUniqueId());
        if (arenaName == null) {
            return false;
        }
        TntRunArena a = arenas.get(arenaName);
        return a != null && a.getState() == TntRunArena.State.RUNNING
                && a.getPlayers().contains(p.getUniqueId())
                && loc != null && a.contains(loc);
    }

    /**
     * 挖掉一格雪块：记进本局要恢复的方块表 + 给玩家 1 个雪球。
     * 调用方（BlockBreakEvent）已确认是"对局中 + 场地内 + 雪块"。
     */
    public void breakSnow(Player p, Block block) {
        String key = TntRunArena.blockKey(block.getX(), block.getY(), block.getZ());
        block.setType(Material.AIR, false);
        TntRunArena a = arenas.get(playerArena.get(p.getUniqueId()));
        if (a != null) {
            a.getBroken().put(key, Material.SNOW_BLOCK);
        }
        giveSnowball(p);
    }

    /**
     * 雪球砸中的雪块消失（只这一格，不往下连带塌）。
     * 不在任何人场地内、或那一格不是雪块时什么都不做——所以别人的世界/场地不会被误伤。
     */
    public void breakSnowByBall(Block block) {
        for (TntRunArena a : arenas.values()) {
            if (a.getState() != TntRunArena.State.RUNNING || !a.contains(block.getLocation())) {
                continue;
            }
            if (block.getType() != Material.SNOW_BLOCK) {
                return;
            }
            String key = TntRunArena.blockKey(block.getX(), block.getY(), block.getZ());
            block.setType(Material.AIR, false);
            a.getBroken().put(key, Material.SNOW_BLOCK);
            return; // 场范围不重叠，命中一个即可
        }
    }

    /** 给玩家 1 个雪球；背包塞不下就掉在脚下（不静默吞掉）。 */
    private void giveSnowball(Player p) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(Material.SNOWBALL, 1));
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    /** 踩雪检测 + 淘汰线检测（移动事件与防蹲人定时检测共用）。 */
    private void checkFade(Player p, TntRunArena a) {
        // 淘汰线
        if (p.getLocation().getY() < a.getCutoffY()) {
            eliminate(a, p);
            return;
        }
        // 踩雪检测：脚底中心 + 四角 5 个采样点。
        // 只检测中心点时，人站在洞口边缘会因中心格已是空气而漏检支撑方块，导致卡在边缘不掉下去。
        Location feet = p.getLocation().subtract(0, 1, 0);
        int by = feet.getBlockY();
        double x = feet.getX();
        double z = feet.getZ();
        double[][] pts = {
                {0, 0},
                {0.29, 0.29}, {0.29, -0.29},
                {-0.29, 0.29}, {-0.29, -0.29}
        };
        for (double[] o : pts) {
            Block b = feet.getWorld().getBlockAt((int) Math.floor(x + o[0]), by, (int) Math.floor(z + o[1]));
            if (b.getType() != Material.SNOW_BLOCK) {
                continue;
            }
            // 必须在配置范围内
            if (!a.contains(b.getLocation())) {
                continue;
            }
            scheduleFade(a, b);
        }
    }

    /**
     * 调度"这一格雪块到点消失"，消失后继续往下塌（原版是沙+TNT两格一起没，这里改成沿雪柱层层塌）。
     * 已调度的格子用 pending 去重：一处先排上队，其它路径不会重复调度。
     */
    private void scheduleFade(TntRunArena a, Block snow) {
        String key = TntRunArena.blockKey(snow.getX(), snow.getY(), snow.getZ());
        if (!a.getPendingFade().add(key)) {
            return; // 已调度
        }
        int fadeTicks = plugin.getConfig().getInt("fade-ticks", 10);
        long round = a.getRound();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            a.getPendingFade().remove(key);
            // 这局已经结束（地图已按快照重置）→ 上一局残留的消失任务不能再动地图，否则刚补回的雪又被挖空
            if (a.getState() != TntRunArena.State.RUNNING || a.getRound() != round) {
                return;
            }
            if (snow.getType() == Material.SNOW_BLOCK) {
                snow.setType(Material.AIR, false);
                a.getBroken().put(key, Material.SNOW_BLOCK);
                // 层层塌：下面还是雪块就接着排队（踩出来的洞不会"卡"在半空）
                Block below = snow.getRelative(0, -1, 0);
                if (below.getType() == Material.SNOW_BLOCK && a.contains(below.getLocation())) {
                    scheduleFade(a, below);
                }
            }
        }, fadeTicks);
    }

    /** 淘汰。 */
    private void eliminate(TntRunArena a, Player p) {
        UUID id = p.getUniqueId();
        a.getPlayers().remove(id);
        a.getEliminated().add(id);
        a.getElimOrder().add(id);
        p.setGameMode(GameMode.SPECTATOR);
        HubAPI.clearGame(id);
        broadcast(a, "&c" + p.getName() + " &f掉下去了！（剩 &e" + a.getPlayers().size() + "&f 人）");
        reportArena(a);

        if (a.getPlayers().size() <= 1) {
            endGame(a, a.getPlayers().isEmpty() ? null : a.getPlayers().get(0));
        }
    }

    /** 结束：前三名 + 恢复方块。 */
    private void updateLiveBoard(TntRunArena a) {
        List<UUID> online = new ArrayList<>(a.getPlayers());
        online.sort(Comparator.comparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        lines.add(new LiveSidebar.Line("§7存活 §f" + a.getPlayers().size(), 1));
        for (UUID id : online) {
            lines.add(new LiveSidebar.Line("§e" + nameOf(id), 0));
        }
        liveSidebar.update("tntrun:" + a.getName(), "&b&l雪塔大作战 · 存活", online, lines);
    }

    /** 收场入口（I4）：先让该场地的会话 end()，再由 onEnd 回到 {@link #doEndGame} 干活。 */
    private void endGame(TntRunArena a, UUID winner) {
        TntRunSession sess = sessions.get(a.getName());
        if (sess == null || sess.state().isFinal()) {
            pendingWinner.put(a.getName(), winner);
            doEndGame(a.getName());
            return;
        }
        pendingWinner.put(a.getName(), winner);
        sess.end(EndReason.ROUND_END);
    }

    /** 各场地的"赢家"暂存，供 doEndGame 使用（endGame 与 onEnd 之间传递）。 */
    private final Map<String, UUID> pendingWinner = new java.util.LinkedHashMap<>();

    /** 真正的收场逻辑（由 session.onEnd 回调，也可在没有会话时直接调用）。 */
    void doEndGame(String arenaName) {
        TntRunArena a = arenas.get(arenaName);
        if (a == null) {
            return;
        }
        UUID winner = pendingWinner.remove(arenaName);
        liveSidebar.hide("tntrun:" + a.getName());
        a.setState(TntRunArena.State.WAITING);
        reportArena(a);
        broadcast(a, "&b&l========== 雪塔大作战 结束 ==========");
        List<UUID> firsts = new ArrayList<>();
        List<UUID> seconds = new ArrayList<>();
        List<UUID> thirds = new ArrayList<>();
        if (winner != null) {
            firsts.add(winner);
        }
        List<UUID> elim = a.getElimOrder();
        int len = elim.size();
        if (len >= 1) {
            seconds.add(elim.get(len - 1));
        }
        if (len >= 2) {
            thirds.add(elim.get(len - 2));
        }
        Top3.broadcast(msg -> broadcast(a, msg), this::nameOf, firsts, seconds, thirds);
        broadcast(a, "&b===================================");
        ArcadeBridge.report("tntrun", firsts, seconds, thirds);
        Top3.award(data, firsts, seconds, thirds);
        saveData();
        // 恢复方块
        restoreBlocks(a);
        broadcast(a, "&7场地已恢复。");
        // 恢复玩家：自动回主城并拿菜单道具
        for (UUID id : new ArrayList<>(a.getPlayers())) {
            Player pp = Bukkit.getPlayer(id);
            if (pp != null && pp.isOnline()) {
                pp.setGameMode(GameMode.SURVIVAL);
                HubAPI.teleportToLobby(pp); // 清状态 + 回主城 + 菜单道具
            } else {
                HubAPI.clearGame(id);
            }
            playerArena.remove(id);
        }
        for (UUID id : new ArrayList<>(a.getEliminated())) {
            Player pp = Bukkit.getPlayer(id);
            if (pp != null && pp.isOnline()) {
                pp.setGameMode(GameMode.SURVIVAL);
                HubAPI.teleportToLobby(pp); // 清状态 + 回主城 + 菜单道具
            } else {
                HubAPI.clearGame(id);
            }
            playerArena.remove(id);
        }
        a.getPlayers().clear();
        a.getEliminated().clear();
        a.getElimOrder().clear();
    }

    public void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        String name = playerArena.remove(id);
        if (name == null) {
            return;
        }
        TntRunArena a = arenas.get(name);
        if (a == null) {
            return;
        }
        a.getPlayers().remove(id);
        a.getEliminated().remove(id);
        HubAPI.clearGame(id);
        reportArena(a);
        if (a.getState() == TntRunArena.State.RUNNING && a.getPlayers().size() <= 1) {
            endGame(a, a.getPlayers().isEmpty() ? null : a.getPlayers().get(0));
        }
    }

    // ---------- 战绩（第一名次数） ----------

    /** 玩家获得一次第一（每场冠军 +1）。 */
    public void recordFirst(UUID id) {
        if (data == null) {
            return;
        }
        data.set("firsts." + id, getFirsts(id) + 1);
        saveData();
    }

    /** 玩家获得第一名的次数。 */
    public int getFirsts(UUID id) {
        return data == null ? 0 : data.getInt("firsts." + id, 0);
    }

    /** OP 清空全部第一名记录。 */
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

    public TntRunArena getSetupArena(UUID id) {
        Player p = Bukkit.getPlayer(id);
        String name = p == null ? null : setup.editingArena(p);
        return name == null ? null : arenas.get(name);
    }

    /** OP 进入某场的配置模式：清背包并发配置工具（备份/发放/识别都在公共库 SetupMode 里）。 */
    public boolean enterSetup(Player p, String arenaName) {
        TntRunArena a = arenas.get(arenaName);
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
        msg(p, "&b木棍&7 左键=范围角1 右键=范围角2");
        msg(p, "&e金锄头&7 右键=出生点  &f纸&7 右键=等待点  &b指南针&7 右键=最上层中心（开局站位圆心）");
        msg(p, "&6绿宝石&7 保存（配置 + 地图快照）  &c屏障&7 退出（淘汰线用 /tr cutoff 设置）");
        return true;
    }

    /** 退出配置模式并恢复背包。 */
    public void exitSetup(Player p) {
        setup.exit(p);
        msg(p, "&7已退出配置模式，背包已恢复。");
    }

    /** 配置工具清单（顺序 = 快捷栏 0~5）。 */
    private List<SetupMode.Tool> setupTools() {
        return List.of(
                SetupMode.Tool.both(Material.STICK, "pos",
                        "&b范围工具", "&7左键=范围角1&8/&7右键=范围角2"),
                SetupMode.Tool.right(Material.GOLDEN_HOE, "spawn",
                        "&e出生点工具", "&7右键=出生点（当前位置）"),
                SetupMode.Tool.right(Material.PAPER, "lobby",
                        "&f等待点工具", "&7右键=等待点（当前位置）"),
                SetupMode.Tool.right(Material.COMPASS, "center",
                        "&b中心点工具", "&7右键=最上层中心（开局站位圆心，半径5格）"),
                SetupMode.Tool.right(Material.EMERALD, "save",
                        "&6保存场", "&7右键=保存配置 + 抓地图快照（每局结束按它重置）"),
                SetupMode.Tool.right(Material.BARRIER, "exit",
                        "&c退出配置模式", "&7右键=结束配置并恢复背包"));
    }

    /** 配置工具回调：处理本插件自己的工具（save / exit 由公共库处理）。 */
    private SetupMode.Handler setupHandler() {
        return new SetupMode.Handler() {
            @Override
            public void onTool(Player p, String arenaName, String toolId, Action action, Block clicked) {
                TntRunArena a = arenas.get(arenaName);
                if (a == null) {
                    exitSetup(p);
                    return;
                }
                switch (toolId) {
                    case "pos" -> {
                        if (action == Action.LEFT_CLICK_BLOCK && clicked != null) {
                            a.setPos1(clicked.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 范围角1：&f" + shortLoc(clicked.getLocation())
                                    + (a.getPos2() != null ? "（范围已定）" : "（接着右键定范围角2）"));
                        } else if (action == Action.RIGHT_CLICK_BLOCK && clicked != null) {
                            a.setPos2(clicked.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 范围角2：&f" + shortLoc(clicked.getLocation())
                                    + (a.getPos1() != null ? "（范围已定）" : "（还差范围角1）"));
                        }
                    }
                    case "spawn" -> {
                        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                            a.setSpawn(p.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 出生点：&f" + shortLoc(p.getLocation()));
                        }
                    }
                    case "lobby" -> {
                        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                            a.setLobbyPoint(p.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 等待点：&f" + shortLoc(p.getLocation()));
                        }
                    }
                    case "center" -> {
                        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                            a.setCenter(p.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 最上层中心：&f" + shortLoc(p.getLocation()));
                            msg(p, "&7开局把玩家平均放在以它为圆心、半径 5 格的圆边缘；记得 &e/tr save&7（或绿宝石）保存地图快照。");
                            int bad = a.circleOutOfRange();
                            if (bad > 0) {
                                msg(p, "&c警告：半径 5 的圆有 &e" + bad + " &c个点在圈地范围外，那些玩家开局会踩空。");
                            }
                        }
                    }
                    default -> {
                    }
                }
            }

            @Override
            public void onSave(Player p, String arenaName) {
                TntRunArena a = arenas.get(arenaName);
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

    // ---------- 加入告示牌 ----------

    /** 进入加入告示牌绑定模式：随后右键点击一个告示牌，把它变为该场的加入牌。 */
    public void placeJoinSign(Player p, String arenaName) {
        if (arenas.get(arenaName) == null) {
            msg(p, "&c场不存在。");
            return;
        }
        pendingSign.put(p.getUniqueId(), arenaName);
        msg(p, "&a请放置/选择一个告示牌，然后&e右键点击它&a，将其变为 &e" + arenaName + "&a 的加入牌。");
    }

    /** 右键告示牌绑定（玩家处于待绑定状态时）。 */
    public boolean handleSignBind(Player p, Location loc) {
        String arenaName = pendingSign.remove(p.getUniqueId());
        if (arenaName == null) {
            return false;
        }
        joinSigns.computeIfAbsent(arenaName, k -> new ArrayList<>()).removeIf(x -> sameBlock(x, loc));
        joinSigns.computeIfAbsent(arenaName, k -> new ArrayList<>()).add(loc);
        refreshJoinSigns(arenaName);
        msg(p, "&a已将告示牌变为 &e" + arenaName + "&a 的加入牌，右键点击即可加入。");
        return true;
    }

    /** 该位置是否为加入告示牌。 */
    public boolean isJoinSign(Location loc) {
        for (List<Location> locs : joinSigns.values()) {
            for (Location l : locs) {
                if (sameBlock(l, loc)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 点击加入告示牌 → 直接加入对应场。 */
    public void onSignClick(Player p, Location loc) {
        for (Map.Entry<String, List<Location>> e : joinSigns.entrySet()) {
            for (Location l : e.getValue()) {
                if (sameBlock(l, loc)) {
                    join(p, e.getKey());
                    return;
                }
            }
        }
    }

    /** 刷新某场所有加入告示牌内容。 */
    public void refreshJoinSigns(String arenaName) {
        List<Location> locs = joinSigns.get(arenaName);
        if (locs == null) {
            return;
        }
        TntRunArena a = arenas.get(arenaName);
        int waiting = a == null ? 0 : a.getPlayers().size();
        boolean running = a != null && a.getState() == TntRunArena.State.RUNNING;
        for (Location loc : locs) {
            if (!(loc.getBlock().getState() instanceof Sign sign)) {
                continue;
            }
            sign.line(0, Chat.color("&a[加入] &e" + arenaName));
            sign.line(1, Chat.color("&7右键点击加入"));
            sign.line(2, Chat.color(running ? "&c游戏中" : "&7等待：" + waiting + "人"));
            sign.line(3, Chat.color("&7雪球砸人/挖雪得球"));
            sign.update();
        }
    }

    private String shortLoc(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
    }

    private boolean sameBlock(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        return a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    // ---------- 辅助 ----------

    private void resetPlayer(Player p) {
        if (p == null || !p.isOnline()) {
            return;
        }
        p.getInventory().clear();
        p.setHealth(20);
        p.setFoodLevel(20);
    }

    private void broadcast(TntRunArena a, String msg) {
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                msg(p, msg);
            }
        }
        for (UUID id : a.getEliminated()) {
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
}
