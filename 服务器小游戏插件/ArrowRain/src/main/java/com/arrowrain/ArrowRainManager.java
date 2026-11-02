package com.arrowrain;

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
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** 万箭齐发：每回合随机刷新半砖掩体，没躲好就会被箭雨淘汰；第 N 回合起开放 PvP。 */
public final class ArrowRainManager {

    /** D22：会话层按场地键索引（单图游戏只有一个键）。 */
    private final java.util.Map<String, ArrowRainSession> sessions = new java.util.LinkedHashMap<>();
    private boolean pendingTimeUp;
    private ArrowRainSession session;

    private ArrowRainSession ensureSession() {
        String key = "main";
        ArrowRainSession s = sessions.get(key);
        if (s == null || s.state().isFinal()) {
            s = new ArrowRainSession(this, key);
            sessions.put(key, s);
        }
        session = s;
        return s;
    }


    public static final String GAME = "万箭齐发";
    public static final String ARENA = "主场地";

    public enum State {
        WAITING, RUNNING, ENDING
    }

    private enum Phase {
        COVER, RAIN, GAP
    }

    private final ArrowRainPlugin plugin;
    private final ArrowRainArena arena;
    private final LiveSidebar liveSidebar;
    private final WaitCountdown countdown;
    private final Random random = new Random();
    private final NamespacedKey returnKey;
    private final NamespacedKey startKey;

    private final Set<UUID> waiting = new LinkedHashSet<>();
    /** D21：加入 tick 与同 tick 内随机名次（同时进入的几人随机决出主持人）。 */
    private final java.util.Map<UUID, Long> joinTick = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> joinSeed = new java.util.HashMap<>();
    private final Set<UUID> playing = new LinkedHashSet<>();
    private final Set<UUID> eliminated = new LinkedHashSet<>();
    private final List<UUID> winners = new ArrayList<>();
    private final Map<Location, BlockData> covers = new LinkedHashMap<>();
    private final Set<UUID> rainArrows = new LinkedHashSet<>();
    private final Map<World, Boolean> pvpStates = new LinkedHashMap<>();

    private State state = State.WAITING;
    private Phase phase = Phase.GAP;
    private StartCountdown startCountdown;
    private BukkitTask gameTask;
    private YamlConfiguration stats;

    private int secondsLeft;
    private int tickCount;
    private int round;
    private int startDelayTicks;
    private int phaseTicks;
    private int lastPhaseSecond = -1;
    private boolean soloTest;

    public ArrowRainManager(ArrowRainPlugin plugin) {
        this.plugin = plugin;
        this.arena = new ArrowRainArena(plugin);
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
                return ArrowRainManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return ArrowRainManager.this.maxPlayers();
            }

