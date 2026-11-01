package com.hillking;

import com.hub.api.ArcadeBridge;
import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.session.EndReason;
import com.hub.session.SessionState;
import com.hub.data.PlayerStats;
import com.hub.util.Chat;
import com.hub.util.LiveSidebar;
import com.hub.util.StartCountdown;
import com.hub.util.Top3;
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
import org.bukkit.enchantments.Enchantment;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** 高台之王核心：台面每秒得分、咸鱼击退、掉台自动回场、限时结算前三名。 */
public final class HillKingManager {

    public static final String GAME = "高台之王";
    public static final String ARENA = "主场地";

    public enum State {
        WAITING, RUNNING
    }

    private final HillKingPlugin plugin;
    private final HillKingArena arena;
    private final NamespacedKey fishKey;
    private final NamespacedKey returnKey;
    private final NamespacedKey startKey;
    private final WaitCountdown countdown;
    private final LiveSidebar liveSidebar;
    private final Random random = new Random();

    /** 会话（I4/I5）。状态由它持有，本类只读。 */
    private HillKingSession session;
    /** 收场时给玩家看的文案（end(reason) 不带文案，所以暂存在这）。 */
    private String endMessage;
    private final Set<UUID> waiting = new LinkedHashSet<>();
    private final Set<UUID> playing = new LinkedHashSet<>();
    private final Map<UUID, Integer> scores = new HashMap<>();

    private StartCountdown startCountdown;
    private BukkitTask gameTask;
    private int secondsLeft;
    private YamlConfiguration data;

    private World pvpWorld;
    private boolean savedPvp;
    private boolean hasSavedPvp;

    public HillKingManager(HillKingPlugin plugin) {
        this.plugin = plugin;
        this.arena = new HillKingArena(plugin);
        this.liveSidebar = new LiveSidebar(plugin);
        this.fishKey = new NamespacedKey(plugin, "fish");
        this.returnKey = new NamespacedKey(plugin, "return_item");
        this.startKey = new NamespacedKey(plugin, "start_item");
        this.countdown = new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return new ArrayList<>(waiting);
            }

