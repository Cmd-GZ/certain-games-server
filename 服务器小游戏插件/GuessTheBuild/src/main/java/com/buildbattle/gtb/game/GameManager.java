package com.buildbattle.gtb.game;

import com.hub.util.StartCountdown;

import com.buildbattle.gtb.GuessBuildPlugin;
import com.buildbattle.gtb.arena.Arena;
import com.buildbattle.gtb.util.Chat;
import com.hub.api.GameState;
import com.hub.data.PlayerStats;
import com.hub.util.Top3;
import com.hub.api.HubAPI;
import com.hub.util.WaitCountdown;
import com.buildbattle.gtb.words.Word;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 游戏主逻辑（Hypixel Guess the Build 风格）：
 * 等待大厅 → 轮流建造/猜词 → 结算排名。
 * 所有修改游戏状态的方法都在主线程调用（聊天猜词通过调度到主线程处理）。
 */
public class GameManager {

    public enum Phase {
        WAITING, PLAYING
    }

    private static final String NORMALIZE_REGEX = "[\\s\\p{Punct}。，、；：？！“”‘’「」『』（）【】《》〈〉…—·～]+";

    private final GuessBuildPlugin plugin;

    private final List<UUID> players = new ArrayList<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, Long> lastGuess = new HashMap<>();
    private final Set<UUID> guessedRound = new HashSet<>();
    /** 游戏进行中中途加入的玩家：仅作为猜测者，本局不参与建造轮换（下一局才正常参与）。 */
    private final Set<UUID> midGameJoins = new HashSet<>();
    /** 本局建造轮换队列：开局时按加入顺序入队；退出者在 leave 时出队（自动跳过其建造回合）；中途加入者不入队。 */
    private final ArrayDeque<UUID> buildRotation = new ArrayDeque<>();
    /** 收尾中标志：endGame 期间禁止回调重入 leave()（HubAPI.teleportToLobby 会回调本插件的 lobby handler）。 */
    private boolean ending;
    /** 等待室自动开局倒计时（公共库 MiniGameLib；同时只有一个场地在跑）。 */
    private final WaitCountdown wait;
    private Location lobbyPoint;

    // 战绩（第一名次数）：唯一文件 plugins/MiniGameLib/players.yml 的 gtb 段
    private YamlConfiguration data;

    /** 设置等待点（OP /gtb lobby）。 */
    public void setLobbyPoint(Location loc) {
        this.lobbyPoint = loc.clone();
        plugin.getConfig().set("lobby-point", loc);
        plugin.saveConfig();
    }

    public Location getLobbyPoint() {
        if (lobbyPoint == null) {
            lobbyPoint = (Location) plugin.getConfig().get("lobby-point");
        }
        return lobbyPoint;
    }
    private final Set<String> usedWords = new HashSet<>();

    private Phase phase = Phase.WAITING;
    private int round = 0;
    private int totalRounds = 0;
    private UUID builder = null;
    private boolean evaluating = false;
    private org.bukkit.scheduler.BukkitTask evalTask = null;
    /** 评价阶段各猜测者的评分（0-10，每人每轮一次）。 */
    private final Map<UUID, Integer> evalScores = new HashMap<>();
    /** 本局各玩家的建筑评分（每轮建造者平均分；同一玩家多次建造时累计，结算时取平均）。 */
    private final Map<UUID, List<Double>> buildScores = new HashMap<>();
    /** 本轮自创词（评价结束后按平均分结算用；非自创词轮为 null）。 */
    private String lastCustomWord = null;
    private boolean awaitingCustomWord = false;   // 建造者正在输入自创词
    private String pendingCustomWord = null;      // 第一次输入的自创词（待二次确认）
    private boolean customRound = false;          // 本轮是否为自创词
    private String currentWord = null;
    private int wordLen = 0;
    private long roundStartMs = 0;
    private long roundEndMs = 0;
    private BukkitTask endTask = null;
    private BukkitTask hintTask = null;

    /** 开局五选一：本轮候选题目（具体词），建造者从中选一个。 */
    private boolean selectingTheme = false;
    private List<Word> themeCandidates = List.of();
    /** 首字提示是否已发送（用于"提示前猜中"判定，与实际提示同步）。 */
    private boolean hintSent = false;
    private int shortenStage = 0; // 0=未缩短 1=已缩至30秒 2=已缩至10秒
    private final Random random = new Random();

