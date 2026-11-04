package com.football;

import com.hub.session.EndReason;

import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.data.PlayerStats;
import com.hub.util.Chat;
import com.hub.util.LiveSidebar;
import com.hub.util.StartCountdown;
import com.hub.util.SetupMode;
import com.hub.util.WaitCountdown;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
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
import org.bukkit.util.Vector;

import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 绿茵足球核心：排队选队、开赛分配、开球/踢球/进球/出界、计时与金球、战绩。
 * <p>
 * 阶段流转：WAITING（等待选队）→ KICKOFF（球冻在开球点、开球方拿力度靴）→ PLAY（自由比赛）
 * → 进球 GOAL_PAUSE（3 秒定格）→ KICKOFF（失球方开球、全员回出生点）→ … → 结算回主城。
 * 出界不开 GOAL_PAUSE，也不重置站位：球放在出界点（水平夹回场内 1 格）、由"最后触球队"的对方开球，
 * 并随机传送一名开球队员到场外球旁（面朝球，踢球方向自然朝场内）；开球期间动作栏每秒倒计时，超时自动开出。
 */
public class FootballManager {

    private static final String GAME_NAME = "绿茵足球";
    private static final long GOAL_PAUSE_MS = 3000L;
    private static final int KICKOFF_TIMEOUT_S = 10;

    private final FootballPlugin plugin;
    private final LiveSidebar liveSidebar;
    private int boardTick;
    private final Map<String, FootballArena> arenas = new LinkedHashMap<>();

    /** D22：每个场地一个会话（多图游戏）。 */
    private final Map<String, FootballSession> sessions = new LinkedHashMap<>();
    private FootballArena.Team pendingWinner;
    private boolean pendingHasWinner;

    private FootballSession ensureSession(String arenaName) {
        FootballSession s = sessions.get(arenaName);
        if (s == null || s.state().isFinal()) {
            s = new FootballSession(this, arenaName);
            sessions.put(arenaName, s);
        }
        return s;
    }
    private final Map<UUID, String> playerArena = new LinkedHashMap<>();
    private final Map<String, FootballBall> balls = new LinkedHashMap<>();      // 场名 -> 球
    private final Map<String, BukkitTask> pauseTasks = new LinkedHashMap<>();   // 场名 -> 进球定格任务
    private final Map<String, Long> kickoffDeadline = new LinkedHashMap<>();    // 场名 -> 开球超时时刻
    private final Map<UUID, Long> sprintCooldown = new LinkedHashMap<>();       // 玩家 -> 冲刺冷却到点
    private final Map<UUID, Long> jumpCooldown = new LinkedHashMap<>();
    private BukkitTask ballTask; // 球物理 + 进球/出界判定 + 动作栏（全局一个，轮询所有对局）

    private YamlConfiguration data;
    private final FootballItems items;
    private final SetupMode setup;
    private final Map<UUID, FootballArena> setupTargets = new LinkedHashMap<>();
    private final Map<String, List<Location>> joinSigns = new LinkedHashMap<>();
    private final Map<UUID, String> pendingSign = new LinkedHashMap<>();
    private final Map<String, WaitCountdown> waits = new LinkedHashMap<>();
    /** I3：会话里的搬迁代码要用到 plugin。 */
    public FootballPlugin plugin() {
        return plugin;
    }


    public FootballManager(FootballPlugin plugin) {
        this.plugin = plugin;
        this.liveSidebar = new LiveSidebar(plugin);
        this.items = new FootballItems(plugin);
        this.setup = new SetupMode(plugin, "fb-setup-tool");
    }

    public FootballItems items() {
        return items;
    }