            @Override
            public int minPlayers() {
                return HillKingManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return HillKingManager.this.maxPlayers();
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

    private String cfgString(String key, String def) {
        return plugin.getConfig().getString(key, def);
    }

    private Material material(String key, Material def) {
        Material material = Material.matchMaterial(cfgString(key, def.name()));
        return material == null || material.isAir() ? def : material;
    }

    public int minPlayers() {
        return Math.max(2, cfgInt("min-players", 2));
    }

    public int maxPlayers() {
        return Math.max(minPlayers(), cfgInt("max-players", 16));
    }

    public int gameSeconds() {
        return Math.max(5, cfgInt("game-seconds", 60));
    }

    private int pointsPerSecond() {
        return Math.max(1, Math.min(100, cfgInt("points-per-second", 1)));
    }

    private Material fishMaterial() {
        return material("fish.material", Material.COD);
    }

    private int knockbackLevel() {
        return Math.max(0, Math.min(10, cfgInt("fish.knockback-level", 2)));
    }

    private double knockbackHorizontal() {
        return Math.max(0.0, cfgDouble("fish.horizontal-per-level", 0.6)) * knockbackLevel();
    }

    private double knockbackVertical() {
        return Math.max(0.0, cfgDouble("fish.vertical-per-level", 0.25)) * knockbackLevel();
    }

    private int fishCooldownTicks() {
        return (int) Math.round(Math.max(0.0, cfgDouble("fish.cooldown-seconds", 0.3)) * 20.0);
    }

    private int returnSlot() {
        return Math.max(0, Math.min(8, cfgInt("wait-items.return.slot", 8)));
    }

    private int startSlot() {
        return Math.max(0, Math.min(8, cfgInt("wait-items.start.slot", 4)));
    }

    private boolean sidebarEnabled() {
        return plugin.getConfig().getBoolean("sidebar.enabled", true);
    }

    private String sidebarTitle() {
        return cfgString("sidebar.title", "&6&l高台之王 · 积分");
    }

    // ---------- 生命周期 ----------

    public void load() {
        // 会话（I4/I5）：只在第一次 load 建一次；重复 load（/hill reload）不动已有会话
        ensureSession();
        startCountdown();
        arena.load();
        data = PlayerStats.load("hillking", new File(plugin.getDataFolder(), "data.yml"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HubAPI.registerGame(GAME, GAME, "COD", "6");
            reportArena();
        }, 40L);
        plugin.getLogger().info("场地" + (arena.isReady() ? "已配置" : "未配置（缺 " + arena.missing() + "）") + "。");
    }

    public void reload() {
        if (!isWaitingState()) {
            return;
        }
        arena.load();
    }

    public void shutdown() {
        cancelTasks();
        liveSidebar.hideAll();
        restoreWorldPvp();
        if (startCountdown != null) {
            startCountdown.cancelAll();
        }
        if (session != null) {
            session.end(EndReason.ADMIN_STOP);
        }
        waiting.clear();
        playing.clear();
        scores.clear();
    }

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

    public HillKingArena arena() {
        return arena;
    }

    /** 兼容旧调用：把 session 状态映射回原来的两态枚举。 */
    public State state() {
        return isRunningState() ? State.RUNNING : State.WAITING;
    }

    /**
     * 拿一个可用会话；已结束的会话**不能复用**（I5：ENDED 是终态）。
     *
     * <p>⚠️ 修的是我引入的回归：原来只在 {@code load()} 里 new 一次，
     * 一局结束后 state 停在 ENDED，{@code isWaitingState()} 永远 false →
     * {@code startGame()} 直接返回，表现为"**每次开服只能玩一局**"。
     * 正确语义是：会话 = 一局，结束就换新的。
     */
    private void ensureSession() {
        if (session == null || session.state().isFinal()) {
            session = new HillKingSession(this);
        }
    }

    /** WAITING 阶段（可加入/退出）。 */
    private boolean isWaitingState() {
        return session != null && session.state() == SessionState.WAITING;
    }

    /** 对局进行中。 */
    private boolean isRunningState() {
        return session != null && session.state() == SessionState.RUNNING;
    }

    public boolean isRunning() {
        return isRunningState();
    }

    public boolean isWaiting(Player player) {
        return waiting.contains(player.getUniqueId());
    }

    public boolean isPlaying(Player player) {
        return isRunningState() && playing.contains(player.getUniqueId());
    }

    public boolean isInGame(Player player) {
        UUID id = player.getUniqueId();
        return waiting.contains(id) || playing.contains(id);
    }

    public boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission("hill.admin");
    }

    public int waitingCount() {
        return waiting.size();
    }

    public int playingCount() {
        return playing.size();
    }

    public int scoreOf(UUID id) {
        return scores.getOrDefault(id, 0);
    }

    // ---------- 加入 / 离开 ----------

