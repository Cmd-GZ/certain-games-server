package com.memoryflip;

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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** 记忆翻牌核心：每人一面翻牌墙，翻两张配对，最快翻完获胜。 */
public final class MemoryFlipManager {

    public static final String GAME = "记忆翻牌";
    public static final String ARENA = "主场地";

    public enum State {
        WAITING, RUNNING
    }

    private final MemoryFlipPlugin plugin;
    /** 没有房间上下文时用的那一份（老路径，数据仍读 config.yml）。 */
    private final MemoryFlipArena defaultArena;
    private final LiveSidebar liveSidebar;
    private final Random random = new Random();
    private StartCountdown startCountdown;

    /**
     * 对局会话（I1/I4/I5）：**按场地名索引**。
     *
     * <p>为什么单场地也要用 Map：上游经常给小游戏加地图，一旦有第二张图，
     * "一个字段"的写法就要重写成"一张表"。这里先从会话层统一成多场地形状，
     * 将来加图时**不用改这一层的形状**（D22）。
     *
     * <p>注意仍然存在的限制：这个游戏的**内部状态**（game().state / game().waiting / game().playing）
     * 目前还是全局单份，真正支持两场同跑需要把那部分也按场地拆开 —— 见改动记录。
     */
    private final java.util.Map<String, MemoryFlipSession> sessions = new java.util.LinkedHashMap<>();

    /** 当前场地键（单图时期固定；加图后由配置/场地对象提供）。 */
    private String arenaKey() {
        return "main";
    }

    /** 每房间一份的一局状态（D22）；**行为还没切过去**，见 MemoryGame 的迁移步骤。 */
    private final com.hub.room.RoomScoped<MemoryGame> games = new com.hub.room.RoomScoped<>("memoryflip");

    /** 当前正在处理的那一局（没有绑定时用当前房间 / 场地名兜底）。 */
    private MemoryGame bound;

    MemoryGame game() {
        if (bound == null) {
            // **优先绑到当前房间**：以前这里直接 use("main")，于是派对/控制台驱动的开局
            // 全都落在同一份 "main" 状态上 —— 两个房间会互相覆盖（2026-11-06 两房验证发现）。
            String roomId = com.hub.room.RoomContext.currentRoomId();
            bound = use(roomId == null || roomId.isBlank() ? arenaKey() : roomId);
        }
        return bound;
    }

    /** 进入某个房间的处理上下文：把 bound 切到那一间（没有上下文就维持现状）。 */
    void bindRoom() {
        String roomId = com.hub.room.RoomContext.currentRoomId();
        if (roomId != null && !roomId.isBlank()) {
            use(roomId);
        }
    }

    /** 切到某个房间的那一局（取或建）。 */
    MemoryGame use(String key) {
        MemoryGame g = games.ofKey(key, k -> newGame(k));
        bound = g;
        return g;
    }

    MemoryGame use(Player p) {
        return use(games.keyOf(p, arenaKey()));
    }

    /** 新建一局：连同它自己的等待倒计时一起建（D22：每房间一个倒计时）。 */
    private MemoryGame newGame(String key) {
        MemoryGame g = new MemoryGame(key, arenaKey(), plugin);
        g.countdown = new WaitCountdown(plugin, new GameHost(g));
        return g;
    }

    /** 这个玩家所在房间的那一局状态（取或建）。 */
    MemoryGame gameFor(org.bukkit.entity.Player p, String arenaName) {
        return games.of(p, arenaName, key -> newGame(key));
    }

    /** 当前房间的那一局状态（给没有玩家引用的 tick 用）；没有房间上下文时返回 null。 */
    MemoryGame currentGame() {
        String roomId = com.hub.room.RoomContext.currentRoomId();
        return roomId == null ? null : games.peek(roomId);
    }

    private MemoryFlipSession ensureSession() {
        return ensureSession(game());
    }

    /** 按**房间键**取会话（D22）：同一游戏两个房间时各自一份；没有房间时键就是场地名。 */
    private MemoryFlipSession ensureSession(MemoryGame g) {
        MemoryFlipSession s = g.session;
        if (s == null || s.state().isFinal()) {
            s = new MemoryFlipSession(this, g.key);
            g.session = s;
        }
        return s;
    }

