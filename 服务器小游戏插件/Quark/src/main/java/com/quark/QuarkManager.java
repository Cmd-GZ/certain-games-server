package com.quark;

import com.hub.api.HubAPI;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 粒子枪战的全部对局逻辑。 */
public final class QuarkManager {

    public static final String GAME_NAME = "粒子枪战";

    public enum State {
        WAITING, RUNNING
    }

    private final QuarkPlugin plugin;
    private final QuarkArena arena;
    private final NamespacedKey gunKey;
    private final WaitCountdown countdown;

    private State state = State.WAITING;
    private final Set<UUID> waiting = new LinkedHashSet<>();
    private final Set<UUID> playing = new LinkedHashSet<>();
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, Long> invulnerableUntil = new HashMap<>();
    private final Map<UUID, UUID> lastKiller = new HashMap<>();
    private final Map<UUID, Long> lastShotMs = new HashMap<>();
    private final Map<UUID, Integer> lastShotTick = new HashMap<>();
    private final LiveSidebar liveSidebar;
    private BukkitTask gameTask;
    private int secondsLeft;

    private YamlConfiguration data;

    public QuarkManager(QuarkPlugin plugin) {
        this.plugin = plugin;
        this.liveSidebar = new LiveSidebar(plugin);
        this.data = PlayerStats.load("quark", new File(plugin.getDataFolder(), "data.yml"));
        this.arena = new QuarkArena(plugin);
        this.gunKey = new NamespacedKey(plugin, "gun");
        this.countdown = new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return new ArrayList<>(waiting);
            }

            @Override
            public int minPlayers() {
                return QuarkManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return QuarkManager.this.maxPlayers();
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
        if (!arena.isReady()) {
            plugin.getLogger().warning("场地未配置完整（缺 " + arena.missing() + "），用 /quark help 查看设置命令。");
        }
    }

    public void reload() {
        arena.load();
    }

    public void shutdown() {
        liveSidebar.hideAll();
        countdown.cancel();
        cancelGameTask();
        state = State.WAITING;
        waiting.clear();
        playing.clear();
        scores.clear();
        invulnerableUntil.clear();
        lastKiller.clear();
        lastShotMs.clear();
        lastShotTick.clear();
    }

    // ---------- 配置 ----------

    public int minPlayers() {
        return Math.max(2, plugin.getConfig().getInt("min-players", 2));
    }

    public int maxPlayers() {
        return Math.max(minPlayers(), plugin.getConfig().getInt("max-players", 16));
    }

    private int gameSeconds() {
        return Math.max(5, plugin.getConfig().getInt("game-seconds", 90));
    }

    private int invulnerableTicks() {
        return Math.max(0, plugin.getConfig().getInt("respawn-invulnerable-ticks", 30));
    }

    private Material gunMaterial() {
        Material material = Material.matchMaterial(plugin.getConfig().getString("gun.material", "IRON_HOE"));
        if (material == null || material == Material.AIR || !material.isItem()) {
            return Material.IRON_HOE;
        }
        return material;
    }

    private double cooldownSeconds() {
        return Math.max(0.0, plugin.getConfig().getDouble("gun.cooldown-seconds", 3.0));
    }

    private double range() {
        return Math.max(1.0, plugin.getConfig().getDouble("gun.range", 64.0));
    }

    private double hitRadius() {
        return Math.max(0.05, plugin.getConfig().getDouble("gun.hit-radius", 0.45));
    }

    private double particleStep() {
        return Math.max(0.05, plugin.getConfig().getDouble("gun.particle-step", 0.25));
    }

