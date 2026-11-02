package com.landclaim;

import com.hub.session.EndReason;

import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.data.PlayerStats;
import com.hub.api.ArcadeBridge;
import com.hub.util.Chat;
import com.hub.util.LiveSidebar;
import com.hub.util.Top3;
import com.hub.util.StartCountdown;
import com.hub.util.WaitCountdown;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 划地为王：圆形场地、锄头占地、铺满或倒计时结束。 */
public final class LandClaimManager {

    public static final String GAME = "battlefield 1 (占地1)";
    public static final String ARENA = "圆形场地";

    private static final Material[] COLORS = {
            Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL,
            Material.LIGHT_BLUE_WOOL, Material.YELLOW_WOOL, Material.LIME_WOOL,
            Material.PINK_WOOL, Material.GRAY_WOOL, Material.LIGHT_GRAY_WOOL,
            Material.CYAN_WOOL, Material.PURPLE_WOOL, Material.BLUE_WOOL,
            Material.BROWN_WOOL, Material.GREEN_WOOL, Material.RED_WOOL,
            Material.BLACK_WOOL
    };
    private static final String[] COLOR_NAMES = {
            "白色", "橙色", "品红", "淡蓝", "黄色", "黄绿", "粉色", "灰色",
            "淡灰", "青色", "紫色", "蓝色", "棕色", "绿色", "红色", "黑色"
    };

    private final LandClaimPlugin plugin;
    private final LandClaimArena arena;
    private final NamespacedKey hoeKey;
    private final NamespacedKey startKey;
    private final NamespacedKey leaveKey;

    private final Map<UUID, PlayerState> states = new LinkedHashMap<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Map<UUID, Integer> scores = new LinkedHashMap<>();
    private final Map<UUID, Material> colors = new LinkedHashMap<>();
    private final Map<UUID, Integer> spawnIndex = new LinkedHashMap<>();
    private final Map<UUID, Set<Long>> claims = new LinkedHashMap<>();
    private final Set<Long> claimedBlocks = new HashSet<>();

    private WaitCountdown countdown;
    /** D21：加入 tick 与同 tick 内随机名次。 */
    private final java.util.Map<UUID, Long> joinTick = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> joinSeed = new java.util.HashMap<>();

    /** D21 适配：等待队列（各游戏队列字段名不同，收敛成两个入口）。 */
    private java.util.List<UUID> waitingIds() {
        return new ArrayList<>(states.keySet());
    }

    /** D21 适配：把某人从等待队列摘掉。 */
    private void dropWaiting(UUID id) {
        states.remove(id);
        joinTick.remove(id);
        joinSeed.remove(id);
    }


    private YamlConfiguration stats;
    /** D22：会话层统一按场地键索引（单图游戏只有一个键）。 */
    private final java.util.Map<String, LandClaimSession> sessions = new java.util.LinkedHashMap<>();
    private String pendingEndMessage;
    private LandClaimSession session;

    private LandClaimSession ensureSession() {
        String key = "main";
        LandClaimSession s = sessions.get(key);
        if (s == null || s.state().isFinal()) {
            s = new LandClaimSession(this, key);
            sessions.put(key, s);
        }
        session = s;
        return s;
    }

    private boolean running;
    private boolean ending;
    private int remaining;
    private int secondsLeft;
    private final LiveSidebar liveSidebar;
    private BukkitTask timeTask;
    private BukkitTask endTask;

    public LandClaimManager(LandClaimPlugin plugin) {
        this.liveSidebar = new LiveSidebar(plugin);
        this.plugin = plugin;
        this.arena = new LandClaimArena(plugin);
        this.hoeKey = new NamespacedKey(plugin, "claim_hoe");
        this.startKey = new NamespacedKey(plugin, "start_item");
        this.leaveKey = new NamespacedKey(plugin, "leave_item");
    }