    public void join(Player player) {
        UUID id = player.getUniqueId();
        // 上一局结束后会话停在 ENDED（终态），必须换新的，否则这里会被判成"对局进行中"而拒绝加入
        ensureSession();
        if (!isWaitingState()) {
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
        // I1：把玩家挂到会话的归属链上（否则 doctor 的 [归属] 永远是 0 人、I1 只是名义成立）
        if (session != null && !session.join(player)) {
            plugin.getLogger().warning("[HillKing] " + player.getName() + " 加入归属链失败（见 [被吞异常]）");
        }
        clearGameState(player);
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
        UUID id = player.getUniqueId();
        boolean wasWaiting = waiting.remove(id);
        boolean wasPlaying = playing.remove(id);
        if (!wasWaiting && !wasPlaying) {
            if (!silent) {
                Chat.send(player, "&e你不在此游戏中。");
            }
            return;
        }
        scores.remove(id);
        clearGameState(player);
        HubAPI.clearGame(id);
        restorePlayer(player);
        if (!silent) {
            HubAPI.teleportToLobby(player);
            Chat.send(player, "&e你已离开" + GAME + "。");
        }
        if (isRunningState() && playing.isEmpty()) {
            endGame(EndReason.ABORT, "&7所有人都离开了，本局结束。");
            // 已收场：只摘归属链（会话已 ENDED，不会再触发一次 end）
            if (session != null) {
                session.leave(player);
            }
            return;
        }
        if (session != null) {
            // 顺序很重要：先让上面的收场逻辑跑完，再摘归属链。
            // 否则 session.leave 的"最后一人走了自动 end(ABORT)"会和这里撞车。
            session.leave(player);
        }
        if (isWaitingState()) {
            updateCountdown();
        }
        updateBoard();
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
        clearGameState(player);
        HubAPI.clearGame(id);
        if (isRunningState() && playing.isEmpty()) {
            endGame(EndReason.ABORT, "&7所有人都离开了，本局结束。");
            if (session != null) {
                session.leave(player);
            }
            return;
        }
        if (session != null) {
            session.leave(player);   // 下线也要摘归属链
        }
        if (isWaitingState()) {
            updateCountdown();
        }
        updateBoard();
        reportArena();
    }

    private void updateCountdown() {
        if (!isWaitingState()) {
            countdown.cancel();
            return;
        }
        countdown.update();
    }

    // ---------- 开局 / 结算 ----------

    public void startGame(boolean force) {
        if (!isWaitingState()) {
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
        if (!ready.isEmpty() && (ready.size() >= minPlayers() || force)) {
            if (startCountdown().begin("main", new ArrayList<>(ready), () -> startGame(force))) {
                return;
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
        session.start();
        enableWorldPvp();
        playing.clear();
        playing.addAll(ready);
        scores.clear();
        for (UUID id : playing) {
            scores.put(id, 0);
        }
        secondsLeft = gameSeconds();

        List<Location> spawnOrder = new ArrayList<>(arena.getSpawns());
        java.util.Collections.shuffle(spawnOrder, random);
        int spawnIndex = 0;
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            clearGameState(p);
            resetPlayer(p);
            p.setGameMode(GameMode.SURVIVAL);
            Location spawn = spawnOrder.isEmpty() ? arena.center() : spawnOrder.get(spawnIndex++ % spawnOrder.size());
            if (spawn != null) {
                p.teleport(spawn);
                p.setVelocity(new Vector(0, 0, 0));
            }
            p.getInventory().setItem(0, fishItem());
            p.getInventory().setHeldItemSlot(0);
            p.setCooldown(fishMaterial(), 0);
            HubAPI.setPlaying(id, GAME);
            Chat.title(p, "&6&l" + GAME, "&f站在高台上得分，用咸鱼把对手拍下去", 2);
        }
        HubAPI.setArenaSpectator(GAME, ARENA, arena.spectatorSpot());
        broadcast("&6&l========== " + GAME + " 开始 ==========");
        broadcast("&f站在红色羊毛高台上每秒 +" + pointsPerSecond() + " 分，掉下台会立刻被送回台上。");
        broadcast("&7手持咸鱼左键拍人，把对手拍出高台！时间 &e" + gameSeconds() + " &7秒。");
        updateBoard();
        updateActionBar();
        reportArena();
        gameTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGame, 20L, 20L);
    }

    public void stopGame() {
        if (!isRunningState()) {
            return;
        }
        endGame(EndReason.ADMIN_STOP, "&e管理员结束了本局。");
    }

    private void tickGame() {
        if (!isRunningState()) {
            cancelGameTask();
            return;
        }
        // 先结算这一秒的分，再扣时间：站满全程就是 game-seconds 分
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && arena.isOnPlatform(p.getLocation())) {
                scores.merge(id, pointsPerSecond(), Integer::sum);
            }
        }
        secondsLeft--;
        if (secondsLeft <= 0) {
            endGame(EndReason.ROUND_END, "&6&l时间到！");
            return;
        }
        if (secondsLeft == 30 || secondsLeft == 10) {
            broadcast("&e剩余 &f" + secondsLeft + " &e秒！");
        }
        updateBoard();
        updateActionBar();
        reportArena();
    }

