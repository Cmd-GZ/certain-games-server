package com.firejumper;

import com.hub.session.EndReason;

import com.hub.api.ArcadeBridge;
import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.data.PlayerStats;
import com.hub.util.Chat;
import com.hub.util.LiveSidebar;
import com.hub.util.StartCountdown;
import com.hub.util.WaitCountdown;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** 跃火者：火焰线从一侧横扫到另一侧，玩家靠跳跃提升 II 跳过，最后存活者获胜。 */
public final class FireJumperManager {

    /** D22：会话层按场地键索引（单图游戏只有一个键）。 */
    private final java.util.Map<String, FireJumperSession> sessions = new java.util.LinkedHashMap<>();
    private boolean pendingTimeUp;
    private FireJumperSession session;

    private FireJumperSession ensureSession() {
        String key = "main";
        FireJumperSession s = sessions.get(key);
        if (s == null || s.state().isFinal()) {
            s = new FireJumperSession(this, key);
            sessions.put(key, s);
        }
        session = s;
        return s;
    }


    public static final String GAME = "跃火者";
    public static final String ARENA = "主场地";

    public enum State {
        WAITING, RUNNING, ENDING
    }

    /** 配置里的一种线的出现方式。 */
    private static final class LineType {
        String id = "type";
        boolean enabled = true;
        int fromRound = 1;
        int toRound = 9999;
        int lines = 1;
        int gapTicks;
        double speed = 6.0;
    }

    /** 一条正在移动的火线。 */
    private static final class Wave {
        final int sign;
        final double speedPerTick;
        final double end;
        final double spanCenter;
        final double spanHalf;
        double position;
        double previous;

        Wave(int sign, double speedPerTick, double start, double end, double spanCenter, double spanHalf) {
            this.sign = sign;
            this.speedPerTick = speedPerTick;
            this.position = start;
            this.previous = start;
            this.end = end;
            this.spanCenter = spanCenter;
            this.spanHalf = spanHalf;
        }
    }

    private final FireJumperPlugin plugin;
    private final FireJumperArena arena;
    private final LiveSidebar liveSidebar;
    private final WaitCountdown countdown;
    private final Random random = new Random();
    private final NamespacedKey returnKey;
    private final NamespacedKey startKey;
    private final List<LineType> lineTypes = new ArrayList<>();

    private final Set<UUID> waiting = new LinkedHashSet<>();
    /** D21：加入 tick 与同 tick 内随机名次（同时进入的几人随机决出主持人）。 */
    private final java.util.Map<UUID, Long> joinTick = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> joinSeed = new java.util.HashMap<>();
    private final Set<UUID> playing = new LinkedHashSet<>();
    private final Set<UUID> eliminated = new LinkedHashSet<>();
    private final List<Wave> waves = new ArrayList<>();
    private final List<UUID> winners = new ArrayList<>();

    private State state = State.WAITING;
    private StartCountdown startCountdown;
    private BukkitTask gameTask;
    private YamlConfiguration stats;

    private int secondsLeft;
    private int tickCount;
    private int round;
    private int startDelayTicks;
    private int pendingLines;
    private int spawnCooldownTicks;
    private int roundGapTicks = -1;
    private LineType currentType;
    /** 管理员单人开局测试：不记录胜场、不向街机派对上报名次。 */
    private boolean soloTest;

    public FireJumperManager(FireJumperPlugin plugin) {
        this.plugin = plugin;
        this.arena = new FireJumperArena(plugin);
        this.liveSidebar = new LiveSidebar(plugin);
        this.returnKey = new NamespacedKey(plugin, "return_item");
        this.startKey = new NamespacedKey(plugin, "start_item");
        this.countdown = new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return new ArrayList<>(waiting);
            }

            @Override
            public int minPlayers() {
                return FireJumperManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return FireJumperManager.this.maxPlayers();
            }