    private Particle particle() {
        String raw = plugin.getConfig().getString("gun.particle", "END_ROD");
        try {
            return Particle.valueOf(String.valueOf(raw).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Particle.END_ROD;
        }
    }

    // ---------- 状态查询 ----------

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public int secondsLeft() {
        return secondsLeft;
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

    /** 声明地盘（{@code docs/框架不变量.md} 的 I2）。 */
    public void publishRegions() {
        com.hub.region.Regions.begin("quark");
        if (arena != null) {
            com.hub.region.Regions.box("quark", "", arena.getPos1(), arena.getPos2());
        }
    }

    public QuarkArena arena() {
        return arena;
    }

    public boolean isGun(ItemStack item) {
        return item != null && item.getType() == gunMaterial() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(gunKey, PersistentDataType.BYTE);
    }

    // ---------- 等待 / 进出 ----------

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
        scores.put(id, 0);
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        resetPlayer(player);
        player.teleport(arena.getLobby());
        HubAPI.giveHubItem(player);
        HubAPI.setGame(id, GAME_NAME);
        HubAPI.announceJoin(plugin.getName(), player.getName());
        Chat.send(player, "&a已加入" + GAME_NAME + "等待队列 &7(" + waiting.size() + "/" + maxPlayers() + ")");
        Chat.send(player, "&7人数达到 &f" + minPlayers() + " &7后自动倒计时开始。");
        updateCountdown();
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
        cleanupPlayer(id);
        player.setCooldown(gunMaterial(), 0);
        if (!silent) {
            Chat.send(player, wasPlaying ? "&e你离开了对局。" : "&e你离开了等待队列。");
        }
        HubAPI.clearGame(id);
        if (player.isDead()) {
            player.spigot().respawn();
        }
        restorePlayer(player);
        if (!silent) {
            HubAPI.teleportToLobby(player);
        }
        if (state == State.RUNNING && wasPlaying && playing.size() <= 1) {
            endGame("人数不足");
        } else if (state == State.WAITING) {
            updateCountdown();
        }
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        boolean wasWaiting = waiting.remove(id);
        boolean wasPlaying = playing.remove(id);
        if (!wasWaiting && !wasPlaying) {
            return;
        }
        cleanupPlayer(id);
        if (state == State.RUNNING && wasPlaying && playing.size() <= 1) {
            endGame("人数不足");
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
        playing.addAll(ready);
        scores.clear();
        for (UUID id : playing) {
            scores.put(id, 0);
        }
        secondsLeft = gameSeconds();
        long until = System.currentTimeMillis() + invulnerableTicks() * 50L;
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            prepareForRound(p);
            invulnerableUntil.put(id, until);
            HubAPI.setPlaying(id, GAME_NAME);
            HubAPI.setArenaSpectator(GAME_NAME, "", arena().getLobby());
            Chat.title(p, "&b&l" + GAME_NAME, "&f击杀最多的玩家获胜", 2);
        }
        broadcastGame("&b[" + GAME_NAME + "] &f游戏开始！&e" + secondsLeft
                + " 秒&f内击杀玩家得分，结束时分最高者获胜。");
        updateActionBar();
        gameTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGame, 20L, 20L);
    }

