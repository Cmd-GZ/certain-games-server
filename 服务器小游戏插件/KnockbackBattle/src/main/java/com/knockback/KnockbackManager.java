package com.knockback;

import com.hub.session.EndReason;

import com.hub.api.HubAPI;
import com.hub.api.ArcadeBridge;
import com.hub.util.Chat;
import com.hub.util.LiveSidebar;
import com.hub.data.PlayerStats;
import com.hub.util.Top3;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import com.hub.util.StartCountdown;
import com.hub.util.WaitCountdown;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.EntityType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 击退大战的全部对局逻辑。 */
public final class KnockbackManager {

    public static final String GAME_NAME = "击退大战";

    public enum State {
        WAITING, RUNNING
    }

    private final KnockbackPlugin plugin;
    private final KnockbackArena arena;
    private final NamespacedKey stickKey;
    private final NamespacedKey windKey;
    private final NamespacedKey returnKey;
    private final NamespacedKey startKey;
    private final WaitCountdown countdown;
    /** D21：加入 tick 与同 tick 内随机名次（同时进入的几人随机决出主持人）。 */
    private final java.util.Map<UUID, Long> joinTick = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> joinSeed = new java.util.HashMap<>();

    private State state = State.WAITING;
    /** D22：会话层统一按场地键索引（单图游戏只有一个键）。 */
    private final java.util.Map<String, KnockbackSession> sessions = new java.util.LinkedHashMap<>();
    private java.util.Set<UUID> pendingWinners;
    private String pendingEndMessage;
    private KnockbackSession session;

    private KnockbackSession ensureSession() {
        String key = "main";
        KnockbackSession s = sessions.get(key);
        if (s == null || s.state().isFinal()) {
            s = new KnockbackSession(this, key);
            sessions.put(key, s);
        }
        session = s;
        return s;
    }

    private final Set<UUID> waiting = new LinkedHashSet<>();
    private final Set<UUID> playing = new LinkedHashSet<>();
    private final Set<UUID> eliminated = new LinkedHashSet<>();
    /** 本插件生成的、还没爆炸的风弹。 */
    private final Set<UUID> windCharges = new LinkedHashSet<>();
    private BukkitTask gameTask;
    private int secondsLeft;
    private long lastAmmoRefill;
    /** 开局临时开启场地世界的 PvP，结算/关服时还原。 */
    private World pvpWorld;
    private boolean savedPvp;
    private boolean hasSavedPvp;

    private YamlConfiguration data;
    private final LiveSidebar liveSidebar;

    public KnockbackManager(KnockbackPlugin plugin) {
        this.plugin = plugin;
        this.liveSidebar = new LiveSidebar(plugin);
        this.data = PlayerStats.load("knockback", new File(plugin.getDataFolder(), "data.yml"));
        this.arena = new KnockbackArena(plugin);
        this.stickKey = new NamespacedKey(plugin, "stick");
        this.windKey = new NamespacedKey(plugin, "wind-charge");
        this.returnKey = new NamespacedKey(plugin, "return-item");
        this.startKey = new NamespacedKey(plugin, "start-item");
        this.countdown = new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return new ArrayList<>(waiting);
            }

            @Override
            public int minPlayers() {
                return KnockbackManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return KnockbackManager.this.maxPlayers();
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
                        Chat.actionBar(p, "&e等待中 &7| &f开始倒计时 &e" + secondsLeft + "s");
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

                Player hp = Bukkit.getPlayer(host);

                String hname = hp == null ? host.toString() : hp.getName();

                waiting.remove(host);

                if (session != null && hp != null && !session.state().isFinal()) {

                    session.leave(hp);

                }

                if (hp != null && hp.isOnline()) {

                    Chat.send(hp, "&c倒计时结束还没开始，你被移出本局（主持人超时）。");

                    HubAPI.teleportToLobby(hp);

                }

                plugin.getLogger().info("[KnockbackBattle] 主持人 " + hname

                        + " 超时未开始，已移出本局（剩余 " + waiting.size() + " 人）");

                broadcast("&e主持人 &f" + hname + " &e超时未开始，已移出本局。");

                countdown.update();

            }