            @Override
            public boolean hasOpQueued() {
                for (UUID id : waiting) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.hasPermission("firejumper.admin")) {
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
                org.bukkit.entity.Player p = Bukkit.getPlayer(host);
                String name = p == null ? host.toString() : p.getName();
                waiting.remove(host);
                if (session != null && p != null && !session.state().isFinal()) {
                    session.leave(p);   // I1：归属也要摘，否则他再也进不了别的游戏
                }
                if (p != null && p.isOnline()) {
                    Chat.send(p, "&c倒计时结束还没开始，你被移出本局（主持人超时）。");
                    com.hub.api.HubAPI.teleportToLobby(p);
                }
                plugin.getLogger().info("[FireJumper] 主持人 " + name + " 超时未开始，已移出本局（剩余 "
                        + waiting.size() + " 人）");
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
        return Math.max(15, cfgInt("game-seconds", 180));
    }

    public int jumpBoostLevel() {
        return Math.max(1, Math.min(4, cfgInt("jump-boost-level", 2)));
    }

    private boolean outOfBoundsEliminate() {
        return plugin.getConfig().getBoolean("out-of-bounds-eliminate", true);
    }

    private int endDelayTicks() {
        return Math.max(1, cfgInt("end-delay-seconds", 3)) * 20;
    }

    private int randomRoundGapTicks() {
        double min = Math.max(0, cfgDouble("round-gap-min-seconds", 1.5));
        double max = Math.max(min, cfgDouble("round-gap-max-seconds", 3.0));
        double value = min + (max - min) * random.nextDouble();
        return Math.max(0, (int) Math.round(value * 20));
    }

