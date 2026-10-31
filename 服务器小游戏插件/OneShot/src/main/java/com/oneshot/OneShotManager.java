package com.oneshot;

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
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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

/** One Shot 核心：木剑 + 弓 + 一支箭、一箭秒杀、击杀补箭、最后存活获胜。 */
public final class OneShotManager {

    public static final String GAME = "One Shot";
    public static final String ARENA = "主场地";

    public enum State {
        WAITING, RUNNING
    }

    /** 最近一次对受害者造成伤害的玩家，用于把摔死人头算给追杀者。 */
    private record Hit(UUID attacker, long at) {
    }

    private final OneShotPlugin plugin;
    private final OneShotArena arena;
    private final NamespacedKey swordKey;
    private final NamespacedKey bowKey;
    private final NamespacedKey returnKey;
    private final NamespacedKey startKey;
    private final WaitCountdown countdown;
    private final LiveSidebar liveSidebar;
    private final Random random = new Random();

    private State state = State.WAITING;
    private final Set<UUID> waiting = new LinkedHashSet<>();
    private final Set<UUID> playing = new LinkedHashSet<>();
    private final Set<UUID> eliminated = new LinkedHashSet<>();
    private final List<UUID> elimOrder = new ArrayList<>();
    private final Map<UUID, Hit> lastHit = new HashMap<>();

    private StartCountdown startCountdown;
    private BukkitTask gameTask;
    private int secondsLeft;
    private boolean glowApplied;
    private YamlConfiguration data;

    /** 本局临时开启过 PvP 的世界 -> 原始状态。 */
    private final Map<World, Boolean> pvpStates = new LinkedHashMap<>();

    public OneShotManager(OneShotPlugin plugin) {
        this.plugin = plugin;
        this.arena = new OneShotArena(plugin);
        this.liveSidebar = new LiveSidebar(plugin);
        this.swordKey = new NamespacedKey(plugin, "sword");
        this.bowKey = new NamespacedKey(plugin, "bow");
        this.returnKey = new NamespacedKey(plugin, "return_item");
        this.startKey = new NamespacedKey(plugin, "start_item");
        this.countdown = new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return new ArrayList<>(waiting);
            }

            @Override
            public int minPlayers() {
                return OneShotManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return OneShotManager.this.maxPlayers();
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
        return Math.max(30, cfgInt("game-seconds", 600));
    }

    private int killCreditSeconds() {
        return Math.max(0, Math.min(60, cfgInt("kill-credit-seconds", 10)));
    }

    /** 剩余多少秒开始全体发光；0 = 关闭。 */
    private int glowAtSeconds() {
        return Math.max(0, cfgInt("glow-at-seconds", 60));
    }

    private boolean nightVision() {
        return plugin.getConfig().getBoolean("night-vision", true);
    }

    private Material swordMaterial() {
        return material("items.sword.material", Material.WOODEN_SWORD);
    }

    private Material bowMaterial() {
        return material("items.bow.material", Material.BOW);
    }

    public Material arrowMaterial() {
        return material("items.arrow.material", Material.ARROW);
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
        return cfgString("sidebar.title", "&c&lOne Shot · 存活");
    }

    // ---------- 生命周期 ----------

    public void load() {
        startCountdown();
        arena.load();
        data = PlayerStats.load("oneshot", new File(plugin.getDataFolder(), "data.yml"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HubAPI.registerGame(GAME, GAME, "BOW", "c");
            reportArena();
        }, 40L);
        plugin.getLogger().info("场地" + (arena.isReady() ? "已配置" : "未配置（缺 " + arena.missing() + "）") + "。");
    }

    public void reload() {
        if (state != State.WAITING) {
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
        state = State.WAITING;
        waiting.clear();
        playing.clear();
        eliminated.clear();
        elimOrder.clear();
        lastHit.clear();
    }

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

    public OneShotArena arena() {
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
        return state == State.RUNNING && playing.contains(player.getUniqueId());
    }

    public boolean isEliminated(Player player) {
        return eliminated.contains(player.getUniqueId());
    }

    public boolean isInGame(Player player) {
        UUID id = player.getUniqueId();
        return waiting.contains(id) || playing.contains(id) || eliminated.contains(id);
    }

    public boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission("oneshot.admin");
    }

