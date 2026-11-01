package com.animalslaughter;

import com.hub.session.EndReason;

import com.destroystokyo.paper.entity.Pathfinder;
import com.hub.api.ArcadeBridge;
import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.data.PlayerStats;
import com.hub.util.Chat;
import com.hub.util.StartCountdown;
import com.hub.util.Top3;
import com.hub.util.WaitCountdown;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 动物屠宰核心：等待/开局倒计时、圆形场地刷动物、固定 1 点伤害计分、干扰项扣分、限时排名。
 */
public final class AnimalSlaughterManager {

    public static final String GAME = "动物屠宰";
    /** 单场地游戏在 HubCore 菜单里显示的场地名。 */
    public static final String ARENA = "主场地";

    public enum State {
        WAITING, COUNTDOWN, RUNNING
    }

    private static final List<String> KINDS = List.of("chicken", "pig", "cow");

    private final AnimalSlaughterPlugin plugin;
    private final AnimalSlaughterArena arena;
    private final NamespacedKey returnKey;
    private final NamespacedKey startKey;
    private final NamespacedKey swordKey;
    private final NamespacedKey pointsKey;
    private final NamespacedKey decoyKey;
    private final NamespacedKey kindKey;
    private final NamespacedKey dropKey;
    private final WaitCountdown countdown;
    private final Random random = new Random();

    private State state = State.WAITING;
    /** D22：会话层统一按场地键索引（单图游戏只有一个键）。 */
    private final java.util.Map<String, AnimalSlaughterSession> sessions = new java.util.LinkedHashMap<>();
    private String pendingEndMessage;
    private AnimalSlaughterSession session;

    private AnimalSlaughterSession ensureSession() {
        String key = "main";
        AnimalSlaughterSession s = sessions.get(key);
        if (s == null || s.state().isFinal()) {
            s = new AnimalSlaughterSession(this, key);
            sessions.put(key, s);
        }
        session = s;
        return s;
    }

    private final Set<UUID> waiting = new LinkedHashSet<>();
    private final Set<UUID> playing = new LinkedHashSet<>();
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();

    /** 本插件生成、还活着的动物。 */
    private final Set<UUID> mobs = new HashSet<>();
    /** 正在逃跑的动物 -> 逃跑参数。 */
    private final Map<UUID, Flee> fleeing = new HashMap<>();
    /** 本局由动物掉出来、不可拾取的掉落物。 */
    private final Set<UUID> drops = new HashSet<>();

    private BukkitTask countdownTask;
    private BukkitTask gameTask;
    private BukkitTask spawnTask;
    private BukkitTask fleeTask;
    private BukkitTask cleanupTask;
    private int secondsLeft;
    private int readySeconds;

    /** 右侧计分板：所有在场玩家的实时积分。 */
    private Scoreboard sidebar;
    private Objective sidebarObjective;
    private String sidebarTitleEntry;
    private String sidebarHeaderEntry;
    private String sidebarFooterEntry;
    private final Map<UUID, String> sidebarEntries = new HashMap<>();

    private YamlConfiguration stats;

    /** 被攻击后乱走：以被攻击点为锚点，在附近随机换目标点。 */
    private static final class Flee {
        final Location anchor;
        final long until;
        final double speed;
        Location target;
        long nextRepath;

        Flee(Location anchor, long until, double speed) {
            this.anchor = anchor;
            this.until = until;
            this.speed = speed;
        }
    }

    public AnimalSlaughterManager(AnimalSlaughterPlugin plugin) {
        this.plugin = plugin;
        this.arena = new AnimalSlaughterArena(plugin);
        this.returnKey = new NamespacedKey(plugin, "return_item");
        this.startKey = new NamespacedKey(plugin, "start_item");
        this.swordKey = new NamespacedKey(plugin, "knife");
        this.pointsKey = new NamespacedKey(plugin, "mob_points");
        this.decoyKey = new NamespacedKey(plugin, "mob_decoy");
        this.kindKey = new NamespacedKey(plugin, "mob_kind");
        this.dropKey = new NamespacedKey(plugin, "game_drop");
        this.countdown = new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return new ArrayList<>(waiting);
            }

            @Override
            public int minPlayers() {
                return AnimalSlaughterManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return AnimalSlaughterManager.this.maxPlayers();
            }

