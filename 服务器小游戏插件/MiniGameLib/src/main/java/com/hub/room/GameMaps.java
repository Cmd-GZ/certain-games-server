package com.hub.room;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图登记表（{@code plugins/MiniGameLib/maps.yml}）的读取与查询。
 *
 * <p>单一事实源：一张地图需要哪些世界、每个世界里的区域与规则，只写在这里。
 * 游戏侧 {@code init} 从 {@link GameMap} 读数据，**具体的开局动作仍由游戏自己写**。
 */
public final class GameMaps {

    private static final Map<String, Map<String, GameMap>> BY_GAME = new LinkedHashMap<>();

    private GameMaps() {
    }

    /** 从数据目录读取（首次自动从 jar 复制）。 */
    @SuppressWarnings("unused")
    static void load(JavaPlugin plugin) {
        BY_GAME.clear();
        File f = new File(plugin.getDataFolder(), "maps.yml");
        if (!f.isFile()) {
            plugin.saveResource("maps.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection maps = cfg.getConfigurationSection("maps");
        if (maps == null) {
            plugin.getLogger().warning("[地图] maps.yml 里没有 maps: 段");
            return;
        }
        for (String game : maps.getKeys(false)) {
            ConfigurationSection gm = maps.getConfigurationSection(game);
            if (gm == null) {
                continue;
            }
            Map<String, GameMap> byName = new LinkedHashMap<>();
            for (String mapName : gm.getKeys(false)) {
                ConfigurationSection m = gm.getConfigurationSection(mapName);
                if (m == null) {
                    continue;
                }
                List<GameMap.WorldPlan> plans = new ArrayList<>();
                for (Map<?, ?> raw : m.getMapList("worlds")) {
                    String tpl = str(raw.get("template"));
                    if (tpl == null || tpl.isBlank()) {
                        continue;
                    }
                    WorldTemplate.Rules rules = rules(raw.get("rules"), WorldTemplate.Rules.defaults());
                    List<GameMap.RegionPlan> regions = new ArrayList<>();
                    Object rr = raw.get("regions");
                    if (rr instanceof List<?> list) {
                        for (Object one : list) {
                            if (!(one instanceof Map<?, ?> r)) {
                                continue;
                            }
                            Object boxObj = r.get("box");
                            Map<?, ?> box = boxObj instanceof Map<?, ?> bmp ? bmp : r;
                            regions.add(new GameMap.RegionPlan(
                                    str(r.get("name")) == null ? "" : str(r.get("name")),
                                    dbl(box.get("x1")), dbl(box.get("y1")), dbl(box.get("z1")),
                                    dbl(box.get("x2")), dbl(box.get("y2")), dbl(box.get("z2")),
                                    r.get("rules") == null ? null : rules(r.get("rules"), rules),
                                    str(r.get("game"))));
                        }
                    }
                    plans.add(new GameMap.WorldPlan(tpl, rules, regions));
                }
                GameMap.Entry entry = null;
                ConfigurationSection es = m.getConfigurationSection("entry");
                if (es != null) {
                    // yaw/pitch 可选（缺省 0 = 朝南平视），老地图定义不用改
                    entry = new GameMap.Entry(es.getString("template", ""),
                            es.getDouble("x"), es.getDouble("y"), es.getDouble("z"),
                            (float) es.getDouble("yaw", 0.0), (float) es.getDouble("pitch", 0.0));
                }
                java.util.Map<String, String> policy = new java.util.LinkedHashMap<>();
                var polSec = m.getConfigurationSection("policy");
                if (polSec != null) {
                    for (String pk : polSec.getKeys(false)) {
                        policy.put(pk, String.valueOf(polSec.get(pk)));
                    }
                }
                byName.put(mapName, new GameMap(game, mapName, plans, entry, policy));
            }
            if (!byName.isEmpty()) {
                BY_GAME.put(game, byName);
            }
        }
        plugin.getLogger().info("[地图] 已登记 " + BY_GAME.size() + " 个游戏 / "
                + BY_GAME.values().stream().mapToInt(Map::size).sum() + " 张地图");
    }

    /**
     * 把一张**子小游戏**的地图**套壳**成"只有一个游戏的派对"地图。
     *
     * <p>为什么需要：子小游戏被单独打开时，按 D15 应当走**派对**的流程（{@code PARTY_OF_ONE}），
     * 房间的拥有者永远是派对。所以这里把
     * {@code maps.<子游戏>.<地图>} 转成
     * {@code game = "arcadeparty"}、区域标注原属子游戏的同一份世界计划。
     *
     * @param src 子小游戏自己的地图定义
     * @return 套壳后的地图（房间会以 arcadeparty 的身份创建）
     */
    public static GameMap wrapAsParty(GameMap src) {
        if (src == null) {
            return null;
        }
        List<GameMap.WorldPlan> worlds = new ArrayList<>();
        for (GameMap.WorldPlan wp : src.worlds()) {
            List<GameMap.RegionPlan> tagged = new ArrayList<>();
            for (GameMap.RegionPlan rp : wp.regions()) {
                // 区域记住"它属于哪个子游戏"，派对换关/开局就靠这个找场地
                tagged.add(new GameMap.RegionPlan(rp.name(), rp.x1(), rp.y1(), rp.z1(),
                        rp.x2(), rp.y2(), rp.z2(), rp.rules(), src.game()));
            }
            worlds.add(new GameMap.WorldPlan(wp.templateKey(), wp.rules(), tagged));
        }
        return new GameMap("arcadeparty", src.game() + ":" + src.name(), worlds, src.entry());
    }

    /** 某张地图的定义；没有返回 null。 */
    public static GameMap get(String game, String map) {
        Map<String, GameMap> byName = BY_GAME.get(game);
        return byName == null ? null : byName.get(map);
    }

    /** 某个游戏登记了哪些地图（给 UI / 补全用）。 */
    public static List<String> mapNames(String game) {
        Map<String, GameMap> byName = BY_GAME.get(game);
        return byName == null ? List.of() : new ArrayList<>(byName.keySet());
    }

    /** 已登记的游戏清单。 */
    public static java.util.List<String> games() {
        return new ArrayList<>(BY_GAME.keySet());
    }

    /** 已登记的游戏数 / 地图总数（doctor 用）。 */
    public static int gameCount() {
        return BY_GAME.size();
    }

    public static int mapCount() {
        return BY_GAME.values().stream().mapToInt(Map::size).sum();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static WorldTemplate.Rules rules(Object raw, WorldTemplate.Rules def) {
        if (!(raw instanceof Map<?, ?> m)) {
            return def;
        }
        return new WorldTemplate.Rules(
                m.get("pvp") instanceof Boolean b ? b : def.pvp(),
                m.get("difficulty") == null ? def.difficulty() : String.valueOf(m.get("difficulty")),
                m.get("mob-spawning") instanceof Boolean mb ? mb : def.mobSpawning(),
                m.get("keep-inventory") instanceof Boolean kb ? kb : def.keepInventory(),
                m.get("fixed-time") instanceof Number n ? n.longValue() : def.fixedTime());
    }
}