    public int waitingCount() {
        return waiting.size();
    }

    public int playingCount() {
        return playing.size();
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
        if (waiting.size() >= maxPlayers()) {
            Chat.send(player, "&c等待队列已满（" + maxPlayers() + " 人）。");
            return;
        }
        if (!arena.isReady()) {
            Chat.send(player, "&c场地还没配置完整（缺 " + arena.missing() + "），请联系管理员。");
            return;
        }
        waiting.add(id);
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
        boolean wasEliminated = eliminated.remove(id);
        if (!wasWaiting && !wasPlaying && !wasEliminated) {
            if (!silent) {
                Chat.send(player, "&e你不在此游戏中。");
            }
            return;
        }
        clearGameState(player);
        HubAPI.clearGame(id);
        restorePlayer(player);
        lastHit.remove(id);
        if (!silent) {
            HubAPI.teleportToLobby(player);
            Chat.send(player, "&e你已离开" + GAME + "。");
        }
        if (state == State.RUNNING && wasPlaying) {
            checkWin();
        } else if (state == State.WAITING) {
            updateCountdown();
        }
        updateBoard();
        reportArena();
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
        HubAPI.clearGame(id);
        lastHit.remove(id);
        if (state == State.RUNNING && wasPlaying) {
            checkWin();
        } else if (state == State.WAITING) {
            updateCountdown();
        }
        updateBoard();
        reportArena();
    }

    private void updateCountdown() {
        if (state != State.WAITING) {
            countdown.cancel();
            return;
        }
        countdown.update();
    }

