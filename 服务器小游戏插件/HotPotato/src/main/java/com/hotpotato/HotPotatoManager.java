package com.hotpotato;

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
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 烫手山芋核心：排队、回合、TNT 传递、爆炸淘汰、前三展示。 */
public class HotPotatoManager {

    private final HotPotatoPlugin plugin;
    private final LiveSidebar liveSidebar;
    private final Map<String, HotPotatoArena> arenas = new LinkedHashMap<>();

    /** D22：每个场地一个会话（多图游戏）。 */
    private final Map<String, HotPotatoSession> sessions = new LinkedHashMap<>();

    /** 玩家 → 房间键；房间键 → 场地名；每房间一份运行时场地实例（D22）。 */
    private final Map<UUID, String> playerKey = new LinkedHashMap<>();
    private final Map<String, String> keyArena = new LinkedHashMap<>();
    private final Map<String, HotPotatoArena> liveArenas = new LinkedHashMap<>();

    public String keyOf(UUID id) {
        String k = playerKey.get(id);
        if (k != null) {
            return k;
        }
        String name = playerArena.get(id);
        return name == null ? null : name;
    }

    public String arenaNameOfKey(String key) {
        return keyArena.getOrDefault(key, key);
    }

    /** 该房间的运行时场地实例；首次使用时从配置模板克隆（只抄坐标）。 */
    public HotPotatoArena liveArena(String key) {
 if (key == null) {
     // **静默 null 是最难查的 bug**：join() 里 key 还没算出来就调 liveArena
     // 会得到 null → 上层"场不存在"→ 命令返回 true、控制台无日志。
     throw new IllegalStateException("HotPotato: liveArena(null) —— 调用点没有先算房间键");
 }
        HotPotatoArena live = liveArenas.get(key);
        if (live != null) {
            return live;
        }
        HotPotatoArena tpl = arenas.get(arenaNameOfKey(key));
        if (tpl == null) {
            return null;
        }
        HotPotatoArena copy = tpl.copyConfig();
        copy.setRoomKey(key);
        liveArenas.put(key, copy);
        return copy;
    }

    /** 只按场地名找（无玩家上下文的只读路径；多房间时模糊）。 */
    private HotPotatoArena arenaByName(String arenaName) {
        for (HotPotatoArena live : liveArenas.values()) {
            if (arenaName.equals(live.getName())) {
                return live;
            }
        }
        return arenas.get(arenaName);
    }

    private HotPotatoSession ensureSession(String key) {
        HotPotatoSession s = sessions.get(key);
        if (s == null || s.state().isFinal()) {
            s = new HotPotatoSession(this, arenaNameOfKey(key));
            sessions.put(key, s);
        }
        return s;
    }
    private final Map<UUID, String> playerArena = new LinkedHashMap<>();
    private final Map<String, BukkitTask> roundTasks = new LinkedHashMap<>();
    private final Map<String, BukkitTask> glowTasks = new LinkedHashMap<>(); // 场名 -> 持弹者红色高亮粒子任务
    private BukkitTask boundsTask; // 出圈轮询兜底任务
    private final Map<String, WaitCountdown> waits = new LinkedHashMap<>(); // 场名 -> 等待倒计时
    private final Map<String, Boolean> worldPvpSaved = new LinkedHashMap<>(); // 世界名 -> 开赛前的 PvP 状态
    private final Map<String, Integer> worldRunning = new LinkedHashMap<>();   // 世界名 -> 进行中的对局数

    // 数据与配置模式
    private YamlConfiguration data;
    private final SetupMode setup; // 公共库：OP 配置模式（背包备份/工具/还原）
    private final Map<UUID, HotPotatoArena> setupTargets = new LinkedHashMap<>(); // 玩家 -> 正在配置的场（直接持对象，改名不失效）
    private final Map<String, List<Location>> joinSigns = new LinkedHashMap<>();
    private final Map<UUID, String> pendingSign = new LinkedHashMap<>();

    /** I3：会话里的搬迁代码要用到 plugin（原 Listener 的字段）。 */
    public HotPotatoPlugin plugin() {
        return plugin;
    }

    public HotPotatoManager(HotPotatoPlugin plugin) {
        this.plugin = plugin;
        this.liveSidebar = new LiveSidebar(plugin);
        this.setup = new SetupMode(plugin, "hp-setup-tool");
    }