    // ---- 局内状态全部在 MemoryGame 里（每房间一份，D22）；下面是"当前房间"的入口 ----

    private YamlConfiguration data;

    public MemoryFlipManager(MemoryFlipPlugin plugin) {
        this.plugin = plugin;
        this.defaultArena = new MemoryFlipArena(plugin, "main");
        this.defaultArena.load();
        this.liveSidebar = new LiveSidebar(plugin);
        // D22：每房间一个 WaitCountdown（在 newGame() 里创建），见 GameHost
    }

    // ---------- 配置 ----------

    private int cfgInt(String key, int def) {
        return plugin.getConfig().getInt(key, def);
    }

    private Material cfgMaterial(String key, Material def) {
        Material material = Material.matchMaterial(plugin.getConfig().getString(key, def.name()), false);
        return material != null && material.isItem() ? material : def;
    }

    public int minPlayers() {
        return Math.max(2, cfgInt("min-players", 2));
    }

    public int maxPlayers() {
        return Math.max(minPlayers(), cfgInt("max-players", 16));
    }

    /** 实际可用人数：不能超过已建好的翻牌墙数量。 */
    public int effectiveMaxPlayers() {
        int boards = arena().getBoards().size();
        return boards <= 0 ? maxPlayers() : Math.min(maxPlayers(), boards);
    }

    public int gameSeconds() {
        return Math.max(10, cfgInt("game-seconds", 300));
    }

    private int boardRows() {
        return Math.max(2, Math.min(8, cfgInt("board.rows", 4)));
    }

    private int boardCols() {
        int cols = Math.max(2, Math.min(8, cfgInt("board.cols", 4)));
        if ((boardRows() * cols) % 2 != 0) {
            cols++;
        }
        return cols;
    }

    public int totalPairs() {
        return boardRows() * boardCols() / 2;
    }

    private int reviewDelayTicks() {
        return Math.max(5, Math.min(100, cfgInt("review-delay-ticks", 20)));
    }

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