            @Override
            public void onStart() {
                startGame(false);
            }
        });
    }

    public void load() {
        startCountdown();
        arena.load();
        if (!arena.isReady()) {
            plugin.getLogger().warning("场地未配置完整（缺 " + arena.missing() + "），用 /kb help 查看设置命令。");
        }
    }

    public void reload() {
        arena.load();
    }

    public void shutdown() {
        countdown.cancel();
        cancelGameTask();
        clearWindCharges();
        restoreWorldPvp();
        state = State.WAITING;
        waiting.clear();
        playing.clear();
        eliminated.clear();
    }

    // ---------- 配置 ----------

    private String cfgString(String key, String def) {
        return plugin.getConfig().getString(key, def);
    }

    private int cfgInt(String key, int def) {
        return plugin.getConfig().getInt(key, def);
    }

    private boolean cfgBoolean(String key, boolean def) {
        return plugin.getConfig().getBoolean(key, def);
    }

    private double cfgDouble(String key, double def) {
        return plugin.getConfig().getDouble(key, def);
    }

    private List<String> cfgLore(String key) {
        return plugin.getConfig().getStringList(key);
    }

    private Material material(String key, Material def) {
        Material material = Material.matchMaterial(cfgString(key, def.name()));
        return material == null || material == Material.AIR ? def : material;
    }

    public int minPlayers() {
        return Math.max(2, cfgInt("min-players", 2));
    }

    public int maxPlayers() {
        return Math.max(minPlayers(), cfgInt("max-players", 16));
    }

    public int gameSeconds() {
        return Math.max(5, cfgInt("game-seconds", 180));
    }

    private int returnSlot() {
        return Math.max(0, Math.min(8, cfgInt("wait-items.return.slot", 8)));
    }

    private int startSlot() {
        return Math.max(0, Math.min(8, cfgInt("wait-items.start.slot", 4)));
    }

    private Material stickMaterial() {
        return material("stick.material", Material.IRON_PICKAXE);
    }

    private double stickHorizontal() {
        return Math.max(0.0, cfgDouble("stick.knockback.horizontal", 1.2));
    }

    private double stickVertical() {
        return Math.max(0.0, cfgDouble("stick.knockback.vertical", 0.45));
    }

    private int stickCooldownTicks() {
        return (int) Math.round(Math.max(0.0, cfgDouble("stick.cooldown-seconds", 0.4)) * 20.0);
    }

    private Material windMaterial() {
        return material("wind-charge.material", Material.WIND_CHARGE);
    }

    private double windSpeed() {
        return Math.max(0.1, cfgDouble("wind-charge.speed", 1.5));
    }

    private int windCooldownTicks() {
        return (int) Math.round(Math.max(0.0, cfgDouble("wind-charge.use-cooldown-seconds", 0.5)) * 20.0);
    }

    private int windAmmoAmount() {
        return Math.max(1, Math.min(64, cfgInt("wind-charge.ammo.amount", 8)));
    }

    private int windRefillAmount() {
        return Math.max(0, cfgInt("wind-charge.ammo.refill-amount", 1));
    }

    private int windRefillSeconds() {
        return Math.max(0, cfgInt("wind-charge.ammo.refill-seconds", 3));
    }

    private double windKnockbackHorizontal() {
        return Math.max(0.0, cfgDouble("wind-charge.knockback.horizontal", 2.0));
    }

    private double windKnockbackVertical() {
        return Math.max(0.0, cfgDouble("wind-charge.knockback.vertical", 0.8));
    }

    private double windKnockbackRadius() {
        return Math.max(0.5, cfgDouble("wind-charge.knockback.radius", 5.0));
    }

    private double windSelfHorizontal() {
        return Math.max(0.0, cfgDouble("wind-charge.knockback.self-horizontal", 0.8));
    }

    private double windSelfVertical() {
        return Math.max(0.0, cfgDouble("wind-charge.knockback.self-vertical", 0.8));
    }

    // ---------- 状态查询 ----------

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public int secondsLeft() {
        return secondsLeft;
    }

    /** 声明地盘（{@code docs/框架不变量.md} 的 I2）。单场游戏，场地名用空串。 */
    public void publishRegions() {
        com.hub.region.Regions.begin("knockback");
        if (arena != null) {
            com.hub.region.Regions.box("knockback", "", arena.getPos1(), arena.getPos2());
        }
    }

    public KnockbackArena arena() {
        return arena;
    }

    public boolean isWaiting(Player player) {
        return waiting.contains(player.getUniqueId());
    }

    public boolean isPlaying(Player player) {
        return state == State.RUNNING && playing.contains(player.getUniqueId());
    }

    public boolean isInGame(Player player) {
        UUID id = player.getUniqueId();
        return waiting.contains(id) || playing.contains(id);
    }

    public boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission("kb.admin");
    }

    // ---------- 等待 / 进出 ----------

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

    public int waitingCount() {
        return waiting.size();
    }

    public int minPlayersPublic() {
        return minPlayers();
    }

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
        // I1（D23）：先挂会话再入队；冲突直接拒绝
        KnockbackSession sess = ensureSession();
        if (!sess.join(player)) {
            if (sess.players().isEmpty()) {
                sessions.remove("main");
            }
            return;
        }
        waiting.add(id);
        joinTick.put(id, (long) Bukkit.getCurrentTick());
        joinSeed.put(id, java.util.concurrent.ThreadLocalRandom.current().nextLong());
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        resetPlayer(player);
        player.teleport(arena.getLobby());
        giveWaitItems(player);
        HubAPI.setGame(id, GAME_NAME);
        HubAPI.announceJoin(plugin.getName(), player.getName());
        Chat.send(player, "&a已加入" + GAME_NAME + "等待队列 &7(" + waiting.size() + "/" + maxPlayers() + ")");
        Chat.send(player, "&7人数达到 &f" + minPlayers() + " &7后自动倒计时开始。");
        updateCountdown();
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
                Chat.send(player, "&e你不在等待队列或对局中。");
            }
            return;
        }
        clearGameState(player);
        HubAPI.clearGame(id);
        restorePlayer(player);
        if (!silent) {
            HubAPI.teleportToLobby(player);
            Chat.send(player, "&e你已离开" + GAME_NAME + "。");
        }
        if (state == State.RUNNING && wasPlaying) {
            checkWinAfterRemoval();
        } else if (state == State.WAITING) {
            updateCountdown();
        }
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        boolean wasWaiting = waiting.remove(id);
        boolean wasPlaying = playing.remove(id);
        boolean wasEliminated = eliminated.remove(id);
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
            checkWinAfterRemoval();
        } else if (state == State.WAITING) {
            updateCountdown();
        }
    }

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
        if (session != null && !session.state().isFinal()) {
            session.start();
        }
        enableWorldPvp();
        playing.clear();
        playing.addAll(ready);
        eliminated.clear();
        clearWindCharges();
        secondsLeft = gameSeconds();
        lastAmmoRefill = System.currentTimeMillis();
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            preparePlayer(p);
            HubAPI.setPlaying(id, GAME_NAME);
            HubAPI.setArenaSpectator(GAME_NAME, "", arena().center());
            Chat.title(p, "&b&l" + GAME_NAME, "&f把别人打下去，活到最后！", 2);
        }
        broadcast("&6&l========== " + GAME_NAME + "开始 ==========");
        broadcast("&f击退镐左键敲人，风弹右键炸飞；掉到 &eY=" + arena.getVoidY() + " &f以下淘汰。");
        updateActionBar();
        gameTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGame, 20L, 20L);
    }

    public void stopGame() {
        if (state != State.RUNNING) {
            return;
        }
        endGame(new LinkedHashSet<>(playing), "&e管理员结束了本局。");
    }

    /** 收场入口（I4）：先让会话 end()，再由 onEnd 回到 doEndGame。 */
    public void endGame(Set<UUID> winners, String reason) {
        pendingWinners = winners;
        pendingEndMessage = reason;
        if (session == null || session.state().isFinal()) {
            doEndGame();
            return;
        }
        session.end(EndReason.ROUND_END);
    }

    /** 真正的收场逻辑。赢家与文案透传，玩家看到的结算不变。 */
    void doEndGame() {
        Set<UUID> winners = pendingWinners;
        String reason = pendingEndMessage != null ? pendingEndMessage : "&7对局结束。";
        pendingWinners = null;
        pendingEndMessage = null;
        if (state != State.RUNNING) {
            return;
        }
        cancelGameTask();
        liveSidebar.hide("knockback");
        clearWindCharges();
        restoreWorldPvp();
        state = State.WAITING;
        secondsLeft = 0;
        lastAmmoRefill = 0L;

        broadcast("&6&l========== 游戏结束 ==========");
        if (reason != null && !reason.isBlank()) {
            broadcast(reason);
        }
        List<String> names = new ArrayList<>();
        for (UUID id : winners) {
            names.add(nameOf(id));
        }
        broadcast("&6&l游戏结束！");
        if (names.isEmpty()) {
            broadcast("&6获胜者为: &7无");
        } else {
            broadcast("&6获胜者为: &e" + String.join("、", names));
        }
        List<UUID> firsts = new ArrayList<>(winners);
        List<UUID> elim = new ArrayList<>(eliminated);
        List<UUID> seconds = new ArrayList<>();
        List<UUID> thirds = new ArrayList<>();
        if (!elim.isEmpty()) {
            seconds.add(elim.get(elim.size() - 1));
        }
        if (elim.size() >= 2) {
            thirds.add(elim.get(elim.size() - 2));
        }
        Top3.broadcast(msg -> broadcast(msg), this::nameOf, firsts, seconds, thirds);
        ArcadeBridge.report("knockback", firsts, seconds, thirds);
        Top3.award(data, firsts, seconds, thirds);
        saveData();
        broadcast("&6===============================");

        Set<UUID> all = new LinkedHashSet<>();
        all.addAll(playing);
        all.addAll(eliminated);
        for (UUID id : all) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.title(p, winners.contains(id) ? "&a&l获胜！" : "&6&l游戏结束",
                        winners.contains(id) ? "&f你是最后的赢家" : "&f再接再厉", 2);
                restorePlayer(p);
                HubAPI.teleportToLobby(p);
            }
            HubAPI.clearGame(id);
        }
        playing.clear();
        eliminated.clear();
        waiting.clear();
        updateCountdown();
    }

    private void updateCountdown() {
        if (state != State.WAITING) {
            countdown.cancel();
            return;
        }
        countdown.update();
    }

    /** 场地世界临时开启 PvP：否则服务器会直接拦截玩家近战，EntityDamageByEntityEvent 根本不触发。 */
    private void enableWorldPvp() {
        World world = arena.getPos1() != null
                ? arena.getPos1().getWorld()
                : (arena.getLobby() == null ? null : arena.getLobby().getWorld());
        if (world == null) {
            return;
        }
        pvpWorld = world;
        savedPvp = world.getPVP();
        hasSavedPvp = true;
        world.setPVP(true);
        plugin.getLogger().info("场地世界 PvP 临时开启：" + world.getName() + "（原值 " + savedPvp + "）");
    }

    private void restoreWorldPvp() {
        if (hasSavedPvp && pvpWorld != null) {
            pvpWorld.setPVP(savedPvp);
            plugin.getLogger().info("场地世界 PvP 已还原：" + pvpWorld.getName() + " -> " + savedPvp);
        }
        pvpWorld = null;
        hasSavedPvp = false;
    }

    // ---------- 每 tick ----------

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
        refillAmmo();
        if (secondsLeft == 30 || secondsLeft == 10) {
            broadcast("&e剩余 &f" + secondsLeft + " &e秒！");
        }
        updateActionBar();
        updateLiveBoard();
    }

    private void updateLiveBoard() {
        if (state != State.RUNNING) {
            return;
        }
        List<UUID> online = new ArrayList<>(playing);
        online.sort(Comparator.comparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        lines.add(new LiveSidebar.Line("§7存活 §f" + playing.size(), 1));
        for (UUID id : online) {
            lines.add(new LiveSidebar.Line("§e" + nameOf(id), 0));
        }
        liveSidebar.update("knockback", "&b&l击退大乱斗 · 存活", online, lines);
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
            Chat.actionBar(p, "&b" + GAME_NAME + " &7| &f剩余 &e" + secondsLeft + "s"
                    + " &7| &f存活 &e" + playing.size()
                    + " &7| &f风弹 &e" + countWindCharges(p));
        }
    }

    private void cancelGameTask() {
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
    }

    // ---------- 淘汰 / 胜负 ----------

    public void handleMove(PlayerMoveEvent event) {
        if (state != State.RUNNING || event.getTo() == null) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!playing.contains(id)) {
            return;
        }
        World world = arena.getPos1() == null
                ? (arena.getLobby() == null ? null : arena.getLobby().getWorld())
                : arena.getPos1().getWorld();
        if (world == null || !event.getTo().getWorld().equals(world)) {
            return;
        }
        if (event.getTo().getY() < arena.getVoidY()) {
            eliminate(player, "掉出场地");
        }
    }

    private void eliminate(Player player, String reason) {
        UUID id = player.getUniqueId();
        if (!playing.remove(id)) {
            return;
        }
        eliminated.add(id);
        clearGameState(player);
        player.setGameMode(GameMode.SPECTATOR);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        Location spectatorSpot = arena.center();
        player.teleport(spectatorSpot == null ? arena.getLobby() : spectatorSpot);
        HubAPI.setPlaying(id, GAME_NAME);
        Chat.send(player, "&c你掉下去了！&7你现在是旁观者，等待本局结束。");
        broadcast("&e" + player.getName() + " &7掉下去了（剩余 &e" + playing.size() + "&7 人）");
        checkWinAfterRemoval();
    }

    private void checkWinAfterRemoval() {
        if (state != State.RUNNING) {
            return;
        }
        if (playing.isEmpty()) {
            endGame(new LinkedHashSet<>(), "&c所有人都掉下去了，无人获胜。");
            return;
        }
        if (playing.size() == 1) {
            UUID winner = playing.iterator().next();
            endGame(Set.of(winner), "&6最后一人存活！");
        }
    }

    // ---------- 风弹 ----------

    public void useWindCharge(Player player) {
        if (state != State.RUNNING || !playing.contains(player.getUniqueId())) {
            return;
        }
        if (player.getCooldown(windMaterial()) > 0) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isWindCharge(held)) {
            held = player.getInventory().getItemInOffHand();
        }
        if (!isWindCharge(held)) {
            return;
        }
        if (!consumeWindCharge(player)) {
            Chat.actionBar(player, "&c风弹用完了，等一会儿会自动补充。");
            return;
        }
        player.setCooldown(windMaterial(), windCooldownTicks());
        spawnWindCharge(player);
        applySelfKick(player);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WIND_CHARGE_THROW, 1.0f, 1.0f);
        Chat.actionBar(player, "&b风弹 &e" + countWindCharges(player));
    }

    private void spawnWindCharge(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location start = eye.clone().add(direction.clone().multiply(0.8));
        org.bukkit.entity.Entity spawned = player.getWorld().spawnEntity(start, EntityType.SNOWBALL);
        if (!(spawned instanceof Snowball ball)) {
            spawned.remove();
            return;
        }
        ball.setShooter(player);
        ball.setItem(new ItemStack(Material.WIND_CHARGE));
        ball.setGravity(false);
        ball.setVelocity(direction.clone().multiply(windSpeed()));
        windCharges.add(ball.getUniqueId());
    }

    public boolean isOurWindCharge(UUID id) {
        return windCharges.contains(id);
    }

    /** 风弹命中时由 ProjectileHitEvent 调用：直接按配置击退。投掷者本人不吃爆炸击退，只吃 self-*。 */
    public void detonateWindCharge(Snowball ball) {
        if (!windCharges.remove(ball.getUniqueId())) {
            return;
        }
        Player shooter = ball.getShooter() instanceof Player p ? p : null;
        Location center = ball.getLocation().clone();
        World world = center.getWorld();
        ball.remove();
        if (world != null) {
            world.spawnParticle(Particle.GUST_EMITTER_LARGE, center, 1, 0, 0, 0, 0);
            world.playSound(center, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0f, 1.0f);
        }
        double radius = windKnockbackRadius();
        for (UUID id : new ArrayList<>(playing)) {
            if (shooter != null && id.equals(shooter.getUniqueId())) {
                continue;
            }
            Player target = Bukkit.getPlayer(id);
            if (target == null || !target.isOnline() || !target.getWorld().equals(center.getWorld())) {
                continue;
            }
            double distance = target.getLocation().distance(center);
            if (distance > radius) {
                continue;
            }
            double factor = Math.max(0.2, 1.0 - distance / radius);
            Vector away = target.getLocation().toVector().subtract(center.toVector());
            away.setY(0.0);
            if (away.lengthSquared() < 0.001) {
                away = new Vector(1.0, 0.0, 0.0);
            }
            away.normalize().multiply(windKnockbackHorizontal() * factor);
            away.setY(windKnockbackVertical() * factor);
            target.setVelocity(away);
            target.getWorld().playSound(target.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 0.6f, 1.4f);
        }
    }

    private void applySelfKick(Player player) {
        Vector back = player.getLocation().getDirection().setY(0.0);
        // 平地上不再水平后坐，只保留向上的推力；空中才给水平后坐
        if (!player.isOnGround() && back.lengthSquared() > 0.001) {
            back.normalize().multiply(-windSelfHorizontal());
        } else {
            back.setX(0.0);
            back.setZ(0.0);
        }
        back.setY(windSelfVertical());
        player.setVelocity(player.getVelocity().add(back));
    }

    private void clearWindCharges() {
        for (UUID id : new ArrayList<>(windCharges)) {
            org.bukkit.entity.Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        windCharges.clear();
    }

    // ---------- 击退镐 ----------

    /**
     * 击退镐命中：只给速度，不造成伤害。返回 true 表示本次敲击生效。
     */
    public boolean stickHit(Player attacker, Player victim) {
        if (state != State.RUNNING || !playing.contains(attacker.getUniqueId())
                || !playing.contains(victim.getUniqueId())) {
            return false;
        }
        if (!isStick(attacker.getInventory().getItemInMainHand())
                && !isStick(attacker.getInventory().getItemInOffHand())) {
            return false;
        }
        if (attacker.getCooldown(stickMaterial()) > 0) {
            return false;
        }
        attacker.setCooldown(stickMaterial(), stickCooldownTicks());
        Vector direction = victim.getLocation().toVector().subtract(attacker.getLocation().toVector());
        direction.setY(0.0);
        if (direction.lengthSquared() < 0.001) {
            direction = attacker.getLocation().getDirection().setY(0.0);
        }
        if (direction.lengthSquared() < 0.001) {
            direction = new Vector(1.0, 0.0, 0.0);
        }
        direction.normalize().multiply(stickHorizontal());
        direction.setY(stickVertical());
        victim.setVelocity(direction);
        victim.getWorld().spawnParticle(Particle.SWEEP_ATTACK, victim.getLocation().add(0, 1, 0), 1);
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.0f);
        return true;
    }

    // ---------- 物品 ----------

    private void giveWaitItems(Player player) {
        player.getInventory().setItem(returnSlot(), waitReturnItem());
        if (isAdmin(player)) {
            player.getInventory().setItem(startSlot(), waitStartItem());
        }
    }

    private void preparePlayer(Player player) {
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        resetPlayer(player);
        Location spawn = arena.randomSpawn();
        if (spawn != null) {
            player.teleport(spawn);
        }
        player.getInventory().setItem(0, stickItem());
        player.getInventory().setItem(1, windChargeItem(windAmmoAmount()));
        player.getInventory().setHeldItemSlot(0);
        player.setCooldown(stickMaterial(), 0);
        player.setCooldown(windMaterial(), 0);
        applyConfigEffects(player);
    }

    private void resetPlayer(Player player) {
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setGlowing(false);
    }

    /** 开局给玩家配置里的速度 / 跳跃提升；duration-seconds = 0 表示整局。 */
    private void applyConfigEffects(Player player) {
        applyEffect(player, "player-effects.speed", PotionEffectType.SPEED);
        applyEffect(player, "player-effects.jump-boost", PotionEffectType.JUMP_BOOST);
    }

    private void applyEffect(Player player, String path, PotionEffectType type) {
        if (!cfgBoolean(path + ".enabled", true)) {
            return;
        }
        int amplifier = Math.max(0, Math.min(9, cfgInt(path + ".amplifier", 1)));
        int seconds = Math.max(0, cfgInt(path + ".duration-seconds", 0));
        int ticks = seconds == 0 ? PotionEffect.INFINITE_DURATION : seconds * 20;
        player.addPotionEffect(new PotionEffect(type, ticks, amplifier, false, false, true));
    }

    private void restorePlayer(Player player) {
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        resetPlayer(player);
    }

    private void clearGameState(Player player) {
        player.getInventory().clear();
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        player.setCooldown(stickMaterial(), 0);
        player.setCooldown(windMaterial(), 0);
    }

    private ItemStack stickItem() {
        ItemStack item = new ItemStack(stickMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.displayName(Chat.noItalic(Chat.color(cfgString("stick.name", "&e&l击退镐 &7(左键敲击)"))));
            meta.lore(lore("stick.lore"));
            meta.getPersistentDataContainer().set(stickKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack windChargeItem(int amount) {
        ItemStack item = new ItemStack(windMaterial(), Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.displayName(Chat.noItalic(Chat.color(cfgString("wind-charge.name", "&b&l风弹 &7(右键发射)"))));
            meta.lore(lore("wind-charge.lore"));
            meta.getPersistentDataContainer().set(windKey, PersistentDataType.BYTE, (byte) 1);
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

    private List<Component> lore(String key) {
        List<Component> out = new ArrayList<>();
        for (String line : cfgLore(key)) {
            out.add(Chat.noItalic(Chat.color(line)));
        }
        return out;
    }

    public boolean isStick(ItemStack item) {
        return item != null && item.getType() == stickMaterial() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(stickKey, PersistentDataType.BYTE);
    }

    public boolean isWindCharge(ItemStack item) {
        return item != null && item.getType() == windMaterial() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(windKey, PersistentDataType.BYTE);
    }

    public boolean isReturnItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(returnKey, PersistentDataType.BYTE);
    }

    public boolean isStartItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(startKey, PersistentDataType.BYTE);
    }

    private int findWindChargeSlot(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isWindCharge(contents[slot])) {
                return slot;
            }
        }
        return -1;
    }

    private boolean consumeWindCharge(Player player) {
        int slot = findWindChargeSlot(player);
        if (slot < 0) {
            return false;
        }
        ItemStack item = player.getInventory().getItem(slot);
        if (item == null) {
            return false;
        }
        if (item.getAmount() <= 1) {
            player.getInventory().setItem(slot, null);
        } else {
            item.setAmount(item.getAmount() - 1);
            player.getInventory().setItem(slot, item);
        }
        return true;
    }

    private int countWindCharges(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isWindCharge(item)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private void refillAmmo() {
        int interval = windRefillSeconds();
        int add = windRefillAmount();
        if (interval <= 0 || add <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAmmoRefill < interval * 1000L) {
            return;
        }
        lastAmmoRefill = now;
        for (UUID id : playing) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                addWindCharges(player, add);
            }
        }
    }

    private void addWindCharges(Player player, int add) {
        int max = windAmmoAmount();
        int slot = findWindChargeSlot(player);
        if (slot >= 0) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item != null) {
                int amount = Math.min(max, item.getAmount() + Math.max(0, add));
                item.setAmount(amount);
                player.getInventory().setItem(slot, item);
            }
            return;
        }
        int amount = Math.min(max, Math.max(1, add));
        org.bukkit.inventory.ItemStack stack = windChargeItem(amount);
        var leftover = player.getInventory().addItem(stack);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    // ---------- 辅助 ----------

    private void broadcast(String message) {
        Set<UUID> all = new LinkedHashSet<>();
        all.addAll(waiting);
        all.addAll(playing);
        all.addAll(eliminated);
        for (UUID id : all) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.send(p, message);
            }
        }
    }

    private String nameOf(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player != null) {
            return player.getName();
        }
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? "未知" : name;
    }

    private void saveData() {
        if (data == null) {
            return;
        }
        try {
            PlayerStats.save("knockback", data);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存玩家记录失败：" + ex.getMessage());
        }
    }
}