    public void endGame(String reason) {
        if (state != State.RUNNING) {
            return;
        }
        cancelGameTask();
        liveSidebar.hide("quark");
        state = State.WAITING;
        secondsLeft = 0;

        List<UUID> ranking = new ArrayList<>(playing);
        ranking.sort(Comparator.comparingInt((UUID id) -> scores.getOrDefault(id, 0)).reversed());
        int topScore = ranking.isEmpty() ? 0 : scores.getOrDefault(ranking.get(0), 0);
        List<String> winners = new ArrayList<>();
        if (topScore > 0) {
            for (UUID id : ranking) {
                if (scores.getOrDefault(id, 0) == topScore) {
                    winners.add(playerName(id));
                }
            }
        }

        broadcastGame("&b[" + GAME_NAME + "] &f游戏结束！"
                + (reason == null ? "" : " &7(" + reason + ")"));
        if (topScore <= 0) {
            broadcastGame("&7本局没有产生击杀。");
        } else if (winners.size() == 1) {
            broadcastGame("&6获胜者：&e" + winners.get(0) + "&7（" + topScore + " 分）");
        } else {
            broadcastGame("&6并列第一：&e" + String.join("、", winners) + "&7（各 " + topScore + " 分）");
        }
        if (ranking.size() > 1) {
            StringBuilder line = new StringBuilder("&7排名：");
            int shown = 0;
            for (UUID id : ranking) {
                if (shown >= 5) {
                    break;
                }
                if (shown > 0) {
                    line.append(" &8| ");
                }
                line.append("&f").append(shown + 1).append(". ")
                        .append(playerName(id)).append(" &e")
                        .append(scores.getOrDefault(id, 0));
                shown++;
            }
            broadcastGame(line.toString());
        }
        List<UUID> firsts = new ArrayList<>();
        List<UUID> seconds = new ArrayList<>();
        List<UUID> thirds = new ArrayList<>();
        int index = 0;
        if (topScore > 0) {
            while (index < ranking.size()
                    && scores.getOrDefault(ranking.get(index), 0) == topScore) {
                firsts.add(ranking.get(index++));
            }
            if (index < ranking.size()) {
                int secondScore = scores.getOrDefault(ranking.get(index), 0);
                if (secondScore > 0) {
                    while (index < ranking.size()
                            && scores.getOrDefault(ranking.get(index), 0) == secondScore) {
                        seconds.add(ranking.get(index++));
                    }
                }
            }
            if (index < ranking.size()) {
                int thirdScore = scores.getOrDefault(ranking.get(index), 0);
                if (thirdScore > 0) {
                    while (index < ranking.size()
                            && scores.getOrDefault(ranking.get(index), 0) == thirdScore) {
                        thirds.add(ranking.get(index++));
                    }
                }
            }
        }
        Top3.broadcast(msg -> broadcastGame(msg), this::playerName, firsts, seconds, thirds);
        Top3.award(data, firsts, seconds, thirds);
        saveData();

        for (UUID id : ranking) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                if (topScore > 0 && scores.getOrDefault(id, 0) == topScore) {
                    Chat.title(p, "&6&l获胜！", "&f得分 " + topScore, 2);
                } else {
                    Chat.title(p, "&c游戏结束", "&f本局得分 " + scores.getOrDefault(id, 0), 2);
                }
            }
        }

        List<UUID> ids = new ArrayList<>(playing);
        playing.clear();
        for (UUID id : ids) {
            cleanupPlayer(id);
            HubAPI.clearGame(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                if (p.isDead()) {
                    p.spigot().respawn();
                }
                restorePlayer(p);
                HubAPI.teleportToLobby(p);
            }
        }
        scores.clear();
        invulnerableUntil.clear();
        lastKiller.clear();
        lastShotMs.clear();
        updateCountdown();
    }

    private void updateCountdown() {
        if (state != State.WAITING) {
            countdown.cancel();
            return;
        }
        countdown.update();
    }

    // ---------- 每 tick / 每局 ----------

    private void tickGame() {
        if (state != State.RUNNING) {
            cancelGameTask();
            return;
        }
        secondsLeft--;
        if (secondsLeft <= 0) {
            endGame("时间到");
            return;
        }
        updateActionBar();
        updateLiveBoard();
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
        online.sort(Comparator.comparingInt((UUID id) -> scores.getOrDefault(id, 0)).reversed()
                .thenComparing(this::playerName, String.CASE_INSENSITIVE_ORDER));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        for (UUID id : online) {
            lines.add(new LiveSidebar.Line("§e" + playerName(id), scores.getOrDefault(id, 0)));
        }
        liveSidebar.update("quark", "&b&l粒子枪战 · 排名", online, lines);
    }

    /** 只发给本局玩家，大厅/其他世界看不到局内播报。 */
    private void broadcastGame(String message) {
        for (UUID id : new ArrayList<>(playing)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.send(p, message);
            }
        }
    }

    private void updateActionBar() {
        if (state != State.RUNNING) {
            return;
        }
        String top = topText();
        for (UUID id : playing) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            Chat.actionBar(p, "&e剩余 &f" + secondsLeft + "s &7| &a你的击杀 &f"
                    + scores.getOrDefault(id, 0) + " &7| &b" + top);
        }
    }

    private String topText() {
        UUID best = null;
        int bestScore = 0;
        for (UUID id : playing) {
            int score = scores.getOrDefault(id, 0);
            if (score > bestScore) {
                bestScore = score;
                best = id;
            }
        }
        if (best == null) {
            return "暂无击杀";
        }
        return playerName(best) + " " + bestScore;
    }

    private void cancelGameTask() {
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
    }

    // ---------- 射击 / 击杀 ----------

    public void shoot(Player shooter) {
        if (state != State.RUNNING || !playing.contains(shooter.getUniqueId())
                || shooter.isDead() || shooter.getHealth() <= 0.0) {
            return;
        }
        // 右键方块/实体时服务端可能在同一 tick 同时派发两个事件，去重避免连发
        int tick = shooter.getTicksLived();
        UUID shooterId = shooter.getUniqueId();
        if (lastShotTick.getOrDefault(shooterId, Integer.MIN_VALUE) == tick) {
            return;
        }
        lastShotTick.put(shooterId, tick);
        long now = System.currentTimeMillis();
        long cooldownMs = (long) (cooldownSeconds() * 1000.0);
        long last = lastShotMs.getOrDefault(shooter.getUniqueId(), 0L);
        if (now - last < cooldownMs) {
            Chat.actionBar(shooter, "&c枪械冷却中 &f" + format((cooldownMs - (now - last)) / 1000.0) + "s");
            return;
        }
        lastShotMs.put(shooter.getUniqueId(), now);
        shooter.setCooldown(gunMaterial(), (int) Math.round(cooldownSeconds() * 20.0));
        shooter.getWorld().playSound(shooter.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.6f, 1.6f);

        Location eye = shooter.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        World world = eye.getWorld();
        Particle particle = particle();
        double step = particleStep();

        Location hitPoint = null;
        Player hitPlayer = null;
        for (double distance = 0.6; distance <= range(); distance += step) {
            Location point = eye.clone().add(direction.clone().multiply(distance));
            if (!world.equals(point.getWorld())) {
                break;
            }
            if (!point.getBlock().isPassable()) {
                world.spawnParticle(particle, point, 6, 0.05, 0.05, 0.05, 0.0);
                world.playSound(point, Sound.BLOCK_STONE_HIT, 0.5f, 1.2f);
                hitPoint = point;
                break;
            }
            world.spawnParticle(particle, point, 1, 0.0, 0.0, 0.0, 0.0);
            Player target = findHit(shooter, point);
            if (target != null) {
                hitPoint = point;
                hitPlayer = target;
                break;
            }
        }
        if (hitPoint != null) {
            world.spawnParticle(Particle.CRIT, hitPoint, 12, 0.15, 0.15, 0.15, 0.05);
        }
        if (hitPlayer != null) {
            kill(shooter, hitPlayer);
        }
    }

    private Player findHit(Player shooter, Location point) {
        for (UUID id : playing) {
            if (id.equals(shooter.getUniqueId())) {
                continue;
            }
            Player target = Bukkit.getPlayer(id);
            if (target == null || !target.isOnline() || target.isDead() || target.getHealth() <= 0.0) {
                continue;
            }
            if (!target.getWorld().equals(point.getWorld())) {
                continue;
            }
            if (isInvulnerable(id)) {
                continue;
            }
            if (target.getBoundingBox().expand(hitRadius()).contains(point.toVector())) {
                return target;
            }
        }
        return null;
    }

    private void kill(Player killer, Player victim) {
        if (!playing.contains(victim.getUniqueId()) || isInvulnerable(victim.getUniqueId())
                || victim.isDead() || victim.getHealth() <= 0.0) {
            return;
        }
        int score = scores.merge(killer.getUniqueId(), 1, Integer::sum);
        lastKiller.put(victim.getUniqueId(), killer.getUniqueId());
        victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1.0, 0),
                30, 0.3, 0.6, 0.3, 0.15);
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1.0f, 1.0f);
        broadcastGame("&b[" + GAME_NAME + "] &f" + killer.getName() + " &7击杀了 &f"
                + victim.getName() + " &7(&e" + score + " 分&7)");
        Chat.title(killer, "&a&l击杀！", "&f" + victim.getName() + " &7| &e当前 " + score + " 分", 1);
        updateActionBar();
        victim.setHealth(0.0);
    }

    public void handleDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID id = victim.getUniqueId();
        if (!playing.contains(id)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        UUID killerId = lastKiller.remove(id);
        if (killerId != null) {
            // 击杀播报已经在 kill() 里发过全服了，这里只压掉原版的那条
            event.deathMessage(null);
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (victim.isOnline() && playing.contains(id)) {
                victim.spigot().respawn();
            }
        });
    }

    public void handleRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!playing.contains(id)) {
            return;
        }
        Location spawn = arena.randomSpawn();
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }
        invulnerableUntil.put(id, System.currentTimeMillis() + invulnerableTicks() * 50L);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !playing.contains(id)) {
                return;
            }
            player.setGameMode(GameMode.SURVIVAL);
            resetPlayer(player);
            player.getInventory().clear();
            player.getInventory().setItem(0, createGun());
            player.getInventory().setHeldItemSlot(0);
            player.setCooldown(gunMaterial(), 0);
            lastShotMs.remove(id);
            HubAPI.setPlaying(id, GAME_NAME);
        });
    }

    // ---------- 掉落 / 场地 ----------

    public void handleMove(org.bukkit.event.player.PlayerMoveEvent event) {
        if (state != State.RUNNING) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!playing.contains(id) || event.getTo() == null) {
            return;
        }
        if (event.getFrom().getWorld() != null && event.getTo().getWorld() != null
                && !event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            return;
        }
        if (event.getTo().getY() < arena.voidY() || !arena.inside(event.getTo())) {
            Location spawn = arena.randomSpawn();
            if (spawn != null) {
                player.teleport(spawn);
                invulnerableUntil.put(id, System.currentTimeMillis() + invulnerableTicks() * 50L);
                Chat.title(player, "&c离开场地", "&f已传送回出生点", 1);
            }
        }
    }

    // ---------- 内部工具 ----------

    private void prepareForRound(Player player) {
        Location spawn = arena.randomSpawn();
        if (spawn != null) {
            player.teleport(spawn);
        }
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        resetPlayer(player);
        player.getInventory().setItem(0, createGun());
        player.getInventory().setHeldItemSlot(0);
        player.setCooldown(gunMaterial(), 0);
        lastShotMs.remove(player.getUniqueId());
    }

    private void resetPlayer(Player player) {
        for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
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
        player.setCooldown(gunMaterial(), 0);
    }

    private void cleanupPlayer(UUID id) {
        scores.remove(id);
        invulnerableUntil.remove(id);
        lastKiller.remove(id);
        lastShotMs.remove(id);
        lastShotTick.remove(id);
    }

    private ItemStack createGun() {
        ItemStack item = new ItemStack(gunMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.noItalic(Chat.color(
                plugin.getConfig().getString("gun.name", "&b&l粒子手枪 &7(右键射击)"))));
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("gun.lore")) {
            lore.add(Chat.noItalic(Chat.color(line)));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(gunKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isInvulnerable(UUID id) {
        return invulnerableUntil.getOrDefault(id, 0L) > System.currentTimeMillis();
    }

    private String playerName(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? "未知" : name;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private void saveData() {
        if (data == null) {
            return;
        }
        try {
            PlayerStats.save("quark", data);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存玩家记录失败：" + ex.getMessage());
        }
    }
}