            @Override
            public boolean hasOpQueued() {
                for (UUID id : waiting) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.hasPermission("arrowrain.admin")) {
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
                plugin.getLogger().info("[ArrowRain] 主持人 " + name + " 超时未开始，已移出本局（剩余 "
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
        return Math.max(30, cfgInt("game-seconds", 240));
    }

    private int coverSeconds() {
        return Math.max(1, cfgInt("round.cover-seconds", 3));
    }

    private int rainSeconds() {
        return Math.max(1, cfgInt("round.rain-seconds", 10));
    }

    private int gapSeconds() {
        return Math.max(0, cfgInt("round.gap-seconds", 3));
    }

    public int pvpStartRound() {
        return Math.max(1, cfgInt("round.pvp-start-round", 10));
    }

    private int coverHeight() {
        return Math.max(1, cfgInt("covers.height", 2));
    }

    private int coverMinDistance() {
        return Math.max(1, cfgInt("covers.min-distance", 3));
    }

    private Material coverMaterial() {
        String raw = plugin.getConfig().getString("covers.material", "OAK_SLAB");
        Material material = Material.matchMaterial(raw == null ? "" : raw);
        return material == null || material.isAir() ? Material.OAK_SLAB : material;
    }

    private int coverCount(int round) {
        int start = Math.max(1, cfgInt("covers.start-count", 8));
        int min = Math.max(1, cfgInt("covers.min-count", 1));
        int step = Math.max(0, cfgInt("covers.decrease-per-round", 1));
        return Math.max(min, start - Math.max(0, round - 1) * step);
    }

    private int endDelayTicks() {
        return Math.max(1, cfgInt("end-delay-seconds", 3)) * 20;
    }

    // ---------- 生命周期 ----------

    public void load() {
        ensureDefaults();
        arena.load();
        stats = PlayerStats.load("arrowrain", new File(plugin.getDataFolder(), "data.yml"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HubAPI.registerGame(GAME, GAME, "ARROW", "e");
            reportArena();
        }, 40L);
        plugin.getLogger().info("场地" + (arena.ready() ? "已配置" : "未配置（缺 " + arena.missing() + "）")
                + "：地面 Y " + arena.groundY() + "。");
    }

    private void ensureDefaults() {
        boolean changed = false;
        changed |= setDefault("round.cover-seconds", 3);
        changed |= setDefault("round.rain-seconds", 10);
        changed |= setDefault("round.gap-seconds", 3);
        changed |= setDefault("round.pvp-start-round", 10);
        changed |= setDefault("covers.start-count", 8);
        changed |= setDefault("covers.min-count", 1);
        changed |= setDefault("covers.decrease-per-round", 1);
        changed |= setDefault("covers.height", 2);
        changed |= setDefault("covers.material", "OAK_SLAB");
        changed |= setDefault("covers.min-distance", 3);
        changed |= setDefault("rain.arrow-height", 20);
        changed |= setDefault("rain.target-interval-ticks", 10);
        changed |= setDefault("rain.target-spread", 0.35);
        changed |= setDefault("rain.arrow-speed", 1.2);
        changed |= setDefault("rain.ambient-per-second", 10);
        changed |= setDefault("debug-rain", false);
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
        removeArrows();
        removeCovers();
        restoreWorldPvp();
        liveSidebar.hideAll();
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
        ArrowRainSession sess = ensureSession();
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
        player.setCollidable(true);
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
        secondsLeft = gameSeconds();
        tickCount = 0;
        round = 0;
        phase = Phase.GAP;
        phaseTicks = 0;
        startDelayTicks = Math.max(0, cfgInt("start-delay-seconds", 3)) * 20;
        state = State.RUNNING;
        if (session != null && !session.state().isFinal()) {
            session.start();
        }

        List<Location> spawns = new ArrayList<>(arena.spawns());
        Collections.shuffle(spawns, random);
        int index = 0;
        for (UUID id : playing) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            Location spawn = spawns.isEmpty() ? arena.center() : spawns.get(index++ % spawns.size());
            clearPlayer(player);
            player.setGameMode(GameMode.SURVIVAL);
            player.setCollidable(false);
            player.setHealth(20.0);
            player.setFoodLevel(20);
            player.setSaturation(20.0f);
            if (spawn != null) {
                player.teleport(spawn);
                player.setFallDistance(0f);
            }
            HubAPI.setPlaying(id, GAME);
            Chat.title(player, "&e&l" + GAME, soloTest ? "&e单人测试模式" : "&f躲在半砖掩体下，活到最后", 2);
        }
        gameTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        updateSidebar();
        reportArena();
    }

    // ---------- 主循环 ----------

    private void tick() {
        if (state != State.RUNNING) {
            return;
        }
        tickCount++;
        if (startDelayTicks > 0) {
            startDelayTicks--;
            if (startDelayTicks == 0) {
                startRound();
            }
        } else {
            tickPhase();
        }
        if (state != State.RUNNING) {
            return;
        }
        if (tickCount % 20 == 0) {
            secondsLeft--;
            if (secondsLeft <= 0) {
                secondsLeft = 0;
                endGame(true);
                return;
            }
            updateSidebar();
            updateActionBar();
        }
    }

    private void tickPhase() {
        switch (phase) {
            case COVER -> {
                if (--phaseTicks <= 0) {
                    startRain();
                } else {
                    int seconds = phaseSeconds();
                    if (seconds != lastPhaseSecond) {
                        lastPhaseSecond = seconds;
                        if (seconds >= 1 && seconds <= 3) {
                            for (UUID id : allPlayers()) {
                                Player player = Bukkit.getPlayer(id);
                                if (player != null) {
                                    Chat.title(player, "&e" + seconds, "&f箭雨即将落下", 1);
                                }
                            }
                        }
                    }
                }
            }
            case RAIN -> {
                rainTick();
                if (--phaseTicks <= 0) {
                    endRain();
                }
            }
            case GAP -> {
                if (--phaseTicks <= 0) {
                    startRound();
                }
            }
        }
    }

    private int phaseSeconds() {
        return Math.max(0, (phaseTicks + 19) / 20);
    }

