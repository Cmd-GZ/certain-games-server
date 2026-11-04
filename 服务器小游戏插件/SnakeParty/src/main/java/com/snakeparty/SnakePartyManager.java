package com.snakeparty;

import com.hub.session.EndReason;

import com.hub.api.ArcadeBridge;
import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.data.PlayerStats;
import com.hub.util.Chat;
import com.hub.util.LiveSidebar;
import com.hub.util.StartCountdown;
import com.hub.util.Top3;
import com.hub.util.WaitCountdown;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 蛇行派对核心：等待/倒计时、沿视线自动前进、脚下留彩色玻璃板、撞板或掉出场即淘汰。
 */
public final class SnakePartyManager {

    public static final String GAME = "蛇行派对";
    public static final String ARENA = "主场地";

    public enum State {
        WAITING, COUNTDOWN, RUNNING
    }

    private final SnakePartyPlugin plugin;
    /**
     * D22：这个实例负责的**房间键**（有房间时 = 房间 id；没有房间时 = {@link #ARENA}）。
     * 每个房间一份实例 = 一份运行时状态，所以两场派对不会互相踩。
     */
    private final String roomKey;
    private final SnakePartyArena arena;
    private final NamespacedKey returnKey;
    private final NamespacedKey startKey;
    private final WaitCountdown countdown;
    /** D21：加入 tick 与同 tick 内随机名次（同时进入的几人随机决出主持人）。 */
    private final java.util.Map<UUID, Long> joinTick = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> joinSeed = new java.util.HashMap<>();
    private final Random random = new Random();
    private final LiveSidebar liveSidebar;
    /** 侧边栏/世界条目的键：每房间一份，否则两个房间会互相覆盖（D22）。 */
    private final String sidebarKey;

    private State state = State.WAITING;
    /** D22：会话层统一按场地键索引（单图游戏只有一个键）。 */
    private final java.util.Map<String, SnakePartySession> sessions = new java.util.LinkedHashMap<>();
    private java.util.Set<UUID> pendingWinners;
    private String pendingEndMessage;
    private SnakePartySession session;

    private SnakePartySession ensureSession() {
        String key = roomKey;   // D22：每房间一个会话（否则第二场派对共用同一份会话）
        SnakePartySession s = sessions.get(key);
        if (s == null || s.state().isFinal()) {
            s = new SnakePartySession(this, key);
            sessions.put(key, s);
        }
        session = s;
        return s;
    }

    private final Set<UUID> waiting = new LinkedHashSet<>();
    private final Set<UUID> playing = new LinkedHashSet<>();
    private final Set<UUID> eliminated = new LinkedHashSet<>();
    private final List<UUID> elimOrder = new ArrayList<>();

    /** 玩家 -> 玻璃板颜色。 */
    private final Map<UUID, Material> colors = new HashMap<>();
    /** 玻璃板：方块坐标 -> 原始方块数据 + 到期时间。 */
    private final Map<String, Plate> plates = new HashMap<>();
    /** 玩家 -> 当前体力（经验条显示）。 */
    private final Map<UUID, Double> stamina = new HashMap<>();
    /** 玩家上一次踩着的脚下方块，避免每 tick 重复判定/铺板。 */
    private final Map<UUID, String> lastFloor = new HashMap<>();
    /** 玩家 -> 已经离开、等着铺板子的格子（离开足够远才铺，避免板子生成在玩家身上）。 */
    private final Map<UUID, List<Pending>> pendingPlates = new HashMap<>();

    private BukkitTask countdownTask;
    private BukkitTask moveTask;
    private BukkitTask plateTask;
    private BukkitTask gameTask;
    private int secondsLeft;
    private int readySeconds;
    private YamlConfiguration stats;

    private static final class Plate {
        final BlockData original;
        /** 到期时间；玩家死亡后改成 Long.MAX_VALUE，整局结束才还原。 */
        long expireAt;
        final int x;
        final int y;
        final int z;
        final UUID owner;

        Plate(BlockData original, long expireAt, int x, int y, int z, UUID owner) {
            this.original = original;
            this.expireAt = expireAt;
            this.x = x;
            this.y = y;
            this.z = z;
            this.owner = owner;
        }
    }

    /** 待铺的玻璃板格子（脚部高度）。 */
    private static final class Pending {
        final int x;
        final int y;
        final int z;

