package com.jumpfill;

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
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
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

/** 跳水填格的核心流程：等待、开局、落水填格、跳偏复位、结算。 */
public final class JumpFillManager {

    public static final String GAME = "跳水填格";
    public static final String ARENA = "水池";

    private static final Material[] COLORS = {
            Material.WHITE_CONCRETE, Material.ORANGE_CONCRETE,
            Material.MAGENTA_CONCRETE, Material.LIGHT_BLUE_CONCRETE,
            Material.YELLOW_CONCRETE, Material.LIME_CONCRETE,
            Material.PINK_CONCRETE, Material.GRAY_CONCRETE,
            Material.LIGHT_GRAY_CONCRETE, Material.CYAN_CONCRETE,
            Material.PURPLE_CONCRETE, Material.BLUE_CONCRETE,
            Material.BROWN_CONCRETE, Material.GREEN_CONCRETE,
            Material.RED_CONCRETE, Material.BLACK_CONCRETE
    };
    private static final String[] COLOR_NAMES = {
            "白色", "橙色", "品红", "淡蓝", "黄色", "黄绿", "粉色", "灰色",
            "淡灰", "青色", "紫色", "蓝色", "棕色", "绿色", "红色", "黑色"
    };

    private final JumpFillPlugin plugin;
    private final JumpFillArena arena;
    private final NamespacedKey startItemKey;
    private final Map<UUID, PlayerState> states = new LinkedHashMap<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Map<UUID, Integer> scores = new LinkedHashMap<>();
    private final Map<UUID, Material> colors = new LinkedHashMap<>();
    private final Map<UUID, Integer> startIndex = new LinkedHashMap<>();
    private final Map<UUID, Double> startY = new LinkedHashMap<>();
    private final Set<UUID> processing = new HashSet<>();
    private final Set<UUID> airborne = new HashSet<>();
    private final Set<UUID> wasOnGround = new HashSet<>();
    private final Set<UUID> walkedOff = new HashSet<>();

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
    private final java.util.Map<String, JumpFillSession> sessions = new java.util.LinkedHashMap<>();
    private String pendingEndMessage;
    private JumpFillSession session;

    private JumpFillSession ensureSession() {
        String key = "main";
        JumpFillSession s = sessions.get(key);
        if (s == null || s.state().isFinal()) {
            s = new JumpFillSession(this, key);
            sessions.put(key, s);
        }
        session = s;
        return s;
    }

    private boolean running;
    private boolean ending;
    private int remaining;
    private BukkitTask endTask;
    private final LiveSidebar liveSidebar;
    private BukkitTask scanTask;
    private BukkitTask timeTask;
    private int secondsLeft;

    public JumpFillManager(JumpFillPlugin plugin) {
        this.liveSidebar = new LiveSidebar(plugin);
        this.plugin = plugin;
        this.arena = new JumpFillArena(plugin);
        this.startItemKey = new NamespacedKey(plugin, "start_item");
    }

    public void load() {
        startCountdown();
        arena.load();
        loadStats();
        createCountdown();
        reportArena();
        plugin.getLogger().info("场地" + (arena.ready() ? "已配置" : "未配置")
                + "：水域 " + arena.width() + "x" + arena.depth()
                + "，出发点 " + arena.starts().size() + " 个。");
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
                return JumpFillManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return JumpFillManager.this.maxPlayers();
            }

