package com.anvilbattle;

import com.hub.api.ArcadeBridge;
import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.data.PlayerStats;
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
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 铁砧掘战核心：等待/开局、随机落砧、砸中/掉虚空淘汰、回到最后一人。
 * <p>
 * 铁砧落地会破坏场地方块；每局被破坏的原始方块记在 {@code broken} 里，局末/关服还原。
 * 落砧位置从区域顶部往下找第一块非空气、非液体方块；整列被挖空（只剩空气/液体）就不再生成。
 */
public final class AnvilBattleManager {

    public static final String GAME = "铁砧掘战";
    /** 单场地游戏在 HubCore 菜单里显示的场地名。 */
    public static final String ARENA = "主场地";

    public enum State {
        WAITING, RUNNING
    }

    private final AnvilBattlePlugin plugin;
    private final AnvilBattleArena arena;
    private final NamespacedKey returnKey;
    private final NamespacedKey startKey;
    private final WaitCountdown countdown;
    /** 玩家 UUID -> 下一波头顶定向落砧的概率（没有记录时取 base-chance）。 */
    private final Map<UUID, Double> targetChance = new HashMap<>();

    private final Random random = new Random();
    private final LiveSidebar liveSidebar;

    private State state = State.WAITING;
    private final Set<UUID> waiting = new LinkedHashSet<>();
    private final Set<UUID> playing = new LinkedHashSet<>();
    private final Set<UUID> eliminated = new LinkedHashSet<>();
    /** 本插件生成、还没落地的铁砧实体。 */
    private final Set<UUID> anvils = new HashSet<>();
    /** 已经砸过方块、正在往下钻的铁砧 -> 消失时间（毫秒）。 */
    private final Map<UUID, Long> anvilExpiry = new HashMap<>();
    /** 本局淘汰顺序（先淘汰的在前），用于前三名。 */
    private final List<UUID> elimOrder = new ArrayList<>();
    /** 本局被铁砧破坏的方块 -> 原始 BlockData（局末还原）。 */
    private final Map<String, BlockData> broken = new LinkedHashMap<>();

    private BukkitTask gameTask;
    private BukkitTask waveTask;
    private BukkitTask hitTask;
    private int secondsLeft;
    private boolean warnedNoColumns;
    private YamlConfiguration stats;

    public AnvilBattleManager(AnvilBattlePlugin plugin) {
        this.plugin = plugin;
        this.liveSidebar = new LiveSidebar(plugin);
        this.arena = new AnvilBattleArena(plugin);
        this.returnKey = new NamespacedKey(plugin, "return_item");
        this.startKey = new NamespacedKey(plugin, "start_item");
        this.countdown = new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return new ArrayList<>(waiting);
            }