    public GameManager(GuessBuildPlugin plugin) {
        this.plugin = plugin;
        this.wait = new WaitCountdown(plugin, new WaitHost());
        startCountdown();
        // 战绩数据
        // 战绩数据（唯一文件 plugins/MiniGameLib/players.yml，命名空间 gtb）
        data = PlayerStats.load("gtb", new File(plugin.getDataFolder(), "data.yml"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        // ActionBar 常驻显示（每 5 tick 刷新，避免闪烁）：建造者看题目，猜测者看字数/提示
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateActionBar, 5L, 5L);
    }

    // ---------- 公共查询 ----------

    public Phase getPhase() {
        return phase;
    }

    public boolean isInGame(UUID uuid) {
        return players.contains(uuid);
    }

    public int getPlayerCount() {
        return players.size();
    }

    public Map<UUID, Integer> getScores() {
        return scores;
    }

    // ---------- 加入 / 离开 / 开始 ----------

    /** 上报所有已设置场地到 GameHub 中央菜单：生效场地游戏进行中=红（显示轮次），其余=绿可加入。 */
    private void reportArena() {
        boolean playing = phase == Phase.PLAYING;
        String active = plugin.getArenas().getActiveName();
        for (Arena a : plugin.getArenas().getAll()) {
            if (!a.isReady()) {
                continue;
            }
            boolean isActive = a.getName().equals(active);
            HubAPI.setArena("猜猜乐", a.getName(),
                    playing && isActive ? GameState.PLAYING : GameState.WAITING,
                    playing && isActive ? players.size() : 0,
                    playing && isActive && round > 0 ? "第 " + round + "/" + totalRounds + " 轮" : null);
            HubAPI.setArenaSpectator("猜猜乐", a.getName(), a.getSpawn());
        }
    }

    /** 插件启动或场地增删/切换后调用：把全部场地状态刷新给 GameHub 中央菜单。 */
    public void reportArenaToHub() {
        reportArena();
    }

    public synchronized void join(Player p) {
        join(p, null);
    }

    /** 加入游戏；arenaName 非空时指定本局场地（存在且已设置才可，等待阶段可切换）。
     *  等待阶段加入：正常等待开局，参与建造轮换；游戏进行中加入：仅作为猜测者，本局不参与建造。 */
    public synchronized void join(Player p, String arenaName) {
        UUID id = p.getUniqueId();
        if (players.contains(id)) {
            Chat.send(p, "&c你已经在游戏中了。");
            return;
        }
        int max = plugin.getConfig().getInt("game.max-players", 16);
        if (players.size() >= max) {
            Chat.send(p, "&c游戏已满员（&e" + max + "&c 人），无法加入。");
            return;
        }
        boolean midGame = phase != Phase.WAITING;
        // 指定场地：等待阶段可校验并切换；游戏进行中不切换（直接加入当前场地）
        if (arenaName != null && !arenaName.isEmpty()) {
            var mgr = plugin.getArenas();
            var a = mgr.getArena(arenaName);
            if (a == null || !a.isReady()) {
                Chat.send(p, "&c场地 &e" + arenaName + "&c 不存在或尚未设置（/gtb list 查看）。");
                return;
            }
            if (midGame) {
                if (!arenaName.equals(mgr.getActiveName())) {
                    Chat.send(p, "&7游戏已在进行，直接加入当前场地 &e" + mgr.getActiveName() + "&7。");
                }
            } else if (!arenaName.equals(mgr.getActiveName())) {
                mgr.setActive(arenaName);
                broadcast("&e" + p.getName() + "&f 指定了场地，本局将在 &b" + arenaName + "&f 进行。");
            }
        }
        players.add(id);
        names.put(id, p.getName());
        states.put(id, PlayerState.capture(p));
        scores.put(id, 0);

        if (midGame) {
            // ---- 游戏进行中：作为猜测者加入，不进入建造轮换 ----
            midGameJoins.add(id);
            HubAPI.setPlaying(id, "猜猜乐"); // 已在对局中：tab 显示 [已加入]
            p.getInventory().clear(); // 清空主城物品，不发等待道具
            Arena arena = plugin.getArena();
            if (arena.isReady()) {
                Player b = Bukkit.getPlayer(builder);
                Location watch = b != null && b.isOnline() ? b.getLocation()
                        : arena.getBuildStart().add(0, 2, 0);
                p.teleport(watch);
            }
            // 与 enforceStates 保持一致：评价阶段观众=创造，建造阶段观众=旁观
            p.setGameMode(evaluating ? GameMode.CREATIVE : GameMode.SPECTATOR);
            p.setInvulnerable(true);
            Chat.send(p, "&a你已作为猜测者加入本局！&7本局不参与建造，下一局可正常参与建造。");
            broadcast("&e" + p.getName() + "&f 中途加入了本局（仅猜测，&e" + players.size() + "/" + max + "&f 人）");
        } else {
            // ---- 等待阶段：正常加入，参与建造轮换 ----
            HubAPI.setGame(id, "猜猜乐");
            // 传送到管理员设置的等待点（getLobbyPoint 会从配置读取，重启后仍生效；未设置则留在原地）
            Location waitPoint = getLobbyPoint();
            if (waitPoint != null && waitPoint.getWorld() != null) {
                p.teleport(waitPoint);
            }
            Chat.send(p, "&a你已加入！当前 &e" + players.size() + "/" + max + "&a 人。右键右下角的床可以退出。");
            broadcast("&e" + p.getName() + "&f 加入了游戏（&e" + players.size() + "/" + max + "&f 人）");
            HubAPI.announceJoin(plugin.getName(), p.getName()); // 全服公屏播报
            giveWaitingItems(p); // 清空背包并发放等待道具
            updateWaitCountdown();
        }
        updateScoreboard();
        reportArena();
    }

    public synchronized void leave(Player p, boolean quit) {
        if (ending) {
            return; // endGame 正在收尾：它会自己还原状态并回城，这里不能再改集合
        }
        UUID id = p.getUniqueId();
        if (!players.contains(id)) {
            return;
        }
        boolean wasPlaying = phase == Phase.PLAYING;
        players.remove(id);
        names.remove(id);
        PlayerState state = states.remove(id);
        scores.remove(id);
        lastGuess.remove(id);
        guessedRound.remove(id);
        midGameJoins.remove(id);
        evalScores.remove(id);
        buildScores.remove(id);
        // 从建造轮换队列移除：若该玩家还有未进行的建造回合，则自动跳过（不会让其他人重复建造）
        buildRotation.remove(id);
        HubAPI.clearGame(id);

        if (wasPlaying) {
            broadcast("&e" + p.getName() + "&f 退出了游戏（剩余 &e" + players.size() + "&f 人）");
            if (state != null) {
                state.apply(p);
            }
            if (players.size() < 2) {
                broadcast("&c人数不足，游戏结束。");
                if (!quit && p.isOnline()) {
                    HubAPI.teleportToLobby(p); // 先送回主城，再收尾（该玩家已不在 players 列表）
                }
                endGame();
                return;
            }
            if (id.equals(builder) && !evaluating) {
                // 建造者退出：本轮提前结束，自动轮换到下一位建造者（其未完成的回合直接跳过）
                broadcast("&c建造者退出了，本轮提前结束。");
                cancelEndTask();
                cancelHintTask();
                if (currentWord != null) {
                    // 已有题目：按原流程公布答案并进入评价，评价结束后自动进入下一轮
                    endRound();
                } else {
                    // 选题/自创词输入阶段退出：本轮没有可评价的建筑，直接进入下一轮
                    selectingTheme = false;
                    themeCandidates = List.of();
                    awaitingCustomWord = false;
                    pendingCustomWord = null;
                    customRound = false;
                    lastCustomWord = null;
                    currentWord = null;
                    wordLen = 0;
                    hintSent = false;
                    nextRoundOrEnd();
                }
            }
        } else {
            if (state != null) {
                state.apply(p); // 恢复原背包和状态
            }
            broadcast("&e" + p.getName() + "&f 离开了游戏（&e" + players.size() + "&f 人）");
        }
        // 正常退出（床/命令/离开）→ 自动回到主城并拿菜单道具；断线退出（quit）不做传送
        if (!quit && p.isOnline()) {
            HubAPI.teleportToLobby(p);
        }
        if (phase == Phase.WAITING) {
            updateWaitCountdown();
        }
        updateScoreboard();
        reportArena();
    }

    // ---------- 等待倒计时（实现已移到公共库 WaitCountdown） ----------

    /**
     * 公共库倒计时插件侧回调：
     * 人数取自 players，min/max 读 game.min-players / game.max-players，
     * 提示文案与动作栏文本保持原样（广播走带前缀的 {@link #broadcast(String)}，动作栏不带前缀）。
     */
    private final class WaitHost implements WaitCountdown.Host {

        @Override
        public Collection<UUID> waitingPlayers() {
            return players;
        }

        @Override
        public int minPlayers() {
            return plugin.getConfig().getInt("game.min-players", 3);
        }

        @Override
        public int maxPlayers() {
            return plugin.getConfig().getInt("game.max-players", 16);
        }

        @Override
        public boolean hasOpQueued() {
            for (UUID id : players) {
                Player pp = Bukkit.getPlayer(id);
                if (pp != null && pp.isOp()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onTick(long secondsLeft) {
            for (UUID id : players) {
                Player pp = Bukkit.getPlayer(id);
                if (pp != null && pp.isOnline()) {
                    com.hub.util.Chat.actionBar(pp, "&6开始倒计时：&e" + secondsLeft + "s");
                }
            }
        }

        @Override
        public void onMessage(String message) {
            broadcast(message);
        }

        @Override
        public void onStart() {
            // 调内部开局路径（无权限校验的那一个），与原先倒计时归零时的行为一致
            Player any = players.isEmpty() ? null : Bukkit.getPlayer(players.get(0));
            if (any != null) {
                start(any);
            }
        }
    }

    /** 人数/状态变化后调用：该启动就启动，该缩短就缩短，该取消就取消（委托公共库）。 */
    private void updateWaitCountdown() {
        wait.update();
    }

    /** 自动倒计时是否正在运行。 */
    private boolean isWaitRunning() {
        return wait.isRunning();
    }

    /** 当前倒计时剩余秒数（不小于 0）。 */
    private long remainingWait() {
        return wait.remainingSeconds();
    }

    private void startWait(int seconds) {
        wait.start(seconds);
    }

    private void cancelWait() {
        wait.cancel();
    }

    private StartCountdown startCountdown;

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

    public synchronized void start(Player requester) {
        cancelWait();
        if (phase != Phase.WAITING) {
            Chat.send(requester, "&c游戏已经开始。");
            return;
        }
        int min = plugin.getConfig().getInt("game.min-players", 3);
        if (players.size() < min) {
            Chat.send(requester, "&c人数不足，至少需要 &e" + min + "&c 人（当前 &e" + players.size() + "&c）。");
            return;
        }
        if (!plugin.getArena().isReady()) {
            Chat.send(requester, "&c场地尚未设置！请管理员先在场地两个角点击木棍，再用 &e/gtb setup save&c 保存。");
            return;
        }
        if (plugin.getWordManager().isEmpty()) {
            Chat.send(requester, "&c词库为空，请管理员在 config.yml 中添加词语。");
            return;
        }
        if (startCountdown().begin("gtb", new ArrayList<>(players), () -> start(requester))) {
            return;
        }
        int configured = plugin.getConfig().getInt("game.rounds", 0);
        // 本局建造轮换队列：按当前加入顺序排好；中途加入者不会进入该队列，退出者会在 leave 时移除
        buildRotation.clear();
        buildRotation.addAll(players);
        totalRounds = configured > 0 ? Math.min(configured, buildRotation.size()) : buildRotation.size();
        round = 0;
        scores.replaceAll((k, v) -> 0);
        phase = Phase.PLAYING;
        reportArena();
        broadcast("&a游戏开始！共 &e" + totalRounds + "&a 轮，每人都会当一次建造者。");
        // 通知 GameHub：所有玩家从"等待中"进入"对局中"（tab 前缀 [等待中] → [已加入]）
        for (UUID u : players) {
            HubAPI.setPlaying(u, "猜猜乐");
        }
        // 回收等待道具，不带入游戏
        for (UUID u : players) {
            Player pp = Bukkit.getPlayer(u);
            if (pp != null && pp.isOnline()) {
                clearWaitingItems(pp);
            }
        }
        beginRound();
    }

    public synchronized void stop(Player admin) {
        if (phase == Phase.WAITING) {
            Chat.send(admin, "&c当前没有进行中的游戏。");
            return;
        }
        broadcast("&c管理员强制结束了游戏。");
        endGame();
    }

    // ---------- 等待道具 ----------

    public static final Material START_ITEM = Material.PAPER;
    public static final Material CONFIRM_ITEM = Material.EMERALD;
    public static final Material LEAVE_ITEM = Material.RED_BED;
    private static final String START_NAME = "开始游戏";
    private static final String CONFIRM_NAME = "确定开始？";
    private static final String LEAVE_NAME = "退出游戏";

    /** 加入等待后：清空背包，发放开始/退出道具。 */
    public void giveWaitingItems(Player p) {
        p.getInventory().clear();
        if (p.isOp()) {
            p.getInventory().setItem(0, waitingItem(START_ITEM, START_NAME, "右键开始游戏（一次确认）"));
        }
        p.getInventory().setItem(8, waitingItem(LEAVE_ITEM, LEAVE_NAME, "右键离开游戏"));
    }

    /** 游戏开始前回收等待道具。 */
    public void clearWaitingItems(Player p) {
        p.getInventory().setItem(0, null);
        p.getInventory().setItem(8, null);
    }

    /**
     * 右键等待道具（主手）：
     * 床=退出；开始工具 → 绿宝石确认 → 再右键绿宝石就开始。
     */
    public synchronized boolean handleWaitingItem(Player p, ItemStack item) {
        if (phase != Phase.WAITING || !players.contains(p.getUniqueId())) {
            return false;
        }
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        String name = PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
        if (item.getType() == LEAVE_ITEM && LEAVE_NAME.equals(name)) {
            leave(p, false);
            return true;
        }
        if (item.getType() == START_ITEM && START_NAME.equals(name)) {
            p.getInventory().setItemInMainHand(waitingItem(CONFIRM_ITEM, CONFIRM_NAME, "再右键一次确认开始"));
            Chat.send(p, "&e确定开始？再右键一次确认。");
            return true;
        }
        if (item.getType() == CONFIRM_ITEM && CONFIRM_NAME.equals(name)) {
            if (!p.isOp()) {
                // 非 OP 无法开始：提示并把道具恢复为开始（纸）
                p.getInventory().setItemInMainHand(waitingItem(START_ITEM, START_NAME, "右键开始游戏（一次确认）"));
                Chat.send(p, "&c只有管理员（OP）可以开始游戏。");
                return true;
            }
            start(p);
            return true;
        }
        return false;
    }

    private ItemStack waitingItem(Material m, String name, String lore) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.noItalic(Chat.color("&f" + name)));
        meta.lore(java.util.List.of(Chat.color("&7" + lore)));
        item.setItemMeta(meta);
        return item;
    }

    // ---------- 轮次流程 ----------

    private synchronized void beginRound() {
        awaitingCustomWord = false;
        pendingCustomWord = null;
        customRound = false;
        lastCustomWord = null;
        if (phase != Phase.PLAYING || players.isEmpty()) {
            return;
        }
        if (buildRotation.isEmpty() || round >= totalRounds) {
            // 建造轮换队列已空（所有人已建造或已退出）或轮次已达上限：本局结束
            endGame();
            return;
        }
        round++;
        reportArena();
        // 从建造轮换队列取出下一位建造者：中途加入者不入队（仅猜测），
        // 退出者已在 leave 时出队，其建造回合被自动跳过
        builder = buildRotation.pollFirst();
        guessedRound.clear();
        currentWord = null;
        wordLen = 0;
        selectingTheme = false;
        themeCandidates = List.of();
        hintSent = false;
        // 每轮开始清空所有玩家背包，防止带入道具/残留物品
        for (UUID u : players) {
            Player pl = Bukkit.getPlayer(u);
            if (pl != null && pl.isOnline()) {
                pl.getInventory().clear();
            }
        }

        cancelEndTask();
        cancelHintTask();

        Arena arena = plugin.getArena();
        // 先还原场地（分批次），还原完成后才把玩家传送到场地——
        // 若还原未完成就传送，第一轮地板还是空气，建造者会掉下虚空
        arena.restore(this::teleportPlayersToArena);

        // 开局五选一：随机给 5 个具体题目，建造者选一个
        Player b = Bukkit.getPlayer(builder);
        int selectSec = plugin.getConfig().getInt("game.theme-select-seconds", 15);
        List<Word> candidates = plugin.getWordManager().pickWords(5, usedWords);
        if (selectSec > 0 && candidates.size() >= 2) {
            selectingTheme = true;
            themeCandidates = candidates;
            roundStartMs = System.currentTimeMillis();
            roundEndMs = roundStartMs + selectSec * 1000L;
            broadcast("&f第 &e" + round + "/" + totalRounds + "&f 轮：&e" + name(builder)
                    + "&f 请选择本轮题目（&e" + selectSec + "&f 秒内不选将随机分配）");
            sendThemeOptions(b);
            endTask = Bukkit.getScheduler().runTaskLater(plugin, this::autoPickTheme, selectSec * 20L);
        } else {
            // 未启用题目选择：直接随机词开建
            startBuildRound(null);
            return;
        }
        updateScoreboard();
    }

    /** 场地还原完成后调用：建造者站场地中央（创造），猜测者旁观同一位置。 */
    private synchronized void teleportPlayersToArena() {
        if (phase != Phase.PLAYING || builder == null) {
            return;
        }
        Arena arena = plugin.getArena();
        if (!arena.isReady()) {
            return;
        }
        Player b = Bukkit.getPlayer(builder);
        if (b != null && b.isOnline()) {
            b.teleport(arena.getBuildStart().add(0, 2, 0));
            b.setGameMode(GameMode.CREATIVE);
            b.setInvulnerable(true);
            // 创造模式自带飞行能力，不强制开启飞行，玩家可双击空格自由切换
        }
        Location buildStart = b != null && b.isOnline() ? b.getLocation() : arena.getBuildStart().add(0, 2, 0);
        for (UUID u : players) {
            if (u.equals(builder)) {
                continue;
            }
            Player g = Bukkit.getPlayer(u);
            if (g == null || !g.isOnline()) {
                continue;
            }
            g.setGameMode(GameMode.SPECTATOR);
            g.setInvulnerable(true);
            g.teleport(buildStart);
        }
    }

    /** 建造者选定题目（或超时随机）后：广播提示并开始正式建造计时。 */
    private void startBuildRound(Word word) {
        if (phase != Phase.PLAYING || builder == null) {
            return;
        }
        if (word == null) {
            word = plugin.getWordManager().randomWord(usedWords);
        }
        usedWords.add(word.word());
        currentWord = word.word();
        wordLen = currentWord.length();
        selectingTheme = false;
        themeCandidates = List.of();
        // 单字词没有首字提示（首字即答案），视为提示已给出，不享受"提示前猜中"加分
        hintSent = wordLen <= 1;
        shortenStage = 0;

        long seconds = Math.max(10, plugin.getConfig().getInt("game.build-time-seconds", 150));
        roundStartMs = System.currentTimeMillis();
        roundEndMs = roundStartMs + seconds * 1000L;

        cancelEndTask();

        Player b = Bukkit.getPlayer(builder);
        if (b != null && b.isOnline()) {
            Chat.send(b, "&6本轮题目：&e" + currentWord + "&6（分类：&7" + word.category() + "&6）");
            Chat.title(b, "&e" + currentWord, "&7这是你的题目，开始建造吧！");
        }
        broadcast("&f第 &e" + round + "/" + totalRounds + "&f 轮：&e" + name(builder)
                + "&f 开始建造！答案共 &e" + wordLen + "&f 个字。");
        if (wordLen > 1) {
            broadcast("&7字数提示：&f" + underscores(wordLen));
        }

        int hintAt = plugin.getConfig().getInt("game.hint-time-seconds", 60);
        if (hintAt > 0 && wordLen > 1) {
            cancelHintTask();
            hintTask = Bukkit.getScheduler().runTaskLater(plugin, this::sendHint, hintAt * 20L);
        }
        endTask = Bukkit.getScheduler().runTaskLater(plugin, this::endRound, seconds * 20L);
        updateScoreboard();
    }

    /** 建造者选择题目（由 /gtb theme 或聊天输入触发，主线程调用）。 */
    public synchronized boolean selectTheme(Player p, String input) {
        if (phase != Phase.PLAYING || !selectingTheme || !p.getUniqueId().equals(builder)) {
            return false;
        }
        String s = input.trim();
        int index = -1;
        try {
            index = Integer.parseInt(s) - 1;
        } catch (NumberFormatException ignored) {
            // 不是数字，尝试按题目名匹配
        }
        if (index == 5) {
            // 选项 6：自创词
            startCustomWord(p);
            return true;
        }
        if (index < 0 || index >= themeCandidates.size()) {
            for (int i = 0; i < themeCandidates.size(); i++) {
                if (themeCandidates.get(i).word().equalsIgnoreCase(s)) {
                    index = i;
                    break;
                }
            }
        }
        if (index < 0 || index >= themeCandidates.size()) {
            Chat.send(p, "&c请输入有效的选项编号（1-" + themeCandidates.size() + "）或点击对应题目。");
            return false;
        }
        pickTheme(index);
        return true;
    }

    /** 选项 6：建造者进入自创词输入模式（黑名单玩家不可用）。 */
    private void startCustomWord(Player p) {
        if (isSelfmadeBanned(builder)) {
            Chat.send(p, "&c你曾因自创词评分过低进入黑名单，本轮无法选择自创词。");
            return;
        }
        cancelEndTask(); // 取消选题超时，自创词输入同样有超时保护
        awaitingCustomWord = true;
        pendingCustomWord = null;
        selectingTheme = false;
        broadcast("&e" + name(builder) + "&f 选择了自创词！请在聊天框输入题目（连续两次相同确认，其他玩家看不到）。");
        Chat.send(p, "&6请输入你的自创题目（最多 &e" + plugin.getConfig().getInt("game.max-word-length", 5)
                + "&6 个字，连续输入两次相同即确定）：");
        int selectSec = Math.max(5, plugin.getConfig().getInt("game.theme-select-seconds", 15));
        endTask = Bukkit.getScheduler().runTaskLater(plugin, this::autoPickTheme, selectSec * 20L);
    }

    private void pickTheme(int index) {
        Word word = themeCandidates.get(index);
        cancelEndTask();
        // 题目只私发建造者，不公屏（否则答案直接暴露）
        Player b = Bukkit.getPlayer(builder);
        if (b != null) {
            Chat.send(b, "&a你选择了题目：&e" + word.word() + "&a。开始建造吧！");
        }
        startBuildRound(word);
    }

    private void autoPickTheme() {
        if (phase != Phase.PLAYING || builder == null) {
            return;
        }
        if (awaitingCustomWord) {
            awaitingCustomWord = false;
            pendingCustomWord = null;
            Player b = Bukkit.getPlayer(builder);
            Word word = plugin.getWordManager().randomWord(usedWords);
            if (b != null) {
                Chat.send(b, "&e自创词输入超时，随机分配：&6" + word.word() + "&e。开始建造吧！");
            }
            startBuildRound(word);
            return;
        }
        if (!selectingTheme || themeCandidates.isEmpty()) {
            return;
        }
        Word word = themeCandidates.get(random.nextInt(themeCandidates.size()));
        // 不公屏答案，只私发建造者
        Player b = Bukkit.getPlayer(builder);
        if (b != null) {
            Chat.send(b, "&e未选择题目，随机分配：&6" + word.word() + "&e。开始建造吧！");
        }
        startBuildRound(word);
    }

    private void sendThemeOptions(Player b) {
        if (b == null || !b.isOnline()) {
            return;
        }
        Chat.send(b, "&6请选择本轮题目（点击下方选项或输入 &e1/2/3/4/5/6&6）：");
        for (int i = 0; i < themeCandidates.size(); i++) {
            Word word = themeCandidates.get(i);
            Component opt = Chat.color("&e" + (i + 1) + ". &f" + word.word());
            opt = opt.clickEvent(ClickEvent.runCommand("/gtb theme " + (i + 1)))
                    .hoverEvent(HoverEvent.showText(Chat.color("&7分类：&f" + word.category() + "&7 ｜ 点击选择")));
            b.sendMessage(opt);
        }
        Component opt6 = Chat.color("&e6. &f自创词（输入你自己的题目）");
        opt6 = opt6.clickEvent(ClickEvent.runCommand("/gtb theme 6"))
                .hoverEvent(HoverEvent.showText(Chat.color("&7输入两次相同词语确认，评分 >6 分可入库，<4 分进黑名单")));
        b.sendMessage(opt6);
    }

    private void sendHint() {
        if (phase != Phase.PLAYING || currentWord == null || wordLen <= 1) {
            return;
        }
        hintSent = true;
        broadcast("&e提示：答案的第一个字是 『&6" + currentWord.charAt(0) + "&e』（共 " + wordLen + " 个字）");
    }

    private synchronized void endRound() {
        if (phase != Phase.PLAYING || currentWord == null) {
            return;
        }
        cancelEndTask();

        int guesserTotal = (int) players.stream().filter(u -> !u.equals(builder)).count();
        int guessedCount = guessedRound.size();

        if (guessedCount == guesserTotal && guesserTotal > 0) {
            broadcast("&a答案：&e" + currentWord + "&a（全部 " + guessedCount + " 人猜对！）");
        } else if (guessedCount > 0) {
            broadcast("&6答案：&e" + currentWord + "&6（" + guessedCount + "/" + guesserTotal + " 人猜对）");
        } else {
            broadcast("&c本轮结束，无人猜对。答案：&e" + currentWord);
        }

        // 自创词词与黑名单结算移动到评价阶段结束后按平均评分处理（finishEvaluation）
        if (customRound) {
            lastCustomWord = currentWord;
        }

        // 进入评价阶段：全员切创造，观众拿无限评价道具
        currentWord = null;
        wordLen = 0;
        selectingTheme = false;
        themeCandidates = List.of();
        hintSent = false;
        cancelHintTask();
        startEvaluate();
        // 评价期间保留 builder，避免 enforceStates 把全员强制成旁观；结束后再清理
    }

    /** 评价阶段：观众切创造、传送至场地安全位并发放无限评价道具（鸡蛋/雪球/烟花）；玩家可在聊天框输入 0-10 打分（每人一次）；定时结束后按平均分结算并进入下一轮。 */
    private void startEvaluate() {
        int evalSec = Math.max(3, plugin.getConfig().getInt("game.evaluate-seconds", 10));
        evalScores.clear();
        broadcast("&6建造完成！评价时间 &e" + evalSec + "&6 秒——在聊天框输入 &e0~10&6 打分（每人一次）！");
        var arena = plugin.getArena();
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.isOnline()) {
                continue;
            }
            if (u.equals(builder)) {
                p.setGameMode(GameMode.SURVIVAL); // 建造者生存，留在原地
                p.setInvulnerable(false); // 允许被鸡蛋/雪球命中
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.GLOWING, evalSec * 20, 0, false, false)); // 高亮
                continue;
            }
            p.setGameMode(GameMode.CREATIVE); // 观众创造，可自由飞行观看
            p.setInvulnerable(true);
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.INVISIBILITY, evalSec * 20, 0, false, false)); // 隐身
            p.getInventory().clear();
            p.teleport(arena.getEvalSpawn());
            giveEvalItems(p);
        }
        evaluating = true;
        evalTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            evaluating = false;
            finishEvaluation();
            nextRoundOrEnd();
        }, evalSec * 20L);
    }

    /** 一轮收尾：清空建造者与场地，进入下一轮；轮次已满或建造轮换队列已空则结束游戏。 */
    private void nextRoundOrEnd() {
        builder = null;
        plugin.getArena().restore(null);
        killArenaEntities();
        updateScoreboard();
        if (round >= totalRounds || buildRotation.isEmpty()) {
            endGame();
        } else {
            broadcast("&7下一轮即将开始…");
            evalTask = Bukkit.getScheduler().runTaskLater(plugin, this::beginRound, 20L);
        }
    }

    /**
     * 评价阶段结束：计算平均分并大字幕显示建造者本轮得分（所有玩家评分平均值）；
     * 自创词轮按平均分结算（评分低于 4 分：词进词黑名单 + 建造者进玩家黑名单；高于 6 分：词入库；4~6 分保持现状；无人评分保持现状）。
     */
    private void finishEvaluation() {
        double avg = -1;
        if (!evalScores.isEmpty()) {
            int sum = 0;
            for (int v : evalScores.values()) {
                sum += v;
            }
            avg = (double) sum / evalScores.size();
        }
        String builderName = name(builder);
        if (avg >= 0) {
            broadcastTitle("&6&l" + builderName + " 的得分",
                    "&e" + fmt(avg) + " &7/ 10 分（" + evalScores.size() + " 人评分）");
            // 记录本局建筑评分（游戏结束时排名用）
            buildScores.computeIfAbsent(builder, k -> new ArrayList<>()).add(avg);
        } else {
            broadcastTitle("&6&l" + builderName + " 的得分", "&7本轮无人评分");
        }

        // 自创词按平均分结算（完全取代旧的“超三分之二答对入库 / 无人答对进黑名单”规则）
        if (customRound) {
            customRound = false;
            String w = lastCustomWord;
            lastCustomWord = null;
            if (w != null) {
                int below = plugin.getConfig().getInt("game.score-blacklist-below", 4);
                int above = plugin.getConfig().getInt("game.score-library-above", 6);
                if (avg >= 0 && avg < below) {
                    plugin.getWordManager().addSelfmadeBannedWord(w);
                    addSelfmadeBan(builder);
                    broadcast("&c自创词「&e" + w + "&c」平均分 &e" + fmt(avg) + "&c 低于 &e" + below
                            + "&c 分：该词已进入黑名单，&6" + builderName + "&c 也被加入自创词黑名单。");
                } else if (avg > above) {
                    plugin.getWordManager().addWord(w, "自创");
                    broadcast("&a自创词「&e" + w + "&a」平均分 &e" + fmt(avg) + "&a 高于 &e" + above + "&a 分，已加入题库！");
                } else if (avg >= 0) {
                    broadcast("&7自创词「&e" + w + "&7」平均分 &e" + fmt(avg) + "&7（" + below + "~" + above + " 分），保持现状。");
                } else {
                    broadcast("&7自创词「&e" + w + "&7」本轮无人评分，保持现状。");
                }
            }
        }
        evalScores.clear();
    }

    /** 平均分保留一位小数。 */
    private String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** 给游戏内全部玩家显示大字幕（Title，屏幕中央，停留 5 秒）。 */
    private void broadcastTitle(String title, String subtitle) {
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                Chat.title(p, title, subtitle, 5);
            }
        }
    }

    /** 观众评价道具：鸡蛋 / 雪球 / 烟花各 16 个（投掷后自动补满 = 无限）。 */
    private void giveEvalItems(Player p) {
        p.getInventory().setItem(0, new ItemStack(Material.EGG, 16));
        p.getInventory().setItem(1, new ItemStack(Material.SNOWBALL, 16));
        p.getInventory().setItem(2, new ItemStack(Material.FIREWORK_ROCKET, 16));
        p.getInventory().setItem(3, new ItemStack(Material.TNT, 16));
        p.getInventory().setItem(4, new ItemStack(Material.CAKE, 16));
        // 打火石（无限耐久）：用于点燃 TNT
        org.bukkit.inventory.ItemStack flint = new org.bukkit.inventory.ItemStack(Material.FLINT_AND_STEEL, 1);
        org.bukkit.inventory.meta.ItemMeta fm = flint.getItemMeta();
        if (fm != null) {
            fm.setUnbreakable(true);
            flint.setItemMeta(fm);
        }
        p.getInventory().setItem(5, flint);
    }

    public boolean isEvaluating() {
        return evaluating;
    }

    /** 评价结束后清理场地：清除除玩家与地板商店 NPC 外的所有实体（动物/怪物/掉落物/投掷物/炎筒等）。 */
    private void killArenaEntities() {
        var arena = plugin.getArena();
        if (!arena.isReady()) {
            return;
        }
        org.bukkit.Location min = arena.getMin();
        org.bukkit.Location max = arena.getMax();
        int topY = max.getBlockY() + plugin.getConfig().getInt("game.max-build-height", 40);
        org.bukkit.entity.Villager npc = plugin.getFloorNpc();
        java.util.List<org.bukkit.entity.Entity> toKill = new java.util.ArrayList<>();
        for (org.bukkit.entity.Entity e : arena.getWorld().getEntities()) {
            if (e instanceof org.bukkit.entity.Player) {
                continue;
            }
            // 地板商店 NPC 保留：按名字识别，重启后内存引用丢失（或它本来就不是本次启动生成的）也不会被误清
            if (plugin.isFloorNpcEntity(e) || (npc != null && e.equals(npc))) {
                continue;
            }
            org.bukkit.Location loc = e.getLocation();
            if (loc.getBlockX() >= min.getBlockX() && loc.getBlockX() <= max.getBlockX()
                    && loc.getBlockZ() >= min.getBlockZ() && loc.getBlockZ() <= max.getBlockZ()
                    && loc.getY() <= topY) {
                toKill.add(e);
            }
        }
        for (org.bukkit.entity.Entity e : toKill) {
            e.remove();
        }
        if (!toKill.isEmpty()) {
            broadcast("&7已清理场地中的 &e" + toKill.size() + "&7 个实体。");
        }
    }

    /**
     * 结束本局。置 ending 标志是为了挡住重入：收尾时要对每个玩家调
     * HubAPI.teleportToLobby，它会回调本插件注册的 lobby handler（即 leave），
     * 而 leave 会修改 players 并可能再次进入 endGame，导致遍历 players 时抛
     * ConcurrentModificationException、收尾半途中断。
     */
    private synchronized void endGame() {
        if (ending) {
            return;
        }
        ending = true;
        try {
            endGame0();
        } finally {
            ending = false;
        }
    }

    private synchronized void endGame0() {
        cancelEndTask();
        cancelHintTask();
        evaluating = false;
        if (evalTask != null) {
            evalTask.cancel();
            evalTask = null;
        }
        boolean wasPlaying = phase == Phase.PLAYING;
        phase = Phase.WAITING;
        reportArena();
        currentWord = null;
        wordLen = 0;
        builder = null;
        selectingTheme = false;
        awaitingCustomWord = false;
        pendingCustomWord = null;
        customRound = false;
        themeCandidates = List.of();
        hintSent = false;

        if (wasPlaying && !players.isEmpty()) {
            List<Map.Entry<UUID, Integer>> sorted = new ArrayList<>(scores.entrySet());
            sorted.sort((a, b) -> b.getValue() - a.getValue());
            broadcastRaw("");
            broadcastRaw("&6========== 建筑猜猜乐 · 游戏结束 ==========");
            int rank = 1;
            for (Map.Entry<UUID, Integer> e : sorted) {
                broadcastRaw("&e" + rank++ + ". &f" + name(e.getKey()) + " &7- &6" + e.getValue() + " 分");
            }

            // 建筑评分排名：只公布最高分 1 人与最低分 1 人（最高/最低不重叠），各占一行
            List<Map.Entry<UUID, Double>> buildSorted = new ArrayList<>();
            for (Map.Entry<UUID, List<Double>> e : buildScores.entrySet()) {
                double sum = 0;
                for (double v : e.getValue()) {
                    sum += v;
                }
                buildSorted.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), sum / e.getValue().size()));
            }
            buildSorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            if (!buildSorted.isEmpty()) {
                Map.Entry<UUID, Double> best = buildSorted.get(0);
                broadcastRaw("&7—— 建筑评分最高 —— &a" + name(best.getKey()) + " &7- &6" + fmt(best.getValue()) + " 分");
                if (buildSorted.size() > 1) {
                    Map.Entry<UUID, Double> worst = buildSorted.get(buildSorted.size() - 1);
                    broadcastRaw("&7—— 建筑评分最低 —— &c" + name(worst.getKey()) + " &7- &6" + fmt(worst.getValue()) + " 分");
                }
            }

            List<UUID> firsts = new ArrayList<>();
            List<UUID> seconds = new ArrayList<>();
            List<UUID> thirds = new ArrayList<>();
            if (sorted.size() >= 1 && sorted.get(0).getValue() > 0) firsts.add(sorted.get(0).getKey());
            if (sorted.size() >= 2 && sorted.get(1).getValue() > 0) seconds.add(sorted.get(1).getKey());
            if (sorted.size() >= 3 && sorted.get(2).getValue() > 0) thirds.add(sorted.get(2).getKey());
            Top3.broadcast(msg -> broadcastRaw(msg), id -> name(id) + " &7(" + scores.getOrDefault(id, 0) + " 分)",
                    firsts, seconds, thirds);
            if (data != null) {
                Top3.award(data, firsts, seconds, thirds);
                try {
                    PlayerStats.save("gtb", data);
                } catch (java.io.IOException ex) {
                    // 保存失败不阻塞结算，但要留痕（R3 失败可见）
                    com.hub.diagnostics.Failures.recordAndWarn("GuessTheBuild.保存战绩", ex);
                }
            }
            broadcastRaw("&6============================================");
        }

        for (UUID u : new ArrayList<>(players)) { // 副本遍历：回调链上有代码会改动 players
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                PlayerState st = states.get(u);
                if (st != null) {
                    st.apply(p);
                }
                HubAPI.clearGame(u);
                // 游戏结束：调回生存模式并送回主城（apply 恢复状态，teleportToLobby 传送到 GameHub 大厅并给菜单道具）
                p.setGameMode(GameMode.SURVIVAL);
                HubAPI.teleportToLobby(p);
            }
        }

        players.clear();
        names.clear();
        states.clear();
        scores.clear();
        lastGuess.clear();
        guessedRound.clear();
        midGameJoins.clear();
        buildRotation.clear();
        evalScores.clear();
        buildScores.clear();
        lastCustomWord = null;
        usedWords.clear();
        updateScoreboard();
    }

    /** 服务端关闭时调用：不播报，只还原玩家状态。 */
    public synchronized void restoreAllOnDisable() {
        awaitingCustomWord = false;
        pendingCustomWord = null;
        customRound = false;
        lastCustomWord = null;
        evalScores.clear();
        buildScores.clear();
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                PlayerState st = states.get(u);
                if (st != null) {
                    st.apply(p);
                }
            }
        }
        cancelEndTask();
        cancelHintTask();
    }

    // ---------- 猜词 ----------

    private volatile long lastChatMs = 0;
    private volatile String lastChatKey = "";

    /** 聊天事件调用（异步线程），只做判定，具体处理调度到主线程。 */
    public synchronized void handleChat(Player p, String message) {
        // Paper 桥接会同时触发 AsyncChatEvent 与 legacy AsyncPlayerChatEvent，
        // 同一条消息会被调用两次；300ms 内同玩家同内容去重，避免自创词被“输一次即确认”
        long now = System.currentTimeMillis();
        String key = p.getUniqueId() + "|" + message;
        if (now - lastChatMs < 300 && lastChatKey.equals(key)) {
            return;
        }
        lastChatMs = now;
        lastChatKey = key;
        if (phase != Phase.PLAYING) {
            return;
        }
        if (evaluating) {
            // 评价阶段：0-10 数字当作评分（每人一次），其余聊天照常公屏
            int sc = parseScore(message);
            if (sc >= 0) {
                int finalSc = sc;
                Bukkit.getScheduler().runTask(plugin, () -> handleEvalScore(p, finalSc));
                return;
            }
            return;
        }
        if (selectingTheme) {
            // 建造者聊天输入 1~5 或题目名来选择题目
            if (p.getUniqueId().equals(builder)) {
                String s = message.trim();
                boolean numeric = s.matches("[1-9]");
                boolean named = themeCandidates.stream().anyMatch(w -> w.word().equalsIgnoreCase(s));
                if (numeric || named) {
                    Bukkit.getScheduler().runTask(plugin, () -> selectTheme(p, s));
                }
            }
            return;
        }
        if (awaitingCustomWord) {
            // 建造者输入自创词（两次相同确认）；消息已被 onChat 屏蔽，其他玩家看不到
            if (p.getUniqueId().equals(builder)) {
                String s = message.trim();
                Bukkit.getScheduler().runTask(plugin, () -> handleCustomWordInput(p, s));
            }
            return;
        }
        if (currentWord == null) {
            return;
        }
        UUID id = p.getUniqueId();
        if (!players.contains(id) || id.equals(builder) || guessedRound.contains(id)) {
            return;
        }
        if (!normalize(message).equals(normalize(currentWord))) {
            return;
        }
        long cooldown = plugin.getConfig().getInt("game.guess-cooldown-ms", 1500);
        Long last = lastGuess.get(id);
        if (last != null && System.currentTimeMillis() - last < cooldown) {
            return;
        }
        Player target = p;
        Bukkit.getScheduler().runTask(plugin, () -> processCorrect(target));
    }

    // ---------- 评价阶段评分 ----------

    /** 消息是否为评分消息（评价阶段猜测者输入 0-10，可带“分”字）——供公屏拦截判断（可异步调用）。 */
    public boolean isEvalScoreMessage(String message) {
        return phase == Phase.PLAYING && evaluating && parseScore(message) >= 0;
    }

    /** 解析 0-10 评分输入：纯数字或带“分”字；无效返回 -1。 */
    private int parseScore(String message) {
        if (message == null || message.isEmpty()) {
            return -1;
        }
        String s = normalize(message).replace("分", "");
        if (s.isEmpty() || s.length() > 2 || !s.matches("\\d{1,2}")) {
            return -1;
        }
        int v = Integer.parseInt(s);
        return v >= 0 && v <= 10 ? v : -1;
    }

    /** 评价阶段猜测者评分（主线程调用）：每人每轮一次，公屏公开分数。 */
    private void handleEvalScore(Player p, int score) {
        if (phase != Phase.PLAYING || !evaluating || builder == null) {
            return;
        }
        UUID id = p.getUniqueId();
        if (!players.contains(id) || id.equals(builder)) {
            return;
        }
        Integer prev = evalScores.get(id);
        if (prev != null) {
            Chat.send(p, "&c你本论已评过分（&e" + prev + "&c 分），每人只能打一次分。");
            return;
        }
        evalScores.put(id, score);
        broadcast("&e" + p.getName() + "&f 给建造者 &6" + name(builder) + "&f 打了 &a" + score + "&f 分");
    }

    /** 建造者输入自创词：连续两次相同则确定为题目（主线程调用）。 */
    private void handleCustomWordInput(Player p, String raw) {
        if (phase != Phase.PLAYING || !awaitingCustomWord || builder == null
                || !p.getUniqueId().equals(builder)) {
            return;
        }
        String word = normalize(raw);
        if (word.isEmpty()) {
            Chat.send(p, "&c输入不能为空，请重新输入自创词。");
            return;
        }
        int maxLen = plugin.getConfig().getInt("game.max-word-length", 5);
        if (word.length() > maxLen) {
            Chat.send(p, "&c题目最多 " + maxLen + " 个字，请重新输入。");
            return;
        }
        if (plugin.getWordManager().isSelfmadeWordBanned(word)) {
            Chat.send(p, "&c该词已在自创词黑名单中（评分过低），请换一个自创词。");
            return;
        }
        if (pendingCustomWord == null) {
            pendingCustomWord = word;
            Chat.send(p, "&a已记录：&e" + word + "&a。请再次输入相同的词语确认。");
            return;
        }
        if (pendingCustomWord.equals(word)) {
            awaitingCustomWord = false;
            pendingCustomWord = null;
            cancelEndTask();
            customRound = true;
            broadcast("&e" + name(builder) + "&f 已确定自创题目，开始建造！");
            startBuildRound(new Word(word, "自创"));
        } else {
            pendingCustomWord = null;
            Chat.send(p, "&c两次输入不一致，请重新输入（连续两次相同即确定）。");
        }
    }

    private synchronized void processCorrect(Player p) {
        UUID id = p.getUniqueId();
        if (phase != Phase.PLAYING || currentWord == null || !players.contains(id)
                || id.equals(builder) || guessedRound.contains(id)) {
            return;
        }
        guessedRound.add(id);
        lastGuess.put(id, System.currentTimeMillis());

        long hintMs = plugin.getConfig().getInt("game.hint-time-seconds", 60) * 1000L;
        // "提示前猜中"以首字提示实际是否发送为准，与提示时机保持一致
        boolean early = hintMs > 0 && !hintSent;
        // 按猜中顺序给分：第 1/2/3 个分别为 20/15/10，其余 5（可配置）
        java.util.List<Integer> order = plugin.getConfig().getIntegerList("game.guess-order-scores");
        if (order.isEmpty()) {
            order = java.util.List.of(20, 15, 10, 5);
        }
        int rank = guessedRound.size(); // add 已执行，size 即当前是第几个猜对
        int reward = rank < order.size() ? order.get(rank) : order.get(order.size() - 1);
        int bonus = early ? plugin.getConfig().getInt("game.early-bonus", 5) : 0;
        scores.merge(id, reward + bonus, Integer::sum);

        broadcast("&a" + p.getName() + "&f 猜对了！&7（第 " + rank + " 个猜中，+" + reward
                + (bonus > 0 ? "，提示前猜中 +" + bonus : "") + " 分）");

        long guessers = players.stream().filter(u -> !u.equals(builder)).count();
        if (guessedRound.size() >= guessers && guessers > 0) {
            broadcast("&a全部猜中！本轮提前结束。");
            endRound();
        } else {
            maybeShortenRound();
            updateScoreboard();
        }
    }

    /**
     * 猜对人数超过一半且剩余时间超过30秒时缩短至30秒；
     * 超过三分之二直接缩短至10秒（只缩不延）。
     */
    private void maybeShortenRound() {
        if (phase != Phase.PLAYING || currentWord == null || builder == null) {
            return;
        }
        int guessers = players.size() - 1;
        if (guessers <= 0) {
            return;
        }
        int correct = guessedRound.size();
        long now = System.currentTimeMillis();
        // 达到半数 / 三分之二就触发，且无论时间是否真正变化都公屏提示
        if (correct * 3 >= guessers * 2 && shortenStage < 2) {
            shortenStage = 2;
            long target = now + 10_000;
            if (roundEndMs > target) {
                roundEndMs = target;
                broadcast("&e已有超过三分之二的玩家猜中，剩余时间缩短至 &610&e 秒！");
                rescheduleEnd(10);
            } else {
                broadcast("&e已有超过三分之二的玩家猜中，剩余时间不足 10 秒，本轮即将结束！");
            }
        } else if (correct * 2 >= guessers && shortenStage < 1) {
            shortenStage = 1;
            long target = now + 30_000;
            if (roundEndMs > target) {
                roundEndMs = target;
                broadcast("&e已有超过一半的玩家猜中，剩余时间缩短至 &630&e 秒！");
                rescheduleEnd(30);
            } else {
                broadcast("&e已有超过一半的玩家猜中，剩余时间不足 30 秒，本轮即将结束！");
            }
        }
    }

    private void rescheduleEnd(long seconds) {
        cancelEndTask();
        endTask = Bukkit.getScheduler().runTaskLater(plugin, this::endRound, seconds * 20L);
    }

    /** 是否为当前建造者（用于方块保护判定）。 */
    public boolean isBuilder(UUID id) {
        return builder != null && builder.equals(id);
    }

    /** 是否为正在选题目的建造者（聊天不公屏，防候选泄露）。 */
    public boolean isBuilderChoosing(UUID id) {
        return phase == Phase.PLAYING && builder != null && builder.equals(id)
                && (selectingTheme || awaitingCustomWord);
    }

    /** 文本是否为当前正确答案（仅用于聊天公屏拦截判断）。 */
    public boolean isCorrectAnswer(String message) {
        if (phase != Phase.PLAYING || currentWord == null || message == null) {
            return false;
        }
        return normalize(message).equals(normalize(currentWord));
    }

    // ---------- 每秒 tick ----------

    private synchronized void tick() {
        if (phase == Phase.PLAYING) {
            enforceStates();
        }
        updateScoreboard();
    }
    /**
     * ActionBar 常驻显示（每 5 tick 刷新）：
     * 建造者看题目+剩余时间；猜测者看对应字数的下划线，提示出现后把第一个下划线换成提示的字。
     */
    private synchronized void updateActionBar() {
        if (phase != Phase.PLAYING) {
            return;
        }
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.isOnline()) {
                continue;
            }
            if (u.equals(builder)) {
                if (evaluating) {
                    p.sendActionBar(Chat.color("&7等待玩家评分…"));
                    continue;
                }
                if (currentWord == null) {
                    p.sendActionBar(Chat.color("&7正在等待选择题目…"));
                    continue;
                }
                long remain = Math.max(0, (roundEndMs - System.currentTimeMillis()) / 1000);
                p.sendActionBar(Chat.color("&6题目：&e" + currentWord
                        + " &7（" + currentCategory() + "）&f ｜ 剩余 &e" + remain + "&f 秒"));
            } else {
                if (evaluating) {
                    Integer mine = evalScores.get(u);
                    p.sendActionBar(Chat.color(mine != null
                            ? "&a你已评分：&e" + mine + "&7 分"
                            : "&e在聊天框输入 0~10 打分&7（每人一次）"));
                    continue;
                }
                if (currentWord == null) {
                    p.sendActionBar(Chat.color("&7建造者正在选择题目…"));
                    continue;
                }
                p.sendActionBar(Chat.color(hintLine()));
            }
        }
    }

    /** 猜测者看到的提示行：提示前为 N 个下划线，提示后首字替换为提示字。 */
    private String hintLine() {
        StringBuilder sb = new StringBuilder("&7答案字数：&f");
        for (int i = 0; i < wordLen; i++) {
            // 单字题不替换首字（首字就是答案，不能直接暴露）
            if (i == 0 && hintSent && wordLen > 1) {
                sb.append("&e").append(currentWord.charAt(0)).append("&f ");
            } else {
                sb.append("_ ");
            }
        }
        return sb.toString();
    }

    private String currentCategory() {
        Word w = plugin.getWordManager().find(currentWord);
        return w == null ? "未知" : w.category();
    }


    /** 每 1 秒强制玩家处于正确模式/位置，防止用命令逃出游戏或乱跑。 */
    private void enforceStates() {
        Arena arena = plugin.getArena();
        int buildHeight = plugin.getConfig().getInt("game.max-build-height", 40);
        int margin = plugin.getConfig().getInt("game.build-margin", 5);

        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.isOnline()) {
                continue;
            }
            if (evaluating) {
                // 评价阶段：建造者生存、观众创造，全程保持，不限制位置
                if (u.equals(builder)) {
                    if (p.getGameMode() != GameMode.SURVIVAL) {
                        p.setGameMode(GameMode.SURVIVAL);
                    }
                    p.setInvulnerable(false); // 建造者可被鸡蛋/雪球命中
                } else {
                    if (p.getGameMode() != GameMode.CREATIVE) {
                        p.setGameMode(GameMode.CREATIVE);
                    }
                    p.setInvulnerable(true);
                }
                p.setFireTicks(0);
                continue;
            }
            if (u.equals(builder)) {
                if (p.getGameMode() != GameMode.CREATIVE) {
                    p.setGameMode(GameMode.CREATIVE);
                }
                p.setInvulnerable(true);
                p.setFireTicks(0);
                Location loc = p.getLocation();
                boolean outOfBounds = !arena.containsXZ(loc, margin)
                        || loc.getY() > arena.getMax().getY() + buildHeight
                        || loc.getY() < arena.getMin().getY() - 5; // 掉到地板以下：拉回，防掉虚空
                if (outOfBounds) {
                    // 越界或飞太高：拉回场地中央（而不是大厅，避免出生点保护与来回拉回）
                    p.teleport(arena.getBuildStart());
                }
            } else {
                if (p.getGameMode() != GameMode.SPECTATOR) {
                    p.setGameMode(GameMode.SPECTATOR);
                }
                p.setInvulnerable(true);
                p.setFireTicks(0);
                // 猜测者旁观自由移动，不做位置拉回
            }
        }
    }

    // ---------- 自创词黑名单 ----------

    /** 该玩家是否在自创词黑名单中（自创词无人答对后被加入，无法再选“自创词”）。 */
    public boolean isSelfmadeBanned(UUID id) {
        return plugin.getConfig().getStringList("game.selfmade-blacklist").contains(id.toString());
    }

    public void addSelfmadeBan(UUID id) {
        List<String> list = new ArrayList<>(plugin.getConfig().getStringList("game.selfmade-blacklist"));
        if (!list.contains(id.toString())) {
            list.add(id.toString());
            plugin.getConfig().set("game.selfmade-blacklist", list);
            plugin.saveConfig();
        }
    }

    public void removeSelfmadeBan(UUID id) {
        List<String> list = new ArrayList<>(plugin.getConfig().getStringList("game.selfmade-blacklist"));
        if (list.remove(id.toString())) {
            plugin.getConfig().set("game.selfmade-blacklist", list);
            plugin.saveConfig();
        }
    }

    public List<String> getSelfmadeBans() {
        return plugin.getConfig().getStringList("game.selfmade-blacklist");
    }

    // ---------- 战绩（第一名次数） ----------

    /** 玩家获得一次第一（每局最高分 +1）。 */
    public void recordFirst(UUID id) {
        if (data == null) {
            return;
        }
        data.set("firsts." + id, getFirsts(id) + 1);
        try {
            PlayerStats.save("gtb", data);
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("保存战绩失败：" + ex.getMessage());
        }
    }

    /** 玩家获得第一名的次数。 */
    public int getFirsts(UUID id) {
        return data == null ? 0 : data.getInt("firsts." + id, 0);
    }

    /** OP 清空全部第一名记录。 */
    public void resetFirsts() {
        if (data != null) {
            data.set("firsts", null);
            try {
                PlayerStats.save("gtb", data);
            } catch (java.io.IOException ex) {
                plugin.getLogger().warning("保存战绩失败：" + ex.getMessage());
            }
        }
    }

    // ---------- 计分板 ----------

    private void updateScoreboard() {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("gtb", Criteria.DUMMY, Chat.color("&6&l建筑猜猜乐"));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        int line = 15;
        if (phase == Phase.PLAYING) {
            setScore(obj, line--, "&7玩家：&f" + players.size() + " 人");
            setScore(obj, line--, "&7轮次：&f" + round + "/" + totalRounds);
            if (currentWord != null) {
                long remain = Math.max(0, (roundEndMs - System.currentTimeMillis()) / 1000);
                setScore(obj, line--, " &r");
                setScore(obj, line--, "&7剩余时间：&f" + remain + "s");
                setScore(obj, line--, "&7建造者：&f" + name(builder));
                setScore(obj, line--, "&7答案字数：&f" + wordLen);
                setScore(obj, line--, " &r");
                setScore(obj, line--, "&7— 得分 —");
                List<Map.Entry<UUID, Integer>> top = new ArrayList<>(scores.entrySet());
                top.sort((a, b) -> b.getValue() - a.getValue());
                for (int i = 0; i < Math.min(5, top.size()); i++) {
                    Map.Entry<UUID, Integer> e = top.get(i);
                    setScore(obj, line--, "&e" + name(e.getKey()) + "&7: &f" + e.getValue());
                }
            } else if (selectingTheme) {
                setScore(obj, line--, "&7建造者选题目中…");
                setScore(obj, line--, "&7剩余时间：&f" + Math.max(0, (roundEndMs - System.currentTimeMillis()) / 1000) + "s");
            } else {
                setScore(obj, line--, "&7准备下一轮…");
            }
        } else {
            setScore(obj, line--, "&7等待开始");
            setScore(obj, line--, "&7人数：&f" + players.size() + "/" + plugin.getConfig().getInt("game.max-players", 16));
            setScore(obj, line--, " &r");
            setScore(obj, line--, "&7/gtb join 加入");
            setScore(obj, line--, "&7/gtb start 开始");
        }
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                p.setScoreboard(sb);
            }
        }
    }

    private void setScore(Objective obj, int score, String text) {
        obj.getScore(text.replace('&', '§')).setScore(score);
    }

    private String underscores(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append("_ ");
        }
        return sb.toString().trim();
    }

    private void cancelEndTask() {
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
    }

    private void cancelHintTask() {
        if (hintTask != null) {
            hintTask.cancel();
            hintTask = null;
        }
    }

    private String name(UUID uuid) {
        String n = names.get(uuid);
        if (n != null) {
            return n;
        }
        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName() != null ? op.getName() : "玩家";
    }

    private void broadcast(String msg) {
        broadcastToPlayers(msg, true);
    }

    /** 不带前缀的局内播报（结算名次用），只发给本局玩家。 */
    private void broadcastRaw(String msg) {
        broadcastToPlayers(msg, false);
    }

    private void broadcastToPlayers(String msg, boolean prefix) {
        for (UUID id : new ArrayList<>(players)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                if (prefix) {
                    Chat.send(p, msg);
                } else {
                    Chat.sendRaw(p, msg);
                }
            }
        }
    }

    /** 去掉空格与中英文标点后的小写文本，用于猜词匹配。 */
    static String normalize(String s) {
        return s.toLowerCase().replaceAll(NORMALIZE_REGEX, "");
    }
}