            @Override
            public boolean hasOpQueued() {
                for (UUID id : waiting) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOp()) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onTick(long secondsLeft) {
                for (UUID id : waiting) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) {
                        Chat.actionBar(p, "&7等待中 &7| &f开始倒计时 &e" + secondsLeft + "s");
                    }
                }
            }

            @Override
            public void onMessage(String message) {
                for (UUID id : waiting) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) {
                        Chat.send(p, message);
                    }
                }
            }

            @Override
            public void onStart() {
                startGame(false);
            }
        });
    }

    // ---------- 配置 ----------

    private int cfgInt(String key, int def) {
        return plugin.getConfig().getInt(key, def);
    }

    private double cfgDouble(String key, double def) {
        return plugin.getConfig().getDouble(key, def);
    }

    public int minPlayers() {
        return Math.max(2, cfgInt("min-players", 2));
    }

    public int maxPlayers() {
        return Math.max(minPlayers(), cfgInt("max-players", 12));
    }

    public int gameSeconds() {
        return Math.max(10, cfgInt("game-seconds", 60));
    }

    private int countdownSeconds() {
        return Math.max(1, Math.min(10, cfgInt("countdown-seconds", 3)));
    }

    private int spawnIntervalTicks() {
        return Math.max(5, cfgInt("mobs.spawn-interval-ticks", 20));
    }

    private int spawnPerCycle() {
        return Math.max(1, Math.min(10, cfgInt("mobs.spawn-per-cycle", 2)));
    }

    private int mobLimit(String kind) {
        return Math.max(0, Math.min(50, cfgInt("mobs.limits." + kind, 8)));
    }

    private int decoyLimit(String kind) {
        return Math.max(0, Math.min(20, cfgInt("mobs.decoy-limits." + kind, 1)));
    }

    private double mobHealth(String kind) {
        return Math.max(1.0, Math.min(100.0, cfgDouble("mobs.health." + kind, 1.0)));
    }

    private int mobScore(String kind) {
        int def = switch (kind) {
            case "pig" -> 3;
            case "cow" -> 5;
            default -> 1;
        };
        return Math.max(0, Math.min(50, cfgInt("mobs.score." + kind, def)));
    }

    private int decoyPercent() {
        return Math.max(1, Math.min(100, cfgInt("mobs.decoy-percent", 50)));
    }

    /** 动物受伤后的无敌帧（tick）；0 = 没有无敌帧。 */
    private int invulnerableTicks() {
        return Math.max(0, Math.min(40, cfgInt("mobs.invulnerable-ticks", 0)));
    }

    private double fleeSpeed() {
        return Math.max(0.1, Math.min(5.0, cfgDouble("flee.speed", 1.5)));
    }

    private double cowFleeSpeed() {
        return Math.max(0.1, Math.min(5.0, cfgDouble("flee.cow-speed", 2.4)));
    }

    private long fleeDurationMs() {
        return Math.max(100, Math.min(10000, cfgInt("flee.duration-ms", 1200)));
    }

    private double fleeWanderRadius() {
        return Math.max(0.5, Math.min(16.0, cfgDouble("flee.wander-radius", 4.0)));
    }

    private long fleeRepathMs() {
        return Math.max(100, Math.min(5000, cfgInt("flee.repath-ms", 400)));
    }

    private int dropClearSeconds() {
        return Math.max(1, Math.min(120, cfgInt("drops.clear-seconds", 8)));
    }

    private boolean sidebarEnabled() {
        return plugin.getConfig().getBoolean("sidebar.enabled", true);
    }

    private String sidebarTitle() {
        return plugin.getConfig().getString("sidebar.title", "&c&l动物屠宰 · 积分");
    }

    private String sidebarHeader() {
        return plugin.getConfig().getString("sidebar.header", "&7积分榜");
    }

    private String sidebarLine() {
        return plugin.getConfig().getString("sidebar.line", "&e{name}");
    }

    private String sidebarFooter() {
        return plugin.getConfig().getString("sidebar.footer", "&7{phase} &e{time}s");
    }

    /** 顶部行右侧的数字；0 = 不显示这一行。 */
    private int sidebarHeaderScore() {
        return Math.max(0, Math.min(10000, cfgInt("sidebar.header-score", 0)));
    }

    /** 底部行右侧的数字；0 = 不显示这一行。 */
    private int sidebarFooterScore() {
        return Math.max(0, Math.min(10000, cfgInt("sidebar.footer-score", 0)));
    }

    /** 配置里写 & 颜色代码，计分板条目只认 §。 */
    private static String legacy(String text) {
        return Chat.toLegacy(Chat.color(text == null ? "" : text));
    }

    // ---------- 生命周期 ----------

    public void load() {
        ensureDefaults();
        arena.load();
        stats = PlayerStats.load("animalslaughter", new File(plugin.getDataFolder(), "data.yml"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HubAPI.registerGame(GAME, GAME, "IRON_SWORD", "c");
            reportArena();
        }, 40L);
        plugin.getLogger().info("场地" + (arena.isReady() ? "已配置" : "未配置（缺 " + arena.missing() + "）") + "。");
    }

    public void reload() {
        if (state != State.WAITING) {
            return;
        }
        load();
    }

    /** 给旧配置补上新增的键（不改已有值）。 */
    private void ensureDefaults() {
        if (plugin.getConfig().getKeys(false).isEmpty()) {
            return;
        }
        boolean changed = false;
        changed |= setDefault("min-players", 2);
        changed |= setDefault("max-players", 12);
        changed |= setDefault("game-seconds", 60);
        changed |= setDefault("countdown-seconds", 3);
        changed |= setDefault("mobs.spawn-interval-ticks", 20);
        changed |= setDefault("mobs.spawn-per-cycle", 2);
        for (String kind : KINDS) {
            changed |= setDefault("mobs.limits." + kind, switch (kind) {
                case "pig" -> 6;
                case "cow" -> 3;
                default -> 10;
            });
            changed |= setDefault("mobs.decoy-limits." + kind, switch (kind) {
                case "pig" -> 1;
                case "cow" -> 1;
                default -> 2;
            });
            changed |= setDefault("mobs.health." + kind, switch (kind) {
                case "pig" -> 2.0;
                case "cow" -> 4.0;
                default -> 1.0;
            });
            changed |= setDefault("mobs.score." + kind, switch (kind) {
                case "pig" -> 3;
                case "cow" -> 5;
                default -> 1;
            });
        }
        changed |= setDefault("mobs.decoy-percent", 50);
        changed |= setDefault("mobs.invulnerable-ticks", 0);
        changed |= setDefault("flee.speed", 1.5);
        changed |= setDefault("flee.cow-speed", 2.4);
        changed |= setDefault("flee.duration-ms", 1200);
        changed |= setDefault("flee.wander-radius", 4.0);
        changed |= setDefault("flee.repath-ms", 400);
        changed |= setDefault("sidebar.enabled", true);
        changed |= setDefault("sidebar.title", "&c&l动物屠宰 · 积分");
        changed |= setDefault("sidebar.header", "&7积分榜");
        changed |= setDefault("sidebar.header-score", 0);
        changed |= setDefault("sidebar.line", "&e{name}");
        changed |= setDefault("sidebar.footer", "&7{phase} &e{time}s");
        changed |= setDefault("sidebar.footer-score", 0);
        changed |= setDefault("drops.clear-seconds", 8);
        changed |= setDefault("arena.center", "");
        changed |= setDefault("arena.radius", 16);
        changed |= setDefault("arena.lobby", "");
        changed |= setDefault("arena.spawns", List.of());
        if (changed) {
            plugin.saveConfig();
        }
    }

    private boolean setDefault(String path, Object value) {
        if (plugin.getConfig().isSet(path)) {
            return false;
        }
        plugin.getConfig().set(path, value);
        return true;
    }

    public void shutdown() {
        cancelTasks();
        removeMobs();
        removeDrops();
        saveAll();
    }

    /** 声明地盘（{@code docs/框架不变量.md} 的 I2）。 */
    public void publishRegions() {
        com.hub.region.Regions.begin("animalslaughter");
        if (arena != null) {
            com.hub.region.Regions.disc("animalslaughter", "", arena.getCenter(), arena.getRadius(),
                    com.hub.region.Regions.DEFAULT_DISC_HEIGHT);
        }
    }

    public void saveAll() {
        publishRegions();   // 场地可能刚被改过，重新声明（I2）
        arena.save();
    }

    public AnimalSlaughterArena arena() {
        return arena;
    }

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public boolean isWaiting(Player player) {
        return waiting.contains(player.getUniqueId());
    }

    public boolean isPlaying(Player player) {
        return playing.contains(player.getUniqueId());
    }

    public boolean isInGame(Player player) {
        return waiting.contains(player.getUniqueId()) || playing.contains(player.getUniqueId());
    }

    public boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission("animal.admin");
    }

    public int waitingCount() {
        return waiting.size();
    }

    public int playingCount() {
        return playing.size();
    }

    public int score(UUID id) {
        return scores.getOrDefault(id, 0);
    }

    public int kills(UUID id) {
        return kills.getOrDefault(id, 0);
    }

    // ---------- 加入 / 离开 ----------

    public void join(Player player) {
        UUID id = player.getUniqueId();
        if (state != State.WAITING) {
            Chat.send(player, "&c对局正在进行中，请等这一局结束后再加入。");
            return;
        }
        if (waiting.contains(id)) {
            Chat.send(player, "&e你已经在等待队列中。");
            return;
        }
        if (waiting.size() >= maxPlayers()) {
            Chat.send(player, "&c等待队列已满（" + maxPlayers() + " 人）。");
            return;
        }
        if (!arena.isReady()) {
            Chat.send(player, "&c场地还没配置完整（缺 " + arena.missing() + "），请联系管理员。");
            return;
        }
        waiting.add(id);
        ensureSession();
        if (session != null && !session.join(player)) {
            plugin.getLogger().warning("[AnimalSlaughter] " + player.getName() + " 加入归属链失败（见 [被吞异常]）");
        }
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        resetPlayer(player);
        player.teleport(arena.getLobby());
        giveWaitItems(player);
        HubAPI.setGame(id, GAME);
        HubAPI.announceJoin(plugin.getName(), player.getName());
        Chat.send(player, "&a已加入" + GAME + "等待队列 &7(" + waiting.size() + "/" + maxPlayers() + ")");
        Chat.send(player, "&7人数达到 &f" + minPlayers() + " &7后自动倒计时开始。");
        updateCountdown();
        reportArena();
    }

    public void leave(Player player, boolean silent) {
        // I1：从归属链上摘掉自己 —— 漏了这行，玩家会卡在"已在等待中"，
        // 之后点任何别的游戏都会被拒绝（只剩重启服务器能救）。
        if (session != null && !session.state().isFinal()) {
            session.leave(player);
        }
        UUID id = player.getUniqueId();
        boolean wasWaiting = waiting.remove(id);
        boolean wasPlaying = playing.remove(id);
        if (!wasWaiting && !wasPlaying) {
            if (!silent) {
                Chat.send(player, "&e你不在等待队列或对局中。");
            }
            return;
        }
        scores.remove(id);
        kills.remove(id);
        clearGameState(player);
        HubAPI.clearGame(id);
        restorePlayer(player);
        if (wasPlaying) {
            removeSidebarEntry(player, id);
        }
        if (!silent) {
            HubAPI.teleportToLobby(player);
            Chat.send(player, "&e你已离开" + GAME + "。");
        }
        if (state == State.RUNNING && wasPlaying && playing.isEmpty()) {
            endGame("&7所有人都离开了，本局结束。");
        } else if (state == State.COUNTDOWN && playing.isEmpty()) {
            cancelTasks();
            state = State.WAITING;
            secondsLeft = 0;
        } else if (state == State.WAITING) {
            updateCountdown();
        }
        reportArena();
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        boolean wasWaiting = waiting.remove(id);
        boolean wasPlaying = playing.remove(id);
        if (!wasWaiting && !wasPlaying) {
            return;
        }
        scores.remove(id);
        kills.remove(id);
        clearGameState(player);
        HubAPI.clearGame(id);
        if (wasPlaying) {
            removeSidebarEntry(player, id);
        }
        if (state == State.RUNNING && wasPlaying && playing.isEmpty()) {
            endGame("&7所有人都离开了，本局结束。");
        } else if (state == State.COUNTDOWN && playing.isEmpty()) {
            cancelTasks();
            state = State.WAITING;
            secondsLeft = 0;
        } else if (state == State.WAITING) {
            updateCountdown();
        }
        reportArena();
    }

    private void updateCountdown() {
        if (state != State.WAITING) {
            countdown.cancel();
            return;
        }
        countdown.update();
    }

    // ---------- 开局 ----------

    public void startGame(boolean force) {
        if (state != State.WAITING) {
            return;
        }
        if (!arena.isReady()) {
            for (UUID id : waiting) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Chat.send(p, "&c场地未配置完整（缺 " + arena.missing() + "），无法开始。");
                }
            }
            return;
        }
        List<UUID> ready = new ArrayList<>();
        for (UUID id : waiting) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                ready.add(id);
            }
        }
        waiting.clear();
        if (ready.isEmpty()) {
            return;
        }
        if (ready.size() < minPlayers() && !force) {
            waiting.addAll(ready);
            updateCountdown();
            return;
        }
        countdown.cancel();
        state = State.COUNTDOWN;
        playing.clear();
        playing.addAll(ready);
        scores.clear();
        kills.clear();
        secondsLeft = gameSeconds();
        readySeconds = countdownSeconds();
        removeMobs();
        removeDrops();

        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            clearGameState(p);
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20);
            p.setFoodLevel(20);
            giveSword(p);
            HubAPI.setPlaying(id, GAME);
        }
        // 尽量让每个人站到不同的出生点
        List<Location> spawnOrder = new ArrayList<>(arena.getSpawns());
        Collections.shuffle(spawnOrder, random);
        int spawnIndex = 0;
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            Location spawn = spawnOrder.isEmpty()
                    ? arena.getLobby()
                    : spawnOrder.get(spawnIndex++ % spawnOrder.size()).clone();
            if (spawn != null) {
                p.teleport(spawn);
                p.setVelocity(new Vector(0, 0, 0));
            }
        }
        HubAPI.setArenaSpectator(GAME, ARENA, arena.spectatorSpot());
        // 开局就把动物刷出来（倒计时期间玩家还不能砍），计时开始后继续补
        for (int i = 0; i < 4; i++) {
            spawnCycle();
        }
        spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::spawnCycle,
                spawnIntervalTicks(), spawnIntervalTicks());
        setupSidebar();
        broadcast("&6&l========== " + GAME + " 开始 ==========");
        broadcast("&f倒计时结束后开始计时 &e" + gameSeconds() + " &f秒，砍动物抢分！");
        broadcast("&7绿色名字是得分动物，&c红色 -" + decoyPercent() + "% &7是干扰项。");
        if (StartCountdown.isSuppressed()) {
            // 街机派对已经用休息阶段计时，别再播一遍开局倒计时
            beginRunning();
        } else {
            showCountdownTitle(readySeconds);
            updateActionBar();
            reportArena();
            countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickCountdown, 20L, 20L);
        }
    }

    private void tickCountdown() {
        if (state != State.COUNTDOWN) {
            cancelCountdownTask();
            return;
        }
        readySeconds--;
        updateSidebar();
        if (readySeconds <= 0) {
            beginRunning();
            return;
        }
        showCountdownTitle(readySeconds);
    }

    private void showCountdownTitle(int seconds) {
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.title(p, "&e游戏即将开始：" + seconds, "&7准备好你的剑", 1);
            }
        }
    }

    private void beginRunning() {
        cancelCountdownTask();
        if (state != State.COUNTDOWN) {
            return;
        }
        state = State.RUNNING;
        if (session != null) {
            session.start();
        }
        secondsLeft = gameSeconds();
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.title(p, "&a&l开始！", "&f砍动物抢分", 2);
            }
        }
        broadcast("&a&l开始！&f时间 &e" + gameSeconds() + " &f秒。");
        updateActionBar();
        updateSidebar();
        reportArena();

        gameTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGame, 20L, 20L);
        fleeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFlee, 2L, 2L);
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickDrops, 20L, 20L);
    }

    public void stopGame() {
        if (state == State.COUNTDOWN) {
            cancelTasks();
            for (UUID id : new ArrayList<>(playing)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    restorePlayer(p);
                    HubAPI.teleportToLobby(p);
                    HubAPI.clearGame(id);
                    Chat.send(p, "&e管理员取消了本局。");
                }
            }
            clearSidebar();
            playing.clear();
            scores.clear();
            kills.clear();
            state = State.WAITING;
            reportArena();
            return;
        }
        if (state == State.RUNNING) {
            endGame("&e管理员结束了本局。");
        }
    }

    /** 收场入口（I4）：先让会话 end()，再由 onEnd 回到 doEndGame 干活。 */
    private void endGame(String reason) {
        pendingEndMessage = reason;
        if (session == null || session.state().isFinal()) {
            doEndGame();
            return;
        }
        session.end(EndReason.ROUND_END);
    }

    /** 真正的收场逻辑（由 session.onEnd 回调）。文案沿用 endGame 传下来的那句。 */
    void doEndGame() {
        String reason = pendingEndMessage != null ? pendingEndMessage : "&7对局结束。";
        pendingEndMessage = null;
        if (state != State.RUNNING && state != State.COUNTDOWN) {
            return;
        }
        cancelTasks();
        removeMobs();
        removeDrops();
        state = State.WAITING;
        secondsLeft = 0;

        broadcast("&6&l========== 游戏结束 ==========");
        if (reason != null && !reason.isBlank()) {
            broadcast(reason);
        }

        List<UUID> ranked = new ArrayList<>(playing);
        ranked.sort(Comparator
                .comparingInt((UUID id) -> score(id)).reversed()
                .thenComparing(Comparator.comparingInt((UUID id) -> kills(id)).reversed())
                .thenComparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));

        int awarded = 0;
        List<UUID> firsts = new ArrayList<>();
        List<UUID> seconds = new ArrayList<>();
        List<UUID> thirds = new ArrayList<>();
        for (UUID id : ranked) {
            if (awarded >= 3 || score(id) <= 0) {
                break;
            }
            int bonus = awarded == 0 ? 3 : (awarded == 1 ? 2 : 1);
            int place = awarded + 1;
            (place == 1 ? firsts : (place == 2 ? seconds : thirds)).add(id);
            recordStat(place == 1 ? "firsts" : (place == 2 ? "seconds" : "thirds"), id);
            addPoints(id, bonus);
            broadcast("&6&l第" + place + "名：&e" + nameOf(id)
                    + " &7(" + score(id) + " 分 / " + kills(id) + " 杀) &6" + Top3.stars(bonus));
            awarded++;
        }
        ArcadeBridge.report("animalslaughter", firsts, seconds, thirds);
        if (awarded == 0) {
            broadcast("&7本局无人得分。");
        }
        broadcast("&6===============================");

        clearSidebar();
        for (UUID id : new ArrayList<>(playing)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.title(p, "&6&l游戏结束", "&f你的得分：" + score(id), 2);
                restorePlayer(p);
                HubAPI.teleportToLobby(p);
            }
            HubAPI.clearGame(id);
        }
        playing.clear();
        scores.clear();
        kills.clear();
        updateCountdown();
        reportArena();
    }

    // ---------- 每局循环 ----------

    private void tickGame() {
        if (state != State.RUNNING) {
            cancelGameTask();
            return;
        }
        secondsLeft--;
        if (secondsLeft <= 0) {
            endGame("&6&l时间到！");
            return;
        }
        if (secondsLeft == 30 || secondsLeft == 10) {
            broadcast("&e剩余 &f" + secondsLeft + " &e秒！");
        }
        updateActionBar();
        updateSidebar();
        reportArena();
    }

    private void updateActionBar() {
        if (state == State.COUNTDOWN) {
            for (UUID id : playing) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Chat.actionBar(p, "&7" + GAME + " &8| &f准备中 &e" + readySeconds + "s");
                }
            }
            return;
        }
        if (state != State.RUNNING) {
            return;
        }
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.actionBar(p, "&7" + GAME + " &8| &f剩余 &e" + secondsLeft + "s"
                        + " &8| &f得分 &e" + score(id));
            }
        }
    }

    /** 玩家掉出场地（没平台可站）时送回出生点。 */
    public void handleMove(Player player, Location to) {
        if (state != State.RUNNING || to == null || !playing.contains(player.getUniqueId())) {
            return;
        }
        Location center = arena.center();
        if (center == null || to.getWorld() == null || !to.getWorld().equals(center.getWorld())) {
            return;
        }
        if (to.getY() < center.getBlockY() - 12) {
            Location spawn = arena.getSpawns().isEmpty() ? center : arena.getSpawns()
                    .get(random.nextInt(arena.getSpawns().size()));
            player.teleport(spawn.clone());
            Chat.send(player, "&e你掉出了场地，已送回出生点。");
        }
    }

    // ---------- 动物 ----------

    private void spawnCycle() {
        if (state != State.RUNNING && state != State.COUNTDOWN) {
            return;
        }
        World world = arena.world();
        if (world == null) {
            return;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (UUID id : new ArrayList<>(mobs)) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof LivingEntity mob) || mob.isDead() || !mob.isValid()) {
                mobs.remove(id);
                fleeing.remove(id);
                continue;
            }
            if (!arena.containsXZ(mob.getLocation())) {
                mob.remove();
                mobs.remove(id);
                fleeing.remove(id);
                continue;
            }
            String signature = signature(mob);
            if (signature != null) {
                counts.merge(signature, 1, Integer::sum);
            }
        }
        int budget = spawnPerCycle();
        for (int i = 0; i < budget; i++) {
            String signature = pickSignature(counts);
            if (signature == null) {
                return;
            }
            if (spawnOne(signature)) {
                counts.merge(signature, 1, Integer::sum);
            }
        }
    }

    private String signature(LivingEntity mob) {
        PersistentDataContainer data = mob.getPersistentDataContainer();
        boolean decoy = data.getOrDefault(decoyKey, PersistentDataType.BYTE, (byte) 0) == 1;
        String kind = data.get(kindKey, PersistentDataType.STRING);
        if (kind == null) {
            return null;
        }
        return (decoy ? "decoy:" : "normal:") + kind;
    }

    /** 在还没到上限的（动物/干扰项）里随机挑一种。 */
    private String pickSignature(Map<String, Integer> counts) {
        List<String> available = new ArrayList<>();
        for (String kind : KINDS) {
            String normal = "normal:" + kind;
            if (counts.getOrDefault(normal, 0) < mobLimit(kind)) {
                available.add(normal);
            }
            String decoy = "decoy:" + kind;
            if (counts.getOrDefault(decoy, 0) < decoyLimit(kind)) {
                available.add(decoy);
            }
        }
        if (available.isEmpty()) {
            return null;
        }
        return available.get(random.nextInt(available.size()));
    }

    private boolean spawnOne(String signature) {
        World world = arena.world();
        if (world == null) {
            return false;
        }
        boolean decoy = signature.startsWith("decoy:");
        String kind = signature.substring(signature.indexOf(':') + 1);
        for (int attempt = 0; attempt < 24; attempt++) {
            Location point = arena.randomPoint(random);
            if (point == null) {
                return false;
            }
            Location ground = arena.groundAt(point.getBlockX(), point.getBlockZ());
            if (ground == null) {
                continue;
            }
            if (!world.getNearbyEntities(ground, 1.0, 1.5, 1.0,
                    entity -> entity instanceof LivingEntity && mobs.contains(entity.getUniqueId())).isEmpty()) {
                continue;
            }
            spawnAnimal(kind, decoy, ground);
            return true;
        }
        return false;
    }

    private void spawnAnimal(String kind, boolean decoy, Location ground) {
        World world = ground.getWorld();
        if (world == null) {
            return;
        }
        EntityType type = switch (kind) {
            case "pig" -> EntityType.PIG;
            case "cow" -> EntityType.COW;
            default -> EntityType.CHICKEN;
        };
        Entity spawned = world.spawnEntity(ground, type);
        if (!(spawned instanceof LivingEntity mob)) {
            return;
        }
        double health = decoy ? 1.0 : mobHealth(kind);
        AttributeInstance maxHealth = mob.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(health);
        }
        mob.setHealth(Math.min(health, mob.getMaxHealth()));
        mob.setMaximumNoDamageTicks(invulnerableTicks());
        mob.setNoDamageTicks(0);
        AttributeInstance knockback = mob.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (knockback != null) {
            knockback.setBaseValue(1.0);
        }
        mob.customName(nameOf(kind, decoy));
        mob.setCustomNameVisible(true);
        mob.setPersistent(false);
        mob.setRemoveWhenFarAway(false);
        mob.setCanPickupItems(false);
        PersistentDataContainer data = mob.getPersistentDataContainer();
        data.set(pointsKey, PersistentDataType.INTEGER, decoy ? 0 : mobScore(kind));
        data.set(decoyKey, PersistentDataType.BYTE, (byte) (decoy ? 1 : 0));
        data.set(kindKey, PersistentDataType.STRING, kind);
        mobs.add(mob.getUniqueId());
    }

    private Component nameOf(String kind, boolean decoy) {
        if (decoy) {
            return Chat.color("&c-" + decoyPercent() + "%");
        }
        return Chat.color("&a+" + mobScore(kind));
    }

    /** 玩家砍到动物：在被打的位置附近乱走（不专门躲玩家）。 */
    public void onMobHit(LivingEntity mob, Player attacker) {
        if (state != State.RUNNING || !mobs.contains(mob.getUniqueId())) {
            return;
        }
        String kind = mob.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        double speed = "cow".equals(kind) ? cowFleeSpeed() : fleeSpeed();
        fleeing.put(mob.getUniqueId(),
                new Flee(mob.getLocation().clone(), System.currentTimeMillis() + fleeDurationMs(), speed));
    }

    private void tickFlee() {
        if (fleeing.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Flee>> iterator = fleeing.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Flee> entry = iterator.next();
            Flee flee = entry.getValue();
            if (now > flee.until) {
                iterator.remove();
                continue;
            }
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof Mob mob) || mob.isDead() || !mob.isValid()) {
                iterator.remove();
                continue;
            }
            if (flee.target == null || now >= flee.nextRepath) {
                flee.target = wanderTarget(flee.anchor, mob.getLocation());
                flee.nextRepath = now + fleeRepathMs();
            }
            Pathfinder pathfinder = mob.getPathfinder();
            if (pathfinder != null && flee.target != null) {
                pathfinder.moveTo(flee.target, flee.speed);
            }
        }
    }

    /** 以被攻击点为锚点随机取一个附近的目标点（夹在圆内，且不要离当前位置太近）。 */
    private Location wanderTarget(Location anchor, Location current) {
        World world = anchor.getWorld();
        if (world == null) {
            return null;
        }
        double radius = fleeWanderRadius();
        Location center = arena.center();
        double maxDistance = arena.getRadius() - 1.5;
        double x = anchor.getX();
        double z = anchor.getZ();
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double dist = Math.sqrt(random.nextDouble()) * radius;
            x = anchor.getX() + Math.cos(angle) * dist;
            z = anchor.getZ() + Math.sin(angle) * dist;
            if (center != null) {
                double cx = center.getBlockX() + 0.5;
                double cz = center.getBlockZ() + 0.5;
                double dx = x - cx;
                double dz = z - cz;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > maxDistance && d > 0.001) {
                    x = cx + dx / d * maxDistance;
                    z = cz + dz / d * maxDistance;
                }
            }
            if (current == null || current.distanceSquared(new Location(world, x, anchor.getY(), z)) > 1.0) {
                break;
            }
        }
        return new Location(world, x, anchor.getY(), z);
    }

    /** 动物死亡：计分/扣分并清理追踪。 */
    public void onMobDeath(LivingEntity mob, Player killer) {
        mobs.remove(mob.getUniqueId());
        fleeing.remove(mob.getUniqueId());
        if (state != State.RUNNING || killer == null) {
            return;
        }
        UUID id = killer.getUniqueId();
        if (!playing.contains(id)) {
            return;
        }
        PersistentDataContainer data = mob.getPersistentDataContainer();
        boolean decoy = data.getOrDefault(decoyKey, PersistentDataType.BYTE, (byte) 0) == 1;
        if (decoy) {
            int before = score(id);
            int loss = (int) Math.ceil(before * decoyPercent() / 100.0);
            int after = Math.max(0, before - loss);
            scores.put(id, after);
            Chat.actionBar(killer, "&c干扰项！&f-" + loss + " 分 &7(共 " + after + " 分)");
        } else {
            int gain = Math.max(0, data.getOrDefault(pointsKey, PersistentDataType.INTEGER, 0));
            scores.merge(id, gain, Integer::sum);
            kills.merge(id, 1, Integer::sum);
            Chat.actionBar(killer, "&a+" + gain + " 分 &7(共 " + score(id) + " 分)");
        }
        updateSidebar();
    }

    public boolean isOurMob(Entity entity) {
        return entity != null && mobs.contains(entity.getUniqueId());
    }

    private void removeMobs() {
        for (UUID id : new ArrayList<>(mobs)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        mobs.clear();
        fleeing.clear();
    }

    // ---------- 掉落物 ----------

    /** 动物掉落物：禁止拾取，登记后由清理任务移除。 */
    public void markDrop(Item item) {
        if (item == null) {
            return;
        }
        item.setCanPlayerPickup(false);
        item.setCanMobPickup(false);
        item.setPickupDelay(32767);
        item.getPersistentDataContainer().set(dropKey, PersistentDataType.BYTE, (byte) 1);
        drops.add(item.getUniqueId());
    }

    public boolean isGameDrop(Item item) {
        return item != null && (drops.contains(item.getUniqueId())
                || item.getPersistentDataContainer().getOrDefault(dropKey, PersistentDataType.BYTE, (byte) 0) == 1);
    }

    private void tickDrops() {
        // 兜底：场地内出现的掉落物都算本局掉落（玩家丢东西已被取消），统一禁拾取+计时清理
        World world = arena.world();
        Location center = arena.center();
        if (world != null && center != null) {
            for (Entity entity : world.getNearbyEntities(center,
                    arena.getRadius() + 4, 24, arena.getRadius() + 4)) {
                if (entity instanceof Item item && arena.containsXZ(item.getLocation()) && !isGameDrop(item)) {
                    markDrop(item);
                }
            }
        }
        if (drops.isEmpty()) {
            return;
        }
        long maxTicks = dropClearSeconds() * 20L;
        Iterator<UUID> iterator = drops.iterator();
        while (iterator.hasNext()) {
            UUID id = iterator.next();
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Item item) || item.isDead() || !item.isValid()) {
                iterator.remove();
                continue;
            }
            if (item.getTicksLived() >= maxTicks) {
                item.remove();
                iterator.remove();
            }
        }
    }

    private void removeDrops() {
        for (UUID id : new ArrayList<>(drops)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        drops.clear();
        World world = arena.world();
        Location center = arena.center();
        if (world != null && center != null) {
            for (Entity entity : world.getNearbyEntities(center,
                    arena.getRadius() + 4, 24, arena.getRadius() + 4)) {
                if (entity instanceof Item item && arena.containsXZ(item.getLocation())) {
                    item.remove();
                }
            }
        }
    }

    // ---------- 出生点 ----------

    /**
     * 自动生成出生点：清空旧的，在圆内随机取点，站在每列最高的非液体方块上。
     * 点与点之间至少间隔 1.5 格。
     */
    public int generateSpawns(int count) {
        arena.clearSpawns();
        if (!arena.hasCircle()) {
            return 0;
        }
        int want = Math.max(1, Math.min(64, count));
        List<Location> points = new ArrayList<>();
        int attempts = 0;
        int maxAttempts = Math.max(200, want * 300);
        while (points.size() < want && attempts++ < maxAttempts) {
            Location point = arena.randomPoint(random);
            if (point == null) {
                break;
            }
            Location ground = arena.groundAt(point.getBlockX(), point.getBlockZ());
            if (ground == null) {
                continue;
            }
            boolean tooClose = false;
            for (Location existing : points) {
                if (existing.distanceSquared(ground) < 1.5 * 1.5) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) {
                continue;
            }
            points.add(ground);
        }
        for (Location point : points) {
            arena.addSpawn(point);
        }
        return points.size();
    }

    // ---------- 道具 / 状态 ----------

    private void giveSword(Player player) {
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = sword.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color("&b&l屠刀 &7(左键攻击)")));
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);
            sword.setItemMeta(meta);
        }
        player.getInventory().setItem(0, sword);
        AttributeInstance attackSpeed = player.getAttribute(Attribute.ATTACK_SPEED);
        if (attackSpeed != null) {
            attackSpeed.setBaseValue(1024.0);
        }
        // 关掉横扫之刃：只砍准星对准的那一只，不会误伤旁边的干扰项
        AttributeInstance sweeping = player.getAttribute(Attribute.SWEEPING_DAMAGE_RATIO);
        if (sweeping != null) {
            sweeping.setBaseValue(0.0);
        }
    }

    private void giveWaitItems(Player player) {
        ItemStack bed = new ItemStack(Material.RED_BED);
        ItemMeta bedMeta = bed.getItemMeta();
        if (bedMeta != null) {
            bedMeta.displayName(Chat.noItalic(Chat.color("&c退出游戏 &7(右键)")));
            bed.setItemMeta(bedMeta);
        }
        player.getInventory().setItem(8, bed);

        if (isAdmin(player)) {
            ItemStack paper = new ItemStack(Material.PAPER);
            ItemMeta paperMeta = paper.getItemMeta();
            if (paperMeta != null) {
                paperMeta.displayName(Chat.noItalic(Chat.color("&a&l开始游戏 &7(右键)")));
                paper.setItemMeta(paperMeta);
            }
            player.getInventory().setItem(4, paper);
        }
    }

    public boolean isReturnItem(ItemStack item) {
        return item != null && item.getType() == Material.RED_BED;
    }

    public boolean isStartItem(ItemStack item) {
        return item != null && item.getType() == Material.PAPER;
    }

    public void handleStartPaper(Player player) {
        if (!isAdmin(player)) {
            Chat.send(player, "&c只有管理员可以开始游戏。");
            return;
        }
        if (!isWaiting(player)) {
            Chat.send(player, "&e你不在等待队列中。");
            return;
        }
        if (state != State.WAITING) {
            Chat.send(player, "&e对局已经在进行中。");
            return;
        }
        if (!arena.isReady()) {
            Chat.send(player, "&c场地未配置完整（缺 " + arena.missing() + "）。");
            return;
        }
        startGame(true);
    }

    private void resetPlayer(Player player) {
        player.setHealth(20);
        player.setFoodLevel(20);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.setExp(0f);
        player.setLevel(0);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
    }

    private void clearGameState(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        resetPlayer(player);
    }

    private void restorePlayer(Player player) {
        resetPlayer(player);
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        AttributeInstance attackSpeed = player.getAttribute(Attribute.ATTACK_SPEED);
        if (attackSpeed != null) {
            attackSpeed.setBaseValue(4.0);
        }
        AttributeInstance sweeping = player.getAttribute(Attribute.SWEEPING_DAMAGE_RATIO);
        if (sweeping != null) {
            sweeping.setBaseValue(1.0);
        }
    }

    // ---------- 右侧计分板 ----------

    private void setupSidebar() {
        if (!sidebarEnabled()) {
            return;
        }
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }
        sidebar = manager.getNewScoreboard();
        sidebarObjective = sidebar.registerNewObjective("animal_score", Criteria.DUMMY,
                Chat.color(sidebarTitle()));
        sidebarObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
        sidebarEntries.clear();
        sidebarTitleEntry = null;
        sidebarHeaderEntry = null;
        sidebarFooterEntry = null;
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.setScoreboard(sidebar);
            }
        }
        updateSidebar();
    }

    private void updateSidebar() {
        if (sidebar == null || sidebarObjective == null) {
            return;
        }
        int time = state == State.COUNTDOWN ? Math.max(0, readySeconds) : Math.max(0, secondsLeft);
        String phase = state == State.COUNTDOWN ? "准备" : "剩余";

        String title = legacy(fillTime(sidebarTitle(), time, phase));
        if (!title.equals(sidebarTitleEntry)) {
            sidebarTitleEntry = title;
            sidebarObjective.displayName(Chat.color(title));
        }

        int headerScore = sidebarHeaderScore();
        String header = legacy(fillTime(sidebarHeader(), time, phase));
        if (headerScore <= 0 || header.isBlank()) {
            if (sidebarHeaderEntry != null) {
                sidebar.resetScores(sidebarHeaderEntry);
                sidebarHeaderEntry = null;
            }
        } else if (!header.equals(sidebarHeaderEntry)) {
            if (sidebarHeaderEntry != null) {
                sidebar.resetScores(sidebarHeaderEntry);
            }
            sidebarHeaderEntry = header;
            sidebarObjective.getScore(header).setScore(headerScore);
        }

        int footerScore = sidebarFooterScore();
        String footer = legacy(fillTime(sidebarFooter(), time, phase));
        if (footerScore <= 0 || footer.isBlank()) {
            if (sidebarFooterEntry != null) {
                sidebar.resetScores(sidebarFooterEntry);
                sidebarFooterEntry = null;
            }
        } else if (!footer.equals(sidebarFooterEntry)) {
            if (sidebarFooterEntry != null) {
                sidebar.resetScores(sidebarFooterEntry);
            }
            sidebarFooterEntry = footer;
            sidebarObjective.getScore(footer).setScore(footerScore);
        }

        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            String template = sidebarLine();
            String entry = legacy(template
                    .replace("{name}", p.getName())
                    .replace("{score}", String.valueOf(score(id)))
                    .replace("{kills}", String.valueOf(kills(id))));
            if (!template.contains("{name}")) {
                // 模板里没有名字的话多人会并成一行，补一个灰色名字保证唯一
                entry = entry + legacy(" &8" + p.getName());
            }
            if (entry.isBlank()) {
                entry = legacy("&e" + p.getName());
            }
            String previous = sidebarEntries.get(id);
            if (!entry.equals(previous)) {
                if (previous != null) {
                    sidebar.resetScores(previous);
                }
                sidebarEntries.put(id, entry);
            }
            sidebarObjective.getScore(entry).setScore(score(id));
        }
    }

    private static String fillTime(String template, int time, String phase) {
        return template == null ? "" : template
                .replace("{time}", String.valueOf(time))
                .replace("{phase}", phase);
    }

    /** 玩家离场：删掉他的行并把计分板还原成主计分板。 */
    private void removeSidebarEntry(Player player, UUID id) {
        String entry = sidebarEntries.remove(id);
        if (entry != null && sidebar != null) {
            sidebar.resetScores(entry);
        }
        restoreSidebar(player);
    }

    /** 结算：所有在场玩家还原主计分板。 */
    private void clearSidebar() {
        for (UUID id : playing) {
            restoreSidebar(Bukkit.getPlayer(id));
        }
        sidebar = null;
        sidebarObjective = null;
        sidebarTitleEntry = null;
        sidebarHeaderEntry = null;
        sidebarFooterEntry = null;
        sidebarEntries.clear();
    }

    private void restoreSidebar(Player player) {
        if (player == null || sidebar == null) {
            return;
        }
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null && player.getScoreboard().equals(sidebar)) {
            player.setScoreboard(manager.getMainScoreboard());
        }
    }

    // ---------- 上报 / 播报 ----------

    private void reportArena() {
        boolean running = state == State.RUNNING || state == State.COUNTDOWN;
        String extra = state == State.COUNTDOWN
                ? "准备中 · " + playing.size() + " 人"
                : (state == State.RUNNING
                ? "剩余 " + secondsLeft + "s · " + playing.size() + " 人"
                : null);
        HubAPI.setArena(GAME, ARENA, running ? GameState.PLAYING : GameState.WAITING,
                waiting.size() + playing.size(), extra);
        HubAPI.setArenaSpectator(GAME, ARENA, arena.spectatorSpot());
    }

    private void broadcast(String message) {
        for (UUID id : waiting) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.send(p, message);
            }
        }
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.send(p, message);
            }
        }
    }

    private String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            return p.getName();
        }
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : id.toString().substring(0, 8);
    }

    // ---------- 任务 ----------

    private void cancelTasks() {
        cancelCountdownTask();
        cancelGameTask();
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
        if (fleeTask != null) {
            fleeTask.cancel();
            fleeTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    private void cancelCountdownTask() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void cancelGameTask() {
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
    }

    // ---------- 战绩 ----------

    public void recordStat(String key, UUID id) {
        if (stats == null) {
            return;
        }
        stats.set(key + "." + id, getStat(key, id) + 1);
        saveStats();
    }

    public int getStat(String key, UUID id) {
        return stats == null ? 0 : stats.getInt(key + "." + id, 0);
    }

    public void addPoints(UUID id, int amount) {
        if (stats == null) {
            return;
        }
        stats.set("points." + id, getPoints(id) + amount);
        saveStats();
    }

    public int getPoints(UUID id) {
        return stats == null ? 0 : stats.getInt("points." + id, 0);
    }

    public void resetStats() {
        if (stats != null) {
            stats.set("firsts", null);
            stats.set("seconds", null);
            stats.set("thirds", null);
            stats.set("points", null);
            saveStats();
        }
    }

    private void saveStats() {
        if (stats == null) {
            return;
        }
        try {
            PlayerStats.save("animalslaughter", stats);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存玩家记录失败：" + ex.getMessage());
        }
    }
}
