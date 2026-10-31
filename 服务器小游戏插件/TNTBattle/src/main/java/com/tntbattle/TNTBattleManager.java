package com.tntbattle;

import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.data.PlayerStats;
import com.hub.util.Chat;
import com.hub.util.LiveSidebar;
import com.hub.util.StartCountdown;
import com.hub.util.WaitCountdown;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Display;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.format.NamedTextColor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** TNT 对战：双队、TNT 发射器、定时羊毛、淘汰与胜负。 */
public final class TNTBattleManager {

    public static final String GAME = "TNT 对战";

    private final TNTBattlePlugin plugin;
    private final TNTBattleCore core;
    private final LiveSidebar liveSidebar;
    private int boardTick;
    private final TNTBattleArena arena;
    private final NamespacedKey tntKey;
    private final NamespacedKey startKey;
    private final NamespacedKey returnKey;
    private final NamespacedKey musicItemKey;
    private final NamespacedKey steakKey;
    private final NamespacedKey specialKey;

    private final Map<UUID, PlayerState> states = new LinkedHashMap<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Map<UUID, TNTBattleArena.Team> teams = new HashMap<>();
    private final Set<UUID> eliminated = new HashSet<>();
    private final Set<UUID> spawnedTnt = new HashSet<>();
    private final Map<UUID, Long> tntReadyAt = new HashMap<>();
    private final Map<UUID, Long> steakReadyAt = new HashMap<>();
    private final Map<UUID, Long> specialReadyAt = new HashMap<>();
    /** 正在飞行的黑洞 / 高爆 / 诱饵道具实体（羊、闪电苦力怕）-> 道具配置。 */
    private final Map<UUID, SpecialItem> specialProjectiles = new HashMap<>();
    /** 正在飞行的烈焰弹火球 -> 道具配置。 */
    private final Map<UUID, SpecialItem> flameByProjectile = new HashMap<>();
    /** 正在飞行的搭路蛋 -> 铺路进度。 */
    private final Map<UUID, BridgeTrail> bridgeEggs = new HashMap<>();
    private final Map<String, SpecialItem> specialById = new LinkedHashMap<>();
    private final List<SpecialItem> specialPool = new ArrayList<>();
    private final Set<BukkitTask> effectTasks = new HashSet<>();
    /** 被爆炸/羊毛改变的方块 -> 原始方块数据。 */
    private final Map<String, BlockData> changedBlocks = new LinkedHashMap<>();
    /** 用烈焰弹把自己弹上天的玩家：下一次落地免摔伤（落地事件到达时消费掉）。 */
    private final Set<UUID> fallImmune = new HashSet<>();
    /** 插件自己触发的爆炸（烈焰弹）的爆心，用于在 EntityExplodeEvent 里认领。 */
    private Location pendingExplosionCenter;

    private WaitCountdown countdown;
    private static YamlConfiguration stats;
    private boolean primary;
    private TextDisplay leaderboardDisplay;
    private Chunk leaderboardChunk;
    private final NamespacedKey leaderboardKey;
    private boolean running;
    private boolean ending;
    /** 正在结算/关服回填玩家状态，期间的强制重生不要走旁观流程。 */
    private boolean restoring;
    private long gameStartedAt;
    private int lastRampAmount = 1;
    private BukkitTask cooldownTask;
    private BukkitTask woolTask;
    private BukkitTask steakTask;
    private BukkitTask leaderboardTask;
    /** 背景音乐：录一次红石音乐，之后在对局里逐人循环播放。 */
    private final TNTBattleMusic music;

    public TNTBattleManager(TNTBattlePlugin plugin, TNTBattleCore core, TNTBattleArena arena) {
        this.plugin = plugin;
        this.core = core;
        this.arena = arena;
        this.liveSidebar = new LiveSidebar(plugin);
        this.music = new TNTBattleMusic(plugin, this);
        this.tntKey = new NamespacedKey(plugin, "tnt_ammo");
        this.startKey = new NamespacedKey(plugin, "start_item");
        this.returnKey = new NamespacedKey(plugin, "return_item");
        this.musicItemKey = new NamespacedKey(plugin, "music_toggle_item");
        this.steakKey = new NamespacedKey(plugin, "steak");
        this.specialKey = new NamespacedKey(plugin, "special_item");
        this.leaderboardKey = new NamespacedKey(plugin, "leaderboard_display");
    }

    public void load(boolean primary) {
        this.primary = primary;
        if (primary) {
            ensureDefaults();
        }
        loadSpecialPool();
        arena.load();
        if (primary) {
            loadLeaderboard();
            startLeaderboardTask();
        }
        createCountdown();
        music.load();
        reportArena();
        plugin.getLogger().info("场地 " + arena.name() + (arena.ready() ? " 已配置" : " 未配置") + "。");
        plugin.getLogger().info("TNT 配置：冷却 " + plugin.getConfig().getInt("tnt-cooldown-seconds", 5)
                + "s，引信 " + plugin.getConfig().getInt("tnt-fuse-ticks", 40)
                + " tick，速度 " + plugin.getConfig().getDouble("tnt-velocity", 1.5)
                + "，威力 " + plugin.getConfig().getDouble("tnt-yield", 4.0)
                + "，伤害 " + plugin.getConfig().getDouble("tnt-damage", 10.0)
                + "，半径 " + plugin.getConfig().getDouble("tnt-damage-radius", 6.0));
        plugin.getLogger().info("特殊道具池：" + specialPool.size() + " 种，冷却 "
                + specialCooldownSeconds() + " 秒。");
    }