    public void load() {
        startCountdown();
        arena.load();
        ensureWaitItemDefaults();
        stats = PlayerStats.load("landclaim", new File(plugin.getDataFolder(), "data.yml"));
        createCountdown();
        reportArena();
        plugin.getLogger().info("场地" + (arena.ready() ? "已配置" : "未配置")
                + "：半径 " + arena.radius() + "，地面格数 " + arena.totalBlocks()
                + "，出生点 " + arena.spawns().size() + " 个。");
    }

    public void reload() {
        if (countdown != null) {
            countdown.cancel();
        }
        load();
    }

    private void createCountdown() {
        countdown = new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return states.keySet();
            }

            @Override
            public int minPlayers() {
                return LandClaimManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return LandClaimManager.this.maxPlayers();
            }

            @Override
            public boolean hasOpQueued() {
                for (UUID id : states.keySet()) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null && player.hasPermission("landclaim.admin")) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onTick(long secondsLeft) {
                for (UUID id : states.keySet()) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null) {
                        Chat.actionBar(player, "&a" + secondsLeft + "s 后开始…");
                    }
                }
            }

            @Override
            public void onMessage(String message) {
                broadcastWaiting(message);
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

                java.util.List<UUID> order = new ArrayList<>(waitingIds());

                order.sort(java.util.Comparator

                        .comparingLong((UUID id) -> joinTick.getOrDefault(id, 0L))

                        .thenComparingLong(id -> joinSeed.getOrDefault(id, 0L)));

                return order;

            }

            @Override

            public void onHostTimeout(UUID host) {

                Player hp = Bukkit.getPlayer(host);

                String hname = hp == null ? host.toString() : hp.getName();

                dropWaiting(host);

                if (session != null && hp != null && !session.state().isFinal()) {

                    session.leave(hp);

                }

                if (hp != null && hp.isOnline()) {

                    Chat.send(hp, "&c倒计时结束还没开始，你被移出本局（主持人超时）。");

                    HubAPI.teleportToLobby(hp);

                }

                plugin.getLogger().info("[LandClaim] 主持人 " + hname

                        + " 超时未开始，已移出本局（剩余 " + waitingIds().size() + " 人）");

                broadcastWaiting("&e主持人 &f" + hname + " &e超时未开始，已移出本局。");

                countdown.update();

            }


            @Override
            public void onStart() {
                startGame(false);
            }
        });
    }

    public int minPlayers() {
        int min = plugin.getConfig().getInt("min-players", 2);
        return Math.max(1, Math.min(min, maxPlayers()));
    }

    public int maxPlayers() {
        int max = plugin.getConfig().getInt("max-players", 16);
        return Math.max(1, Math.min(max, COLORS.length));
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

    public int waitingCountPublic() {
        return waitingIds().size();
    }

    public int minPlayersPublic() {
        return plugin.getConfig().getInt("min-players", 2);
    }


    public boolean join(Player player) {
        UUID id = player.getUniqueId();
        if (running || ending) {
            Chat.send(player, "&c游戏正在进行中，等下一局吧。");
            return false;
        }
        if (states.containsKey(id)) {
            Chat.send(player, "&e你已经在队列中。");
            return false;
        }
        if (!player.hasPermission("landclaim.play")) {
            Chat.send(player, "&c你没有加入该游戏的权限。");
            return false;
        }
        if (states.size() >= maxPlayers()) {
            Chat.send(player, "&c人数已满（最多 " + maxPlayers() + " 人）。");
            return false;
        }
        if (!arena.ready()) {
            Chat.send(player, "&c场地还没配置好：请管理员先设置中心、半径、生成场地和出生点。");
            return false;
        }
        Material color = nextColor();
        if (color == null) {
            Chat.send(player, "&c颜色已经用完了（最多 16 人）。");
            return false;
        }
        states.put(id, PlayerState.capture(player));
        joinTick.put(id, (long) Bukkit.getCurrentTick());
        joinSeed.put(id, java.util.concurrent.ThreadLocalRandom.current().nextLong());
        // I1（D23）：玩家已在别的对局里 -> **拒绝加入**并把话说清楚。
        // 这一步必须在把玩家写进本游戏队列**之前**，否则会留下
        // "游戏队列里有他、归属链上没他"的半加入状态（doctor 里查不出来，事后很难查）。
        ensureSession();
        if (session != null && !session.join(player)) {
            Chat.send(player, "&c你正在另一局游戏里，请先退出再加入（右键 &f回主城&c 或输入 &f/hub&c）。");
            plugin.getLogger().warning("[LandClaim] " + player.getName() + " 已在别的对局，拒绝加入");
            return false;
        }
        names.put(id, player.getName());
        scores.put(id, 0);
        colors.put(id, color);
        claims.put(id, new HashSet<>());
        int index = (states.size() - 1) % arena.spawns().size();
        spawnIndex.put(id, index);

        HubAPI.setGame(id, GAME);
        HubAPI.announceJoin(plugin.getName(), player.getName());
        prepareWaiting(player);
        countdown.update();
        reportArena();
        Chat.send(player, "&a已加入 battlefield 1 (占地1)，你的颜色：&f" + colorName(color) + "&a。");
        return true;
    }

    /** fromLobby = HubCore 已经在传回大厅，不再重复传送。 */
    public void leave(Player player, boolean fromLobby) {
        // I1：从归属链上摘掉自己 —— 漏了这行，玩家会卡在"已在等待中"，
        // 之后点任何别的游戏都会被拒绝（只剩重启服务器能救）。
        if (session != null && !session.state().isFinal()) {
            session.leave(player);
        }
        UUID id = player.getUniqueId();
        PlayerState state = states.remove(id);
        if (state == null) {
            return;
        }
        names.remove(id);
        scores.remove(id);
        colors.remove(id);
        spawnIndex.remove(id);
        Set<Long> own = claims.remove(id);
        if (own != null) {
            claimedBlocks.removeAll(own);
        }

        if (player.isOnline()) {
            state.apply(player);
        }
        HubAPI.clearGame(id);
        if (!fromLobby && player.isOnline()) {
            HubAPI.teleportToLobby(player);
        }
        if (!running && !ending) {
            countdown.update();
        }
        reportArena();
        if (running && states.isEmpty()) {
            endGame(false);
        }
    }

    public void quit(Player player) {
        leave(player, true);
    }

    // ---------- 开局 ----------

    private StartCountdown startCountdown;

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

    public boolean startGame(boolean force) {
        if (running || ending) {
            return false;
        }
        if (!arena.ready()) {
            broadcastWaiting("&c场地未配置完整，无法开始。");
            return false;
        }
        int need = force ? 1 : minPlayers();
        if (states.size() < need) {
            broadcastWaiting("&c至少需要 " + need + " 名玩家才能开始。");
            return false;
        }

        if (startCountdown().begin("main", new ArrayList<>(states.keySet()), () -> startGame(force))) {
            return true;
        }
        if (countdown != null) {
            countdown.cancel();
        }
        arena.resetFloor();
        remaining = arena.totalBlocks();
        claimedBlocks.clear();
        for (Set<Long> set : claims.values()) {
            set.clear();
        }
        running = true;
        if (session != null && !session.state().isFinal()) {
            session.start();
        }
        ending = false;

        int i = 0;
        int playerCount = Math.max(1, states.size());
        int spawnCount = arena.spawns().size();
        for (UUID id : new ArrayList<>(states.keySet())) {
            scores.put(id, 0);
            colors.computeIfAbsent(id, key -> nextColor());
            spawnIndex.put(id, (i * spawnCount / playerCount) % spawnCount);
            i++;

            HubAPI.setPlaying(id, GAME);
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                preparePlaying(player);
                teleportToSpawn(player);
            }
        }
        broadcastGame("&a&l===== battlefield 1 (占地1) 开始 =====");
        broadcastGame("&f用锄头把脚下的地面染成自己的颜色，占地最多者获胜！");
        broadcastGame("&7场地共 &e" + arena.totalBlocks() + " &7格，半径 &e" + arena.radius());
        startGameTimer();
        reportArena();
        return true;
    }

    private void startGameTimer() {
        if (timeTask != null) {
            timeTask.cancel();
            timeTask = null;
        }
        secondsLeft = Math.max(0, plugin.getConfig().getInt("game-seconds", 300));
        if (secondsLeft <= 0) {
            return;
        }
        timeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGameTime, 20L, 20L);
    }

    private void tickGameTime() {
        if (!running || ending) {
            return;
        }
        secondsLeft--;
        if (secondsLeft <= 0) {
            broadcastGame("&e时间到！按占地格数结算。");
            endGame(true);
            return;
        }
        for (Player player : onlineParticipants()) {
            Chat.actionBar(player, "&e剩余 " + secondsLeft + "s &7| 你占地 &f"
                    + scores.getOrDefault(player.getUniqueId(), 0) + " &7| 全场剩余 &f" + remaining);
        }
        if (secondsLeft == 30 || secondsLeft == 10) {
            broadcastGame("&e剩余 " + secondsLeft + " 秒！");
        }
        updateLiveBoard();
    }

    private void updateLiveBoard() {
        if (!running || ending) {
            return;
        }
        List<UUID> online = new ArrayList<>(states.keySet());
        online.sort(Comparator.comparingInt((UUID id) -> scores.getOrDefault(id, 0)).reversed()
                .thenComparing(id -> names.getOrDefault(id, "?"), String.CASE_INSENSITIVE_ORDER));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        for (UUID id : online) {
            lines.add(new LiveSidebar.Line("§e" + names.getOrDefault(id, "?"), scores.getOrDefault(id, 0)));
        }
        liveSidebar.update("landclaim", "&a&l占地为王 · 排名", online, lines);
    }

    // ---------- 占地 ----------

    public void claim(Player player, Block clicked) {
        if (!running || ending || !states.containsKey(player.getUniqueId())) {
            return;
        }
        UUID id = player.getUniqueId();
        // 以玩家准星指向的方块为目标，而不是脚下方块
        Block target = clicked;
        if (target == null || !arena.isFloorBlock(target.getLocation())) {
            return;
        }

        Location targetLocation = target.getLocation();
        if (arena.isClaimedBlock(target)) {
            return;
        }
        long key = pack(targetLocation.getBlockX(), targetLocation.getBlockZ());
        if (claimedBlocks.contains(key)) {
            return;
        }
        Set<Long> own = claims.computeIfAbsent(id, k -> new HashSet<>());
        if (!own.isEmpty() && !nearOwnClaim(targetLocation, own)) {
            return;
        }

        Material color = colors.getOrDefault(id, Material.WHITE_WOOL);
        target.setType(color, false);
        own.add(key);
        claimedBlocks.add(key);
        int score = scores.merge(id, 1, Integer::sum);
        remaining = Math.max(0, remaining - 1);

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 1.7f);
        player.spawnParticle(Particle.HAPPY_VILLAGER,
                targetLocation.clone().add(0.5, 1.0, 0.5), 8, 0.3, 0.3, 0.3, 0.01);
        Chat.actionBar(player, "&a+1 格 &7| 你占地 &f" + score + " &7| 全场剩余 &f" + remaining);

        checkArenaFull();
    }

    /** 场上格子全部被占满时直接结算。 */
    private void checkArenaFull() {
        if (!running || ending) {
            return;
        }
        if (remaining <= 0 || claimedBlocks.size() >= arena.totalBlocks()) {
            if (endTask == null) {
                endTask = Bukkit.getScheduler().runTaskLater(plugin, () -> endGame(true), 20L);
            }
        }
    }

    /** 曼哈顿距离判定：默认 radius=1 时只允许前后左右，不含斜角。 */
    private boolean nearOwnClaim(Location target, Set<Long> own) {
        int radius = claimRadius();
        for (long key : own) {
            int dx = Math.abs(unpackX(key) - target.getBlockX());
            int dz = Math.abs(unpackZ(key) - target.getBlockZ());
            if (dx + dz <= radius) {
                return true;
            }
        }
        return false;
    }

    private int claimRadius() {
        return Math.max(1, plugin.getConfig().getInt("claim-radius", 1));
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }

    // ---------- 结算 ----------

    /** 收场入口（I4）：先让会话 end()，再由 onEnd 回到 doEndGame。 */
    public void endGame(boolean natural) {
        pendingNatural = natural;
        if (session == null || session.state().isFinal()) {
            doEndGame();
            return;
        }
        session.end(EndReason.ROUND_END);
    }

    private boolean pendingNatural;

    /** 真正的收场逻辑（由 session.onEnd 回调）。 */
    void doEndGame() {
        boolean natural = pendingNatural;
        if (ending) {
            return;
        }
        ending = true;
        liveSidebar.hide("landclaim");
        running = false;
        if (timeTask != null) {
            timeTask.cancel();
            timeTask = null;
        }
        if (countdown != null) {
            countdown.cancel();
        }
        if (natural) {
            announceRanking();
        } else {
            broadcastGame("&7battlefield 1 (占地1) 本局已结束。");
        }
        int delay = Math.max(1, plugin.getConfig().getInt("end-delay-ticks", 60));
        Bukkit.getScheduler().runTaskLater(plugin, this::finishEnd, delay);
    }

    private void announceRanking() {
        List<UUID> ranked = new ArrayList<>(states.keySet());
        ranked.sort((a, b) -> {
            int cmp = Integer.compare(scores.getOrDefault(b, 0), scores.getOrDefault(a, 0));
            return cmp != 0 ? cmp : names.getOrDefault(a, "?").compareToIgnoreCase(names.getOrDefault(b, "?"));
        });
        broadcastGame("&b&l===== battlefield 1 (占地1) 结束 =====");
        List<UUID> firsts = new ArrayList<>();
        List<UUID> seconds = new ArrayList<>();
        List<UUID> thirds = new ArrayList<>();
        if (ranked.size() >= 1) firsts.add(ranked.get(0));
        if (ranked.size() >= 2) seconds.add(ranked.get(1));
        if (ranked.size() >= 3) thirds.add(ranked.get(2));
        Top3.broadcast(msg -> broadcastGame(msg), id -> names.getOrDefault(id, "?")
                + " &7(" + scores.getOrDefault(id, 0) + " 格)",
                firsts, seconds, thirds);
        Top3.award(stats, firsts, seconds, thirds);
        ArcadeBridge.report("landclaim", firsts, seconds, thirds);
        try {
            PlayerStats.save("landclaim", stats);
        } catch (Exception ex) {
            // 保存失败不阻塞结算，但要留痕（R3 失败可见）
            com.hub.diagnostics.Failures.recordAndWarn("LandClaim.保存玩家记录", ex);
        }
    }

    private void finishEnd() {
        for (UUID id : new ArrayList<>(states.keySet())) {
            Player player = Bukkit.getPlayer(id);
            PlayerState state = states.get(id);
            if (player != null && player.isOnline() && state != null) {
                state.apply(player);
                HubAPI.teleportToLobby(player);
            }
            HubAPI.clearGame(id);
        }
        states.clear();
        names.clear();
        scores.clear();
        colors.clear();
        spawnIndex.clear();
        claims.clear();
        claimedBlocks.clear();
        ending = false;
        running = false;
        remaining = 0;
        arena.resetFloor();
        reportArena();
        countdown.update();
    }

    public void shutdown() {
        liveSidebar.hideAll();
        if (countdown != null) {
            countdown.cancel();
        }
        if (timeTask != null) {
            timeTask.cancel();
            timeTask = null;
        }
        if (endTask != null) {
            endTask.cancel();
        }
        for (UUID id : new ArrayList<>(states.keySet())) {
            Player player = Bukkit.getPlayer(id);
            PlayerState state = states.get(id);
            if (player != null && player.isOnline() && state != null) {
                state.apply(player);
            }
            HubAPI.clearGame(id);
        }
        states.clear();
        if (running || ending) {
            arena.resetFloor();
        }
        running = false;
        ending = false;
    }

    // ---------- 状态 / 工具 ----------

    public void reportArena() {
        if (!arena.configured()) {
            return;
        }
        HubAPI.setArena(GAME, ARENA, (running || ending) ? GameState.PLAYING : GameState.WAITING,
                states.size(), (running || ending) ? "剩余 " + remaining + " 格" : null);
        HubAPI.setArenaSpectator(GAME, ARENA, arena.center());
    }

    private void prepareWaiting(Player player) {
        Location wait = arena.lobby() != null ? arena.lobby() : firstSpawnFor(player.getUniqueId());
        if (wait != null) {
            player.teleport(wait);
        }
        prepare(player);
        giveWaitingItems(player);
    }

    private void preparePlaying(Player player) {
        prepare(player);
        giveHoe(player);
    }

    private void prepare(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setExtraContents(new ItemStack[inventory.getExtraContents().length]);
        inventory.setItemInOffHand(null);

        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFallDistance(0f);
        player.setFireTicks(0);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setExhaustion(0f);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        double max = player.getAttribute(Attribute.MAX_HEALTH) != null
                ? player.getAttribute(Attribute.MAX_HEALTH).getValue() : 20.0;
        player.setHealth(max);
        player.setVelocity(new Vector(0, 0, 0));
    }

    private void giveHoe(Player player) {
        ItemStack hoe = new ItemStack(Material.DIAMOND_HOE);
        ItemMeta meta = hoe.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color("&a&l划地锄 &7(右键脚下)")));
            meta.lore(List.of(Chat.noItalic(Chat.color("&7把脚下的地面染成你的颜色"))));
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(hoeKey, PersistentDataType.BYTE, (byte) 1);
            hoe.setItemMeta(meta);
        }
        player.getInventory().setItem(0, hoe);
    }

    public boolean isHoe(ItemStack item) {
        if (item == null || item.getType() != Material.DIAMOND_HOE || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(hoeKey, PersistentDataType.BYTE);
    }

    // ---------- 等待室道具 ----------

    /** 旧配置没有 wait-items 段时写入默认值，方便 OP 在面板里直接改。 */
    private void ensureWaitItemDefaults() {
        if (plugin.getConfig().isConfigurationSection("wait-items")) {
            return;
        }
        plugin.getConfig().set("wait-items.start.material", "PAPER");
        plugin.getConfig().set("wait-items.start.name", "&a&l开始游戏 &7(右键)");
        plugin.getConfig().set("wait-items.start.lore", List.of("&7点击立刻开始本局"));
        plugin.getConfig().set("wait-items.leave.material", "BARRIER");
        plugin.getConfig().set("wait-items.leave.name", "&c&l退出房间 &7(右键)");
        plugin.getConfig().set("wait-items.leave.lore", List.of("&7点击离开等待室"));
        plugin.saveConfig();
    }

    private void giveWaitingItems(Player player) {
        player.getInventory().setItem(8, waitingItem("wait-items.leave", leaveKey,
                Material.BARRIER, "&c&l退出房间 &7(右键)", List.of("&7点击离开等待室")));
        if (player.hasPermission("landclaim.admin")) {
            player.getInventory().setItem(4, waitingItem("wait-items.start", startKey,
                    Material.PAPER, "&a&l开始游戏 &7(右键)", List.of("&7点击立刻开始本局")));
        }
    }

    private ItemStack waitingItem(String path, NamespacedKey key, Material defaultMaterial,
                                  String defaultName, List<String> defaultLore) {
        Material material = Material.matchMaterial(
                plugin.getConfig().getString(path + ".material", defaultMaterial.name()), false);
        if (material == null || !material.isItem()) {
            material = defaultMaterial;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = plugin.getConfig().getString(path + ".name", defaultName);
            List<String> lore = plugin.getConfig().isList(path + ".lore")
                    ? plugin.getConfig().getStringList(path + ".lore") : defaultLore;
            meta.displayName(Chat.noItalic(Chat.color(name)));
            meta.lore(lore.stream().map(line -> Chat.noItalic(Chat.color(line))).toList());
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isStartItem(ItemStack item) {
        return hasTag(item, startKey);
    }

    public boolean isLeaveItem(ItemStack item) {
        return hasTag(item, leaveKey);
    }

    private boolean hasTag(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public void useStartItem(Player player) {
        if (!running && !ending) {
            startGame(true);
        }
    }

    public void useLeaveItem(Player player) {
        if (states.containsKey(player.getUniqueId())) {
            leave(player, false);
        }
    }

    private Location firstSpawnFor(UUID id) {
        List<Location> spawns = arena.spawns();
        if (spawns.isEmpty()) {
            return arena.lobby();
        }
        int index = Math.floorMod(spawnIndex.getOrDefault(id, 0), spawns.size());
        return spawns.get(index).clone();
    }

    private void teleportToSpawn(Player player) {
        Location spawn = firstSpawnFor(player.getUniqueId());
        if (spawn != null) {
            player.teleport(spawn);
            player.setFallDistance(0f);
            player.setVelocity(new Vector(0, 0, 0));
        }
    }

    private Material nextColor() {
        for (Material material : COLORS) {
            if (!colors.containsValue(material)) {
                return material;
            }
        }
        return null;
    }

    private String colorName(Material material) {
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i] == material) {
                return COLOR_NAMES[i];
            }
        }
        return "白色";
    }

    private void broadcastWaiting(String message) {
        for (UUID id : states.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                Chat.send(player, message);
            }
        }
    }

    /** 只发给本局玩家，大厅/其他世界看不到局内播报。 */
    private void broadcastGame(String message) {
        for (UUID id : new ArrayList<>(states.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                Chat.sendTitled(player, message);
            }
        }
    }

    private List<Player> onlineParticipants() {
        List<Player> list = new ArrayList<>();
        for (UUID id : states.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                list.add(player);
            }
        }
        return list;
    }

    private void recordFirst(UUID id) {        stats.set("firsts." + id, stats.getInt("firsts." + id, 0) + 1);
        try {
            PlayerStats.save("landclaim", stats);
        } catch (IOException exception) {
            plugin.getLogger().warning("保存玩家记录失败: " + exception.getMessage());
        }
    }

    public int firsts(UUID id) {
        return stats == null ? 0 : stats.getInt("firsts." + id, 0);
    }

    /** 声明地盘（{@code docs/框架不变量.md} 的 I2）。 */
    public void publishRegions() {
        com.hub.region.Regions.begin("landclaim");
        if (arena != null) {
            com.hub.region.Regions.disc("landclaim", "", arena.getCenter(), arena.getRadius(),
                    com.hub.region.Regions.DEFAULT_DISC_HEIGHT);
        }
    }

    public LandClaimArena arena() {
        return arena;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isEnding() {
        return ending;
    }

    public boolean isPlaying(Player player) {
        return states.containsKey(player.getUniqueId());
    }

    public int remaining() {
        return remaining;
    }

    public int statesCount() {
        return states.size();
    }

    public Map<UUID, Integer> scores() {
        return new LinkedHashMap<>(scores);
    }

    public String nameOf(UUID id) {
        return names.getOrDefault(id, "?");
    }

    public Material colorOf(UUID id) {
        return colors.getOrDefault(id, Material.WHITE_WOOL);
    }

    public String colorNameOf(UUID id) {
        return colorName(colorOf(id));
    }
}
