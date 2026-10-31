package com.arcade;

import com.hub.api.ArenaStatus;
import com.hub.api.ArcadeBridge;
import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.util.Chat;
import com.hub.util.StartCountdown;
import com.hub.util.WaitCountdown;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** 街机派对核心：随机串烧小游戏、星星积分、过渡阶段、颁奖台与烟花。 */
public final class ArcadePartyManager implements ArcadeBridge.Listener {

    public static final String GAME = "街机派对";
    public static final String ARENA = "主场地";

    public enum State {
        WAITING, TRANSITION, ROUND, PODIUM
    }

    private static final Color[] PALETTE = {
            Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA,
            Color.BLUE, Color.PURPLE, Color.FUCHSIA, Color.WHITE
    };

    private final ArcadePartyPlugin plugin;
    private final NamespacedKey menuKey;
    private final WaitCountdown countdown;
    private final Random random = new Random();

    private State state = State.WAITING;
    private final Set<UUID> waiting = new LinkedHashSet<>();
    private final Set<UUID> playing = new LinkedHashSet<>();
    private final Map<UUID, Integer> stars = new HashMap<>();

    private final List<String> selected = new ArrayList<>();
    private int roundIndex;
    private int transitionLeft;
    private String currentGameKey = "";
    /** 过渡阶段选好的场地名（arena-aware 游戏），开局时优先复用。 */
    private String currentArena;
    private final Set<UUID> roundParticipants = new LinkedHashSet<>();
    private final Set<UUID> transitionParticipants = new LinkedHashSet<>();
    private final Set<UUID> transitionSpectators = new LinkedHashSet<>();
    private boolean roundResolved;

    private Location lobby;
    private Location audience;
    private Location firstSpot;
    private Location secondSpot;
    private Location thirdSpot;

    private BukkitTask transitionTask;
    private BukkitTask timeoutTask;
    private BukkitTask podiumTask;
    private BukkitTask endTask;
    private BukkitTask guardTask;
    private int guardTicks;
    private int guardIdleTicks;
    private final List<BukkitTask> fireworkTasks = new ArrayList<>();

    private Scoreboard sidebar;
    private Objective sidebarObjective;
    private final Map<UUID, String> sidebarEntries = new HashMap<>();
    /** 颁奖时逐个揭晓的名次行，全部揭晓后一起发到聊天栏。 */
    private final List<String> podiumLines = new ArrayList<>();
    /** 颁奖台上还没被揭晓的玩家：在揭晓前锁住移动。 */
    private final Set<UUID> podiumFrozen = new LinkedHashSet<>();

    public ArcadePartyManager(ArcadePartyPlugin plugin) {
        this.plugin = plugin;
        this.menuKey = new NamespacedKey(plugin, "menu_item");
        this.countdown = new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return new ArrayList<>(waiting);
            }

            @Override
            public int minPlayers() {
                return ArcadePartyManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return ArcadePartyManager.this.maxPlayers();
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
                startParty(false);
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
        return Math.max(minPlayers(), cfgInt("max-players", 16));
    }

    public int gamesPerParty() {
        return Math.max(1, Math.min(16, cfgInt("games-per-party", 3)));
    }

    public int transitionSeconds() {
        return Math.max(1, Math.min(300, cfgInt("transition-seconds", 15)));
    }

    private int roundTimeoutSeconds() {
        return Math.max(30, Math.min(7200, cfgInt("round-timeout-seconds", 600)));
    }

    private int starFirst() {
        return Math.max(0, Math.min(10, cfgInt("stars.first", 3)));
    }

    private int starSecond() {
        return Math.max(0, Math.min(10, cfgInt("stars.second", 2)));
    }

    private int starThird() {
        return Math.max(0, Math.min(10, cfgInt("stars.third", 1)));
    }

    private int starSpectatorBonus() {
        return Math.max(0, Math.min(10, cfgInt("stars.spectator-bonus", 1)));
    }

    // ---------- 生命周期 ----------

