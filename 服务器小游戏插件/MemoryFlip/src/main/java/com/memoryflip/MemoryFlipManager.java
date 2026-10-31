package com.memoryflip;

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

    private static final class PlayState {
        int boardIndex;
        final List<ItemFrame> frames = new ArrayList<>();
        Material[] items = new Material[0];
        boolean[] matched = new boolean[0];
        int pairs;
        int firstIdx = -1;
        boolean reviewing;
        long finishedAt = -1L;

        boolean finished() {
            return finishedAt > 0;
        }
    }

    private final MemoryFlipPlugin plugin;
    private final MemoryFlipArena arena;
    private final WaitCountdown countdown;
    private final LiveSidebar liveSidebar;
    private final Random random = new Random();
    private StartCountdown startCountdown;

    private State state = State.WAITING;
    private final Set<UUID> waiting = new LinkedHashSet<>();
    private final Set<UUID> playing = new LinkedHashSet<>();
    private final Map<UUID, PlayState> states = new HashMap<>();

    private BukkitTask gameTask;
    private int secondsLeft;
    private long startedAt;
    private ItemStack questionItem;
    private ItemStack checkItem;

    private YamlConfiguration data;

    public MemoryFlipManager(MemoryFlipPlugin plugin) {
        this.plugin = plugin;
        this.arena = new MemoryFlipArena(plugin);
        this.liveSidebar = new LiveSidebar(plugin);
        this.countdown = new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return new ArrayList<>(waiting);
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
        int boards = arena.getBoards().size();
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
        arena.load();
        data = PlayerStats.load("memoryflip", new File(plugin.getDataFolder(), "data.yml"));
        World world = arena.getLobby() != null ? arena.getLobby().getWorld() : null;
        if (world == null && !arena.getBoards().isEmpty()) {
            world = arena.getBoards().get(0).stand.getWorld();
        }
        questionItem = null;
        checkItem = null;
        if (world != null) {
            MapView question = MapArt.create(world, false);
            MapView check = MapArt.create(world, true);
            questionItem = MapArt.item(question);
            checkItem = MapArt.item(check);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HubAPI.registerGame(GAME, GAME, "FILLED_MAP", "b");
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
        liveSidebar.hideAll();
        restoreFrames();
        for (UUID id : playing) {
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

    public MemoryFlipArena arena() {
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
        return isWaiting(player) || isPlaying(player);
    }

    public boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission("memory.admin");
    }

    public int waitingCount() {
        return waiting.size();
    }

    public int playingCount() {
        return playing.size();
    }

    public void saveAll() {
        arena.save();
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
        if (waiting.size() >= effectiveMaxPlayers()) {
            Chat.send(player, "&c等待队列已满（" + effectiveMaxPlayers() + " 人）。");
            return;
        }
        if (!arena.isReady()) {
            Chat.send(player, "&c场地还没配置完整（缺 " + arena.missing() + "）。");
            return;
        }
        waiting.add(id);
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        resetPlayer(player);
        player.teleport(arena.getLobby());
        giveWaitItems(player);
        HubAPI.setGame(id, GAME);
        HubAPI.announceJoin(plugin.getName(), player.getName());
        Chat.send(player, "&a已加入" + GAME + " &7(" + waiting.size() + "/" + effectiveMaxPlayers() + ")");
        updateCountdown();
        reportArena();
    }

    public void leave(Player player, boolean silent) {
        UUID id = player.getUniqueId();
        boolean wasWaiting = waiting.remove(id);
        boolean wasPlaying = playing.remove(id);
        if (!wasWaiting && !wasPlaying) {
            if (!silent) {
                Chat.send(player, "&e你不在等待队列或对局中。");
            }
            return;
        }
        PlayState st = states.remove(id);
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
        if (state == State.RUNNING && wasPlaying) {
            if (playing.isEmpty() || allFinished()) {
                endGame("&7对局结束。");
                return;
            }
            updateLiveBoard();
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
        PlayState st = states.remove(id);
        if (st != null) {
            resetBoard(st);
        }
        showNameTag(player);
        HubAPI.clearGame(id);
        if (state == State.RUNNING && wasPlaying && (playing.isEmpty() || allFinished())) {
            endGame("&7对局结束。");
            return;
        }
        if (state == State.WAITING) {
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
        if (ready.isEmpty()) {
            return;
        }
        if (ready.size() < minPlayers() && !force) {
            updateCountdown();
            return;
        }
        if (ready.size() > arena.getBoards().size()) {
            for (UUID id : ready) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Chat.send(p, "&c翻牌墙数量不够（当前 " + arena.getBoards().size() + " 面），请管理员 /memory build。");
                }
            }
            return;
        }
        if (startCountdown().begin("memoryflip", ready, () -> startGame(force))) {
            return;
        }
        countdown.cancel();
        state = State.RUNNING;
        waiting.clear();
        playing.clear();
        playing.addAll(ready);
        states.clear();
        secondsLeft = gameSeconds();
        startedAt = System.currentTimeMillis();
        cancelTasks();

        // 每局重新播种，保证每局的物品组合与牌面位置都不一样
        Random gameRandom = new Random(System.nanoTime());
        List<MemoryFlipArena.Board> boards = new ArrayList<>(arena.getBoards());
        Collections.shuffle(boards, gameRandom);
        List<UUID> order = new ArrayList<>(playing);
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
            states.put(id, st);
            clearGameState(p);
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20);
            p.setFoodLevel(20);
            p.teleport(board.stand);
            hideNameTag(p);
            HubAPI.setPlaying(id, GAME);
            Chat.title(p, "&b&l" + GAME, "&f翻开两张相同的物品！", 2);
        }
        playing.retainAll(states.keySet());
        broadcast("&6&l========== " + GAME + " 开始 ==========");
        broadcast("&f左键/右键翻开物品展示框，两张相同就得分；先翻完 " + totalPairs() + " 对的人获胜。");
        updateActionBar();
        updateLiveBoard();
        reportArena();
        gameTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGame, 20L, 20L);
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
            for (ItemFrame candidate : arena.framesAt(loc)) {
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
        if (questionItem == null) {
            World world = st.frames.get(0).getWorld();
            questionItem = MapArt.item(MapArt.create(world, false));
            checkItem = MapArt.item(MapArt.create(world, true));
        }
        for (ItemFrame frame : st.frames) {
            frame.setItem(questionItem);
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
        updateLiveBoard();
        reportArena();
    }

    private void updateActionBar() {
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            PlayState st = states.get(id);
            if (p != null && st != null) {
                Chat.actionBar(p, "&b" + GAME + " &8| &f剩余 &b" + secondsLeft + "s"
                        + " &8| &f已配对 &e" + st.pairs + "&7/&e" + totalPairs()
                        + (st.finished() ? " &a✔ 完成" : ""));
            }
        }
    }

    // ---------- 翻牌 ----------

    /** 展示框被点击（左键或右键）。 */
    public void handleFrameClick(Player player, ItemFrame frame) {
        if (state != State.RUNNING) {
            return;
        }
        UUID id = player.getUniqueId();
        PlayState st = states.get(id);
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
            if (!playing.contains(id) || states.get(id) != st) {
                return;
            }
            if (same && first != idx) {
                st.matched[first] = true;
                st.matched[idx] = true;
                st.pairs++;
                st.frames.get(first).setItem(checkItem);
                st.frames.get(idx).setItem(checkItem);
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
                    Chat.actionBar(p, "&a配对成功！&f已配对 &e" + st.pairs + "&7/&e" + totalPairs());
                }
                if (st.pairs >= totalPairs()) {
                    st.finishedAt = System.currentTimeMillis() - startedAt;
                    broadcast("&a" + player.getName() + " &f已完成全部翻牌！&7用时 &e"
                            + formatSeconds(st.finishedAt));
                    if (allFinished()) {
                        endGame("&6&l所有人完成！");
                        return;
                    }
                }
            } else {
                st.frames.get(first).setItem(questionItem);
                st.frames.get(idx).setItem(questionItem);
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
        for (UUID id : playing) {
            PlayState st = states.get(id);
            if (st != null && !st.finished()) {
                return false;
            }
        }
        return !playing.isEmpty();
    }

    // ---------- 结束 ----------

    public void stopGame() {
        if (state != State.RUNNING) {
            return;
        }
        endGame("&e管理员结束了本局。");
    }

    private void endGame(String reason) {
        if (state != State.RUNNING) {
            return;
        }
        cancelTasks();
        state = State.WAITING;

        List<UUID> ranked = new ArrayList<>(playing);
        ranked.sort((a, b) -> {
            PlayState sa = states.get(a);
            PlayState sb = states.get(b);
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
            PlayState st = states.get(id);
            return nameOf(id) + " &7(" + (st == null ? 0 : st.pairs) + " 对"
                    + (st != null && st.finished() ? "，用时 " + formatSeconds(st.finishedAt) : "") + ")";
        }, firsts, seconds, thirds);
        Top3.award(data, firsts, seconds, thirds);
        saveData();
        ArcadeBridge.report("memoryflip", firsts, seconds, thirds);
        broadcast("&6===============================");

        liveSidebar.hide("memoryflip");
        for (UUID id : new ArrayList<>(playing)) {
            PlayState st = states.get(id);
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
        playing.clear();
        states.clear();
        updateCountdown();
        reportArena();
    }

    private void resetBoard(PlayState st) {
        for (int i = 0; i < st.frames.size(); i++) {
            ItemFrame frame = st.frames.get(i);
            if (frame != null && !frame.isDead()) {
                frame.setItem(questionItem == null ? new ItemStack(Material.AIR) : questionItem);
            }
        }
    }

    private void restoreFrames() {
        for (PlayState st : states.values()) {
            resetBoard(st);
        }
        states.clear();
    }

    // ---------- 计分板 ----------

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
        online.sort(Comparator.comparingInt((UUID id) -> {
            PlayState st = states.get(id);
            if (st == null) {
                return 0;
            }
            return st.finished() ? 100000 + (int) Math.max(0, 100000 - st.finishedAt / 1000) : st.pairs;
        }).reversed().thenComparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        for (UUID id : online) {
            PlayState st = states.get(id);
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

    // ---------- 任务 ----------

    private void cancelTasks() {
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
    }

    private void cancelGameTask() {
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
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
        String extra = state == State.RUNNING
                ? "剩余 " + secondsLeft + "s · " + playing.size() + " 人" : null;
        HubAPI.setArena(GAME, ARENA, state == State.RUNNING ? GameState.PLAYING : GameState.WAITING,
                waiting.size() + playing.size(), extra);
        if (arena.getLobby() != null) {
            HubAPI.setArenaSpectator(GAME, ARENA, arena.getLobby().clone().add(0, 6, 0));
        }
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
}
