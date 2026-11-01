package com.grouprace;

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
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 群友竞速赛的全部对局逻辑。 */
public final class GroupRaceManager {
    /** D22：会话层统一按场地键索引（单图游戏只有一个键）。 */
    private final java.util.Map<String, GroupRaceSession> sessions = new java.util.LinkedHashMap<>();
    private String pendingEndMessage;
    private java.util.List<UUID> pendingRanking;
    private GroupRaceSession session;

    private GroupRaceSession ensureSession() {
        String key = "main";
        GroupRaceSession s = sessions.get(key);
        if (s == null || s.state().isFinal()) {
            s = new GroupRaceSession(this, key);
            sessions.put(key, s);
        }
        session = s;
        return s;
    }


    public static final String GAME_NAME = "群友竞速赛";

    public enum State {
        WAITING, RUNNING
    }

    private static final class RaceProgress {
        final Set<Integer> passed = new HashSet<>();
        int score;
        int lastRingIndex = -1;
        boolean finished;
        long finishTime;
        int finishOrder;
        Location lastPosition;
    }

    private final GroupRacePlugin plugin;
    private final LiveSidebar liveSidebar;
    private final GroupRaceArena arena;
    private final WaitCountdown countdown;
    private final NamespacedKey returnKey;
    private final NamespacedKey startKey;

    private State state = State.WAITING;
    private final Set<UUID> waiting = new LinkedHashSet<>();
    private final Set<UUID> playing = new LinkedHashSet<>();
    private final Set<UUID> finished = new LinkedHashSet<>();
    private final Map<UUID, RaceProgress> progress = new HashMap<>();
    private final Map<UUID, UUID> pigByPlayer = new HashMap<>();
    private final Map<UUID, UUID> playerByPig = new HashMap<>();
    private final Map<UUID, UUID> lobbyPigByPlayer = new HashMap<>();
    private final Map<UUID, UUID> playerByLobbyPig = new HashMap<>();
    private final Map<UUID, Boolean> jumpHeld = new HashMap<>();
    private final Map<UUID, Boolean> sneakHeld = new HashMap<>();
    private final Map<UUID, Boolean> forwardHeld = new HashMap<>();
    private final Map<UUID, Boolean> backHeld = new HashMap<>();
    private final Map<UUID, Boolean> leftHeld = new HashMap<>();
    private final Map<UUID, Boolean> rightHeld = new HashMap<>();
    private final Map<UUID, Vector> flightVelocity = new HashMap<>();
    private final Set<UUID> allowDismount = new HashSet<>();

    private BukkitTask flightTask;
    private BukkitTask gameTask;
    private BukkitTask lobbyTask;
    private int secondsLeft;
    private int finishCounter;
    private boolean shortened;

    private YamlConfiguration data;

    public GroupRaceManager(GroupRacePlugin plugin) {
        this.plugin = plugin;
        this.liveSidebar = new LiveSidebar(plugin);
        this.data = PlayerStats.load("grouprace", new File(plugin.getDataFolder(), "data.yml"));
        this.arena = new GroupRaceArena(plugin);
        this.returnKey = new NamespacedKey(plugin, "return-item");
        this.startKey = new NamespacedKey(plugin, "start-item");
        this.countdown = new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return new ArrayList<>(waiting);
            }