    public void load() {
        logFlowMode();
        startCountdown();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("arenas");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                arenas.put(name, FootballArena.load(name, sec.getConfigurationSection(name)));
            }
        }
        // 战绩（唯一文件 plugins/MiniGameLib/players.yml，命名空间 football；新插件无旧 data.yml）
        data = PlayerStats.load("football", new File(plugin.getDataFolder(), "data.yml"));
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
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (FootballArena a : arenas.values()) {
                reportArena(a);
            }
        }, 40L);
        startBallTask();
    }

    /** 关服兜底：清掉所有场的展示实体球（球实体 setPersistent(false) 不落盘，这里防热重载残留）。 */
    public void cleanupBalls() {
        for (FootballBall ball : balls.values()) {
            ball.remove();
        }
        balls.clear();
    }

    /**
     * 声明地盘（{@code docs/框架不变量.md} 的 I2）：把每个场地的边界报给框架。
     * 只在内存里读字段，不碰配置；还没建场的场地会被 {@code Regions} 静默跳过。
     */
    public void publishRegions() {
        com.hub.region.Regions.begin("football");
        arenas.forEach((name, a) ->
                com.hub.region.Regions.box("football", name, a.getPos1(), a.getPos2()));
    }

    public void saveAll() {
                    // **未就绪的场地跳过回写**：世界缺失/没加载成功时，内存里的坐标是空的，
            // 写回去等于把磁盘上的场地坐标抹掉（真实事故：删了 world_tr 后 TntRun 的场地被清空）。
            // 房间只替换"世界"，坐标仍以这里为准，所以绝不能丢。
publishRegions();   // 场地可能刚被改过，重新声明（I2）
        ConfigurationSection sec = com.hub.world.Worlds.getOrCreate(plugin.getConfig(), "arenas");
        for (Map.Entry<String, FootballArena> e : arenas.entrySet()) {
            if (!e.getValue().isReady()) {
                plugin.getLogger().warning("[保存] 场地 " + e.getKey()
                        + " 未就绪（世界缺失？），跳过回写以免丢坐标");
                continue;
            }
            e.getValue().save(com.hub.world.Worlds.getOrCreate(sec, e.getKey()));
        }
        ConfigurationSection js = com.hub.world.Worlds.getOrCreate(plugin.getConfig(), "join-signs");
        for (Map.Entry<String, List<Location>> e : joinSigns.entrySet()) {
            js.set(e.getKey(), e.getValue());
        }
        plugin.saveConfig();
        saveData();
        reportAllArenas();
    }

    private void reportAllArenas() {
        if (!plugin.isEnabled()) {
            return;
        }
        for (FootballArena a : arenas.values()) {
            reportArena(a);
        }
    }

    private void saveData() {
        if (data == null) {
            return;
        }
        try {
            PlayerStats.save("football", data);
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("保存战绩失败：" + ex.getMessage());
        }
    }

    public FootballArena getArena(String name) {
        return arenas.get(name);
    }

    public Map<String, FootballArena> getArenas() {
        return arenas;
    }

    public boolean createArena(String name) {
        if (arenas.containsKey(name)) {
            return false;
        }
        arenas.put(name, new FootballArena(name));
        return true;
    }

    public boolean removeArena(String name) {
        FootballArena a = arenas.get(name);
        if (a == null) {
            return false;
        }
        if (a.getState() != FootballArena.State.WAITING || !a.getPlayers().isEmpty()) {
            return false;
        }
        arenas.remove(name);
        WaitCountdown w = waits.remove(name);
        if (w != null) {
            w.cancel();
        }
        FootballBall ball = balls.remove(name);
        if (ball != null) {
            ball.remove();
        }
        plugin.getConfig().set("arenas." + name, null);
        joinSigns.remove(name);
        plugin.getConfig().set("join-signs." + name, null);
        plugin.saveConfig();
        HubAPI.removeArena(GAME_NAME, name);
        return true;
    }

    public boolean renameArena(String oldName, String newName) {
        if (oldName == null || oldName.equals(newName) || !validName(newName) || arenas.containsKey(newName)) {
            return false;
        }
        FootballArena a = arenas.get(oldName);
        if (a == null || a.getState() == FootballArena.State.RUNNING) {
            return false;
        }
        arenas.remove(oldName);
        a.setName(newName);
        arenas.put(newName, a);
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
        WaitCountdown wt = waits.remove(oldName);
        if (wt != null) {
            waits.put(newName, wt);
        }
        FootballBall ball = balls.remove(oldName);
        if (ball != null) {
            balls.put(newName, ball);
        }
        List<Location> locs = joinSigns.remove(oldName);
        if (locs != null) {
            joinSigns.put(newName, locs);
            refreshJoinSigns(newName);
        }
        saveAll();
        HubAPI.removeArena(GAME_NAME, oldName);
        reportArena(a);
        return true;
    }

    /** 场地名校验：1~20 字符，仅中英文/数字/下划线/短横线（与其它游戏一致）。 */
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

    private void reportArena(FootballArena a) {
        boolean playing = a.getState() == FootballArena.State.RUNNING;
        String extra = playing ? "比分 " + a.getScoreRed() + ":" + a.getScoreBlue() : null;
        HubAPI.setArena(GAME_NAME, a.getName(),
                playing ? GameState.PLAYING : GameState.WAITING, a.getPlayers().size(), extra);
        HubAPI.setArenaSpectator(GAME_NAME, a.getName(), a.getCenter());
    }

    // ---------- 玩家会话 ----------

    public String arenaOf(UUID id) {
        return playerArena.get(id);
    }

    public boolean join(Player p, String arenaName) {
        FootballArena a = arenas.get(arenaName);
        if (a == null) {
            msg(p, "&c场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        UUID id = p.getUniqueId();
        if (playerArena.containsKey(id)) {
            msg(p, "&c你已在其他场。");
            return false;
        }
        if (a.getState() == FootballArena.State.RUNNING) {
            msg(p, "&c该场正在游戏中。");
            return false;
        }
        if (!a.isReady()) {
            msg(p, "&c该场未配置完成，还缺：" + String.join("、", a.missingPoints()));
            return false;
        }
        int max = plugin.getConfig().getInt("max-players", 10);
        if (a.getPlayers().size() >= max) {
            msg(p, "&c人数已满（&e" + max + "&c）。");
            return false;
        }
        playerArena.put(id, arenaName);
        FootballSession sess = ensureSession(arenaName);
        if (!sess.join(p)) {
            if (sess.players().isEmpty()) {
                sessions.remove(arenaName);
            }
            return false;
        }
        a.getPlayers().add(id);
        a.recordJoin(id);   // D21：记加入 tick + 随机名次（同 tick 随机选主持人）
        p.getInventory().clear();
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        Location lobby = a.getLobbyPoint() != null ? a.getLobbyPoint() : a.getCenter();
        p.teleport(lobby);
        HubAPI.setGame(id, GAME_NAME);
        items.giveWaitingItems(p, p.isOp() || p.hasPermission("fb.admin"));
        msg(p, "&a你已加入 &e" + arenaName + "&a，右键 &c红队旗 &f/ &9蓝队旗 &f选队，开赛后自动分到人少的一队。");
        broadcast(a, "&e" + p.getName() + "&f 加入了（&e" + a.getPlayers().size() + "&f 人，红 "
                + a.getTeamRed().size() + "/蓝 " + a.getTeamBlue().size() + "）");
        HubAPI.announceJoin(plugin.getName(), p.getName(), a.getName());
        wait(a).update();
        reportArena(a);
        return true;
    }

    public void leave(Player p) {
        leave(p, false);
    }

    /** silent=true 用于主城回主城回调（不在场中时别刷提示，见 docs/开发须知.md 第 5 节）。 */
    public void leave(Player p, boolean silent) {
        UUID id = p.getUniqueId();
        String name = playerArena.remove(id);
        // I1：从该场地的会话上摘掉自己（漏了会让玩家卡在"已在等待中"）
        if (name != null) {
            FootballSession sess = sessions.get(name);
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
        FootballArena a = arenas.get(name);
        if (a != null) {
            a.getPlayers().remove(id);
            a.removeFromTeams(id);
            clearTeamColor(p);
            sprintCooldown.remove(id);
            jumpCooldown.remove(id);
            HubAPI.clearGame(id);
            broadcast(a, "&e" + p.getName() + "&f 离开了（剩 &e" + a.getPlayers().size() + "&f 人）");
            if (a.getState() == FootballArena.State.RUNNING) {
                if (a.getTeamRed().isEmpty() || a.getTeamBlue().isEmpty()) {
                    // 一队走光：另一队直接获胜
                    FootballArena.Team winner = a.getTeamRed().isEmpty()
                            ? FootballArena.Team.BLUE : FootballArena.Team.RED;
                    endMatch(a, winner);
                } else {
                    reportArena(a);
                }
            } else {
                reportArena(a);
                wait(a).update();
            }
        }
        p.setGameMode(GameMode.SURVIVAL);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        HubAPI.teleportToLobby(p);
        msg(p, "&7你已离开。");
    }

    /** 等待阶段选队（旗帜）。开赛后不可再选。 */
    public void pickTeam(Player p, FootballArena.Team team) {
        String name = playerArena.get(p.getUniqueId());
        if (name == null) {
            return;
        }
        FootballArena a = arenas.get(name);
        if (a == null || a.getState() != FootballArena.State.WAITING) {
            return;
        }
        a.assignTeam(p.getUniqueId(), team);
        HubAPI.setTabNameColor(p.getUniqueId(), teamColorCode(team));
        msg(p, "&a你选择了 " + team.display() + "&a（当前 红 " + a.getTeamRed().size()
                + " : 蓝 " + a.getTeamBlue().size() + "&a），开赛时人数不平衡会自动调剂。");
    }

    // ---------- 开赛 ----------

    public boolean start(Player op, String arenaName) {
        FootballArena a = arenas.get(arenaName);
        if (a == null) {
            msg(op, "&c场不存在。");
            return false;
        }
        int min = plugin.getConfig().getInt("min-players", 2);
        if (a.getPlayers().size() < min) {
            msg(op, "&c人数不足（至少 &e" + min + "&c 人，当前 &e" + a.getPlayers().size() + "&c）。");
            return false;
        }
        startMatch(a);
        return true;
    }

    private StartCountdown startCountdown;

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

    private void startMatch(FootballArena a) {
        if (a.getState() != FootballArena.State.RUNNING
                && startCountdown().begin(a.getName(), new ArrayList<>(a.getPlayers()), () -> startMatch(a))) {
            return;
        }
        a.setState(FootballArena.State.RUNNING);
        FootballSession started = sessions.get(a.getName());
        if (started != null && !started.state().isFinal()) {
            started.start();
        }
        a.resetMatch();
        cancelWait(a);
        balanceTeams(a);
        reportArena(a);
        plugin.getLogger().info("比赛开始：" + a.getName() + "（红 " + a.getTeamRed().size()
                + " vs 蓝 " + a.getTeamBlue().size() + "）");
        int seconds = plugin.getConfig().getInt("match-seconds", 300);
        a.setMatchTotalSeconds(seconds);
        a.setMatchEndMs(System.currentTimeMillis() + seconds * 1000L);
        for (UUID id : a.getPlayers()) {
            HubAPI.setPlaying(id, GAME_NAME);
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.getInventory().clear();
                p.setGameMode(GameMode.SURVIVAL);
                p.setHealth(20);
                p.setFoodLevel(20);
                p.setFallDistance(0);
            }
        }
        broadcast(a, "&6&l========== 比赛开始 ==========");
        broadcast(a, "&c红队 " + a.getTeamRed().size() + " 人 &fVS &9蓝队 " + a.getTeamBlue().size() + " 人"
                + "&7（先到 " + plugin.getConfig().getInt("goals-to-win", 3)
                + " 球或 " + seconds / 60 + " 分钟）");
        // 随机先开球的一方
        a.setKickoffTeam(java.util.concurrent.ThreadLocalRandom.current().nextBoolean()
                ? FootballArena.Team.RED : FootballArena.Team.BLUE);
        spawnBall(a, a.getCenter());
        equipAll(a);
        resetPositions(a);
        startKickoff(a, true);
    }

    /**
     * 开赛分配：已选队的保留；没选的进人少的一队；一边多出超过 1 人时把"后加入大队"的人调剂到小队
     * （保证两边人数差 ≤ 1，即每边不超过总人数的一半向上取整）。
     */
    private void balanceTeams(FootballArena a) {
        List<UUID> unassigned = new ArrayList<>();
        for (UUID id : a.getPlayers()) {
            if (a.teamOf(id) == null) {
                unassigned.add(id);
            }
        }
        for (UUID id : unassigned) {
            FootballArena.Team to = a.getTeamRed().size() <= a.getTeamBlue().size()
                    ? FootballArena.Team.RED : FootballArena.Team.BLUE;
            a.assignTeam(id, to);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                msg(p, "&7你被自动分到了 " + to.display() + "&7。");
            }
        }
        while (Math.abs(a.getTeamRed().size() - a.getTeamBlue().size()) > 1) {
            FootballArena.Team big = a.getTeamRed().size() > a.getTeamBlue().size()
                    ? FootballArena.Team.RED : FootballArena.Team.BLUE;
            List<UUID> bigTeam = a.teamOf(big);
            UUID moved = bigTeam.get(bigTeam.size() - 1);
            a.assignTeam(moved, big.other());
            Player p = Bukkit.getPlayer(moved);
            if (p != null) {
                msg(p, "&e为了平衡人数，你被调剂到了 " + big.other().display() + "&e。");
            }
            broadcast(a, "&7人数平衡：" + big.display() + "&7 的 &e"
                    + nameOf(moved) + "&7 调剂到 " + big.other().display());
        }
        // 队色：记分板队伍染头顶名 + HubAPI.setTabNameColor 染 tab 名字（只染色不设前缀，不碰 HubCore 的 tab 前缀）
        for (UUID id : a.getTeamRed()) {
            applyTeamColor(Bukkit.getPlayer(id), NamedTextColor.RED);
        }
        for (UUID id : a.getTeamBlue()) {
            applyTeamColor(Bukkit.getPlayer(id), NamedTextColor.BLUE);
        }
    }

    /** 全员发对局装备（按队伍颜色）。 */
    private void equipAll(FootballArena a) {
        for (UUID id : a.getTeamRed()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                items.giveMatchKit(p, FootballArena.Team.RED);
            }
        }
        for (UUID id : a.getTeamBlue()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                items.giveMatchKit(p, FootballArena.Team.BLUE);
            }
        }
    }

    /** 全员传回本队出生点（进球后的重新开球用）。 */
    private void resetPositions(FootballArena a) {
        for (UUID id : a.getTeamRed()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && a.getSpawnRed() != null) {
                p.setFallDistance(0);
                p.teleport(a.getSpawnRed());
            }
        }
        for (UUID id : a.getTeamBlue()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && a.getSpawnBlue() != null) {
                p.setFallDistance(0);
                p.teleport(a.getSpawnBlue());
            }
        }
    }

    /** 在指定点生成球（读 ball 配置段：材质/大小；材质必须是方块，BlockDisplay 用）。 */
    private void spawnBall(FootballArena a, Location at) {
        Material material = Material.matchMaterial(plugin.getConfig().getString("ball.material", "WHITE_CONCRETE"));
        if (material == null || !material.isBlock()) {
            material = Material.WHITE_CONCRETE;
        }
        double scale = plugin.getConfig().getDouble("ball.scale", 0.9);
        FootballBall ball = balls.computeIfAbsent(a.getName(), n -> new FootballBall(plugin));
        ball.spawn(at, material, scale);
    }

    // ---------- 开球 ----------

    /**
     * 开始一次开球。resetPositions=true（开局/进球后）：全员回出生点；
     * false（出界后）：原地不动，球放在出界点。at=null 时球回中央开球点。
     */
    private void startKickoff(FootballArena a, boolean resetPositions) {
        startKickoff(a, resetPositions, null);
    }

    /** at != null：出界开球——球冻在出界点，并随机传送一名开球队员到场外球旁（面朝球往场内开）。 */
    private void startKickoff(FootballArena a, boolean resetPositions, Location at) {
        cancelPause(a);
        a.setPhase(FootballArena.Phase.KICKOFF);
        a.setLastTouch(null, null);
        FootballBall ball = balls.get(a.getName());
        Location spot = at != null ? at : a.getCenter();
        if (ball != null && spot != null) {
            ball.freeze(spot);
            ball.nudgeToGround();
        }
        if (resetPositions) {
            resetPositions(a);
        }
        // 开球方全队拿力度靴（力度 1~8），对方保持普通装备但踢球无效
        FootballArena.Team kt = a.getKickoffTeam();
        for (UUID id : a.teamOf(kt)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                items.giveKickoffBoots(p);
                msg(p, kt.display() + "&a 开球：&f选一只开球靴，&a左键&a把球往对方半场开！力度越大越高越远。");
            }
        }
        Player chosen = at != null ? teleportRandomKicker(a, kt, spot) : null;
        String where = at != null ? "出界点" : "中央";
        String who = chosen != null ? "&8（已随机传送 &f" + chosen.getName() + "&8 到场外球旁）" : "";
        broadcast(a, kt.display() + "&e 开球！球在" + where + who + "，等待 "
                + KICKOFF_TIMEOUT_S + " 秒内不开自动开出。");
        kickoffDeadline.put(a.getName(), System.currentTimeMillis() + KICKOFF_TIMEOUT_S * 1000L);
    }

    /** 出界开球：随机挑一名在线的开球队员传到球旁的界外一侧（面朝球，人→球的踢球方向自然朝场内），返回被选中者。 */
    private Player teleportRandomKicker(FootballArena a, FootballArena.Team kt, Location spot) {
        List<Player> candidates = new ArrayList<>();
        for (UUID id : a.teamOf(kt)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty() || spot == null || spot.getWorld() == null) {
            return null;
        }
        Player p = candidates.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size()));
        // 真实边线球站位：人在场外、面向球，往场内开。方向 = 球 -> 界外（远离场地中心）
        Vector outward = a.getCenter() != null && spot.getWorld().equals(a.getCenter().getWorld())
                ? spot.toVector().subtract(a.getCenter().toVector()).setY(0)
                : new Vector(1, 0, 0);
        if (outward.lengthSquared() < 0.001) {
            outward = new Vector(1, 0, 0);
        }
        outward.normalize();
        // 从 2 格起往界外试站位（脚+头都不是实体方块才算能站），被墙/网挡住就再退一格；都不行就硬放 2 格处
        Location to = null;
        for (double d = 2.0; d <= 4.0; d += 1.0) {
            Location tryLoc = spot.clone().add(outward.clone().multiply(d));
            tryLoc.setY(spot.getY());
            if (!tryLoc.getBlock().getType().isSolid()
                    && !tryLoc.clone().add(0, 1, 0).getBlock().getType().isSolid()) {
                to = tryLoc;
                break;
            }
        }
        if (to == null) {
            to = spot.clone().add(outward.multiply(2.0));
            to.setY(spot.getY());
        }
        Vector face = spot.toVector().subtract(to.toVector());
        face.setY(0);
        if (face.lengthSquared() > 0.001) {
            to.setDirection(face.normalize());
        }
        p.setFallDistance(0);
        p.teleport(to);
        msg(p, "&a你被随机选中开球——你站在场外球旁，面向球往场内开！");
        return p;
    }

    /** 开球被踢出（或超时自动开出）后的收尾：换回普通装备、阶段切 PLAY。 */
    private void releaseKickoff(FootballArena a) {
        if (a.getPhase() != FootballArena.Phase.KICKOFF) {
            return;
        }
        a.setPhase(FootballArena.Phase.PLAY);
        kickoffDeadline.remove(a.getName());
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                items.clearKickoffBoots(p);
                items.giveMatchKit(p, a.teamOf(id));
            }
        }
    }

    // ---------- 踢球 ----------

    /**
     * 玩家左键挥动拿着球鞋踢球。kind=道具种类；kickoffPower 仅开球靴有意义。
     * 阶段规则：KICKOFF 只接受开球方的开球靴；PLAY 只接受击球/挑高靴（开球靴已被收回）。
     */
    public void handleKick(Player p, FootballItems.Kind kind, int kickoffPower) {
        String name = playerArena.get(p.getUniqueId());
        if (name == null) {
            return;
        }
        FootballArena a = arenas.get(name);
        if (a == null || a.getState() != FootballArena.State.RUNNING) {
            return;
        }
        FootballBall ball = balls.get(name);
        if (ball == null || !ball.isAlive()) {
            return;
        }
        FootballArena.Team team = a.teamOf(p.getUniqueId());
        if (team == null) {
            return;
        }
        FootballArena.Phase phase = a.getPhase();
        double range = plugin.getConfig().getDouble("kick.range", 3.0);
        double dist = ball.distanceTo(p.getLocation().add(0, 1.0, 0));
        if (dist > range) {
            return; // 挥空：没够到球，不作任何提示（否则左键空气刷屏）
        }
        Vector dir = ball.location().toVector().subtract(p.getLocation().toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 0.0001) {
            dir = p.getLocation().getDirection().setY(0);
        }
        dir.normalize();

        switch (kind) {
            case KICKOFF -> {
                if (phase != FootballArena.Phase.KICKOFF) {
                    return;
                }
                if (team != a.getKickoffTeam()) {
                    msg(p, "&c现在由 " + a.getKickoffTeam().display() + "&c 开球，先防守一下！");
                    return;
                }
                double min = plugin.getConfig().getDouble("kick.kickoff-min-power", 0.6);
                double max = plugin.getConfig().getDouble("kick.kickoff-max-power", 2.2);
                double ratio = plugin.getConfig().getDouble("kick.kickoff-lift-ratio", 0.45);
                // 力度沿用旧 8 档刻度（除以 7）：只发 1~4 档，但 1~4 的数值与旧版完全一致；
                // 不能按 1~4 直接铺满 min~max，否则力度 4 = 2.2 又高又陡，球近乎垂直飞
                int level = Math.max(1, Math.min(FootballItems.KICKOFF_MAX_LEVEL, kickoffPower));
                double power = min + (max - min) * (level - 1) / 7.0;
                Vector v = dir.clone().multiply(power);
                v.setY(power * ratio);
                ball.setVelocity(v);
                a.setLastTouch(p.getUniqueId(), team);
                releaseKickoff(a);
                kickEffects(p, ball);
                broadcast(a, team.display() + "&e 的 &f" + p.getName() + "&e 开出球（力度 &f" + kickoffPower + "&e）！比赛开始！");
            }
            case KICK_FORWARD -> {
                if (phase != FootballArena.Phase.PLAY) {
                    return;
                }
                double power = plugin.getConfig().getDouble("kick.forward-power", 1.0);
                double lift = plugin.getConfig().getDouble("kick.forward-lift", 0.42);
                Vector v = dir.clone().multiply(power);
                v.setY(lift); // 半人高的抛物线：往前为主、带一点升力
                ball.setVelocity(v);
                a.setLastTouch(p.getUniqueId(), team);
                kickEffects(p, ball);
            }
            case KICK_VERTICAL -> {
                if (phase != FootballArena.Phase.PLAY) {
                    return;
                }
                double power = plugin.getConfig().getDouble("kick.vertical-power", 0.95);
                Vector v = new Vector(dir.getX() * 0.15, power, dir.getZ() * 0.15); // 垂直为主，保留一点点方向
                ball.setVelocity(v);
                a.setLastTouch(p.getUniqueId(), team);
                kickEffects(p, ball);
            }
            default -> {
            }
        }
    }

    /** 踢球反馈：挥手动作用 + 音效 + 球上粒子。 */
    private void kickEffects(Player p, FootballBall ball) {
        p.swingMainHand();
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.3f);
        Location l = ball.location();
        if (l != null) {
            l.getWorld().spawnParticle(Particle.CRIT, l, 8, 0.2, 0.2, 0.2, 0.1);
        }
    }

    // ---------- 冲刺 / 高跳 ----------

    public void useSprint(Player p) {
        String name = playerArena.get(p.getUniqueId());
        if (name == null) {
            return;
        }
        FootballArena a = arenas.get(name);
        if (a == null || a.getState() != FootballArena.State.RUNNING) {
            return;
        }
        long now = System.currentTimeMillis();
        long until = sprintCooldown.getOrDefault(p.getUniqueId(), 0L);
        if (now < until) {
            Chat.actionBar(p, "&7冲刺冷却中：" + ((until - now) / 1000 + 1) + "s");
            return;
        }
        int seconds = plugin.getConfig().getInt("sprint-seconds", 5);
        int cooldown = plugin.getConfig().getInt("sprint-cooldown", 5);
        p.removePotionEffect(PotionEffectType.SPEED);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, seconds * 20, 0, false, true, true));
        sprintCooldown.put(p.getUniqueId(), now + (seconds + cooldown) * 1000L);
        p.playSound(p.getLocation(), Sound.ENTITY_RABBIT_JUMP, 0.8f, 1.4f);
        Chat.actionBar(p, "&b冲刺！" + seconds + " 秒速度提升");
    }

    public void useJump(Player p) {
        String name = playerArena.get(p.getUniqueId());
        if (name == null) {
            return;
        }
        FootballArena a = arenas.get(name);
        if (a == null || a.getState() != FootballArena.State.RUNNING) {
            return;
        }
        long now = System.currentTimeMillis();
        long until = jumpCooldown.getOrDefault(p.getUniqueId(), 0L);
        if (now < until) {
            Chat.actionBar(p, "&7高跳冷却中：" + ((until - now) / 1000 + 1) + "s");
            return;
        }
        int cooldown = plugin.getConfig().getInt("jump-cooldown", 5);
        Vector v = p.getVelocity();
        v.setY(0.95);
        p.setVelocity(v);
        p.setFallDistance(0); // 落地不掉血（对局本身也免伤，这里双保险）
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 12, 0.25, 0.05, 0.25, 0.02);
        jumpCooldown.put(p.getUniqueId(), now + cooldown * 1000L);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.7f, 1.6f);
        Chat.actionBar(p, "&d高跳！");
    }

    // ---------- 球物理 + 判定（全局任务） ----------

    private void startBallTask() {
        if (ballTask != null) {
            return;
        }
        ballTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 4L, 1L);
    }

    private void tickAll() {
        updateLiveBoards();
        for (FootballArena a : arenas.values()) {
            if (a.getState() != FootballArena.State.RUNNING) {
                continue;
            }
            FootballBall ball = balls.get(a.getName());
            if (ball == null || !ball.isAlive()) {
                continue;
            }
            // 球员贴身带球：距离过近就把球水平推开（慢速时才推，避免打断真正的射门）
            if (a.getPhase() == FootballArena.Phase.PLAY && !ball.isMoving()) {
                pushPlayersNear(a, ball);
            }
            // 物理：每 2 tick 推进一步
            if (plugin.getConfig().getBoolean("ball.enabled-physics", true)) {
                if (Bukkit.getCurrentTick() % 2 == 0) {
                    ball.step(2,
                            plugin.getConfig().getDouble("ball.gravity", 0.08),
                            plugin.getConfig().getDouble("ball.drag", 0.985),
                            plugin.getConfig().getDouble("ball.bounce", 0.6),
                            plugin.getConfig().getDouble("ball.ground-friction", 0.86),
                            plugin.getConfig().getDouble("ball.rest-speed", 0.05));
                }
            }
            if (a.getPhase() == FootballArena.Phase.PLAY) {
                // 先判进球（球门可能贴着边线），再判出界
                FootballArena.Team goal = a.goalAt(ball.location());
                if (goal != null) {
                    handleGoal(a, goal);
                    continue;
                }
                if (isOutOfBounds(a, ball.location())) {
                    handleOut(a, ball.location());
                }
            }
            if (a.getPhase() == FootballArena.Phase.KICKOFF
                    && kickoffDeadline.containsKey(a.getName())
                    && System.currentTimeMillis() > kickoffDeadline.get(a.getName())) {
                autoKickoff(a);
            }
            if (Bukkit.getCurrentTick() % 20 == 0) {
                tickActionBar(a);
            }
        }
    }

    /** 玩家身体带球：贴身时把球往玩家面对的方向推。 */
    private void pushPlayersNear(FootballArena a, FootballBall ball) {
        double push = plugin.getConfig().getDouble("ball.push-speed", 0.32);
        double contact = ball.getRadius() + 0.9;
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || p.isDead()) {
                continue;
            }
            double dx = ball.location().getX() - p.getLocation().getX();
            double dz = ball.location().getZ() - p.getLocation().getZ();
            double dy = ball.location().getY() - (p.getLocation().getY() + 1.0);
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (horiz < contact && Math.abs(dy) < 1.6) {
                // 推开方向 = 玩家 -> 球；再混入玩家视线方向，走位就能带球
                Vector away = new Vector(dx, 0, dz);
                if (away.lengthSquared() < 0.001) {
                    away = p.getLocation().getDirection().setY(0);
                }
                away.normalize().multiply(push);
                away.setY(0);
                ball.setVelocity(away);
                a.setLastTouch(id, a.teamOf(id));
                break; // 一 tick 只响应一个人（最近距离者），防止两人同时抢推抖动
            }
        }
    }

    /** 球是否出界（含掉出世界/掉进场地下方的洞）。 */
    private boolean isOutOfBounds(FootballArena a, Location loc) {
        if (loc == null || a.getPos1() == null) {
            return false;
        }
        if (!a.contains(loc)) {
            return true;
        }
        return loc.getY() < a.getPos1().getWorld().getMinHeight() + 2;
    }

    /** 进球：side = 被进球的球门（红门被进 = 蓝队得分）。 */
    private void handleGoal(FootballArena a, FootballArena.Team side) {
        FootballArena.Team scorer = side.other();
        a.addScore(scorer);
        FootballBall ball = balls.get(a.getName());
        if (ball != null) {
            Location l = ball.location();
            if (l != null) {
                l.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, l, 1, 0, 0, 0, 0);
                l.getWorld().playSound(l, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1.6f);
            }
        }
        broadcast(a, "&6&l⚽ GOAL！ " + scorer.display() + "&e 攻破 " + side.display() + "&e 的球门！"
                + " 比分 &c" + a.getScoreRed() + " &f: &9" + a.getScoreBlue());
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.title(p, "&6&l⚽ GOAL!", scorer.display() + "&e 得分！&c" + a.getScoreRed()
                        + " &f: &9" + a.getScoreBlue(), 2);
            }
        }
        reportArena(a);
        // 胜负判定：金球模式先进球直接结束；常规模式达到目标球数结束
        if (a.isGoldenGoal()) {
            endMatch(a, scorer);
            return;
        }
        int goalsToWin = plugin.getConfig().getInt("goals-to-win", 3);
        if (a.getScoreRed() >= goalsToWin || a.getScoreBlue() >= goalsToWin) {
            endMatch(a, a.getScoreRed() > a.getScoreBlue() ? FootballArena.Team.RED : FootballArena.Team.BLUE);
            return;
        }
        // GOAL_PAUSE 定格几秒后重置站位、由失球方开球
        a.setPhase(FootballArena.Phase.GOAL_PAUSE);
        FootballBall b = balls.get(a.getName());
        if (b != null) {
            b.freeze(null);
        }
        a.setKickoffTeam(side); // 失球方开球
        BukkitTask old = pauseTasks.remove(a.getName());
        if (old != null) {
            old.cancel();
        }
        pauseTasks.put(a.getName(), Bukkit.getScheduler().runTaskLater(plugin,
                () -> startKickoff(a, true), GOAL_PAUSE_MS / 50L));
    }

    /** 球出界：由"最后触球队"的对方在出界点开球（没记录到触球则随机），不重置站位。 */
    private void handleOut(FootballArena a, Location at) {
        FootballArena.Team kt;
        if (a.getLastTouchTeam() != null) {
            kt = a.getLastTouchTeam().other();
        } else {
            kt = java.util.concurrent.ThreadLocalRandom.current().nextBoolean()
                    ? FootballArena.Team.RED : FootballArena.Team.BLUE;
        }
        a.setKickoffTeam(kt);
        if (at != null) {
            at.getWorld().playSound(at, Sound.BLOCK_GLASS_BREAK, 0.7f, 0.8f);
        }
        broadcast(a, "&7球出界了！" + kt.display() + "&e 获得开球权（球放在出界点，站位不动）。"
                + (a.getLastTouchTeam() != null ? "&8（最后触球：" + a.getLastTouchTeam().display() + "&8）" : ""));
        startKickoff(a, false, clampKickoffSpot(a, at));
    }

    /**
     * 把出界点夹回场内作为开球点：水平方向留 1 格边距（否则球还在界外，恢复 PLAY 立刻再判出界），
     * Y 夹在场高范围且留出球半径余量。算不出来（at/范围缺失）返回 null，由 startKickoff 回退到中央。
     */
    private Location clampKickoffSpot(FootballArena a, Location at) {
        if (at == null || a.getPos1() == null || a.getPos2() == null || a.getPos1().getWorld() == null) {
            return null;
        }
        double minX = Math.min(a.getPos1().getX(), a.getPos2().getX());
        double maxX = Math.max(a.getPos1().getX(), a.getPos2().getX());
        double minY = Math.min(a.getPos1().getY(), a.getPos2().getY());
        double maxY = Math.max(a.getPos1().getY(), a.getPos2().getY());
        double minZ = Math.min(a.getPos1().getZ(), a.getPos2().getZ());
        double maxZ = Math.max(a.getPos1().getZ(), a.getPos2().getZ());
        double margin = 1.0;
        double x = maxX - minX > margin * 2
                ? Math.max(minX + margin, Math.min(maxX - margin, at.getX()))
                : (minX + maxX) / 2;
        double z = maxZ - minZ > margin * 2
                ? Math.max(minZ + margin, Math.min(maxZ - margin, at.getZ()))
                : (minZ + maxZ) / 2;
        double y = Math.max(minY, Math.min(maxY - 0.5, at.getY()));
        return new Location(a.getPos1().getWorld(), x, y, z);
    }

    /** 开球超时：自动往对方半场开出中等力度。 */
    private void autoKickoff(FootballArena a) {
        FootballArena.Team kt = a.getKickoffTeam();
        FootballBall ball = balls.get(a.getName());
        if (ball == null || !ball.isAlive() || a.getCenter() == null) {
            releaseKickoff(a);
            return;
        }
        // 朝对方球门方向开（球现在可能在出界点，方向以球的当前位置为基准）
        Location goalCenter = goalCenterOf(a, kt.other());
        Location from = ball.location();
        Vector dir = goalCenter != null && from != null
                ? goalCenter.toVector().subtract(from.toVector()).setY(0)
                : new Vector(1, 0, 0);
        if (dir.lengthSquared() < 0.001) {
            dir = new Vector(1, 0, 0);
        }
        dir.normalize();
        Vector v = dir.clone().multiply(plugin.getConfig().getDouble("kick.kickoff-min-power", 0.6) + 0.4);
        v.setY(0.35);
        ball.setVelocity(v);
        releaseKickoff(a);
        broadcast(a, "&7超时未开球，自动开出！");
    }

    private Location goalCenterOf(FootballArena a, FootballArena.Team team) {
        Location p1 = team == FootballArena.Team.RED ? a.getGoalRedPos1() : a.getGoalBluePos1();
        Location p2 = team == FootballArena.Team.RED ? a.getGoalRedPos2() : a.getGoalBluePos2();
        if (p1 == null || p2 == null) {
            return null;
        }
        return new Location(p1.getWorld(),
                (p1.getX() + p2.getX()) / 2, (p1.getY() + p2.getY()) / 2, (p1.getZ() + p2.getZ()) / 2);
    }

    /** 每秒动作栏：比分 + 剩余时间（/金球倒计时）。 */
    private void tickActionBar(FootballArena a) {
        long leftMs = a.getMatchEndMs() - System.currentTimeMillis();
        long left = Math.max(0, leftMs / 1000);
        String score = "&c红 " + a.getScoreRed() + " &f: &9蓝 " + a.getScoreBlue();
        String timePart;
        if (a.isGoldenGoal()) {
            timePart = "&6&l金球加时 &7先进球获胜 &e剩 " + left + "s";
        } else {
            timePart = "&7剩余 &e" + (left / 60) + ":" + String.format("%02d", left % 60);
        }
        String phaseHint = switch (a.getPhase()) {
            case KICKOFF -> {
                // 开球超时倒数（动作栏每秒刷新，到 0 由 autoKickoff 自动开出）
                long deadline = kickoffDeadline.getOrDefault(a.getName(),
                        System.currentTimeMillis() + KICKOFF_TIMEOUT_S * 1000L);
                long kickLeft = Math.max(0, (deadline - System.currentTimeMillis() + 999) / 1000);
                yield "&e【开球倒计时 &f" + kickLeft + "s&e】";
            }
            case GOAL_PAUSE -> "&6【进球！】";
            default -> "";
        };
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.actionBar(p, score + " &8| " + timePart + " " + phaseHint);
            }
        }
        if (leftMs <= 0 && a.getPhase() == FootballArena.Phase.PLAY) {
            // 常规时间到：分出胜负 / 进入金球加时 / 平局
            if (a.isGoldenGoal()) {
                endMatch(a, null);
            } else if (a.leadingTeam() != null) {
                endMatch(a, a.leadingTeam());
            } else {
                int golden = plugin.getConfig().getInt("golden-goal-seconds", 120);
                a.setGoldenGoal(true);
                a.setMatchTotalSeconds(golden);
                a.setMatchEndMs(System.currentTimeMillis() + golden * 1000L);
                broadcast(a, "&6&l常规时间结束，比分相同！&e进入金球加时（" + golden / 60 + " 分钟，先进球获胜）");
            }
        }
    }

    // ---------- 结算 ----------

    private void updateLiveBoards() {
        if (++boardTick % 20 != 0) {
            return;
        }
        for (FootballArena a : arenas.values()) {
            if (a.getState() != FootballArena.State.RUNNING) {
                continue;
            }
            List<LiveSidebar.Line> lines = new ArrayList<>();
            lines.add(new LiveSidebar.Line("§c红队", a.getScoreRed()));
            lines.add(new LiveSidebar.Line("§9蓝队", a.getScoreBlue()));
            liveSidebar.update("football:" + a.getName(), "&a&l绿茵足球 · 比分", a.getPlayers(), lines);
        }
    }

    /** 收场入口（I4）：先让会话 end()，再由 onEnd 回到 doEndMatch。 */
    private void endMatch(FootballArena a, FootballArena.Team winner) {
        pendingWinner = winner;
        pendingHasWinner = winner != null;
        FootballSession sess = sessions.get(a.getName());
        if (sess == null || sess.state().isFinal()) {
            doEndMatch(a.getName());
            return;
        }
        sess.end(EndReason.ROUND_END);
    }

    /** 真正的收场逻辑。赢家透传，玩家看到的结算不变。 */
    void doEndMatch(String arenaName) {
        FootballArena a = arenas.get(arenaName);
        if (a == null) {
            return;
        }
        FootballArena.Team winner = pendingHasWinner ? pendingWinner : null;
        pendingWinner = null;
        pendingHasWinner = false;
        liveSidebar.hide("football:" + a.getName());
        cancelPause(a);
        kickoffDeadline.remove(a.getName());
        a.setState(FootballArena.State.WAITING);
        a.resetMatch();
        reportArena(a);
        FootballBall ball = balls.remove(a.getName());
        if (ball != null) {
            ball.remove();
        }
        broadcast(a, "&6&l========== 比赛结束 ==========");
        if (winner != null) {
            broadcast(a, winner.display() + " &e&l获胜！&7（终场比分 &c" + a.getScoreRed() + " &f: &9" + a.getScoreBlue() + "&7）");
            for (UUID id : new ArrayList<>(a.teamOf(winner))) {
                recordWin(id);
            }
        } else {
            broadcast(a, "&e平局！&7（终场比分 &c" + a.getScoreRed() + " &f: &9" + a.getScoreBlue() + "&7）");
        }
        broadcast(a, "&6==============================");
        for (UUID id : new ArrayList<>(a.getPlayers())) {
            Player p = Bukkit.getPlayer(id);
            clearTeamColor(p);
            sprintCooldown.remove(id);
            jumpCooldown.remove(id);
            if (p != null && p.isOnline()) {
                p.setGameMode(GameMode.SURVIVAL);
                p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
                HubAPI.clearGame(id);
                playerArena.remove(id);
                HubAPI.teleportToLobby(p);
            } else {
                HubAPI.clearGame(id);
                playerArena.remove(id);
            }
        }
        a.getPlayers().clear();
    }

    public void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        String name = playerArena.remove(id);
        p.setGameMode(GameMode.SURVIVAL);
        if (name == null) {
            return;
        }
        FootballArena a = arenas.get(name);
        if (a == null) {
            return;
        }
        a.getPlayers().remove(id);
        a.removeFromTeams(id);
        clearTeamColor(p);
        sprintCooldown.remove(id);
        jumpCooldown.remove(id);
        HubAPI.clearGame(id);
        reportArena(a);
        if (a.getState() == FootballArena.State.RUNNING
                && (a.getTeamRed().isEmpty() || a.getTeamBlue().isEmpty())) {
            FootballArena.Team winner = a.getTeamRed().isEmpty()
                    ? FootballArena.Team.BLUE : FootballArena.Team.RED;
            endMatch(a, winner);
        } else if (a.getState() == FootballArena.State.WAITING) {
            wait(a).update();
        }
    }

    public void handleJoin(Player p) {
        if (p == null || !p.isOnline()) {
            return;
        }
        if (playerArena.containsKey(p.getUniqueId())) {
            return;
        }
        if (p.getGameMode() == GameMode.SPECTATOR) {
            p.setGameMode(GameMode.SURVIVAL);
            plugin.getLogger().info("玩家 " + p.getName() + " 加入时处于观战模式，已恢复为生存模式。");
        }
        java.util.Set<String> fbWorlds = new java.util.HashSet<>();
        for (FootballArena a : arenas.values()) {
            if (a.getCenter() != null && a.getCenter().getWorld() != null) {
                fbWorlds.add(a.getCenter().getWorld().getName());
            }
        }
        if (fbWorlds.contains(p.getWorld().getName())) {
            HubAPI.teleportToLobby(p);
            plugin.getLogger().info("玩家 " + p.getName() + " 不在场但位于对局世界，已送回主城。");
        }
    }

    // ---------- 等待倒计时 ----------

    public int waitingCountOf(String arenaName) {
        FootballArena a = arenas.get(arenaName);
        return a == null ? 0 : a.getPlayers().size();
    }

    public int minPlayersOf(String arenaName) {
        return plugin.getConfig().getInt("min-players", 2);
    }

    public boolean isHostOf(Player p, String arenaName) {
        if (!"host".equalsIgnoreCase(plugin.getConfig().getString("flow", "upstream"))) {
            return false;
        }
        FootballArena a = arenas.get(arenaName);
        if (a == null || p == null) {
            return false;
        }
        UUID host = wait(a).currentHost();
        return host != null && host.equals(p.getUniqueId());
    }

    /** 打印当前等待流程，配置写错时一眼看得出来。 */
    private void logFlowMode() {
        boolean host = "host".equalsIgnoreCase(plugin.getConfig().getString("flow", "upstream"));
        plugin.getLogger().info("等待流程：" + (host
                ? "主持人（" + Math.max(5, plugin.getConfig().getInt("wait-seconds", 300)) + "s）"
                : "上游（达标 30s / 满人 3s；队列里有 OP 则暂停）")
                + "；min-players=" + plugin.getConfig().getInt("min-players", 2));
    }

    private WaitCountdown wait(FootballArena a) {
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
                return plugin.getConfig().getInt("max-players", 10);
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
                        Chat.actionBar(p, "&6开始倒计时：&e" + secondsLeft + "s &8（红 "
                                + a.getTeamRed().size() + " / 蓝 " + a.getTeamBlue().size() + "）");
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

                FootballSession sess = sessions.get(a.getName());

                if (sess != null && hp != null && !sess.state().isFinal()) {

                    sess.leave(hp);

                }

                if (hp != null && hp.isOnline()) {

                    msg(hp, "&c倒计时结束还没开始，你被移出本局（主持人超时）。");

                    com.hub.api.HubAPI.teleportToLobby(hp);

                }

                plugin.getLogger().info("[FootballArena] 场 " + a.getName() + "：主持人 " + hname

                        + " 超时未开始，已移出（剩余 " + a.getPlayers().size() + " 人）");

                broadcast(a, "&e主持人 &f" + hname + " &e超时未开始，已移出本局。");

                FootballManager.this.wait(a).update();

            }


            @Override
            public void onStart() {
                startMatch(a);
            }
        }));
    }

    private void cancelWait(FootballArena a) {
        WaitCountdown w = waits.get(a.getName());
        if (w != null) {
            w.cancel();
        }
    }

    // ---------- 队色（记分板队伍，只染色不设前缀） ----------

    private static final String RED_TEAM = "fb_red";
    private static final String BLUE_TEAM = "fb_blue";

    /** 队伍对应的 tab 名字色号（legacy）：红=c，蓝=9。 */
    private static String teamColorCode(FootballArena.Team team) {
        return team == FootballArena.Team.RED ? "c" : "9";
    }

    private void applyTeamColor(Player p, NamedTextColor color) {
        if (p == null || !p.isOnline()) {
            return;
        }
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = color == NamedTextColor.RED ? RED_TEAM : BLUE_TEAM;
        Team team = sb.getTeam(teamName);
        if (team == null) {
            team = sb.registerNewTeam(teamName);
        }
        team.color(color);
        team.addEntry(p.getName());
        HubAPI.setTabNameColor(p.getUniqueId(), color == NamedTextColor.RED ? "c" : "9");
    }

    private void clearTeamColor(Player p) {
        if (p == null) {
            return;
        }
        HubAPI.setTabNameColor(p.getUniqueId(), null);
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (String teamName : List.of(RED_TEAM, BLUE_TEAM)) {
            Team team = sb.getTeam(teamName);
            if (team != null) {
                team.removeEntry(p.getName());
                if (team.getEntries().isEmpty()) {
                    team.unregister();
                }
            }
        }
    }

    // ---------- 战绩 ----------

    private void recordWin(UUID id) {
        if (data == null) {
            return;
        }
        data.set("wins." + id, getWins(id) + 1);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            data.set("names." + id, p.getName());
        }
        saveData();
    }

    public int getWins(UUID id) {
        return data == null ? 0 : data.getInt("wins." + id, 0);
    }

    /** OP 清空全部战绩。 */
    public void resetStats() {
        if (data != null) {
            for (String k : new ArrayList<>(data.getKeys(false))) {
                data.set(k, null);
            }
            saveData();
        }
    }

    // ---------- 配置模式 ----------

    private final SetupMode.Handler setupHandler = new SetupMode.Handler() {
        @Override
        public void onTool(Player p, String arenaName, String toolId, Action action, Block clicked) {
            FootballArena a = setupTargets.get(p.getUniqueId());
            if (a == null) {
                a = arenas.get(arenaName);
            }
            if (a == null) {
                exitSetup(p);
                return;
            }
            String name = a.getName();
            boolean left = action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR;
            boolean right = action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR;
            switch (toolId) {
                case "pos" -> {
                    if (left && clicked != null) {
                        a.setPos1(clicked.getLocation());
                        msg(p, "&a已设置 &e" + name + "&a 范围角1：&f" + shortLoc(clicked.getLocation()));
                        saveAll();
                    } else if (right && clicked != null) {
                        a.setPos2(clicked.getLocation());
                        msg(p, "&a已设置 &e" + name + "&a 范围角2：&f" + shortLoc(clicked.getLocation()));
                        saveAll();
                    }
                }
                case "center" -> {
                    if (right) {
                        a.setCenter(p.getLocation());
                        msg(p, "&a已设置 &e" + name + "&a 开球点：&f" + shortLoc(p.getLocation()));
                        saveAll();
                    }
                }
                case "spawn-red" -> {
                    if (right) {
                        a.setSpawnRed(p.getLocation());
                        msg(p, "&a已设置 &e" + name + "&a 红队出生点：&f" + shortLoc(p.getLocation()));
                        saveAll();
                    }
                }
                case "spawn-blue" -> {
                    if (right) {
                        a.setSpawnBlue(p.getLocation());
                        msg(p, "&a已设置 &e" + name + "&a 蓝队出生点：&f" + shortLoc(p.getLocation()));
                        saveAll();
                    }
                }
                case "goal-red" -> {
                    if (left && clicked != null) {
                        a.setGoalRedPos1(clicked.getLocation());
                        msg(p, "&a已设置 &e" + name + "&a 红门角1：&f" + shortLoc(clicked.getLocation())
                                + (a.getGoalRedPos2() != null ? "（红门已定）" : "（右键定红门角2）"));
                        saveAll();
                    } else if (right && clicked != null) {
                        a.setGoalRedPos2(clicked.getLocation());
                        msg(p, "&a已设置 &e" + name + "&a 红门角2：&f" + shortLoc(clicked.getLocation())
                                + (a.getGoalRedPos1() != null ? "（红门已定）" : "（左键定红门角1）"));
                        saveAll();
                    }
                }
                case "goal-blue" -> {
                    if (left && clicked != null) {
                        a.setGoalBluePos1(clicked.getLocation());
                        msg(p, "&a已设置 &e" + name + "&a 蓝门角1：&f" + shortLoc(clicked.getLocation())
                                + (a.getGoalBluePos2() != null ? "（蓝门已定）" : "（右键定蓝门角2）"));
                        saveAll();
                    } else if (right && clicked != null) {
                        a.setGoalBluePos2(clicked.getLocation());
                        msg(p, "&a已设置 &e" + name + "&a 蓝门角2：&f" + shortLoc(clicked.getLocation())
                                + (a.getGoalBluePos1() != null ? "（蓝门已定）" : "（左键定蓝门角1）"));
                        saveAll();
                    }
                }
                default -> {
                }
            }
        }

        @Override
        public void onSave(Player p, String arenaName) {
            FootballArena a = setupTargets.get(p.getUniqueId());
            saveAll();
            msg(p, "&a已保存场 &e" + (a != null ? a.getName() : arenaName) + "&a。");
        }
    };

    private List<SetupMode.Tool> setupTools() {
        return List.of(
                SetupMode.Tool.both(Material.STICK, "pos", "&b场地范围", "&7左键=角1&8/&7右键=角2"),
                SetupMode.Tool.right(Material.GOLDEN_HOE, "center", "&e开球点", "&7右键=球放在这"),
                SetupMode.Tool.right(Material.RED_WOOL, "spawn-red", "&c红队出生点", "&7右键=红队站这"),
                SetupMode.Tool.right(Material.BLUE_WOOL, "spawn-blue", "&9蓝队出生点", "&7右键=蓝队站这"),
                SetupMode.Tool.both(Material.BRICKS, "goal-red", "&c红队球门", "&7左键=角1&8/&7右键=角2&7（红队守）"),
                SetupMode.Tool.both(Material.STONE_BRICKS, "goal-blue", "&9蓝队球门", "&7左键=角1&8/&7右键=角2&7（蓝队守）"),
                SetupMode.Tool.right(Material.EMERALD, "save", "&6保存场", "&7右键=保存当前配置"),
                SetupMode.Tool.right(Material.BARRIER, "exit", "&c退出配置模式", "&7右键=结束并恢复背包"));
    }

    public boolean isInSetup(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null && !setup.isEditing(p)) {
            setupTargets.remove(id);
        }
        return setupTargets.containsKey(id);
    }

    public boolean enterSetup(Player p, String arenaName) {
        FootballArena a = arenas.get(arenaName);
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
        msg(p, "&b木棍&7 场地范围（左1右2） &e金锄头&7 开球点");
        msg(p, "&c红羊毛&7 红队出生 &9蓝羊毛&7 蓝队出生");
        msg(p, "&c红砖&7 红门（左1右2） &f石砖&7 蓝门（左1右2）");
        msg(p, "&6绿宝石&7 保存  &c屏障&7 退出");
        msg(p, "&7提示：球门要圈住门框内的空间（含底线），球心进圈即判进球。");
        return true;
    }

    public void exitSetup(Player p) {
        setupTargets.remove(p.getUniqueId());
        setup.exit(p);
        msg(p, "&7已退出配置模式，背包已恢复。");
    }

    public boolean handleSetupTool(Player p, Action action, ItemStack hand, Block clicked) {
        return setup.handleInteract(p, action, hand, clicked);
    }

    // ---------- 加入告示牌 ----------

    public void placeJoinSign(Player p, String arenaName) {
        if (arenas.get(arenaName) == null) {
            msg(p, "&c场不存在。");
            return;
        }
        pendingSign.put(p.getUniqueId(), arenaName);
        msg(p, "&a请右键点击一个告示牌，将其变为 &e" + arenaName + "&a 的加入牌。");
    }

    public boolean handleSignBind(Player p, Location loc) {
        String arenaName = pendingSign.remove(p.getUniqueId());
        if (arenaName == null) {
            return false;
        }
        joinSigns.computeIfAbsent(arenaName, k -> new ArrayList<>()).removeIf(x -> sameBlock(x, loc));
        joinSigns.computeIfAbsent(arenaName, k -> new ArrayList<>()).add(loc);
        refreshJoinSigns(arenaName);
        saveAll();
        msg(p, "&a已将告示牌变为 &e" + arenaName + "&a 的加入牌。");
        return true;
    }

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

    public void refreshJoinSigns(String arenaName) {
        List<Location> locs = joinSigns.get(arenaName);
        if (locs == null) {
            return;
        }
        FootballArena a = arenas.get(arenaName);
        int waiting = a == null ? 0 : a.getPlayers().size();
        boolean running = a != null && a.getState() == FootballArena.State.RUNNING;
        for (Location loc : locs) {
            if (!(loc.getBlock().getState() instanceof Sign sign)) {
                continue;
            }
            sign.line(0, Chat.color("&a[加入] &e" + arenaName));
            sign.line(1, Chat.color("&7绿茵足球 右键加入"));
            sign.line(2, Chat.color(running ? "&c比赛中" : "&7等待：" + waiting + "人"));
            sign.line(3, Chat.color("&7战绩：/fb stats"));
            sign.update();
        }
    }

    // ---------- 辅助 ----------

    private void cancelPause(FootballArena a) {
        BukkitTask t = pauseTasks.remove(a.getName());
        if (t != null) {
            t.cancel();
        }
    }

    private void broadcast(FootballArena a, String msg) {
        for (UUID id : a.getPlayers()) {
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

    private boolean sameBlock(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        return a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }
}
