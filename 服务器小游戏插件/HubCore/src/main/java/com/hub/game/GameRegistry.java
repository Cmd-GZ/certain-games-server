package com.hub.game;

import com.hub.HubCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * 游戏注册表。
 * <p>
 * 登记来源（按优先级，先登记的先显示）：
 * <ol>
 *   <li>HubCore 配置 games 段（name / alias / color）；</li>
 *   <li>自动扫描 plugins 目录下所有 jar 的 plugin.yml，
 *       凡是带 {@code minigame-game: true} 或 {@code minigame:} 段的插件自动登记为小游戏；</li>
 *   <li>运行时通过 {@link com.hub.api.HubAPI#registerGame} 注册。</li>
 * </ol>
 * 接入标记用中性的 {@code minigame} 命名：它只表示"我是小游戏插件"，
 * 不跟着主城插件改名而变（主城插件曾叫 GameHub，现在的标记不会再随之失效）。
 */
public class GameRegistry {

    private final HubCorePlugin plugin;
    private final Map<String, GameInfo> byId = new LinkedHashMap<>();   // id -> 游戏
    private final Map<String, String> byName = new LinkedHashMap<>();   // 显示名/别名 -> id
    /** 小写游戏 key（maps.yml / games.yml / players.yml 用的那套）→ 注册表 id（显示名）。 */
    private final Map<String, String> byKey = new java.util.LinkedHashMap<>();

    /** 玩家 key 映射（同时同步进 byKey）。 */
    /** scanPluginsFolder 阶段先记 key→插件名，等 register() 完成后回填 key→id。 */
    private final Map<String, String> pendingKeys = new java.util.LinkedHashMap<>();

    private final Map<String, String> byPlugin = new LinkedHashMap<>(); // 插件名 -> id（插件用自己的插件名就能引用到游戏）

    public GameRegistry(HubCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** 重新扫描：先读配置里的 games，再扫 plugins 目录（注册表每次整体重建）。 */
    public synchronized void rescan() {
        byId.clear();
        byName.clear();
        byPlugin.clear();
        registerFromConfig();
        pendingKeys.clear();
        scanPluginsFolder();
        // 回填：把"key → 插件名"解析成"key → 注册表 id"
        for (Map.Entry<String, String> e : pendingKeys.entrySet()) {
            String id = canonicalName(e.getValue());
            if (id != null) {
                byKey.put(e.getKey(), id);
            }
        }
        pendingKeys.clear();
        plugin.getLogger().info("HubCore 已登记 " + byId.size() + " 个游戏：" + String.join(", ", byId.keySet()));
    }

    /** 从 HubCore 配置的 games 段登记游戏（同时是中央菜单的数据源）。 */
    private void registerFromConfig() {
        for (Map<?, ?> raw : plugin.getConfig().getMapList("games")) {
            Object nameObj = raw.get("name");
            String name = nameObj == null ? "游戏" : String.valueOf(nameObj);
            Object iconObj = raw.get("icon");
            String icon = iconObj == null ? "GRASS_BLOCK" : String.valueOf(iconObj);
            Object colorObj = raw.get("color");
            char color = parseColor(colorObj == null ? "" : String.valueOf(colorObj));
            Object aliasObj = raw.get("alias");
            String alias = aliasObj == null ? name : String.valueOf(aliasObj);
            register(new GameInfo(name, name, icon, color), alias);
            // 配置登记的游戏也要能被"插件名"引用到：jar 文件名与显示名常常不同
            // （如 jar `DuelArena.jar` / 显示名 `单挑 PvP` / 别名 `决斗`），这里把显示名和别名
            // 一起记进 byPlugin；jar 名（= 插件名）由下面的扫描补上，
            // 这样"插件用自己的插件名引用自己"在配置登记优先时也不会失效。
            mapPluginAlias(name, name);
            mapPluginAlias(alias, name);
        }
    }

    /**
     * 把一个"插件名可能的写法"（显示名 / 配置里的别名，通常就是命令词）映射到已登记的游戏。
     * <p>仅当这个写法不是别的游戏的 id / 显示名时才写，避免抢掉已有映射；
     * 写进来的都是原本查不到的名字，只会让 {@link #get} 从"查不到"变成"查得到"。
     */
    private void mapPluginAlias(String pluginName, String gameId) {
        if (pluginName == null || pluginName.isEmpty()) {
            return;
        }
        if (byId.containsKey(pluginName) || byName.containsKey(pluginName)) {
            return;
        }
        byPlugin.put(pluginName, gameId);
    }

    /**
     * 扫描 plugins 目录下所有 jar，读取各自 plugin.yml，
     * 发现 {@code minigame-game: true}（或存在 {@code minigame:} 段）的小游戏插件并登记。
     */
    private void scanPluginsFolder() {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        File[] jars = pluginsDir == null ? null : pluginsDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null) {
            return;
        }
        for (File jar : jars) {
            if (jar.getName().equalsIgnoreCase(plugin.getName() + ".jar")) {
                continue; // 跳过自己
            }
            String pluginName;
            String display;
            String icon;
            String color;
            try (JarFile jf = new JarFile(jar)) {
                ZipEntry entry = jf.getJarEntry("plugin.yml");
                if (entry == null) {
                    continue;
                }
                try (InputStream in = jf.getInputStream(entry)) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new StringReader(content));
                    ConfigurationSection mg = cfg.getConfigurationSection("minigame");
                    if (!cfg.getBoolean("minigame-game", false) && mg == null) {
                        continue; // 不是标记过的小游戏插件
                    }
                    pluginName = cfg.getString("name", jar.getName());
                    if (mg != null) {
                        display = mg.getString("name", pluginName);
                        String key = mg.getString("key", "");
                        if (key != null && !key.isBlank()) {
                            synchronized (this) {
                                byKey.put(key.toLowerCase(java.util.Locale.ROOT), null);
                                pendingKeys.put(key.toLowerCase(java.util.Locale.ROOT), pluginName);
                            }
                        }
                        icon = mg.getString("icon", "GRASS_BLOCK");
                        color = mg.getString("color", "");
                    } else {
                        display = pluginName;
                        icon = "GRASS_BLOCK";
                        color = "";
                    }
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("读取插件 " + jar.getName() + " 的 plugin.yml 失败：" + ex.getMessage());
                continue;
            }
            // 只登记已加载且启用的插件（防止把停用的插件也显示出来）
            if (Bukkit.getPluginManager().getPlugin(pluginName) == null) {
                plugin.getLogger().info("跳过未加载的小游戏插件：" + pluginName);
                continue;
            }
            register(new GameInfo(pluginName, display, icon, parseColor(color)));
            // 即使显示名和配置里的游戏重名（登记被跳过，此时 byId 里没有 pluginName），
            // 也要记下"插件名 -> 已注册游戏"：插件用自己的插件名调
            // announceJoin(plugin.getName(), ...) / setGame 时才查得到显示名与颜色。
            GameInfo actual = get(display) == null ? get(pluginName) : get(display);
            if (actual != null) {
                byPlugin.put(pluginName, actual.id());
            }
        }
    }

    /** 登记一个游戏。id 或显示名重复时跳过（配置优先，已存在的同名游戏不会被覆盖）。 */
    public synchronized void register(GameInfo info, String... aliases) {
        if (info == null || info.id().isEmpty()) {
            return;
        }
        if (byId.containsKey(info.id()) || byName.containsKey(info.displayName())) {
            return;
        }
        byId.put(info.id(), info);
        byName.put(info.displayName(), info.id());
        for (String a : aliases) {
            if (a != null && !a.isEmpty() && !a.equals(info.displayName())) {
                byName.putIfAbsent(a, info.id());
            }
        }
    }

    /** 按 id、显示名 / 别名或插件名查找游戏，未找到返回 null。 */
    public synchronized GameInfo get(String idOrName) {
        if (idOrName == null) {
            return null;
        }
        GameInfo info = byId.get(idOrName);
        if (info != null) {
            return info;
        }
        String id = byName.get(idOrName);
        if (id == null) {
            id = byPlugin.get(idOrName);
        }
        return id == null ? null : byId.get(id);
    }

    /** 把插件传入的任意名字归一化为注册的显示名；未注册则原样返回（兜底分区）。 */
    public synchronized String canonicalName(String idOrName) {
        GameInfo info = get(idOrName);
        return info == null ? idOrName : info.displayName();
    }

    /** 当前登记的全部游戏（保持登记顺序）。 */
    /** 这个显示名对应的**小写游戏 key**（maps.yml / games.yml 那套）；没有就 null。 */
    public synchronized String keyOf(String idOrDisplay) {
        String id = canonicalName(idOrDisplay);
        if (id == null) {
            return null;
        }
        for (Map.Entry<String, String> e : byKey.entrySet()) {
            if (id.equals(e.getValue())) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 小写游戏 key → 注册表 id（显示名）；没有就 null。 */
    public synchronized String displayOfKey(String key) {
        return key == null ? null : byKey.get(key.toLowerCase(java.util.Locale.ROOT));
    }

    /** 所有登记过 key 的游戏（小写 key）。 */
    public synchronized java.util.Set<String> keys() {
        return new java.util.LinkedHashSet<>(byKey.keySet());
    }

    public synchronized List<GameInfo> getGames() {
        return new ArrayList<>(byId.values());
    }

    private static char parseColor(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        char c = Character.toLowerCase(s.charAt(0));
        if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
            return c;
        }
        return 0;
    }
}
