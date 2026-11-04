package com.tntbattle;

import com.hub.api.HubAPI;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.TNTPrimed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 多场地协调器：维护场地表 / 场对局实例 / 玩家归属，并路由命令与事件。
 *
 * <p>每个场地常驻一个 {@link TNTBattleManager} 实例；本类只负责全局内容
 * （场地注册、战绩、排行榜所在的“主场地”、道具池配置、旧配置迁移、
 * 背景音乐录制入口）以及“玩家 → 场地”的映射。
 */
public final class TNTBattleCore {

    public static final String GAME = TNTBattleManager.GAME;
    public static final String DEFAULT_ARENA = "双岛";

    private final TNTBattlePlugin plugin;
    private final Map<String, TNTBattleArena> arenas = new LinkedHashMap<>();
    private final Map<String, TNTBattleManager> matches = new LinkedHashMap<>();
    private final Map<UUID, TNTBattleManager> playerMatch = new LinkedHashMap<>();
    private TNTBattleManager primary;

    public TNTBattleCore(TNTBattlePlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- 生命周期 ----------

    public void load() {
        ensureArenaConfig();
        TNTBattleManager.loadSharedStats(plugin);
        List<String> names = arenaNames();
        for (String name : names) {
            TNTBattleArena arena = new TNTBattleArena(plugin, name);
            TNTBattleManager manager = new TNTBattleManager(plugin, this, arena);
            arenas.put(name, arena);
            matches.put(name, manager);
        }
        if (matches.isEmpty()) {
            writeEmptyArena(DEFAULT_ARENA);
            TNTBattleArena arena = new TNTBattleArena(plugin, DEFAULT_ARENA);
            arenas.put(DEFAULT_ARENA, arena);
            matches.put(DEFAULT_ARENA, new TNTBattleManager(plugin, this, arena));
        }
        primary = matches.values().iterator().next();
        primary.ensureDefaults();
        for (TNTBattleManager manager : matches.values()) {
            manager.load(manager == primary);
            // I3：本插件已无 Listener —— 事件由框架按归属派发到 TNTBattleSession 的钩子
            // I3：音乐**不再常驻监听**——录制期间由框架的 NoteRecorder 注册监听器
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::reportAll, 40L);
        plugin.getLogger().info("TNT 场地 " + matches.size() + " 个："
                + String.join("、", matches.keySet()));
    }

    public void reload() {
        plugin.reloadConfig();
        ensureArenaConfig();
        TNTBattleManager.loadSharedStats(plugin);
        primary = matches.values().stream().findFirst().orElse(null);
        if (primary != null) {
            primary.ensureDefaults();
        }
        for (TNTBattleManager manager : matches.values()) {
            manager.reload(manager == primary);
        }
        reportAll();
    }

    public void shutdown() {
        for (TNTBattleManager manager : matches.values()) {
            manager.shutdown();
        }
        TNTBattleManager.saveSharedStats(plugin);
        playerMatch.clear();
    }

    // ---------- 场地管理 ----------

    public List<String> arenaNames() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("arenas");
        if (sec == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(sec.getKeys(false));
    }

    public Collection<TNTBattleManager> matches() {
        return matches.values();
    }

    /**
     * 声明地盘（{@code docs/框架不变量.md} 的 I2）：把每个场地的边界报给框架。
     *
     * <p>1.12.1 起 TNTBattle 支持多场地（每场独立等待室/出生点），
     * 所以声明必须遍历 {@link #arenas}，每个场地一块 AABB。
     */
    public void publishRegions() {
        com.hub.region.Regions.begin("tntbattle");
        for (TNTBattleArena a : arenas.values()) {
            com.hub.region.Regions.box("tntbattle", a.name(), a.getPos1(), a.getPos2());
        }
    }

    public Collection<TNTBattleArena> arenas() {
        return arenas.values();
    }

    public TNTBattleManager match(String name) {
        return name == null ? null : matches.get(name);
    }

    public TNTBattleArena arena(String name) {
        return name == null ? null : arenas.get(name);
    }

    public TNTBattleManager primary() {
        return primary;
    }

    public boolean exists(String name) {
        return matches.containsKey(name);
    }

    /** 只有一个场时允许省略场名。 */
    public String defaultArenaName() {
        return matches.size() == 1 ? matches.keySet().iterator().next() : null;
    }

    public TNTBattleManager createArena(String name) {
        if (name == null || !name.matches("[\\p{L}\\p{N}_-]{1,24}")) {
            return null;
        }
        if (matches.containsKey(name)) {
            return null;
        }
        writeEmptyArena(name);

        TNTBattleArena arena = new TNTBattleArena(plugin, name);
        TNTBattleManager manager = new TNTBattleManager(plugin, this, arena);
        arenas.put(name, arena);
        matches.put(name, manager);
        if (primary == null) {
            primary = manager;
        }
        manager.load(manager == primary);
        if (manager == primary) {
            manager.becomePrimary();
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 TNTBattleSession 的钩子
        // I3：音乐**不再常驻监听**——录制期间由框架的 NoteRecorder 注册监听器
        reportAll();
        return manager;
    }

    private void writeEmptyArena(String name) {
        FileConfiguration cfg = plugin.getConfig();
        String path = "arenas." + name;
        cfg.set(path + ".world", "world");
        cfg.set(path + ".pos1", "");
        cfg.set(path + ".pos2", "");
        cfg.set(path + ".lobby", "");
        cfg.set(path + ".red-spawns", List.of());
        cfg.set(path + ".blue-spawns", List.of());
        cfg.set(path + ".void-y", cfg.getInt("void-y", -64));
        cfg.set(path + ".build-height-limit", cfg.getInt("build-height-limit", -1));
        cfg.set(path + ".max-players", 0);
        plugin.saveConfig();
    }

    public boolean removeArena(String name) {
        TNTBattleManager manager = matches.get(name);
        if (manager == null || manager.inUse()) {
            return false;
        }
        plugin.getConfig().set("arenas." + name, null);
        plugin.saveConfig();
        HubAPI.removeArena(GAME, name);
        matches.remove(name);
        arenas.remove(name);
        manager.dispose();
        if (primary == manager) {
            primary = matches.values().stream().findFirst().orElse(null);
            if (primary != null) {
                primary.becomePrimary();
            }
        }
        reportAll();
        return true;
    }

    public void saveAll() {
        for (TNTBattleManager manager : matches.values()) {
            manager.saveArena();
        }
    }

    public void reportAll() {
        for (TNTBattleManager manager : matches.values()) {
            manager.reportArena();
        }
    }

    // ---------- 玩家归属 ----------

    public void bind(UUID id, TNTBattleManager manager) {
        if (id != null && manager != null) {
            playerMatch.put(id, manager);
        }
    }

    public void unbind(UUID id) {
        if (id != null) {
            playerMatch.remove(id);
        }
    }

    public TNTBattleManager managerFor(UUID id) {
        TNTBattleManager manager = id == null ? null : playerMatch.get(id);
        if (manager != null) {
            return manager;
        }
        for (TNTBattleManager candidate : matches.values()) {
            if (candidate.isInGame(id)) {
                playerMatch.put(id, candidate);
                return candidate;
            }
        }
        return null;
    }

    public TNTBattleManager managerFor(Player player) {
        return player == null ? null : managerFor(player.getUniqueId());
    }

    public TNTBattleManager managerForTnt(TNTPrimed tnt) {
        for (TNTBattleManager manager : matches.values()) {
            if (manager.ownsTnt(tnt)) {
                return manager;
            }
        }
        return null;
    }

    public TNTBattleManager managerForFlame(SmallFireball fireball) {
        for (TNTBattleManager manager : matches.values()) {
            if (manager.ownsFlame(fireball)) {
                return manager;
            }
        }
        return null;
    }

    public TNTBattleManager managerForBridgeEgg(Egg egg) {
        for (TNTBattleManager manager : matches.values()) {
            if (manager.ownsBridgeEgg(egg)) {
                return manager;
            }
        }
        return null;
    }

    public TNTBattleManager ownedExplosion(Block block) {
        for (TNTBattleManager manager : matches.values()) {
            if (manager.isOwnedExplosion(block)) {
                return manager;
            }
        }
        return null;
    }

    public void leave(Player player) {
        TNTBattleManager manager = managerFor(player);
        if (manager != null) {
            manager.leave(player, true);
        } else {
            unbind(player.getUniqueId());
        }
    }

    public void quit(Player player) {
        TNTBattleManager manager = managerFor(player);
        if (manager != null) {
            manager.quit(player);
        }
        unbind(player.getUniqueId());
    }

    // ---------- 全局功能委托（排行榜/音乐以主场地为准） ----------

    public void refreshLeaderboard() {
        if (primary != null) {
            primary.refreshLeaderboard();
        }
    }

    public void onWorldLoad(World world) {
        if (primary != null) {
            primary.onWorldLoad(world);
        }
    }

    public TNTBattleMusic musicRecorder() {
        return primary == null ? null : primary.music();
    }

    public void reloadMusicAll() {
        for (TNTBattleManager manager : matches.values()) {
            manager.music().load();
        }
    }

    public int firsts(UUID id) {
        return primary == null ? 0 : primary.firsts(id);
    }

    public List<TNTBattleManager.WinEntry> leaderboard(int limit) {
        return primary == null ? List.of() : primary.leaderboard(limit);
    }

    public boolean hasLeaderboard() {
        return primary != null && primary.hasLeaderboard();
    }

    public void setLeaderboardLocation(org.bukkit.Location location) {
        if (primary != null) {
            primary.setLeaderboardLocation(location);
        }
    }

    public void removeLeaderboard() {
        if (primary != null) {
            primary.removeLeaderboard();
        }
    }

    // ---------- 配置 ----------

    /** 旧单场 `arena.*` 自动迁移为 `arenas.双岛.*`；没有任何场时补一个空场。 */
    private void ensureArenaConfig() {
        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection arenasSec = cfg.getConfigurationSection("arenas");
        boolean hasArenas = arenasSec != null && !arenasSec.getKeys(false).isEmpty();
        ConfigurationSection legacy = cfg.getConfigurationSection("arena");
        boolean changed = false;
        if (!hasArenas && legacy != null) {
            String path = "arenas." + DEFAULT_ARENA;
            for (String key : legacy.getKeys(false)) {
                cfg.set(path + "." + key, legacy.get(key));
            }
            cfg.set(path + ".void-y", cfg.getInt("void-y", -64));
            cfg.set(path + ".build-height-limit", cfg.getInt("build-height-limit", -1));
            cfg.set(path + ".max-players", 0);
            changed = true;
        }
        if (!hasArenas && legacy == null) {
            String path = "arenas." + DEFAULT_ARENA;
            cfg.set(path + ".world", "world");
            cfg.set(path + ".pos1", "");
            cfg.set(path + ".pos2", "");
            cfg.set(path + ".lobby", "");
            cfg.set(path + ".red-spawns", List.of());
            cfg.set(path + ".blue-spawns", List.of());
            cfg.set(path + ".void-y", cfg.getInt("void-y", -64));
            cfg.set(path + ".build-height-limit", cfg.getInt("build-height-limit", -1));
            cfg.set(path + ".max-players", 0);
            changed = true;
        }
        if (cfg.getInt("config-version", 0) < 8) {
            cfg.set("config-version", 8);
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
    }
}