            @Override
            public int minPlayers() {
                return AnvilBattleManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return AnvilBattleManager.this.maxPlayers();
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

    public int minPlayers() {
        return Math.max(2, cfgInt("min-players", 2));
    }

    public int maxPlayers() {
        return Math.max(minPlayers(), cfgInt("max-players", 12));
    }

    public int gameSeconds() {
        return Math.max(10, cfgInt("game-seconds", 180));
    }

    public int anvilAmount() {
        return Math.max(1, Math.min(50, cfgInt("anvil.amount", 3)));
    }

    private int anvilIntervalSeconds() {
        return Math.max(1, cfgInt("anvil.interval-seconds", 1));
    }

    private int anvilSpawnHeight() {
        return Math.max(1, Math.min(100, cfgInt("anvil.spawn-height", 12)));
    }

    private int anvilBreakRadius() {
        return Math.max(0, Math.min(8, cfgInt("anvil.break-radius", 0)));
    }

    private int anvilBreakDepth() {
        return Math.max(0, Math.min(32, cfgInt("anvil.break-depth", 0)));
    }

    /** 砸掉方块后继续往下钻的时长（毫秒）；0 = 砸完立刻消失。 */
    private long anvilFallAfterBreakMs() {
        return Math.max(0, Math.min(30, cfgInt("anvil.fall-after-break-seconds", 2))) * 1000L;
    }

    private boolean rampEnabled() {
        return plugin.getConfig().getBoolean("anvil.ramp.enabled", true);
    }

    private int rampIntervalSeconds() {
        return Math.max(1, cfgInt("anvil.ramp.interval-seconds", 30));
    }

    private int rampPerStep() {
        return Math.max(0, cfgInt("anvil.ramp.amount-per-step", 1));
    }

    private int rampMaxAmount() {
        return Math.max(anvilAmount(), cfgInt("anvil.ramp.max-amount", 12));
    }

    // ---------- 玩家头顶定向落砧 ----------

    private boolean playerTargetEnabled() {
        return plugin.getConfig().getBoolean("anvil.player-target.enabled", true);
    }

    private double playerTargetBaseChance() {
        return clampChance(plugin.getConfig().getDouble("anvil.player-target.base-chance", 0.15));
    }

    private double playerTargetChanceStep() {
        return clampChance(plugin.getConfig().getDouble("anvil.player-target.chance-step", 0.10));
    }

    private double playerTargetMaxChance() {
        return Math.max(playerTargetBaseChance(),
                clampChance(plugin.getConfig().getDouble("anvil.player-target.max-chance", 0.75)));
    }

    private static double clampChance(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** 这名玩家当前这一波被“头顶定向落砧”的概率。 */
    private double targetChance(UUID id) {
        Double current = targetChance.get(id);
        return current == null ? playerTargetBaseChance() : current;
    }

    private void bumpTargetChance(UUID id) {
        targetChance.put(id, Math.min(playerTargetMaxChance(),
                targetChance(id) + playerTargetChanceStep()));
    }

    private void resetTargetChance(UUID id) {
        targetChance.remove(id);
    }

    /** 当前这一波应该落几个铁砧（随时间递增，封顶）。 */
    public int currentAnvilAmount() {
        int base = anvilAmount();
        if (!rampEnabled() || state != State.RUNNING || secondsLeft <= 0) {
            return base;
        }
        int elapsed = Math.max(0, gameSeconds() - secondsLeft);
        int step = elapsed / rampIntervalSeconds();
        return Math.min(rampMaxAmount(), base + step * rampPerStep());
    }

    // ---------- 生命周期 ----------

    public void load() {
        startCountdown();
        ensureDefaults();
        arena.load();
        stats = PlayerStats.load("anvilbattle", new File(plugin.getDataFolder(), "data.yml"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HubAPI.registerGame(GAME, GAME, "ANVIL", "7");
            reportArena();
        }, 40L);
        plugin.getLogger().info("场地" + (arena.isReady() ? "已配置" : "未配置（缺 " + arena.missing() + "）") + "。");
    }

    public void shutdown() {
        liveSidebar.hideAll();
        cancelTasks();
        removeAnvils();
        restoreBlocks();
        saveAll();
    }

    /** 给旧配置补上新键（不改已有值）。 */
    private void ensureDefaults() {
        if (plugin.getConfig().getKeys(false).isEmpty()) {
            return;
        }
        boolean changed = false;
        changed |= setDefault("min-players", 2);
        changed |= setDefault("max-players", 12);
        changed |= setDefault("game-seconds", 180);
        changed |= setDefault("anvil.interval-seconds", 1);
        changed |= setDefault("anvil.amount", 3);
        changed |= setDefault("anvil.ramp.enabled", true);
        changed |= setDefault("anvil.ramp.interval-seconds", 30);
        changed |= setDefault("anvil.ramp.amount-per-step", 1);
        changed |= setDefault("anvil.ramp.max-amount", 12);
        changed |= setDefault("anvil.fall-after-break-seconds", 2);
        changed |= setDefault("anvil.spawn-height", 12);
        changed |= setDefault("anvil.break-radius", 0);
        changed |= setDefault("anvil.break-depth", 0);
        changed |= setDefault("arena.pos1", "");
        changed |= setDefault("arena.pos2", "");
        changed |= setDefault("arena.lobby", "");
        changed |= setDefault("arena.spawns", List.of());
        changed |= setDefault("arena.void-y", -64.0);
        changed |= setDefault("arena.anvil-y", 0.0);
        changed |= setDefault("anvil.player-target.enabled", true);
        changed |= setDefault("anvil.player-target.base-chance", 0.15);
        changed |= setDefault("anvil.player-target.chance-step", 0.10);
        changed |= setDefault("anvil.player-target.max-chance", 0.75);
        // 1.0.4 一次性迁移：旧配置默认是 3×3、2 层，改成只砸 1 格
        if (plugin.getConfig().getInt("config-version", 0) < 1) {
            plugin.getConfig().set("anvil.break-radius", 0);
            plugin.getConfig().set("anvil.break-depth", 0);
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

    /** 声明地盘（{@code docs/框架不变量.md} 的 I2）。单场游戏，场地名用空串。 */
    public void publishRegions() {
        com.hub.region.Regions.begin("anvilbattle");
        if (arena != null) {
            com.hub.region.Regions.box("anvilbattle", "", arena.getPos1(), arena.getPos2());
        }
    }

    public void saveAll() {
        publishRegions();   // 场地可能刚被改过，重新声明（I2）
        if (plugin.getConfig().getKeys(false).isEmpty()) {
            return;
        }
        arena.save();
    }

    public void reload() {
        if (state == State.RUNNING) {
            return;
        }
        load();
    }

    public AnvilBattleArena arena() {
        return arena;
    }

    /** 淘汰后的旁观位置（场地中心上方）。 */
    public Location spectatorSpot() {
        return arena.center();
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
        UUID id = player.getUniqueId();
        return waiting.contains(id) || playing.contains(id) || eliminated.contains(id);
    }

    public boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission("anvil.admin");
    }

    /**
     * 自动生成出生点：清空旧的，在区域 X/Z 上按网格找每列最高的非液体方块，站在方块顶。
     * 网格覆盖不到的（洞/水）再用随机列补齐。返回实际生成数量。
     */
    public int generateSpawns(int count) {
        arena.clearSpawns();
        if (!arena.hasRegion()) {
            return 0;
        }
        World world = arena.world();
        if (world == null) {
            return 0;
        }
        int want = Math.max(1, Math.min(64, count));
        Set<String> used = new HashSet<>();
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(want)));
        int rows = Math.max(1, (int) Math.ceil((double) want / cols));
        int minX = arena.minX();
        int maxX = arena.maxX();
        int minZ = arena.minZ();
        int maxZ = arena.maxZ();
        for (int row = 0; row < rows && arena.getSpawns().size() < want; row++) {
            for (int col = 0; col < cols && arena.getSpawns().size() < want; col++) {
                int x = minX + (int) Math.round((col + 0.5) / cols * (maxX - minX));
                int z = minZ + (int) Math.round((row + 0.5) / rows * (maxZ - minZ));
                trySpawn(world, x, z, used);
            }
        }
        int attempts = 0;
        int maxAttempts = Math.max(100, want * 100);
        while (arena.getSpawns().size() < want && attempts++ < maxAttempts) {
            int x = minX + random.nextInt(maxX - minX + 1);
            int z = minZ + random.nextInt(maxZ - minZ + 1);
            trySpawn(world, x, z, used);
        }
        return arena.getSpawns().size();
    }

    private boolean trySpawn(World world, int x, int z, Set<String> used) {
        if (!used.add(x + "," + z)) {
            return false;
        }
        int top = arena.topBlockY(x, z);
        if (top == Integer.MIN_VALUE) {
            return false;
        }
        arena.addSpawn(new Location(world, x + 0.5, top + 1.0, z + 0.5));
        return true;
    }

    public int waitingCount() {
        return waiting.size();
    }

    public int playingCount() {
        return playing.size();
    }

    public int eliminatedCount() {
        return eliminated.size();
    }

    // ---------- 加入 / 离开 ----------

    public void join(Player player) {
        UUID id = player.getUniqueId();
        if (state == State.RUNNING) {
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
        UUID id = player.getUniqueId();
        boolean wasWaiting = waiting.remove(id);
        boolean wasPlaying = playing.remove(id);
        boolean wasEliminated = eliminated.remove(id);
        resetTargetChance(id);
        if (!wasWaiting && !wasPlaying && !wasEliminated) {
            if (!silent) {
                Chat.send(player, "&e你不在等待队列或对局中。");
            }
            return;
        }
        clearGameState(player);
        HubAPI.clearGame(id);
        restorePlayer(player);
        if (!silent) {
            HubAPI.teleportToLobby(player);
            Chat.send(player, "&e你已离开" + GAME + "。");
        }
        if (state == State.RUNNING && wasPlaying) {
            checkWin();
        } else if (state == State.WAITING) {
            updateCountdown();
        }
        reportArena();
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        boolean wasWaiting = waiting.remove(id);
        boolean wasPlaying = playing.remove(id);
        boolean wasEliminated = eliminated.remove(id);
        resetTargetChance(id);
        if (!wasWaiting && !wasPlaying && !wasEliminated) {
            return;
        }
        clearGameState(player);
        if (wasEliminated) {
            player.setGameMode(GameMode.SURVIVAL);
            player.setAllowFlight(false);
            player.setFlying(false);
        }
        HubAPI.clearGame(id);
        if (state == State.RUNNING && wasPlaying) {
            checkWin();
        } else if (state == State.WAITING) {
            updateCountdown();
        }
        reportArena();
    }

    // ---------- 开局 ----------

    private StartCountdown startCountdown;

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

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
        state = State.RUNNING;
        playing.clear();
        playing.addAll(ready);
        eliminated.clear();
        elimOrder.clear();
        targetChance.clear();
        removeAnvils();
        restoreBlocks();
        secondsLeft = gameSeconds();
        warnedNoColumns = false;

        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            clearGameState(p);
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20);
            p.setFoodLevel(20);
            HubAPI.setPlaying(id, GAME);
            Chat.title(p, "&7&l" + GAME, "&f躲开铁砧，活到最后！", 3);
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
        HubAPI.setArenaSpectator(GAME, ARENA, arena.center());
        broadcast("&6&l========== " + GAME + " 开始 ==========");
        broadcast("&f天上会随机掉铁砧；被砸中或掉到 &eY=" + format(arena.getVoidY()) + " &f以下就淘汰。");
        String rampInfo = rampEnabled()
                ? "，每 &f" + rampIntervalSeconds() + " &7秒增加到 &f" + rampMaxAmount() + " &7个封顶"
                : "";
        broadcast("&7每波 &f" + anvilAmount() + " &7个铁砧，每 &f" + anvilIntervalSeconds() + " &7秒一波" + rampInfo + "。");
        updateActionBar();
        reportArena();

        gameTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGame, 20L, 20L);
        waveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::spawnWave,
                anvilIntervalSeconds() * 20L, anvilIntervalSeconds() * 20L);
        hitTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAnvils, 1L, 1L);
    }

    public void stopGame() {
        if (state != State.RUNNING) {
            return;
        }
        endGame(new LinkedHashSet<>(playing), "&e管理员结束了本局。");
    }

    private void endGame(Set<UUID> winners, String reason) {
        if (state != State.RUNNING) {
            return;
        }
        cancelTasks();
        liveSidebar.hide("anvilbattle");
        removeAnvils();
        state = State.WAITING;
        secondsLeft = 0;
        targetChance.clear();

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
        ArcadeBridge.report("anvilbattle", firsts, seconds, thirds);
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
        // 先送人回大厅，再还原被铁砧挖掉的方块，避免把人埋进方块里
        restoreBlocks();
        updateCountdown();
        reportArena();
    }

    private void updateCountdown() {
        if (state != State.WAITING) {
            countdown.cancel();
            return;
        }
        countdown.update();
    }

    // ---------- 铁砧 ----------

    private void spawnWave() {
        if (state != State.RUNNING) {
            cancelWaveTask();
            return;
        }
        World world = arena.world();
        if (world == null) {
            return;
        }
        Set<String> used = new HashSet<>();
        int amount = currentAnvilAmount();
        int height = anvilSpawnHeight();
        int spawned = 0;
        int targeted = spawnTargeted(world, used, amount);
        spawned += targeted;
        for (int n = targeted; n < amount; n++) {
            for (int attempt = 0; attempt < 40; attempt++) {
                int x = arena.minX() + random.nextInt(arena.maxX() - arena.minX() + 1);
                int z = arena.minZ() + random.nextInt(arena.maxZ() - arena.minZ() + 1);
                String key = x + "," + z;
                if (used.contains(key)) {
                    continue;
                }
                int top = arena.topBlockY(x, z);
                if (top == Integer.MIN_VALUE) {
                    continue; // 整列已挖空 / 只剩液体：不在这个位置落砧
                }
                int spawnY;
                double customY = arena.getAnvilY();
                if (Math.abs(customY) > 0.0001 && customY > top) {
                    spawnY = Math.min(world.getMaxHeight() - 2, (int) Math.round(customY));
                } else {
                    spawnY = Math.min(world.getMaxHeight() - 2, top + height);
                }
                if (spawnY <= top) {
                    spawnY = top + 1;
                }
                Location loc = new Location(world, x + 0.5, spawnY, z + 0.5);
                if (spawnAnvil(loc, 0L) != null) {
                    spawned++;
                }
                used.add(key);
                break;
            }
        }
        if (spawned == 0 && !warnedNoColumns) {
            warnedNoColumns = true;
            plugin.getLogger().warning("铁砧波次没有生成任何铁砧：检查 /anvil list 场地是否已配置、"
                    + "arena.pos1/pos2 的 X/Z 是否圈住平台、void-y=" + arena.getVoidY()
                    + " 是否在平台下方（扫描 Y=" + arena.maxY() + ".." + arena.voidLimitY() + "）。");
        }
    }

    /**
     * 玩家头顶定向落砧：每名存活玩家按各自概率消耗本波名额，落点跟着人走。
     * 没落成就叠加 chance-step（封顶 max-chance），落成后重置为 base-chance。
     *
     * @return 实际消耗掉的名额数
     */
    private int spawnTargeted(World world, Set<String> used, int slots) {
        if (!playerTargetEnabled() || slots <= 0 || playing.isEmpty()) {
            return 0;
        }
        List<UUID> candidates = new ArrayList<>(playing);
        Collections.shuffle(candidates, random);
        int done = 0;
        for (UUID id : candidates) {
            if (done >= slots) {
                break;
            }
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || !p.getWorld().equals(world)) {
                continue;
            }
            Location pl = p.getLocation();
            String key = pl.getBlockX() + "," + pl.getBlockZ();
            if (used.contains(key)) {
                continue; // 这一列本波已有铁砧，等于头顶已经有一颗，概率不变
            }
            if (random.nextDouble() >= targetChance(id)) {
                bumpTargetChance(id);
                continue;
            }
            int x = pl.getBlockX();
            int z = pl.getBlockZ();
            int feetY = (int) Math.floor(pl.getY());
            int from = feetY;
            int top = arena.topBlockY(x, z);
            if (top != Integer.MIN_VALUE) {
                from = Math.max(from, top);
            }
            int spawnY;
            double customY = arena.getAnvilY();
            if (Math.abs(customY) > 0.0001 && customY > feetY) {
                spawnY = (int) Math.round(customY);
            } else {
                spawnY = from + anvilSpawnHeight();
            }
            if (spawnY < feetY + 4) {
                spawnY = feetY + 4;
            }
            spawnY = Math.min(world.getMaxHeight() - 2, spawnY);
            Location loc = new Location(world, x + 0.5, spawnY, z + 0.5);
            if (spawnAnvil(loc, 0L) != null) {
                done++;
                resetTargetChance(id);
                used.add(key);
            }
        }
        return done;
    }

    /** 生成一个铁砧；expiry > 0 表示它已经砸过方块、到点就消失。 */
    private FallingBlock spawnAnvil(Location loc, long expiry) {
        World world = loc.getWorld();
        if (world == null) {
            return null;
        }
        FallingBlock fb = world.spawnFallingBlock(loc, Material.ANVIL.createBlockData());
        fb.setDropItem(false);
        fb.setHurtEntities(true);
        fb.setPersistent(false);
        anvils.add(fb.getUniqueId());
        if (expiry > 0L) {
            anvilExpiry.put(fb.getUniqueId(), expiry);
        }
        return fb;
    }

    private void tickAnvils() {
        if (state != State.RUNNING) {
            return;
        }
        World world = arena.world();
        if (world != null) {
            for (UUID anvilId : new ArrayList<>(anvils)) {
                Long expiry = anvilExpiry.get(anvilId);
                if (expiry != null && System.currentTimeMillis() >= expiry) {
                    anvils.remove(anvilId);
                    anvilExpiry.remove(anvilId);
                    Entity gone = Bukkit.getEntity(anvilId);
                    if (gone != null) {
                        gone.remove();
                    }
                    continue;
                }
                Entity entity = Bukkit.getEntity(anvilId);
                if (!(entity instanceof FallingBlock fb) || fb.isDead() || !fb.isValid()) {
                    anvils.remove(anvilId);
                    anvilExpiry.remove(anvilId);
                    continue;
                }
                Location loc = fb.getLocation();
                if (loc.getWorld() == null || !loc.getWorld().equals(world)
                        || loc.getY() < arena.getVoidY() - 20) {
                    anvils.remove(anvilId);
                    anvilExpiry.remove(anvilId);
                    fb.remove();
                    continue;
                }
                for (UUID pid : new ArrayList<>(playing)) {
                    Player p = Bukkit.getPlayer(pid);
                    if (p == null || !p.isOnline() || !p.getWorld().equals(world)) {
                        continue;
                    }
                    if (fb.getBoundingBox().overlaps(p.getBoundingBox())) {
                        eliminate(p, "被铁砧砸中");
                        if (state != State.RUNNING) {
                            return;
                        }
                        break;
                    }
                }
            }
        }
        // 虚空兜底
        for (UUID pid : new ArrayList<>(playing)) {
            Player p = Bukkit.getPlayer(pid);
            if (p != null && p.isOnline() && p.getLocation().getY() < arena.getVoidY()) {
                eliminate(p, "掉进虚空");
                if (state != State.RUNNING) {
                    return;
                }
            }
        }
    }

    public boolean isOurAnvil(UUID id) {
        return anvils.contains(id);
    }

    /** 铁砧落地：取消原来的“变成铁砧方块”，破坏周围方块；砸穿后继续往下钻一段时间再消失。 */
    public void onAnvilLand(FallingBlock fb) {
        UUID id = fb.getUniqueId();
        if (!anvils.remove(id)) {
            return;
        }
        Long stored = anvilExpiry.remove(id);
        Location impact = fb.getLocation().clone();
        fb.remove();
        if (state != State.RUNNING) {
            return;
        }
        long now = System.currentTimeMillis();
        long expiry = stored == null ? 0L : stored;
        int brokenCount = breakBlocks(impact);
        World world = impact.getWorld();
        if (world != null) {
            world.playSound(impact, Sound.BLOCK_ANVIL_LAND, 1.0f, 0.8f);
        }
        if (stored == null) {
            // 第一次砸到方块：从这里开始计时，继续往下钻
            long duration = anvilFallAfterBreakMs();
            if (duration <= 0L) {
                return;
            }
            expiry = now + duration;
        }
        if (brokenCount <= 0) {
            // 没砸掉任何方块（落点已经是空气/液体），别原地反复生成
            return;
        }
        if (now >= expiry) {
            anvilExpiry.remove(id);
            return;
        }
        // 在坑底重新生成一个铁砧继续下坠，带着同一个消失时间
        FallingBlock next = spawnAnvil(impact, expiry);
        if (next != null) {
            next.setVelocity(new Vector(0, -0.6, 0));
        }
    }

    private int breakBlocks(Location impact) {
        World world = impact.getWorld();
        if (world == null) {
            return 0;
        }
        int changed = 0;
        int cx = impact.getBlockX();
        int cy = impact.getBlockY();
        int cz = impact.getBlockZ();
        int radius = anvilBreakRadius();
        int depth = anvilBreakDepth();
        int lower = Math.max(world.getMinHeight(), arena.voidLimitY());

        // 半径和深度都为 0：只砸落点下方第一块实体方块（可能是落点那层，也可能是它下面一格）
        if (radius <= 0 && depth <= 0) {
            if (!arena.inColumn(cx, cz)) {
                return 0;
            }
            for (int y = cy; y >= Math.max(lower, cy - 4); y--) {
                if (y > arena.maxY()) {
                    continue;
                }
                Block block = world.getBlockAt(cx, y, cz);
                Material type = block.getType();
                if (type.isAir() || AnvilBattleArena.isLiquid(type) || type == Material.BEDROCK
                        || type == Material.BARRIER || type == Material.LIGHT) {
                    continue;
                }
                broken.putIfAbsent(key(cx, y, cz), block.getBlockData().clone());
                block.setType(Material.AIR, false);
                return 1;
            }
            return 0;
        }

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (!arena.inColumn(x, z)) {
                    continue;
                }
                for (int dy = 0; dy <= depth; dy++) {
                    int y = cy - dy;
                    if (y < lower || y > arena.maxY()) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type.isAir() || AnvilBattleArena.isLiquid(type) || type == Material.BEDROCK
                            || type == Material.BARRIER || type == Material.LIGHT) {
                        continue;
                    }
                    broken.putIfAbsent(key(x, y, z), block.getBlockData().clone());
                    block.setType(Material.AIR, false);
                    changed++;
                }
            }
        }
        return changed;
    }

    private void restoreBlocks() {
        World world = arena.world();
        if (world != null) {
            for (Map.Entry<String, BlockData> entry : broken.entrySet()) {
                String[] parts = entry.getKey().split(",");
                if (parts.length != 3) {
                    continue;
                }
                try {
                    int x = Integer.parseInt(parts[0]);
                    int y = Integer.parseInt(parts[1]);
                    int z = Integer.parseInt(parts[2]);
                    world.getBlockAt(x, y, z).setBlockData(entry.getValue(), false);
                } catch (NumberFormatException ignored) {
                    // 脏 key 跳过
                }
            }
        }
        broken.clear();
    }

    private void removeAnvils() {
        for (UUID id : new ArrayList<>(anvils)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        anvils.clear();
        anvilExpiry.clear();
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    // ---------- 每局循环 ----------

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
        for (UUID id : new ArrayList<>(playing)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && p.getLocation().getY() < arena.getVoidY()) {
                eliminate(p, "掉进虚空");
                if (state != State.RUNNING) {
                    return;
                }
            }
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
        liveSidebar.update("anvilbattle", "&7&l铁砧掘战 · 存活", online, lines);
    }

    private void updateActionBar() {
        if (state != State.RUNNING) {
            return;
        }
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            Chat.actionBar(p, "&7" + GAME + " &8| &f剩余 &e" + secondsLeft + "s"
                    + " &8| &f存活 &e" + playing.size()
                    + " &8| &f每波 &e" + currentAnvilAmount() + " &7个");
        }
    }

    /** 被铁砧砸中（事件入口）。 */
    public void handleAnvilHit(Player player) {
        if (state != State.RUNNING || !playing.contains(player.getUniqueId())) {
            return;
        }
        eliminate(player, "被铁砧砸中");
    }

    /** 其它原因死亡（兜底）。 */
    public void handleDeath(Player player) {
        if (state != State.RUNNING || !playing.contains(player.getUniqueId())) {
            return;
        }
        eliminate(player, "被击败");
    }

    public void handleMove(Player player, Location to) {
        if (state != State.RUNNING || to == null || !playing.contains(player.getUniqueId())) {
            return;
        }
        World world = arena.world();
        if (world == null || !to.getWorld().equals(world)) {
            return;
        }
        if (to.getY() < arena.getVoidY()) {
            eliminate(player, "掉进虚空");
        }
    }

    private void eliminate(Player player, String reason) {
        UUID id = player.getUniqueId();
        if (!playing.remove(id)) {
            return;
        }
        eliminated.add(id);
        elimOrder.add(id);
        resetTargetChance(id);
        clearGameState(player);
        player.setGameMode(GameMode.SPECTATOR);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        Location spectator = arena.center();
        if (spectator != null) {
            player.teleport(spectator);
        }
        HubAPI.setPlaying(id, GAME);
        Chat.send(player, "&c" + reason + "！&7你现在是旁观者，等待本局结束。");
        broadcast("&e" + player.getName() + " &7" + reason + "（剩余 &e" + playing.size() + "&7 人）");
        // 死亡兜底：某些伤害路径可能让客户端停在死亡界面，下一 tick 强制重生到旁观席
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
            UUID winner = playing.iterator().next();
            Set<UUID> winners = new LinkedHashSet<>();
            winners.add(winner);
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
        if (isRunning()) {
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
        boolean running = state == State.RUNNING;
        String extra = running
                ? ("剩余 " + secondsLeft + "s · 存活 " + playing.size() + " · 每波 " + currentAnvilAmount())
                : null;
        HubAPI.setArena(GAME, ARENA, running ? GameState.PLAYING : GameState.WAITING,
                waiting.size() + playing.size() + eliminated.size(), extra);
        HubAPI.setArenaSpectator(GAME, ARENA, arena.center());
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
        for (UUID id : eliminated) {
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

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    // ---------- 任务 ----------

    private void cancelTasks() {
        cancelGameTask();
        cancelWaveTask();
        if (hitTask != null) {
            hitTask.cancel();
            hitTask = null;
        }
    }

    private void cancelGameTask() {
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
    }

    private void cancelWaveTask() {
        if (waveTask != null) {
            waveTask.cancel();
            waveTask = null;
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
            PlayerStats.save("anvilbattle", stats);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存玩家记录失败：" + ex.getMessage());
        }
    }
}