    public void load() {
        ensureDefaults();
        countdown.cancel();
        cancelTasks();
        restoreAll();
        lobby = parse(plugin.getConfig().getString("lobby", ""));
        audience = parse(plugin.getConfig().getString("podium.audience", ""));
        firstSpot = parse(plugin.getConfig().getString("podium.first", ""));
        secondSpot = parse(plugin.getConfig().getString("podium.second", ""));
        thirdSpot = parse(plugin.getConfig().getString("podium.third", ""));
        state = State.WAITING;
        waiting.clear();
        playing.clear();
        stars.clear();
        selected.clear();
        ArcadeBridge.register(this);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HubAPI.registerGame(GAME, GAME, "FIREWORK_ROCKET", "e");
            reportArena();
        }, 40L);
        plugin.getLogger().info("等待区" + (lobby != null ? "已配置" : "未配置（/arcade setlobby）") + "。");
    }

    public void reload() {
        if (state != State.WAITING) {
            return;
        }
        load();
    }

    public void shutdown() {
        cancelTasks();
        restoreAll();
        StartCountdown.setGloballySuppressed(false);
        WaitCountdown.setGloballySuppressed(false);
        ArcadeBridge.unregister(this);
        saveSettings();
    }

    private void ensureDefaults() {
        FileConfiguration cfg = plugin.getConfig();
        if (cfg.getKeys(false).isEmpty()) {
            return;
        }
        boolean changed = false;
        changed |= setDefault("min-players", 2);
        changed |= setDefault("max-players", 16);
        changed |= setDefault("games-per-party", 3);
        changed |= setDefault("transition-seconds", 15);
        changed |= setDefault("transition.freeze", true);
        changed |= setDefault("round-timeout-seconds", 600);
        changed |= setDefault("stars.first", 3);
        changed |= setDefault("stars.second", 2);
        changed |= setDefault("stars.third", 1);
        changed |= setDefault("stars.spectator-bonus", 1);
        changed |= setDefault("lobby", "");
        changed |= setDefault("podium.audience", "");
        changed |= setDefault("podium.first", "");
        changed |= setDefault("podium.second", "");
        changed |= setDefault("podium.third", "");
        changed |= setDefault("podium.end-delay-seconds", 10);
        changed |= setDefault("fireworks.enabled", true);
        changed |= setDefault("fireworks.count", 3);
        changed |= setDefault("fireworks.interval-ticks", 10);
        changed |= setDefault("fireworks.flight-ticks", 30);
        changed |= setDefault("fireworks.spots", List.of());
        changed |= setDefault("fireworks.types", List.of("BALL", "BALL_LARGE", "STAR", "CREEPER", "BURST"));
        changed |= setDefault("scoreboard.enabled", true);
        changed |= setDefault("scoreboard.title", "&e&l街机派对 · 星星");
        changed |= setDefault("scoreboard.line", "&e{name}");
        changed |= setDefault("games.digtobottom.display", "一掘到底");
        changed |= setDefault("games.digtobottom.enabled", true);
        changed |= setDefault("games.digtobottom.arena-aware", true);
        changed |= setDefault("games.digtobottom.max-players", 8);
        changed |= setDefault("games.digtobottom.join-command", "dig join {arena}");
        changed |= setDefault("games.digtobottom.start-command", "dig start {arena}");
        changed |= setDefault("games.anvilbattle.arena-aware", false);
        changed |= setDefault("games.animalslaughter.arena-aware", false);
        changed |= setDefault("games.snakeparty.arena-aware", false);
        changed |= setDefault("games.anvilbattle.display", "铁砧掘战");
        changed |= setDefault("games.anvilbattle.enabled", true);
        changed |= setDefault("games.anvilbattle.max-players", 12);
        changed |= setDefault("games.anvilbattle.join-command", "anvil join");
        changed |= setDefault("games.anvilbattle.start-command", "anvil start");
        changed |= setDefault("games.animalslaughter.display", "动物屠宰");
        changed |= setDefault("games.animalslaughter.enabled", true);
        changed |= setDefault("games.animalslaughter.max-players", 12);
        changed |= setDefault("games.animalslaughter.join-command", "animal join");
        changed |= setDefault("games.animalslaughter.start-command", "animal start");
        changed |= setDefault("games.snakeparty.display", "蛇行派对");
        changed |= setDefault("games.snakeparty.enabled", true);
        changed |= setDefault("games.snakeparty.max-players", 16);
        changed |= setDefault("games.snakeparty.join-command", "snake join");
        changed |= setDefault("games.snakeparty.start-command", "snake start");
        changed |= setDefault("games.hotpotato.display", "烫手山芋");
        changed |= setDefault("games.hotpotato.enabled", true);
        changed |= setDefault("games.hotpotato.arena-aware", true);
        changed |= setDefault("games.hotpotato.max-players", 12);
        changed |= setDefault("games.hotpotato.join-command", "hotpotato join {arena}");
        changed |= setDefault("games.hotpotato.start-command", "hotpotato start {arena}");
        changed |= setDefault("games.tntrun.display", "雪塔大作战");
        changed |= setDefault("games.tntrun.enabled", true);
        changed |= setDefault("games.tntrun.arena-aware", true);
        changed |= setDefault("games.tntrun.max-players", 12);
        changed |= setDefault("games.tntrun.join-command", "tntrun join {arena}");
        changed |= setDefault("games.tntrun.start-command", "tntrun start {arena}");
        changed |= setDefault("games.colorparty.display", "色盲派对");
        changed |= setDefault("games.colorparty.enabled", true);
        changed |= setDefault("games.colorparty.arena-aware", true);
        changed |= setDefault("games.colorparty.max-players", 12);
        changed |= setDefault("games.colorparty.join-command", "colorparty join {arena}");
        changed |= setDefault("games.colorparty.start-command", "colorparty start {arena}");
        changed |= setDefault("games.grouprace.display", "群友竞速");
        changed |= setDefault("games.grouprace.enabled", true);
        changed |= setDefault("games.grouprace.arena-aware", false);
        changed |= setDefault("games.grouprace.max-players", 16);
        changed |= setDefault("games.grouprace.join-command", "race join");
        changed |= setDefault("games.grouprace.start-command", "race start");
        changed |= setDefault("games.knockback.display", "击退大战");
        changed |= setDefault("games.knockback.enabled", true);
        changed |= setDefault("games.knockback.arena-aware", false);
        changed |= setDefault("games.knockback.max-players", 16);
        changed |= setDefault("games.knockback.join-command", "knockback join");
        changed |= setDefault("games.knockback.start-command", "knockback start");
        changed |= setDefault("games.landclaim.display", "占地风云1");
        changed |= setDefault("games.landclaim.enabled", true);
        changed |= setDefault("games.landclaim.arena-aware", false);
        changed |= setDefault("games.landclaim.max-players", 16);
        changed |= setDefault("games.landclaim.join-command", "land join");
        changed |= setDefault("games.landclaim.start-command", "land start");
        changed |= setDefault("games.jumpfill.display", "花落水家");
        changed |= setDefault("games.jumpfill.enabled", true);
        changed |= setDefault("games.jumpfill.arena-aware", false);
        changed |= setDefault("games.jumpfill.max-players", 16);
        changed |= setDefault("games.jumpfill.join-command", "jump join");
        changed |= setDefault("games.jumpfill.start-command", "jump start");
        changed |= setDefault("games.memoryflip.display", "记忆翻牌");
        changed |= setDefault("games.memoryflip.enabled", true);
        changed |= setDefault("games.memoryflip.arena-aware", false);
        changed |= setDefault("games.memoryflip.max-players", 16);
        changed |= setDefault("games.memoryflip.join-command", "memory join");
        changed |= setDefault("games.memoryflip.start-command", "memory start");
        changed |= setDefault("games.hillking.display", "高台之王");
        changed |= setDefault("games.hillking.enabled", true);
        changed |= setDefault("games.hillking.arena-aware", false);
        changed |= setDefault("games.hillking.max-players", 16);
        changed |= setDefault("games.hillking.join-command", "hill join");
        changed |= setDefault("games.hillking.start-command", "hill start");
        changed |= setDefault("games.oneshot.display", "One Shot");
        changed |= setDefault("games.oneshot.enabled", true);
        changed |= setDefault("games.oneshot.arena-aware", false);
        changed |= setDefault("games.oneshot.max-players", 16);
        changed |= setDefault("games.oneshot.join-command", "oneshot join");
        changed |= setDefault("games.oneshot.start-command", "oneshot start");
        // 1.0.3：多场地游戏改用带 {arena} 的命令；旧配置一次性迁移
        if (plugin.getConfig().getInt("config-version", 0) < 1) {
            plugin.getConfig().set("games.digtobottom.arena-aware", true);
            plugin.getConfig().set("games.digtobottom.join-command", "dig join {arena}");
            plugin.getConfig().set("games.digtobottom.start-command", "dig start {arena}");
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

    public void saveSettings() {
        plugin.saveConfig();
    }

    public State state() {
        return state;
    }

    public ArcadePartyPlugin plugin() {
        return plugin;
    }

    public boolean isWaiting(Player player) {
        return waiting.contains(player.getUniqueId());
    }

    public boolean isPlaying(Player player) {
        return playing.contains(player.getUniqueId());
    }

    public boolean isInParty(Player player) {
        return isWaiting(player) || isPlaying(player);
    }

    public boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission("arcade.admin");
    }

    public int waitingCount() {
        return waiting.size();
    }

    public int playingCount() {
        return playing.size();
    }

    public int starsOf(UUID id) {
        return stars.getOrDefault(id, 0);
    }

    public Location getLobby() {
        return lobby == null ? null : lobby.clone();
    }

    public List<String> enabledGameKeys() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("games");
        List<String> out = new ArrayList<>();
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            if (!sec.getBoolean(key + ".enabled", true)) {
                continue;
            }
            String join = sec.getString(key + ".join-command", "");
            String start = sec.getString(key + ".start-command", "");
            if (join == null || join.isBlank() || start == null || start.isBlank()) {
                continue;
            }
            if (arenaAware(key)) {
                if (!join.contains("{arena}") || !start.contains("{arena}")) {
                    plugin.getLogger().warning("小游戏 " + key + " 标了 arena-aware 但命令里没有 {arena}，已跳过。");
                    continue;
                }
                if (freeArenas(key).isEmpty()) {
                    continue;
                }
            }
            out.add(key);
        }
        return out;
    }

    /** 多场地游戏：开局前要挑一个空场，并把 {arena} 替换成场名。 */
    public boolean arenaAware(String key) {
        return plugin.getConfig().getBoolean("games." + key + ".arena-aware", false);
    }

    /** 该游戏当前的空闲场地（等待中、没有对局在跑）；没有就返回空列表。 */
    public List<String> freeArenas(String key) {
        List<String> out = new ArrayList<>();
        for (ArenaStatus status : HubAPI.arenas(displayOf(key))) {
            // 只看状态：刚结束的对局人数可能还没清零，但已经可以再开一局了
            if (status.state() == GameState.WAITING) {
                out.add(status.name());
            }
        }
        return out;
    }

    public boolean transitionFreeze() {
        return plugin.getConfig().getBoolean("transition.freeze", true);
    }

    /** 选一个空闲场地（等待中 + 0 人），没有返回 null。 */
    /** 过渡阶段站的位置：优先下一个游戏的（空）场地观战点，其次派对等待区。 */
    public String displayOf(String key) {
        return plugin.getConfig().getString("games." + key + ".display", key);
    }

    public int gameMaxPlayers(String key) {
        return Math.max(1, plugin.getConfig().getInt("games." + key + ".max-players", 16));
    }

    public boolean gameEnabled(String key) {
        return plugin.getConfig().getBoolean("games." + key + ".enabled", true);
    }

    public void toggleGame(String key) {
        plugin.getConfig().set("games." + key + ".enabled", !gameEnabled(key));
        plugin.saveConfig();
    }

    public void setGamesPerParty(int value) {
        plugin.getConfig().set("games-per-party", Math.max(1, Math.min(16, value)));
        plugin.saveConfig();
    }

    public void setMaxPlayers(int value) {
        plugin.getConfig().set("max-players", Math.max(minPlayers(), Math.min(64, value)));
        plugin.saveConfig();
    }

    public void setTransitionSeconds(int value) {
        plugin.getConfig().set("transition-seconds", Math.max(1, Math.min(300, value)));
        plugin.saveConfig();
    }

    public void setLobby(Location loc) {
        lobby = loc;
        plugin.getConfig().set("lobby", encode(loc));
        plugin.saveConfig();
    }

    public void setAudience(Location loc) {
        audience = loc;
        plugin.getConfig().set("podium.audience", encode(loc));
        plugin.saveConfig();
    }

    public void setPodium(String place, Location loc) {
        plugin.getConfig().set("podium." + place, encode(loc));
        plugin.saveConfig();
        if ("first".equals(place)) {
            firstSpot = loc;
        } else if ("second".equals(place)) {
            secondSpot = loc;
        } else if ("third".equals(place)) {
            thirdSpot = loc;
        }
    }

    // ---------- 加入 / 离开 ----------

    public void join(Player player) {
        UUID id = player.getUniqueId();
        if (state != State.WAITING) {
            Chat.send(player, "&c派对正在进行中，请等这一局结束后再加入。");
            return;
        }
        if (waiting.contains(id)) {
            Chat.send(player, "&e你已经在派对等待队列中。");
            return;
        }
        if (waiting.size() >= maxPlayers()) {
            Chat.send(player, "&c派对等待队列已满（" + maxPlayers() + " 人）。");
            return;
        }
        if (lobby == null) {
            Chat.send(player, "&c派对还没配置等待区（/arcade setlobby）。");
            return;
        }
        waiting.add(id);
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        resetPlayer(player);
        player.teleport(lobby);
        giveWaitItems(player);
        HubAPI.setGame(id, GAME);
        HubAPI.announceJoin(plugin.getName(), player.getName());
        Chat.send(player, "&a已加入" + GAME + " &7(" + waiting.size() + "/" + maxPlayers() + ")");
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
                Chat.send(player, "&e你不在此派对中。");
            }
            return;
        }
        stars.remove(id);
        clearGameState(player);
        restoreSidebar(player);
        HubAPI.clearGame(id);
        if (!silent) {
            HubAPI.teleportToLobby(player);
            Chat.send(player, "&e你已离开" + GAME + "。");
        }
        if (state == State.WAITING) {
            updateCountdown();
        } else if (playing.isEmpty()) {
            abortParty("&7所有玩家都离开了，派对结束。");
            return;
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
        stars.remove(id);
        HubAPI.clearGame(id);
        if (state == State.WAITING) {
            updateCountdown();
        } else if (playing.isEmpty()) {
            abortParty("&7所有玩家都离开了，派对结束。");
            return;
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

    // ---------- 派对流程 ----------

    public void startParty(boolean force) {
        if (state != State.WAITING) {
            return;
        }
        if (lobby == null) {
            broadcastWaiting("&c派对还没配置等待区（/arcade setlobby）。");
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
        List<String> pool = enabledGameKeys();
        if (pool.isEmpty()) {
            broadcastWaiting("&c没有启用任何小游戏（config.yml 的 games 段或 /arcade menu）。");
            return;
        }
        Collections.shuffle(pool, random);
        int want = Math.min(gamesPerParty(), pool.size());
        selected.clear();
        selected.addAll(pool.subList(0, want));

        countdown.cancel();
        cancelTasks();
        waiting.clear();
        playing.clear();
        playing.addAll(ready);
        stars.clear();
        for (UUID id : playing) {
            stars.put(id, 0);
        }
        roundIndex = 0;
        state = State.TRANSITION;
        StartCountdown.setGloballySuppressed(true);
        WaitCountdown.setGloballySuppressed(true);

        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                clearGameState(p);
                p.setGameMode(GameMode.SURVIVAL);
                p.setHealth(20);
                p.setFoodLevel(20);
                HubAPI.setPlaying(id, GAME);
            }
        }
        broadcast("&6&l========== " + GAME + " 开始 ==========");
        broadcast("&7本局共 &e" + selected.size() + " &7个小游戏：" + displayList());
        beginTransition();
    }

    private void beginTransition() {
        if (state == State.PODIUM) {
            return;
        }
        // 多场地游戏必须有空场，否则从本局清单里去掉、换下一个，避免 join/start 带着 {arena} 空跑
        while (roundIndex < selected.size()) {
            String key = selected.get(roundIndex);
            if (!arenaAware(key) || !freeArenas(key).isEmpty()) {
                break;
            }
            String skipped = displayOf(key);
            selected.remove(roundIndex);
            broadcast("&7小游戏 &f" + skipped + " &7当前没有空场地，已跳过。");
        }
        if (roundIndex >= selected.size()) {
            beginPodium();
            return;
        }
        state = State.TRANSITION;
        currentGameKey = selected.get(roundIndex);
        currentArena = null;
        transitionLeft = transitionSeconds();

        String join = plugin.getConfig().getString("games." + currentGameKey + ".join-command", "");
        if (arenaAware(currentGameKey)) {
            // 刚查过有空场，这里再兜一次：真被别的对局抢走就跳过这个游戏，别让 {arena} 空跑
            List<String> free = freeArenas(currentGameKey);
            if (free.isEmpty()) {
                String skipped = displayOf(currentGameKey);
                selected.remove(roundIndex);
                broadcast("&7小游戏 &f" + skipped + " &7当前没有空场地，已跳过。");
                beginTransition();
                return;
            }
            currentArena = free.get(random.nextInt(free.size()));
            join = join == null ? null : join.replace("{arena}", currentArena);
        }

        transitionParticipants.clear();
        transitionSpectators.clear();

        List<UUID> online = new ArrayList<>();
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                online.add(id);
            }
        }
        Collections.shuffle(online, random);
        int max = gameMaxPlayers(currentGameKey);
        for (UUID id : online) {
            if (transitionParticipants.size() < max) {
                transitionParticipants.add(id);
            } else {
                transitionSpectators.add(id);
            }
        }
        for (UUID id : transitionSpectators) {
            addStars(id, starSpectatorBonus());
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.teleport(lobby != null ? lobby : p.getLocation());
                Chat.send(p, "&7本局人数超出上限，你在旁观，补偿 &e" + starSpectatorBonus() + " &7颗星。");
            }
        }

        // 过渡阶段直接把参赛者加入下一个游戏的等待区（游戏自己会把人传到它的 lobby），
        // 不再把人冻在场地半空，避免被服务器判定为飞行踢出。
        if (join != null && !join.isBlank()) {
            for (UUID id : transitionParticipants) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.performCommand(join);
                }
            }
        }
        setupSidebar();
        broadcast("&e第 &f" + (roundIndex + 1) + "/" + selected.size() + " &e局：&f"
                + displayOf(currentGameKey) + " &7将在 &e" + transitionLeft + " &7秒后开始"
                + (transitionSpectators.isEmpty() ? "" : " &7（&f" + transitionSpectators.size() + " 人旁观）"));
        if (currentArena != null) {
            broadcast("&7本局场地：&f" + currentArena);
        }
        updateTransitionBar();
        reportArena();
        transitionTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickTransition, 20L, 20L);
    }

    private void tickTransition() {
        if (state != State.TRANSITION) {
            cancelTransition();
            return;
        }
        transitionLeft--;
        if (transitionLeft <= 0) {
            cancelTransition();
            startRound();
            return;
        }
        if (transitionLeft <= 3) {
            for (UUID id : playing) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Chat.title(p, "&e游戏即将开始：" + transitionLeft, "&f" + displayOf(currentGameKey), 1);
                }
            }
        }
        updateTransitionBar();
        updateSidebar();
    }

    private void updateTransitionBar() {
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.actionBar(p, "&7第 &f" + (roundIndex + 1) + "/" + selected.size()
                        + " &7局 &f" + displayOf(currentGameKey) + " &8| &e" + transitionLeft + "s"
                        + " &8| &6★ &f" + starsOf(id));
            }
        }
    }

    private void startRound() {
        if (roundIndex >= selected.size()) {
            beginPodium();
            return;
        }
        state = State.ROUND;
        roundResolved = false;
        roundParticipants.clear();
        roundParticipants.addAll(transitionParticipants);
        restoreAllSidebar();
        reportArena();

        String start = plugin.getConfig().getString("games." + currentGameKey + ".start-command", "");
        if (currentArena != null) {
            start = start == null ? null : start.replace("{arena}", currentArena);
        }
        if (roundParticipants.isEmpty() || start == null || start.isBlank()) {
            Chat.broadcast("&c小游戏 " + displayOf(currentGameKey) + " 启动失败，跳过本局。");
            finishRound(List.of(), List.of(), List.of());
            return;
        }
        broadcast("&6&l第 " + (roundIndex + 1) + " 局：&e" + displayOf(currentGameKey) + " &7开始！"
                + (transitionSpectators.isEmpty() ? "" : "（" + transitionSpectators.size() + " 人旁观）"));
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), start);
        guardTicks = 0;
        guardIdleTicks = 0;
        guardTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRoundGuard, 20L, 20L);
        timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!roundResolved && state == State.ROUND) {
                Chat.broadcast("&e小游戏超时，本局按无人得分处理。");
                finishRound(List.of(), List.of(), List.of());
            }
        }, roundTimeoutSeconds() * 20L);
    }

    @Override
    public void onRoundResult(String gameKey, List<UUID> firsts, List<UUID> seconds, List<UUID> thirds) {
        if (state != State.ROUND || roundResolved) {
            return;
        }
        if (gameKey == null || !gameKey.equals(currentGameKey)) {
            return;
        }
        // 延后 1 tick：小游戏上报完还会把玩家传回主城大厅，等它处理完再切到派对过渡阶段，
        // 否则派对刚把人传到等待区，又会被小游戏自己的回城传送覆盖。
        List<UUID> f = new ArrayList<>(firsts);
        List<UUID> s2 = new ArrayList<>(seconds);
        List<UUID> t = new ArrayList<>(thirds);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (state == State.ROUND && !roundResolved && gameKey.equals(currentGameKey)) {
                finishRound(f, s2, t);
            }
        });
    }

    @Override
    public void onRoundAborted(String gameKey) {
        if (state == State.ROUND && !roundResolved && gameKey != null && gameKey.equals(currentGameKey)) {
            finishRound(List.of(), List.of(), List.of());
        }
    }

    private void finishRound(List<UUID> firsts, List<UUID> seconds, List<UUID> thirds) {
        if (roundResolved) {
            return;
        }
        roundResolved = true;
        cancelTimeout();

        Set<UUID> awarded = new LinkedHashSet<>();
        for (UUID id : firsts) {
            if (playing.contains(id) && awarded.add(id)) {
                addStars(id, starFirst());
            }
        }
        for (UUID id : seconds) {
            if (playing.contains(id) && awarded.add(id)) {
                addStars(id, starSecond());
            }
        }
        for (UUID id : thirds) {
            if (playing.contains(id) && awarded.add(id)) {
                addStars(id, starThird());
            }
        }

        broadcast("&6&l✦  ✦  ✦   第 " + (roundIndex + 1) + " 局结束   ✦  ✦  ✦");
        broadcastRank("第一名", firsts, starFirst());
        broadcastRank("第二名", seconds, starSecond());
        broadcastRank("第三名", thirds, starThird());
        if (firsts.isEmpty() && seconds.isEmpty() && thirds.isEmpty()) {
            broadcast("&7本局无人得分。");
        }

        roundIndex++;
        if (roundIndex >= selected.size()) {
            beginPodium();
        } else {
            beginTransition();
        }
    }

    private void broadcastRank(String label, List<UUID> ids, int starGain) {
        List<UUID> valid = new ArrayList<>();
        for (UUID id : ids) {
            if (playing.contains(id)) {
                valid.add(id);
            }
        }
        if (valid.isEmpty()) {
            return;
        }
        StringBuilder names = new StringBuilder();
        for (UUID id : valid) {
            if (names.length() > 0) {
                names.append("&7、&f");
            }
            names.append(nameOf(id));
        }
        String stars = starGain > 0 ? " &6" + "★".repeat(starGain) : "";
        broadcast("&e" + label + "：&f" + names + stars);
    }

    // ---------- 颁奖 ----------

    private void beginPodium() {
        cancelTransition();
        cancelTimeout();
        state = State.PODIUM;
        currentGameKey = "";
        podiumLines.clear();
        podiumFrozen.clear();
        teleportPartyToAudience();
        setupSidebar();

        List<UUID> ranked = rankedPlayers();
        List<UUID> top = new ArrayList<>();
        for (int i = 0; i < Math.min(3, ranked.size()); i++) {
            top.add(ranked.get(i));
        }
        // 先把前三名藏起来、送到各自站位
        for (int i = 0; i < top.size(); i++) {
            Player p = Bukkit.getPlayer(top.get(i));
            if (p == null) {
                continue;
            }
            int place = i + 1;
            Location spot = podiumSpot(place);
            // 揭晓前锁住移动：站上颁奖台后不能走，等轮到自己的名次才解锁
            podiumFrozen.add(top.get(i));
            p.setGameMode(GameMode.ADVENTURE);
            p.setInvisible(true);
            p.setInvulnerable(true);
            if (spot != null) {
                p.teleport(spot);
            }
        }
        broadcast("&6&l✦  ✦  ✦   颁 奖 典 礼   ✦  ✦  ✦");
        reportArena();

        podiumTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int tick;
            private int stage; // 0 = 还没揭晓第三名

            @Override
            public void run() {
                tick++;
                if (stage == 0 && tick >= 60) {
                    reveal(3);
                    stage = 1;
                } else if (stage == 1 && tick >= 160) {
                    reveal(2);
                    stage = 2;
                } else if (stage == 2 && tick >= 260) {
                    reveal(1);
                    stage = 3;
                } else if (stage == 3 && tick >= 420) {
                    // 三个人都揭晓完：先公布结果，再留一段时间让观众看前三名
                    announcePodiumResults();
                    int delay = endDelaySeconds();
                    if (delay > 0) {
                        endTask = Bukkit.getScheduler().runTaskLater(plugin,
                                ArcadePartyManager.this::endParty, delay * 20L);
                    } else {
                        endParty();
                    }
                    stage = 4;
                }
            }
        }, 1L, 1L);
    }

    private void reveal(int place) {
        List<UUID> ranked = rankedPlayers();
        if (place > ranked.size()) {
            return;
        }
        UUID id = ranked.get(place - 1);
        Player p = Bukkit.getPlayer(id);
        String label = place == 1 ? "第 一 名" : (place == 2 ? "第 二 名" : "第 三 名");
        // 名次只用 title 播报，聊天栏留到三个人都揭晓后一次性公布
        // 揭晓顺序是第三名→第二名→第一名，聊天栏要正序（一/二/三），所以插到最前面
        podiumLines.add(0, "&e" + label.replace(" ", "") + "：&f" + nameOf(id) + " &6★ " + starsOf(id));
        for (UUID other : playing) {
            Player op = Bukkit.getPlayer(other);
            if (op != null) {
                Chat.title(op, "&6&l" + label, "&f" + nameOf(id) + " &6★ " + starsOf(id), 3);
            }
        }
        podiumFrozen.remove(id);
        if (p != null) {
            p.setInvisible(false);
            launchFireworks(p.getLocation());
        } else {
            launchFireworks(podiumSpot(place) != null ? podiumSpot(place) : lobby);
        }
    }

    /** 颁奖结果公布；随后按 podium.end-delay-seconds 留一段时间展示前三名。 */
    private void announcePodiumResults() {
        broadcast("&6&l✦  ✦  ✦   颁 奖 结 果   ✦  ✦  ✦");
        for (String line : podiumLines) {
            broadcast(line);
        }
        int delay = endDelaySeconds();
        if (delay > 0) {
            broadcast("&e&l颁奖典礼结束！&f" + delay + " 秒后返回大厅，可以先看看前三名。");
        }
    }

    private int endDelaySeconds() {
        return Math.max(0, Math.min(300, cfgInt("podium.end-delay-seconds", 10)));
    }

    private void endParty() {
        if (state != State.PODIUM) {
            return;
        }
        broadcast("&6&l派对结束！");
        List<UUID> everyone = new ArrayList<>(playing);
        for (UUID id : everyone) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.setInvisible(false);
                p.setInvulnerable(false);
                resetPlayer(p);
                p.setGameMode(GameMode.SURVIVAL);
                p.setAllowFlight(false);
                p.setFlying(false);
                Chat.title(p, "&6&l派对结束", "&f感谢参与！", 2);
                HubAPI.teleportToLobby(p);
            }
            HubAPI.clearGame(id);
        }
        restoreAllSidebar();
        playing.clear();
        stars.clear();
        selected.clear();
        podiumFrozen.clear();
        endTask = null;
        roundIndex = 0;
        cancelTasks();
        StartCountdown.setGloballySuppressed(false);
        WaitCountdown.setGloballySuppressed(false);
        state = State.WAITING;
        updateCountdown();
        reportArena();
    }

    /** 管理员中止：不颁奖，直接回大厅。 */
    public void abortParty(String reason) {
        if (state == State.WAITING) {
            return;
        }
        broadcast(reason == null ? "&e派对已结束。" : reason);
        for (UUID id : new ArrayList<>(playing)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.setInvisible(false);
                p.setInvulnerable(false);
                resetPlayer(p);
                p.setGameMode(GameMode.SURVIVAL);
                p.setAllowFlight(false);
                p.setFlying(false);
                HubAPI.teleportToLobby(p);
            }
            HubAPI.clearGame(id);
        }
        restoreAllSidebar();
        playing.clear();
        waiting.clear();
        stars.clear();
        selected.clear();
        podiumFrozen.clear();
        roundIndex = 0;
        cancelTasks();
        StartCountdown.setGloballySuppressed(false);
        WaitCountdown.setGloballySuppressed(false);
        state = State.WAITING;
        updateCountdown();
        reportArena();
    }

    // ---------- 星星 / 计分板 ----------

    private void addStars(UUID id, int amount) {
        if (amount == 0) {
            return;
        }
        stars.merge(id, amount, Integer::sum);
    }

    private List<UUID> rankedPlayers() {
        List<UUID> ranked = new ArrayList<>(playing);
        ranked.sort(Comparator
                .comparingInt((UUID id) -> starsOf(id)).reversed()
                .thenComparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        return ranked;
    }

    private void setupSidebar() {
        if (!plugin.getConfig().getBoolean("scoreboard.enabled", true)) {
            return;
        }
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }
        restoreAllSidebar();
        sidebar = manager.getNewScoreboard();
        sidebarObjective = sidebar.registerNewObjective("arcade_stars", Criteria.DUMMY,
                Chat.color(plugin.getConfig().getString("scoreboard.title", "&e&l街机派对 · 星星")));
        sidebarObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
        sidebarEntries.clear();
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            String entry = legacy(plugin.getConfig().getString("scoreboard.line", "&e{name}")
                    .replace("{name}", p.getName())
                    .replace("{stars}", String.valueOf(starsOf(id))));
            sidebarEntries.put(id, entry);
            sidebarObjective.getScore(entry).setScore(starsOf(id));
            p.setScoreboard(sidebar);
        }
        updateSidebar();
    }

    private void updateSidebar() {
        if (sidebarObjective == null) {
            return;
        }
        for (UUID id : playing) {
            String entry = sidebarEntries.get(id);
            if (entry != null) {
                sidebarObjective.getScore(entry).setScore(starsOf(id));
            }
        }
    }

    private void restoreAllSidebar() {
        for (UUID id : playing) {
            restoreSidebar(Bukkit.getPlayer(id));
        }
        sidebar = null;
        sidebarObjective = null;
        sidebarEntries.clear();
    }

    private void restoreSidebar(Player p) {
        if (p == null || sidebar == null) {
            return;
        }
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null && p.getScoreboard().equals(sidebar)) {
            p.setScoreboard(manager.getMainScoreboard());
        }
    }

    private void restoreAll() {
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.setInvisible(false);
                p.setInvulnerable(false);
                restoreSidebar(p);
            }
        }
        sidebar = null;
        sidebarObjective = null;
        sidebarEntries.clear();
    }

    // ---------- 场地 / 传送 ----------

    /** 颁奖台上还没揭晓的玩家不能移动。 */
    public boolean isPodiumFrozen(Player player) {
        return player != null && podiumFrozen.contains(player.getUniqueId());
    }

    private void teleportPartyToLobby() {
        if (lobby == null) {
            return;
        }
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.teleport(lobby);
                p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            }
        }
    }

    private void teleportPartyToAudience() {
        Location target = audience != null ? audience : lobby;
        if (target == null) {
            return;
        }
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.teleport(target);
            }
        }
    }

    private Location podiumSpot(int place) {
        Location spot = place == 1 ? firstSpot : (place == 2 ? secondSpot : thirdSpot);
        return spot != null ? spot : (audience != null ? audience : lobby);
    }

    // ---------- 烟花 ----------

    /** 已配置的固定烟花发射点（/arcade addfirework）。 */
    public List<Location> fireworkSpots() {
        List<Location> out = new ArrayList<>();
        for (String raw : plugin.getConfig().getStringList("fireworks.spots")) {
            Location loc = parse(raw);
            if (loc != null) {
                out.add(loc);
            }
        }
        return out;
    }

    /** 把玩家脚下这一格记为烟花发射点（方块中心）。 */
    public void addFireworkSpot(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        Location spot = new Location(loc.getWorld(),
                loc.getBlockX() + 0.5, loc.getBlockY(), loc.getBlockZ() + 0.5);
        List<String> spots = new ArrayList<>(plugin.getConfig().getStringList("fireworks.spots"));
        spots.add(encode(spot));
        plugin.getConfig().set("fireworks.spots", spots);
        plugin.saveConfig();
    }

    public void clearFireworkSpots() {
        plugin.getConfig().set("fireworks.spots", new ArrayList<String>());
        plugin.saveConfig();
    }

    private void launchFireworks(Location base) {
        if (!plugin.getConfig().getBoolean("fireworks.enabled", true)) {
            return;
        }
        List<String> types = plugin.getConfig().getStringList("fireworks.types");
        if (types.isEmpty()) {
            types = List.of("BALL");
        }
        int flight = Math.max(0, Math.min(200, cfgInt("fireworks.flight-ticks", 30)));
        List<Location> spots = fireworkSpots();
        if (!spots.isEmpty()) {
            // 配了固定发射点：每个点都放一颗，在 spread-ticks 内依次点燃
            int spread = Math.max(1, Math.min(400, cfgInt("fireworks.spread-ticks", 40)));
            long step = Math.max(1L, spread / spots.size());
            for (int i = 0; i < spots.size(); i++) {
                final Location spot = spots.get(i).clone();
                final String typeName = types.get(i % types.size());
                BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin,
                        () -> spawnFirework(spot, typeName, flight), i * step);
                fireworkTasks.add(task);
            }
            return;
        }
        if (base == null || base.getWorld() == null) {
            return;
        }
        int count = Math.max(1, Math.min(30, cfgInt("fireworks.count", 3)));
        int interval = Math.max(1, Math.min(100, cfgInt("fireworks.interval-ticks", 10)));
        for (int i = 0; i < count; i++) {
            final Location spot = base.clone().add(
                    random.nextDouble() * 4 - 2, 1.0, random.nextDouble() * 4 - 2);
            final String typeName = types.get(i % types.size());
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin,
                    () -> spawnFirework(spot, typeName, flight), (long) i * interval);
            fireworkTasks.add(task);
        }
    }

    private void spawnFirework(Location loc, String typeName, int flight) {
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        FireworkEffect.Type type;
        try {
            type = FireworkEffect.Type.valueOf(typeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            type = FireworkEffect.Type.BALL;
        }
        Firework fw = world.spawn(loc, Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        Color color = PALETTE[random.nextInt(PALETTE.length)];
        Color fade = PALETTE[random.nextInt(PALETTE.length)];
        meta.addEffect(FireworkEffect.builder()
                .with(type)
                .withColor(color)
                .withFade(fade)
                .withTrail()
                .build());
        meta.setPower(0);
        fw.setFireworkMeta(meta);
        fw.setTicksToDetonate(flight);
    }

    // ---------- 任务 ----------

    /**
     * 兜底：有些小游戏结束时没上报结果（或开局失败），这里发现场地已经回到等待且
     * 持续几秒没人，就按无人得分结束本局，不至于一直卡到超时。
     */
    private void tickRoundGuard() {
        if (state != State.ROUND || roundResolved) {
            cancelGuard();
            return;
        }
        guardTicks++;
        if (guardTicks < 15) {
            return;
        }
        if (!gameIdle()) {
            guardIdleTicks = 0;
            return;
        }
        guardIdleTicks++;
        if (guardIdleTicks >= 3) {
            cancelGuard();
            Chat.broadcast("&e小游戏 &f" + displayOf(currentGameKey)
                    + " &e没有正常回报结果，本局按无人得分处理。");
            finishRound(List.of(), List.of(), List.of());
        }
    }

    private boolean gameIdle() {
        List<ArenaStatus> arenas = HubAPI.arenas(displayOf(currentGameKey));
        if (arenas.isEmpty()) {
            return false;
        }
        if (arenaAware(currentGameKey) && currentArena != null) {
            for (ArenaStatus status : arenas) {
                if (status.name().equals(currentArena)) {
                    return status.state() == GameState.WAITING;
                }
            }
            return false;
        }
        for (ArenaStatus status : arenas) {
            if (status.state() != GameState.WAITING) {
                return false;
            }
        }
        return true;
    }

    private void cancelGuard() {
        if (guardTask != null) {
            guardTask.cancel();
            guardTask = null;
        }
    }

    private void cancelTransition() {
        if (transitionTask != null) {
            transitionTask.cancel();
            transitionTask = null;
        }
    }

    private void cancelTimeout() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
    }

    private void cancelTasks() {
        cancelTransition();
        cancelTimeout();
        cancelGuard();
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
        if (podiumTask != null) {
            podiumTask.cancel();
            podiumTask = null;
        }
        for (BukkitTask task : fireworkTasks) {
            task.cancel();
        }
        fireworkTasks.clear();
    }

    // ---------- 玩家 ----------

    public void giveWaitItems(Player player) {
        ItemStack bed = new ItemStack(Material.RED_BED);
        ItemMeta bedMeta = bed.getItemMeta();
        if (bedMeta != null) {
            bedMeta.displayName(Chat.noItalic(Chat.color("&c退出派对 &7(右键)")));
            bed.setItemMeta(bedMeta);
        }
        player.getInventory().setItem(8, bed);
        if (isAdmin(player)) {
            ItemStack paper = new ItemStack(Material.PAPER);
            ItemMeta paperMeta = paper.getItemMeta();
            if (paperMeta != null) {
                paperMeta.displayName(Chat.noItalic(Chat.color("&a&l开始派对 &7(右键)")));
                paper.setItemMeta(paperMeta);
            }
            player.getInventory().setItem(4, paper);
            ItemStack menu = new ItemStack(Material.COMPASS);
            ItemMeta menuMeta = menu.getItemMeta();
            if (menuMeta != null) {
                menuMeta.displayName(Chat.noItalic(Chat.color("&e&l派对设置 &7(右键)")));
                menuMeta.getPersistentDataContainer().set(menuKey, PersistentDataType.BYTE, (byte) 1);
                menu.setItemMeta(menuMeta);
            }
            player.getInventory().setItem(0, menu);
        }
    }

    public boolean isReturnItem(ItemStack item) {
        return item != null && item.getType() == Material.RED_BED;
    }

    public boolean isStartItem(ItemStack item) {
        return item != null && item.getType() == Material.PAPER;
    }

    public boolean isMenuOpener(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .getOrDefault(menuKey, PersistentDataType.BYTE, (byte) 0) == 1;
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

    // ---------- 上报 / 播报 ----------

    private void reportArena() {
        boolean active = state != State.WAITING;
        String extra = state == State.ROUND
                ? "第 " + (roundIndex + 1) + "/" + selected.size() + " 局 · " + displayOf(currentGameKey)
                : (state == State.TRANSITION
                ? "准备中 · 第 " + (roundIndex + 1) + "/" + selected.size() + " 局"
                : null);
        HubAPI.setArena(GAME, ARENA, active ? GameState.PLAYING : GameState.WAITING,
                waiting.size() + playing.size(), extra);
        if (lobby != null) {
            HubAPI.setArenaSpectator(GAME, ARENA, lobby.clone().add(0, 6, 0));
        }
    }

    private void broadcastWaiting(String message) {
        for (UUID id : waiting) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.send(p, message);
            }
        }
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
        return name != null ? name : id.toString().substring(0, 8);
    }

    private String displayList() {
        StringBuilder sb = new StringBuilder();
        for (String key : selected) {
            if (sb.length() > 0) {
                sb.append("&7、&f");
            }
            sb.append(displayOf(key));
        }
        return sb.toString();
    }

    private static String legacy(String text) {
        return Chat.toLegacy(Chat.color(text == null ? "" : text));
    }

    private static String encode(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.1f,%.1f",
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch());
    }

    private static Location parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0].trim());
        if (world == null) {
            return null;
        }
        try {
            double x = Double.parseDouble(parts[1].trim());
            double y = Double.parseDouble(parts[2].trim());
            double z = Double.parseDouble(parts[3].trim());
            float yaw = parts.length > 4 ? Float.parseFloat(parts[4].trim()) : 0.0f;
            float pitch = parts.length > 5 ? Float.parseFloat(parts[5].trim()) : 0.0f;
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