    // ---------- 开局 / 结算 ----------

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
        // 手动区域只认 XZ，开打前把纵向范围（区域内最高/最低方块）算好
        arena.ensureArea();
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
        state = State.RUNNING;
        playing.clear();
        playing.addAll(ready);
        eliminated.clear();
        elimOrder.clear();
        lastHit.clear();
        glowApplied = false;
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
            if (nightVision()) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                        PotionEffect.INFINITE_DURATION, 0, false, false, false));
            }
            p.getInventory().setItem(0, swordItem());
            p.getInventory().setItem(1, bowItem());
            p.getInventory().setItem(2, arrowItem(1));
            p.getInventory().setHeldItemSlot(1);
            HubAPI.setPlaying(id, GAME);
            Chat.title(p, "&c&l" + GAME, "&f你只有一支箭：射中即秒杀，击杀可补箭", 2);
        }
        // 玩家已经传送到场地世界后再开 PvP，确保开的正是他们所在的世界
        enableWorldPvp();
        HubAPI.setArenaSpectator(GAME, ARENA, arena.spectatorSpot());
        broadcast("&6&l========== " + GAME + " 开始 ==========");
        broadcast("&f每人：一把木剑、一把弓、&e一支箭&f。弓箭射中 = 秒杀。");
        broadcast("&7击杀任意玩家（含把对手打下台摔死）会补回一支箭；最后存活者获胜。");
        if (glowAtSeconds() > 0 && secondsLeft <= glowAtSeconds()) {
            applyLowTimeGlow();
        }
        updateBoard();
        updateActionBar();
        reportArena();
        gameTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGame, 20L, 20L);
    }

    public void stopGame() {
        if (state != State.RUNNING) {
            return;
        }
        endGame(new LinkedHashSet<>(), "&e管理员结束了本局。");
    }

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
        if (!glowApplied && glowAtSeconds() > 0 && secondsLeft <= glowAtSeconds()) {
            applyLowTimeGlow();
        }
        if (secondsLeft == 60 || secondsLeft == 30 || secondsLeft == 10) {
            broadcast("&e剩余 &f" + secondsLeft + " &e秒！");
        }
        enforceWorldPvp();
        updateBoard();
        updateActionBar();
        reportArena();
    }

    /** 剩余时间进入最后阶段：所有存活玩家发光（方便互相找到）。 */
    private void applyLowTimeGlow() {
        glowApplied = true;
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.setGlowing(true);
            }
        }
        broadcast("&e&l剩余 " + secondsLeft + " 秒：所有存活玩家开始发光！");
    }

    private void endGame(Set<UUID> winners, String reason) {
        if (state != State.RUNNING) {
            return;
        }
        cancelTasks();
        liveSidebar.hide("oneshot");
        restoreWorldPvp();
        state = State.WAITING;
        secondsLeft = 0;

        broadcast("&6&l========== 游戏结束 ==========");
        if (reason != null && !reason.isBlank()) {
            broadcast(reason);
        }

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
        } else {
            // 没有人存活（同归于尽）：按淘汰顺序倒着取前三档
            int rank = 1;
            for (int i = elimOrder.size() - 1; i >= 0 && rank <= 3; i--, rank++) {
                UUID id = elimOrder.get(i);
                if (rank == 1) {
                    firsts.add(id);
                } else if (rank == 2) {
                    seconds.add(id);
                } else {
                    thirds.add(id);
                }
            }
        }

        if (!firsts.isEmpty()) {
            broadcast("&6&l第一名：&e" + namesOf(firsts) + " &6" + Top3.stars(3));
        }
        if (!seconds.isEmpty()) {
            broadcast("&e第二名：&f" + namesOf(seconds) + " &6" + Top3.stars(2));
        }
        if (!thirds.isEmpty()) {
            broadcast("&e第三名：&f" + namesOf(thirds) + " &6" + Top3.stars(1));
        }
        if (firsts.isEmpty() && seconds.isEmpty() && thirds.isEmpty()) {
            broadcast("&7本局无人存活。");
        }
        Top3.award(data, firsts, seconds, thirds);
        saveData();
        broadcast("&6===============================");

        Set<UUID> all = new LinkedHashSet<>();
        all.addAll(playing);
        all.addAll(eliminated);
        for (UUID id : all) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                // 死亡界面中的玩家先重生，避免回城传送被吃掉
                if (p.isDead()) {
                    p.spigot().respawn();
                }
                Chat.title(p, winners.contains(id) ? "&a&l获胜！" : "&6&l游戏结束",
                        winners.contains(id) ? "&f你活到了最后" : "&f再接再厉", 2);
                restorePlayer(p);
                HubAPI.teleportToLobby(p);
            }
            HubAPI.clearGame(id);
        }
        // 等所有玩家（尤其是最后一名被击杀的）都回到大厅后再上报，
        // 否则派对会在回城传送之前把人加进下一局，随后又被回城踢出，导致跳过一局。
        ArcadeBridge.report("oneshot", firsts, seconds, thirds);
        playing.clear();
        eliminated.clear();
        elimOrder.clear();
        lastHit.clear();
        waiting.clear();
        updateCountdown();
        reportArena();
    }

    // ---------- 淘汰 / 击杀 ----------

    /** 记录一次玩家造成的伤害，用于摔死人头归属。 */
    public void recordHit(Player victim, Player attacker) {
        if (state != State.RUNNING || !playing.contains(victim.getUniqueId())
                || !playing.contains(attacker.getUniqueId())) {
            return;
        }
        lastHit.put(victim.getUniqueId(), new Hit(attacker.getUniqueId(), System.currentTimeMillis()));
    }

    /** 摔下台：判定死亡（人头算最后打他的人）。 */
    public void handleMove(Player player, Location to) {
        if (state != State.RUNNING || to == null || !playing.contains(player.getUniqueId())) {
            return;
        }
        World world = to.getWorld();
        if (world != null && to.getY() < world.getMinHeight()) {
            // 掉进世界底部虚空：直接死亡，人头同样按最后伤害者算
            player.setHealth(0.0);
            return;
        }
        if (!arena.isFallenOff(to)) {
            return;
        }
        player.setHealth(0.0);
    }

    /** 玩家死亡（弓箭秒杀、木剑击杀、摔死都在这里收尾）。 */
    public void handleDeath(Player victim) {
        if (state != State.RUNNING || !playing.contains(victim.getUniqueId())) {
            return;
        }
        Player killer = victim.getKiller();
        if (killer == null || !playing.contains(killer.getUniqueId()) || killer.equals(victim)) {
            killer = lastHitWithin(victim);
        }
        UUID id = victim.getUniqueId();
        playing.remove(id);
        eliminated.add(id);
        elimOrder.add(id);
        lastHit.remove(id);
        victim.setGlowing(false);
        // 夜视保留给旁观者，整局结束再统一移除
        clearGameState(victim);

        if (killer != null && playing.contains(killer.getUniqueId())) {
            grantArrow(killer);
            Chat.send(killer, "&a击杀 &e" + victim.getName() + " &a，补回 &e1 &a支箭。");
        }
        String message = killer != null
                ? "&e" + victim.getName() + " &7被 &e" + killer.getName() + " &7淘汰"
                : "&e" + victim.getName() + " &7摔死了";
        broadcast(message + " &7（剩余 &e" + playing.size() + "&7 人）");
        Chat.send(victim, "&c你被淘汰了，现在可以继续观战。");
        // 强制重生并兜底切成旁观（不依赖 PlayerRespawnEvent 是否被别的插件覆盖）
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!victim.isOnline()) {
                return;
            }
            if (victim.isDead()) {
                victim.spigot().respawn();
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> onRespawned(victim), 2L);
        }, 1L);
        updateBoard();
        reportArena();
        // 死亡事件里直接结算/强制重生容易卡死亡界面，延后 1 tick 再判胜负
        Bukkit.getScheduler().runTask(plugin, this::checkWin);
    }

    private Player lastHitWithin(Player victim) {
        Hit hit = lastHit.get(victim.getUniqueId());
        if (hit == null) {
            return null;
        }
        long window = killCreditSeconds() * 1000L;
        if (window <= 0 || System.currentTimeMillis() - hit.at() > window) {
            return null;
        }
        Player attacker = Bukkit.getPlayer(hit.attacker());
        return attacker != null && attacker.isOnline() ? attacker : null;
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
            Set<UUID> winners = new LinkedHashSet<>();
            winners.add(playing.iterator().next());
            endGame(winners, "&6最后一人存活！");
        }
    }

    /** 淘汰后重生完成：切成旁观并送到观战点（重生没生效就再试一次）。 */
    public void onRespawned(Player player) {
        if (!player.isOnline() || state != State.RUNNING
                || !eliminated.contains(player.getUniqueId())) {
            return;
        }
        if (player.isDead()) {
            player.spigot().respawn();
            Bukkit.getScheduler().runTaskLater(plugin, () -> onRespawned(player), 2L);
            return;
        }
        player.setGameMode(GameMode.SPECTATOR);
        player.setAllowFlight(true);
        player.setFlying(true);
        Location spot = arena.spectatorSpot();
        if (spot != null) {
            player.teleport(spot);
        }
    }

    // ---------- 箭 ----------

    public int arrowCount(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == arrowMaterial()) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /** 击杀奖励：不足 1 支时补到 1 支（上限就是 1 支）。 */
    public void grantArrow(Player player) {
        if (player == null || arrowCount(player) >= 1) {
            return;
        }
        ItemStack arrow = arrowItem(1);
        if (player.getInventory().getItem(2) == null) {
            player.getInventory().setItem(2, arrow);
        } else {
            var leftover = player.getInventory().addItem(arrow);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    // ---------- 场地世界 PvP ----------

    private void enableWorldPvp() {
        Set<World> worlds = new LinkedHashSet<>();
        World arenaWorld = arena.world();
        if (arenaWorld != null) {
            worlds.add(arenaWorld);
        }
        if (arena.getLobby() != null && arena.getLobby().getWorld() != null) {
            worlds.add(arena.getLobby().getWorld());
        }
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getWorld() != null) {
                worlds.add(p.getWorld());
            }
        }
        for (World world : worlds) {
            if (pvpStates.containsKey(world)) {
                continue;
            }
            pvpStates.put(world, world.getPVP());
            world.setPVP(true);
            plugin.getLogger().info("对局临时开启世界 PvP：" + world.getName()
                    + "（原值 " + pvpStates.get(world) + "）");
        }
    }

    /** 每秒检查一次：防止别的插件/世界管理把场地世界的 PvP 又关掉。 */
    private void enforceWorldPvp() {
        for (World world : pvpStates.keySet()) {
            if (!world.getPVP()) {
                world.setPVP(true);
                plugin.getLogger().warning("世界 PvP 被重置，已重新开启：" + world.getName());
            }
        }
    }

    private void restoreWorldPvp() {
        for (Map.Entry<World, Boolean> entry : pvpStates.entrySet()) {
            entry.getKey().setPVP(entry.getValue());
            plugin.getLogger().info("世界 PvP 已还原：" + entry.getKey().getName()
                    + " -> " + entry.getValue());
        }
        pvpStates.clear();
    }

    // ---------- 计分板 / 动作栏 ----------

    private void updateBoard() {
        if (state != State.RUNNING) {
            return;
        }
        if (!sidebarEnabled()) {
            liveSidebar.hide("oneshot");
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
        liveSidebar.update("oneshot", sidebarTitle(), online, lines);
    }

    private void updateActionBar() {
        if (state != State.RUNNING) {
            return;
        }
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.actionBar(p, "&c" + GAME + " &7| &f剩余 &e" + secondsLeft + "s"
                        + " &7| &f存活 &e" + playing.size()
                        + " &7| &f箭 &e" + arrowCount(p) + "/1");
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

    private ItemStack swordItem() {
        ItemStack item = new ItemStack(swordMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.displayName(Chat.noItalic(Chat.color(cfgString("items.sword.name", "&6&l木剑 &7(无限耐久)"))));
            meta.lore(lore("items.sword.lore"));
            meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack bowItem() {
        ItemStack item = new ItemStack(bowMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.displayName(Chat.noItalic(Chat.color(cfgString("items.bow.name", "&a&l弓 &7(无限耐久)"))));
            meta.lore(lore("items.bow.lore"));
            meta.getPersistentDataContainer().set(bowKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack arrowItem(int amount) {
        ItemStack item = new ItemStack(arrowMaterial(), Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color(cfgString("items.arrow.name", "&e&l箭 &7(只有一支)"))));
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

    public boolean isSword(ItemStack item) {
        if (item == null || item.getType() != swordMaterial() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
    }

    public boolean isBow(ItemStack item) {
        if (item == null || item.getType() != bowMaterial() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(bowKey, PersistentDataType.BYTE);
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

    public boolean isArrowItem(ItemStack item) {
        return item != null && item.getType() == arrowMaterial();
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

    // ---------- 玩家状态 ----------

    private void resetPlayer(Player player) {
        player.setGlowing(false);
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
    }

    private void restorePlayer(Player player) {
        clearGameState(player);
        resetPlayer(player);
        player.setGameMode(GameMode.SURVIVAL);
    }

    // ---------- 上报 / 播报 ----------

    private void reportArena() {
        boolean running = state == State.RUNNING;
        String extra = running ? "剩余 " + secondsLeft + "s · " + playing.size() + " 人" : null;
        HubAPI.setArena(GAME, ARENA, running ? GameState.PLAYING : GameState.WAITING,
                waiting.size() + playing.size() + eliminated.size(), extra);
        HubAPI.setArenaSpectator(GAME, ARENA, arena.spectatorSpot());
    }

    private void broadcast(String message) {
        Set<UUID> all = new LinkedHashSet<>();
        all.addAll(waiting);
        all.addAll(playing);
        all.addAll(eliminated);
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
            PlayerStats.save("oneshot", data);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存玩家记录失败：" + ex.getMessage());
        }
    }

    public void saveAll() {
        arena.save();
    }

    // ---------- 建场 ----------

    public int[] build(Location center, int radius, int depth, boolean regenerateSpawns) {
        // 自动建场 = 圆形模式，清掉之前手动划的 pos1/pos2
        arena.clearPos();
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