            @Override
            public int minPlayers() {
                return GroupRaceManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return GroupRaceManager.this.maxPlayers();
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

            @Override
            public void onStart() {
                startGame(false);
            }
        });
    }

    public void load() {
        startCountdown();
        arena.load();
        rebuildRings();
        if (lobbyTask == null) {
            lobbyTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickLobbyPigs, 1L, 1L);
        }
        if (!arena.isReady()) {
            plugin.getLogger().warning("赛道未配置完整（缺 " + arena.missing() + "），用 /race help 查看设置命令。");
        }
    }

    public void reload() {
        arena.load();
        rebuildRings();
    }

    public void shutdown() {
        countdown.cancel();
        cancelTasks();
        if (lobbyTask != null) {
            lobbyTask.cancel();
            lobbyTask = null;
        }
        state = State.WAITING;
        removeAllPigs();
        removeAllLobbyPigs();
        waiting.clear();
        playing.clear();
        finished.clear();
        progress.clear();
        jumpHeld.clear();
        sneakHeld.clear();
        forwardHeld.clear();
        backHeld.clear();
        leftHeld.clear();
        rightHeld.clear();
        flightVelocity.clear();
        allowDismount.clear();
    }

    private void rebuildRings() {
        arena.buildRings(ringMaterial(), finishMaterial());
    }

    // ---------- 配置 ----------

    private String cfgString(String key, String def) {
        return plugin.getConfig().getString(key, def);
    }

    private int cfgInt(String key, int def) {
        return plugin.getConfig().getInt(key, def);
    }

    private double cfgDouble(String key, double def) {
        return plugin.getConfig().getDouble(key, def);
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
        return Math.max(5, cfgInt("game-seconds", 300));
    }

    private int finishShortSeconds() {
        return Math.max(1, cfgInt("finish-short-seconds", 20));
    }

    private double pigSpeed() {
        return Math.max(0.05, cfgDouble("pig.speed", 0.55));
    }

    private double pigInertia() {
        return Math.max(0.0, Math.min(0.99, cfgDouble("pig.inertia", 0.85)));
    }

    private double ascendSpeed() {
        return Math.max(0.0, cfgDouble("pig.ascend-speed", 0.35));
    }

    private double descendSpeed() {
        return Math.max(0.0, cfgDouble("pig.descend-speed", 0.35));
    }

    private double startSpread() {
        return Math.max(0.5, cfgDouble("pig.start-spread", 2.0));
    }

    private boolean lobbyPigEnabled() {
        return plugin.getConfig().getBoolean("lobby-pig.enabled", true);
    }

    private double lobbyPigSpeed() {
        return Math.max(0.0, cfgDouble("lobby-pig.speed", 0.25));
    }

    private double ringRadius() {
        return Math.max(1.0, cfgDouble("ring.radius", 3.0));
    }

    private Material ringMaterial() {
        return material("ring.material", Material.SEA_LANTERN);
    }

    private Material finishMaterial() {
        return material("ring.finish-material", Material.REDSTONE_BLOCK);
    }

    private boolean invisibleGlowing() {
        return plugin.getConfig().getBoolean("invisible-glowing", true);
    }

    private int returnSlot() {
        return Math.max(0, Math.min(8, cfgInt("wait-items.return.slot", 8)));
    }

    private int startSlot() {
        return Math.max(0, Math.min(8, cfgInt("wait-items.start.slot", 4)));
    }

    private List<net.kyori.adventure.text.Component> loreList(String key) {
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList(key)) {
            lore.add(Chat.noItalic(Chat.color(line)));
        }
        return lore;
    }

    // ---------- 状态 ----------

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public int secondsLeft() {
        return secondsLeft;
    }

    public int waitingCount() {
        return waiting.size();
    }

    public int playingCount() {
        return playing.size();
    }

    /** 声明地盘（{@code docs/框架不变量.md} 的 I2）。单场游戏，场地名用空串。 */
    public void publishRegions() {
        com.hub.region.Regions.begin("grouprace");
        if (arena != null) {
            com.hub.region.Regions.box("grouprace", "", arena.getPos1(), arena.getPos2());
        }
    }

    public GroupRaceArena arena() {
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
        return player.isOp() || player.hasPermission("race.admin");
    }

    // ---------- 等待 / 进出 ----------

    public void join(Player player) {
        UUID id = player.getUniqueId();
        if (state == State.RUNNING) {
            Chat.send(player, "&c比赛正在进行中，请等这一局结束后再加入。");
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
            Chat.send(player, "&c赛道还没配置完整（缺 " + arena.missing() + "），请联系管理员。");
            return;
        }
        waiting.add(id);
        ensureSession();
        if (session != null && !session.join(player)) {
            plugin.getLogger().warning("[GroupRace] " + player.getName() + " 加入归属链失败（见 [被吞异常]）");
        }
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        resetPlayer(player);
        player.teleport(arena.getLobby());
        giveWaitItems(player);
        if (lobbyPigEnabled()) {
            spawnLobbyPig(player, arena.getLobby());
        }
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
        finished.remove(id);
        if (!wasWaiting && !wasPlaying) {
            if (!silent) {
                Chat.send(player, "&e你不在等待队列或比赛中。");
            }
            return;
        }
        removePig(player);
        removeLobbyPig(player);
        progress.remove(id);
        jumpHeld.remove(id);
        sneakHeld.remove(id);
        forwardHeld.remove(id);
        backHeld.remove(id);
        leftHeld.remove(id);
        rightHeld.remove(id);
        flightVelocity.remove(id);
        HubAPI.clearGame(id);
        restorePlayer(player);
        if (!silent) {
            HubAPI.teleportToLobby(player);
            Chat.send(player, "&e你已离开" + GAME_NAME + "。");
        }
        if (state == State.RUNNING && playing.isEmpty()) {
            endGame(new ArrayList<>(), "&7所有玩家都离开了比赛。");
        } else if (state == State.WAITING) {
            updateCountdown();
        } else if (state == State.RUNNING) {
            maybeShorten();
        }
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        boolean wasWaiting = waiting.remove(id);
        boolean wasPlaying = playing.remove(id);
        finished.remove(id);
        if (!wasWaiting && !wasPlaying) {
            return;
        }
        removePig(player);
        removeLobbyPig(player);
        progress.remove(id);
        jumpHeld.remove(id);
        sneakHeld.remove(id);
        forwardHeld.remove(id);
        backHeld.remove(id);
        leftHeld.remove(id);
        rightHeld.remove(id);
        flightVelocity.remove(id);
        HubAPI.clearGame(id);
        restorePlayer(player);
        if (state == State.RUNNING && playing.isEmpty()) {
            endGame(new ArrayList<>(), "&7所有玩家都离开了比赛。");
        } else if (state == State.WAITING) {
            updateCountdown();
        } else if (state == State.RUNNING) {
            maybeShorten();
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
                    Chat.send(p, "&c赛道未配置完整（缺 " + arena.missing() + "），无法开始。");
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
        removeAllLobbyPigs();
        countdown.cancel();
        state = State.RUNNING;
        if (session != null && !session.state().isFinal()) {
            session.start();
        }
        playing.clear();
        playing.addAll(ready);
        finished.clear();
        progress.clear();
        jumpHeld.clear();
        sneakHeld.clear();
        flightVelocity.clear();
        finishCounter = 0;
        shortened = false;
        secondsLeft = gameSeconds();
        int total = ready.size();
        for (int i = 0; i < total; i++) {
            UUID id = ready.get(i);
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            RaceProgress prog = new RaceProgress();
            progress.put(id, prog);
            jumpHeld.put(id, false);
            sneakHeld.put(id, false);
            forwardHeld.put(id, false);
            backHeld.put(id, false);
            leftHeld.put(id, false);
            rightHeld.put(id, false);
            removeLobbyPig(p);
            preparePlayer(p, arena.startLocation(i, total, startSpread()), prog);
            HubAPI.setPlaying(id, GAME_NAME);
            HubAPI.setArenaSpectator(GAME_NAME, "", arena.getStart());
            Chat.title(p, "&e&l" + GAME_NAME, "&f视角控制方向 · 空格上升 · 下蹲下降", 2);
        }
        broadcast("&6&l========== " + GAME_NAME + "开始 ==========");
        broadcast("&f骑着飞猪穿过圆环得分；终点圆环会结束你的比赛。");
        updateActionBar();
        flightTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFlight, 1L, 1L);
        gameTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGame, 20L, 20L);
    }

    private void preparePlayer(Player player, Location start, RaceProgress prog) {
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        resetPlayer(player);
        Location location = start == null ? arena.getLobby() : start;
        if (location != null) {
            player.teleport(location);
            prog.lastPosition = location.clone().add(0.0, 1.0, 0.0);
            spawnPig(player, location);
            if (invisibleGlowing()) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                        PotionEffect.INFINITE_DURATION, 0, false, false, false));
                // 玩家只隐身，不再发光；发光只留给飞猪
            }
            Vector initial = location.getDirection().setY(0.0);
            if (initial.lengthSquared() < 0.001) {
                initial = new Vector(0.0, 0.0, 1.0);
            }
            flightVelocity.put(player.getUniqueId(), initial.normalize().multiply(pigSpeed()));
        }
    }

    private void giveWaitItems(Player player) {
        player.getInventory().setItem(returnSlot(), waitReturnItem());
        if (isAdmin(player)) {
            player.getInventory().setItem(startSlot(), waitStartItem());
        }
    }

    private ItemStack waitReturnItem() {
        ItemStack item = new ItemStack(material("wait-items.return.material", Material.RED_BED));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color(
                    cfgString("wait-items.return.name", "&a&l返回大厅 &7(右键)"))));
            meta.lore(loreList("wait-items.return.lore"));
            meta.getPersistentDataContainer().set(returnKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack waitStartItem() {
        ItemStack item = new ItemStack(material("wait-items.start.material", Material.PAPER));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color(
                    cfgString("wait-items.start.name", "&a&l开始游戏 &7(右键)"))));
            meta.lore(loreList("wait-items.start.lore"));
            meta.getPersistentDataContainer().set(startKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isReturnItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(returnKey, PersistentDataType.BYTE);
    }

    public boolean isStartItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(startKey, PersistentDataType.BYTE);
    }

    // ---------- 飞猪 ----------

    private Pig spawnPig(Player player, Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        Pig pig = world.spawn(location, Pig.class);
        pig.setSaddle(true);
        // 关键：不能 setAI(false)，这个 Paper 构建里 NoAI 实体完全不走物理，setVelocity 不会前进。
        // setAware(false) 只关掉 AI 目标，物理/速度仍然正常。
        pig.setAI(true);
        pig.setAware(false);
        pig.setGravity(false);
        pig.setInvulnerable(true);
        pig.setSilent(true);
        pig.setCollidable(false);
        pig.setPersistent(true);
        if (invisibleGlowing()) {
            // 飞猪保持可见，只保留发光轮廓；隐身只作用于玩家
            pig.setGlowing(true);
        }
        pig.setRotation(location.getYaw(), 0.0f);
        pigByPlayer.put(player.getUniqueId(), pig.getUniqueId());
        playerByPig.put(pig.getUniqueId(), player.getUniqueId());
        player.teleport(location);
        pig.addPassenger(player);
        return pig;
    }

    private Pig getPig(Player player) {
        UUID pigId = pigByPlayer.get(player.getUniqueId());
        if (pigId == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(pigId);
        return entity instanceof Pig pig ? pig : null;
    }

    private Pig spawnLobbyPig(Player player, Location location) {
        if (location == null) {
            return null;
        }
        removeLobbyPig(player);
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        Pig pig = world.spawn(location, Pig.class);
        pig.setSaddle(true);
        // 同样不能用 NoAI；准备阶段的猪是重力正常、完全手动控制的普通载具
        pig.setAI(true);
        pig.setAware(false);
        pig.setGravity(true);
        pig.setInvulnerable(true);
        pig.setSilent(true);
        pig.setCollidable(false);
        pig.setPersistent(true);
        pig.setRotation(location.getYaw(), 0.0f);
        lobbyPigByPlayer.put(player.getUniqueId(), pig.getUniqueId());
        playerByLobbyPig.put(pig.getUniqueId(), player.getUniqueId());
        player.teleport(location);
        pig.addPassenger(player);
        return pig;
    }

    private Pig getLobbyPig(Player player) {
        UUID pigId = lobbyPigByPlayer.get(player.getUniqueId());
        if (pigId == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(pigId);
        return entity instanceof Pig pig ? pig : null;
    }

    private void removeLobbyPig(Player player) {
        allowDismount.add(player.getUniqueId());
        if (player.getVehicle() != null) {
            player.leaveVehicle();
        }
        allowDismount.remove(player.getUniqueId());
        UUID pigId = lobbyPigByPlayer.remove(player.getUniqueId());
        if (pigId != null) {
            playerByLobbyPig.remove(pigId);
            Entity entity = Bukkit.getEntity(pigId);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    private void removeAllLobbyPigs() {
        for (UUID playerId : new ArrayList<>(lobbyPigByPlayer.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                removeLobbyPig(player);
            }
        }
        for (UUID pigId : new ArrayList<>(playerByLobbyPig.keySet())) {
            Entity entity = Bukkit.getEntity(pigId);
            if (entity != null) {
                entity.remove();
            }
        }
        playerByLobbyPig.clear();
        lobbyPigByPlayer.clear();
    }

    public boolean isLobbyPig(Entity entity) {
        return entity != null && playerByLobbyPig.containsKey(entity.getUniqueId());
    }

    /** 准备阶段的猪：只按 WASD 移动 + 视角转向 + 跳跃，有重力、不自动前进、不会飞。 */
    private void tickLobbyPigs() {
        if (state != State.WAITING) {
            return;
        }
        for (UUID id : new ArrayList<>(waiting)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            Pig pig = getLobbyPig(player);
            if (pig == null || !pig.isValid()) {
                spawnLobbyPig(player, arena.getLobby());
                continue;
            }
            if (player.getVehicle() != pig) {
                continue; // 玩家主动下猪了，不强制再骑上去
            }
            double yaw = Math.toRadians(player.getLocation().getYaw());
            Vector forward = new Vector(-Math.sin(yaw), 0.0, Math.cos(yaw));
            // Minecraft yaw 0 朝南(+Z)，此时玩家的右手边是西(-X)，所以 right = (-cos, 0, -sin)
            Vector right = new Vector(-Math.cos(yaw), 0.0, -Math.sin(yaw));
            double forwardInput = (forwardHeld.getOrDefault(id, false) ? 1.0 : 0.0)
                    - (backHeld.getOrDefault(id, false) ? 1.0 : 0.0);
            double strafeInput = (rightHeld.getOrDefault(id, false) ? 1.0 : 0.0)
                    - (leftHeld.getOrDefault(id, false) ? 1.0 : 0.0);
            Vector desired = forward.multiply(forwardInput).add(right.multiply(strafeInput));
            if (desired.lengthSquared() > 1.0) {
                desired.normalize();
            }
            desired.multiply(lobbyPigSpeed());

            Vector velocity = pig.getVelocity();
            if (jumpHeld.getOrDefault(id, false) && pig.isOnGround()) {
                velocity.setY(0.42);
            }
            velocity.setX(desired.getX());
            velocity.setZ(desired.getZ());
            pig.setVelocity(velocity);
            pig.setRotation(player.getLocation().getYaw(), 0.0f);
        }
    }

    private void removePig(Player player) {
        allowDismount.add(player.getUniqueId());
        if (player.getVehicle() != null) {
            player.leaveVehicle();
        }
        allowDismount.remove(player.getUniqueId());
        UUID pigId = pigByPlayer.remove(player.getUniqueId());
        if (pigId != null) {
            playerByPig.remove(pigId);
            Entity entity = Bukkit.getEntity(pigId);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    private void removeAllPigs() {
        for (UUID pigId : new ArrayList<>(playerByPig.keySet())) {
            Entity entity = Bukkit.getEntity(pigId);
            if (entity != null) {
                entity.remove();
            }
        }
        playerByPig.clear();
        pigByPlayer.clear();
    }

    private void tickFlight() {
        if (state != State.RUNNING) {
            cancelTasks();
            return;
        }
        for (UUID id : new ArrayList<>(playing)) {
            if (finished.contains(id)) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            RaceProgress prog = progress.get(id);
            if (prog == null) {
                continue;
            }
            Pig pig = getPig(player);
            if (pig == null || !pig.isValid()) {
                pig = spawnPig(player, player.getLocation());
                if (pig == null) {
                    continue;
                }
            }
            if (player.getVehicle() != pig) {
                pig.addPassenger(player);
            }

            Vector forward = player.getLocation().getDirection().setY(0.0);
            if (forward.lengthSquared() < 0.001) {
                double yaw = Math.toRadians(player.getLocation().getYaw());
                forward = new Vector(-Math.sin(yaw), 0.0, Math.cos(yaw));
            }
            forward.normalize().multiply(pigSpeed());
            boolean jump = jumpHeld.getOrDefault(id, false);
            boolean sneak = sneakHeld.getOrDefault(id, false) || player.isSneaking();
            forward.setY(jump ? ascendSpeed() : (sneak ? -descendSpeed() : 0.0));

            double inertia = pigInertia();
            Vector previous = flightVelocity.getOrDefault(id, new Vector());
            Vector velocity = previous.multiply(inertia).add(forward.multiply(1.0 - inertia));
            flightVelocity.put(id, velocity);

            pig.setVelocity(velocity);
            pig.setRotation(player.getLocation().getYaw(), 0.0f);
            if (player.getVehicle() != pig) {
                pig.addPassenger(player);
            }

            Location current = player.getLocation().clone().add(0.0, 1.0, 0.0);
            if (!arena.inside(current)) {
                rescue(player, prog);
                continue;
            }
            if (prog.lastPosition != null) {
                checkRings(player, prog, prog.lastPosition, current);
            }
            prog.lastPosition = current;
        }
    }

    private void checkRings(Player player, RaceProgress prog, Location previous, Location current) {
        for (GroupRaceRing ring : new ArrayList<>(arena.rings())) {
            if (prog.finished) {
                return;
            }
            if (!ring.finish() && prog.passed.contains(ring.index())) {
                continue;
            }
            if (ring.finish() && prog.finished) {
                continue;
            }
            double d0 = previous.clone().subtract(ring.center()).toVector().dot(ring.normal());
            double d1 = current.clone().subtract(ring.center()).toVector().dot(ring.normal());
            if (d0 == d1 || d0 * d1 > 0.0) {
                continue;
            }
            double ratio = d0 / (d0 - d1);
            Vector intersection = previous.toVector().add(current.toVector().subtract(previous.toVector()).multiply(ratio));
            Vector radial = intersection.subtract(ring.center().toVector());
            double allowed = Math.max(0.2, ring.radius() - 0.15);
            if (radial.lengthSquared() > allowed * allowed) {
                continue;
            }
            if (ring.finish()) {
                finishPlayer(player, prog);
                return;
            }
            prog.passed.add(ring.index());
            prog.score++;
            prog.lastRingIndex = Math.max(prog.lastRingIndex, ring.index());
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
            player.spawnParticle(Particle.HAPPY_VILLAGER, ring.center(), 16, 1.0, 1.0, 1.0, 0.1);
            Chat.actionBar(player, "&a+" + prog.score + " &7圆环 " + (ring.index() + 1));
        }
    }

    private void rescue(Player player, RaceProgress prog) {
        GroupRaceRing target = prog.lastRingIndex >= 0 ? arena.ring(prog.lastRingIndex) : null;
        GroupRaceRing next = target == null ? arena.firstRing() : arena.nextRing(target.index());
        Location destination;
        if (target != null) {
            destination = target.center();
        } else {
            destination = arena.getStart() == null ? arena.getLobby() : arena.getStart();
        }
        if (destination == null) {
            return;
        }
        GroupRaceRing lookAt = next != null ? next : target;
        if (lookAt != null) {
            Vector direction = lookAt.center().toVector().subtract(destination.toVector());
            if (direction.lengthSquared() > 0.001) {
                destination.setDirection(direction);
            }
        }
        Pig pig = getPig(player);
        if (pig != null) {
            allowDismount.add(player.getUniqueId());
            player.leaveVehicle();
            allowDismount.remove(player.getUniqueId());
        }
        player.teleport(destination);
        if (pig != null && pig.isValid()) {
            pig.teleport(destination);
            pig.setVelocity(new Vector(0.0, 0.0, 0.0));
            pig.addPassenger(player);
        } else {
            spawnPig(player, destination);
        }
        if (prog != null) {
            prog.lastPosition = destination.clone().add(0.0, 1.0, 0.0);
        }
        flightVelocity.put(player.getUniqueId(), new Vector(0.0, 0.0, 0.0));
        Chat.actionBar(player, "&c飞出场地，已传回最后一个圆环");
    }

    private void finishPlayer(Player player, RaceProgress prog) {
        if (prog.finished) {
            return;
        }
        prog.finished = true;
        prog.finishTime = System.currentTimeMillis();
        prog.finishOrder = ++finishCounter;
        finished.add(player.getUniqueId());
        removePig(player);
        player.setGameMode(GameMode.SPECTATOR);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setGlowing(false);
        player.getInventory().clear();
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        GroupRaceRing finishRing = arena.lastRing();
        if (finishRing != null) {
            Location view = finishRing.center().add(finishRing.normal().multiply(6.0)).add(0.0, 2.0, 0.0);
            Vector direction = finishRing.center().toVector().subtract(view.toVector());
            if (direction.lengthSquared() > 0.001) {
                view.setDirection(direction);
            }
            player.teleport(view);
        }
        Chat.title(player, "&6&l到达终点！", "&f得分 " + prog.score, 2);
        broadcast("&e" + player.getName() + " &f到达终点！得分 &e" + prog.score);
        maybeShorten();
        if (!playing.isEmpty() && finished.size() >= playing.size()) {
            endGame(computeRanking(), "&6所有玩家都到达了终点。");
        }
    }

    /** 超过一半参赛者到达终点时，把剩余时间压到 finish-short-seconds。 */
    private void maybeShorten() {
        if (state != State.RUNNING || shortened || playing.isEmpty()) {
            return;
        }
        if (finished.size() * 2 > playing.size()) {
            shortened = true;
            secondsLeft = Math.min(secondsLeft, finishShortSeconds());
            broadcast("&6超过一半玩家到达终点！剩余时间缩短为 &e" + secondsLeft + " &6秒。");
        }
    }

    // ---------- 计时 / 结算 ----------

    private void tickGame() {
        if (state != State.RUNNING) {
            cancelTasks();
            return;
        }
        secondsLeft--;
        if (secondsLeft <= 0) {
            endGame(computeRanking(), "&6&l时间到！");
            return;
        }
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
        online.sort(Comparator.comparingInt((UUID id) -> {
            RaceProgress prog = progress.get(id);
            return prog == null ? 0 : prog.score;
        }).reversed().thenComparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        for (UUID id : online) {
            RaceProgress prog = progress.get(id);
            lines.add(new LiveSidebar.Line("§e" + nameOf(id), prog == null ? 0 : prog.score));
        }
        liveSidebar.update("grouprace", "&6&l分组竞速 · 分数", online, lines);
    }

    private void updateActionBar() {
        if (state != State.RUNNING) {
            return;
        }
        for (UUID id : playing) {
            if (finished.contains(id)) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }
            RaceProgress prog = progress.get(id);
            int score = prog == null ? 0 : prog.score;
            Chat.actionBar(player, "&e" + GAME_NAME + " &7| &f分数 &e" + score
                    + " &7| &f圆环 &e" + (prog == null ? 0 : prog.lastRingIndex + 1)
                    + " &7| &f时间 &e" + secondsLeft + "s");
        }
    }

    /** 按分数、终点、最后圆环序号排出的完整名次（冠军/亚军/季军…）。 */
    private List<UUID> computeRanking() {
        List<UUID> ranking = new ArrayList<>(playing);
        ranking.sort((a, b) -> {
            RaceProgress pa = progress.get(a);
            RaceProgress pb = progress.get(b);
            int scoreA = pa == null ? 0 : pa.score;
            int scoreB = pb == null ? 0 : pb.score;
            int byScore = Integer.compare(scoreB, scoreA);
            if (byScore != 0) {
                return byScore;
            }
            boolean fa = pa != null && pa.finished;
            boolean fb = pb != null && pb.finished;
            if (fa != fb) {
                return fa ? -1 : 1;
            }
            if (fa && fb) {
                int byFinish = Integer.compare(pa.finishOrder, pb.finishOrder);
                if (byFinish != 0) {
                    return byFinish;
                }
            }
            int ringA = pa == null ? -1 : pa.lastRingIndex;
            int ringB = pb == null ? -1 : pb.lastRingIndex;
            int byRing = Integer.compare(ringB, ringA);
            if (byRing != 0) {
                return byRing;
            }
            return nameOf(a).compareToIgnoreCase(nameOf(b));
        });
        return ranking;
    }

    public void stopGame() {
        if (state != State.RUNNING) {
            return;
        }
        endGame(computeRanking(), "&e管理员结束了本局。");
    }

    /** 收场入口（I4）：先让会话 end()，再由 onEnd 回到 doEndGame。 */
    public void endGame(List<UUID> ranking, String reason) {
        pendingRanking = ranking;
        pendingEndMessage = reason;
        if (session == null || session.state().isFinal()) {
            doEndGame();
            return;
        }
        session.end(EndReason.ROUND_END);
    }

    /** 真正的收场逻辑（由 session.onEnd 回调）。名次与文案都透传，玩家看到的结算不变。 */
    void doEndGame() {
        List<UUID> ranking = pendingRanking;
        String reason = pendingEndMessage != null ? pendingEndMessage : "&7比赛结束。";
        pendingRanking = null;
        pendingEndMessage = null;
        if (state != State.RUNNING) {
            return;
        }
        cancelTasks();
        liveSidebar.hide("grouprace");
        state = State.WAITING;
        secondsLeft = 0;
        removeAllPigs();
        removeAllLobbyPigs();

        broadcast("&6&l========== 游戏结束 ==========");
        if (reason != null && !reason.isBlank()) {
            broadcast(reason);
        }
        List<UUID> firsts = new ArrayList<>();
        List<UUID> seconds = new ArrayList<>();
        List<UUID> thirds = new ArrayList<>();
        if (ranking.size() >= 1) firsts.add(ranking.get(0));
        if (ranking.size() >= 2) seconds.add(ranking.get(1));
        if (ranking.size() >= 3) thirds.add(ranking.get(2));
        if (ranking.isEmpty()) {
            broadcast("&7本局无人完赛。");
        } else {
            Top3.broadcast(msg -> broadcast(msg), id -> {
                RaceProgress prog = progress.get(id);
                return nameOf(id) + " &7(" + (prog == null ? 0 : prog.score) + " 分)";
            }, firsts, seconds, thirds);
            Top3.award(data, firsts, seconds, thirds);
            saveData();
        }
        // 没人完赛也要上报，否则街机派对会一直等不到本局结束
        ArcadeBridge.report("grouprace", firsts, seconds, thirds);
        broadcast("&6===============================");

        for (UUID id : new ArrayList<>(playing)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                int place = ranking.indexOf(id);
                RaceProgress prog = progress.get(id);
                int score = prog == null ? 0 : prog.score;
                if (place == 0) {
                    Chat.title(player, "&6&l冠军！", "&f得分 " + score, 2);
                } else if (place == 1) {
                    Chat.title(player, "&7&l亚军", "&f得分 " + score, 2);
                } else if (place == 2) {
                    Chat.title(player, "&c&l季军", "&f得分 " + score, 2);
                } else {
                    Chat.title(player, "&f再接再厉", "&f得分 " + score, 2);
                }
                restorePlayer(player);
                HubAPI.teleportToLobby(player);
            }
            HubAPI.clearGame(id);
        }
        playing.clear();
        finished.clear();
        waiting.clear();
        progress.clear();
        jumpHeld.clear();
        sneakHeld.clear();
        forwardHeld.clear();
        backHeld.clear();
        leftHeld.clear();
        rightHeld.clear();
        flightVelocity.clear();
        allowDismount.clear();
        updateCountdown();
    }

    // ---------- 输入 / 骑乘 ----------

    public void handleInput(Player player, PlayerInputEvent event) {
        UUID id = player.getUniqueId();
        boolean waitingPlayer = waiting.contains(id);
        boolean racing = state == State.RUNNING && playing.contains(id) && !finished.contains(id);
        if (!waitingPlayer && !racing) {
            return;
        }
        forwardHeld.put(id, event.getInput().isForward());
        backHeld.put(id, event.getInput().isBackward());
        leftHeld.put(id, event.getInput().isLeft());
        rightHeld.put(id, event.getInput().isRight());
        jumpHeld.put(id, event.getInput().isJump());
        sneakHeld.put(id, event.getInput().isSneak());
    }

    /** 下蹲事件的兜底：即使 Input 事件没更新，也能保持下降输入。 */
    public void handleSneak(Player player, boolean sneaking) {
        UUID id = player.getUniqueId();
        boolean waitingPlayer = waiting.contains(id);
        boolean racing = state == State.RUNNING && playing.contains(id) && !finished.contains(id);
        if (!waitingPlayer && !racing) {
            return;
        }
        sneakHeld.put(id, sneaking);
    }

    public boolean shouldKeepMounted(Player player, Entity vehicle) {
        if (state != State.RUNNING || !playing.contains(player.getUniqueId())
                || finished.contains(player.getUniqueId()) || allowDismount.contains(player.getUniqueId())) {
            return false;
        }
        return playerByPig.containsKey(vehicle.getUniqueId());
    }

    public boolean isRacePig(Entity entity) {
        return entity != null && playerByPig.containsKey(entity.getUniqueId());
    }

    // ---------- 增删圆环 ----------

    public boolean addRing(Player player, boolean finish) {
        if (state == State.RUNNING) {
            Chat.send(player, "&c比赛中不能修改赛道。");
            return false;
        }
        if (!finish && !arena.rings().isEmpty() && arena.lastRing() != null && arena.lastRing().finish()) {
            Chat.send(player, "&c已经有终点环了，先 /race clearrings 再重新生成。");
            return false;
        }
        if (finish && arena.lastRing() != null && arena.lastRing().finish()) {
            Chat.send(player, "&c终点环已经存在。");
            return false;
        }
        Location center = ringCenter(player, ringRadius());
        Vector normal;
        if (arena.rings().isEmpty()) {
            normal = player.getLocation().getDirection().setY(0.0);
            if (normal.lengthSquared() < 0.001) {
                normal = new Vector(0.0, 0.0, 1.0);
            }
        } else {
            // 第一个环决定整条赛道的轴，后面的环全部和它同方向
            normal = arena.rings().get(0).normal();
        }
        GroupRaceRing ring = new GroupRaceRing(arena.rings().size(), center, normal, ringRadius(), finish);
        ring.build(finish ? finishMaterial() : ringMaterial());
        arena.rings().add(ring);
        arena.save();
        Chat.send(player, "&a已生成" + (finish ? "终点" : "普通") + "圆环 #"
                + (ring.index() + 1) + "（半径 " + ringRadius() + "），已保存。");
        return true;
    }

    public void clearRings(Player player) {
        if (state == State.RUNNING) {
            Chat.send(player, "&c比赛中不能修改赛道。");
            return;
        }
        arena.clearRings();
        arena.save();
        Chat.send(player, "&a已清除所有圆环方块和圆环数据。");
    }

    /**
     * 圆心默认在玩家身体位置；如果圆环最低点埋在地下或卡在玩家身体里，
     * 自动把圆心往上抬，直到整圈都在玩家头顶上方（站在地面生成也能得到完整圆）。
     */
    private Location ringCenter(Player player, double radius) {
        Location center = player.getLocation().clone().add(0.0, 1.0, 0.0);
        double feetY = player.getLocation().getY();
        for (int i = 0; i < 32 && bottomBlocked(center, radius, feetY); i++) {
            center.add(0.0, 1.0, 0.0);
        }
        return center;
    }

    private boolean bottomBlocked(Location center, double radius, double feetY) {
        Location bottom = center.clone().subtract(0.0, radius, 0.0);
        if (!bottom.getBlock().isPassable()) {
            return true;
        }
        double y = bottom.getY();
        return y > feetY - 0.5 && y < feetY + 2.0;
    }

    // ---------- 辅助 ----------

    private void updateCountdown() {
        if (state != State.WAITING) {
            countdown.cancel();
            return;
        }
        countdown.update();
    }

    private void cancelTasks() {
        if (flightTask != null) {
            flightTask.cancel();
            flightTask = null;
        }
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
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

    private void restorePlayer(Player player) {
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        resetPlayer(player);
    }

    private void broadcast(String message) {
        for (UUID id : new LinkedHashSet<>(playing)) {
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
            PlayerStats.save("grouprace", data);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存玩家记录失败：" + ex.getMessage());
        }
    }
}