    /** 读取配置并重建大厅里的悬浮排行榜（先删旧的，避免重载后叠出多个）。 */
    public void loadLeaderboard() {
        Chunk previous = leaderboardChunk;
        Location location = TNTBattleArena.parse(plugin.getConfig().getString("leaderboard.location", ""));
        Chunk target = null;
        if (location != null && location.getWorld() != null) {
            // 先加载目标区块并把旧排行实体一起清掉，再挂常驻 ticket
            target = location.getChunk();
            target.addPluginChunkTicket(plugin);
            leaderboardChunk = target;
        }
        removeTaggedDisplays();
        if (previous != null && !previous.equals(target)) {
            previous.removePluginChunkTicket(plugin);
        }
        if (target == null) {
            leaderboardChunk = null;
            return;
        }
        if (!plugin.getConfig().getBoolean("leaderboard.enabled", true)) {
            return;
        }
        Location spawnAt = location.clone();
        spawnAt.setX(spawnAt.getBlockX() + 0.5);
        spawnAt.setZ(spawnAt.getBlockZ() + 0.5);
        try {
            leaderboardDisplay = spawnAt.getWorld().spawn(spawnAt, TextDisplay.class, entity -> {
                entity.getPersistentDataContainer().set(leaderboardKey, PersistentDataType.BYTE, (byte) 1);
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setAlignment(TextDisplay.TextAlignment.CENTER);
                entity.setLineWidth(240);
                entity.setSeeThrough(false);
                entity.setShadowed(true);
                entity.setPersistent(true);
                entity.setViewRange((float) Math.max(1.0,
                        plugin.getConfig().getDouble("leaderboard.view-range", 4.0)));
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("大厅排行榜生成失败: " + exception.getMessage());
            return;
        }
        refreshLeaderboard();
        plugin.getLogger().info("大厅胜场排行榜已放置于 " + spawnAt.getWorld().getName()
                + " " + spawnAt.getBlockX() + ", " + spawnAt.getBlockY() + ", " + spawnAt.getBlockZ() + "。");
    }

    /** 多世界托管下，排行榜所在世界加载完成后立刻补放（否则启动时世界还没加载会一直空着）。 */
    public void onWorldLoad(World world) {
        if (!primary) {
            return;
        }
        Location location = TNTBattleArena.parse(plugin.getConfig().getString("leaderboard.location", ""));
        if (location != null && location.getWorld() != null && location.getWorld().equals(world)) {
            loadLeaderboard();
        }
    }

    /** 定期确认排行榜还在（世界重载/实体被清掉后自动补回来），并刷新胜场文字。 */
    private void startLeaderboardTask() {
        if (leaderboardTask != null) {
            leaderboardTask.cancel();
        }
        leaderboardTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getConfig().getBoolean("leaderboard.enabled", true)) {
                return;
            }
            String raw = plugin.getConfig().getString("leaderboard.location", "");
            if (raw == null || raw.isBlank() || TNTBattleArena.parse(raw) == null) {
                // 没有配置位置，或所在世界还没加载：等下一轮
                return;
            }
            if (leaderboardDisplay == null || !leaderboardDisplay.isValid()) {
                loadLeaderboard();
            } else {
                refreshLeaderboard();
            }
        }, 100L, 200L);
    }

    private void removeTaggedDisplays() {
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (display.getPersistentDataContainer().has(leaderboardKey, PersistentDataType.BYTE)) {
                    display.remove();
                }
            }
        }
        leaderboardDisplay = null;
    }

    /** 把当前胜场写进悬浮文字；没放置或实体已失效时什么也不做。 */
    public void refreshLeaderboard() {
        if (leaderboardDisplay == null || !leaderboardDisplay.isValid()) {
            return;
        }
        int limit = plugin.getConfig().getInt("leaderboard.limit", 10);
        List<WinEntry> entries = leaderboard(limit);
        List<String> lines = new ArrayList<>();
        lines.add(plugin.getConfig().getString("leaderboard.title", "&6&lTNT 对战 胜场排行榜"));
        if (entries.isEmpty()) {
            lines.add(plugin.getConfig().getString("leaderboard.empty", "&7暂无胜场记录"));
        } else {
            String format = plugin.getConfig().getString("leaderboard.line",
                    "&e#%rank% &f%player% &7- &c%wins% &7胜");
            for (int i = 0; i < entries.size(); i++) {
                WinEntry entry = entries.get(i);
                lines.add(format.replace("%rank%", String.valueOf(i + 1))
                        .replace("%player%", entry.name())
                        .replace("%wins%", String.valueOf(entry.wins())));
            }
        }
        leaderboardDisplay.text(Chat.color(String.join("\n", lines)));
    }

    /** 在指定位置放排行榜（同时打开开关并保存到配置）。 */
    public void setLeaderboardLocation(Location location) {
        plugin.getConfig().set("leaderboard.enabled", true);
        plugin.getConfig().set("leaderboard.location", TNTBattleArena.format(location));
        plugin.saveConfig();
        loadLeaderboard();
    }

    /** 移除排行榜并清掉保存的位置。 */
    public void removeLeaderboard() {
        plugin.getConfig().set("leaderboard.location", "");
        plugin.saveConfig();
        loadLeaderboard();
    }

    public boolean hasLeaderboard() {
        return leaderboardDisplay != null && leaderboardDisplay.isValid();
    }

    private void loadSpecialPool() {
        specialPool.clear();
        specialById.clear();
        int index = 0;
        for (Map<?, ?> raw : plugin.getConfig().getMapList("special-items.pool")) {
            SpecialItem item = SpecialItem.fromMap(raw, index++);
            if (item == null) {
                plugin.getLogger().warning("特殊道具池第 " + index + " 项 type 无法识别，已跳过。");
                continue;
            }
            specialById.put(item.id, item);
            if (!item.enabled) {
                continue;
            }
            specialPool.add(item);
        }
    }

    public int specialCooldownSeconds() {
        return Math.max(0, plugin.getConfig().getInt("special-items.cooldown-seconds", 30));
    }

    /** 重载前先把当前场地（可能还没 /save）写回配置，再重新读取。 */
    public void reload(boolean primary) {
        this.primary = primary;
        saveArena();
        if (countdown != null) {
            countdown.cancel();
        }
        if (primary) {
            ensureDefaults();
        }
        loadSpecialPool();
        arena.load();
        if (primary) {
            loadLeaderboard();
            startLeaderboardTask();
        }
        createCountdown();
        music.load();
        reportArena();
    }

    private void createCountdown() {
        countdown = new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return states.keySet();
            }

            @Override
            public int minPlayers() {
                return TNTBattleManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return TNTBattleManager.this.maxPlayers();
            }

            @Override
            public boolean hasOpQueued() {
                for (UUID id : states.keySet()) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null && player.hasPermission("tntbattle.admin")) {
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
                        Chat.actionBar(player, "&c" + secondsLeft + "s 后开始…");
                    }
                }
            }

            @Override
            public void onMessage(String message) {
                broadcastWaiting(message);
            }

            @Override
            public void onStart() {
                startGame(false);
            }
        });
    }

    public int minPlayers() {
        return Math.max(2, Math.min(plugin.getConfig().getInt("min-players", 2), maxPlayers()));
    }

    public int maxPlayers() {
        int global = Math.max(2, Math.min(100, plugin.getConfig().getInt("max-players", 16)));
        int perArena = arena.maxPlayers();
        return perArena > 0 ? Math.max(2, Math.min(100, perArena)) : global;
    }

    // ---------- 加入 / 离开 ----------

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
        if (!player.hasPermission("tntbattle.play")) {
            Chat.send(player, "&c你没有加入该游戏的权限。");
            return false;
        }
        if (states.size() >= maxPlayers()) {
            Chat.send(player, "&c人数已满（最多 " + maxPlayers() + " 人）。");
            return false;
        }
        if (!arena.ready()) {
            Chat.send(player, "&c场地还没配置好：请管理员设置红蓝出生点和等待点。");
            return false;
        }
        states.put(id, PlayerState.capture(player));
        names.put(id, player.getName());
        core.bind(id, this);
        // 背景音乐默认关闭：每次进等待室都按 music.default-on 重置，想听的人自己右键音符盒开启
        music.setEnabled(id, plugin.getConfig().getBoolean("music.default-on", false));

        HubAPI.setGame(id, GAME);
        HubAPI.announceJoin(plugin.getName(), player.getName());
        prepareWaiting(player);
        countdown.update();
        reportArena();
        Chat.send(player, "&a已加入 TNT 对战，等待开始…");
        return true;
    }

    public void leave(Player player, boolean fromLobby) {
        UUID id = player.getUniqueId();
        PlayerState state = states.remove(id);
        if (state == null) {
            return;
        }
        core.unbind(id);
        names.remove(id);
        teams.remove(id);
        eliminated.remove(id);
        tntReadyAt.remove(id);
        steakReadyAt.remove(id);
        specialReadyAt.remove(id);
        // 离开对局/大厅回调：关掉个人背景音乐开关，避免已不在场的人还让播放任务起来
        music.setEnabled(id, false);

        if (player.isOnline()) {
            clearTeamGlow(player);
            state.apply(player);
            refreshTitle(player);
        }
        HubAPI.clearGame(id);
        if (!fromLobby && player.isOnline()) {
            HubAPI.teleportToLobby(player);
        }
        if (running) {
            checkWin();
        }
        if (running) {
            maybeStartMusic();
        }
        if (!running && !ending) {
            countdown.update();
        }
        reportArena();
        if (running && states.isEmpty()) {
            endGame(null);
        }
    }

    public void quit(Player player) {
        leave(player, true);
        // 玩家退出服务器：清掉个人背景音乐开关
        music.setEnabled(player.getUniqueId(), false);
    }

    // ---------- 开局 / 分队 ----------

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
        int need = force ? 2 : minPlayers();
        if (states.size() < need) {
            broadcastWaiting("&c至少需要 " + need + " 名玩家才能开始。");
            return false;
        }

        if (startCountdown().begin(arena.name(), new ArrayList<>(states.keySet()), () -> startGame(force))) {
            return true;
        }
        if (countdown != null) {
            countdown.cancel();
        }
        changedBlocks.clear();
        spawnedTnt.clear();
        specialProjectiles.clear();
        eliminated.clear();
        fallImmune.clear();
        tntReadyAt.clear();
        steakReadyAt.clear();
        specialReadyAt.clear();
        teams.clear();
        running = true;
        ending = false;
        gameStartedAt = System.currentTimeMillis();
        lastRampAmount = 1;

        List<UUID> ids = new ArrayList<>(states.keySet());
        Collections.shuffle(ids);
        for (int i = 0; i < ids.size(); i++) {
            TNTBattleArena.Team team = (i % 2 == 0) ? TNTBattleArena.Team.RED : TNTBattleArena.Team.BLUE;
            teams.put(ids.get(i), team);
        }

        Map<TNTBattleArena.Team, Integer> spawnCursor = new HashMap<>();
        for (UUID id : ids) {
            HubAPI.setPlaying(id, GAME);
            TNTBattleArena.Team team = teams.get(id);
            HubAPI.setTabNameColor(id, team.tabColor());
            int index = spawnCursor.merge(team, 1, Integer::sum) - 1;
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                preparePlaying(player, team, arena.spawn(team, index));
            }
        }

        broadcastGame("&c&l===== TNT 对战 开始 =====");
        broadcastGame("&c红队 &7: " + teamNames(TNTBattleArena.Team.RED)
                + " &8| &9蓝队 &7: " + teamNames(TNTBattleArena.Team.BLUE));
        broadcastGame("&7右键发射 TNT，每 " + woolSeconds() + " 秒获得 "
                + woolAmount() + " 个队伍羊毛。把对方全部淘汰获胜！");
        startTasks();
        reportArena();
        maybeStartMusic();
        return true;
    }

    private int woolSeconds() {
        return Math.max(1, plugin.getConfig().getInt("wool-cooldown-seconds", 1));
    }

    private int woolAmount() {
        return Math.max(1, plugin.getConfig().getInt("wool-amount", 1));
    }

    private String teamNames(TNTBattleArena.Team team) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, TNTBattleArena.Team> entry : teams.entrySet()) {
            if (entry.getValue() == team) {
                if (sb.length() > 0) {
                    sb.append("&7, &f");
                }
                sb.append("&f").append(names.getOrDefault(entry.getKey(), "?"));
            }
        }
        return sb.length() == 0 ? "&7(无)" : sb.toString();
    }

    private void prepareWaiting(Player player) {
        Location wait = arena.lobby();
        if (wait != null) {
            player.teleport(wait);
        }
        prepare(player);
        giveWaitingItems(player);
    }

    private void preparePlaying(Player player, TNTBattleArena.Team team, Location spawn) {
        prepare(player);
        giveAmmo(player, 1);
        giveWool(player, team);
        giveSteak(player);
        // 全队高亮：红队红光、蓝队蓝光（通过计分板队伍颜色实现）
        applyTeamGlow(player, team);
        // 无粒子夜视
        giveNightVision(player);
        int cooldown = Math.max(1, plugin.getConfig().getInt("tnt-cooldown-seconds", 5));
        tntReadyAt.put(player.getUniqueId(), System.currentTimeMillis() + cooldown * 1000L);
        int steakCooldown = Math.max(1, plugin.getConfig().getInt("steak.refill-cooldown-seconds", 30));
        steakReadyAt.put(player.getUniqueId(), System.currentTimeMillis() + steakCooldown * 1000L);
        int specialCooldown = specialCooldownSeconds();
        if (specialCooldown > 0) {
            specialReadyAt.put(player.getUniqueId(), System.currentTimeMillis() + specialCooldown * 1000L);
        }
        player.setExp(1f);
        player.setLevel(cooldown);

        if (spawn != null) {
            double dx = (Math.random() - 0.5) * 2.0;
            double dz = (Math.random() - 0.5) * 2.0;
            player.teleport(spawn.clone().add(dx, 0, dz));
            player.setFallDistance(0f);
            player.setVelocity(new Vector(0, 0, 0));
        }
    }

    private static final String GLOW_RED_TEAM = "tntbattle_red";
    private static final String GLOW_BLUE_TEAM = "tntbattle_blue";

    /**
     * 红队红光、蓝队蓝光：发光颜色由玩家**当前计分板**上的队伍颜色决定。
     * 对局内用了 LiveSidebar（另一块计分板），所以必须挂到 player.getScoreboard()，
     * 不能只挂主计分板，否则客户端看不到队伍颜色，只会发白光。
     */
    private void applyTeamGlow(Player player, TNTBattleArena.Team team) {
        glowTeam(player.getScoreboard(), team).addEntry(player.getName());
        // 主计分板也挂一份，回大厅/侧边栏关闭后颜色不会突然变白
        glowTeam(Bukkit.getScoreboardManager().getMainScoreboard(), team).addEntry(player.getName());
        player.setGlowing(true);
    }

    private Team glowTeam(Scoreboard board, TNTBattleArena.Team team) {
        String name = team == TNTBattleArena.Team.RED ? GLOW_RED_TEAM : GLOW_BLUE_TEAM;
        Team scoreboardTeam = board.getTeam(name);
        if (scoreboardTeam == null) {
            scoreboardTeam = board.registerNewTeam(name);
        }
        scoreboardTeam.color(team == TNTBattleArena.Team.RED ? NamedTextColor.RED : NamedTextColor.BLUE);
        return scoreboardTeam;
    }

    private void clearTeamGlow(Player player) {
        if (player == null) {
            return;
        }
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (String name : List.of(GLOW_RED_TEAM, GLOW_BLUE_TEAM)) {
            Team team = main.getTeam(name);
            if (team != null) {
                team.removeEntry(player.getName());
            }
        }
        if (player.isOnline()) {
            Scoreboard current = player.getScoreboard();
            if (current != null && current != main) {
                for (String name : List.of(GLOW_RED_TEAM, GLOW_BLUE_TEAM)) {
                    Team team = current.getTeam(name);
                    if (team != null) {
                        team.removeEntry(player.getName());
                    }
                }
            }
            player.setGlowing(false);
        }
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

    /** 无粒子夜视；死亡重生后补一次，旁观期间也保持。 */
    public void giveNightVision(Player player) {
        player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                20 * 60 * 60 * 6, 0, false, false, false));
    }

    private void prepare(Player player) {        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setExtraContents(new ItemStack[inventory.getExtraContents().length]);
        inventory.setItemInOffHand(null);

        player.setGameMode(GameMode.SURVIVAL);
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

    // ---------- 道具 ----------

    /** TNT 发射器固定占快捷栏第 1 格（0 号槽），不会被其他道具挤走。 */
    private static final int TNT_SLOT = 0;
    /** 队伍羊毛固定占快捷栏第 2 格（1 号槽）。 */
    private static final int WOOL_SLOT = 1;

    private void giveAmmo(Player player, int amount) {
        int max = Math.max(1, plugin.getConfig().getInt("tnt-max-ammo", 10));
        int current = countAmmo(player);
        int toGive = Math.min(Math.max(1, Math.min(64, amount)), max - current);
        if (toGive <= 0) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack inSlot = inventory.getItem(TNT_SLOT);
        if (inSlot != null && !inSlot.getType().isAir() && !isTntItem(inSlot)) {
            inventory.setItem(TNT_SLOT, null);
            stashAway(player, inSlot);
            inSlot = null;
        }
        if (inSlot != null && isTntItem(inSlot)) {
            inSlot.setAmount(Math.min(inSlot.getMaxStackSize(), inSlot.getAmount() + toGive));
        } else {
            inventory.setItem(TNT_SLOT, createAmmoItem(toGive));
        }
    }

    private ItemStack createAmmoItem(int amount) {
        ItemStack item = new ItemStack(Material.TNT, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color("&c&lTNT 弹药 &7(右键发射)")));
            meta.lore(List.of(Chat.noItalic(Chat.color("&7右键向准星方向发射"))));
            meta.getPersistentDataContainer().set(tntKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 把不该出现在固定槽的物品挪到其他空位，背包满了就丢在脚下。 */
    private void stashAway(Player player, ItemStack item) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (slot == TNT_SLOT || slot == WOOL_SLOT) {
                continue;
            }
            ItemStack existing = inventory.getItem(slot);
            if (existing == null || existing.getType().isAir()) {
                inventory.setItem(slot, item);
                return;
            }
        }
        player.getWorld().dropItemNaturally(player.getLocation(), item);
    }

    private int countAmmo(Player player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isTntItem(stack)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    public boolean isTntItem(ItemStack item) {
        return hasTag(item, tntKey);
    }

    /** 旧配置缺少的键自动补上，保证这些项能在面板里直接看到并修改。 */
    public void ensureDefaults() {
        boolean changed = false;
        // 1.4.1 一次性迁移：引信 2 秒（40 tick）、初速度 1.50
        if (plugin.getConfig().getInt("config-version", 1) < 2) {
            plugin.getConfig().set("tnt-fuse-ticks", 40);
            plugin.getConfig().set("tnt-velocity", 1.5);
            plugin.getConfig().set("config-version", 2);
            changed = true;
        }
        // 1.7.0 一次性迁移：给已有特殊道具补 enabled 开关，并追加搭路蛋 / 天使之赐
        if (plugin.getConfig().getInt("config-version", 1) < 3) {
            migrateSpecialPoolV3();
            plugin.getConfig().set("config-version", 3);
            changed = true;
        }
        // 1.8.0 一次性迁移：给已有的搭路蛋补 width（桥宽）
        if (plugin.getConfig().getInt("config-version", 1) < 4) {
            migrateBridgeWidthV4();
            plugin.getConfig().set("config-version", 4);
            changed = true;
        }
        // 1.10.0 一次性迁移：给特殊道具补 unlock-seconds（黑洞/搭路蛋默认 120 秒后才进池）
        if (plugin.getConfig().getInt("config-version", 1) < 5) {
            migrateSpecialUnlockV5();
            plugin.getConfig().set("config-version", 5);
            changed = true;
        }
        // 1.11.0 一次性迁移：追加粘性炸药
        if (plugin.getConfig().getInt("config-version", 1) < 6) {
            migrateStickyV6();
            plugin.getConfig().set("config-version", 6);
            changed = true;
        }
        // 1.11.7 一次性迁移：给烈焰弹补 self-launch / self-launch-radius（脚下自爆把自己弹上天）
        if (plugin.getConfig().getInt("config-version", 1) < 7) {
            changed |= migrateFlameSelfLaunchV7();
            plugin.getConfig().set("config-version", 7);
            changed = true;
        }
        changed |= setDefault("min-players", 2);
        changed |= setDefault("max-players", 16);
        changed |= setDefault("tnt-cooldown-seconds", 5);
        changed |= setDefault("tnt-max-ammo", 10);
        changed |= setDefault("tnt-fuse-ticks", 40);
        changed |= setDefault("tnt-velocity", 1.4);
        changed |= setDefault("tnt-yield", 4.0);
        changed |= setDefault("tnt-damage", 10.0);
        changed |= setDefault("tnt-damage-radius", 6.0);
        changed |= setDefault("tnt-ramp.enabled", true);
        changed |= setDefault("tnt-ramp.interval-seconds", 60);
        changed |= setDefault("tnt-ramp.amount-per-step", 1);
        changed |= setDefault("tnt-ramp.max-amount", 5);
        changed |= setDefault("fall-damage-multiplier", 0.5);
        changed |= setDefault("bridge-egg.ground-scan-depth", 8);
        changed |= setDefault("bridge-egg.ground-min-gap", 1.0);
        changed |= setDefault("wool-cooldown-seconds", 1);
        changed |= setDefault("wool-amount", 1);
        changed |= setDefault("void-y", -64);
        changed |= setDefault("build-height-limit", -1);
        changed |= setDefault("end-delay-ticks", 60);
        changed |= setDefault("steak.enabled", true);
        changed |= setDefault("steak.amount", 64);
        changed |= setDefault("steak.slot", 8);
        changed |= setDefault("steak.auto-refill", false);
        changed |= setDefault("steak.refill-cooldown-seconds", 30);
        changed |= setDefault("leaderboard.enabled", true);
        changed |= setDefault("leaderboard.limit", 10);
        changed |= setDefault("leaderboard.title", "&6&lTNT 对战 胜场排行榜");
        changed |= setDefault("leaderboard.line", "&e#%rank% &f%player% &7- &c%wins% &7胜");
        changed |= setDefault("leaderboard.empty", "&7暂无胜场记录");
        changed |= setDefault("leaderboard.view-range", 4.0);
        changed |= setDefault("leaderboard.location", "");
        changed |= setDefault("music.enabled", true);
        changed |= setDefault("music.loop", true);
        changed |= setDefault("music.default-on", false);
        changed |= setDefault("music.volume", 1.0);
        changed |= setDefault("music.trigger-team-alive", 1);
        changed |= setDefault("music.tail-ticks", 20);
        changed |= setDefault("music.max-record-seconds", 600);
        if (!plugin.getConfig().isConfigurationSection("wait-items")) {
            plugin.getConfig().set("wait-items.return.material", "CLOCK");
            plugin.getConfig().set("wait-items.return.name", "&a&l回主城 &7(右键)");
            plugin.getConfig().set("wait-items.return.lore", List.of("&7点击返回主城"));
            plugin.getConfig().set("wait-items.return.slot", 8);
            plugin.getConfig().set("wait-items.start.material", "PAPER");
            plugin.getConfig().set("wait-items.start.name", "&a&l开始游戏 &7(右键)");
            plugin.getConfig().set("wait-items.start.lore", List.of("&7点击立刻开始本局"));
            plugin.getConfig().set("wait-items.start.slot", 4);
            changed = true;
        }
        changed |= setDefault("wait-items.music.material", "NOTE_BLOCK");
        changed |= setDefault("wait-items.music.name", "&b&l背景音乐 &7(右键开关)");
        changed |= setDefault("wait-items.music.lore", List.of("&7只控制你自己能不能听到"));
        changed |= setDefault("wait-items.music.slot", 0);
        if (!plugin.getConfig().isConfigurationSection("special-items")) {
            plugin.getConfig().set("special-items.cooldown-seconds", 30);
            plugin.getConfig().set("special-items.pool", defaultSpecialPool());
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
    }

    private List<Map<String, Object>> defaultSpecialPool() {
        List<Map<String, Object>> pool = new ArrayList<>();
        pool.add(specialEntry("flame", 25, "BLAZE_POWDER", "&6&l烈焰弹 &7(右键发射)",
                List.of("&7碰到方块才会爆炸", "&7炸在自己脚边会把自己弹上天"), Map.of("velocity", 1.2, "yield", 4.0,
                        "damage", 10.0, "damage-radius", 6.0, "self-launch", 1.0, "self-launch-radius", 3.0)));
        pool.add(specialEntry("blackhole", 20, "NETHER_STAR", "&5&l黑洞炸弹 &7(右键发射)",
                List.of("&7落地后把附近玩家吸向中心"), Map.of("velocity", 1.0, "fuse-ticks", 20,
                        "pull-radius", 8.0, "pull-strength", 0.35, "pull-seconds", 4,
                        "unlock-seconds", 120)));
        pool.add(specialEntry("decoy", 20, "TARGET", "&e&l诱饵炸弹 &7(右键发射)",
                List.of("&7让附近玩家的视线锁定它"), Map.of("velocity", 1.2, "fuse-ticks", 20,
                        "lock-radius", 12.0, "lock-seconds", 5)));
        pool.add(specialEntry("highex", 20, "TNT", "&c&l高爆炸弹 &7(右键发射)",
                List.of("&7爆炸范围更大"), Map.of("velocity", 1.4, "fuse-ticks", 40,
                        "yield", 8.0, "damage", 16.0, "damage-radius", 10.0)));
        pool.add(specialEntry("sticky", 18, "SLIME_BLOCK", "&a&l粘性炸药 &7(右键发射)",
                List.of("&7飞出去后黏在方块上，到点爆炸"), Map.of("velocity", 1.3, "fuse-ticks", 40,
                        "yield", 6.0, "damage", 12.0, "damage-radius", 8.0, "unlock-seconds", 0)));
        pool.add(specialEntry("bridge_egg", 20, "EGG", "&f&l搭路蛋 &7(右键发射)",
                List.of("&7沿蛋的飞行轨迹铺出羊毛路"), Map.of("velocity", 1.2, "max-blocks", 30, "width", 1,
                        "unlock-seconds", 120)));
        pool.add(specialEntry("angel", 15, "FEATHER", "&b&l天使之赐 &7(右键使用)",
                List.of("&7获得漂浮效果"), Map.of("levitation-seconds", 6, "levitation-amplifier", 0)));
        return pool;
    }

    /** 旧配置升级：给每项补 `enabled: true`，并补上新增的两种道具（已有的不重复加）。 */
    private void migrateSpecialPoolV3() {
        List<?> raw = plugin.getConfig().getList("special-items.pool", List.of());
        List<Object> pool = new ArrayList<>();
        boolean hasBridgeEgg = false;
        boolean hasAngel = false;
        for (Object entry : raw) {
            if (!(entry instanceof Map<?, ?> map)) {
                pool.add(entry);
                continue;
            }
            Map<Object, Object> copy = new LinkedHashMap<>(map);
            copy.putIfAbsent("enabled", true);
            String type = String.valueOf(copy.get("type")).toLowerCase(Locale.ROOT).replace('-', '_');
            if (type.equals("bridge_egg")) {
                hasBridgeEgg = true;
            }
            if (type.equals("angel")) {
                hasAngel = true;
            }
            pool.add(copy);
        }
        if (!hasBridgeEgg) {
            pool.add(specialEntry("bridge_egg", 20, "EGG", "&f&l搭路蛋 &7(右键发射)",
                    List.of("&7沿蛋的飞行轨迹铺出羊毛路"), Map.of("velocity", 1.2, "max-blocks", 30, "width", 1)));
        }
        if (!hasAngel) {
            pool.add(specialEntry("angel", 15, "FEATHER", "&b&l天使之赐 &7(右键使用)",
                    List.of("&7获得漂浮效果"), Map.of("levitation-seconds", 6, "levitation-amplifier", 0)));
        }
        plugin.getConfig().set("special-items.pool", pool);
    }

    /** 旧配置升级：给搭路蛋补 `width`（桥宽），其它条目原样保留。 */
    private void migrateBridgeWidthV4() {
        List<?> raw = plugin.getConfig().getList("special-items.pool", List.of());
        List<Object> pool = new ArrayList<>();
        for (Object entry : raw) {
            if (!(entry instanceof Map<?, ?> map)) {
                pool.add(entry);
                continue;
            }
            Map<Object, Object> copy = new LinkedHashMap<>(map);
            String type = String.valueOf(copy.get("type")).toLowerCase(Locale.ROOT).replace('-', '_');
            if ("bridge_egg".equals(type)) {
                copy.putIfAbsent("width", 1);
            }
            pool.add(copy);
        }
        plugin.getConfig().set("special-items.pool", pool);
    }

    /** 旧配置升级：给每项补 `unlock-seconds`（开局多少秒后才进入随机池），黑洞 / 搭路蛋默认 120。 */
    private void migrateSpecialUnlockV5() {
        List<?> raw = plugin.getConfig().getList("special-items.pool", List.of());
        List<Object> pool = new ArrayList<>();
        for (Object entry : raw) {
            if (!(entry instanceof Map<?, ?> map)) {
                pool.add(entry);
                continue;
            }
            Map<Object, Object> copy = new LinkedHashMap<>(map);
            String type = String.valueOf(copy.get("type")).toLowerCase(Locale.ROOT).replace('-', '_');
            int unlock = ("blackhole".equals(type) || "bridge_egg".equals(type)) ? 120 : 0;
            copy.putIfAbsent("unlock-seconds", unlock);
            pool.add(copy);
        }
        plugin.getConfig().set("special-items.pool", pool);
    }

    /** 旧配置升级：把粘性炸药追加进池子（已有则不重复加）。 */
    private void migrateStickyV6() {
        List<?> raw = plugin.getConfig().getList("special-items.pool", List.of());
        List<Object> pool = new ArrayList<>(raw);
        for (Object entry : raw) {
            if (entry instanceof Map<?, ?> map) {
                String type = String.valueOf(map.get("type")).toLowerCase(Locale.ROOT).replace('-', '_');
                if ("sticky".equals(type)) {
                    return;
                }
            }
        }
        pool.add(specialEntry("sticky", 18, "SLIME_BLOCK", "&a&l粘性炸药 &7(右键发射)",
                List.of("&7飞出去后黏在方块上，到点爆炸"), Map.of("velocity", 1.3, "fuse-ticks", 40,
                        "yield", 6.0, "damage", 12.0, "damage-radius", 8.0, "unlock-seconds", 0)));
        plugin.getConfig().set("special-items.pool", pool);
    }

    /** 旧配置升级：给烈焰弹补 self-launch（自己脚下的竖直初速度）和 self-launch-radius。 */
    private boolean migrateFlameSelfLaunchV7() {
        List<?> raw = plugin.getConfig().getList("special-items.pool", List.of());
        List<Object> pool = new ArrayList<>();
        boolean changed = false;
        for (Object entry : raw) {
            if (!(entry instanceof Map<?, ?> map)) {
                pool.add(entry);
                continue;
            }
            Map<Object, Object> copy = new LinkedHashMap<>(map);
            String type = String.valueOf(copy.get("type")).toLowerCase(Locale.ROOT).replace('-', '_');
            if ("flame".equals(type)) {
                if (!copy.containsKey("self-launch")) {
                    copy.put("self-launch", 1.0);
                    changed = true;
                }
                if (!copy.containsKey("self-launch-radius")) {
                    copy.put("self-launch-radius", 3.0);
                    changed = true;
                }
            }
            pool.add(copy);
        }
        if (changed) {
            plugin.getConfig().set("special-items.pool", pool);
        }
        return changed;
    }

    private Map<String, Object> specialEntry(String type, int weight, String material,
                                             String name, List<String> lore, Map<String, Object> extra) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("enabled", true);
        entry.put("weight", weight);
        entry.put("material", material);
        entry.put("name", name);
        entry.put("lore", lore);
        entry.putAll(extra);
        return entry;
    }

    private boolean setDefault(String path, Object value) {
        if (plugin.getConfig().isSet(path)) {
            return false;
        }
        plugin.getConfig().set(path, value);
        return true;
    }

    private void giveWaitingItems(Player player) {
        player.getInventory().setItem(itemSlot("wait-items.music", 0),
                musicItem(music.isEnabled(player.getUniqueId())));
        player.getInventory().setItem(itemSlot("wait-items.return", 8),
                waitingItem("wait-items.return", returnKey, Material.CLOCK,
                        "&a&l回主城 &7(右键)", List.of("&7点击返回主城")));
        if (player.hasPermission("tntbattle.admin")) {
            player.getInventory().setItem(itemSlot("wait-items.start", 4),
                    waitingItem("wait-items.start", startKey, Material.PAPER,
                            "&a&l开始游戏 &7(右键)", List.of("&7点击立刻开始本局")));
        }
    }

    /** 等待室的音符盒道具：默认关闭；右键只开启自己的背景音乐，不影响别人。 */
    private ItemStack musicItem(boolean enabled) {
        ItemStack item = waitingItem("wait-items.music", musicItemKey, Material.NOTE_BLOCK,
                "&b&l背景音乐 &7(右键开关)", List.of("&7只控制你自己能不能听到"));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.lore(List.of(
                    Chat.noItalic(Chat.color("&7只控制你自己能不能听到")),
                    Chat.noItalic(Chat.color(enabled ? "&a当前：已开启" : "&c当前：已关闭"))));
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isMusicItem(ItemStack item) {
        return hasTag(item, musicItemKey);
    }

    /** 右键音符盒道具：切换个人开关（只在等待阶段生效）。 */
    public void toggleMusic(Player player) {
        if (!isPlaying(player) || running || ending) {
            return;
        }
        boolean enabled = music.toggleEnabled(player.getUniqueId());
        Chat.send(player, enabled ? "&a背景音乐已开启（只对你生效）" : "&7背景音乐已关闭（只对你生效）");
        int slot = findItemSlot(player, musicItemKey);
        player.getInventory().setItem(slot >= 0 ? slot : itemSlot("wait-items.music", 0),
                musicItem(enabled));
    }

    /** 找带指定标记的道具当前所在的槽位（道具可能被玩家挪过位置）；找不到返回 -1。 */
    private int findItemSlot(Player player, NamespacedKey key) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (hasTag(inventory.getItem(slot), key)) {
                return slot;
            }
        }
        return -1;
    }

    private int itemSlot(String path, int fallback) {
        return Math.max(0, Math.min(8, plugin.getConfig().getInt(path + ".slot", fallback)));
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

    public boolean isReturnItem(ItemStack item) {
        return hasTag(item, returnKey);
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

    public void useReturnItem(Player player) {
        if (states.containsKey(player.getUniqueId())) {
            leave(player, false);
        }
    }

    // ---------- TNT ----------

    public void launchTnt(Player player) {
        if (!running || ending || !isAlive(player)) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isTntItem(hand)) {
            return;
        }
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - 1);
        }

        Vector direction = player.getEyeLocation().getDirection().normalize();
        Location spawn = player.getEyeLocation().add(direction.clone().multiply(1.0));
        TNTPrimed tnt = player.getWorld().spawn(spawn, TNTPrimed.class);
        tnt.setVelocity(direction.multiply(plugin.getConfig().getDouble("tnt-velocity", 1.5)));
        tnt.setFuseTicks(Math.max(1, plugin.getConfig().getInt("tnt-fuse-ticks", 40)));
        tnt.setYield((float) plugin.getConfig().getDouble("tnt-yield", 4.0));
        tnt.setIsIncendiary(false);
        tnt.setSource(player);
        spawnedTnt.add(tnt.getUniqueId());
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_TNT_PRIMED, 0.8f, 1.0f);
    }

    public boolean ownsTnt(TNTPrimed tnt) {
        return spawnedTnt.contains(tnt.getUniqueId());
    }

    public void onTntExplode(TNTPrimed tnt, Location center) {
        spawnedTnt.remove(tnt.getUniqueId());
        damagePlayers(center, tntDamage(), tntDamageRadius());
    }

    public void rememberBlock(Block block) {
        if (!arena.contains(block.getLocation())) {
            return;
        }
        String key = block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ();
        changedBlocks.putIfAbsent(key, block.getBlockData().clone());
    }

    public boolean insideArena(Block block) {
        return block != null && arena.contains(block.getLocation());
    }

    /** 建筑高度上限（绝对 Y，每场独立，未设置时回退全局）；-1 = 不限制。 */
    public int buildHeightLimit() {
        return arena.buildHeightLimit();
    }

    public boolean withinBuildHeight(Block block) {
        int limit = buildHeightLimit();
        return limit < 0 || block.getY() <= limit;
    }

    /** 是否是羊毛方块（16 色）。 */
    public boolean isWool(Block block) {
        return block != null && org.bukkit.Tag.WOOL.isTagged(block.getType());
    }

    /** 允许徒手破坏的玻璃类方块（玻璃 / 玻璃板 / 各色玻璃与玻璃板），破坏不掉落。 */
    public boolean isGlass(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        if (type == Material.GLASS || type == Material.GLASS_PANE || type == Material.TINTED_GLASS) {
            return true;
        }
        String name = type.name();
        return name.endsWith("_STAINED_GLASS") || name.endsWith("_STAINED_GLASS_PANE");
    }

    public void rememberBlockState(BlockState state) {
        if (state == null || !arena.contains(state.getLocation())) {
            return;
        }
        String key = state.getLocation().getWorld().getName() + "," + state.getLocation().getBlockX()
                + "," + state.getLocation().getBlockY() + "," + state.getLocation().getBlockZ();
        changedBlocks.putIfAbsent(key, state.getBlockData().clone());
    }

    /** TNT 爆炸对玩家造成的伤害（半颗心 = 1）；0 表示不掉血。 */
    public double tntDamage() {
        return Math.max(0.0, plugin.getConfig().getDouble("tnt-damage", 10.0));
    }

    /** 当前每次冷却应发放的 TNT 数量：随时间按档位递增，封顶。 */
    public int tntAmountNow() {
        if (!plugin.getConfig().getBoolean("tnt-ramp.enabled", true)) {
            return 1;
        }
        int interval = Math.max(0, plugin.getConfig().getInt("tnt-ramp.interval-seconds", 60));
        if (interval <= 0 || gameStartedAt <= 0) {
            return 1;
        }
        long elapsed = Math.max(0, (System.currentTimeMillis() - gameStartedAt) / 1000L);
        int step = (int) (elapsed / interval);
        int perStep = Math.max(0, plugin.getConfig().getInt("tnt-ramp.amount-per-step", 1));
        int max = Math.max(1, plugin.getConfig().getInt("tnt-ramp.max-amount", 5));
        return Math.min(max, 1 + step * perStep);
    }

    /** 摔落伤害倍率：0 = 无摔落伤害，1 = 原版。 */
    public double fallDamageMultiplier() {
        return Math.max(0.0, Math.min(1.0,
                plugin.getConfig().getDouble("fall-damage-multiplier", 0.5)));
    }

    /** 这一次落地免摔伤（自己用烈焰弹弹上天时挂上，落地事件到达时消费掉）。 */
    public boolean consumeFallImmunity(Player player) {
        return player != null && fallImmune.remove(player.getUniqueId());
    }

    public double tntDamageRadius() {
        return Math.max(1.0, plugin.getConfig().getDouble("tnt-damage-radius", 6.0));
    }

    /** 排查伤害异常用：config 的 debug-damage 打开后，把每次爆炸和每个受害者的数值打到控制台。 */
    public boolean debugDamage() {
        return plugin.getConfig().getBoolean("debug-damage", false);
    }

    /** 爆炸时由插件主动扣血 + 击退，不依赖原版爆炸伤害事件。 */
    public void damagePlayers(Location center, double damage, double radius) {
        damagePlayers(center, damage, radius, null);
    }

    /** 同上，exempt 那个玩家不吃这次伤害和击退（烈焰弹把自己弹上天时用）。 */
    public void damagePlayers(Location center, double damage, double radius, UUID exempt) {
        if (damage <= 0) {
            return;
        }
        boolean debug = debugDamage();
        if (debug) {
            plugin.getLogger().info("[伤害调试] 爆炸 " + center.getWorld().getName()
                    + " " + fmt(center.getX()) + "," + fmt(center.getY()) + "," + fmt(center.getZ())
                    + " 配置伤害=" + damage + " 半径=" + radius);
        }
        for (UUID id : states.keySet()) {
            if (eliminated.contains(id) || id.equals(exempt)) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline() || !player.getWorld().equals(center.getWorld())) {
                continue;
            }
            double distance = player.getLocation().distance(center);
            if (distance > radius) {
                continue;
            }
            double factor = Math.max(0.15, 1.0 - distance / radius);
            double before = player.getHealth();
            player.setHealth(Math.max(0.0, before - damage * factor));
            if (debug) {
                plugin.getLogger().info("[伤害调试] " + player.getName()
                        + " 距离=" + fmt(distance) + " 系数=" + fmt(factor)
                        + " 血量 " + fmt(before) + " -> " + fmt(player.getHealth()));
            }

            Vector push = player.getLocation().toVector().subtract(center.toVector());
            if (push.lengthSquared() > 0.01) {
                push.normalize().multiply(0.5).setY(0.35);
                player.setVelocity(player.getVelocity().add(push));
            }
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private void restoreChangedBlocks() {
        for (Map.Entry<String, BlockData> entry : changedBlocks.entrySet()) {
            String[] parts = entry.getKey().split(",");
            if (parts.length != 4) {
                continue;
            }
            org.bukkit.World world = Bukkit.getWorld(parts[0]);
            if (world == null) {
                continue;
            }
            try {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                world.getBlockAt(x, y, z).setBlockData(entry.getValue(), false);
            } catch (NumberFormatException ignored) {
                // 损坏的快照行跳过
            }
        }
        changedBlocks.clear();
    }

    // ---------- 任务 ----------

    private void startTasks() {
        cancelTasks();
        cooldownTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickCooldowns, 2L, 2L);
        long woolTicks = Math.max(1, plugin.getConfig().getInt("wool-cooldown-seconds", 1)) * 20L;
        woolTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWool, woolTicks, woolTicks);
        steakTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSteak, 20L, 20L);
    }

    private void cancelTasks() {
        if (cooldownTask != null) {
            cooldownTask.cancel();
            cooldownTask = null;
        }
        if (woolTask != null) {
            woolTask.cancel();
            woolTask = null;
        }
        if (steakTask != null) {
            steakTask.cancel();
            steakTask = null;
        }
    }

    private void tickCooldowns() {
        updateLiveBoardThrottled();
        if (!running || ending) {
            return;
        }
        long now = System.currentTimeMillis();
        int cooldown = Math.max(1, plugin.getConfig().getInt("tnt-cooldown-seconds", 5));
        int currentAmount = tntAmountNow();
        if (currentAmount > lastRampAmount) {
            lastRampAmount = currentAmount;
            broadcastGame("&eTNT 补给提升！现在每次冷却获得 &f" + currentAmount + " &e个 TNT");
        }
        for (UUID id : states.keySet()) {
            if (eliminated.contains(id)) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            long readyAt = tntReadyAt.getOrDefault(id, 0L);
            long remaining = readyAt - now;
            if (remaining <= 0) {
                giveAmmo(player, currentAmount);
                tntReadyAt.put(id, now + cooldown * 1000L);
                player.setExp(1f);
                player.setLevel(cooldown);
            } else {
                player.setExp(Math.min(1f, remaining / (cooldown * 1000f)));
                player.setLevel((int) Math.ceil(remaining / 1000.0));
            }
            int specialCooldown = specialCooldownSeconds();
            if (specialCooldown > 0 && !specialPool.isEmpty()
                    && now >= specialReadyAt.getOrDefault(id, 0L)) {
                giveSpecial(player);
                specialReadyAt.put(id, now + specialCooldown * 1000L);
            }
        }
    }

    private void tickWool() {
        if (!running || ending) {
            return;
        }
        for (UUID id : states.keySet()) {
            if (eliminated.contains(id)) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            TNTBattleArena.Team team = teams.get(id);
            if (player == null || !player.isOnline() || team == null) {
                continue;
            }
            giveWool(player, team);
        }
    }

    /** 队伍羊毛固定放在第 2 格（1 号槽），不会被特殊道具挤走。 */
    private void giveWool(Player player, TNTBattleArena.Team team) {
        PlayerInventory inventory = player.getInventory();
        Material wool = team.wool();
        ItemStack inSlot = inventory.getItem(WOOL_SLOT);
        if (inSlot != null && !inSlot.getType().isAir() && inSlot.getType() != wool) {
            inventory.setItem(WOOL_SLOT, null);
            stashAway(player, inSlot);
            inSlot = null;
        }
        int amount = woolAmount();
        if (inSlot != null && inSlot.getType() == wool) {
            int max = inSlot.getMaxStackSize();
            if (inSlot.getAmount() < max) {
                inSlot.setAmount(Math.min(max, inSlot.getAmount() + amount));
            }
        } else {
            inventory.setItem(WOOL_SLOT, new ItemStack(wool, Math.min(amount, wool.getMaxStackSize())));
        }
    }

    // ---------- 牛排 ----------

    private void giveSteak(Player player) {
        if (!plugin.getConfig().getBoolean("steak.enabled", true)) {
            return;
        }
        player.getInventory().setItem(steakSlot(), createSteak(steakAmount()));
    }

    private int steakSlot() {
        return Math.max(0, Math.min(35, plugin.getConfig().getInt("steak.slot", 8)));
    }

    private ItemStack createSteak(int amount) {
        ItemStack steak = new ItemStack(Material.COOKED_BEEF, amount);
        ItemMeta meta = steak.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color("&e&l牛排 &7(右键食用)")));
            meta.getPersistentDataContainer().set(steakKey, PersistentDataType.BYTE, (byte) 1);
            steak.setItemMeta(meta);
        }
        return steak;
    }

    private boolean isSteak(ItemStack item) {
        return hasTag(item, steakKey);
    }

    private int steakAmount() {
        return Math.max(1, Math.min(64, plugin.getConfig().getInt("steak.amount", 64)));
    }

    private void tickSteak() {
        if (!running || ending
                || !plugin.getConfig().getBoolean("steak.enabled", true)
                || !plugin.getConfig().getBoolean("steak.auto-refill", false)) {
            return;
        }
        int interval = Math.max(1, plugin.getConfig().getInt("steak.refill-cooldown-seconds", 30));
        long now = System.currentTimeMillis();
        for (UUID id : states.keySet()) {
            if (eliminated.contains(id)) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (now < steakReadyAt.getOrDefault(id, 0L)) {
                continue;
            }
            if (refillSteak(player)) {
                steakReadyAt.put(id, now + interval * 1000L);
            }
        }
    }

    private boolean refillSteak(Player player) {
        int amount = steakAmount();
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (isSteak(item)) {
                if (item.getAmount() >= amount) {
                    return false;
                }
                item.setAmount(amount);
                return true;
            }
        }
        ItemStack offhand = inventory.getItemInOffHand();
        if (isSteak(offhand)) {
            if (offhand.getAmount() >= amount) {
                return false;
            }
            offhand.setAmount(amount);
            inventory.setItemInOffHand(offhand);
            return true;
        }
        ItemStack last = inventory.getItem(steakSlot());
        if (last == null || last.getType().isAir()) {
            inventory.setItem(steakSlot(), createSteak(amount));
            return true;
        }
        return false;
    }

    // ---------- 特殊道具 ----------

    private void giveSpecial(Player player) {
        SpecialItem special = pickSpecial();
        if (special == null) {
            return;
        }
        ItemStack item = new ItemStack(special.material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color(special.name)));
            meta.lore(special.lore.stream().map(line -> Chat.noItalic(Chat.color(line))).toList());
            meta.getPersistentDataContainer().set(specialKey, PersistentDataType.STRING, special.id);
            item.setItemMeta(meta);
        }
        addSpecialItem(player, item);
    }

    /** 特殊道具不会占用第 1、2 格（TNT/羊毛的固定槽），从第 3 格往后找位置。 */
    private void addSpecialItem(Player player, ItemStack item) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 2; slot < 36 && item.getAmount() > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing != null && existing.isSimilar(item)
                    && existing.getAmount() < existing.getMaxStackSize()) {
                int move = Math.min(existing.getMaxStackSize() - existing.getAmount(), item.getAmount());
                existing.setAmount(existing.getAmount() + move);
                item.setAmount(item.getAmount() - move);
            }
        }
        if (item.getAmount() <= 0) {
            return;
        }
        for (int slot = 2; slot < 36; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing == null || existing.getType().isAir()) {
                inventory.setItem(slot, item);
                return;
            }
        }
        player.getWorld().dropItemNaturally(player.getLocation(), item);
    }

    /** 本局已经进行了多少秒（未开局返回 0）。 */
    private long elapsedSeconds() {
        if (gameStartedAt <= 0) {
            return 0;
        }
        return Math.max(0, (System.currentTimeMillis() - gameStartedAt) / 1000L);
    }

    private SpecialItem pickSpecial() {
        long elapsed = elapsedSeconds();
        List<SpecialItem> available = new ArrayList<>();
        for (SpecialItem item : specialPool) {
            if (item.unlockSeconds <= elapsed) {
                available.add(item);
            }
        }
        if (available.isEmpty()) {
            return null;
        }
        double total = 0;
        for (SpecialItem item : available) {
            total += item.weight;
        }
        if (total <= 0) {
            return available.get(0);
        }
        double roll = Math.random() * total;
        for (SpecialItem item : available) {
            roll -= item.weight;
            if (roll <= 0) {
                return item;
            }
        }
        return available.get(available.size() - 1);
    }

    public boolean isSpecialItem(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer()
                .has(specialKey, PersistentDataType.STRING);
    }

    public void launchSpecial(Player player, ItemStack item) {
        if (!running || ending || !isAlive(player)) {
            return;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(specialKey, PersistentDataType.STRING);
        SpecialItem special = id == null ? null : specialById.get(id);
        if (special == null) {
            return;
        }
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }

        if (special.type == SpecialItem.Type.FLAME) {
            launchFlame(player, special);
            return;
        }
        if (special.type == SpecialItem.Type.BRIDGE_EGG) {
            launchBridgeEgg(player, special);
            return;
        }
        if (special.type == SpecialItem.Type.ANGEL) {
            useAngel(player, special);
            return;
        }

        Vector direction = player.getEyeLocation().getDirection().normalize();
        Location spawn = player.getEyeLocation().add(direction.clone().multiply(1.0));
        launchSpecialProjectile(player, special, direction, spawn);
    }

    /**
     * 黑洞 / 高爆 / 诱饵：用带队伍看不出区别的代表实体当飞行道具
     * （黑洞 = 黑色羊，高爆 = 闪电苦力怕，诱饵 = 红色羊），到引信时间后执行各自效果。
     */
    private void launchSpecialProjectile(Player player, SpecialItem special, Vector direction, Location spawn) {
        Entity entity;
        if (special.type == SpecialItem.Type.HIGHEX) {
            Creeper creeper = spawn.getWorld().spawn(spawn, Creeper.class);
            creeper.setPowered(true);
            // 只是外观：本体不爆，伤害由插件在到点后统一结算
            creeper.setExplosionRadius(0);
            entity = creeper;
        } else if (special.type == SpecialItem.Type.STICKY) {
            // 粘性炸药：用粘液块外观的展示实体当子弹（没有物理、没有 AI）
            BlockDisplay display = spawn.getWorld().spawn(spawn, BlockDisplay.class);
            display.setBlock(Material.SLIME_BLOCK.createBlockData());
            display.setTransformation(new Transformation(new Vector3f(-0.5f, -0.5f, -0.5f),
                    new Quaternionf(), new Vector3f(1f, 1f, 1f), new Quaternionf()));
            entity = display;
        } else {
            Sheep sheep = spawn.getWorld().spawn(spawn, Sheep.class);
            sheep.setColor(special.type == SpecialItem.Type.BLACKHOLE ? DyeColor.BLACK : DyeColor.RED);
            entity = sheep;
        }
        if (entity instanceof Mob mob) {
            // NoAI 生物在这版 Paper 里不参与物理（给速度也不动、也不掉），轨迹由插件自己每 tick 推
            mob.setAI(false);
            mob.setSilent(true);
            mob.setPersistent(false);
            mob.setCollidable(false);
        }
        entity.setInvulnerable(true);
        entity.setGravity(false);
        specialProjectiles.put(entity.getUniqueId(), special);
        SpecialFlight flight = new SpecialFlight(entity, special,
                direction.clone().multiply(Math.max(0.2, special.velocity)),
                Math.max(1, special.fuseTicks));
        startSpecialFlight(flight);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_TNT_PRIMED, 0.8f, 0.8f);
    }

    /** 手动推进代表实体的抛物线轨迹（无 AI 实体不会自己动）；撞到方块只是停下，到引信时间才生效。 */
    private void startSpecialFlight(SpecialFlight flight) {
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Entity entity = flight.entity;
            if (!running || !entity.isValid() || flight.ticksLeft-- <= 0) {
                holder[0].cancel();
                effectTasks.remove(holder[0]);
                detonateSpecialProjectile(entity, flight.special);
                return;
            }
            if (flight.stopped) {
                return;
            }
            Location current = entity.getLocation();
            Vector velocity = flight.velocity;
            // 逐轴判定：撞到哪一面墙就只清掉那一轴，保留沿墙的切向速度，斜着撞墙会贴着墙面滑走
            if (velocity.getX() != 0 && blockedAlong(current, velocity.getX(), 0, 0)) {
                if (flight.sticky) {
                    stopFlight(flight, velocity);
                    return;
                }
                velocity.setX(0);
            }
            if (velocity.getZ() != 0 && blockedAlong(current, 0, 0, velocity.getZ())) {
                if (flight.sticky) {
                    stopFlight(flight, velocity);
                    return;
                }
                velocity.setZ(0);
            }
            if (velocity.getY() != 0 && blockedAlong(current, 0, velocity.getY(), 0)) {
                if (flight.sticky || velocity.getY() < 0) {
                    // 粘性炸药黏住 / 普通炸弹落地：停住等引信
                    stopFlight(flight, velocity);
                    return;
                }
                velocity.setY(0);
            }
            Location next = current.clone().add(velocity);
            if (!next.equals(current)) {
                entity.teleport(next);
            }
            velocity.setY(velocity.getY() - 0.04);
            velocity.multiply(0.99);
        }, 1L, 1L);
        effectTasks.add(holder[0]);
    }

    /**
     * 沿单轴走一小段会不会撞到方块。
     * 只看 Material#isSolid 会把活版门、半砖、楼梯这类只占半格 / 缺角的方块当成整格，
     * 贴着眼睛高度丢出去的炸药就会诡异地黏在半空中；这里按真实碰撞箱判定，并按 0.25 格采样防穿模。
     */
    private boolean blockedAlong(Location from, double dx, double dy, double dz) {
        double length = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        int steps = Math.max(1, (int) Math.ceil(length / 0.25));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            Location point = from.clone().add(dx * t, dy * t, dz * t);
            Block block = point.getBlock();
            if (!block.getType().isSolid()) {
                continue;
            }
            BoundingBox box = block.getBoundingBox();
            if (box.getVolume() <= 0.0 || box.clone().expand(0.01).contains(point.toVector())) {
                return true;
            }
        }
        return false;
    }

    /** 停住原地等引信（粘性炸药黏住 / 普通炸弹落地）。 */
    private void stopFlight(SpecialFlight flight, Vector velocity) {
        velocity.setX(0);
        velocity.setY(0);
        velocity.setZ(0);
        flight.stopped = true;
    }

    private void detonateSpecialProjectile(Entity entity, SpecialItem special) {
        if (entity == null || !entity.isValid()) {
            return;
        }
        Location center = entity.getLocation().clone();
        entity.remove();
        specialProjectiles.remove(entity.getUniqueId());
        if (!running) {
            return;
        }
        switch (special.type) {
            case HIGHEX, STICKY -> explodeAt(center, special);
            case BLACKHOLE -> startPull(center, special);
            case DECOY -> startLock(center.add(0.5, 0.5, 0.5), special);
            default -> {
            }
        }
    }

    /** 烈焰弹：发射一颗直线飞行的火球（无重力、无抛物线），命中方块/实体时由插件引爆。 */
    private void launchFlame(Player player, SpecialItem special) {
        flameByProjectile.entrySet().removeIf(entry -> {
            Entity entity = Bukkit.getEntity(entry.getKey());
            return entity == null || !entity.isValid();
        });
        Vector direction = player.getEyeLocation().getDirection().normalize();
        Location spawn = player.getEyeLocation().add(direction.clone().multiply(1.0));
        SmallFireball fireball = player.getWorld().spawn(spawn, SmallFireball.class);
        fireball.setShooter(player);
        fireball.setIsIncendiary(false);
        fireball.setDirection(direction);
        fireball.setVelocity(direction.clone().multiply(Math.max(0.2, special.velocity)));
        flameByProjectile.put(fireball.getUniqueId(), special);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.0f);
    }

    public boolean ownsFlame(SmallFireball fireball) {
        return fireball != null && flameByProjectile.containsKey(fireball.getUniqueId());
    }

    /** 烈焰弹命中：火球已经移除，这里按配置补一次爆炸和伤害。 */
    public void onFlameHit(SmallFireball fireball, Location hit) {
        SpecialItem special = flameByProjectile.remove(fireball.getUniqueId());
        if (special == null || !running) {
            return;
        }
        Player thrower = fireball.getShooter() instanceof Player player ? player : null;
        detonateFlame(hit, special, thrower);
    }

    /** 插件自定义爆炸（烈焰弹 / 高爆炸弹）：方块由监听器按场地过滤，伤害按配置结算。 */
    private void explodeAt(Location center, SpecialItem special) {
        pendingExplosionCenter = center;
        try {
            // 交给原版爆炸处理抗爆/方块判定，方块白名单由监听器按场地过滤
            center.getWorld().createExplosion(center, (float) Math.max(0.1, special.yield), false, true);
        } finally {
            pendingExplosionCenter = null;
        }
        if (special.damage > 0) {
            damagePlayers(center, special.damage, special.damageRadius);
        }
    }

    /**
     * 烈焰弹引爆：炸在自己脚边时把发射者竖直弹上天（自己不扣血，这一次落地也免摔伤），
     * 其余情况和大厅里的爆炸照常结算。
     */
    private void detonateFlame(Location center, SpecialItem special, Player thrower) {
        pendingExplosionCenter = center;
        try {
            center.getWorld().createExplosion(center, (float) Math.max(0.1, special.yield), false, true);
        } finally {
            pendingExplosionCenter = null;
        }
        boolean selfLaunch = canSelfLaunch(thrower, center, special);
        if (special.damage > 0) {
            damagePlayers(center, special.damage, special.damageRadius,
                    selfLaunch ? thrower.getUniqueId() : null);
        }
        if (selfLaunch) {
            thrower.setFallDistance(0f);
            fallImmune.add(thrower.getUniqueId());
            thrower.setVelocity(new Vector(0, special.selfLaunch, 0));
            thrower.getWorld().playSound(thrower.getLocation(),
                    Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.2f);
        }
    }

    /** 火球是不是就炸在自己身边（往脚下丢）：是的话走竖直弹射。 */
    private boolean canSelfLaunch(Player thrower, Location center, SpecialItem special) {
        if (thrower == null || special.selfLaunch <= 0 || !thrower.isOnline()) {
            return false;
        }
        UUID id = thrower.getUniqueId();
        if (!states.containsKey(id) || eliminated.contains(id)) {
            return false;
        }
        if (!thrower.getWorld().equals(center.getWorld())) {
            return false;
        }
        return thrower.getLocation().distance(center) <= Math.max(0.5, special.selfLaunchRadius);
    }

    /** 判断这次无实体源爆炸是不是插件自己触发的（烈焰弹 / 高爆炸弹，按爆心所在方块比对）。 */
    public boolean isOwnedExplosion(Block block) {
        return block != null && pendingExplosionCenter != null
                && block.getWorld().equals(pendingExplosionCenter.getWorld())
                && block.getX() == pendingExplosionCenter.getBlockX()
                && block.getY() == pendingExplosionCenter.getBlockY()
                && block.getZ() == pendingExplosionCenter.getBlockZ();
    }

    /**
     * 插件自定义爆炸的引爆窗口内（createExplosion 同步执行）。
     * 无实体源的爆炸伤害在 Paper 里没有对应实体，Bukkit 把它归到 CUSTOM 而不是 BLOCK_EXPLOSION，
     * 只能靠这个时间窗口识别并取消，伤害仍由 damagePlayers 按配置结算。
     */
    public boolean isDetonatingSpecial() {
        return pendingExplosionCenter != null;
    }

    /** 搭路蛋：发射原版蛋，蛋飞过的轨迹上铺自己队伍颜色的羊毛。 */
    private void launchBridgeEgg(Player player, SpecialItem special) {
        TNTBattleArena.Team team = teamOf(player);
        if (team == null) {
            return;
        }
        bridgeEggs.entrySet().removeIf(entry -> {
            Entity entity = Bukkit.getEntity(entry.getKey());
            return entity == null || !entity.isValid();
        });
        Vector direction = player.getEyeLocation().getDirection().normalize();
        Location spawn = player.getEyeLocation().add(direction.clone().multiply(1.0));
        Egg egg = player.getWorld().spawn(spawn, Egg.class);
        egg.setShooter(player);
        egg.setVelocity(direction.multiply(Math.max(0.2, special.velocity)));
        // 桥从玩家腿部高度起、沿蛋的抛物线一路向下铺；起点前移半格，避免把方块铺进自己脚下
        Location feet = player.getLocation();
        double bridgeY = Math.floor(feet.getY());
        // 跳起来丢时 feet 在半空，桥会照着半空那一层铺，落地后正好卡在玩家头/身上把人压成趴着。
        // 脚下离地面超过一格就把桥压回地面那一层；站着丢（脚下就是地）保持原样。
        double ground = groundLevelUnder(feet);
        if (!Double.isNaN(ground) && feet.getY() - ground >= bridgeEggMinGap()) {
            bridgeY = ground;
        }
        double hx = direction.getX();
        double hz = direction.getZ();
        double hlen = Math.sqrt(hx * hx + hz * hz);
        double offsetX = hlen > 0.001 ? hx / hlen * 0.6 : 0;
        double offsetZ = hlen > 0.001 ? hz / hlen * 0.6 : 0;
        Location start = new Location(feet.getWorld(), feet.getX() + offsetX, bridgeY, feet.getZ() + offsetZ);
        // 起点比蛋的出生点（眼睛高度）低多少，整条轨迹就整体下移多少
        double yOffset = spawn.getY() - bridgeY;
        // 起始朝向的垂直方向（宽度用），1 tick 后会用蛋的实际走向覆盖
        Vector side = hlen > 0.001
                ? new Vector(-hz / hlen, 0, hx / hlen)
                : new Vector(1, 0, 0);
        BridgeTrail trail = new BridgeTrail(team, Math.max(1, special.maxBlocks), special.width, start, side, yOffset);
        bridgeEggs.put(egg.getUniqueId(), trail);
        startBridgeTrail(egg, trail);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EGG_THROW, 1.0f, 1.0f);
    }

    /**
     * 玩家脚下最近的地面所在的方块层 Y（方块顶面，等于"站在这一层"的 feet.y）。
     * 脚下 8 格内都没有方块（虚空 / 高塔边缘 / 深坑）时返回 {@link Double#NaN}，调用方保持原逻辑。
     */
    private double groundLevelUnder(Location feet) {
        World world = feet.getWorld();
        int startY = feet.getBlockY();
        int minY = Math.max(arena.voidY(), startY - bridgeEggScanDepth());
        for (int y = startY; y >= minY; y--) {
            Block block = world.getBlockAt(feet.getBlockX(), y, feet.getBlockZ());
            if (block.getType().isSolid()) {
                return y + 1.0;
            }
        }
        return Double.NaN;
    }

    /** 搭路蛋：脚下最多向下找几格地面（最小 1）。 */
    private int bridgeEggScanDepth() {
        return Math.max(1, plugin.getConfig().getInt("bridge-egg.ground-scan-depth", 8));
    }

    /** 搭路蛋：脚离地面多少格以上才把桥压回地面（0 = 只要不是站在地上就压）。 */
    private double bridgeEggMinGap() {
        return Math.max(0.0, plugin.getConfig().getDouble("bridge-egg.ground-min-gap", 1.0));
    }

    private void startBridgeTrail(Egg egg, BridgeTrail trail) {
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!running || !egg.isValid() || trail.placed >= trail.maxBlocks) {
                holder[0].cancel();
                effectTasks.remove(holder[0]);
                bridgeEggs.remove(egg.getUniqueId());
                if (egg.isValid()) {
                    egg.remove();
                }
                return;
            }
            Location raw = egg.getLocation();
            if (raw.getWorld() == null) {
                return;
            }
            // 蛋走抛物线；整条轨迹下移到以玩家腿部高度为起点
            Location current = raw.clone().add(0, -trail.yOffset, 0);
            placeTrail(trail, trail.last, current);
            trail.last = current;
        }, 1L, 1L);
        effectTasks.add(holder[0]);
    }

    private void placeTrail(BridgeTrail trail, Location from, Location to) {
        if (from == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        Block end = to.getBlock();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.001) {
            // 更新铺路方向（垂直于前进方向的水平向量），窄缝里抖动的 tick 不会把桥铺歪
            trail.side = new Vector(-dz / horizontal, 0, dx / horizontal);
        }
        int stepX = 0;
        int stepZ = 0;
        if (Math.abs(trail.side.getX()) >= Math.abs(trail.side.getZ())) {
            stepX = trail.side.getX() >= 0 ? 1 : -1;
        } else {
            stepZ = trail.side.getZ() >= 0 ? 1 : -1;
        }
        int half = (trail.width - 1) / 2;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(distance / 0.5));
        for (int i = 0; i < steps && trail.placed < trail.maxBlocks; i++) {
            double ratio = i / (double) steps;
            Block center = from.clone().add(dx * ratio, dy * ratio, dz * ratio).getBlock();
            if (center.equals(end)) {
                continue;
            }
            for (int offset = -half; offset < trail.width - half; offset++) {
                if (trail.placed >= trail.maxBlocks) {
                    break;
                }
                Block block = offset == 0
                        ? center
                        : center.getRelative(stepX * offset, 0, stepZ * offset);
                if (!insideArena(block) || !withinBuildHeight(block) || !block.getType().isAir()) {
                    continue;
                }
                rememberBlock(block);
                block.setType(trail.team.wool(), false);
                trail.placed++;
            }
        }
    }

    public boolean ownsBridgeEgg(Egg egg) {
        return egg != null && bridgeEggs.containsKey(egg.getUniqueId());
    }

    public void stopBridgeEgg(Egg egg) {
        if (egg != null) {
            bridgeEggs.remove(egg.getUniqueId());
        }
    }

    /** 天使之赐：给自己上漂浮效果。 */
    private void useAngel(Player player, SpecialItem special) {
        int seconds = Math.max(1, special.levitationSeconds);
        int amplifier = Math.max(0, special.levitationAmplifier);
        player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, seconds * 20, amplifier, true, false, true));
        player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1, 0),
                30, 0.5, 1.0, 0.5, 0.05);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.2f);
    }

    private void startPull(Location center, SpecialItem special) {
        int totalTicks = Math.max(1, special.pullSeconds) * 20;
        int[] elapsed = {0};
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!running || elapsed[0]++ >= totalTicks) {
                holder[0].cancel();
                effectTasks.remove(holder[0]);
                return;
            }
            center.getWorld().spawnParticle(Particle.PORTAL, center, 30, 1.5, 1.5, 1.5, 0.1);
            for (UUID id : states.keySet()) {
                if (eliminated.contains(id)) {
                    continue;
                }
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline() || !player.getWorld().equals(center.getWorld())) {
                    continue;
                }
                Vector pull = center.toVector().subtract(player.getLocation().toVector());
                if (pull.lengthSquared() > 0.01 && pull.length() <= special.pullRadius) {
                    player.setVelocity(player.getVelocity().add(pull.normalize().multiply(special.pullStrength)));
                }
            }
        }, 1L, 1L);
        effectTasks.add(holder[0]);
    }

    private void startLock(Location center, SpecialItem special) {
        int totalTicks = Math.max(1, special.lockSeconds) * 20;
        int[] elapsed = {0};
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!running || elapsed[0]++ >= totalTicks) {
                holder[0].cancel();
                effectTasks.remove(holder[0]);
                return;
            }
            center.getWorld().spawnParticle(Particle.END_ROD, center, 6, 0.4, 0.4, 0.4, 0.01);
            for (UUID id : states.keySet()) {
                if (eliminated.contains(id)) {
                    continue;
                }
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline() || !player.getWorld().equals(center.getWorld())) {
                    continue;
                }
                if (player.getLocation().distance(center) > special.lockRadius) {
                    continue;
                }
                Vector direction = center.toVector().subtract(player.getEyeLocation().toVector());
                if (direction.lengthSquared() < 0.01) {
                    continue;
                }
                float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
                float pitch = (float) Math.toDegrees(-Math.asin(
                        direction.getY() / direction.length()));
                player.setRotation(yaw, pitch);
            }
        }, 1L, 1L);
        effectTasks.add(holder[0]);
    }

    private void cancelEffects() {
        for (BukkitTask task : new ArrayList<>(effectTasks)) {
            task.cancel();
        }
        effectTasks.clear();
    }

    private void removeFlames() {
        for (UUID id : new ArrayList<>(flameByProjectile.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        flameByProjectile.clear();
    }

    private void removeSpecialProjectiles() {
        for (UUID id : new ArrayList<>(specialProjectiles.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        specialProjectiles.clear();
    }

    private void removeBridgeEggs() {
        for (UUID id : new ArrayList<>(bridgeEggs.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        bridgeEggs.clear();
    }

    private static final class SpecialFlight {
        private final Entity entity;
        private final SpecialItem special;
        private final Vector velocity;
        private final boolean sticky;
        private int ticksLeft;
        private boolean stopped;

        private SpecialFlight(Entity entity, SpecialItem special, Vector velocity, int ticksLeft) {
            this.entity = entity;
            this.special = special;
            this.velocity = velocity;
            this.sticky = special.type == SpecialItem.Type.STICKY;
            this.ticksLeft = ticksLeft;
        }
    }

    private static final class BridgeTrail {
        private final TNTBattleArena.Team team;
        private final int maxBlocks;
        private final int width;
        private final double yOffset;
        private int placed;
        private Location last;
        private Vector side;

        private BridgeTrail(TNTBattleArena.Team team, int maxBlocks, int width, Location start, Vector side,
                            double yOffset) {
            this.team = team;
            this.maxBlocks = maxBlocks;
            this.width = width;
            this.yOffset = yOffset;
            this.last = start;
            this.side = side;
        }
    }

    // ---------- 淘汰 / 胜负 ----------

    public void eliminate(Player player, String reason) {
        UUID id = player.getUniqueId();
        if (!states.containsKey(id) || eliminated.contains(id)) {
            return;
        }
        eliminated.add(id);
        tntReadyAt.remove(id);
        steakReadyAt.remove(id);
        specialReadyAt.remove(id);
        fallImmune.remove(id);
        clearTeamGlow(player);
        refreshTitle(player);
        // 旁观期间也保留无粒子夜视
        giveNightVision(player);
        player.setExp(0f);
        player.setLevel(0);
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.setGameMode(GameMode.SPECTATOR);
        player.setAllowFlight(true);
        player.setFlying(true);
        Location spectator = arena.spectatorLocation();
        if (spectator != null) {
            player.teleport(spectator);
        }
        broadcastGame("&7" + player.getName() + " " + reason + "，转入旁观。");
        // 死亡状态下立刻切旁观，客户端会卡在死亡界面无法重生；下一 tick 自动重生（仍是旁观）
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.isDead()) {
                player.spigot().respawn();
            }
        });
        checkWin();
        maybeStartMusic();
    }

    /** 结算/关服时若玩家还停在死亡界面，先强制重生，否则回填血量后客户端会一直卡住。 */
    private void respawnIfDead(Player player) {
        if (player.isOnline() && player.isDead()) {
            player.spigot().respawn();
        }
    }

    public boolean isRestoring() {
        return restoring;
    }

    private void checkWin() {
        if (!running || ending) {
            return;
        }
        int red = aliveCount(TNTBattleArena.Team.RED);
        int blue = aliveCount(TNTBattleArena.Team.BLUE);
        if (red == 0 && blue == 0) {
            endGame(null);
        } else if (red == 0) {
            endGame(TNTBattleArena.Team.BLUE);
        } else if (blue == 0) {
            endGame(TNTBattleArena.Team.RED);
        }
    }

    private int aliveCount(TNTBattleArena.Team team) {
        int count = 0;
        for (Map.Entry<UUID, TNTBattleArena.Team> entry : teams.entrySet()) {
            if (entry.getValue() == team && !eliminated.contains(entry.getKey())) {
                count++;
            }
        }
        return count;
    }

    /** 背景音乐控制器。 */
    public TNTBattleMusic music() {
        return music;
    }

    /** 本局所有玩家（含已淘汰的旁观者），背景音乐与局内播报共用这个范围。 */
    public List<UUID> gamePlayerIds() {
        return new ArrayList<>(states.keySet());
    }

    /**
     * 任一方存活人数 <= music.trigger-team-alive（默认 1）时开始循环背景音乐。
     * 已在播放就不重启；对局结束/平局由 stopPlayback 停。
     */
    private void maybeStartMusic() {
        if (!running || ending) {
            return;
        }
        if (!plugin.getConfig().getBoolean("music.enabled", true) || music.isPlaying()) {
            return;
        }
        int trigger = music.triggerAlive();
        int red = aliveCount(TNTBattleArena.Team.RED);
        int blue = aliveCount(TNTBattleArena.Team.BLUE);
        if ((red > 0 && red <= trigger) || (blue > 0 && blue <= trigger)) {
            music.startLoop();
        }
    }

    public boolean isAlive(Player player) {
        UUID id = player.getUniqueId();
        return states.containsKey(id) && !eliminated.contains(id);
    }

    public boolean isPlaying(Player player) {
        return states.containsKey(player.getUniqueId());
    }

    public boolean isInGame(UUID id) {
        return states.containsKey(id);
    }

    public TNTBattleArena.Team teamOf(Player player) {
        return teams.get(player.getUniqueId());
    }

    public boolean isEliminated(Player player) {
        return eliminated.contains(player.getUniqueId());
    }

    // ---------- 结算 ----------

    private void updateLiveBoardThrottled() {
        if (!running || ending) {
            return;
        }
        if (++boardTick % 10 != 0) {
            return;
        }
        int red = 0;
        int blue = 0;
        for (UUID id : teams.keySet()) {
            if (eliminated.contains(id)) {
                continue;
            }
            if (teams.get(id) == TNTBattleArena.Team.RED) {
                red++;
            } else {
                blue++;
            }
        }
        List<LiveSidebar.Line> lines = new ArrayList<>();
        lines.add(new LiveSidebar.Line("§c红队存活", red));
        lines.add(new LiveSidebar.Line("§9蓝队存活", blue));
        liveSidebar.update(sidebarKey(), "&c&lTNT 对战 · 存活", new ArrayList<>(teams.keySet()), lines);
        // LiveSidebar 会给玩家换计分板；换完再挂一次队伍，保证发光颜色还在
        for (UUID id : teams.keySet()) {
            if (eliminated.contains(id)) {
                continue;
            }
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                applyTeamGlow(p, teams.get(id));
            }
        }
    }

    public void endGame(TNTBattleArena.Team winner) {
        if (ending) {
            return;
        }
        ending = true;
        running = false;
        music.stopPlayback();
        liveSidebar.hide(sidebarKey());
        cancelTasks();
        cancelEffects();
        removeFlames();
        removeSpecialProjectiles();
        removeBridgeEggs();
        if (countdown != null) {
            countdown.cancel();
        }
        if (winner == null) {
            broadcastGame("&e双方都被淘汰，本局平局。");
        } else {
            broadcastGame("&c&l===== TNT 对战 结束 =====");
            broadcastGame((winner == TNTBattleArena.Team.RED ? "&c红队" : "&9蓝队")
                    + " &f获胜！");
            for (Map.Entry<UUID, TNTBattleArena.Team> entry : teams.entrySet()) {
                if (entry.getValue() == winner && !eliminated.contains(entry.getKey())) {
                    recordFirst(entry.getKey());
                }
            }
        }
        int delay = Math.max(1, plugin.getConfig().getInt("end-delay-ticks", 60));
        Bukkit.getScheduler().runTaskLater(plugin, this::finishEnd, delay);
    }

    private void finishEnd() {
        restoring = true;
        music.stopPlayback();
        removeTnt();
        restoreChangedBlocks();
        for (UUID id : new ArrayList<>(states.keySet())) {
            Player player = Bukkit.getPlayer(id);
            PlayerState state = states.get(id);
            if (player != null && player.isOnline() && state != null) {
                clearTeamGlow(player);
                respawnIfDead(player);
                state.apply(player);
                refreshTitle(player);
                HubAPI.teleportToLobby(player);
            }
            HubAPI.clearGame(id);
            core.unbind(id);
        }
        states.clear();
        names.clear();
        teams.clear();
        eliminated.clear();
        fallImmune.clear();
        tntReadyAt.clear();
        steakReadyAt.clear();
        specialReadyAt.clear();
        spawnedTnt.clear();
        specialProjectiles.clear();
        ending = false;
        running = false;
        restoring = false;
        reportArena();
        countdown.update();
    }

    public boolean inUse() {
        return running || ending || !states.isEmpty();
    }

    /** 原来承载排行榜的场被删除后，把排行榜交给新的主场地。 */
    public void becomePrimary() {
        primary = true;
        loadLeaderboard();
        startLeaderboardTask();
    }

    public void saveArena() {
        if (arena.regionConfigured()
                || !arena.spawns(TNTBattleArena.Team.RED).isEmpty()
                || !arena.spawns(TNTBattleArena.Team.BLUE).isEmpty()) {
            arena.save();
        }
    }

    /** 删除场地时清理本场资源，但不写回 arena 配置（否则会把刚删掉的场又写回来）。 */
    public void dispose() {
        liveSidebar.hideAll();
        music.shutdown();
        music.clearEnabled();
        if (countdown != null) {
            countdown.cancel();
        }
        if (startCountdown != null) {
            startCountdown.cancelAll();
        }
        cancelTasks();
        cancelEffects();
        removeFlames();
        removeSpecialProjectiles();
        removeBridgeEggs();
        removeTnt();
        restoreChangedBlocks();
        if (leaderboardTask != null) {
            leaderboardTask.cancel();
            leaderboardTask = null;
        }
        removeTaggedDisplays();
        if (leaderboardChunk != null) {
            leaderboardChunk.removePluginChunkTicket(plugin);
            leaderboardChunk = null;
        }
        for (UUID id : new ArrayList<>(states.keySet())) {
            core.unbind(id);
        }
        states.clear();
        teams.clear();
        eliminated.clear();
        running = false;
        ending = false;
    }

    private String sidebarKey() {
        return "tntbattle:" + arena.name();
    }

    public void shutdown() {
        liveSidebar.hideAll();
        ending = true;
        restoring = true;
        music.shutdown();
        music.clearEnabled();
        if (countdown != null) {
            countdown.cancel();
        }
        cancelTasks();
        cancelEffects();
        removeFlames();
        removeSpecialProjectiles();
        removeBridgeEggs();
        removeTnt();
        restoreChangedBlocks();
        for (UUID id : new ArrayList<>(states.keySet())) {
            Player player = Bukkit.getPlayer(id);
            PlayerState state = states.get(id);
            if (player != null && player.isOnline() && state != null) {
                clearTeamGlow(player);
                respawnIfDead(player);
                state.apply(player);
                refreshTitle(player);
            }
            HubAPI.clearGame(id);
            core.unbind(id);
        }
        states.clear();
        teams.clear();
        eliminated.clear();
        fallImmune.clear();
        tntReadyAt.clear();
        steakReadyAt.clear();
        if (leaderboardTask != null) {
            leaderboardTask.cancel();
            leaderboardTask = null;
        }
        // 实体留着随世界一起保存，下次启动 loadLeaderboard() 会先删旧再重建
        leaderboardDisplay = null;
        running = false;
        ending = false;
        saveArena();
    }

    private void removeTnt() {
        for (UUID id : new ArrayList<>(spawnedTnt)) {
            org.bukkit.entity.Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        spawnedTnt.clear();
    }

    // ---------- 状态 / 工具 ----------

    public void reportArena() {
        if (!arena.ready()) {
            return;
        }
        String extra = running ? "红 " + aliveCount(TNTBattleArena.Team.RED)
                + " vs 蓝 " + aliveCount(TNTBattleArena.Team.BLUE) : null;
        HubAPI.setArena(GAME, arena.name(), (running || ending) ? GameState.PLAYING : GameState.WAITING,
                states.size(), extra);
        HubAPI.setArenaSpectator(GAME, arena.name(), arena().spectatorLocation());
    }

    private void broadcastWaiting(String message) {
        for (UUID id : states.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                Chat.send(player, message);
            }
        }
    }

    /** 只发给本局玩家（含已淘汰的旁观者），大厅/其他游戏看不到局内播报。 */
    private void broadcastGame(String message) {
        for (UUID id : new ArrayList<>(states.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                Chat.sendTitled(player, message);
            }
        }
    }

    private void recordFirst(UUID id) {
        if (stats == null) {
            return;
        }
        stats.set("firsts." + id, stats.getInt("firsts." + id, 0) + 1);
        saveSharedStats(plugin);
        core.refreshLeaderboard();
    }

    public static void loadSharedStats(TNTBattlePlugin plugin) {
        stats = PlayerStats.load("tntbattle", new File(plugin.getDataFolder(), "data.yml"));
    }

    public static void saveSharedStats(TNTBattlePlugin plugin) {
        if (stats == null) {
            return;
        }
        try {
            PlayerStats.save("tntbattle", stats);
        } catch (IOException exception) {
            plugin.getLogger().warning("保存玩家记录失败: " + exception.getMessage());
        }
    }

    public int firsts(UUID id) {
        return stats == null ? 0 : stats.getInt("firsts." + id, 0);
    }

    /** 胜场排行榜条目。 */
    public record WinEntry(UUID id, String name, int wins) {
    }

    /** 按胜场降序（同胜场按名字）返回前 limit 名；limit <= 0 返回全部。 */
    public List<WinEntry> leaderboard(int limit) {
        if (stats == null) {
            return List.of();
        }
        ConfigurationSection firsts = stats.getConfigurationSection("firsts");
        if (firsts == null) {
            return List.of();
        }
        List<WinEntry> entries = new ArrayList<>();
        for (String key : firsts.getKeys(false)) {
            int wins = firsts.getInt(key, 0);
            if (wins <= 0) {
                continue;
            }
            try {
                UUID id = UUID.fromString(key);
                entries.add(new WinEntry(id, lookupName(id), wins));
            } catch (IllegalArgumentException ignored) {
                // 非 UUID 的脏数据跳过
            }
        }
        entries.sort(Comparator.comparingInt(WinEntry::wins).reversed()
                .thenComparing(WinEntry::name, String.CASE_INSENSITIVE_ORDER));
        if (limit > 0 && entries.size() > limit) {
            return new ArrayList<>(entries.subList(0, limit));
        }
        return entries;
    }

    private String lookupName(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
        String name = offline.getName();
        return name == null ? id.toString().substring(0, 8) : name;
    }

    public TNTBattleArena arena() {
        return arena;
    }

    public TNTBattlePlugin plugin() {
        return plugin;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isEnding() {
        return ending;
    }

    public int statesCount() {
        return states.size();
    }

    public int aliveCountTotal(TNTBattleArena.Team team) {
        return aliveCount(team);
    }

    public int teamSize(TNTBattleArena.Team team) {
        int count = 0;
        for (TNTBattleArena.Team value : teams.values()) {
            if (value == team) {
                count++;
            }
        }
        return count;
    }

    public String nameOf(UUID id) {
        return names.getOrDefault(id, "?");
    }

    public Set<UUID> players() {
        return Set.copyOf(states.keySet());
    }
}