        Pending(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public SnakePartyManager(SnakePartyPlugin plugin) {
        this(plugin, ARENA);
    }

    public SnakePartyManager(SnakePartyPlugin plugin, String roomKey) {
        this.plugin = plugin;
        this.roomKey = roomKey == null || roomKey.isBlank() ? ARENA : roomKey;
        this.sidebarKey = "snakeparty:" + this.roomKey;
        this.liveSidebar = new LiveSidebar(plugin);
        this.arena = new SnakePartyArena(plugin);
        this.returnKey = new NamespacedKey(plugin, "return_item");
        this.startKey = new NamespacedKey(plugin, "start_item");
        this.countdown = new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return new ArrayList<>(waiting);
            }

            @Override
            public int minPlayers() {
                return SnakePartyManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return SnakePartyManager.this.maxPlayers();
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

                java.util.List<UUID> order = new ArrayList<>(waiting);

                order.sort(java.util.Comparator

                        .comparingLong((UUID id) -> joinTick.getOrDefault(id, 0L))

                        .thenComparingLong(id -> joinSeed.getOrDefault(id, 0L)));

                return order;

            }

            @Override

            public void onHostTimeout(UUID host) {

                Player hp = Bukkit.getPlayer(host);

                String hname = hp == null ? host.toString() : hp.getName();

                waiting.remove(host);

                if (session != null && hp != null && !session.state().isFinal()) {

                    session.leave(hp);

                }

                if (hp != null && hp.isOnline()) {

                    Chat.send(hp, "&c倒计时结束还没开始，你被移出本局（主持人超时）。");

                    HubAPI.teleportToLobby(hp);

                }

                plugin.getLogger().info("[SnakeParty] 主持人 " + hname

                        + " 超时未开始，已移出本局（剩余 " + waiting.size() + " 人）");

                broadcast("&e主持人 &f" + hname + " &e超时未开始，已移出本局。");

                countdown.update();

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
        return Math.max(minPlayers(), cfgInt("max-players", 16));
    }

    public int gameSeconds() {
        return Math.max(10, cfgInt("game-seconds", 120));
    }

    private int countdownSeconds() {
        return Math.max(1, Math.min(10, cfgInt("countdown-seconds", 3)));
    }

    private double moveSpeed() {
        return Math.max(0.05, Math.min(1.0, cfgDouble("movement.speed", 0.28)));
    }

    private double boostMultiplier() {
        return Math.max(1.0, Math.min(4.0, cfgDouble("movement.boost-multiplier", 1.7)));
    }

    /** 体力为 0 但仍按住 W 时的速度倍率（比没按 W 的人快一点）。 */
    private double emptyWMultiplier() {
        return Math.max(1.0, Math.min(4.0, cfgDouble("movement.empty-w-multiplier", 1.15)));
    }

    private double staminaMax() {
        return Math.max(1.0, Math.min(10000.0, cfgDouble("stamina.max", 100.0)));
    }

    private double staminaDrainPerSecond() {
        return Math.max(0.0, Math.min(10000.0, cfgDouble("stamina.drain-per-second", 35.0)));
    }

    private double staminaRegenPerSecond() {
        return Math.max(0.0, Math.min(10000.0, cfgDouble("stamina.regen-per-second", 18.0)));
    }

    private double plateLifetimeSeconds() {
        return Math.max(0.5, Math.min(120.0, cfgDouble("plate.lifetime-seconds", 3.0)));
    }

    private int plateGrowthMsPerSecond() {
        return Math.max(0, Math.min(5000, cfgInt("plate.lifetime-growth-ms-per-second", 150)));
    }

    private double plateMaxLifetimeSeconds() {
        return Math.max(plateLifetimeSeconds(),
                Math.min(300.0, cfgDouble("plate.max-lifetime-seconds", 45.0)));
    }

    /** 玩家离开一格至少这么远才铺板子：太快铺会正好卡在玩家身上，转身就算撞板。 */
    private double platePlaceDistance() {
        return Math.max(0.5, Math.min(3.0, cfgDouble("plate.place-distance", 1.2)));
    }

    /** 当前玻璃板的存活时间（随对局进行越来越慢地消失）。 */
    private long plateLifetimeMs() {
        long base = (long) (plateLifetimeSeconds() * 1000L);
        long max = (long) (plateMaxLifetimeSeconds() * 1000L);
        int elapsed = Math.max(0, gameSeconds() - secondsLeft);
        return Math.min(max, base + (long) elapsed * plateGrowthMsPerSecond());
    }

    // ---------- 生命周期 ----------

    public void load() {
        ensureDefaults();
        arena.load();
        stats = PlayerStats.load("snakeparty", new File(plugin.getDataFolder(), "data.yml"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HubAPI.registerGame(GAME, GAME, "GLASS_PANE", "a");
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

    public void shutdown() {
        cancelTasks();
        liveSidebar.hide(sidebarKey);
        restorePlates();
        saveAll();
    }

    /** 声明地盘（{@code docs/框架不变量.md} 的 I2）。 */
    public void publishRegions() {
        if (roomKey.equals(ARENA)) {
            com.hub.region.Regions.begin("snakeparty");   // 只有默认实例负责"清掉上一轮"
        }
        if (arena != null) {
            com.hub.region.Regions.disc("snakeparty", roomKey, arena.getCenter(), arena.getRadius(),
                    com.hub.region.Regions.DEFAULT_DISC_HEIGHT);
        }
    }

    public void saveAll() {
        publishRegions();   // 场地可能刚被改过，重新声明（I2）
        arena.save();
    }

    /** 给旧配置补上新增的键（不改已有值）。 */
    private void ensureDefaults() {
        if (plugin.getConfig().getKeys(false).isEmpty()) {
            return;
        }
        boolean changed = false;
        changed |= setDefault("min-players", 2);
        changed |= setDefault("max-players", 16);
        changed |= setDefault("game-seconds", 120);
        changed |= setDefault("countdown-seconds", 3);
        changed |= setDefault("movement.speed", 0.28);
        changed |= setDefault("movement.boost-multiplier", 1.7);
        changed |= setDefault("movement.empty-w-multiplier", 1.15);
        changed |= setDefault("stamina.max", 100.0);
        changed |= setDefault("stamina.drain-per-second", 35.0);
        changed |= setDefault("stamina.regen-per-second", 18.0);
        changed |= setDefault("plate.lifetime-seconds", 3.0);
        changed |= setDefault("plate.lifetime-growth-ms-per-second", 150);
        changed |= setDefault("plate.max-lifetime-seconds", 45.0);
        changed |= setDefault("plate.place-distance", 1.2);
        changed |= setDefault("plate.colors", List.of("RED", "ORANGE", "YELLOW", "LIME",
                "LIGHT_BLUE", "BLUE", "PURPLE", "MAGENTA", "PINK", "CYAN",
                "WHITE", "GRAY", "BROWN", "GREEN", "BLACK", "LIGHT_GRAY"));
        changed |= setDefault("arena.center", "");
        changed |= setDefault("arena.radius", 16);
        changed |= setDefault("arena.floor-material", "STONE");
        changed |= setDefault("arena.floor-layers", 2);
        changed |= setDefault("arena.lobby", "");
        changed |= setDefault("arena.spawns", List.of());
        // 1.1.0：平台默认改成两层石头；旧配置一次性迁移
        if (plugin.getConfig().getInt("config-version", 0) < 1) {
            plugin.getConfig().set("arena.floor-material", "STONE");
            plugin.getConfig().set("arena.floor-layers", 2);
            plugin.getConfig().set("config-version", 1);
            changed = true;
        }
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

    public SnakePartyArena arena() {
        return arena;
    }

    /** D22：本实例负责的房间键。 */
    public String roomKey() {
        return roomKey;
    }

    /** 菜单/日志里用的场地名：默认实例仍叫「主场地」，房间实例用房间 id。 */
    public String arenaLabel() {
        return roomKey;
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

    /** 这个实例是不是已经空着（没人等、没人在打）：外层据此回收房间实例。 */
    public boolean idle() {
        return state == State.WAITING && waiting.isEmpty() && playing.isEmpty() && eliminated.isEmpty();
    }

    public boolean isInGame(Player player) {
        UUID id = player.getUniqueId();
        return waiting.contains(id) || playing.contains(id) || eliminated.contains(id);
    }

    public boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission("snake.admin");
    }

    public int playingCount() {
        return playing.size();
    }

    public int eliminatedCount() {
        return eliminated.size();
    }

    // ---------- 加入 / 离开 ----------

    /** 发送者是否是当前主持人（D21；仅 flow: host 时成立）。 */
    public boolean isHost(org.bukkit.command.CommandSender sender) {
        if (!"host".equalsIgnoreCase(plugin.getConfig().getString("flow", "upstream"))) {
            return false;
        }
        if (!(sender instanceof Player p)) {
            return false;
        }
        UUID host = countdown.currentHost();
        return host != null && host.equals(p.getUniqueId());
    }

    public int waitingCount() {
        return waiting.size();
    }

    public int minPlayersPublic() {
        return minPlayers();
    }

    /** 玩家加入本房间的等待队列；返回是否真的加进去了（D22：外层按房间路由要用）。 */
    public boolean join(Player player) {
        UUID id = player.getUniqueId();
        if (state != State.WAITING) {
            Chat.send(player, "&c对局正在进行中，请等这一局结束后再加入。");
            return false;
        }
        if (waiting.contains(id)) {
            Chat.send(player, "&e你已经在等待队列中。");
            return false;
        }
        if (waiting.size() >= maxPlayers()) {
            Chat.send(player, "&c等待队列已满（" + maxPlayers() + " 人）。");
            return false;
        }
        if (!arena.isReady()) {
            Chat.send(player, "&c场地还没配置完整（缺 " + arena.missing() + "），请联系管理员。");
            return false;
        }
        // I1（D23）：先挂会话再入队；失败就拒绝，避免"队列里有他、归属链上没他"的半加入
        ensureSession();
        if (session != null && !session.join(player)) {
            Chat.send(player, "&c你正在另一局游戏里，请先退出再加入（右键 &f回主城&c 或输入 &f/hub&c）。");
            plugin.getLogger().warning("[SnakeParty] " + player.getName() + " 已在别的对局，拒绝加入");
            return false;
        }
        waiting.add(id);
        joinTick.put(id, (long) Bukkit.getCurrentTick());
        joinSeed.put(id, java.util.concurrent.ThreadLocalRandom.current().nextLong());
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
        return true;
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
        boolean wasEliminated = eliminated.remove(id);
        if (!wasWaiting && !wasPlaying && !wasEliminated) {
            if (!silent) {
                Chat.send(player, "&e你不在等待队列或对局中。");
            }
            return;
        }
        clearGameState(player);
        HubAPI.clearGame(id);
        restorePlayer(player);
        removePlayerState(id);
        if (!silent) {
            HubAPI.teleportToLobby(player);
            Chat.send(player, "&e你已离开" + GAME + "。");
        }
        if (state == State.RUNNING && wasPlaying && playing.isEmpty()) {
            endGame(new LinkedHashSet<>(), "&7所有人都离开了，本局结束。");
        } else if (state == State.COUNTDOWN && playing.isEmpty()) {
            cancelTasks();
            restorePlates();
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
        eliminated.remove(id);
        if (!wasWaiting && !wasPlaying) {
            return;
        }
        clearGameState(player);
        HubAPI.clearGame(id);
        removePlayerState(id);
        if (state == State.RUNNING && wasPlaying && playing.isEmpty()) {
            endGame(new LinkedHashSet<>(), "&7所有人都离开了，本局结束。");
        } else if (state == State.COUNTDOWN && playing.isEmpty()) {
            cancelTasks();
            restorePlates();
            state = State.WAITING;
            secondsLeft = 0;
        } else if (state == State.WAITING) {
            updateCountdown();
        }
        reportArena();
    }

    private void removePlayerState(UUID id) {
        colors.remove(id);
        lastFloor.remove(id);
        stamina.remove(id);
        pendingPlates.remove(id);
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
        cancelTasks();
        restorePlates();
        state = State.COUNTDOWN;
        playing.clear();
        playing.addAll(ready);
        eliminated.clear();
        elimOrder.clear();
        colors.clear();
        lastFloor.clear();
        pendingPlates.clear();
        secondsLeft = gameSeconds();
        readySeconds = countdownSeconds();
        assignColors();

        List<Location> spawnOrder = new ArrayList<>(arena.getSpawns());
        Collections.shuffle(spawnOrder, random);
        int spawnIndex = 0;
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            clearGameState(p);
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20);
            p.setFoodLevel(20);
            stamina.put(id, staminaMax());
            updateStaminaBar(p, staminaMax(), staminaMax());
            HubAPI.setPlaying(id, GAME);
            Location spawn = spawnOrder.isEmpty()
                    ? arena.getLobby()
                    : spawnOrder.get(spawnIndex++ % spawnOrder.size()).clone();
            if (spawn != null) {
                p.teleport(spawn);
                p.setVelocity(new Vector(0, 0, 0));
                // 记录出生格，正式开跑后从这里开始铺板，轨迹才是完整的一条
                lastFloor.put(id, key(spawn.getBlockX(), spawn.getBlockY() - 1, spawn.getBlockZ()));
            }
        }
        HubAPI.setArenaSpectator(GAME, arenaLabel(), arena.spectatorSpot());
        broadcast("&6&l========== " + GAME + " 开始 ==========");
        broadcast("&f倒计时结束后所有人会沿视线自动前进，脚下会留下自己的玻璃板。");
        broadcast("&7撞到玻璃板（自己的也算）或掉出场地就淘汰，活到最后获胜。");
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
        if (readySeconds <= 0) {
            beginRunning();
            return;
        }
        showCountdownTitle(readySeconds);
        updateActionBar();
    }

    private void showCountdownTitle(int seconds) {
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.title(p, "&e游戏即将开始：" + seconds, "&7准备好，倒计时结束才会前进", 1);
            }
        }
    }

    private void beginRunning() {
        cancelCountdownTask();
        if (state != State.COUNTDOWN) {
            return;
        }
        state = State.RUNNING;
        if (session != null && !session.state().isFinal()) {
            session.start();
        }
        secondsLeft = gameSeconds();
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.title(p, "&a&l开始！", "&f沿视线自动前进", 2);
            }
        }
        broadcast("&a&l开始！&f时间 &e" + gameSeconds() + " &f秒。");
        updateActionBar();
        reportArena();
        moveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickMovement, 1L, 1L);
        plateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPlates, 5L, 5L);
        gameTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGame, 20L, 20L);
    }

    public void stopGame() {
        if (state == State.COUNTDOWN) {
            cancelTasks();
            liveSidebar.hide(sidebarKey);
            restorePlates();
            for (UUID id : new ArrayList<>(playing)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    restorePlayer(p);
                    HubAPI.teleportToLobby(p);
                    HubAPI.clearGame(id);
                    Chat.send(p, "&e管理员取消了本局。");
                }
            }
            playing.clear();
            colors.clear();
            lastFloor.clear();
            pendingPlates.clear();
            state = State.WAITING;
            reportArena();
            return;
        }
        if (state == State.RUNNING) {
            endGame(new LinkedHashSet<>(playing), "&e管理员结束了本局。");
        }
    }

    /** 收场入口（I4）：先让会话 end()，再由 onEnd 回到 doEndGame。 */
    private void endGame(Set<UUID> winners, String reason) {
        pendingWinners = winners;
        pendingEndMessage = reason;
        if (session == null || session.state().isFinal()) {
            doEndGame();
            return;
        }
        session.end(EndReason.ROUND_END);
    }

    /** 真正的收场逻辑。赢家与文案透传，玩家看到的结算不变。 */
    void doEndGame() {
        Set<UUID> winners = pendingWinners;
        String reason = pendingEndMessage != null ? pendingEndMessage : "&7对局结束。";
        pendingWinners = null;
        pendingEndMessage = null;
        if (state != State.RUNNING && state != State.COUNTDOWN) {
            return;
        }
        cancelTasks();
        liveSidebar.hide(sidebarKey);
        restorePlates();
        state = State.WAITING;
        secondsLeft = 0;

        broadcast("&6&l========== 游戏结束 ==========");
        if (reason != null && !reason.isBlank()) {
            broadcast(reason);
        }

        // 前三名：获胜者并列第一；被淘汰的按淘汰顺序倒序补第二、第三
        List<UUID> firsts = new ArrayList<>(winners);
        List<UUID> seconds = new ArrayList<>();
        List<UUID> thirds = new ArrayList<>();
        if (!firsts.isEmpty()) {
            int rank = firsts.size() + 1;
            for (int i = elimOrder.size() - 1; i >= 0 && rank <= 3; i--, rank++) {
                UUID id = elimOrder.get(i);
                if (rank == 2) {
                    seconds.add(id);
                } else if (rank == 3) {
                    thirds.add(id);
                }
            }
            for (UUID id : firsts) {
                recordStat("firsts", id);
                addPoints(id, 3);
            }
            for (UUID id : seconds) {
                recordStat("seconds", id);
                addPoints(id, 2);
            }
            for (UUID id : thirds) {
                recordStat("thirds", id);
                addPoints(id, 1);
            }
            broadcast("&6&l第一名：&e" + namesOf(firsts) + " &6" + Top3.stars(3));
            if (!seconds.isEmpty()) {
                broadcast("&e第二名：&f" + namesOf(seconds) + " &6" + Top3.stars(2));
            }
            if (!thirds.isEmpty()) {
                broadcast("&e第三名：&f" + namesOf(thirds) + " &6" + Top3.stars(1));
            }
        } else {
            broadcast("&6获胜者为: &7无");
        }
        // 不管有没有人获胜都要上报，否则街机派对会一直等不到本局结束
        ArcadeBridge.report("snakeparty", firsts, seconds, thirds);
        broadcast("&6===============================");

        Set<UUID> all = new LinkedHashSet<>();
        all.addAll(playing);
        all.addAll(eliminated);
        for (UUID id : all) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.title(p, winners.contains(id) ? "&a&l获胜！" : "&6&l游戏结束",
                        winners.contains(id) ? "&f你活到了最后" : "&f再接再厉", 2);
                restorePlayer(p);
                HubAPI.teleportToLobby(p);
            }
            HubAPI.clearGame(id);
        }
        playing.clear();
        eliminated.clear();
        elimOrder.clear();
        waiting.clear();
        colors.clear();
        lastFloor.clear();
        pendingPlates.clear();
        updateCountdown();
        reportArena();
    }

    // ---------- 每局循环 ----------

    private void tickMovement() {
        if (state != State.RUNNING) {
            cancelMoveTask();
            return;
        }
        for (UUID id : new ArrayList<>(playing)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                continue;
            }
            Location loc = p.getLocation();
            if (!arena.containsXZ(loc) || loc.getY() < arena.floorY() - 6) {
                eliminate(p, "掉出了场地");
                if (state != State.RUNNING) {
                    return;
                }
                continue;
            }
            p.setSprinting(false);
            double max = staminaMax();
            double current = stamina.getOrDefault(id, max);
            Input input = p.getCurrentInput();
            boolean forward = input != null && input.isForward();
            boolean boosting = forward && current > 0.0;
            double speedMultiplier;
            if (boosting) {
                current = Math.max(0.0, current - staminaDrainPerSecond() / 20.0);
                speedMultiplier = boostMultiplier();
            } else if (forward) {
                // 体力空了但还按着 W：不给加速，但比完全没按 W 的人稍快一点
                speedMultiplier = emptyWMultiplier();
            } else {
                current = Math.min(max, current + staminaRegenPerSecond() / 20.0);
                speedMultiplier = 1.0;
            }
            stamina.put(id, current);
            updateStaminaBar(p, current, max);

            // 用水平朝向（yaw）而不是视线向量：低头看脚时视线向量几乎竖直，
            // 原来会导致水平分量为 0、玩家完全停住。
            double yawRad = Math.toRadians(loc.getYaw());
            Vector dir = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
            if (blockedByPlate(loc, dir)) {
                eliminate(p, "撞到了玻璃板");
                if (state != State.RUNNING) {
                    return;
                }
                continue;
            }
            if (blockedAhead(p, loc, dir)) {
                eliminate(p, "撞到了方块");
                if (state != State.RUNNING) {
                    return;
                }
                continue;
            }
            double speed = moveSpeed() * speedMultiplier;
            Vector velocity = p.getVelocity();
            velocity.setX(dir.getX() * speed);
            velocity.setZ(dir.getZ() * speed);
            p.setVelocity(velocity);
            trackTrail(p, loc);
            flushPending(p);
            if (state != State.RUNNING) {
                return;
            }
        }
    }

    /** 前方半格内是否有玻璃板：按玩家碰撞箱（0.6 宽）判定，贴边也不会穿过去。 */
    private boolean blockedByPlate(Location loc, Vector dir) {
        Location ahead = loc.clone().add(dir.getX() * 0.35, 0, dir.getZ() * 0.35);
        return hitsPlate(ahead);
    }

    /** 玩家碰撞箱覆盖到的格子里是否有本局的玻璃板（脚/头两层）。 */
    private boolean hitsPlate(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        double half = 0.3;
        int y = loc.getBlockY();
        int minX = (int) Math.floor(loc.getX() - half);
        int maxX = (int) Math.floor(loc.getX() + half);
        int minZ = (int) Math.floor(loc.getZ() - half);
        int maxZ = (int) Math.floor(loc.getZ() + half);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (plates.containsKey(key(x, y, z)) || plates.containsKey(key(x, y + 1, z))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 视线前方半格内是否有实心方块（墙/障碍），撞上即淘汰。 */
    private boolean blockedAhead(Player p, Location loc, Vector dir) {
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }
        int x = (int) Math.floor(loc.getX() + dir.getX() * 0.45);
        int z = (int) Math.floor(loc.getZ() + dir.getZ() * 0.45);
        int y = loc.getBlockY();
        return world.getBlockAt(x, y, z).getType().isSolid()
                || world.getBlockAt(x, y + 1, z).getType().isSolid();
    }

    private void updateStaminaBar(Player p, double current, double max) {
        double ratio = max <= 0 ? 0 : Math.max(0.0, Math.min(1.0, current / max));
        p.setExp((float) ratio);
        p.setLevel((int) Math.round(ratio * 100.0));
    }

    private void tickGame() {
        if (state != State.RUNNING) {
            cancelGameTask();
            return;
        }
        secondsLeft--;
        if (secondsLeft <= 0) {
            endGame(new LinkedHashSet<>(playing), "&6&l时间到！&f仍存活的玩家共同获胜。");
            return;
        }
        if (secondsLeft == 30 || secondsLeft == 10) {
            broadcast("&e剩余 &f" + secondsLeft + " &e秒！");
        }
        updateActionBar();
        updateLiveBoard();
        reportArena();
    }

    private void updateLiveBoard() {
        if (state != State.RUNNING) {
            return;
        }
        List<UUID> online = new ArrayList<>();
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                online.add(id);
            }
        }
        online.sort(Comparator.comparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        lines.add(new LiveSidebar.Line("§7存活 §f" + playing.size(), 1));
        for (UUID id : online) {
            lines.add(new LiveSidebar.Line("§e" + nameOf(id), 0));
        }
        liveSidebar.update(sidebarKey, "&a&l蛇行派对 · 存活", online, lines);
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
                        + " &8| &f存活 &e" + playing.size());
            }
        }
    }

    // ---------- 玻璃板 ----------

    private void assignColors() {
        colors.clear();
        List<String> palette = plugin.getConfig().getStringList("plate.colors");
        if (palette.isEmpty()) {
            palette = List.of("RED", "ORANGE", "YELLOW", "LIME", "LIGHT_BLUE", "BLUE",
                    "PURPLE", "MAGENTA", "PINK", "CYAN", "WHITE", "GRAY",
                    "BROWN", "GREEN", "BLACK", "LIGHT_GRAY");
        }
        int index = 0;
        for (UUID id : playing) {
            String colorName = palette.get(index % palette.size());
            Material material = Material.matchMaterial(colorName.toUpperCase(Locale.ROOT) + "_STAINED_GLASS_PANE");
            colors.put(id, material != null ? material : Material.RED_STAINED_GLASS_PANE);
            index++;
        }
    }

    /**
     * 玩家换格时，把刚离开的格子排进待铺队列；斜着跨格时再补一个拐角格，
     * 这样玻璃板是首尾相接的一整条，不会只剩对角相接、看起来断了一节。
     */
    private void trackTrail(Player p, Location loc) {
        UUID id = p.getUniqueId();
        int x = loc.getBlockX();
        int y = loc.getBlockY() - 1;
        int z = loc.getBlockZ();
        String currentKey = key(x, y, z);
        String previousKey = lastFloor.put(id, currentKey);
        if (previousKey == null || currentKey.equals(previousKey)) {
            return;
        }
        String[] parts = previousKey.split(",");
        int px;
        int py;
        int pz;
        try {
            px = Integer.parseInt(parts[0]);
            py = Integer.parseInt(parts[1]);
            pz = Integer.parseInt(parts[2]);
        } catch (RuntimeException ex) {
            return;
        }
        Block previousFloor = blockFromKey(previousKey);
        if (previousFloor == null || !arena.contains(previousFloor)) {
            return;
        }
        queuePane(id, px, py + 1, pz);
        if (px != x && pz != z) {
            // 斜走：补一个与前后两格都正交相邻的拐角格
            double fx = loc.getX() - x;
            if (fx < 0.5) {
                queuePane(id, px, py + 1, z);
            } else {
                queuePane(id, x, py + 1, pz);
            }
        }
    }

    private void queuePane(UUID id, int x, int y, int z) {
        if (plates.containsKey(key(x, y, z))) {
            return;
        }
        List<Pending> list = pendingPlates.computeIfAbsent(id, k -> new ArrayList<>());
        for (Pending q : list) {
            if (q.x == x && q.y == y && q.z == z) {
                return;
            }
        }
        list.add(new Pending(x, y, z));
    }

    /** 玩家离开这格足够远之后才真正放下玻璃板，避免转身时撞到自己刚生成的板。 */
    private void flushPending(Player p) {
        UUID id = p.getUniqueId();
        List<Pending> list = pendingPlates.get(id);
        if (list == null || list.isEmpty()) {
            return;
        }
        Location loc = p.getLocation();
        double min = platePlaceDistance();
        min *= min;
        Iterator<Pending> iterator = list.iterator();
        while (iterator.hasNext()) {
            Pending q = iterator.next();
            double dx = loc.getX() - (q.x + 0.5);
            double dz = loc.getZ() - (q.z + 0.5);
            if (dx * dx + dz * dz < min) {
                continue;
            }
            iterator.remove();
            placePane(id, q);
        }
        if (list.isEmpty()) {
            pendingPlates.remove(id);
        }
    }

    private void placePane(UUID id, Pending q) {
        World world = arena.world();
        if (world == null) {
            return;
        }
        Block pane = world.getBlockAt(q.x, q.y, q.z);
        if (!arena.contains(pane) || !pane.getType().isAir()) {
            return;
        }
        String paneKey = key(q.x, q.y, q.z);
        if (plates.containsKey(paneKey)) {
            return;
        }
        Material color = colors.getOrDefault(id, Material.RED_STAINED_GLASS_PANE);
        Plate plate = new Plate(pane.getBlockData().clone(),
                System.currentTimeMillis() + plateLifetimeMs(), q.x, q.y, q.z, id);
        plates.put(paneKey, plate);
        pane.setBlockData(color.createBlockData(), true);
    }

    private Block blockFromKey(String key) {
        World world = arena.world();
        if (world == null) {
            return null;
        }
        String[] parts = key.split(",");
        try {
            return world.getBlockAt(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void tickPlates() {
        if (plates.isEmpty()) {
            return;
        }
        World world = arena.world();
        if (world == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Plate>> iterator = plates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Plate> entry = iterator.next();
            Plate plate = entry.getValue();
            if (now < plate.expireAt) {
                continue;
            }
            if (plate.y >= world.getMinHeight() && plate.y < world.getMaxHeight()) {
                Block block = world.getBlockAt(plate.x, plate.y, plate.z);
                block.setBlockData(plate.original, true);
            }
            iterator.remove();
        }
    }

    private void restorePlates() {
        World world = arena.world();
        if (world != null) {
            for (Plate plate : plates.values()) {
                if (plate.y >= world.getMinHeight() && plate.y < world.getMaxHeight()) {
                    world.getBlockAt(plate.x, plate.y, plate.z).setBlockData(plate.original, true);
                }
            }
        }
        plates.clear();
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    // ---------- 淘汰 ----------

    /** 玩家进入新格子/出界时的外部入口（由监听器在移动时调用）。 */
    public void handleMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!playing.contains(player.getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (state == State.COUNTDOWN) {
            // 倒计时期间：锁住位置，只允许转头
            event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(),
                    to.getYaw(), to.getPitch()));
            return;
        }
        if (state == State.RUNNING && to.getY() > from.getY() + 0.001) {
            // 不能跳跃：把纵向位移按回原地，保留转头
            event.setTo(new Location(from.getWorld(), to.getX(), from.getY(), to.getZ(),
                    to.getYaw(), to.getPitch()));
            return;
        }
        if (state == State.RUNNING && hitsPlate(to)) {
            eliminate(player, "撞到了玻璃板");
        }
    }

    private void eliminate(Player player, String reason) {
        UUID id = player.getUniqueId();
        if (!playing.remove(id)) {
            return;
        }
        eliminated.add(id);
        elimOrder.add(id);
        lastFloor.remove(id);
        stamina.remove(id);
        // 死亡后保留该玩家已经铺下的玻璃板，直到本局结束
        for (Plate plate : plates.values()) {
            if (id.equals(plate.owner)) {
                plate.expireAt = Long.MAX_VALUE;
            }
        }
        clearGameState(player);
        player.setGameMode(GameMode.SPECTATOR);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        Location spectator = arena.spectatorSpot();
        if (spectator != null) {
            player.teleport(spectator);
        }
        HubAPI.setPlaying(id, GAME);
        Chat.send(player, "&c" + reason + "！&7你现在是旁观者，等待本局结束。");
        broadcast("&e" + player.getName() + " &7" + reason + "（剩余 &e" + playing.size() + "&7 人）");
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.isDead()) {
                player.spigot().respawn();
            }
        });
        reportArena();
        checkWin();
    }

    private void checkWin() {
        if (state != State.RUNNING) {
            return;
        }
        if (playing.isEmpty()) {
            endGame(new LinkedHashSet<>(), "&c所有人都被淘汰了，无人获胜。");
            return;
        }
        if (playing.size() == 1) {
            Set<UUID> winners = new LinkedHashSet<>();
            winners.add(playing.iterator().next());
            endGame(winners, "&6最后一人存活！");
        }
    }

    // ---------- 道具 / 状态 ----------

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
    }

    // ---------- 上报 / 播报 ----------

    private void reportArena() {
        boolean running = state == State.RUNNING || state == State.COUNTDOWN;
        String extra = state == State.COUNTDOWN
                ? "准备中 · " + playing.size() + " 人"
                : (state == State.RUNNING ? "剩余 " + secondsLeft + "s · " + playing.size() + " 人" : null);
        HubAPI.setArena(GAME, arenaLabel(), running ? GameState.PLAYING : GameState.WAITING,
                waiting.size() + playing.size() + eliminated.size(), extra);
        HubAPI.setArenaSpectator(GAME, arenaLabel(), arena.spectatorSpot());
    }

    private void broadcast(String message) {
        Set<UUID> all = new LinkedHashSet<>();
        all.addAll(waiting);
        all.addAll(playing);
        all.addAll(eliminated);
        for (UUID id : all) {
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

    private String namesOf(List<UUID> ids) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) {
            names.add(nameOf(id));
        }
        return String.join("、", names);
    }

    // ---------- 任务 ----------

    private void cancelTasks() {
        cancelCountdownTask();
        cancelMoveTask();
        cancelGameTask();
        if (plateTask != null) {
            plateTask.cancel();
            plateTask = null;
        }
    }

    private void cancelCountdownTask() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void cancelMoveTask() {
        if (moveTask != null) {
            moveTask.cancel();
            moveTask = null;
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
            PlayerStats.save("snakeparty", stats);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存玩家记录失败：" + ex.getMessage());
        }
    }
}