            @Override
            public boolean hasOpQueued() {
                for (UUID id : states.keySet()) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null && player.hasPermission("jumpfill.admin")) {
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
                        Chat.actionBar(player, "&b" + secondsLeft + "s 后开始…");
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

                plugin.getLogger().info("[JumpFill] 主持人 " + hname

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
        if (!player.hasPermission("jumpfill.play")) {
            Chat.send(player, "&c你没有加入该游戏的权限。");
            return false;
        }
        if (states.size() >= maxPlayers()) {
            Chat.send(player, "&c人数已满（最多 " + maxPlayers() + " 人）。");
            return false;
        }
        if (!arena.ready()) {
            Chat.send(player, "&c场地还没配置好：请管理员先设置水域和至少一个高台出发点。");
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
            plugin.getLogger().warning("[JumpFill] " + player.getName() + " 已在别的对局，拒绝加入");
            return false;
        }
        names.put(id, player.getName());
        scores.put(id, 0);
        colors.put(id, color);
        int index = (states.size() - 1) % arena.starts().size();
        startIndex.put(id, index);
        startY.put(id, arena.starts().get(index).getY());

        HubAPI.setGame(id, GAME);
        HubAPI.announceJoin(plugin.getName(), player.getName());
        prepareWaiting(player);
        countdown.update();
        reportArena();
        Chat.send(player, "&a已加入跳水填格，你的颜色：&f" + colorName(color) + "&a。");
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
        startIndex.remove(id);
        startY.remove(id);
        processing.remove(id);
        airborne.remove(id);
        wasOnGround.remove(id);
        walkedOff.remove(id);

        if (player.isOnline()) {
            unhideNametag(player);
            state.apply(player);
            refreshTitle(player);
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
        arena.fillWater();
        remaining = arena.volume();
        running = true;
        if (session != null && !session.state().isFinal()) {
            session.start();
        }
        ending = false;

        int i = 0;
        int playerCount = Math.max(1, states.size());
        int startCount = arena.starts().size();
        for (UUID id : new ArrayList<>(states.keySet())) {
            scores.put(id, 0);
            colors.computeIfAbsent(id, key -> nextColor());
            // 把出发点尽量平均分给所有玩家（4 个点 2 人 → 第 1、3 个点）
            int index = (i * startCount / playerCount) % startCount;
            startIndex.put(id, index);
            startY.put(id, arena.starts().get(index).getY());
            i++;

            HubAPI.setPlaying(id, GAME);
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                preparePlaying(player);
                teleportToStart(player);
                // 对局中身体隐身，但保留发光轮廓，其他玩家能看到位置
                player.setInvisible(true);
                player.setGlowing(true);
            }
        }
        broadcastGame("&b&l===== 跳水填格 开始 =====");
        broadcastGame("&f水池共 &e" + arena.volume() + " &f格，跳进水里把格子变成自己的颜色！");
        int limit = plugin.getConfig().getInt("game-seconds", 180);
        broadcastGame(limit > 0 ? "&7限时 &e" + limit + " &7秒，时间到按得分结算。"
                : "&7本局不限时。");
        broadcastGame("&7每人颜色：" + colorList());
        hideNametags();
        startGameTimer();
        if (scanTask != null) {
            scanTask.cancel();
        }
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWaterCheck, 1L, 1L);
        reportArena();
        return true;
    }

    private static final String HIDE_TEAM = "jumpfill_hide";

    /** 对局中隐藏头顶 ID/头衔：只隐藏名牌，不隐藏实体（配合隐身+发光轮廓）。 */
    private void hideNametags() {
        Team team = nametagTeam();
        for (Player player : onlineParticipants()) {
            team.addEntry(player.getName());
        }
    }

    private void hideNametag(Player player) {
        nametagTeam().addEntry(player.getName());
    }

    private void unhideNametag(Player player) {
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(HIDE_TEAM);
        if (team != null) {
            team.removeEntry(player.getName());
        }
    }