    private void startRound() {
        round++;
        removeCovers();
        placeCovers();
        phase = Phase.COVER;
        phaseTicks = coverSeconds() * 20;
        lastPhaseSecond = -1;
        if (round >= pvpStartRound()) {
            enableWorldPvp();
        }
        String extra = round == pvpStartRound() ? " &c&l· PvP 已开启" : "";
        for (UUID id : allPlayers()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                Chat.title(player, "&e第 " + round + " 回合", "&f半砖掩体 &e" + covers.size() + " &f个", 2);
            }
        }
        broadcastGame("&e第 " + round + " 回合开始：&f" + covers.size() + " &e个掩体已刷新" + extra);
        World world = arena.world();
        if (world != null) {
            world.playSound(arena.center(), Sound.BLOCK_WOOD_PLACE, 0.8f, 1.1f);
        }
        updateSidebar();
    }

    private void startRain() {
        phase = Phase.RAIN;
        phaseTicks = rainSeconds() * 20;
        lastPhaseSecond = -1;
        broadcastGame("&c&l箭雨来了！&f快躲到半砖下面！");
        World world = arena.world();
        if (world != null) {
            world.playSound(arena.center(), Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.8f);
        }
    }

    private void endRain() {
        removeArrows();
        phase = Phase.GAP;
        phaseTicks = gapSeconds() * 20;
        lastPhaseSecond = -1;
        broadcastGame("&a箭雨结束。&7下一回合掩体会重新刷新。");
    }

    private void rainTick() {
        int targetInterval = Math.max(1, cfgInt("rain.target-interval-ticks", 10));
        if (tickCount % targetInterval == 0) {
            for (UUID id : new ArrayList<>(playing)) {
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline()) {
                    continue;
                }
                if (isCovered(player)) {
                    continue;
                }
                double spread = Math.max(0, cfgDouble("rain.target-spread", 0.35));
                double x = player.getLocation().getX() + (random.nextDouble() * 2 - 1) * spread;
                double z = player.getLocation().getZ() + (random.nextDouble() * 2 - 1) * spread;
                spawnArrow(x, z);
            }
        }

        int ambientPerSecond = Math.max(0, cfgInt("rain.ambient-per-second", 10));
        if (ambientPerSecond > 0 && tickCount % Math.max(1, 20 / ambientPerSecond) == 0) {
            for (int i = 0; i < ambientPerSecond; i++) {
                double x = arena.minX() + random.nextDouble() * (arena.maxX() - arena.minX() + 1);
                double z = arena.minZ() + random.nextDouble() * (arena.maxZ() - arena.minZ() + 1);
                spawnArrow(x, z);
            }
        }
    }

    private void spawnArrow(double x, double z) {
        World world = arena.world();
        if (world == null) {
            return;
        }
        double height = Math.max(5, cfgDouble("rain.arrow-height", 20));
        double speed = Math.max(0.3, cfgDouble("rain.arrow-speed", 1.2));
        Location from = new Location(world, x, arena.groundY() + height, z);
        Arrow arrow = world.spawn(from, Arrow.class, entity -> {
            entity.setShooter(null);
            entity.setDamage(0);
            entity.setKnockbackStrength(0);
            entity.setCritical(false);
            entity.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            entity.setPersistent(false);
            entity.setSilent(true);
        });
        arrow.setVelocity(new Vector(0, -speed, 0));
        rainArrows.add(arrow.getUniqueId());
    }

    // ---------- 掩体 ----------

    private void placeCovers() {
        World world = arena.world();
        if (world == null) {
            return;
        }
        int count = coverCount(round);
        int minDistance = coverMinDistance();
        int minDistanceSq = minDistance * minDistance;
        List<int[]> chosen = new ArrayList<>();
        int attempts = count * 80;
        Material material = coverMaterial();
        int height = coverHeight();
        while (chosen.size() < count && attempts-- > 0) {
            int x = arena.minX() + random.nextInt(Math.max(1, arena.maxX() - arena.minX() + 1));
            int z = arena.minZ() + random.nextInt(Math.max(1, arena.maxZ() - arena.minZ() + 1));
            boolean tooClose = false;
            for (int[] other : chosen) {
                int dx = other[0] - x;
                int dz = other[1] - z;
                if (dx * dx + dz * dz < minDistanceSq) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) {
                continue;
            }
            Block block = world.getBlockAt(x, arena.groundY() + height, z);
            covers.put(block.getLocation(), block.getBlockData());
            block.setType(material, false);
            chosen.add(new int[]{x, z});
        }
        if (plugin.getConfig().getBoolean("debug-rain", false)) {
            plugin.getLogger().info("[万箭齐发] 第 " + round + " 回合掩体 " + covers.size()
                    + " 个（目标 " + count + "），高度 Y=" + (arena.groundY() + height));
        }
    }

    private void removeCovers() {
        for (Map.Entry<Location, BlockData> entry : covers.entrySet()) {
            Location location = entry.getKey();
            if (location.getWorld() == null) {
                continue;
            }
            location.getBlock().setBlockData(entry.getValue(), false);
        }
        covers.clear();
    }

    /** 玩家是否站在某块掩体正下方（XZ 同格且脚在地面）。 */
    public boolean isCovered(Player player) {
        if (player == null) {
            return false;
        }
        Location location = player.getLocation();
        if (location.getY() > arena.groundY() + 0.6) {
            return false;
        }
        for (Location cover : covers.keySet()) {
            if (cover.getWorld() != null && cover.getWorld().equals(location.getWorld())
                    && cover.getBlockX() == location.getBlockX()
                    && cover.getBlockZ() == location.getBlockZ()) {
                return true;
            }
        }
        return false;
    }

    // ---------- 箭雨命中 ----------

    public boolean isRainArrow(Arrow arrow) {
        return arrow != null && rainArrows.contains(arrow.getUniqueId());
    }

    public void forgetArrow(Arrow arrow) {
        if (arrow != null) {
            rainArrows.remove(arrow.getUniqueId());
        }
    }

    /** 箭命中玩家：没在掩体下就淘汰。 */
    public void hitByArrow(Player player) {
        if (player == null || !playing.contains(player.getUniqueId())) {
            return;
        }
        if (isCovered(player)) {
            return;
        }
        eliminate(player, "被箭击中");
    }

    private void removeArrows() {
        for (UUID id : new ArrayList<>(rainArrows)) {
            org.bukkit.entity.Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        rainArrows.clear();
    }

    // ---------- 淘汰 / 结算 ----------

    public void eliminate(Player player, String reason) {
        UUID id = player.getUniqueId();
        if (!playing.remove(id)) {
            return;
        }
        eliminated.add(id);
        player.setGlowing(false);
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

    /** 真正的收场逻辑。timeUp 透传，玩家看到的结算不变。 */
    void doEndGame() {
        boolean timeUp = pendingTimeUp;
        pendingTimeUp = false;
        if (state == State.RUNNING) {
            finishGame(timeUp, true);
        }
    }

    public void abortGame() {
        if (state == State.RUNNING) {
            finishGame(false, false);
        }
    }

    private void finishGame(boolean timeUp, boolean award) {
        state = State.ENDING;
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
        removeArrows();
        removeCovers();
        restoreWorldPvp();
        winners.clear();
        if (award) {
            winners.addAll(playing);
            playing.clear();
        }
        if (!award) {
            broadcastGame("&e本局已被管理员结束。");
            ArcadeBridge.aborted("arrowrain");
        } else if (winners.isEmpty()) {
            broadcastGame("&e所有人都被淘汰了，本局平局。");
        } else if (timeUp) {
            broadcastGame("&6&l时间到！&f剩余玩家并列获胜：&e" + winnerNames());
        } else {
            broadcastGame("&6&l" + winnerNames() + " &f获得了胜利！");
        }
        if (award) {
            if (soloTest) {
                broadcastGame("&e单人测试结束（不记录胜场）。");
            } else {
                for (UUID id : winners) {
                    recordFirst(id);
                }
                ArcadeBridge.report("arrowrain", new ArrayList<>(winners), List.of(), List.of());
            }
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
                player.setCollidable(true);
                HubAPI.teleportToLobby(player);
            }
            HubAPI.clearGame(id);
        }
        waiting.clear();
        playing.clear();
        eliminated.clear();
        winners.clear();
        covers.clear();
        rainArrows.clear();
        soloTest = false;
        state = State.WAITING;
        liveSidebar.hide("arrowrain");
        countdown.update();
        reportArena();
    }

    private void recordFirst(UUID id) {
        if (stats == null) {
            return;
        }
        stats.set("firsts." + id, stats.getInt("firsts." + id, 0) + 1);
        try {
            PlayerStats.save("arrowrain", stats);
        } catch (IOException exception) {
            plugin.getLogger().warning("保存玩家记录失败: " + exception.getMessage());
        }
    }

    // ---------- PvP ----------

    public boolean pvpEnabled() {
        return state == State.RUNNING && round >= pvpStartRound();
    }

    private void enableWorldPvp() {
        World world = arena.world();
        if (world == null) {
            return;
        }
        if (pvpStates.containsKey(world)) {
            return;
        }
        pvpStates.put(world, world.getPVP());
        world.setPVP(true);
        if (plugin.getConfig().getBoolean("debug-rain", false)) {
            plugin.getLogger().info("[万箭齐发] 第 " + pvpStartRound() + " 回合开启世界 PvP：" + world.getName());
        }
    }

    private void restoreWorldPvp() {
        for (Map.Entry<World, Boolean> entry : pvpStates.entrySet()) {
            entry.getKey().setPVP(entry.getValue());
        }
        pvpStates.clear();
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
        player.getInventory().setItem(waitSlot("return", 8), waitItem(returnKey, "return", Material.RED_BED));
        if (player.hasPermission("arrowrain.admin")) {
            player.getInventory().setItem(waitSlot("start", 4), waitItem(startKey, "start", Material.PAPER));
        }
    }

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

    public ArrowRainArena arena() {
        return arena;
    }

    public State state() {
        return state;
    }

    public boolean isWaiting(Player player) {
        return waiting.contains(player.getUniqueId());
    }

    public boolean isPlaying(Player player) {
        return player != null && playing.contains(player.getUniqueId());
    }

    public boolean isInGame(Player player) {
        UUID id = player.getUniqueId();
        return waiting.contains(id) || playing.contains(id) || eliminated.contains(id);
    }

    public int aliveCount() {
        return playing.size();
    }

    public int round() {
        return round;
    }

    public int coverCount() {
        return covers.size();
    }

    public boolean isSoloTest() {
        return soloTest;
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

    private void updateActionBar() {
        int phaseLeft = phaseSeconds();
        String bar = switch (phase) {
            case COVER -> "&a找掩体 &7| &f箭雨 &e" + phaseLeft + "s &7后开始"
                    + " &7| &f掩体 &e" + covers.size()
                    + " &7| &f回合 &e" + Math.max(1, round)
                    + " &7| &f剩余 &e" + secondsLeft + "s";
            case RAIN -> "&c&l箭雨中 &7| &f本回合还剩 &e" + phaseLeft + "s"
                    + " &7| &f存活 &e" + playing.size()
                    + " &7| &f剩余 &e" + secondsLeft + "s";
            case GAP -> "&7休息 &7| &f下一回合 &e" + phaseLeft + "s"
                    + " &7| &f掩体 &e" + covers.size()
                    + " &7| &f剩余 &e" + secondsLeft + "s";
        };
        if (pvpEnabled()) {
            bar += " &7| &cPvP";
        }
        for (UUID id : allPlayers()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                Chat.actionBar(player, bar);
            }
        }
    }

    public void updateSidebar() {
        if (!plugin.getConfig().getBoolean("sidebar.enabled", true)) {
            return;
        }
        Set<UUID> viewers = allPlayers();
        if (viewers.isEmpty()) {
            liveSidebar.hide("arrowrain");
            return;
        }
        List<LiveSidebar.Line> lines = new ArrayList<>();
        lines.add(new LiveSidebar.Line("§6剩余时间", Math.max(0, secondsLeft)));
        lines.add(new LiveSidebar.Line("§e回合", Math.max(1, round)));
        lines.add(new LiveSidebar.Line("§f存活", playing.size()));
        liveSidebar.update("arrowrain",
                plugin.getConfig().getString("sidebar.title", "&e&l万箭齐发 · 存活"),
                new ArrayList<>(viewers), lines);
    }

    private void reportArena() {
        boolean running = state != State.WAITING;
        String extra = running ? "剩余 " + secondsLeft + "s · " + playing.size() + " 人" : null;
        HubAPI.setArena(GAME, ARENA, running ? GameState.PLAYING : GameState.WAITING,
                waiting.size() + playing.size() + eliminated.size(), extra);
        HubAPI.setArenaSpectator(GAME, ARENA, arena.spectatorSpot());
    }

    public List<String> debugLines() {
        List<String> out = new ArrayList<>();
        out.add("状态 " + state + "，阶段 " + phase + "，剩余 " + secondsLeft + "s，回合 " + round
                + "，存活 " + playing.size() + "，掩体 " + covers.size() + "，箭 " + rainArrows.size());
        out.add("世界 " + arena.worldName() + "，区域 " + arena.sizeText()
                + "，出生点 " + arena.spawns().size() + "，PvP 回合 " + pvpStartRound()
                + "，当前 PvP " + pvpEnabled());
        return out;
    }

    public String roundText() {
        return Math.max(1, round) + " / " + Math.max(0, secondsLeft) + "s";
    }
}