    public void load() {
        startCountdown();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("arenas");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                arenas.put(name, HotPotatoArena.load(name, sec.getConfigurationSection(name)));
            }
        }
        // 战绩数据
        // 战绩数据（唯一文件 plugins/MiniGameLib/players.yml，命名空间 hotpotato）
        data = PlayerStats.load("hotpotato", new File(plugin.getDataFolder(), "data.yml"));
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
            for (HotPotatoArena a : arenas.values()) {
                reportArena(a);
            }
        }, 40L);
        // 出圈轮询兜底：不依赖 PlayerMoveEvent（玩家掉出世界后服务器端移动事件可能不再派发），
        // 定时检查所有对局中存活玩家的服务器位置，掉出即淘汰
        startBoundsCheck();
    }

    /** 每 0.5 秒检查所有进行中对局的存活玩家是否出圈，出圈直接淘汰。 */
    private void startBoundsCheck() {
        if (boundsTask != null) {
            return;
        }
        boundsTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (HotPotatoArena a : arenas.values()) {
                if (a.getState() != HotPotatoArena.State.RUNNING) {
                    continue;
                }
                for (UUID id : new ArrayList<>(a.getPlayers())) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null || !p.isOnline()) {
                        continue;
                    }
                    handleMove(p);
                }
            }
        }, 10L, 10L);
    }

    /**
     * 声明地盘（{@code docs/框架不变量.md} 的 I2）：把每个场地的边界报给框架。
     * 只在内存里读字段，不碰配置；还没建场的场地会被 {@code Regions} 静默跳过。
     */
    public void publishRegions() {
        com.hub.region.Regions.begin("hotpotato");
        arenas.forEach((name, a) ->
                com.hub.region.Regions.box("hotpotato", name, a.getPos1(), a.getPos2()));
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
        for (Map.Entry<String, HotPotatoArena> e : arenas.entrySet()) {
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
        reportAllArenas();
    }

    /**
     * 把当前所有场地重新上报给 HubCore 中央菜单。
     * <p>
     * 必须跟着"配置落盘"一起做：{@code /hp create}、{@code /hp pos1|pos2|center|lobby|save}、
     * 配置模式工具（木棍/金锄头/纸/绿宝石）、删除与改名都会走 {@link #saveAll()}。
     * 否则运行中新建成 / 刚圈好地的场对中央菜单根本不存在（{@code reportArena} 原来只在开服延迟上报、
     * 玩家进出场、开局/结束时调用），表现就是：主菜单点「烫手山芋」→ HubCore 查不到场地
     * （{@code arenasOf} 空表）→ 打开一个"一块羊毛都没有"的场地子菜单。
     */
    private void reportAllArenas() {
        if (!plugin.isEnabled()) {
            return; // 关服保存时不必上报（HubCore 可能已在卸载）
        }
        for (HotPotatoArena a : arenas.values()) {
            reportArena(a);
        }
    }

    private void saveData() {
        if (data == null) {
            return;
        }
        try {
            PlayerStats.save("hotpotato", data);
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("保存战绩失败：" + ex.getMessage());
        }
    }

    public HotPotatoArena getArena(String name) {
        return arenas.get(name);
    }

    public Map<String, HotPotatoArena> getArenas() {
        return arenas;
    }

    public boolean createArena(String name) {
        if (arenas.containsKey(name)) {
            return false;
        }
        arenas.put(name, new HotPotatoArena(name));
        return true;
    }

    /** 删除场（仅等待且无人时），连同其加入告示牌。 */
    public boolean removeArena(String name) {
        HotPotatoArena a = arenas.get(name);
        if (a == null) {
            return false;
        }
        if (a.getState() != HotPotatoArena.State.WAITING || !a.getPlayers().isEmpty() || !a.getEliminated().isEmpty()) {
            return false;
        }
        arenas.remove(name);
        // 丢弃该场的倒计时实例（实例持有场对象，同名重建时必须换新实例）
        WaitCountdown w = waits.remove(name);
        if (w != null) {
            w.cancel();
        }
        plugin.getConfig().set("arenas." + name, null);
        joinSigns.remove(name);
        plugin.getConfig().set("join-signs." + name, null);
        plugin.saveConfig();
        HubAPI.removeArena("烫手山芋", name);
        return true;
    }

    /** 场地改名：同步配置、告示牌、玩家/倒计时引用与中央菜单；对局中禁止。 */
    public boolean renameArena(String oldName, String newName) {
        if (oldName == null || oldName.equals(newName) || !validName(newName) || arenas.containsKey(newName)) {
            return false;
        }
        HotPotatoArena a = arenas.get(oldName);
        if (a == null || a.getState() == HotPotatoArena.State.RUNNING) {
            return false;
        }
        arenas.remove(oldName);
        a.setName(newName);
        arenas.put(newName, a);
        // 玩家 / 待绑定告示牌引用同步（配置模式直接持有场对象，改名天然跟着走）
        for (Map.Entry<UUID, String> e : playerArena.entrySet()) {
            if (e.getValue().equals(oldName)) {
                e.setValue(newName);
            }
        }
        for (Map.Entry<UUID, String> e : pendingSign.entrySet()) {
            if (e.getValue().equals(oldName)) {
                e.setValue(newName);
            }
        }
        // 倒计时实例按新名重挂（实例内 getName() 已返回新名，保证 update/cancel 找到同一场）
        WaitCountdown wt = waits.remove(oldName);
        if (wt != null) {
            waits.put(newName, wt);
        }
        // 加入告示牌：重挂并刷新文字
        List<Location> locs = joinSigns.remove(oldName);
        if (locs != null) {
            joinSigns.put(newName, locs);
            refreshJoinSigns(newName);
        }
        saveAll(); // 配置（场 + 加入告示牌）立即落盘
        HubAPI.removeArena("烫手山芋", oldName); // 菜单移除旧名
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
    private void reportArena(HotPotatoArena a) {
        boolean playing = a.getState() == HotPotatoArena.State.RUNNING;
        HubAPI.setArena("烫手山芋", a.getName(),
                playing ? GameState.PLAYING : GameState.WAITING, a.getPlayers().size(), null);
        HubAPI.setArenaSpectator("烫手山芋", a.getName(), a.getCenter());
    }

    // ---------- 玩家会话 ----------

    public String arenaOf(UUID id) {
        return playerArena.get(id);
    }

    /** 玩家加入。 */
    public boolean join(Player p, String arenaName) {
        HotPotatoArena a = arenas.get(arenaName);
        if (a == null) {
            msg(p, "&c场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        UUID id = p.getUniqueId();
        if (playerArena.containsKey(id)) {
            msg(p, "&c你已在其他场。");
            return false;
        }
        if (a.getState() == HotPotatoArena.State.RUNNING) {
            msg(p, "&c该场正在游戏中。");
            return false;
        }
        if (!a.isReady()) {
            msg(p, "&c该场未配置完成。");
            return false;
        }
        int max = plugin.getConfig().getInt("max-players", 8);
        if (a.getPlayers().size() >= max) {
            msg(p, "&c人数已满（&e" + max + "&c）。");
            return false;
        }
        // D22：键 = 房间 id（有房间时）；本局状态落在"本房间的"运行时场地实例上
        String key = com.hub.room.RoomContext.roomKeyFor(p, "hotpotato", arenaName);
        keyArena.put(key, arenaName);
        HotPotatoArena live = liveArena(key);
        if (live != null) {
            a = live;
        }
        if (a.getState() == HotPotatoArena.State.RUNNING) {
            msg(p, "&c该场正在游戏中。");
            return false;
        }
        // I1（D23）：先挂会话再入队；冲突直接拒绝，避免半加入状态
        HotPotatoSession sess = ensureSession(key);
        if (!sess.join(p)) {
            // 刚建出来的空会话不能留着 —— 否则 doctor 里会多出一个"0 人的等待中场地"，
            // 看起来像场地被占了，其实谁也没进去
            if (sess.players().isEmpty()) {
                sessions.remove(key);
            }
            return false;
        }
        playerArena.put(id, arenaName);
        playerKey.put(id, key);
        a.getPlayers().add(id);
        a.recordJoin(id);   // D21：记加入 tick + 随机名次
        p.getInventory().clear();
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(20);
        p.setFoodLevel(20);
        Location lobby = a.getLobbyPoint() != null ? a.getLobbyPoint() : a.getCenter();
        p.teleport(lobby);
        HubAPI.setGame(id, "烫手山芋");
        giveLeaveBed(p);
        if (p.isOp()) {
            giveStartPaper(p);
        }
        msg(p, "&a你已加入 &e" + arenaName + "&a，等待开始…");
        broadcast(a, "&e" + p.getName() + "&f 加入了（&e" + a.getPlayers().size() + "&f 人）");
        com.hub.api.HubAPI.announceJoin(plugin.getName(), p.getName()); // 全服公屏播报
        updateWaitCountdown(a);
        reportArena(a);
        return true;
    }

    /** 玩家主动离开。 */
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
        // I1：从该场地的会话上摘掉自己（漏了会让玩家卡在"已在等待中"）
        if (name != null) {
            HotPotatoSession sess = sessions.get(name);
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
        HotPotatoArena a = liveArena(keyOf(p.getUniqueId()));
        if (a != null) {
            a.getPlayers().remove(id);
            a.getEliminated().remove(id);
            a.getBombHolders().remove(id);
            clearBombVisual(p);
            HubAPI.clearGame(id);
            broadcast(a, "&e" + p.getName() + "&f 离开了（剩 &e" + a.getPlayers().size() + "&f 人）");
            if (a.getState() == HotPotatoArena.State.RUNNING && a.getPlayers().size() <= 1) {
                endGame(a, a.getPlayers().isEmpty() ? null : a.getPlayers().get(0));
            }
            reportArena(a);
        }
        p.setGameMode(GameMode.SURVIVAL);
        HubAPI.teleportToLobby(p); // 回主城并拿菜单道具
        msg(p, "&7你已离开。");
        if (a != null && a.getState() == HotPotatoArena.State.WAITING) {
            updateWaitCountdown(a);
        }
    }

    // ---------- 游戏流程 ----------

    /** OP 开始游戏。 */
    private StartCountdown startCountdown;

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

    public boolean start(Player op, String arenaName) {
        HotPotatoArena a = liveArena(keyOf(op.getUniqueId()));
        if (a == null) {
            msg(op, "&c场不存在。");
            return false;
        }
        int min = plugin.getConfig().getInt("min-players", 2);
        if (a.getPlayers().size() < min) {
            msg(op, "&c人数不足（至少 &e" + min + "&c 人，当前 &e" + a.getPlayers().size() + "&c）。");
            return false;
        }
        if (startCountdown().begin(arenaName, new ArrayList<>(a.getPlayers()), () -> start(op, arenaName))) {
            return true;
        }
        a.setState(HotPotatoArena.State.RUNNING);
        plugin.getLogger().info("游戏开始：" + arenaName + "（" + a.getPlayers().size() + " 人）");
        reportArena(a);
        enableWorldPvp(a);
        cancelWait(a);
        a.getEliminated().clear();
        a.getBombHolders().clear();
        a.getPlayers().forEach(id -> {
            HubAPI.setPlaying(id, "烫手山芋"); // 游戏开始：tab 从 [等待中] 变为 [已加入]
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.getInventory().clear();
                p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
                clearTntNameTag(p); // 开局清一下上一局可能残留的红名
                p.setGameMode(GameMode.SURVIVAL);
                p.setHealth(20);
                p.setFoodLevel(20);
                p.teleport(a.getCenter());
            }
        });
        startRound(a, true);
        return true;
    }

    /** 开始新回合：分配 TNT。firstRound=true 时 Speed II。 */
    private void startRound(HotPotatoArena a, boolean firstRound) {
        a.getBombHolders().clear();
        int alive = a.getPlayers().size();
        int twoThreshold = plugin.getConfig().getInt("two-bomb-threshold", 5);
        int bombCount = alive >= twoThreshold ? 2 : 1;
        List<UUID> pool = new ArrayList<>(a.getPlayers());
        Collections.shuffle(pool);
        for (int i = 0; i < Math.min(bombCount, pool.size()); i++) {
            a.getBombHolders().add(pool.get(i));
        }
        for (UUID id : a.getBombHolders()) {
            giveBomb(Bukkit.getPlayer(id), firstRound);
        }
        // 回合时长随存活人数动态调整：>=8人 40s，4~7人 30s，<4人 20s
        int seconds = alive >= 8 ? 40 : alive >= 4 ? 30 : 20;
        a.setRoundEndMs(System.currentTimeMillis() + seconds * 1000L);
        broadcast(a, "&6&l— 第 " + (a.getEliminated().size() + 1) + " 回合开始 —");
        plugin.getLogger().info("回合开始：" + a.getName() + "，TNT 持有者 " + bombCount + " 人：" + a.getBombHolders());
        StringBuilder names = new StringBuilder();
        for (UUID id : a.getBombHolders()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                if (names.length() > 0) {
                    names.append("&f、");
                }
                names.append("&e").append(p.getName());
                msg(p, "&c你拿到了 TNT！拿着 TNT 打别人才能传出去！");
            }
        }
        if (names.length() > 0) {
            broadcast(a, "&cTNT 在 " + names + " &c身上！");
        }
        // 倒计时任务
        BukkitTask old = roundTasks.remove(a.roomKey());
        if (old != null) {
            old.cancel();
        }
        roundTasks.put(a.roomKey(), Bukkit.getScheduler().runTaskTimer(plugin, () -> tickRound(a), 20L, 20L));
        // 红色高亮：每 1/4 秒在持弹者身上铺一层红色粉尘（不用 GLOWING：它的描边只有白色）
        BukkitTask oldGlow = glowTasks.remove(a.roomKey());
        if (oldGlow != null) {
            oldGlow.cancel();
        }
        glowTasks.put(a.roomKey(), Bukkit.getScheduler().runTaskTimer(plugin, () -> glowTick(a), 10L, 5L));
    }

    /** 持弹者红色粒子壳（原版 GLOWING 描边是白色的，红色只能靠粒子）。 */
    private void glowTick(HotPotatoArena a) {
        if (a.getState() != HotPotatoArena.State.RUNNING) {
            return; // 回合结束/游戏结束由 cancelTask 收任务，这里只兜底不刷粒子
        }
        Particle.DustOptions red = new Particle.DustOptions(Color.RED, 1.2f);
        for (UUID id : a.getBombHolders()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || p.isDead()) {
                continue;
            }
            // 躯干 + 头肩 + 腿，铺成一层"红色描边"（没有原版白描边了，全靠这些粒子）
            p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1.0, 0), 20, 0.32, 0.7, 0.32, 0, red);
            p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 2.05, 0), 10, 0.34, 0.13, 0.34, 0, red);
            p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 0.35, 0), 8, 0.26, 0.15, 0.26, 0, red);
            p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 0.2, 0), 2, 0.25, 0.05, 0.25, 0.005);
        }
    }

    private void tickRound(HotPotatoArena a) {
        if (a.getState() != HotPotatoArena.State.RUNNING) {
            cancelTask(a);
            return;
        }
        long left = (a.getRoundEndMs() - System.currentTimeMillis()) / 1000;
        if (left <= 0) {
            explodeEndRound(a);
            return;
        }
        // actionBar 显示剩余时间 + 谁拿着 TNT（持有者发光高亮，隔着墙也能看见）
        String holderNames = bombHolderNames(a);
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.actionBar(p, a.isBombHolder(id)
                        ? "&c&lTNT！&f 剩余 &e" + left + "s &f— 拿 TNT 打人才能传出去！"
                        : "&cTNT 在 &e" + holderNames + " &c身上（红色高亮）！&7剩余 &e" + left + "s");
            }
        }
        updateLiveBoard(a);
    }

    private void updateLiveBoard(HotPotatoArena a) {
        List<UUID> online = new ArrayList<>(a.getPlayers());
        online.sort(Comparator.comparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        lines.add(new LiveSidebar.Line("§7存活 §f" + a.getPlayers().size(), 1));
        for (UUID id : online) {
            lines.add(new LiveSidebar.Line("§e" + nameOf(id), 0));
        }
        liveSidebar.update("hotpotato:" + a.getName(), "&6&l烫手山芋 · 存活", online, lines);
    }

    /** 逗号拼接的持弹者名字，用于公屏/动作栏高亮提示。 */
    private String bombHolderNames(HotPotatoArena a) {
        StringBuilder sb = new StringBuilder();
        for (UUID id : a.getBombHolders()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("&7, &e");
            }
            sb.append(p.getName());
        }
        return sb.length() > 0 ? sb.toString() : "&7（无）&e";
    }

    /** 回合结束：持有者原地爆炸淘汰。 */
    private void explodeEndRound(HotPotatoArena a) {
        cancelTask(a);
        List<UUID> holders = new ArrayList<>(a.getBombHolders());
        a.getBombHolders().clear();
        for (UUID id : holders) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                continue;
            }
            // 爆炸视觉（不破坏地形）
            p.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, p.getLocation(), 2, 0, 0, 0, 0);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
            eliminateOne(a, p, "&c" + p.getName() + " &f被炸淘汰了！", null);
        }
        afterEliminate(a);
    }

    /** 游戏结束：前三名展示。 */
    /** 收场入口（I4）：先让该场地的会话 end()，再由 onEnd 回到 doEndGame。 */
    private void endGame(HotPotatoArena a, UUID winner) {
        pendingWinner = winner;
        HotPotatoSession sess = sessions.get(a.getName());
        if (sess == null || sess.state().isFinal()) {
            doEndGame(a.getName());
            return;
        }
        sess.end(EndReason.ROUND_END);
    }

    /** 收场参数暂存（玩家看到的结算不能变）。 */
    private UUID pendingWinner;

    /** 真正的收场逻辑（由 session.onEnd 回调）。 */
    void doEndGame(String arenaName) {
        HotPotatoArena a = arenaByName(arenaName);
        if (a == null) {
            return;
        }
        UUID winner = pendingWinner;
        cancelTask(a);
        liveSidebar.hide("hotpotato:" + a.getName());
        a.setState(HotPotatoArena.State.WAITING);
        restoreWorldPvp(a);
        reportArena(a);
        broadcast(a, "&6&l========== 游戏结束 ==========");
        // 前三名：winner + elimOrder 最后 2 个
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
        broadcast(a, "&6===============================");
        ArcadeBridge.report("hotpotato", firsts, seconds, thirds);
        Top3.award(data, firsts, seconds, thirds);
        saveData();
        // 恢复所有玩家：自动回主城并拿菜单道具
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

    /** 玩家被淘汰后也可能在 eliminated 列表里（spectator），退出服务器需清理。 */
    public void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        String name = playerArena.remove(id);
        // 无论是否在场，退出都恢复生存模式，防止观战模式残留到下次加入导致"无敌"
        p.setGameMode(GameMode.SURVIVAL);
        if (name == null) {
            return;
        }
        HotPotatoArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null) {
            return;
        }
        a.getPlayers().remove(id);
        a.getEliminated().remove(id);
        a.getBombHolders().remove(id);
        clearBombVisual(p); // 拿过 TNT 的人掉线也要清掉发光/速度，否则重连还亮着
        HubAPI.clearGame(id);
        reportArena(a);
        if (a.getState() == HotPotatoArena.State.RUNNING && a.getPlayers().size() <= 1) {
            endGame(a, a.getPlayers().isEmpty() ? null : a.getPlayers().get(0));
        }
    }

    /** 玩家加入服务器：不在任何场时恢复生存模式并送回主城（防止上一局淘汰/掉线后 NBT 残留观战模式或对局世界导致"无敌/打不了"）。 */
    public void handleJoin(Player p) {
        if (p == null || !p.isOnline()) {
            return;
        }
        String name = playerArena.get(p.getUniqueId());
        if (name != null) {
            return;
        }
        if (p.getGameMode() == GameMode.SPECTATOR) {
            p.setGameMode(GameMode.SURVIVAL);
            plugin.getLogger().info("玩家 " + p.getName() + " 加入时处于观战模式，已恢复为生存模式。");
        }
        // 不在任何场但停留在烫手山芋场地世界：送回主城（否则玩家"在场地上但不在对局里"，攻击无任何效果）
        Set<String> hpWorlds = new HashSet<>();
        for (HotPotatoArena a : arenas.values()) {
            if (a.getCenter() != null && a.getCenter().getWorld() != null) {
                hpWorlds.add(a.getCenter().getWorld().getName());
            }
            if (a.getPos1() != null && a.getPos1().getWorld() != null) {
                hpWorlds.add(a.getPos1().getWorld().getName());
            }
            if (a.getPos2() != null && a.getPos2().getWorld() != null) {
                hpWorlds.add(a.getPos2().getWorld().getName());
            }
        }
        if (hpWorlds.contains(p.getWorld().getName())) {
            HubAPI.teleportToLobby(p);
            plugin.getLogger().info("玩家 " + p.getName() + " 不在场但位于对局世界，已送回主城。");
        }
    }

    // ---------- 等待倒计时 ----------

    /** 退出床。 */
    public void giveLeaveBed(Player p) {
        ItemStack bed = new ItemStack(Material.RED_BED);
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
        ItemStack paper = new ItemStack(Material.PAPER);
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
        HotPotatoArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || a.getState() != HotPotatoArena.State.WAITING) {
            return;
        }
        if (!p.isOp() && !p.hasPermission("hp.admin")) {
            msg(p, "&c只有管理员（OP 或 hp.admin）可以开始。");
            return;
        }
        start(p, arenaName);
    }

    /** 根据当前排队人数决定是否启动倒计时（公共库 WaitCountdown 负责全部规则）。 */
    private void updateWaitCountdown(HotPotatoArena a) {
        wait(a).update();
    }

    /** 该场的等待倒计时实例：一场一个，懒创建。 */
    public int waitingCountOf(String arenaName) {
        HotPotatoArena a = arenaByName(arenaName);
        return a == null ? 0 : a.getPlayers().size();
    }

    public int minPlayersOf(String arenaName) {
        return plugin.getConfig().getInt("min-players", 2);
    }

    public boolean isHostOf(Player p, String arenaName) {
        if (!"host".equalsIgnoreCase(plugin.getConfig().getString("flow", "upstream"))) {
            return false;
        }
        HotPotatoArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || p == null) {
            return false;
        }
        UUID host = wait(a).currentHost();
        return host != null && host.equals(p.getUniqueId());
    }

    private WaitCountdown wait(HotPotatoArena a) {
        return waits.computeIfAbsent(a.roomKey(), n -> new WaitCountdown(plugin, new WaitCountdown.Host() {
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
                return plugin.getConfig().getInt("max-players", 8);
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

                HotPotatoSession s2 = sessions.get(a.getName());

                if (s2 != null && hp != null && !s2.state().isFinal()) {

                    s2.leave(hp);

                }

                if (hp != null && hp.isOnline()) {

                    msg(hp, "&c倒计时结束还没开始，你被移出本局（主持人超时）。");

                    com.hub.api.HubAPI.teleportToLobby(hp);

                }

                plugin.getLogger().info("[HotPotato] 场 " + a.getName() + "：主持人 " + hname

                        + " 超时未开始，已移出（剩余 " + a.getPlayers().size() + " 人）");

                broadcast(a, "&e主持人 &f" + hname + " &e超时未开始，已移出本局。");

                updateWaitCountdown(a);

            }


            @Override
            public void onStart() {
                // 内部开局方法（无权限校验）；命令/开始纸那一层已经校验过权限
                Player any = a.getPlayers().isEmpty() ? null : Bukkit.getPlayer(a.getPlayers().get(0));
                if (any != null) {
                    start(any, a.getName());
                }
            }
        }));
    }

    /** 取消该场的自动倒计时（没有实例时什么都不做）。 */
    private void cancelWait(HotPotatoArena a) {
        WaitCountdown w = waits.get(a.getName());
        if (w != null) {
            w.cancel();
        }
    }

    // ---------- 对局期间强制开启 PvP ----------

    /** 开赛前：若该世界原本 PvP 关闭，则临时开启（仅在对局期间生效）。 */
    private void enableWorldPvp(HotPotatoArena a) {
        if (a.getCenter() == null || a.getCenter().getWorld() == null) {
            plugin.getLogger().warning("无法开启 PvP：" + a.getName() + " 中央点或世界为空！");
            return;
        }
        String worldName = a.getCenter().getWorld().getName();
        int count = worldRunning.getOrDefault(worldName, 0) + 1;
        worldRunning.put(worldName, count);
        if (count == 1) {
            boolean prev = a.getCenter().getWorld().getPVP();
            worldPvpSaved.put(worldName, prev);
            a.getCenter().getWorld().setPVP(true);
            plugin.getLogger().info("对局开始：世界 " + worldName + " PvP 已临时开启（原 " + prev + "）");
        } else {
            plugin.getLogger().info("世界 " + worldName + " 已有 " + count + " 场对局，PvP 保持开启");
        }
    }

    /** 对局结束：该世界没有其他进行中的对局时，恢复原本的 PvP 状态。 */
    private void restoreWorldPvp(HotPotatoArena a) {
        if (a.getCenter() == null || a.getCenter().getWorld() == null) {
            return;
        }
        String worldName = a.getCenter().getWorld().getName();
        int count = worldRunning.getOrDefault(worldName, 0) - 1;
        if (count <= 0) {
            worldRunning.remove(worldName);
            Boolean prev = worldPvpSaved.remove(worldName);
            if (prev != null) {
                a.getCenter().getWorld().setPVP(prev);
                plugin.getLogger().info("对局结束：世界 " + worldName + " PvP 已恢复为 " + prev);
            }
        } else {
            worldRunning.put(worldName, count);
        }
    }

    /**
     * 关服兜底：把临时开启的 PvP 全部还原。
     * worldPvpSaved 只在内存里，对局中关服/热重载会让世界永久保持 PvP=true。
     */
    public void restoreAllWorldPvp() {
        for (Map.Entry<String, Boolean> e : worldPvpSaved.entrySet()) {
            World w = Bukkit.getWorld(e.getKey());
            if (w != null) {
                w.setPVP(e.getValue());
                plugin.getLogger().info("关服：世界 " + e.getKey() + " PvP 已恢复为 " + e.getValue());
            }
        }
        worldPvpSaved.clear();
        worldRunning.clear();
    }

    // ---------- TNT 传递 ----------

    /** 手里真的拿着 TNT（主手）才算"用 TNT 打人"，光有持有者标记不算。 */
    public boolean isHoldingTnt(Player p) {
        if (p == null) {
            return false;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        return hand != null && hand.getType() == Material.TNT;
    }

    /** 持有者用 TNT 攻击别人 → 传递。没有拿着 TNT 打人无效。 */
    public void handleAttack(Player damager, Player victim) {
        UUID d = damager.getUniqueId();
        UUID v = victim.getUniqueId();
        String arenaName = playerArena.get(d);
        if (arenaName == null) {
            return;
        }
        HotPotatoArena a = liveArena(keyOf(damager.getUniqueId()));
        if (a == null || a.getState() != HotPotatoArena.State.RUNNING) {
            return;
        }
        if (!a.isBombHolder(d)) {
            return;
        }
        if (!isHoldingTnt(damager)) {
            return; // 手里没有 TNT：打人无效，不传递
        }
        if (d.equals(v)) {
            return;
        }
        if (!a.getPlayers().contains(v)) {
            return; // 旁观者/淘汰者不接
        }
        if (a.isBombHolder(v)) {
            return; // 对方已在持有 TNT，避免把 TNT 甩给持弹者导致数量减少
        }
        // 传递
        giveBomb(victim, false);
        clearBombVisual(damager);
        a.getBombHolders().remove(d);
        a.getBombHolders().add(v);
        broadcast(a, "&e" + damager.getName() + "&f 把 TNT 传给了 &e" + victim.getName() + "&f！");
        msg(victim, "&cTNT 传到你身上了！快传给别人！");
    }

    /** 给玩家 TNT：主手物品栏 + 头盔，速度效果。firstRound=true → Speed II。先清掉身上所有残留 TNT，保证持有者 TNT 唯一。 */
    private void giveBomb(Player p, boolean firstRound) {
        if (p == null || !p.isOnline()) {
            return;
        }
        clearBombVisual(p);
        p.getInventory().setItemInMainHand(new ItemStack(Material.TNT));
        p.getInventory().setHelmet(new ItemStack(Material.TNT));
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        int amplifier = firstRound ? 1 : 0; // Speed II / Speed I
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, amplifier, false, false, true));
        // 高亮：头顶名字变红（队伍只当"红名标记"用，不设 prefix，避免和 HubCore 的 tab 前缀打架）
        applyTntNameTag(p);
        // 不做原版 GLOWING：它的轮廓线是客户端硬编码白色，红色只能靠 glowTick 的红色粒子壳
    }

    /** 清掉玩家身上所有 TNT（背包全部格子 + 头盔），防止玩家把 TNT 移到背包其他格后残留导致"拿着 TNT 却不是持有者"。 */
    private void clearBombVisual(Player p) {
        if (p == null || !p.isOnline()) {
            return;
        }
        // 按槽位显式清除（Inventory.forEach 传的 ItemStack 是副本，改副本不会生效，必须 setItem 回写）
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack item = p.getInventory().getItem(i);
            if (item != null && item.getType() == Material.TNT) {
                p.getInventory().setItem(i, null);
            }
        }
        if (p.getInventory().getHelmet() != null && p.getInventory().getHelmet().getType() == Material.TNT) {
            p.getInventory().setHelmet(null);
        }
        p.removePotionEffect(PotionEffectType.SPEED);
        p.removePotionEffect(PotionEffectType.GLOWING); // 兜底：清掉历史版本可能留下的白描边
        clearTntNameTag(p);
    }

    // ---------- 持弹者红名（记分板队伍）----------

    /** 只当"红名标记"用：不设 prefix/suffix，也不碰 tab 的 playerListName，避免和 HubCore 的 [已加入·烫手山芋] 打架。 */
    private static final String TNT_TEAM = "hp_tnt";

    /** 持弹者头顶名字变红。 */
    private void applyTntNameTag(Player p) {
        if (p == null || !p.isOnline()) {
            return;
        }
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = sb.getTeam(TNT_TEAM);
        if (team == null) {
            team = sb.registerNewTeam(TNT_TEAM);
        }
        team.color(NamedTextColor.RED);
        team.addEntry(p.getName());
    }

    /** 去掉红名（不在此队伍里则无操作）。 */
    private void clearTntNameTag(Player p) {
        if (p == null) {
            return;
        }
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(TNT_TEAM);
        if (team == null) {
            return;
        }
        team.removeEntry(p.getName());
        if (team.getEntries().isEmpty()) {
            team.unregister();
        }
    }

    private void resetPlayer(Player p) {
        if (p == null || !p.isOnline()) {
            return;
        }
        p.getInventory().clear();
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        p.setHealth(20);
        p.setFoodLevel(20);
    }

    /** 离开圈定游戏区域 → 直接淘汰，并在公屏（全服）播报。 */
    public void handleMove(Player p) {
        String name = playerArena.get(p.getUniqueId());
        if (name == null) {
            return;
        }
        HotPotatoArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || a.getState() != HotPotatoArena.State.RUNNING) {
            return;
        }
        if (!a.getPlayers().contains(p.getUniqueId())) {
            return;
        }
        Location loc = p.getLocation();
        boolean inside = a.contains(loc);
        // 垂直兜底三保险：只要玩家明显低于游戏区域，无论 X/Z 范围怎么圈都视为出圈淘汰
        // 1) 低于场地中央点 1 格（中央点一般=平台表面，掉出平台即淘汰；留 1 格缓冲避免误伤正常站立）
        if (inside && a.getCenter() != null && loc.getY() < a.getCenter().getY() - 1) {
            inside = false;
        }
        // 2) 低于 pos1/pos2 中较低那个 1 格（覆盖中央点设得过低的情况）
        if (inside) {
            Location p1 = a.getPos1();
            Location p2 = a.getPos2();
            double floorY = Double.MAX_VALUE;
            if (p1 != null) {
                floorY = Math.min(floorY, p1.getY());
            }
            if (p2 != null) {
                floorY = Math.min(floorY, p2.getY());
            }
            if (floorY != Double.MAX_VALUE && loc.getY() < floorY - 1) {
                inside = false;
            }
        }
        // 3) 掉进虚空（世界最低可建造高度附近）必定淘汰，防止玩家一路掉到底
        if (inside && loc.getY() <= loc.getWorld().getMinHeight() + 1) {
            inside = false;
        }
        if (inside) {
            return;
        }
        plugin.getLogger().info("出圈淘汰：" + p.getName() + "（" + name + "，位置 " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + "）");
        // 直接淘汰：变观战 + 公屏播报
        eliminateOne(a, p, null, "&c" + p.getName() + " &f掉出了这个世界！");
        p.teleport(a.getCenter()); // 观战位置拉回场地中央，方便观战
        afterEliminate(a);
    }

    /** 将一名存活玩家淘汰为观战者（只处理单名玩家，不推进回合流程，由调用方统一 afterEliminate）。 */
    private void eliminateOne(HotPotatoArena a, Player p, String arenaMsg, String serverMsg) {
        UUID id = p.getUniqueId();
        a.getPlayers().remove(id);
        a.getEliminated().add(id);
        a.getElimOrder().add(id);
        p.playHurtAnimation(0); // 淘汰红屏反馈
        clearBombVisual(p);
        p.setGameMode(GameMode.SPECTATOR);
        HubAPI.clearGame(id);
        if (arenaMsg != null) {
            broadcast(a, arenaMsg);
        }
        if (serverMsg != null) {
            broadcast(a, serverMsg); // 只发给本场玩家（含已被淘汰的观战者）
        }
    }

    /** 淘汰后的统一收尾：上报状态，判定胜利或开启下一回合。 */
    private void afterEliminate(HotPotatoArena a) {
        reportArena(a);
        if (a.getPlayers().size() <= 1) {
            endGame(a, a.getPlayers().isEmpty() ? null : a.getPlayers().get(0));
            return;
        }
        startRound(a, false);
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

    // ---------- 配置模式（道具快速配置，公共库 SetupMode 负责背包/工具/还原） ----------

    /** 配置工具回调：按工具 id 更新场地字段，与原来 handleSetupTool 的行为一致。 */
    private final SetupMode.Handler setupHandler = new SetupMode.Handler() {

        @Override
        public void onTool(Player p, String arenaName, String toolId, Action action, Block clicked) {
            HotPotatoArena a = setupTargets.get(p.getUniqueId());
            if (a == null) {
                a = arenas.get(arenaName);
            }
            if (a == null) {
                // 场已被删除：直接退出配置模式（与原逻辑一致）
                exitSetup(p);
                return;
            }
            String name = a.getName();
            switch (toolId) {
                case "pos" -> {
                    if (action == Action.LEFT_CLICK_BLOCK && clicked != null) {
                        a.setPos1(clicked.getLocation());
                        msg(p, "&a已设置 &e" + name + "&a 范围角1：&f" + shortLoc(clicked.getLocation())
                                + (a.getPos2() != null ? "（范围已定）" : "（接着右键定范围角2）"));
                        saveAll(); // 配置改动立即落盘，防止重启丢失
                    } else if (action == Action.RIGHT_CLICK_BLOCK && clicked != null) {
                        a.setPos2(clicked.getLocation());
                        msg(p, "&a已设置 &e" + name + "&a 范围角2：&f" + shortLoc(clicked.getLocation())
                                + (a.getPos1() != null ? "（范围已定）" : "（还差范围角1）"));
                        saveAll();
                    }
                }
                case "center" -> {
                    if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                        a.setCenter(p.getLocation());
                        msg(p, "&a已设置 &e" + name + "&a 中央点：&f" + shortLoc(p.getLocation()));
                        saveAll();
                    }
                }
                case "lobby" -> {
                    if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                        a.setLobbyPoint(p.getLocation());
                        msg(p, "&a已设置 &e" + name + "&a 等待点：&f" + shortLoc(p.getLocation()));
                        saveAll();
                    }
                }
                default -> {
                    // save / exit 由公共库自己处理
                }
            }
        }

        @Override
        public void onSave(Player p, String arenaName) {
            HotPotatoArena a = setupTargets.get(p.getUniqueId());
            saveAll();
            msg(p, "&a已保存场 &e" + (a != null ? a.getName() : arenaName) + "&a。");
        }
    };

    /** 本插件的配置工具（顺序 = 快捷栏槽位 0~4）。 */
    private List<SetupMode.Tool> setupTools() {
        return List.of(
                SetupMode.Tool.both(Material.STICK, "pos", "&b范围工具", "&7左键=范围角1&8/&7右键=范围角2"),
                SetupMode.Tool.right(Material.GOLDEN_HOE, "center", "&e中央点工具", "&7右键=中央点（当前位置）"),
                SetupMode.Tool.right(Material.PAPER, "lobby", "&f等待点工具", "&7右键=等待点（当前位置）"),
                SetupMode.Tool.right(Material.EMERALD, "save", "&6保存场", "&7右键=保存当前配置"),
                SetupMode.Tool.right(Material.BARRIER, "exit", "&c退出配置模式", "&7右键=结束配置并恢复背包"));
    }

    public boolean isInSetup(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null && !setup.isEditing(p)) {
            setupTargets.remove(id); // 玩家用屏障工具退出（公共库内部处理），清理镜像
        }
        return setupTargets.containsKey(id);
    }

    public HotPotatoArena getSetupArena(UUID id) {
        return setupTargets.get(id);
    }

    /** OP 进入某场的配置模式：清背包并发配置工具。 */
    public boolean enterSetup(Player p, String arenaName) {
        HotPotatoArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null) {
            msg(p, "&c场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        if (setup.isEditing(p)) {
            msg(p, "&c你已在配置模式中，先退出（屏障工具）再进入其他场。");
            return false;
        }
        setupTargets.put(p.getUniqueId(), a);
        setup.enter(p, arenaName, setupTools(), setupHandler);
        msg(p, "&a已进入 &e" + arenaName + "&a 的配置模式：");
        msg(p, "&b木棍&7 左键=范围角1 右键=范围角2");
        msg(p, "&e金锄头&7 右键=中央点  &f纸&7 右键=等待点");
        msg(p, "&6绿宝石&7 保存  &c屏障&7 退出");
        return true;
    }

    /** 退出配置模式并恢复背包。 */
    public void exitSetup(Player p) {
        setupTargets.remove(p.getUniqueId());
        setup.exit(p);
        msg(p, "&7已退出配置模式，背包已恢复。");
    }

    /** 配置工具交互处理。返回是否被本插件消费（公共库 SetupMode 分发）。 */
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
        saveAll(); // 告示牌绑定立即落盘，防止重启丢失
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
        HotPotatoArena a = arenaByName(arenaName);
        int waiting = a == null ? 0 : a.getPlayers().size();
        boolean running = a != null && a.getState() == HotPotatoArena.State.RUNNING;
        for (Location loc : locs) {
            if (!(loc.getBlock().getState() instanceof Sign sign)) {
                continue;
            }
            sign.line(0, Chat.color("&a[加入] &e" + arenaName));
            sign.line(1, Chat.color("&7右键点击加入"));
            sign.line(2, Chat.color(running ? "&c游戏中" : "&7等待：" + waiting + "人"));
            sign.line(3, Chat.color("&7战绩：/hp stats"));
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

    private void cancelTask(HotPotatoArena a) {
        BukkitTask t = roundTasks.remove(a.roomKey());
        if (t != null) {
            t.cancel();
        }
        BukkitTask g = glowTasks.remove(a.roomKey());
        if (g != null) {
            g.cancel();
        }
    }

    private void broadcast(HotPotatoArena a, String msg) {
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