    private Team nametagTeam() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(HIDE_TEAM);
        if (team == null) {
            team = board.registerNewTeam(HIDE_TEAM);
        }
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        // 附带关闭碰撞，隐藏中的玩家不会互相挡住落点
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        return team;
    }

    /** 通知头衔插件重新应用头顶队伍（游戏里临时占用过计分板队伍）。 */
    private void refreshTitle(Player player) {
        for (Class<?> type : Bukkit.getServicesManager().getKnownServices()) {
            if (!"com.example.playertitles.api.TitleProvider".equals(type.getName())) {
                continue;
            }
            Object provider = Bukkit.getServicesManager().load(type);
            if (provider == null) {
                return;
            }
            try {
                type.getMethod("refresh", UUID.class).invoke(provider, player.getUniqueId());
            } catch (ReflectiveOperationException ignored) {
                // 旧版头衔插件没有 refresh，退回世界切换时自动刷新
            }
            return;
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

    /** 单局限时倒计时；game-seconds <= 0 表示不限时。 */
    private void startGameTimer() {
        if (timeTask != null) {
            timeTask.cancel();
            timeTask = null;
        }
        secondsLeft = Math.max(0, plugin.getConfig().getInt("game-seconds", 180));
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
            broadcastGame("&e时间到！按当前得分结算。");
            endGame(true);
            return;
        }
        for (Player player : onlineParticipants()) {
            Chat.actionBar(player, "&e剩余 " + secondsLeft + "s &7| 剩余水格 &f" + remaining
                    + " &7| 得分 &f" + scores.getOrDefault(player.getUniqueId(), 0));
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
        liveSidebar.update("jumpfill", "&b&l跳水填格 · 排名", online, lines);
    }

    private String colorList() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, Material> entry : colors.entrySet()) {
            if (sb.length() > 0) {
                sb.append(" &7· ");
            }
            sb.append("&f").append(names.getOrDefault(entry.getKey(), "?")).append(" &7= &f")
                    .append(colorName(entry.getValue()));
        }
        return sb.toString();
    }

    // ---------- 落点判定 ----------

    public void onMove(Player player, Location from, Location to) {
        if (!running || ending) {
            return;
        }
        UUID id = player.getUniqueId();
        if (!states.containsKey(id) || processing.contains(id)) {
            return;
        }
        trackLeaveGround(player, from, to);
        if (tryScore(player)) {
            return;
        }
        if (to.getY() < arena.missY()) {
            miss(player, "掉出了水池");
            return;
        }
        if (!player.isOnGround()) {
            double start = startY.getOrDefault(id, arena.waterY() + 10.0);
            if (to.getY() < start - 0.5) {
                airborne.add(id);
            }
            return;
        }
        if (airborne.contains(id)) {
            miss(player, "没跳进水里");
        }
    }

    /** 检测“没按跳跃、直接走下高台”，只提醒一次。 */
    private void trackLeaveGround(Player player, Location from, Location to) {
        UUID id = player.getUniqueId();
        if (player.isOnGround()) {
            wasOnGround.add(id);
            walkedOff.remove(id);
            return;
        }
        if (wasOnGround.remove(id)) {
            double dy = to.getY() - from.getY();
            if (dy <= 0.01 && walkedOff.add(id)) {
                Chat.actionBar(player, "&e请按跳跃键跳下去（走下去不算跳水）");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.8f);
            }
        }
    }

    /** 只有人真正落到水面以下、且中心所在的水位方块还是水，才计分。
     * 站在池边（脚 Y = 水面顶）不满足高度条件；水面上方坠落时也不满足。
     */
    private boolean tryScore(Player player) {
        Location loc = player.getLocation();
        if (loc.getWorld() == null || !arena.waterConfigured()) {
            return false;
        }
        // 水源方块顶面约 +0.875；用 0.9，站在池边（+1.0）仍不算
        if (loc.getY() > arena.waterY() + 0.9) {
            return false;
        }
        if (loc.getY() < arena.waterY() - 1.0) {
            return false;
        }
        Block water = loc.getWorld().getBlockAt(loc.getBlockX(), arena.waterY(), loc.getBlockZ());
        if (!isArenaWater(water)) {
            return false;
        }
        score(player, water);
        return true;
    }

    private boolean isArenaWater(Block block) {
        return block.getType() == Material.WATER && arena.containsBlock(block.getLocation());
    }

    /** 每 tick 兜底检查一次，避免快速落水时移动事件漏判。 */
    private void tickWaterCheck() {
        if (!running || ending) {
            return;
        }
        for (Player player : onlineParticipants()) {
            if (processing.contains(player.getUniqueId())) {
                continue;
            }
            tryScore(player);
        }
    }

    private void score(Player player, Block block) {
        UUID id = player.getUniqueId();
        Material glass = colors.getOrDefault(id, Material.WHITE_STAINED_GLASS);
        arena.setGlass(block.getLocation(), glass);
        int score = scores.merge(id, 1, Integer::sum);
        remaining = Math.max(0, remaining - 1);

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
        player.spawnParticle(Particle.HAPPY_VILLAGER,
                block.getLocation().add(0.5, 1.0, 0.5), 12, 0.3, 0.3, 0.3, 0.01);
        Chat.actionBar(player, "&a+1 &7| 得分 &f" + score + " &7| 剩余 &f" + remaining + " &7格");

        if (remaining == 0) {
            broadcastGame("&e" + player.getName() + " &f填上了最后一格！");
            if (endTask == null) {
                endTask = Bukkit.getScheduler().runTaskLater(plugin, () -> endGame(true), 40L);
            }
        } else if (remaining <= 5 || remaining % 10 == 0) {
            broadcastGame("&e" + player.getName() + " &f填了一格！剩余 &e" + remaining + " &f格");
        }
        returnToStart(player);
    }

    private void miss(Player player, String reason) {
        Chat.actionBar(player, "&c" + reason + "，回到高台");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
        returnToStart(player);
    }

    private void returnToStart(Player player) {
        UUID id = player.getUniqueId();
        if (!processing.add(id)) {
            return;
        }
        int delay = Math.max(1, plugin.getConfig().getInt("respawn-delay-ticks", 8));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            processing.remove(id);
            airborne.remove(id);
            if (!running || ending || !states.containsKey(id)) {
                return;
            }
            Player online = Bukkit.getPlayer(id);
            if (online == null || !online.isOnline()) {
                return;
            }
            if (!arena.starts().isEmpty()) {
                int next = Math.floorMod(startIndex.getOrDefault(id, 0) + 1, arena.starts().size());
                startIndex.put(id, next);
                startY.put(id, arena.starts().get(next).getY());
            }
            Location start = startFor(id);
            if (start != null) {
                online.teleport(start);
                online.setFallDistance(0f);
                online.setVelocity(new Vector(0, 0, 0));
                walkedOff.remove(id);
                markGroundedLater(id);
            }
        }, delay);
    }

    private Location startFor(UUID id) {
        List<Location> starts = arena.starts();
        if (starts.isEmpty()) {
            return arena.lobby();
        }
        int index = Math.floorMod(startIndex.getOrDefault(id, 0), starts.size());
        return starts.get(index).clone();
    }

    /** 给重生事件用：回到自己的高台。 */
    /**
     * I3 A 批：重生后下一 tick 收尾（原写在 {@code JumpFillListener.onRespawn} 里）。
     * 由 {@link JumpFillSession#onPlayerRespawn} 调用 —— 游戏模块不再监听 {@code PlayerRespawnEvent}。
     */
    void respawnNextTick(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> respawn(player));
    }

    public void respawn(Player player) {
        UUID id = player.getUniqueId();
        if (!running || !states.containsKey(id)) {
            return;
        }
        processing.remove(id);
        airborne.remove(id);
        prepare(player);
        hideNametag(player);
        teleportToStart(player);
        player.setInvisible(true);
        player.setGlowing(true);
    }

    public Location startForPublic(UUID id) {
        return startFor(id);
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
        liveSidebar.hide("jumpfill");
        running = false;
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        if (timeTask != null) {
            timeTask.cancel();
            timeTask = null;
        }
        if (countdown != null) {
            countdown.cancel();
        }
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
        if (natural) {
            announceRanking();
        } else {
            broadcastGame("&7跳水填格本局已结束。");
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
        broadcastGame("&b&l===== 跳水填格 结束 =====");
        List<UUID> firsts = new ArrayList<>();
        List<UUID> seconds = new ArrayList<>();
        List<UUID> thirds = new ArrayList<>();
        if (ranked.size() >= 1) firsts.add(ranked.get(0));
        if (ranked.size() >= 2) seconds.add(ranked.get(1));
        if (ranked.size() >= 3) thirds.add(ranked.get(2));
        Top3.broadcast(msg -> broadcastGame(msg), id -> names.getOrDefault(id, "?")
                + " &7(" + scores.getOrDefault(id, 0) + " 分)",
                firsts, seconds, thirds);
        Top3.award(stats, firsts, seconds, thirds);
        ArcadeBridge.report("jumpfill", firsts, seconds, thirds);
        try {
            PlayerStats.save("jumpfill", stats);
        } catch (Exception ex) {
            // 保存失败不阻塞结算，但要留痕（R3 失败可见）
            com.hub.diagnostics.Failures.recordAndWarn("JumpFill.保存玩家记录", ex);
        }
    }

    private void finishEnd() {
        for (UUID id : new ArrayList<>(states.keySet())) {
            Player player = Bukkit.getPlayer(id);
            PlayerState state = states.get(id);
            if (player != null && player.isOnline() && state != null) {
                unhideNametag(player);
                state.apply(player);
                refreshTitle(player);
                HubAPI.teleportToLobby(player);
            }
            HubAPI.clearGame(id);
        }
        states.clear();
        names.clear();
        scores.clear();
        colors.clear();
        startIndex.clear();
        startY.clear();
        processing.clear();
        airborne.clear();
        wasOnGround.clear();
        walkedOff.clear();
        ending = false;
        running = false;
        remaining = 0;
        arena.restore();
        reportArena();
        countdown.update();
    }

    public void shutdown() {
        liveSidebar.hideAll();
        if (countdown != null) {
            countdown.cancel();
        }
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
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
                unhideNametag(player);
                state.apply(player);
                refreshTitle(player);
            }
            HubAPI.clearGame(id);
        }
        states.clear();
        wasOnGround.clear();
        walkedOff.clear();
        if (running || ending) {
            arena.restore();
        }
        running = false;
        ending = false;
    }

    // ---------- 状态 ----------

    public void reportArena() {
        if (!arena.waterConfigured()) {
            return;
        }
        HubAPI.setArena(GAME, ARENA, (running || ending) ? GameState.PLAYING : GameState.WAITING,
                states.size(), (running || ending) ? "剩余 " + remaining + " 格" : null);
        HubAPI.setArenaSpectator(GAME, ARENA, arena.lobby());
    }

    private void prepareWaiting(Player player) {
        Location wait = arena.lobby() != null ? arena.lobby() : startFor(player.getUniqueId());
        if (wait != null) {
            player.teleport(wait);
        }
        prepare(player);
        if (player.hasPermission("jumpfill.admin")) {
            giveStartItem(player);
        }
    }

    /** 管理员在等待室的"开始游戏"纸，右键立刻开局。 */
    private void giveStartItem(Player player) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color("&a&l开始游戏 &7(右键)")));
            meta.lore(List.of(Chat.noItalic(Chat.color("&7点击立刻开始本局"))));
            meta.getPersistentDataContainer().set(startItemKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        player.getInventory().setItem(4, item);
    }

    public boolean isStartItem(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(startItemKey, PersistentDataType.BYTE);
    }

    public void useStartItem(Player player) {
        if (!running && !ending) {
            startGame(true);
        }
    }

    private void preparePlaying(Player player) {
        prepare(player);
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

    private void teleportToStart(Player player) {
        Location start = startFor(player.getUniqueId());
        if (start != null) {
            player.teleport(start);
            player.setFallDistance(0f);
            startY.put(player.getUniqueId(), start.getY());
            walkedOff.remove(player.getUniqueId());
            markGroundedLater(player.getUniqueId());
        }
    }

    /** 传送后延迟把玩家标记为“站在地面”，用于识别下一步是不是直接走下去。 */
    private void markGroundedLater(UUID id) {
        wasOnGround.remove(id);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online != null && online.isOnline() && online.isOnGround()) {
                wasOnGround.add(id);
            }
        }, 5L);
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

    private void loadStats() {
        stats = PlayerStats.load("jumpfill", new File(plugin.getDataFolder(), "data.yml"));
    }

    private void recordFirst(UUID id) {
        stats.set("firsts." + id, stats.getInt("firsts." + id, 0) + 1);
        try {
            PlayerStats.save("jumpfill", stats);
        } catch (IOException exception) {
            plugin.getLogger().warning("保存玩家记录失败: " + exception.getMessage());
        }
    }

    public int firsts(UUID id) {
        return stats == null ? 0 : stats.getInt("firsts." + id, 0);
    }

    /** 声明地盘（{@code docs/框架不变量.md} 的 I2）。 */
    public void publishRegions() {
        com.hub.region.Regions.begin("jumpfill");
        if (arena != null) {
            com.hub.region.Regions.box("jumpfill", "", arena.getPos1(), arena.getPos2());
        }
    }

    public JumpFillArena arena() {
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

    public boolean isProcessing(Player player) {
        return processing.contains(player.getUniqueId());
    }

    public boolean isAirborne(Player player) {
        return airborne.contains(player.getUniqueId());
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
        return colors.getOrDefault(id, Material.WHITE_STAINED_GLASS);
    }

    public String colorNameOf(UUID id) {
        return colorName(colorOf(id));
    }
}