    public void load() {
        ensureDefaults();
        data = PlayerStats.load("memoryflip", new File(plugin.getDataFolder(), "data.yml"));
        World world = defaultArena.getLobby() != null ? defaultArena.getLobby().getWorld() : null;
        if (world == null && !defaultArena.getBoards().isEmpty()) {
            world = defaultArena.getBoards().get(0).stand.getWorld();
        }
        game().questionItem = null;
        game().checkItem = null;
        if (world != null) {
            MapView question = MapArt.create(world, false);
            MapView check = MapArt.create(world, true);
            game().questionItem = MapArt.item(question);
            game().checkItem = MapArt.item(check);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HubAPI.registerGame(GAME, GAME, "FILLED_MAP", "b");
            reportArena();
        }, 40L);
        plugin.getLogger().info("场地" + (defaultArena.isReady() ? "已配置" : "未配置（缺 " + defaultArena.missing()
                + "）") + "（每个房间各自一份翻板数据）");
    }

    public void reload() {
        if (game().state != State.WAITING) {
            return;
        }
        load();
    }

    public void shutdown() {
        cancelTasks();
        liveSidebar.hideAll();
        restoreFrames();
        for (UUID id : game().playing) {
            showNameTag(Bukkit.getPlayer(id));
        }
        saveData();
    }

    private void ensureDefaults() {
        boolean changed = false;
        changed |= setDefault("min-players", 2);
        changed |= setDefault("max-players", 16);
        changed |= setDefault("game-seconds", 300);
        changed |= setDefault("countdown-seconds", 3);
        changed |= setDefault("review-delay-ticks", 20);
        changed |= setDefault("board.rows", 4);
        changed |= setDefault("board.cols", 4);
        changed |= setDefault("board.spacing", 6);
        changed |= setDefault("board.wall-material", "SMOOTH_STONE");
        changed |= setDefault("board.floor-material", "SMOOTH_STONE");
        changed |= setDefault("board.items", List.of("DIAMOND", "EMERALD", "GOLD_INGOT", "IRON_INGOT",
                "APPLE", "BREAD", "COAL", "REDSTONE", "LAPIS_LAZULI", "QUARTZ",
                "SLIME_BALL", "BLAZE_ROD", "ENDER_PEARL", "GHAST_TEAR",
                "PRISMARINE_SHARD", "AMETHYST_SHARD"));
        changed |= setDefault("lobby", "");
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

    /** **当前房间**的场地（翻板/等待点）；没有上下文时退回 main 那一份。 */
    public MemoryFlipArena arena() {
        String roomId = com.hub.room.RoomContext.currentRoomId();
        if (roomId == null || roomId.isBlank()) {
            return defaultArena;
        }
        return games.ofKey(roomId, k -> newGame(k)).arena;
    }

    /** 某个玩家所在房间的场地（玩家在房间里打命令时用这个）。 */
    public MemoryFlipArena arenaFor(org.bukkit.entity.Player p) {
        return gameFor(p, arenaKey()).arena;
    }

    public State state() {
        return game().state;
    }

    public boolean isRunning() {
        return game().state == State.RUNNING;
    }

    public boolean isWaiting(Player player) {
        return game().waiting.contains(player.getUniqueId());
    }

    public boolean isPlaying(Player player) {
        return game().playing.contains(player.getUniqueId());
    }

    public boolean isInGame(Player player) {
        return isWaiting(player) || isPlaying(player);
    }

    public boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission("memory.admin");
    }

    public int waitingCount() {
        return game().waiting.size();
    }

    public int playingCount() {
        return game().playing.size();
    }

    public void saveAll() {
        arena().save();
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
        UUID host = game().countdown.currentHost();
        return host != null && host.equals(p.getUniqueId());
    }

    public void join(Player player) {
        UUID id = player.getUniqueId();
        if (game().state != State.WAITING) {
            Chat.send(player, "&c对局正在进行中，请等这一局结束后再加入。");
            return;
        }
        if (game().waiting.contains(id)) {
            Chat.send(player, "&e你已经在等待队列中。");
            return;
        }
        if (game().waiting.size() >= effectiveMaxPlayers()) {
            Chat.send(player, "&c等待队列已满（" + effectiveMaxPlayers() + " 人）。");
            return;
        }
        if (!arena().isReady()) {
            Chat.send(player, "&c场地还没配置完整（缺 " + arena().missing() + "）。");
            return;
        }
        game().waiting.add(id);
        game().joinTick.put(id, (long) Bukkit.getCurrentTick());
        game().joinSeed.put(id, java.util.concurrent.ThreadLocalRandom.current().nextLong());
        // D22：会话键按玩家所在房间算（单房间/无房间时仍是 "main"，行为不变）
        ensureSession(game());
        if (game().session != null && !game().session.join(player)) {
            plugin.getLogger().warning("[MemoryFlip] " + player.getName() + " 加入归属链失败（见 [被吞异常]）");
        }
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        resetPlayer(player);
        player.teleport(arena().getLobby());
        giveWaitItems(player);
        HubAPI.setGame(id, GAME);
        HubAPI.announceJoin(plugin.getName(), player.getName());
        Chat.send(player, "&a已加入" + GAME + " &7(" + game().waiting.size() + "/" + effectiveMaxPlayers() + ")");
        updateCountdown();
        reportArena();
    }

    public void leave(Player player, boolean silent) {
        // I1：从归属链上摘掉自己 —— 漏了这行，玩家会卡在"已在等待中"，
        // 之后点任何别的游戏都会被拒绝（只剩重启服务器能救）。
        if (game().session != null && !game().session.state().isFinal()) {
            game().session.leave(player);
        }
        UUID id = player.getUniqueId();
        boolean wasWaiting = game().waiting.remove(id);
        boolean wasPlaying = game().playing.remove(id);
        if (!wasWaiting && !wasPlaying) {
            if (!silent) {
                Chat.send(player, "&e你不在等待队列或对局中。");
            }
            return;
        }
        PlayState st = game().states.remove(id);
        if (st != null) {
            resetBoard(st);
        }
        showNameTag(player);
        clearGameState(player);
        HubAPI.clearGame(id);
        restorePlayer(player);
        if (!silent) {
            HubAPI.teleportToLobby(player);
            Chat.send(player, "&e你已离开" + GAME + "。");
        }
        if (game().state == State.RUNNING && wasPlaying) {
            if (game().playing.isEmpty() || allFinished()) {
                endGame("&7对局结束。");
                return;
            }
            updateLiveBoard();
        } else if (game().state == State.WAITING) {
            updateCountdown();
        }
        reportArena();
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        boolean wasWaiting = game().waiting.remove(id);
        boolean wasPlaying = game().playing.remove(id);
        if (!wasWaiting && !wasPlaying) {
            return;
        }
        PlayState st = game().states.remove(id);
        if (st != null) {
            resetBoard(st);
        }
        showNameTag(player);
        HubAPI.clearGame(id);
        if (game().state == State.RUNNING && wasPlaying && (game().playing.isEmpty() || allFinished())) {
            endGame("&7对局结束。");
            return;
        }
        if (game().state == State.WAITING) {
            updateCountdown();
        }
        reportArena();
    }

    private void updateCountdown() {
        MemoryGame g = game();
        if (g.state != State.WAITING) {
            g.countdown.cancel();
            return;
        }
        g.countdown.update();
    }

    // ---------- 开局 ----------

    public void startGame(boolean force) {
        if (game().state != State.WAITING) {
            return;
        }
        if (!arena().isReady()) {
            for (UUID id : game().waiting) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Chat.send(p, "&c场地未配置完整（缺 " + arena().missing() + "），无法开始。");
                }
            }
            return;
        }
        List<UUID> ready = new ArrayList<>();
        for (UUID id : game().waiting) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                ready.add(id);
            }
        }
        if (ready.isEmpty()) {
            return;
        }
        if (ready.size() < minPlayers() && !force) {
            updateCountdown();
            return;
        }
        if (ready.size() > arena().getBoards().size()) {
            for (UUID id : ready) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Chat.send(p, "&c翻牌墙数量不够（当前 " + arena().getBoards().size() + " 面），请管理员 /memory build。");
                }
            }
            return;
        }
        if (startCountdown().begin("memoryflip", ready, () -> startGame(force))) {
            return;
        }
        game().countdown.cancel();
        game().state = State.RUNNING;
        if (game().session != null) {
            game().session.start();
        }
        game().waiting.clear();
        game().playing.clear();
        game().playing.addAll(ready);
        game().states.clear();
        game().secondsLeft = gameSeconds();
        game().startedAt = System.currentTimeMillis();
        cancelTasks();

        // 每局重新播种，保证每局的物品组合与牌面位置都不一样
        Random gameRandom = new Random(System.nanoTime());
        List<MemoryFlipArena.Board> boards = new ArrayList<>(arena().getBoards());
        Collections.shuffle(boards, gameRandom);
        List<UUID> order = new ArrayList<>(game().playing);
        Collections.shuffle(order, gameRandom);
        int index = 0;
        for (UUID id : order) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            MemoryFlipArena.Board board = boards.get(index++);
            PlayState st = prepareBoard(id, board, index - 1, gameRandom);
            if (st == null) {
                continue;
            }
            game().states.put(id, st);
            clearGameState(p);
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20);
            p.setFoodLevel(20);
            p.teleport(board.stand);
            hideNameTag(p);
            HubAPI.setPlaying(id, GAME);
            Chat.title(p, "&b&l" + GAME, "&f翻开两张相同的物品！", 2);
        }
        game().playing.retainAll(game().states.keySet());
        broadcast("&6&l========== " + GAME + " 开始 ==========");
        broadcast("&f左键/右键翻开物品展示框，两张相同就得分；先翻完 " + totalPairs() + " 对的人获胜。");
        updateActionBar();
        updateLiveBoard();
        reportArena();
        game().gameTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGame, 20L, 20L);
    }

    private PlayState prepareBoard(UUID id, MemoryFlipArena.Board board, int boardIndex, Random gameRandom) {
        PlayState st = new PlayState();
        st.boardIndex = boardIndex;
        int total = boardRows() * boardCols();
        st.items = new Material[total];
        st.matched = new boolean[total];
        // 按存档位置收集展示框；同一面框只认一次，缺失/重复的自动补一个。
        // 之前旧场地残留的展示框会被重复认领，导致 16 个牌位里只有 9 个真正拿到地图。
        if (new HashSet<>(board.frames).size() < board.frames.size()) {
            plugin.getLogger().warning("翻牌墙 " + boardIndex
                    + " 的存档位置有重复，请重新 /memory build 建场。");
        }
        Set<UUID> used = new HashSet<>();
        int repaired = 0;
        for (Location loc : board.frames) {
            ItemFrame frame = null;
            for (ItemFrame candidate : arena().framesAt(loc)) {
                if (used.add(candidate.getUniqueId())) {
                    frame = candidate;
                    break;
                }
            }
            if (frame == null) {
                frame = spawnFrame(loc, board.stand);
                if (frame != null) {
                    used.add(frame.getUniqueId());
                    repaired++;
                }
            }
            if (frame == null) {
                plugin.getLogger().warning("翻牌墙缺少展示框且无法补齐：" + loc);
                continue;
            }
            frame.setFixed(false);
            frame.setInvulnerable(true);
            frame.setItemDropChance(0f);
            st.frames.add(frame);
        }
        if (repaired > 0) {
            plugin.getLogger().info("翻牌墙 " + boardIndex + " 补齐了 " + repaired + " 个展示框。");
        }
        if (st.frames.size() < total) {
            plugin.getLogger().warning("翻牌墙牌位不足（" + st.frames.size() + "/" + total
                    + "，墙上有 " + board.frames.size() + " 个位置），跳过该玩家。");
            return null;
        }
        List<Material> pool = new ArrayList<>();
        for (String raw : plugin.getConfig().getStringList("board.items")) {
            Material m = Material.matchMaterial(raw, false);
            if (m != null && m.isItem()) {
                pool.add(m);
            }
        }
        if (pool.isEmpty()) {
            pool.add(Material.DIAMOND);
        }
        Collections.shuffle(pool, gameRandom);
        List<Material> pairs = new ArrayList<>();
        int pairCount = total / 2;
        for (int i = 0; i < pairCount; i++) {
            Material m = pool.get(i % pool.size());
            pairs.add(m);
            pairs.add(m);
        }
        Collections.shuffle(pairs, gameRandom);
        for (int i = 0; i < total; i++) {
            st.items[i] = pairs.get(i);
        }
        if (game().questionItem == null) {
            World world = st.frames.get(0).getWorld();
            game().questionItem = MapArt.item(MapArt.create(world, false));
            game().checkItem = MapArt.item(MapArt.create(world, true));
        }
        for (ItemFrame frame : st.frames) {
            frame.setItem(game().questionItem);
        }
        return st;
    }

    /** 在存档位置补一个展示框，朝向玩家站位。 */
    private ItemFrame spawnFrame(Location loc, Location stand) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        World world = loc.getWorld();
        Block block = world.getBlockAt(loc);
        if (!block.getType().isAir()) {
            block.setType(Material.AIR, false);
        }
        ItemFrame frame = world.spawn(loc.clone().add(0.5, 0.5, 0.5), ItemFrame.class);
        frame.setFacingDirection(facingToward(loc, stand));
        frame.setInvulnerable(true);
        frame.setItemDropChance(0f);
        frame.setItem(new ItemStack(Material.AIR));
        return frame;
    }

    /** 展示框要朝向玩家站位（玩家站在墙的内侧）。 */
    private static BlockFace facingToward(Location frameBlock, Location stand) {
        if (stand == null) {
            return BlockFace.SOUTH;
        }
        double dx = stand.getX() - (frameBlock.getBlockX() + 0.5);
        double dz = stand.getZ() - (frameBlock.getBlockZ() + 0.5);
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private void tickGame() {
        if (game().state != State.RUNNING) {
            cancelGameTask();
            return;
        }
        game().secondsLeft--;
        if (game().secondsLeft <= 0) {
            endGame("&6&l时间到！");
            return;
        }
        if (game().secondsLeft == 30 || game().secondsLeft == 10) {
            broadcast("&e剩余 &f" + game().secondsLeft + " &e秒！");
        }
        updateActionBar();
        updateLiveBoard();
        reportArena();
    }

    private void updateActionBar() {
        for (UUID id : game().playing) {
            Player p = Bukkit.getPlayer(id);
            PlayState st = game().states.get(id);
            if (p != null && st != null) {
                Chat.actionBar(p, "&b" + GAME + " &8| &f剩余 &b" + game().secondsLeft + "s"
                        + " &8| &f已配对 &e" + st.pairs + "&7/&e" + totalPairs()
                        + (st.finished() ? " &a✔ 完成" : ""));
            }
        }
    }

    // ---------- 翻牌 ----------

    /** 展示框被点击（左键或右键）。 */
    public void handleFrameClick(Player player, ItemFrame frame) {
        if (game().state != State.RUNNING) {
            return;
        }
        UUID id = player.getUniqueId();
        PlayState st = game().states.get(id);
        if (st == null || st.reviewing || st.finished()) {
            return;
        }
        int idx = st.frames.indexOf(frame);
        if (idx < 0 || st.matched[idx]) {
            return;
        }
        if (st.firstIdx == idx) {
            return; // 同一面牌一轮内第二次点击不算
        }
        frame.setItem(new ItemStack(st.items[idx]));
        if (st.firstIdx < 0) {
            st.firstIdx = idx;
            return;
        }
        int first = st.firstIdx;
        st.firstIdx = -1;
        st.reviewing = true;
        boolean same = st.items[first] == st.items[idx];
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!game().playing.contains(id) || game().states.get(id) != st) {
                return;
            }
            if (same && first != idx) {
                st.matched[first] = true;
                st.matched[idx] = true;
                st.pairs++;
                st.frames.get(first).setItem(game().checkItem);
                st.frames.get(idx).setItem(game().checkItem);
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
                    Chat.actionBar(p, "&a配对成功！&f已配对 &e" + st.pairs + "&7/&e" + totalPairs());
                }
                if (st.pairs >= totalPairs()) {
                    st.finishedAt = System.currentTimeMillis() - game().startedAt;
                    broadcast("&a" + player.getName() + " &f已完成全部翻牌！&7用时 &e"
                            + formatSeconds(st.finishedAt));
                    if (allFinished()) {
                        endGame("&6&l所有人完成！");
                        return;
                    }
                }
            } else {
                st.frames.get(first).setItem(game().questionItem);
                st.frames.get(idx).setItem(game().questionItem);
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f);
                }
            }
            st.reviewing = false;
            updateLiveBoard();
        }, reviewDelayTicks());
    }

    private boolean allFinished() {
        for (UUID id : game().playing) {
            PlayState st = game().states.get(id);
            if (st != null && !st.finished()) {
                return false;
            }
        }
        return !game().playing.isEmpty();
    }

    // ---------- 结束 ----------

    public void stopGame() {
        if (game().state != State.RUNNING) {
            return;
        }
        endGame("&e管理员结束了本局。");
    }

    /**
     * 收场入口（I4：生命周期唯一出口）。
     *
     * <p>原来这里直接干活；现在改成"先让会话 end()，再由 onEnd 回到 {@link #doEndGame} 干活"，
     * 这样"谁结束了这一局、为什么结束"有了统一记录，且**只会执行一次**（框架保证幂等）。
     */
    private void endGame(String reason) {
        if (game().state != State.RUNNING) {
            return;
        }
        game().pendingEndMessage = reason;          // 把玩家原本看到的文案带过去，别丢
        if (game().session == null) {
            doEndGame(EndReason.ROUND_END);
            return;
        }
        game().session.end(EndReason.ROUND_END);
    }

    /** 真正的收场逻辑（由 game().session.onEnd 回调，也可在无会话时直接调用）。 */
    void doEndGame(EndReason endReason) {
        if (game().state != State.RUNNING) {
            return;
        }
        // 收场文案沿用原来的（endGame 传下来的那句），没有就用默认
        String reason = game().pendingEndMessage != null ? game().pendingEndMessage : "&7对局结束。";
        game().pendingEndMessage = null;
        cancelTasks();
        game().state = State.WAITING;

        List<UUID> ranked = new ArrayList<>(game().playing);
        ranked.sort((a, b) -> {
            PlayState sa = game().states.get(a);
            PlayState sb = game().states.get(b);
            boolean fa = sa != null && sa.finished();
            boolean fb = sb != null && sb.finished();
            if (fa != fb) {
                return fa ? -1 : 1;
            }
            if (fa) {
                return Long.compare(sa.finishedAt, sb.finishedAt);
            }
            int pa = sa == null ? 0 : sa.pairs;
            int pb = sb == null ? 0 : sb.pairs;
            if (pa != pb) {
                return Integer.compare(pb, pa);
            }
            return nameOf(a).compareToIgnoreCase(nameOf(b));
        });

        broadcast("&6&l========== 游戏结束 ==========");
        broadcast(reason);
        List<UUID> firsts = new ArrayList<>();
        List<UUID> seconds = new ArrayList<>();
        List<UUID> thirds = new ArrayList<>();
        if (ranked.size() >= 1) firsts.add(ranked.get(0));
        if (ranked.size() >= 2) seconds.add(ranked.get(1));
        if (ranked.size() >= 3) thirds.add(ranked.get(2));
        Top3.broadcast(msg -> broadcast(msg), id -> {
            PlayState st = game().states.get(id);
            return nameOf(id) + " &7(" + (st == null ? 0 : st.pairs) + " 对"
                    + (st != null && st.finished() ? "，用时 " + formatSeconds(st.finishedAt) : "") + ")";
        }, firsts, seconds, thirds);
        Top3.award(data, firsts, seconds, thirds);
        saveData();
        ArcadeBridge.report("memoryflip", firsts, seconds, thirds);
        broadcast("&6===============================");

        liveSidebar.hide("memoryflip");
        for (UUID id : new ArrayList<>(game().playing)) {
            PlayState st = game().states.get(id);
            if (st != null) {
                resetBoard(st);
            }
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                showNameTag(p);
                p.setGameMode(GameMode.SURVIVAL);
                p.setHealth(20);
                p.setFoodLevel(20);
                HubAPI.teleportToLobby(p);
            }
            HubAPI.clearGame(id);
        }
        game().playing.clear();
        game().states.clear();
        updateCountdown();
        reportArena();
    }

    private void resetBoard(PlayState st) {
        for (int i = 0; i < st.frames.size(); i++) {
            ItemFrame frame = st.frames.get(i);
            if (frame != null && !frame.isDead()) {
                frame.setItem(game().questionItem == null ? new ItemStack(Material.AIR) : game().questionItem);
            }
        }
    }

    private void restoreFrames() {
        for (PlayState st : game().states.values()) {
            resetBoard(st);
        }
        game().states.clear();
    }

    // ---------- 计分板 ----------

    private void updateLiveBoard() {
        if (game().state != State.RUNNING) {
            return;
        }
        List<UUID> online = new ArrayList<>();
        for (UUID id : game().playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                online.add(id);
            }
        }
        online.sort(Comparator.comparingInt((UUID id) -> {
            PlayState st = game().states.get(id);
            if (st == null) {
                return 0;
            }
            return st.finished() ? 100000 + (int) Math.max(0, 100000 - st.finishedAt / 1000) : st.pairs;
        }).reversed().thenComparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        for (UUID id : online) {
            PlayState st = game().states.get(id);
            int pairs = st == null ? 0 : st.pairs;
            boolean done = st != null && st.finished();
            lines.add(new LiveSidebar.Line("§e" + nameOf(id) + (done ? " §a✓" : ""), pairs));
        }
        liveSidebar.update("memoryflip", "&b&l记忆翻牌 · 配对", online, lines);
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
        if (game().state != State.WAITING) {
            Chat.send(player, "&e对局已经在进行中。");
            return;
        }
        if (!arena().isReady()) {
            Chat.send(player, "&c场地未配置完整（缺 " + arena().missing() + "）。");
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

    // ---------- 任务 ----------

    private void cancelTasks() {
        if (game().gameTask != null) {
            game().gameTask.cancel();
            game().gameTask = null;
        }
    }

    private void cancelGameTask() {
        if (game().gameTask != null) {
            game().gameTask.cancel();
            game().gameTask = null;
        }
    }

    // ---------- 上报 ----------

    /** 隐藏头顶名字，防止挡住翻牌墙（用主计分板队伍实现）。 */
    private void hideNameTag(Player player) {
        if (Bukkit.getScoreboardManager() == null) {
            return;
        }
        var main = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = main.getTeam("mf_hide");
        if (team == null) {
            team = main.registerNewTeam("mf_hide");
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
        team.addEntry(player.getName());
    }

    private void showNameTag(Player player) {
        if (player == null || Bukkit.getScoreboardManager() == null) {
            return;
        }
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam("mf_hide");
        if (team != null) {
            team.removeEntry(player.getName());
        }
    }

    private void reportArena() {
        String extra = game().state == State.RUNNING
                ? "剩余 " + game().secondsLeft + "s · " + game().playing.size() + " 人" : null;
        HubAPI.setArena(GAME, ARENA, game().state == State.RUNNING ? GameState.PLAYING : GameState.WAITING,
                game().waiting.size() + game().playing.size(), extra);
        if (arena().getLobby() != null) {
            HubAPI.setArenaSpectator(GAME, ARENA, arena().getLobby().clone().add(0, 6, 0));
        }
    }

    private void broadcast(String message) {
        for (UUID id : game().waiting) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.send(p, message);
            }
        }
        for (UUID id : game().playing) {
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

    private static String formatSeconds(long ms) {
        long total = Math.max(0, ms / 1000L);
        long m = total / 60;
        long s = total % 60;
        return String.format(Locale.ROOT, "%d分%02d秒", m, s);
    }

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
            PlayerStats.save("memoryflip", data);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存玩家记录失败：" + ex.getMessage());
        }
    }

    /**
     * 每房间一份的等待倒计时回调（D22）。
     *
     * <p>为什么必须每房间一个：`WaitCountdown` 自己维护"倒计时/主持人"，两个房间共用一个实例时
     * 会互相取消（真实限制在 `docs/改动记录.md` 里写过）。这里把它绑到具体的 {@link MemoryGame}，
     * 回调里读的永远是**这个房间**的等待队列。
     */
    private final class GameHost implements WaitCountdown.Host {

        private final MemoryGame h;

        GameHost(MemoryGame h) {
            this.h = h;
        }

        @Override
        public Collection<UUID> waitingPlayers() {
            return new ArrayList<>(h.waiting);
        }

        @Override
        public int minPlayers() {
            return MemoryFlipManager.this.minPlayers();
        }

        @Override
        public int maxPlayers() {
            return MemoryFlipManager.this.maxPlayers();
        }

        @Override
        public boolean hasOpQueued() {
            for (UUID id : h.waiting) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOp()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onTick(long seconds) {
            for (UUID id : h.waiting) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Chat.actionBar(p, "&7等待中 &7| &f开始倒计时 &e" + seconds + "s");
                }
            }
        }

        @Override
        public void onMessage(String message) {
            for (UUID id : h.waiting) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Chat.send(p, message);
                }
            }
        }

        @Override
        public void onStart() {
            use(h.key);
            startGame(false);
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
            // 顺序 = (加入 tick, 该 tick 内的随机名次)：同 tick 进来的人随机决定谁是主持人
            java.util.List<UUID> order = new ArrayList<>(h.waiting);
            order.sort(java.util.Comparator
                    .comparingLong((UUID id) -> h.joinTick.getOrDefault(id, 0L))
                    .thenComparingLong(id -> h.joinSeed.getOrDefault(id, 0L)));
            return order;
        }

        @Override
        public void onHostTimeout(UUID host) {
            use(h.key);
            Player p = Bukkit.getPlayer(host);
            String name = p == null ? host.toString() : p.getName();
            h.waiting.remove(host);
            // I1：会话归属也要摘掉，否则 doctor 的人数不降、且他再也进不了别的游戏
            if (h.session != null && p != null) {
                h.session.leave(p);
            }
            if (p != null && p.isOnline()) {
                Chat.send(p, "&c倒计时结束还没开始，你被移出本局（主持人超时）。");
                HubAPI.teleportToLobby(p);
            }
            plugin.getLogger().info("[MemoryFlip] 主持人 " + name + " 超时未开始，已移出本局（剩余 "
                    + h.waiting.size() + " 人）");
            broadcast("&e主持人 &f" + name + " &e超时未开始，已移出本局。");
            updateCountdown();
        }
    }
}
