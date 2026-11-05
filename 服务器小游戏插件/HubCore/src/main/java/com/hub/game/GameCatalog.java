package com.hub.game;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **游戏目录**（{@code docs/框架不变量.md} 的 <b>R1 单一事实源</b>）。
 *
 * <p>一个游戏的定义现在散在四处：`plugin.yml`（key / 显示名 / 图标 / 契约）、
 * `maps.yml`（有哪些地图）、`games.yml`（join/start 命令）、派对配置（子游戏名单 + 语言名）。
 * 本类把它们**合成一份只读视图**，供菜单、doctor、派对查询 —— 目的不是再造一份数据，
 * 而是让"哪里不一致"能被一处看出来（{@link #drift()}）。
 *
 * <p>只读：不写任何配置；数据源变了就重新 {@link #snapshot()} 一次。
 */
public final class GameCatalog {

    /** 一个游戏在目录里的样子。 */
    public record Entry(String key, String display, String icon, char color,
                        List<String> maps, String joinCommand, String startCommand,
                        boolean partySubGame, int contract) {
    }

    private final JavaPlugin plugin;
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> drift = new ArrayList<>();
    /** 不算问题的提示（例如 SkyPit 按 D26 没有地图、Quark 上游未建场）。 */
    private final List<String> notes = new ArrayList<>();

    public GameCatalog(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 重新汇总一次（启动时、`/hub doctor` 时调用）。 */
    public synchronized void snapshot() {
        entries.clear();
        drift.clear();
        notes.clear();
        GameRegistry reg = ((com.hub.HubCorePlugin) plugin).getRegistry();
        ConfigurationSection games = gamesSection();
        ConfigurationSection partyCfg = partySection();
        Map<String, List<String>> mapsByGame = mapsByGame();

        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(reg.keys());
        keys.addAll(mapsByGame.keySet());
        if (games != null) {
            keys.addAll(games.getKeys(false));
        }

        for (String key : keys) {
            String display = reg.displayOfKey(key);
            if (display == null) {
                display = key;
                drift.add(key + "：没有插件登记（maps.yml/games.yml 里有，plugin.yml 里没有）");
            }
            GameInfo info = reg.get(display);
            List<String> maps = mapsByGame.getOrDefault(key, List.of());
            String join = games == null ? "" : games.getString(key + ".join-command", "");
            String start = games == null ? "" : games.getString(key + ".start-command", "");
            if ((join == null || join.isBlank()) && partyCfg != null) {
                // 派对子游戏的 join/start 命令写在 **ArcadeParty 的配置**里，不在 HubCore 的 games.yml
                var pg = partyCfg.getConfigurationSection("games." + key);
                if (pg != null) {
                    join = pg.getString("join-command", "");
                    start = pg.getString("start-command", "");
                }
            }
            boolean sub = com.hub.room.PartyGames.isSubGame(key)
                    || (partyCfg != null && partyCfg.isConfigurationSection("games." + key));
            int contract = com.hub.api.Contract.declaredByKey(key);

            if (!maps.isEmpty() && (join == null || join.isBlank())) {
                drift.add(key + "：有地图但没有 join-command（造得出房间、进不去）");
            }
            if (maps.isEmpty()) {
                notes.add(key + "：没有地图（造不出房间）");
            }
            entries.add(new Entry(key, display, info == null ? "GRASS_BLOCK" : info.icon(),
                    info == null ? 0 : info.color(), maps, join == null ? "" : join,
                    start == null ? "" : start, sub, contract));
        }
    }

    /** 全部游戏（按 key 排序，稳定输出）。 */
    public synchronized List<Entry> entries() {
        List<Entry> copy = new ArrayList<>(entries);
        copy.sort(java.util.Comparator.comparing(Entry::key));
        return copy;
    }

    /** 汇总出来的不一致（空 = 干净）。 */
    public synchronized List<String> drift() {
        return new ArrayList<>(drift);
    }

    /** 不算问题的提示（SkyPit 无地图、Quark 上游未建场之类）。 */
    public synchronized List<String> notes() {
        return new ArrayList<>(notes);
    }

    /** 某个 key 的条目（没有则 null）。 */
    public synchronized Entry entry(String key) {
        for (Entry e : entries) {
            if (e.key().equalsIgnoreCase(key)) {
                return e;
            }
        }
        return null;
    }

    /** 给 doctor 的一行摘要。 */
    public synchronized String summary() {
        int withMaps = 0;
        int subGames = 0;
        for (Entry e : entries) {
            if (!e.maps().isEmpty()) {
                withMaps++;
            }
            if (e.partySubGame()) {
                subGames++;
            }
        }
        return entries.size() + " 个游戏（有地图 " + withMaps + "，派对子游戏 " + subGames
                + "，不一致 " + drift.size() + " 条）";
    }

    // ---------- 数据源 ----------

    private ConfigurationSection gamesSection() {
        java.io.File f = new java.io.File(plugin.getDataFolder(), "games.yml");
        if (!f.isFile()) {
            return null;
        }
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        // 注意：games.yml 的结构是 `games: {<key>: {join-command…}}`，**不是**平铺的
        return yaml.getConfigurationSection("games");
    }

    /** ArcadeParty 的配置（子游戏的 join/start 命令与语言名在这里）。 */
    private ConfigurationSection partySection() {
        java.io.File f = new java.io.File(plugin.getDataFolder().getParentFile(),
                "ArcadeParty/config.yml");
        if (!f.isFile()) {
            return null;
        }
        return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
    }

    /** maps.yml：游戏 → 地图名列表。 */
    private Map<String, List<String>> mapsByGame() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        java.io.File f = new java.io.File(plugin.getDataFolder().getParentFile(),
                "MiniGameLib/maps.yml");
        if (!f.isFile()) {
            return out;
        }
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        ConfigurationSection maps = yaml.getConfigurationSection("maps");
        if (maps == null) {
            return out;
        }
        for (String game : maps.getKeys(false)) {
            ConfigurationSection g = maps.getConfigurationSection(game);
            out.put(game, g == null ? List.of() : new ArrayList<>(g.getKeys(false)));
        }
        return out;
    }
}