    /** 请求结束对局：交给 session（唯一出口，幂等），文案暂存给 doEndGame。 */
    private void endGame(EndReason why, String message) {
        this.endMessage = message;
        if (session != null) {
            session.end(why);
        }
    }

    /**
     * 真正的收场逻辑。由 {@link HillKingSession#onEnd(EndReason)} 回调，
     * 所以**必然只执行一次**（框架保证），这里不再需要"是否在 RUNNING"的守卫。
     */
    void doEndGame(EndReason why) {
        String reason = endMessage;
        cancelTasks();
        liveSidebar.hide("hillking");
        restoreWorldPvp();
        secondsLeft = 0;

        broadcast("&6&l========== 游戏结束 ==========");
        if (reason != null && !reason.isBlank()) {
            broadcast(reason);
        }

        List<UUID> ranked = new ArrayList<>(playing);
        ranked.sort(Comparator
                .comparingInt((UUID id) -> scoreOf(id)).reversed()
                .thenComparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        // 同分并列：只按“不同分数”取前三档，多人同分就一起拿同一个名次
        Map<Integer, List<UUID>> groups = new LinkedHashMap<>();
        for (UUID id : ranked) {
            int score = scoreOf(id);
            if (score <= 0) {
                continue;
            }
            groups.computeIfAbsent(score, k -> new ArrayList<>()).add(id);
        }
        List<UUID> firsts = new ArrayList<>();
        List<UUID> seconds = new ArrayList<>();
        List<UUID> thirds = new ArrayList<>();
        int place = 0;
        for (Map.Entry<Integer, List<UUID>> entry : groups.entrySet()) {
            place++;
            if (place == 1) {
                firsts.addAll(entry.getValue());
                broadcast("&6&l第一名：&e" + namesOf(firsts) + " &7(" + entry.getKey() + " 分) &6" + Top3.stars(3));
            } else if (place == 2) {
                seconds.addAll(entry.getValue());
                broadcast("&e第二名：&f" + namesOf(seconds) + " &7(" + entry.getKey() + " 分) &6" + Top3.stars(2));
            } else if (place == 3) {
                thirds.addAll(entry.getValue());
                broadcast("&e第三名：&f" + namesOf(thirds) + " &7(" + entry.getKey() + " 分) &6" + Top3.stars(1));
                break;
            }
        }
        if (place == 0) {
            broadcast("&7本局无人得分（没人站上高台）。");
        }
        ArcadeBridge.report("hillking", firsts, seconds, thirds);
        Top3.award(data, firsts, seconds, thirds);
        saveData();
        broadcast("&6===============================");

        for (UUID id : new ArrayList<>(playing)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.title(p, "&6&l游戏结束", "&f你的分数：" + scoreOf(id), 2);
                restorePlayer(p);
                HubAPI.teleportToLobby(p);
            }
            HubAPI.clearGame(id);
        }
        playing.clear();
        waiting.clear();
        scores.clear();
        updateCountdown();
        reportArena();
    }

    // ---------- 掉台 / 咸鱼 ----------

    public void handleMove(Player player, Location to) {
        if (!isRunningState() || to == null || !playing.contains(player.getUniqueId())) {
            return;
        }
        Location center = arena.center();
        if (center == null || to.getWorld() == null || !to.getWorld().equals(center.getWorld())) {
            return;
        }
        // 掉下高台就留在下面，不再自动传送回台上/等待点（只是不得分）。
        // 只有真的掉到世界底部（掉进虚空，再往下就没地方站了）才送回等待点，避免一直下坠。
        double voidY = to.getWorld().getMinHeight() + 5.0;
        if (to.getY() < voidY) {
            Location lobby = arena.getLobby();
            if (lobby != null) {
                player.teleport(lobby);
            }
            player.setVelocity(new Vector(0, 0, 0));
            player.setFallDistance(0f);
            Chat.send(player, "&c你掉进了虚空，已送回等待点（本局无法再上台，等待本局结束）。");
        }
    }

