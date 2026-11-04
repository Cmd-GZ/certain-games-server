package com.arcade;

import com.hub.api.ArenaStatus;
import com.hub.api.ArcadeBridge;
import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.session.EndReason;
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

    /**
     * 「派对」的**编排属性**（决策 D17）—— 它决定流程/UI，不决定会话结构。
     *
     * <p>判断只有一条：<b>父 session 是否必须比子 session 活得久</b>。
     * 单局游戏（{@code SINGLE}）与单局包装成派对（{@link #SINGLE} = PARTY_OF_ONE）
     * 共用同一条执行路径；只有多局派对（{@link #PARTY} + 未来的 COMPOSITE 父子）才需要嵌套。
     */
    public enum Flow {
        /** 多局串烧（现状）：抽 N 个小游戏，打完进颁奖台。 */
        PARTY,
        /** PARTY_OF_ONE：只打一个，打完就结束，没有领奖台、没有"下一局"。 */
        SINGLE;

        static Flow of(String raw) {
            return raw != null && raw.trim().equalsIgnoreCase("single") ? SINGLE : PARTY;
        }

        public String id() {
            return this == SINGLE ? "single" : "party";
        }
    }

    public enum State {
        WAITING, TRANSITION, ROUND, PODIUM
    }

    private static final Color[] PALETTE = {
            Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA,
            Color.BLUE, Color.PURPLE, Color.FUCHSIA, Color.WHITE
    };

    private final ArcadePartyPlugin plugin;
    private final NamespacedKey menuKey;
    private final Random random = new Random();

    /** 正在关服：收场时不要播报/传送（玩家正在被踢下线）。 */
    private boolean shuttingDown;

    /** 每房间一份的派对状态（D22）；第 1 阶段只有一个实例，行为不变。 */
    private final com.hub.room.RoomScoped<Party> parties = new com.hub.room.RoomScoped<>("arcadeparty");
    /** 当前这一场派对（第 2 阶段改成按房间取）。 */
    /** 兜底那一场（没有房间上下文时用；单派对/老路径行为不变）。 */
    private final Party fallbackParty = new Party();

    /**
     * 当前这一场派对（D22 第 2 阶段：**按房间取**）。
     *
     * <p>顺序：① 房间上下文（`/hub join` 时框架压栈）→ ② 派对房间（`partyRoom()` 的 roomId）
     * → ③ 兜底实例。所以"只有一场派对"时行为和以前完全一样。
     */
    Party party() {
        String roomId = com.hub.room.RoomContext.currentRoomId();
        if (roomId == null) {
            var r = partyRoom();
            roomId = (r == null) ? null : r.roomId;
        }
        if (roomId == null) {
            return fallbackParty;
        }
        final String key = roomId;
        return parties.ofKey(key, k -> new Party());
    }

    /** 这场派对的等待倒计时（懒创建；host 回调绑在**这个** Party 上）。 */
    private WaitCountdown countdown() {
        Party p = party();
        if (p.countdown == null) {
            p.countdown = new WaitCountdown(plugin, new CountdownHost(this, p));
        }
        return p.countdown;
    }

    /** 倒计时回调：所有读取都走"这一个 Party"，不碰全局。 */
    private record CountdownHost(ArcadePartyManager m, Party p) implements WaitCountdown.Host {
        @Override
        public Collection<UUID> waitingPlayers() {
            return new ArrayList<>(p.waiting);
        }

        @Override
        public int minPlayers() {
            return m.minPlayers();
        }

        @Override
        public int maxPlayers() {
            return m.maxPlayers();
        }

        @Override
        public boolean hasOpQueued() {
            for (UUID id : p.waiting) {
                Player pl = Bukkit.getPlayer(id);
                if (pl != null && pl.isOp()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onTick(long secondsLeft) {
            for (UUID id : p.waiting) {
                Player pl = Bukkit.getPlayer(id);
                if (pl != null) {
                    Chat.actionBar(pl, "&7等待中 &7| &f开始倒计时 &e" + secondsLeft + "s");
                }
            }
        }

        @Override
        public void onMessage(String message) {
            for (UUID id : p.waiting) {
                Player pl = Bukkit.getPlayer(id);
                if (pl != null) {
                    Chat.send(pl, message);
                }
            }
        }

        @Override
        public void onStart() {
            m.startParty(false);
        }
    }

    private Location lobby;
    /** 等待区坐标原始串（可能是 tpl: 令牌，需延迟解析）。 */
    private String lobbyRaw = "";
    private Location audience;
    private Location firstSpot;
    private Location thirdSpot;


    private Scoreboard sidebar;
    private Objective sidebarObjective;

    public ArcadePartyManager(ArcadePartyPlugin plugin) {
        this.plugin = plugin;
        this.menuKey = new NamespacedKey(plugin, "menu_item");
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

    /**
     * 这一场打几个小游戏。**房间策略优先**（R5）：地图的 `policy: { games-per-party: N }`
     * 会被建房时带进房间，读不到才回落全局 config。
     */
    public int gamesPerParty() {
        int fromRoom = 0;
        var room = partyRoom();
        if (room != null) {
            try {
                fromRoom = Integer.parseInt(room.policy("games-per-party", ""));
            } catch (NumberFormatException ignored) {
                fromRoom = 0;   // 没配 / 不是数字 -> 用全局
            }
        }
        int value = fromRoom > 0 ? fromRoom : cfgInt("games-per-party", 3);
        return Math.max(1, Math.min(16, value));
    }

    /** 实际这一场要打几个小游戏：单局包装（PARTY_OF_ONE）恒为 1。 */
    public int effectiveGamesPerParty() {
        return flow() == Flow.SINGLE ? 1 : gamesPerParty();
    }

    /**
     * 编排属性（D17）。声明在 {@code ArcadeParty/config.yml} 的 {@code flow} 键：
     * {@code party}（多局串烧，现状）或 {@code single}（单局包装成派对 = PARTY_OF_ONE）。
     *
     * <p>为什么暂时放在插件配置而不是房间模板：房间↔派对的绑定是阶段二（PARTY_MULTI）的事，
     * 现在把 {@code flow} 写进 {@code templates.yml} 会是一个没人读的死键；
     * 等房间能绑定派对时，只需要把这里的**数据来源**换成"绑定的房间"，执行路径不变。
     */
    /**
     * 编排属性（D17）。**房间策略优先**（R5）：地图可以声明
     * `policy: { flow: single }`，建房时带进房间，只影响这个房间；读不到回落全局 config。
     */
    public Flow flow() {
        String global = plugin.getConfig().getString("flow", "party");
        var room = partyRoom();
        String raw = room == null ? global : room.policy("flow", global);
        return Flow.of(raw);
    }

    /** 单局包装（PARTY_OF_ONE）：只打一个、打完即整局结束、没有领奖台。 */
    public boolean isPartyOfOne() {
        return flow() == Flow.SINGLE;
    }

    /** 只允许本场玩的小游戏（key）；空 = 不限制（用 {@code /arcade flow <x> -} 清掉）。 */
    public String flowGame() {
        String key = plugin.getConfig().getString("flow-game", "");
        return key == null ? "" : key.trim();
    }

    /** 改编排属性并落盘（单一写者：config.yml）。game 传 {@code -} 表示清空限制。 */
    public void setFlow(String raw, String game) {
        Flow target = Flow.of(raw);
        plugin.getConfig().set("flow", target.id());
        if (game != null) {
            String cleaned = game.trim();
            if (cleaned.equals("-") || cleaned.equalsIgnoreCase("none")) {
                cleaned = "";
            }
            plugin.getConfig().set("flow-game", cleaned);
        }
        plugin.saveConfig();
    }

    /** 给人看的一行：{@code party（多局串烧，3 个）} / {@code single（单局包装，hotpotato）}。 */
    public String describeFlow() {
        String key = flowGame();
        String pinned = key.isEmpty() ? "" : "，只玩 " + key;
        if (isPartyOfOne()) {
            return "single（单局包装" + (key.isEmpty() ? "，随机一个" : pinned) + "）";
        }
        return "party（多局串烧，" + gamesPerParty() + " 个" + pinned + "）";
    }

    public int transitionSeconds() {
        return Math.max(1, Math.min(300, cfgInt("transition-seconds", 15)));
    }

    private int roundTimeoutSeconds() {
        return Math.max(30, Math.min(7200, cfgInt("round-timeout-seconds", 600)));
    }

    private int starFirst() {
        return Math.max(0, Math.min(10, cfgInt("party.stars.first", 3)));
    }

    private int starSecond() {
        return Math.max(0, Math.min(10, cfgInt("party.stars.second", 2)));
    }

    private int starThird() {
        return Math.max(0, Math.min(10, cfgInt("party.stars.third", 1)));
    }

    private int starSpectatorBonus() {
        return Math.max(0, Math.min(10, cfgInt("party.stars.spectator-bonus", 1)));
    }

    // ---------- 生命周期 ----------

    public void load() {
        ensureDefaults();
        countdown().cancel();
        cancelTasks();
        restoreAll();
        // 坐标串可能是 `tpl:<模板>`：那种令牌**只有存活房间时才能解析**，
        // 所以这里保留原始串，真正用到时再解析（见 getLobby()）。
        lobbyRaw = plugin.getConfig().getString("lobby", "");
        lobby = parse(lobbyRaw);
        audience = parse(plugin.getConfig().getString("podium.audience", ""));
        firstSpot = parse(plugin.getConfig().getString("podium.first", ""));
        party().secondSpot = parse(plugin.getConfig().getString("podium.second", ""));
        thirdSpot = parse(plugin.getConfig().getString("podium.third", ""));
        party().state = State.WAITING;
        party().waiting.clear();
        party().playing.clear();
        party().stars.clear();
        party().selected.clear();
        ArcadeBridge.register(this);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HubAPI.registerGame(GAME, GAME, "FIREWORK_ROCKET", "e");
            reportArena();
        }, 40L);
        plugin.getLogger().info("等待区" + (lobby != null ? "已配置" : "未配置（/arcade setlobby）")
                + "；编排属性 flow=" + describeFlow() + "。");
    }

    public void reload() {
        if (party().state != State.WAITING) {
            return;
        }
        load();
    }

    public void shutdown() {
        // 关服：走同一个 end() 出口（I4），但收场时不播报、不传送（玩家正在被踢下线）
        shuttingDown = true;
        try {
            if (party().session != null) {
                party().session.end(EndReason.ADMIN_STOP);
            } else {
                cancelTasks();
                restoreAll();
            }
        } finally {
            shuttingDown = false;
            StartCountdown.setGloballySuppressed(false);
            WaitCountdown.setGloballySuppressed(false);
            ArcadeBridge.unregister(this);
            saveSettings();
        }
    }

    private void ensureDefaults() {
        FileConfiguration cfg = plugin.getConfig();
        if (cfg.getKeys(false).isEmpty()) {
            return;
        }
        boolean changed = false;
        changed |= setDefault("min-players", 2);
        changed |= setDefault("max-players", 16);
        changed |= setDefault("flow", "party");
        changed |= setDefault("flow-game", "");
        changed |= setDefault("games-per-party", 3);
        changed |= setDefault("transition-seconds", 15);
        changed |= setDefault("transition.freeze", true);
        changed |= setDefault("round-timeout-seconds", 600);
        changed |= setDefault("party.stars.first", 3);
        changed |= setDefault("party.stars.second", 2);
        changed |= setDefault("party.stars.third", 1);
        changed |= setDefault("party.stars.spectator-bonus", 1);
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
        changed |= setDefault("games.arrowrain.display", "万箭齐发");
        changed |= setDefault("games.arrowrain.enabled", true);
        changed |= setDefault("games.arrowrain.arena-aware", false);
        changed |= setDefault("games.arrowrain.max-players", 16);
        changed |= setDefault("games.arrowrain.join-command", "arrowrain join");
        changed |= setDefault("games.arrowrain.start-command", "arrowrain start");
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
        return party().state;
    }

    public ArcadePartyPlugin plugin() {
        return plugin;
    }

    public boolean isWaiting(Player player) {
        return party().waiting.contains(player.getUniqueId());
    }

    public boolean isPlaying(Player player) {
        return party().playing.contains(player.getUniqueId());
    }

    public boolean isInParty(Player player) {
        return isWaiting(player) || isPlaying(player);
    }

    public boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission("arcade.admin");
    }

    public int waitingCount() {
        return party().waiting.size();
    }

    public int playingCount() {
        return party().playing.size();
    }

    public int starsOf(UUID id) {
        return party().stars.getOrDefault(id, 0);
    }

    public Location getLobby() {
        if (lobby == null && lobbyRaw != null && !lobbyRaw.isBlank()) {
            lobby = parse(lobbyRaw);   // 房间出现后再试一次（tpl: 令牌此时才解析得出来）
        }
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

    /** 派对当前所在的房间（房间化之后由它提供世界与场地）；没有则 null。 */
    private com.hub.room.RoomManager.Room partyRoom() {
        // 注意：本类的 GAME 是**显示名**（"街机派对"），而房间的 game 是**游戏 key**（arcadeparty）。
        // 两者不能混用 —— 混了的结果是"房间明明在，派对却看不见它"（池子不取交集、也不委派）。
        // ① 框架在 /hub join 时告诉我们的房间（唯一可靠来源：多房间时 activeFor 会返回 null）
        com.hub.room.RoomManager.Room r = com.hub.room.RoomContext.roomOf("arcadeparty");
        if (r == null) {
            r = com.hub.room.RoomContext.roomOf("arcade");
        }
        // ② 没有上下文（例如老路径直接 /arcade join）才退回"搜唯一房间"
        if (r == null) {
            r = com.hub.room.RoomManager.activeFor("arcadeparty");
        }
        if (r == null) {
            r = com.hub.room.RoomManager.activeFor("arcade");
        }
        return r;
    }

    /** 该游戏当前的空闲场地（等待中、没有对局在跑）；没有就返回空列表。 */
    public List<String> freeArenas(String key) {
        // **房间模式优先**：房间的地图里已经写好了这张图包含哪些子游戏的哪些场地。
        com.hub.room.RoomManager.Room room = partyRoom();
        if (room != null) {
            List<String> inRoom = new ArrayList<>();
            for (com.hub.room.RoomManager.ArenaSlot slot : room.arenasOf(key)) {
                inRoom.add(slot.arenaName());
            }
            if (!inRoom.isEmpty()) {
                return inRoom;
            }
        }
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
        lobbyRaw = loc == null ? "" : encode(loc);
        // R2：读不出来（场地未就绪）时**什么都不写**，绝不能把磁盘上已有的坐标擦成 ''
        if (loc != null) {
            plugin.getConfig().set("lobby", encode(loc));
        }
        plugin.saveConfig();
    }

    public void setAudience(Location loc) {
        audience = loc;
        // R2：读不出来（场地未就绪）时**什么都不写**，绝不能把磁盘上已有的坐标擦成 ''
        if (loc != null) {
            plugin.getConfig().set("podium.audience", encode(loc));
        }
        plugin.saveConfig();
    }

    public void setPodium(String place, Location loc) {
        // R2：读不出来（场地未就绪）时**什么都不写**，绝不能把磁盘上已有的坐标擦成 ''
        if (loc != null) {
            plugin.getConfig().set("podium." + place, encode(loc));
        }
        plugin.saveConfig();
        if ("first".equals(place)) {
            firstSpot = loc;
        } else if ("second".equals(place)) {
            party().secondSpot = loc;
        } else if ("third".equals(place)) {
            thirdSpot = loc;
        }
    }

    // ---------- 加入 / 离开 ----------

    /**
     * 确保有一个"可加入"的派对会话（I1）。
     *
     * <p>语义：等待区阶段就要有会话，玩家一进队列就挂在链上；会话结束（ENDED）后换新的。
     * 这与其它游戏 `ensureSession()` 的写法一致。
     */
    private void ensureSession() {
        if (party().session == null || party().session.state().isFinal()) {
            party().session = new ArcadePartySession(this);
            plugin.getLogger().info("[派对] 新建等待中的派对会话（等待区阶段玩家就在归属链上）");
        }
    }

    public void join(Player player) {
        UUID id = player.getUniqueId();
        if (party().state != State.WAITING) {
            Chat.send(player, "&c派对正在进行中，请等这一局结束后再加入。");
            return;
        }
        if (party().waiting.contains(id)) {
            Chat.send(player, "&e你已经在派对等待队列中。");
            return;
        }
        if (party().waiting.size() >= maxPlayers()) {
            Chat.send(player, "&c派对等待队列已满（" + maxPlayers() + " 人）。");
            return;
        }
        // 用 getLobby()：等待区可能写成 `tpl:` 令牌，需要等房间存在后才能解析
        Location lobbyNow = getLobby();
        if (lobbyNow == null) {
            Chat.send(player, "&c派对还没配置等待区（/arcade setlobby）。");
            return;
        }
        // I1（D23）：**先把玩家挂到归属链，成功了才写进等待队列**。
        // 为什么在 join 就建会话：以前会话是在 startParty() 里建的，于是"派对等待区"阶段
        // 玩家的归属链是空的（doctor 显示 `0 人有归属`）—— 既不符合 I1 的模型
        // （他明明已经在排队等这一场派对），也让 I3 的区域裁决没有归属者可派发。
        ensureSession();
        if (!party().session.join(player)) {
            Chat.send(player, "&c加入失败：你正在另一局游戏里，请先退出那一局。");
            plugin.getLogger().info("[派对] 拒绝 " + player.getName() + " 加入：会话归属链挂接失败（"
                    + party().session.describe() + "）");
            return;
        }
        var roomNow = partyRoom();
        if (roomNow != null) {
            party().session.bindRoom(roomNow.roomId);
        }
        party().waiting.add(id);
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        resetPlayer(player);
        player.teleport(lobbyNow);
        giveWaitItems(player);
        HubAPI.setGame(id, GAME);
        HubAPI.announceJoin(plugin.getName(), player.getName());
        Chat.send(player, "&a已加入" + GAME + " &7(" + party().waiting.size() + "/" + maxPlayers() + ")");
        Chat.send(player, "&7人数达到 &f" + minPlayers() + " &7后自动倒计时开始。");
        updateCountdown();
        reportArena();
    }

    public void leave(Player player, boolean silent) {
        UUID id = player.getUniqueId();
        boolean wasWaiting = party().waiting.remove(id);
        boolean wasPlaying = party().playing.remove(id);
        if (!wasWaiting && !wasPlaying) {
            if (!silent) {
                Chat.send(player, "&e你不在此派对中。");
            }
            return;
        }
        party().stars.remove(id);
        // [上游 c80b1a6] 退出时把过渡/本局名单一起清掉，否则残留会让下一局"跳过"人
        party().transitionParticipants.remove(id);
        party().transitionSpectators.remove(id);
        party().roundParticipants.remove(id);
        clearGameState(player);
        restoreSidebar(player);
        HubAPI.clearGame(id);
        if (!silent) {
            HubAPI.teleportToLobby(player);
            Chat.send(player, "&e你已离开" + GAME + "。");
        }
        // 归属链（I1）：先把这个玩家从派对的会话上摘掉；最后一个人走了会话会自己 end(ABORT)
        if (party().session != null) {
            party().session.leave(player);
        }
        if (party().state == State.WAITING) {
            updateCountdown();
        } else if (party().playing.isEmpty()) {
            abortParty("&7所有玩家都离开了，派对结束。");
            return;
        }
        reportArena();
    }

    /**
     * 派对进行中玩家主动 {@code /hub} 或右键回城道具：立即退出整个派对。
     *
     * <p>[上游 c80b1a6] 只处理玩家主动回城；小游戏结算时的 {@code teleportToLobby}
     * 是框架/游戏自己发的，不会走到这里，所以不会误伤"正常结算后回主城"。
     */
    public void handleHubCommand(Player player) {
        if (!isInParty(player)) {
            return;
        }
        if (party().state == State.WAITING) {
            return;   // 等待阶段交给 lobby 回调统一处理
        }
        leave(player, true);
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        boolean wasWaiting = party().waiting.remove(id);
        boolean wasPlaying = party().playing.remove(id);
        if (!wasWaiting && !wasPlaying) {
            return;
        }
        party().stars.remove(id);
        party().transitionParticipants.remove(id);
        party().transitionSpectators.remove(id);
        party().roundParticipants.remove(id);
        HubAPI.clearGame(id);
        if (party().session != null) {
            party().session.leave(player);
        }
        if (party().state == State.WAITING) {
            updateCountdown();
        } else if (party().playing.isEmpty()) {
            abortParty("&7所有玩家都离开了，派对结束。");
            return;
        }
        reportArena();
    }

    private void updateCountdown() {
        if (party().state != State.WAITING) {
            countdown().cancel();
            return;
        }
        countdown().update();
        plugin.getLogger().info("[派对诊断] state=" + party().state + " waiting=" + party().waiting.size()
                + " min=" + minPlayers() + " 倒计时中=" + countdown().isRunning()
                + " 剩余=" + countdown().remainingSeconds() + "s 等待区=" + (getLobby() == null ? "null" : "有"));
    }

    // ---------- 派对流程 ----------

    public void startParty(boolean force) {
        if (party().state != State.WAITING) {
            return;
        }
        // **必须用 getLobby()**：等待区可能写成 `tpl:<模板键>`，字段 lobby 在插件启用时
        // 解析不到（那时还没有房间），只有等到房间存在后才能解析出来。
        // 之前这里读字段 → 倒计时到点后静默 return，表现为"派对永远不开局"。
        if (getLobby() == null) {
            broadcastWaiting("&c派对还没配置等待区（/arcade setlobby）。");
            return;
        }
        List<UUID> ready = new ArrayList<>();
        for (UUID id : party().waiting) {
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
        // **房间模式**：只打这个房间里**真有场地**的子游戏（房间地图是事实源）。
        // 否则会出现"派对选了 4 个游戏、房间里只有 1 个"的情况——那些关卡根本没地方跑。
        com.hub.room.RoomManager.Room roomNow = partyRoom();
        plugin.getLogger().info("[派对调试] partyRoom=" + (roomNow == null ? "null"
                : roomNow.roomId + "/" + roomNow.game() + " 子游戏=" + roomNow.subGames()));
        if (roomNow != null) {
            List<String> inRoom = roomNow.subGames();
            if (!inRoom.isEmpty()) {
                pool.retainAll(inRoom);
                plugin.getLogger().info("[派对] 房间 " + roomNow.roomId + " 提供的子游戏："
                        + String.join(", ", inRoom) + "；取交集后可选 " + pool.size() + " 个");
                if (pool.isEmpty()) {
                    broadcastWaiting("&c这个房间里没有可玩的小游戏（检查地图数据）。");
                    return;
                }
            }
        }
        // flow-game 非空 = 本场只允许这几个小游戏（两种 flow 都生效；房间化之后它会来自房间模板）
        String pinned = flowGame();
        if (!pinned.isEmpty()) {
            // 支持**逗号分隔的多个**（`hillking,hotpotato`）。以前直接 List.of(pinned)，
            // 整串被当成一个 key -> 池子直接空 -> 派对永远不开局（踩过两次）。
            List<String> wanted = new ArrayList<>();
            for (String one : pinned.split("[,，\\s]+")) {
                if (!one.isBlank()) {
                    wanted.add(one.trim());
                }
            }
            pool.retainAll(wanted);
            if (pool.isEmpty()) {
                broadcastWaiting("&c本场指定的小游戏 &f" + pinned
                        + " &c当前不可用（未启用 / 没有空场地 / join-start 命令不完整）。");
                return;
            }
        }
        Collections.shuffle(pool, random);
        party().selected.clear();
        if (isPartyOfOne()) {
            // PARTY_OF_ONE：整场只有一个游戏（钉死的那一个，或从可用的池里随机抽）
            party().selected.add(pool.get(0));
        } else {
            int want = Math.min(gamesPerParty(), pool.size());
            party().selected.addAll(pool.subList(0, want));
        }

        countdown().cancel();
        cancelTasks();
        party().waiting.clear();
        party().playing.clear();
        party().playing.addAll(ready);
        party().stars.clear();
        for (UUID id : party().playing) {
            party().stars.put(id, 0);
        }
        party().roundIndex = 0;
        party().state = State.TRANSITION;
        StartCountdown.setGloballySuppressed(true);
        WaitCountdown.setGloballySuppressed(true);

        for (UUID id : party().playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                clearGameState(p);
                p.setGameMode(GameMode.SURVIVAL);
                p.setHealth(20);
                p.setFoodLevel(20);
                HubAPI.setPlaying(id, GAME);
            }
        }
        // 会话（I1/I4/I5）：整场派对 = 一个 session，玩家挂到它的归属链上；无 children（D17）
        // 会话在 join() 时就已经建好（等待区阶段玩家也在归属链上），这里只复用/兜底新建。
        ensureSession();
        // **把房间号盖到会话上**（D25：对局结束 -> 销毁房间）。
        // 注意：派对会话的 gameKey 是 "arcade"，而房间上下文是按 room.game()（"arcadeparty"）绑的，
        // 靠 GameSession 构造时自动盖章是拿不到的（真实事故：派对收场后房间没销毁，
        //  一直占着到 300s 空置回收）。所以这里由派对自己盖章。
        var partyRoomNow = partyRoom();
        if (partyRoomNow != null) {
            party().session.bindRoom(partyRoomNow.roomId);
            plugin.getLogger().info("[派对] 会话已绑定房间 " + partyRoomNow.roomId);
        }
        for (UUID id : party().playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                party().session.join(p);
            }
        }
        party().session.start();
        party().podiumShown = false;

        plugin.getLogger().info("[派对] 开局 flow=" + flow().id()
                + "，小游戏 " + party().selected.size() + " 个（" + String.join(", ", party().selected) + "）"
                + "，参与者 " + party().playing.size() + " 人，会话 " + party().session.describe()
                + (isPartyOfOne() ? "；单局包装：本局结束 = 整场结束（无颁奖台、无下一局）" : ""));

        broadcast("&6&l========== " + GAME + " 开始 ==========");
        if (isPartyOfOne()) {
            broadcast("&7单局派对（PARTY_OF_ONE）：&f" + displayList() + " &7打完即结束，没有颁奖典礼。");
        } else {
            broadcast("&7本局共 &e" + party().selected.size() + " &7个小游戏：" + displayList());
        }
        beginTransition();
    }

    private void beginTransition() {
        if (party().state == State.PODIUM) {
            return;
        }
        // 多场地游戏必须有空场，否则从本局清单里去掉、换下一个，避免 join/start 带着 {arena} 空跑
        while (party().roundIndex < party().selected.size()) {
            String key = party().selected.get(party().roundIndex);
            if (!arenaAware(key) || !freeArenas(key).isEmpty()) {
                break;
            }
            String skipped = displayOf(key);
            party().selected.remove(party().roundIndex);
            broadcast("&7小游戏 &f" + skipped + " &7当前没有空场地，已跳过。");
        }
        if (party().roundIndex >= party().selected.size()) {
            beginPodium();
            return;
        }
        party().state = State.TRANSITION;
        party().currentGameKey = party().selected.get(party().roundIndex);
        party().currentArena = null;
        party().transitionLeft = transitionSeconds();

        String join = plugin.getConfig().getString("games." + party().currentGameKey + ".join-command", "");
        if (arenaAware(party().currentGameKey)) {
            // 刚查过有空场，这里再兜一次：真被别的对局抢走就跳过这个游戏，别让 {arena} 空跑
            List<String> free = freeArenas(party().currentGameKey);
            if (free.isEmpty()) {
                String skipped = displayOf(party().currentGameKey);
                party().selected.remove(party().roundIndex);
                broadcast("&7小游戏 &f" + skipped + " &7当前没有空场地，已跳过。");
                beginTransition();
                return;
            }
            party().currentArena = free.get(random.nextInt(free.size()));
            join = join == null ? null : join.replace("{arena}", party().currentArena);
        }

        party().transitionParticipants.clear();
        party().transitionSpectators.clear();

        List<UUID> online = new ArrayList<>();
        for (UUID id : party().playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                online.add(id);
            }
        }
        Collections.shuffle(online, random);
        int max = gameMaxPlayers(party().currentGameKey);
        for (UUID id : online) {
            if (party().transitionParticipants.size() < max) {
                party().transitionParticipants.add(id);
            } else {
                party().transitionSpectators.add(id);
            }
        }
        for (UUID id : party().transitionSpectators) {
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
            // **父级委派**：房间模式下，把"这一关在哪个世界、哪块场地、哪些人"交给子游戏，
            // 子游戏据此初始化（它不会自己去查房间）。委派在整关期间保持有效，关卡结束后清除。
            com.hub.room.RoomManager.Room room = partyRoom();
            if (room != null) {
                // 房间模式：场地与世界都从房间里来。`party().currentArena` 只有 arena-aware 的游戏才有，
                // 其它子游戏就取它在这张地图里的第一块场地（通常只有一块）。
                org.bukkit.World stageWorld = null;
                String stageArena = party().currentArena;
                for (com.hub.room.RoomManager.ArenaSlot slot : room.arenasOf(party().currentGameKey)) {
                    if (party().currentArena != null && !slot.arenaName().equals(party().currentArena)) {
                        continue;
                    }
                    stageWorld = slot.world();
                    stageArena = slot.arenaName();
                    break;
                }
                if (stageWorld != null) {
                    party().currentArena = stageArena;
                    // 带上派对会话：子游戏会话会挂到它下面（I1 嵌套：派对 ⊃ 当前子游戏）
                    com.hub.room.GameDelegation.begin(party().currentGameKey, stageWorld,
                            stageArena, new ArrayList<>(party().transitionParticipants), party().session);
                    plugin.getLogger().info("[派对] 已委派 " + party().currentGameKey + " 到房间 "
                            + room.roomId + " 的世界 " + stageWorld.getName()
                            + "（场地 " + party().currentArena + "，"
                            + party().transitionParticipants.size() + " 人）");
                }
            }
            plugin.getLogger().info("[派对] 给 " + party().transitionParticipants.size()
                    + " 人执行关卡命令：/" + join + "（委派=" + (com.hub.room.GameDelegation.current() == null
                    ? "无" : com.hub.room.GameDelegation.current().game()) + "）");
            for (UUID id : party().transitionParticipants) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    boolean ok = p.performCommand(join);
                    if (!ok) {
                        plugin.getLogger().warning("[派对] 关卡命令执行失败（未知命令？）：/" + join);
                    }
                }
            }
        }
        setupSidebar();
        broadcast("&e" + roundLabel() + "：&f"
                + displayOf(party().currentGameKey) + " &7将在 &e" + party().transitionLeft + " &7秒后开始"
                + (party().transitionSpectators.isEmpty() ? "" : " &7（&f" + party().transitionSpectators.size() + " 人旁观）"));
        if (party().currentArena != null) {
            broadcast("&7本局场地：&f" + party().currentArena);
        }
        updateTransitionBar();
        reportArena();
        party().transitionTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickTransition, 20L, 20L);
    }

    private void tickTransition() {
        if (party().state != State.TRANSITION) {
            cancelTransition();
            return;
        }
        party().transitionLeft--;
        if (party().transitionLeft <= 0) {
            cancelTransition();
            startRound();
            return;
        }
        if (party().transitionLeft <= 3) {
            for (UUID id : party().playing) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Chat.title(p, "&e游戏即将开始：" + party().transitionLeft, "&f" + displayOf(party().currentGameKey), 1);
                }
            }
        }
        updateTransitionBar();
        updateSidebar();
    }

    private void updateTransitionBar() {
        for (UUID id : party().playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.actionBar(p, "&7" + roundLabel() + " &f" + displayOf(party().currentGameKey)
                        + " &8| &e" + party().transitionLeft + "s"
                        + " &8| &6★ &f" + starsOf(id));
            }
        }
    }

    /**
     * 局数标签：多局串烧是「第 x/y 局」，单局包装（PARTY_OF_ONE）只说「本局」——
     * 这是两种 flow 唯一的展示差异（D17：结构完全相同）。
     */
    private String roundLabel() {
        return isPartyOfOne()
                ? "本局" : "第 " + (party().roundIndex + 1) + "/" + party().selected.size() + " 局";
    }

    private void startRound() {
        if (party().roundIndex >= party().selected.size()) {
            beginPodium();
            return;
        }
        party().state = State.ROUND;
        party().roundResolved = false;
        party().roundParticipants.clear();
        party().roundParticipants.addAll(party().transitionParticipants);
        restoreAllSidebar();
        reportArena();

        String start = plugin.getConfig().getString("games." + party().currentGameKey + ".start-command", "");
        if (party().currentArena != null) {
            start = start == null ? null : start.replace("{arena}", party().currentArena);
        }
        // [上游 c80b1a6] 上一局结算后可能延迟把玩家传回主城，回城会触发各游戏的 lobby 回调，
        // 把已经加入本局等待区的玩家踢掉。开局前重新 join 一次，确保队列里确实有人。
        String rejoin = plugin.getConfig().getString("games." + party().currentGameKey + ".join-command", "");
        if (party().currentArena != null && rejoin != null) {
            rejoin = rejoin.replace("{arena}", party().currentArena);
        }
        if (rejoin != null && !rejoin.isBlank()) {
            for (UUID id : new ArrayList<>(party().roundParticipants)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.performCommand(rejoin);
                }
            }
        }
        if (party().roundParticipants.isEmpty() || start == null || start.isBlank()) {
            Chat.broadcast("&c小游戏 " + displayOf(party().currentGameKey) + " 启动失败，跳过本局。");
            finishRound(List.of(), List.of(), List.of());
            return;
        }
        broadcast("&6&l" + roundLabel() + "：&e" + displayOf(party().currentGameKey) + " &7开始！"
                + (party().transitionSpectators.isEmpty() ? "" : "（" + party().transitionSpectators.size() + " 人旁观）"));
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), start);
        party().guardTicks = 0;
        party().guardIdleTicks = 0;
        party().guardTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRoundGuard, 20L, 20L);
        party().timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!party().roundResolved && party().state == State.ROUND) {
                Chat.broadcast("&e小游戏超时，本局按无人得分处理。");
                finishRound(List.of(), List.of(), List.of());
            }
        }, roundTimeoutSeconds() * 20L);
    }

    @Override
    public void onRoundResult(String gameKey, List<UUID> firsts, List<UUID> seconds, List<UUID> thirds) {
        if (party().state != State.ROUND || party().roundResolved) {
            return;
        }
        if (gameKey == null || !gameKey.equals(party().currentGameKey)) {
            return;
        }
        // 延后 1 tick：小游戏上报完还会把玩家传回主城大厅，等它处理完再切到派对过渡阶段，
        // 否则派对刚把人传到等待区，又会被小游戏自己的回城传送覆盖。
        List<UUID> f = new ArrayList<>(firsts);
        List<UUID> s2 = new ArrayList<>(seconds);
        List<UUID> t = new ArrayList<>(thirds);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (party().state == State.ROUND && !party().roundResolved && gameKey.equals(party().currentGameKey)) {
                finishRound(f, s2, t);
            }
        });
    }

    @Override
    public void onRoundAborted(String gameKey) {
        if (party().state == State.ROUND && !party().roundResolved && gameKey != null && gameKey.equals(party().currentGameKey)) {
            finishRound(List.of(), List.of(), List.of());
        }
    }

    private void finishRound(List<UUID> firsts, List<UUID> seconds, List<UUID> thirds) {
        if (party().roundResolved) {
            return;
        }
        party().roundResolved = true;
        // **撤销对子游戏的委派**：这一关结束了，之后别人单开这个子小游戏时，
        // 绝不能还读到本场派对的房间世界（真实风险：委派残留 → 场地被绑到别人的房间）。
        if (com.hub.room.GameDelegation.current() != null) {
            plugin.getLogger().info("[派对] 撤销委派：" + com.hub.room.GameDelegation.current().game());
            com.hub.room.GameDelegation.end();
        }
        cancelTimeout();

        Set<UUID> awarded = new LinkedHashSet<>();
        for (UUID id : firsts) {
            if (party().playing.contains(id) && awarded.add(id)) {
                addStars(id, starFirst());
            }
        }
        for (UUID id : seconds) {
            if (party().playing.contains(id) && awarded.add(id)) {
                addStars(id, starSecond());
            }
        }
        for (UUID id : thirds) {
            if (party().playing.contains(id) && awarded.add(id)) {
                addStars(id, starThird());
            }
        }

        broadcast("&6&l✦  ✦  ✦   " + roundLabel() + "结束   ✦  ✦  ✦");
        plugin.getLogger().info("[派对] " + roundLabel() + "结束："
                + displayOf(party().currentGameKey) + "（第一/二/三名 "
                + firsts.size() + "/" + seconds.size() + "/" + thirds.size() + " 人）");
        broadcastRank("第一名", firsts, starFirst());
        broadcastRank("第二名", seconds, starSecond());
        broadcastRank("第三名", thirds, starThird());
        if (firsts.isEmpty() && seconds.isEmpty() && thirds.isEmpty()) {
            broadcast("&7本局无人得分。");
        }

        party().roundIndex++;
        // D17：单局包装（PARTY_OF_ONE）—— 这一局结束 = 整场结束，直接收场，不进领奖台、没有"下一局"
        if (isPartyOfOne()) {
            endParty(EndReason.ROUND_END, "&6&l派对结束！");
            return;
        }
        if (party().roundIndex >= party().selected.size()) {
            beginPodium();
        } else {
            beginTransition();
        }
    }

    private void broadcastRank(String label, List<UUID> ids, int starGain) {
        List<UUID> valid = new ArrayList<>();
        for (UUID id : ids) {
            if (party().playing.contains(id)) {
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
        String starText = starGain > 0 ? " &6" + "★".repeat(starGain) : "";
        broadcast("&e" + label + "：&f" + names + starText);
    }

    // ---------- 颁奖 ----------

    private void beginPodium() {
        // 防御：单局包装永远不该走到领奖台（正常路径在 finishRound 里已经收场了，D17）
        if (isPartyOfOne()) {
            endParty(EndReason.ROUND_END, "&6&l派对结束！");
            return;
        }
        cancelTransition();
        cancelTimeout();
        party().state = State.PODIUM;
        party().podiumShown = true;
        plugin.getLogger().info("[派对] 全部小游戏打完，进入颁奖典礼（flow=" + flow().id() + "）");
        party().currentGameKey = "";
        party().podiumLines.clear();
        party().podiumFrozen.clear();
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
            party().podiumFrozen.add(top.get(i));
            p.setGameMode(GameMode.ADVENTURE);
            p.setInvisible(true);
            p.setInvulnerable(true);
            if (spot != null) {
                p.teleport(spot);
            }
        }
        broadcast("&6&l✦  ✦  ✦   颁 奖 典 礼   ✦  ✦  ✦");
        reportArena();

        party().podiumTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
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
                        party().endTask = Bukkit.getScheduler().runTaskLater(plugin,
                                () -> endParty(EndReason.ROUND_END, null), delay * 20L);
                    } else {
                        endParty(EndReason.ROUND_END, null);
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
        party().podiumLines.add(0, "&e" + label.replace(" ", "") + "：&f" + nameOf(id) + " &6★ " + starsOf(id));
        for (UUID other : party().playing) {
            Player op = Bukkit.getPlayer(other);
            if (op != null) {
                Chat.title(op, "&6&l" + label, "&f" + nameOf(id) + " &6★ " + starsOf(id), 3);
            }
        }
        party().podiumFrozen.remove(id);
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
        for (String line : party().podiumLines) {
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

    /**
     * 请求结束整场派对。**统一走会话的 {@code end(reason)}**（I4：生命周期唯一出口），
     * 幂等；真正的收场在 {@link #doEndParty(EndReason)}（由 {@link ArcadePartySession#onEnd} 回调）。
     *
     * @param why     结束原因（只有 ROUND_END 才算"正常打完"）
     * @param message 给玩家看的一句话；null = 按 reason 给默认文案
     */
    private void endParty(EndReason why, String message) {
        if (message != null) {
            party().endMessage = message;
        }
        if (party().session != null) {
            party().session.end(why);
            return;
        }
        // 没有会话（理论上不会发生：party().state != WAITING 时一定有会话）—— 兜底收场，别把状态卡死
        doEndParty(why);
    }

    /** 管理员中止：不颁奖，直接回大厅。 */
    public void abortParty(String reason) {
        if (party().state == State.WAITING) {
            return;
        }
        endParty(EndReason.ADMIN_STOP, reason == null ? "&e派对已结束。" : reason);
    }

    /**
     * 真正的收场逻辑。由 {@link ArcadePartySession#onEnd(EndReason)} 回调，
     * 所以**必然只执行一次**（框架保证），这里不再需要"是否在颁奖阶段"的守卫。
     */
    void doEndParty(EndReason why) {
        plugin.getLogger().info("[派对] 收场 reason=" + why + "，flow=" + flow().id()
                + "，走过颁奖=" + party().podiumShown);
        String message = party().endMessage;
        if (message == null) {
            message = switch (why) {
                case ROUND_END -> "&6&l派对结束！";
                case ADMIN_STOP -> "&e管理员结束了本场派对。";
                case ABORT -> "&7所有玩家都离开了，派对结束。";
                default -> "&e派对已结束。";
            };
        }
        party().endMessage = null;
        boolean announce = !shuttingDown;
        if (announce) {
            broadcast(message);
        }
        List<UUID> everyone = new ArrayList<>(party().playing);
        for (UUID id : everyone) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.setInvisible(false);
                p.setInvulnerable(false);
                resetPlayer(p);
                p.setGameMode(GameMode.SURVIVAL);
                p.setAllowFlight(false);
                p.setFlying(false);
                if (announce) {
                    Chat.title(p, "&6&l派对结束", "&f感谢参与！", 2);
                    HubAPI.teleportToLobby(p);
                }
            }
            HubAPI.clearGame(id);
        }
        restoreAllSidebar();
        party().playing.clear();
        party().waiting.clear();
        party().stars.clear();
        party().selected.clear();
        party().podiumFrozen.clear();
        party().currentGameKey = "";
        party().currentArena = null;
        party().roundParticipants.clear();
        party().transitionParticipants.clear();
        party().transitionSpectators.clear();
        party().roundResolved = false;
        party().roundIndex = 0;
        cancelTasks();   // 取消并清空所有任务字段（party().endTask/party().podiumTask/party().transitionTask/party().timeoutTask/party().guardTask）
        StartCountdown.setGloballySuppressed(false);
        WaitCountdown.setGloballySuppressed(false);
        party().state = State.WAITING;
        party().session = null;
        if (announce) {
            updateCountdown();
            reportArena();
        }
    }

    // ---------- 星星 / 计分板 ----------

    private void addStars(UUID id, int amount) {
        if (amount == 0) {
            return;
        }
        party().stars.merge(id, amount, Integer::sum);
    }

    private List<UUID> rankedPlayers() {
        List<UUID> ranked = new ArrayList<>(party().playing);
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
        party().sidebarEntries.clear();
        for (UUID id : party().playing) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            String entry = legacy(plugin.getConfig().getString("scoreboard.line", "&e{name}")
                    .replace("{name}", p.getName())
                    .replace("{stars}", String.valueOf(starsOf(id))));
            party().sidebarEntries.put(id, entry);
            sidebarObjective.getScore(entry).setScore(starsOf(id));
            p.setScoreboard(sidebar);
        }
        updateSidebar();
    }

    private void updateSidebar() {
        if (sidebarObjective == null) {
            return;
        }
        for (UUID id : party().playing) {
            String entry = party().sidebarEntries.get(id);
            if (entry != null) {
                sidebarObjective.getScore(entry).setScore(starsOf(id));
            }
        }
    }

    private void restoreAllSidebar() {
        for (UUID id : party().playing) {
            restoreSidebar(Bukkit.getPlayer(id));
        }
        sidebar = null;
        sidebarObjective = null;
        party().sidebarEntries.clear();
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
        for (UUID id : party().playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.setInvisible(false);
                p.setInvulnerable(false);
                restoreSidebar(p);
            }
        }
        sidebar = null;
        sidebarObjective = null;
        party().sidebarEntries.clear();
    }

    // ---------- 场地 / 传送 ----------

    /** 颁奖台上还没揭晓的玩家不能移动。 */
    public boolean isPodiumFrozen(Player player) {
        return player != null && party().podiumFrozen.contains(player.getUniqueId());
    }

    private void teleportPartyToLobby() {
        if (lobby == null) {
            return;
        }
        for (UUID id : party().playing) {
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
        for (UUID id : party().playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.teleport(target);
            }
        }
    }

    private Location podiumSpot(int place) {
        Location spot = place == 1 ? firstSpot : (place == 2 ? party().secondSpot : thirdSpot);
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
                party().fireworkTasks.add(task);
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
            party().fireworkTasks.add(task);
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
        if (party().state != State.ROUND || party().roundResolved) {
            cancelGuard();
            return;
        }
        party().guardTicks++;
        if (party().guardTicks < 15) {
            return;
        }
        if (!gameIdle()) {
            party().guardIdleTicks = 0;
            return;
        }
        party().guardIdleTicks++;
        if (party().guardIdleTicks >= 3) {
            cancelGuard();
            Chat.broadcast("&e小游戏 &f" + displayOf(party().currentGameKey)
                    + " &e没有正常回报结果，本局按无人得分处理。");
            finishRound(List.of(), List.of(), List.of());
        }
    }

    private boolean gameIdle() {
        List<ArenaStatus> arenas = HubAPI.arenas(displayOf(party().currentGameKey));
        if (arenas.isEmpty()) {
            return false;
        }
        if (arenaAware(party().currentGameKey) && party().currentArena != null) {
            for (ArenaStatus status : arenas) {
                if (status.name().equals(party().currentArena)) {
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
        if (party().guardTask != null) {
            party().guardTask.cancel();
            party().guardTask = null;
        }
    }

    private void cancelTransition() {
        if (party().transitionTask != null) {
            party().transitionTask.cancel();
            party().transitionTask = null;
        }
    }

    private void cancelTimeout() {
        if (party().timeoutTask != null) {
            party().timeoutTask.cancel();
            party().timeoutTask = null;
        }
    }

    private void cancelTasks() {
        cancelTransition();
        cancelTimeout();
        cancelGuard();
        if (party().endTask != null) {
            party().endTask.cancel();
            party().endTask = null;
        }
        if (party().podiumTask != null) {
            party().podiumTask.cancel();
            party().podiumTask = null;
        }
        for (BukkitTask task : party().fireworkTasks) {
            task.cancel();
        }
        party().fireworkTasks.clear();
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
        boolean active = party().state != State.WAITING;
        String extra = party().state == State.ROUND
                ? roundLabel() + " · " + displayOf(party().currentGameKey)
                : (party().state == State.TRANSITION ? "准备中 · " + roundLabel() : null);
        HubAPI.setArena(GAME, ARENA, active ? GameState.PLAYING : GameState.WAITING,
                party().waiting.size() + party().playing.size(), extra);
        if (lobby != null) {
            HubAPI.setArenaSpectator(GAME, ARENA, lobby.clone().add(0, 6, 0));
        }
    }

    private void broadcastWaiting(String message) {
        for (UUID id : party().waiting) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.send(p, message);
            }
        }
    }

    private void broadcast(String message) {
        Set<UUID> all = new LinkedHashSet<>();
        all.addAll(party().waiting);
        all.addAll(party().playing);
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
        for (String key : party().selected) {
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
        // R2：坐标里的世界段可能是 tpl: 令牌（房间化后）
        World world = com.hub.world.Worlds.resolve(parts[0].trim());
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