    private void loadLineTypes() {
        lineTypes.clear();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("line-types")) {
            LineType type = new LineType();
            type.id = string(raw.get("id"), "type-" + (lineTypes.size() + 1));
            type.enabled = bool(raw.get("enabled"), true);
            type.fromRound = Math.max(1, number(raw.get("from-round"), 1));
            type.toRound = Math.max(type.fromRound, number(raw.get("to-round"), 9999));
            type.lines = Math.max(1, number(raw.get("lines"), 1));
            type.gapTicks = Math.max(0, (int) Math.round(decimal(raw.get("gap-seconds"), 0) * 20));
            type.speed = Math.max(0.2, decimal(raw.get("speed"), 6.0));
            lineTypes.add(type);
        }
        if (lineTypes.isEmpty()) {
            LineType type = new LineType();
            type.speed = 6.0;
            lineTypes.add(type);
        }
    }

    private static String string(Object value, String def) {
        return value instanceof String s && !s.isBlank() ? s : def;
    }

    private static boolean bool(Object value, boolean def) {
        return value instanceof Boolean b ? b : def;
    }

    private static int number(Object value, int def) {
        return value instanceof Number n ? n.intValue() : def;
    }

    private static double decimal(Object value, double def) {
        return value instanceof Number n ? n.doubleValue() : def;
    }

    // ---------- 生命周期 ----------

    public void load() {
        ensureDefaults();
        arena.load();
        loadLineTypes();
        stats = PlayerStats.load("firejumper", new File(plugin.getDataFolder(), "data.yml"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HubAPI.registerGame(GAME, GAME, "BLAZE_POWDER", "6");
            reportArena();
        }, 40L);
        plugin.getLogger().info("场地" + (arena.ready() ? "已配置" : "未配置（缺 " + arena.missing() + "）")
                + "：方向 " + arena.direction() + "，地面 Y " + arena.groundY()
                + "，线高 " + arena.lineHeight() + "。");
    }

    /** 旧配置缺少的键自动补上，保证 particles / debug-lines 能在配置文件里看到。 */
    private void ensureDefaults() {
        boolean changed = false;
        changed |= setDefault("particles.type", "FLAME");
        changed |= setDefault("particles.rows", 1);
        changed |= setDefault("particles.row-gap", 0.6);
        changed |= setDefault("particles.spacing", 1.0);
        changed |= setDefault("particles.interval-ticks", 3);
        changed |= setDefault("particles.count", 1);
        changed |= setDefault("debug-lines", false);
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

    public void reload() {
        if (state != State.WAITING) {
            return;
        }
        arena.load();
        loadLineTypes();
        reportArena();
    }

    public void shutdown() {
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
        if (startCountdown != null) {
            startCountdown.cancelAll();
        }
        liveSidebar.hideAll();
        waves.clear();
        waiting.clear();
        playing.clear();
        eliminated.clear();
        state = State.WAITING;
    }

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

    // ---------- 加入 / 离开 ----------

    /** 发送者是否是当前主持人（D21；仅 flow: host 时成立）。 */
    public boolean isHost(org.bukkit.command.CommandSender sender) {
        if (!"host".equalsIgnoreCase(plugin.getConfig().getString("flow", "upstream"))) {
            return false;
        }
        if (!(sender instanceof org.bukkit.entity.Player p)) {
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
        if (!arena.ready()) {
            Chat.send(player, "&c场地还没配置完整（缺 " + arena.missing() + "），请联系管理员。");
            return;
        }
        // I1（D23）：先挂会话再入队；冲突就拒绝，避免半加入
        FireJumperSession sess = ensureSession();
        if (!sess.join(player)) {
            if (sess.players().isEmpty()) {
                sessions.remove("main");
            }
            return;
        }
        waiting.add(id);
        joinTick.put(id, (long) Bukkit.getCurrentTick());
        joinSeed.put(id, java.util.concurrent.ThreadLocalRandom.current().nextLong());
        clearPlayer(player);
        player.setGameMode(GameMode.ADVENTURE);
        player.teleport(arena.lobby());
        giveWaitItems(player);
        HubAPI.setGame(id, GAME);
        HubAPI.announceJoin(plugin.getName(), player.getName());
        Chat.send(player, "&a已加入" + GAME + "等待队列 &7(" + waiting.size() + "/" + maxPlayers() + ")");
        Chat.send(player, "&7人数达到 &f" + minPlayers() + " &7后自动倒计时开始。");
        countdown.update();
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
        boolean wasEliminated = eliminated.remove(id);
        if (!wasWaiting && !wasPlaying && !wasEliminated) {
            if (!silent) {
                Chat.send(player, "&e你不在此游戏中。");
            }
            return;
        }
        clearPlayer(player);
        HubAPI.clearGame(id);
        player.setGameMode(GameMode.SURVIVAL);
        if (!silent) {
            HubAPI.teleportToLobby(player);
            Chat.send(player, "&e你已离开" + GAME + "。");
        }
        if (state == State.RUNNING && wasPlaying) {
            checkWin();
        } else if (state == State.WAITING) {
            countdown.update();
        }
        updateSidebar();
        reportArena();
    }

    /**
     * I3 A 批：重生后下一 tick 切成旁观（原来写在 {@code FireJumperListener.onRespawn} 里）。
     * 由 {@link FireJumperSession#onPlayerRespawn} 调用 —— 游戏模块不再监听 {@code PlayerRespawnEvent}。
     */
    void spectateNextTick(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && isInGame(player)) {
                player.setGameMode(GameMode.SPECTATOR);
            }
        });
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        boolean wasWaiting = waiting.remove(id);
        boolean wasPlaying = playing.remove(id);
        boolean wasEliminated = eliminated.remove(id);
        if (!wasWaiting && !wasPlaying && !wasEliminated) {
            return;
        }
        if (state == State.RUNNING && wasPlaying) {
            checkWin();
        } else if (state == State.WAITING) {
            countdown.update();
        }
        updateSidebar();
        reportArena();
    }

    // ---------- 开局 ----------

    public void startGame(boolean force) {
        if (state != State.WAITING) {
            return;
        }
        if (!arena.ready()) {
            broadcastWaiting("&c场地未配置完整（缺 " + arena.missing() + "），无法开始。");
            return;
        }
        int need = force ? 1 : minPlayers();
        if (waiting.size() < need) {
            broadcastWaiting("&c至少需要 " + need + " 名玩家才能开始。");
            return;
        }
        if (startCountdown().begin("main", new ArrayList<>(waiting), () -> startGame(force))) {
            return;
        }
        countdown.cancel();
        playing.clear();
        eliminated.clear();
        winners.clear();
        playing.addAll(waiting);
        waiting.clear();
        soloTest = force && playing.size() == 1 && !ArcadeBridge.isActive();
        waves.clear();
        secondsLeft = gameSeconds();
        tickCount = 0;
        round = 0;
        pendingLines = 0;
        spawnCooldownTicks = 0;
        roundGapTicks = -1;
        currentType = null;
        startDelayTicks = Math.max(0, cfgInt("start-delay-seconds", 3)) * 20;
        state = State.RUNNING;
        if (session != null && !session.state().isFinal()) {
            session.start();
        }

        int level = jumpBoostLevel();
        List<Location> spawnPoints = new ArrayList<>(arena.spawns());
        Collections.shuffle(spawnPoints, random);
        int spawnIndex = 0;
        for (UUID id : playing) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            Location spawn = spawnPoints.isEmpty() ? null : spawnPoints.get(spawnIndex++ % spawnPoints.size());
            clearPlayer(player);
            player.setGameMode(GameMode.SURVIVAL);
            player.setHealth(20.0);
            player.setFoodLevel(20);
            player.setSaturation(20.0f);
            if (spawn != null) {
                player.teleport(spawn);
                player.setFallDistance(0f);
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST,
                    PotionEffect.INFINITE_DURATION, level - 1, false, false, false));
            HubAPI.setPlaying(id, GAME);
            Chat.title(player, "&6&l" + GAME,
                    soloTest ? "&e单人测试模式 &7· &f跳过火焰线" : "&f跳跃提升 " + level + " &7· &f跳过火焰线，活到最后", 2);
        }
        gameTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        updateSidebar();
        reportArena();
    }

    // ---------- 每 tick ----------

    private void tick() {
        if (state != State.RUNNING) {
            return;
        }
        tickCount++;
        if (startDelayTicks > 0) {
            startDelayTicks--;
        } else {
            scheduleRounds();
        }
        moveWaves();
        if (state != State.RUNNING) {
            return;
        }
        if (tickCount % particleIntervalTicks() == 0) {
            renderWaves();
        }
        if (tickCount % 20 == 0) {
            secondsLeft--;
            if (secondsLeft <= 0) {
                secondsLeft = 0;
                endGame(true);
                return;
            }
            updateSidebar();
            String bar = "&6第 " + Math.max(1, round) + " 轮 &7| &f剩余 &e" + secondsLeft + "s";
            for (UUID id : allPlayers()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Chat.actionBar(p, bar);
                }
            }
        }
    }

    private void scheduleRounds() {
        if (pendingLines > 0) {
            if (spawnCooldownTicks <= 0) {
                spawnWave(currentType);
                pendingLines--;
                spawnCooldownTicks = currentType == null ? 0 : currentType.gapTicks;
            } else {
                spawnCooldownTicks--;
            }
            return;
        }
        if (!waves.isEmpty()) {
            return;
        }
        if (roundGapTicks < 0) {
            roundGapTicks = randomRoundGapTicks();
        }
        if (roundGapTicks-- <= 0) {
            startRound();
        }
    }

    private void startRound() {
        round++;
        roundGapTicks = -1;
        spawnCooldownTicks = 0;
        List<LineType> eligible = new ArrayList<>();
        for (LineType type : lineTypes) {
            if (type.enabled && round >= type.fromRound && round <= type.toRound) {
                eligible.add(type);
            }
        }
        if (eligible.isEmpty()) {
            for (LineType type : lineTypes) {
                if (type.enabled) {
                    eligible.add(type);
                }
            }
        }
        if (eligible.isEmpty()) {
            pendingLines = 0;
            return;
        }
        currentType = eligible.get(random.nextInt(eligible.size()));
        pendingLines = Math.max(1, currentType.lines);
    }

    private void spawnWave(LineType type) {
        if (type == null || !arena.regionConfigured()) {
            return;
        }
        double extent = travelExtent();
        double range = arena.travelRange() > 0 ? arena.travelRange() : extent;
        double center = travelCenter();
        double half = range / 2.0;
        double start = arena.sign() > 0 ? center - half : center + half;
        double end = arena.sign() > 0 ? center + half : center - half;
        double spanLength = arena.lineLength() > 0 ? arena.lineLength() : spanExtent();
        Wave wave = new Wave(arena.sign(), type.speed / 20.0, start, end,
                spanCenter(), spanLength / 2.0);
        waves.add(wave);
        if (plugin.getConfig().getBoolean("debug-lines", false)) {
            plugin.getLogger().info("[火线] 第 " + round + " 轮 " + type.id
                    + "：起点 " + fmt(start) + " → 终点 " + fmt(end)
                    + "，跨度 " + fmt(spanLength) + "，地面 Y " + arena.groundY()
                    + "，判定 Y≤" + fmt(arena.groundY() + arena.lineHeight()));
        }
        World world = arena.world();
        if (world != null) {
            Location centerLoc = arena.center();
            if (centerLoc != null) {
                world.playSound(centerLoc, Sound.ENTITY_BLAZE_SHOOT, 0.7f, 1.5f);
            }
        }
    }

    private void moveWaves() {
        if (waves.isEmpty()) {
            return;
        }
        List<UUID> hit = new ArrayList<>();
        for (Wave wave : new ArrayList<>(waves)) {
            wave.previous = wave.position;
            wave.position += wave.sign * wave.speedPerTick;
            collectHits(wave, hit);
            boolean done = wave.sign > 0 ? wave.position >= wave.end : wave.position <= wave.end;
            if (done) {
                waves.remove(wave);
            }
        }
        for (UUID id : hit) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                eliminate(player, "被火焰线击中");
            }
        }
    }

    private void collectHits(Wave wave, List<UUID> hit) {
        World world = arena.world();
        if (world == null) {
            return;
        }
        double low = Math.min(wave.previous, wave.position) - 0.35;
        double high = Math.max(wave.previous, wave.position) + 0.35;
        double lineTop = arena.groundY() + arena.lineHeight();
        for (UUID id : new ArrayList<>(playing)) {
            if (hit.contains(id)) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline() || !player.getWorld().equals(world)) {
                continue;
            }
            Location loc = player.getLocation();
            if (loc.getY() > lineTop) {
                continue;
            }
            double along = arena.travelX() ? loc.getX() : loc.getZ();
            if (along + 0.35 < low || along - 0.35 > high) {
                continue;
            }
            double across = arena.travelX() ? loc.getZ() : loc.getX();
            if (Math.abs(across - wave.spanCenter) > wave.spanHalf + 0.35) {
                continue;
            }
            hit.add(id);
        }
    }

    private void renderWaves() {
        World world = arena.world();
        if (world == null || waves.isEmpty()) {
            return;
        }
        List<Player> viewers = world.getPlayers();
        if (viewers.isEmpty()) {
            return;
        }
        Particle particle = particleType();
        int count = Math.max(1, cfgInt("particles.count", 1));
        int rows = Math.max(1, cfgInt("particles.rows", 1));
        double gap = Math.max(0.1, cfgDouble("particles.row-gap", 0.6));
        double spacing = Math.max(0.2, cfgDouble("particles.spacing", 1.0));
        double bottom = arena.groundY() + 0.2;
        for (Wave wave : waves) {
            double along = wave.position;
            for (double across = wave.spanCenter - wave.spanHalf;
                 across <= wave.spanCenter + wave.spanHalf; across += spacing) {
                for (int row = 0; row < rows; row++) {
                    double y = bottom + row * gap;
                    if (arena.travelX()) {
                        emit(viewers, particle, along, y, across, count);
                    } else {
                        emit(viewers, particle, across, y, along, count);
                    }
                }
            }
        }
    }

    /**
     * 逐玩家发送，不受 32 格视距限制。
     * 注意：带 force 的 World#spawnParticle 重载第 9 个参数是粒子 data；
     * FLAME 没有 data，传 double 会被当成 data，部分版本直接不发包。
     */
    private void emit(List<Player> viewers, Particle particle, double x, double y, double z, int count) {
        for (Player player : viewers) {
            player.spawnParticle(particle, x, y, z, count, 0.02, 0.02, 0.02, 0.01);
        }
    }

    private int particleIntervalTicks() {
        return Math.max(1, Math.min(20, cfgInt("particles.interval-ticks", 3)));
    }

    private Particle particleType() {
        String raw = plugin.getConfig().getString("particles.type", "FLAME");
        try {
            Particle particle = Particle.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
            // 只接受不需要额外 data 的粒子（DUST 等需要 DustOptions，传错会不发包）
            return particle.getDataType() == Void.class ? particle : Particle.FLAME;
        } catch (IllegalArgumentException exception) {
            return Particle.FLAME;
        }
    }

    private double travelCenter() {
        return arena.travelX() ? (arena.minX() + arena.maxX()) / 2.0
                : (arena.minZ() + arena.maxZ()) / 2.0;
    }

    private double travelExtent() {
        return arena.travelX() ? arena.maxX() - arena.minX() + 1.0
                : arena.maxZ() - arena.minZ() + 1.0;
    }

    private double spanCenter() {
        return arena.travelX() ? (arena.minZ() + arena.maxZ()) / 2.0
                : (arena.minX() + arena.maxX()) / 2.0;
    }

    private double spanExtent() {
        return arena.travelX() ? arena.maxZ() - arena.minZ() + 1.0
                : arena.maxX() - arena.minX() + 1.0;
    }

    // ---------- 淘汰 / 结算 ----------

    public void eliminate(Player player, String reason) {
        UUID id = player.getUniqueId();
        if (!playing.remove(id)) {
            return;
        }
        eliminated.add(id);
        player.setGlowing(false);
        player.removePotionEffect(PotionEffectType.JUMP_BOOST);
        player.setGameMode(GameMode.SPECTATOR);
        Chat.title(player, "&c你被淘汰了", "&7" + reason, 2);
        broadcastGame("&c" + player.getName() + " &7被淘汰（" + reason + "）");
        if (state == State.RUNNING) {
            checkWin();
        }
        updateSidebar();
        reportArena();
    }

    private void checkWin() {
        if (state != State.RUNNING) {
            return;
        }
        if (playing.size() <= 1) {
            endGame(false);
        }
    }

    /** 收场入口（I4）：先让会话 end()，再由 onEnd 回到 doEndGame。 */
    private void endGame(boolean timeUp) {
        pendingTimeUp = timeUp;
        if (session == null || session.state().isFinal()) {
            doEndGame();
            return;
        }
        session.end(EndReason.ROUND_END);
    }

    /** 真正的收场逻辑。timeUp 透传。 */
    void doEndGame() {
        boolean timeUp = pendingTimeUp;
        pendingTimeUp = false;
        endGame(timeUp, true);
    }

    /** 管理员强制结束：不记录胜场，通知街机派对本局中止。 */
    public void abortGame() {
        endGame(false, false);
    }

    private void endGame(boolean timeUp, boolean award) {
        if (state != State.RUNNING) {
            return;
        }
        state = State.ENDING;
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
        waves.clear();
        winners.clear();
        if (award) {
            winners.addAll(playing);
            playing.clear();
        }
        if (!award) {
            broadcastGame("&e本局已被管理员结束。");
            ArcadeBridge.aborted("firejumper");
        } else if (winners.isEmpty()) {
            broadcastGame("&e所有人都被淘汰了，本局平局。");
        } else if (timeUp) {
            broadcastGame("&6&l时间到！&f剩余玩家并列获胜：&e" + winnerNames());
        } else {
            broadcastGame("&6&l" + winnerNames() + " &f获得了胜利！");
        }
        if (award && soloTest) {
            broadcastGame("&e单人测试结束（不记录胜场）。");
        } else if (award) {
            for (UUID id : winners) {
                recordFirst(id);
            }
            ArcadeBridge.report("firejumper", new ArrayList<>(winners), List.of(), List.of());
        }
        updateSidebar();
        Bukkit.getScheduler().runTaskLater(plugin, this::finishEnd, endDelayTicks());
    }

    private String winnerNames() {
        List<String> names = new ArrayList<>();
        for (UUID id : winners) {
            Player player = Bukkit.getPlayer(id);
            names.add(player == null ? id.toString().substring(0, 8) : player.getName());
        }
        return String.join("、", names);
    }

    private void finishEnd() {
        for (UUID id : allPlayers()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                clearPlayer(player);
                player.setGameMode(GameMode.SURVIVAL);
                HubAPI.teleportToLobby(player);
            }
            HubAPI.clearGame(id);
        }
        waiting.clear();
        playing.clear();
        eliminated.clear();
        winners.clear();
        waves.clear();
        soloTest = false;
        state = State.WAITING;
        liveSidebar.hide("firejumper");
        countdown.update();
        reportArena();
    }

    private void recordFirst(UUID id) {
        if (stats == null) {
            return;
        }
        stats.set("firsts." + id, stats.getInt("firsts." + id, 0) + 1);
        try {
            PlayerStats.save("firejumper", stats);
        } catch (IOException exception) {
            plugin.getLogger().warning("保存玩家记录失败: " + exception.getMessage());
        }
    }

    // ---------- 玩家状态 / 道具 ----------

    private void clearPlayer(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setGlowing(false);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setExp(0f);
        player.setLevel(0);
        player.setAllowFlight(false);
        player.setFlying(false);
    }

    private void giveWaitItems(Player player) {
        player.getInventory().setItem(waitSlot("return", 8), waitItem(returnKey, "return", RED_BED_FALLBACK));
        if (player.hasPermission("firejumper.admin")) {
            player.getInventory().setItem(waitSlot("start", 4), waitItem(startKey, "start", Material.PAPER));
        }
    }

    private static final Material RED_BED_FALLBACK = Material.RED_BED;

    private int waitSlot(String key, int def) {
        int slot = cfgInt("wait-items." + key + ".slot", def);
        return slot >= 0 && slot <= 35 ? slot : def;
    }

    private ItemStack waitItem(NamespacedKey key, String section, Material fallback) {
        String raw = plugin.getConfig().getString("wait-items." + section + ".material", fallback.name());
        Material material = Material.matchMaterial(raw == null ? "" : raw);
        ItemStack item = new ItemStack(material == null || material.isAir() ? fallback : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = plugin.getConfig().getString("wait-items." + section + ".name", null);
            if (name != null) {
                meta.displayName(Chat.noItalic(Chat.color(name)));
            }
            List<String> lore = plugin.getConfig().getStringList("wait-items." + section + ".lore");
            if (!lore.isEmpty()) {
                List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
                for (String line : lore) {
                    lines.add(Chat.color(line));
                }
                meta.lore(lines);
            }
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isReturnItem(ItemStack item) {
        return tagged(item, returnKey);
    }

    public boolean isStartItem(ItemStack item) {
        return tagged(item, startKey);
    }

    private boolean tagged(ItemStack item, NamespacedKey key) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // ---------- 查询 / 上报 ----------

    public FireJumperArena arena() {
        return arena;
    }

    public State state() {
        return state;
    }

    public boolean isWaiting(Player player) {
        return waiting.contains(player.getUniqueId());
    }

    public boolean isAlive(Player player) {
        return playing.contains(player.getUniqueId());
    }

    public boolean isInGame(Player player) {
        UUID id = player.getUniqueId();
        return waiting.contains(id) || playing.contains(id) || eliminated.contains(id);
    }

    public int aliveCount() {
        return playing.size();
    }

    public void checkBounds(Player player) {
        if (state != State.RUNNING || !outOfBoundsEliminate()) {
            return;
        }
        UUID id = player.getUniqueId();
        if (!playing.contains(id)) {
            return;
        }
        Location location = player.getLocation();
        if (location.getY() < arena.groundY() - 4 || !arena.contains(location)) {
            eliminate(player, "离开了场地");
        }
    }

    private Set<UUID> allPlayers() {
        Set<UUID> all = new LinkedHashSet<>();
        all.addAll(waiting);
        all.addAll(playing);
        all.addAll(eliminated);
        all.addAll(winners);
        return all;
    }

    private void broadcastWaiting(String message) {
        for (UUID id : waiting) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                Chat.send(player, message);
            }
        }
    }

    private void broadcastGame(String message) {
        for (UUID id : allPlayers()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                Chat.sendTitled(player, message);
            }
        }
    }

    /** 排查用：把场地/火线的实际坐标打出来（/firejumper debug）。 */
    public List<String> debugLines() {
        List<String> out = new ArrayList<>();
        out.add("状态 " + state + "，剩余 " + secondsLeft + "s，轮次 " + round
                + "，存活 " + playing.size() + "，火线 " + waves.size() + " 条");
        out.add("世界 " + arena.worldName() + "，区域 " + arena.sizeText()
                + "，方向 " + arena.direction() + "，线高 " + arena.lineHeight()
                + "，地面 Y " + arena.groundY());
        Location p1 = arena.pos1();
        Location p2 = arena.pos2();
        out.add("pos1Y=" + (p1 == null ? "-" : p1.getBlockY())
                + " pos2Y=" + (p2 == null ? "-" : p2.getBlockY())
                + " 出生点=" + arena.spawns().size()
                + " 首个出生点Y=" + (arena.spawns().isEmpty() ? "-" : arena.spawns().get(0).getBlockY()));
        for (int i = 0; i < waves.size(); i++) {
            Wave wave = waves.get(i);
            out.add("火线" + (i + 1) + " 当前位置 " + fmt(wave.position)
                    + "，本段 " + fmt(Math.min(wave.previous, wave.position))
                    + ".." + fmt(Math.max(wave.previous, wave.position)));
        }
        return out;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    public void updateSidebar() {        if (!plugin.getConfig().getBoolean("sidebar.enabled", true)) {
            return;
        }
        Set<UUID> viewers = allPlayers();
        if (viewers.isEmpty()) {
            liveSidebar.hide("firejumper");
            return;
        }
        List<LiveSidebar.Line> lines = new ArrayList<>();
        lines.add(new LiveSidebar.Line("§6剩余时间", Math.max(0, secondsLeft)));
        lines.add(new LiveSidebar.Line("§e轮次", Math.max(1, round)));
        lines.add(new LiveSidebar.Line("§f存活", playing.size()));
        liveSidebar.update("firejumper",
                plugin.getConfig().getString("sidebar.title", "&6&l跃火者 · 存活"),
                new ArrayList<>(viewers), lines);
    }

    private void reportArena() {
        boolean running = state != State.WAITING;
        String extra = running ? "剩余 " + secondsLeft + "s · " + playing.size() + " 人" : null;
        HubAPI.setArena(GAME, ARENA, running ? GameState.PLAYING : GameState.WAITING,
                waiting.size() + playing.size() + eliminated.size(), extra);
        HubAPI.setArenaSpectator(GAME, ARENA, arena.spectatorSpot());
    }

    public String roundText() {
        return Math.max(1, round) + " / " + Math.max(0, secondsLeft) + "s";
    }

    public String directionText() {
        return arena.direction().toUpperCase(Locale.ROOT);
    }

    /**
     * 声明地盘（{@code docs/框架不变量.md} 的 I2）：把场地边界报给框架。
     *
     * <p>新游戏接入时按 {@code HubCore/docs/游戏插件接入指南.md} 第 4 步做，
     * 不声明的话框架无法检测"两个游戏的地盘重叠"。
     */
    public void publishRegions() {
        com.hub.region.Regions.begin("firejumper");
        if (arena != null && arena.pos1() != null && arena.pos2() != null) {
            com.hub.region.Regions.box("firejumper", "", arena.pos1(), arena.pos2());
        }
    }
}