    /** 咸鱼命中：只给击退、不造成伤害。返回 true 表示本次有效。 */
    public boolean fishHit(Player attacker, Player victim) {
        if (!isRunningState() || !playing.contains(attacker.getUniqueId())
                || !playing.contains(victim.getUniqueId())) {
            return false;
        }
        if (!isFish(attacker.getInventory().getItemInMainHand())
                && !isFish(attacker.getInventory().getItemInOffHand())) {
            return false;
        }
        if (attacker.getCooldown(fishMaterial()) > 0) {
            return false;
        }
        attacker.setCooldown(fishMaterial(), fishCooldownTicks());
        Vector direction = victim.getLocation().toVector().subtract(attacker.getLocation().toVector());
        direction.setY(0.0);
        if (direction.lengthSquared() < 0.001) {
            direction = attacker.getLocation().getDirection().setY(0.0);
        }
        if (direction.lengthSquared() < 0.001) {
            direction = new Vector(1.0, 0.0, 0.0);
        }
        direction.normalize().multiply(knockbackHorizontal());
        direction.setY(knockbackVertical());
        victim.setVelocity(direction);
        victim.getWorld().spawnParticle(Particle.SWEEP_ATTACK, victim.getLocation().add(0, 1, 0), 1);
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.0f);
        return true;
    }

    // ---------- 场地世界 PvP ----------

    /** 咸鱼要打人，必须先让场地世界允许 PvP，否则近战事件根本不触发。 */
    private void enableWorldPvp() {
        Location center = arena.center();
        World world = center != null ? center.getWorld()
                : (arena.getLobby() == null ? null : arena.getLobby().getWorld());
        if (world == null) {
            return;
        }
        pvpWorld = world;
        savedPvp = world.getPVP();
        hasSavedPvp = true;
        world.setPVP(true);
    }

    private void restoreWorldPvp() {
        if (hasSavedPvp && pvpWorld != null) {
            pvpWorld.setPVP(savedPvp);
        }
        pvpWorld = null;
        hasSavedPvp = false;
    }

    // ---------- 计分板 / 动作栏 ----------

    private void updateBoard() {
        if (!isRunningState()) {
            return;
        }
        if (!sidebarEnabled()) {
            liveSidebar.hide("hillking");
            return;
        }
        List<UUID> online = new ArrayList<>();
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                online.add(id);
            }
        }
        online.sort(Comparator
                .comparingInt((UUID id) -> scoreOf(id)).reversed()
                .thenComparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        for (UUID id : online) {
            lines.add(new LiveSidebar.Line("§e" + nameOf(id), scoreOf(id)));
        }
        liveSidebar.update("hillking", sidebarTitle(), online, lines);
    }

    private void updateActionBar() {
        if (!isRunningState()) {
            return;
        }
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.actionBar(p, "&6" + GAME + " &7| &f剩余 &e" + secondsLeft + "s"
                        + " &7| &f你的分数 &e" + scoreOf(id));
            }
        }
    }

    private void cancelGameTask() {
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
    }

    private void cancelTasks() {
        cancelGameTask();
    }

    // ---------- 道具 ----------

    private void giveWaitItems(Player player) {
        player.getInventory().setItem(returnSlot(), waitReturnItem());
        if (isAdmin(player)) {
            player.getInventory().setItem(startSlot(), waitStartItem());
        }
    }

    private ItemStack fishItem() {
        ItemStack item = new ItemStack(fishMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.displayName(Chat.noItalic(Chat.color(cfgString("fish.name", "&6&l咸鱼 &7(左键拍人)"))));
            meta.lore(lore("fish.lore"));
            int level = knockbackLevel();
            if (level > 0) {
                meta.addEnchant(Enchantment.KNOCKBACK, level, true);
            }
            meta.getPersistentDataContainer().set(fishKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack waitReturnItem() {
        Material material = material("wait-items.return.material", Material.RED_BED);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color(cfgString("wait-items.return.name", "&a&l返回大厅 &7(右键)"))));
            meta.lore(lore("wait-items.return.lore"));
            meta.getPersistentDataContainer().set(returnKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack waitStartItem() {
        Material material = material("wait-items.start.material", Material.PAPER);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color(cfgString("wait-items.start.name", "&a&l开始游戏 &7(右键)"))));
            meta.lore(lore("wait-items.start.lore"));
            meta.getPersistentDataContainer().set(startKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<net.kyori.adventure.text.Component> lore(String key) {
        List<net.kyori.adventure.text.Component> out = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList(key)) {
            out.add(Chat.noItalic(Chat.color(line)));
        }
        return out;
    }

    public boolean isFish(ItemStack item) {
        if (item == null || item.getType() != fishMaterial() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(fishKey, PersistentDataType.BYTE);
    }

    public boolean isReturnItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(returnKey, PersistentDataType.BYTE);
    }

    public boolean isStartItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(startKey, PersistentDataType.BYTE);
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
        if (!isWaitingState()) {
            Chat.send(player, "&e对局已经在进行中。");
            return;
        }
        if (!arena.isReady()) {
            Chat.send(player, "&c场地未配置完整（缺 " + arena.missing() + "）。");
            return;
        }
        startGame(true);
    }

    // ---------- 玩家状态 ----------

    private void resetPlayer(Player player) {
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.setExp(0f);
        player.setLevel(0);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
    }

    private void clearGameState(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.setCooldown(fishMaterial(), 0);
    }

    private void restorePlayer(Player player) {
        clearGameState(player);
        resetPlayer(player);
        player.setGameMode(GameMode.SURVIVAL);
    }

    // ---------- 上报 / 播报 ----------

    private void reportArena() {
        boolean running = isRunningState();
        String extra = running ? "剩余 " + secondsLeft + "s · " + playing.size() + " 人" : null;
        HubAPI.setArena(GAME, ARENA, running ? GameState.PLAYING : GameState.WAITING,
                waiting.size() + playing.size(), extra);
        HubAPI.setArenaSpectator(GAME, ARENA, arena.spectatorSpot());
    }

    private void broadcast(String message) {
        Set<UUID> all = new LinkedHashSet<>();
        all.addAll(waiting);
        all.addAll(playing);
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
        return name == null ? "未知" : name;
    }

    private String namesOf(List<UUID> ids) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) {
            names.add(nameOf(id));
        }
        return String.join("、", names);
    }

    // ---------- 战绩 ----------

    public int getStat(String key, UUID id) {
        return data == null ? 0 : data.getInt(key + "." + id, 0);
    }

    public int getPoints(UUID id) {
        return data == null ? 0 : data.getInt("points." + id, 0);
    }

    public void resetStats() {
        if (data == null) {
            return;
        }
        data.set("firsts", null);
        data.set("seconds", null);
        data.set("thirds", null);
        data.set("points", null);
        saveData();
    }

    private void saveData() {
        if (data == null) {
            return;
        }
        try {
            PlayerStats.save("hillking", data);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存玩家记录失败：" + ex.getMessage());
        }
    }

    public void saveAll() {
        arena.save();
    }

    // ---------- 建场辅助 ----------

    /** 建台 + 自动生成出生点（一键建场用）。 */
    public int[] build(Location center, int radius, int depth, boolean regenerateSpawns) {
        Location oldCenter = arena.center();
        int oldRadius = arena.getRadius();
        int oldDepth = arena.getDepth();
        Location clean = center.clone();
        clean.setX(clean.getBlockX() + 0.5);
        clean.setZ(clean.getBlockZ() + 0.5);
        clean.setY(clean.getBlockY());
        arena.setCenter(clean);
        arena.setRadius(radius);
        arena.setDepth(depth);
        int cleared = arena.clearPlatform(oldCenter, oldRadius, oldDepth);
        int changed = arena.buildPlatform();
        int spawns = 0;
        if (regenerateSpawns) {
            spawns = arena.generateSpawns(maxPlayers(), random);
        }
        saveAll();
        return new int[]{changed, cleared, spawns};
    }

    public int generateSpawns(int count) {
        int generated = arena.generateSpawns(count, random);
        saveAll();
        return generated;
    }
}
