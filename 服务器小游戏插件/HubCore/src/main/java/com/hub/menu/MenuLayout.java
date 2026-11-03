package com.hub.menu;

import com.hub.HubCorePlugin;
import com.hub.api.GameState;
import com.hub.game.GameInfo;
import com.hub.util.Chat;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主菜单布局：由 OP 用 /hub menu edit 可视化编辑，存在 config.yml 的 menu 段。
 *
 * <pre>
 * menu:
 *   rows: 6                       # 1-6
 *   title: '&e游戏大厅'
 *   items:
 *     '13':                      # 槽位
 *       material: DIAMOND_SWORD
 *       name: '&c单挑 PvP'
 *       lore: ['&7点我进入']
 *       action: 'game:单挑 PvP'  # 见下
 *   sub:                         # 每个游戏的场地子菜单（有多个场地时点击图标进入）
 *     单挑 PvP:
 *       rows: 3
 *       title: '&e单挑 PvP 场地'
 *       items:
 *         风场:                  # 场地名（也可用槽位数字当纯装饰）
 *           material: DIAMOND_SWORD
 *           name: '&a{arena} &7({players} 人)'
 *           lore: ['{state} {extra}']
 *   arena-desc:                  # 场地介绍（OP 用 /hub arena desc 写，显示在场地羊毛的 lore 里）
 *     单挑 PvP:
 *       风场: '&7风很大，小心被吹下去'
 *
 * 动作 action 五种写法：
 *   game:单挑 PvP          该游戏只有 1 个场地时直接 join，多个场地时打开子菜单（推荐）
 *   open:单挑 PvP          总是打开场地子菜单
 *   arena:单挑 PvP|风场     直接加入指定场地
 *   cmd:duel join           以玩家身份执行任意命令
 *   none                    纯装饰，点了没反应
 * </pre>
 *
 * 场地子菜单：每个场地一块羊毛，名字就是场地名，颜色表示实时状态
 * （绿=没人，黄=等待中，红=游戏中）；这是"场地列表"的核心约定，别改回固定颜色。
 *
 * 玩家侧：左键点击 = 按 action 执行；SHIFT+左键 = 查看提示（方便 OP 验证 lore）。
 */
public final class MenuLayout {

    /** 菜单类型：主菜单 / 某游戏的场地子菜单。 */
    public enum Place {
        MAIN, SUB
    }

    /** 主菜单一个槽位的内容；name/lore 为 null 表示用自动生成的动态提示。 */
    public record Entry(String material, String name, List<String> lore, String action, boolean opOnly) {
    }

    /**
     * 场地在子菜单里的三种状态（决定羊毛颜色）：
     * <ul>
     *   <li>{@link #EMPTY} 没人（等待中且 0 人）——绿羊毛</li>
     *   <li>{@link #WAITING} 等待中（有人但还没开局）——黄羊毛</li>
     *   <li>{@link #PLAYING} 游戏中（已开局，进不去）——红羊毛</li>
     * </ul>
     */
    public enum ArenaState {
        EMPTY, WAITING, PLAYING;

        public String text() {
            return switch (this) {
                case EMPTY -> "没人";
                case WAITING -> "等待中";
                case PLAYING -> "游戏中";
            };
        }

        public char color() {
            return switch (this) {
                case EMPTY -> 'a';
                case WAITING -> 'e';
                case PLAYING -> 'c';
            };
        }

        public Material wool() {
            return switch (this) {
                case EMPTY -> Material.LIME_WOOL;
                case WAITING -> Material.YELLOW_WOOL;
                case PLAYING -> Material.RED_WOOL;
            };
        }

        /** 场地实时状态（info 为 null = 场地刚被删/还没上报）。 */
        public static ArenaState of(com.hub.HubManager.ArenaInfo info) {
            if (info == null || info.state() != GameState.WAITING) {
                return PLAYING;
            }
            return info.players() <= 0 ? EMPTY : WAITING;
        }
    }

    /** 子菜单里某个场地槽位的外观覆盖（状态行与状态色仍由实时状态决定）。 */
    public record SubItem(String material, String name, List<String> lore, String action) {
    }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final HubCorePlugin plugin;

    public MenuLayout(HubCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- 配置路径 ----------

    public static String itemPath(String place, String game, int slot) {
        return Place.SUB.name().equals(place) ? itemPathSub(game, String.valueOf(slot)) : itemPathMain(slot);
    }

    public static String itemPathMain(int slot) {
        return "menu.items." + slot;
    }

    public static String itemPathSub(String game, String key) {
        return "menu.sub." + game + ".items." + key;
    }

    public static String itemPathSub(String game, int slot) {
        return itemPathSub(game, String.valueOf(slot));
    }

    /** 布局键 -> config 路径（子菜单里 "12" 视为槽位、"风场" 视为场地名）。 */
    public static String entryPath(Place place, String game, String key) {
        return place == Place.SUB ? itemPathSub(game, key) : itemPathMain(slotOfKey(key));
    }

    /** 该布局键是否是个槽位数字。 */
    public static boolean isSlotKey(String key) {
        return slotOfKey(key) >= 0;
    }

    private static int slotOfKey(String key) {
        if (key == null) {
            return -1;
        }
        try {
            int s = Integer.parseInt(key.trim());
            return (s >= 0 && s < 54) ? s : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    // ---------- 基础读写 ----------

    public ConfigurationSection section() {
        return plugin.getConfig().getConfigurationSection("menu");
    }

    /** 旧配置里 menu-title 直接写在根上；menu 段不存在时才回退，避免覆盖 OP 编辑过的标题。 */
    public String title(Place place, String game) {
        if (place == Place.SUB) {
            return resolve(plugin.getConfig().getString("menu.sub." + game + ".title", "&e" + game + " 场地"), null, null);
        }
        ConfigurationSection s = section();
        if (s != null && s.isSet("title")) {
            return resolve(s.getString("title", "&e游戏大厅"), null, null);
        }
        return resolve(plugin.getConfig().getString("menu-title", "&e游戏大厅"), null, null);
    }

    public int rows(Place place, String game) {
        int def = (place == Place.SUB) ? 3 : 6;
        String path = (place == Place.SUB) ? ("menu.sub." + game + ".rows") : "menu.rows";
        int r = plugin.getConfig().getInt(path, def);
        return Math.max(1, Math.min(6, r));
    }

    public int size(Place place, String game) {
        return rows(place, game) * 9;
    }

    public Entry entry(Place place, String game, int slot) {
        String path = (place == Place.SUB) ? itemPathSub(game, slot) : itemPathMain(slot);
        if (!plugin.getConfig().isConfigurationSection(path)) {
            return null;
        }
        ConfigurationSection s = plugin.getConfig().getConfigurationSection(path);
        String mat = s.getString("material", s.getString("icon", null));
        List<String> lore = s.isList("lore") ? s.getStringList("lore") : null;
        return new Entry(mat, s.getString("name", null), lore, s.getString("action", null),
                s.getBoolean("op-only", false));
    }

    public SubItem subItem(String game, String key) {
        String path = itemPathSub(game, key);
        if (!plugin.getConfig().isConfigurationSection(path)) {
            return null;
        }
        ConfigurationSection s = plugin.getConfig().getConfigurationSection(path);
        List<String> lore = s.isList("lore") ? s.getStringList("lore") : null;
        return new SubItem(s.getString("material", s.getString("icon", null)), s.getString("name", null), lore, s.getString("action", null));
    }

    /** 该游戏的全部场地外观覆盖（键=场地名）。 */
    public Map<String, SubItem> subItems(String game) {
        Map<String, SubItem> out = new LinkedHashMap<>();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("menu.sub." + game + ".items");
        if (root == null) {
            return out;
        }
        for (String key : root.getKeys(false)) {
            if (!root.isConfigurationSection(key)) {
                continue;
            }
            List<String> lore = root.isList(key + ".lore") ? root.getStringList(key + ".lore") : null;
            out.put(key, new SubItem(root.getString(key + ".material", root.getString(key + ".icon", null)),
                    root.getString(key + ".name", null), lore, root.getString(key + ".action", null)));
        }
        return out;
    }

    /** 该游戏是否已经有人工编辑过的场地子菜单。 */
    public boolean hasSub(String game) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("menu.sub." + game);
        return s != null && (s.isSet("title") || s.isConfigurationSection("items"));
    }

    // ---------- 场地介绍（OP 用 /hub arena desc 写，存在 menu.arena-desc 段） ----------

    public static String descPath(String game, String arena) {
        return "menu.arena-desc." + game + "." + arena;
    }

    /** 场地介绍（按注册表显示名归一化后查，别名/少空格也认）；没写过返回空表。 */
    public List<String> arenaDesc(String game, String arena) {
        if (game == null || arena == null) {
            return List.of();
        }
        String canon = plugin.getRegistry().canonicalName(game);
        String raw = plugin.getConfig().getString(descPath(canon, arena), null);
        return splitDesc(raw);
    }

    /** 多行介绍在配置里用一个字符串存，行之间用 | 分隔（跟 /hub menu lore 一致）。 */
    public static List<String> splitDesc(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\\|")) {
            if (!line.isBlank()) {
                lines.add(line.trim());
            }
        }
        return lines;
    }

    /** 写场地介绍；null/空 = 删除（调用方负责 save）。键用注册表显示名，和 arenaDesc 的读法一致。 */
    public void setArenaDesc(String game, String arena, String desc) {
        if (game == null || arena == null) {
            return;
        }
        String canon = plugin.getRegistry().canonicalName(game);
        String path = descPath(canon, arena);
        if (desc == null || desc.isBlank()) {
            plugin.getConfig().set(path, null);
            ConfigurationSection s = plugin.getConfig().getConfigurationSection("menu.arena-desc." + canon);
            if (s != null && s.getKeys(false).isEmpty()) {
                plugin.getConfig().set("menu.arena-desc." + canon, null); // 别留空段
            }
            return;
        }
        plugin.getConfig().set(path, desc.trim());
    }

    public boolean hasMain() {
        ConfigurationSection s = section();
        return s != null && s.isConfigurationSection("items") && !s.getKeys(false).isEmpty();
    }

    /** 该游戏在主菜单里的槽位（没有则 -1）。 */
    public int slotOfGame(String game) {
        ConfigurationSection items = plugin.getConfig().getConfigurationSection("menu.items");
        if (items == null) {
            return -1;
        }
        for (String key : items.getKeys(false)) {
            String action = items.getString(key + ".action");
            if (action == null) {
                continue;
            }
            String[] parsed = parseAction(action);
            if (parsed != null && game.equals(parsed[1])) {
                return slotOfKey(key);
            }
        }
        return -1;
    }

    // ---------- 编辑（调用方负责 saveConfig） ----------

    public void setItem(Place place, String game, String key, String material, String name, List<String> lore, String action) {
        String path = entryPath(place, game, key);
        plugin.getConfig().set(path + ".material", material);
        setOrRemove(path + ".name", name);
        plugin.getConfig().set(path + ".lore", (lore == null || lore.isEmpty()) ? null : lore);
        setOrRemove(path + ".action", action);
    }

    /** 保留已有动作，只替换外观（GUI 里放物品时用）。action 单独用 setAction 改。 */
    public void setAppearance(Place place, String game, int slot, String material, String name, List<String> lore) {
        ConfigurationSection old = plugin.getConfig().getConfigurationSection(itemPath(place.name(), game, slot));
        String action = old == null ? null : old.getString("action");
        setItem(place, game, String.valueOf(slot), material, name, lore, action);
    }

    public void setAction(Place place, String game, int slot, String action) {
        String path = itemPath(place.name(), game, slot);
        setOrRemove(path + ".action", action);
    }

    /** 子菜单里某个场地的槽位绑定（"slot:<槽位>"）。 */
    public void setSubSlot(String game, String arena, int slot) {
        plugin.getConfig().set(itemPathSub(game, arena) + ".action", "slot:" + slot);
    }

    /**
     * 把动作里的游戏名归一化成注册表里的显示名（如 单挑PvP -> 单挑 PvP）。
     * <p>
     * 必须归一化：场地状态（{@code HubManager.arenaInfos}）是按显示名存的，
     * 而动作里的游戏名是 OP 手写/从配置读的，可能少个空格或用了 alias（如 决斗），
     * 不归一化会查不到场地 → 点场地羊毛被误报"正在游戏中"。
     */
    public String canonicalAction(String action) {
        String[] parsed = parseAction(action);
        if (parsed == null) {
            return action;
        }
        String name = switch (parsed[0]) {
            case "game", "open" -> parsed[1];
            case "arena" -> parsed[1];
            default -> null;
        };
        if (name == null || name.isEmpty()) {
            return action; // cmd / none 不碰
        }
        String canon = plugin.getRegistry().canonicalName(name);
        if (canon == null || canon.equals(name)) {
            return action;
        }
        return switch (parsed[0]) {
            case "game" -> "game:" + canon;
            case "open" -> "open:" + canon;
            case "arena" -> (parsed[2] == null || parsed[2].isEmpty())
                    ? "arena:" + canon
                    : "arena:" + canon + "|" + parsed[2];
            default -> action;
        };
    }

    public void setLore(Place place, String game, int slot, List<String> lore) {
        plugin.getConfig().set(itemPath(place.name(), game, slot) + ".lore", lore);
    }

    public void setTitle(Place place, String game, String title) {
        if (place == Place.SUB) {
            plugin.getConfig().set("menu.sub." + game + ".title", title);
        } else {
            plugin.getConfig().set("menu.title", title);
        }
    }

    public void setName(Place place, String game, int slot, String name) {
        plugin.getConfig().set(itemPath(place.name(), game, slot) + ".name", name);
    }

    /** 删除一个槽位/场地条目；返回是否真的删掉了东西。 */
    public boolean remove(Place place, String game, int slot) {
        String path = itemPath(place.name(), game, slot);
        if (!plugin.getConfig().isConfigurationSection(path)) {
            return false;
        }
        plugin.getConfig().set(path, null);
        return true;
    }

    /** 槽位搬家：material/name/lore/action 一起搬（目标槽位被覆盖）。 */
    public void move(Place place, String game, int from, int to) {
        ConfigurationSection old = plugin.getConfig().getConfigurationSection(itemPath(place.name(), game, from));
        if (old == null) {
            return;
        }
        String path = itemPath(place.name(), game, to);
        plugin.getConfig().set(path, null);
        plugin.getConfig().set(path + ".material", old.getString("material", "STONE"));
        if (old.isSet("name")) {
            plugin.getConfig().set(path + ".name", old.getString("name"));
        }
        if (old.isList("lore")) {
            plugin.getConfig().set(path + ".lore", old.getStringList("lore"));
        }
        if (old.isSet("action")) {
            plugin.getConfig().set(path + ".action", old.getString("action"));
        }
        plugin.getConfig().set(itemPath(place.name(), game, from), null);
    }

    /** 有值就写、空值就删键（Bukkit 里 set(path, null) 等于 remove）。 */
    private void setOrRemove(String path, String value) {
        plugin.getConfig().set(path, (value == null || value.isEmpty()) ? null : value);
    }

    /** 菜单道具（打开菜单的指南针）放物品栏第几格，默认 4（中间）。 */
    public int menuItemSlot() {
        int configured = plugin.getConfig().getInt("menu-item-slot", 4);
        return (configured >= 0 && configured <= 8) ? configured : 4;
    }

    /**
     * 在主菜单左下角（最后一行第一格）放「观战」入口：点开 = 正在进行的对局列表。
     * 该格已被 OP 摆过东西时不动，避免覆盖。开场要在 ensureMiniGameList 之前调，先把这一格占住。
     */
    public void ensureSpectateMenuEntry() {
        if (!hasMain()) {
            return;
        }
        int slot = Math.max(0, (rows(Place.MAIN, null) - 1) * 9);
        String path = itemPathMain(slot);
        if (plugin.getConfig().isConfigurationSection(path)) {
            return;
        }
        plugin.getConfig().set(path + ".material", "ENDER_EYE");
        plugin.getConfig().set(path + ".name", "&b&l观战");
        plugin.getConfig().set(path + ".lore", List.of(
                "&7查看正在进行的小游戏，点一下进去观战",
                "&7观战中途退出：点聊天栏的按钮或输入 &f/hub"));
        plugin.getConfig().set(path + ".action", "spectate");
        plugin.saveConfig();
        plugin.getLogger().info("已在菜单第 " + slot + " 格添加观战入口（spectate）。");
    }

    /**
     * 头衔插件存在时，在菜单最后一格（右下角）放一个打开头衔菜单的入口。
     * 该格已被占用时不动，避免覆盖 OP 自己摆的物品。
     */
    public void ensureTitleMenuEntry() {
        int slot = size(Place.MAIN, null) - 1;
        String path = itemPathMain(slot);
        if (plugin.getConfig().isConfigurationSection(path)) {
            return;
        }
        plugin.getConfig().set(path + ".material", "NETHER_STAR");
        plugin.getConfig().set(path + ".name", "&e&l头衔菜单");
        plugin.getConfig().set(path + ".lore", List.of("&7点击查看并选择你的头衔", "&7也可以输入 &f/titles"));
        plugin.getConfig().set(path + ".action", "cmd:titles");
        plugin.saveConfig();
        plugin.getLogger().info("已在菜单第 " + slot + " 格添加头衔入口（cmd:titles）。");
    }

    /**
     * 安装了街机派对时，在主菜单加一个只有 OP 看得见的「派对设置」入口。
     * 已有 cmd:arcade menu 条目时只补 op-only，不重复添加。
     */
    public void ensureArcadeMenuEntry() {
        if (!hasMain() || plugin.getServer().getPluginManager().getPlugin("ArcadeParty") == null) {
            return;
        }
        ConfigurationSection items = plugin.getConfig().getConfigurationSection("menu.items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                String[] parsed = parseAction(items.getString(key + ".action"));
                if (parsed != null && "cmd".equals(parsed[0]) && parsed[3] != null
                        && parsed[3].toLowerCase(Locale.ROOT).startsWith("arcade menu")) {
                    if (!items.getBoolean(key + ".op-only", false)) {
                        plugin.getConfig().set("menu.items." + key + ".op-only", true);
                        plugin.saveConfig();
                        plugin.getLogger().info("已将菜单里的街机派对入口设为仅 OP 可见（op-only）。");
                    }
                    return;
                }
            }
        }
        int slot = -1;
        for (int s = size(Place.MAIN, null) - 1; s >= 0; s--) {
            if (!plugin.getConfig().isConfigurationSection(itemPathMain(s))) {
                slot = s;
                break;
            }
        }
        if (slot < 0) {
            return;
        }
        String path = itemPathMain(slot);
        plugin.getConfig().set(path + ".material", "COMPASS");
        plugin.getConfig().set(path + ".name", "&e&l派对设置 &7(管理员)");
        plugin.getConfig().set(path + ".lore", List.of(
                "&7调整街机派对：每局小游戏个数、人数、过渡时间",
                "&7以及每个小游戏的启用/禁用"));
        plugin.getConfig().set(path + ".action", "cmd:arcade menu");
        plugin.getConfig().set(path + ".op-only", true);
        plugin.saveConfig();
        plugin.getLogger().info("已在菜单第 " + slot + " 格添加街机派对设置入口（仅 OP 可见）。");
    }

    /** 收进「迷你小游戏」子菜单的游戏（显示名）。 */
    private static final List<String> MINI_GAMES = List.of("烫手山芋", "雪塔大作战", "色盲派对", "跳水填格");
    /** 子菜单布局版本：1 = 3 行三款游戏；2 = 6 行四款游戏（含跳水填格）。 */
    private static final int MINI_LIST_VERSION = 2;

    /**
     * 把烫手山芋 / 雪塔大作战 / 色盲派对 / 跳水填格从主菜单收进「迷你小游戏」子菜单。
     * 6 行大箱子布局，只迁移一次；用 version 标记，旧版 3 行子菜单会自动升级。
     */
    /**
     * **菜单可达性审计**（R3）：注册了、也有地图，但菜单里**点不到**的游戏。
     *
     * <p>为什么要它：菜单是手配的（`menu.items.<槽位>` 的 action），加游戏/改名时很容易漏。
     * 玩家面对的表现就是"某个游戏明明有，但找不到入口"。
     * 这里只**报**不改：菜单是玩家看得见的东西，自动塞图标属于产品决策，得人拍板。
     *
     * <p>⚠️ 已知限制：注册表的 id 是**显示名**（"建筑猜猜乐"），而 maps.yml 用的是小写 key，
     * 两套命名目前没有映射关系 —— 所以这里只能用显示名比对，**没法判断"这个游戏有没有地图"**。
     * 要彻底解决，得让注册表把 key 也登记出来（下一步）。
     */
    public void auditMenuReachability() {
        var reg = plugin.getRegistry();
        if (reg == null) {
            return;
        }
        java.util.Set<String> reachable = new java.util.LinkedHashSet<>();
        collectReachable("menu.items", reachable, 0);
        var subs = plugin.getConfig().getConfigurationSection("menu.sub");
        if (subs != null) {
            for (String sub : subs.getKeys(false)) {
                collectReachable("menu.sub." + sub + ".items", reachable, 1);
            }
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (var info : reg.getGames()) {
            if (!reachable.contains(info.id()) && !reachable.contains(info.displayName())) {
                missing.add(info.displayName());
            }
        }
        if (missing.isEmpty()) {
            plugin.getLogger().info("[菜单审计] 所有已登记游戏都能从菜单进入（共 "
                    + reg.getGames().size() + " 个）");
        } else {
            plugin.getLogger().warning("[菜单审计] 这些游戏已登记但菜单里点不到（共 "
                    + missing.size() + " 个）：" + missing);
        }
    }

    /** 递归收集 `game:` 动作指向的游戏显示名（子菜单里也可能有 game: 动作）。 */
    private void collectReachable(String path, java.util.Set<String> out, int depth) {
        if (depth > 3) {
            return;
        }
        var items = plugin.getConfig().getConfigurationSection(path);
        if (items == null) {
            return;
        }
        for (String key : items.getKeys(false)) {
            String action = items.getString(key + ".action");
            String[] parsed = parseAction(action);
            if (parsed == null) {
                continue;
            }
            if ("game".equals(parsed[0]) || "arena".equals(parsed[0])) {
                out.add(parsed[1]);
            } else if ("open".equals(parsed[0]) && parsed[1] != null) {
                collectReachable("menu.sub." + parsed[1] + ".items", out, depth + 1);
            }
        }
    }

    /**
     * **菜单由注册表派生**（R1）：把"注册了、有地图、但菜单里还没有入口"的游戏补进去。
     *
     * <p>用户定的结构：
     * <ul>
     *   <li><b>子小游戏</b>（`PartyGames.all()` 那 16 个）→ 统一放进「迷你小游戏」子菜单（史莱姆球入口）；</li>
     *   <li><b>其余游戏</b>（含街机派对）→ 根菜单各占一项。</li>
     * </ul>
     *
     * <p>**只补不覆盖**：OP 已摆好的格子一律不动（玩家看得见的东西不能被自动改写），
     * 只在空槽上补；没有空槽就报警让人手动加。
     */
    public void ensureDerivedGameEntries() {
        var reg = plugin.getRegistry();
        if (reg == null || !hasMain()) {
            return;
        }
        var subGames = new java.util.LinkedHashSet<String>(com.hub.room.PartyGames.all());
        int addedRoot = 0, addedSub = 0;

        // —— 子小游戏 → 子菜单 ——
        String subBase = "menu.sub.迷你小游戏";
        if (!plugin.getConfig().isConfigurationSection(subBase)) {
            plugin.getConfig().set(subBase + ".rows", 6);
            plugin.getConfig().set(subBase + ".title", "&b&l迷你小游戏");
        }
        if (findItemSlot("menu.items", "open:迷你小游戏") < 0
                && findItemSlot("menu.items", null) != Integer.MIN_VALUE) {
            int slot = firstFreeSlot("menu.items", size(Place.MAIN, null));
            if (slot >= 0) {
                plugin.getConfig().set(itemPathMain(slot) + ".material", "SLIME_BALL");
                plugin.getConfig().set(itemPathMain(slot) + ".name", "&b&l迷你小游戏");
                plugin.getConfig().set(itemPathMain(slot) + ".lore",
                        List.of("&7点击打开子菜单"));
                plugin.getConfig().set(itemPathMain(slot) + ".action", "open:迷你小游戏");
                addedRoot++;
            }
        }
        for (String key : subGames) {
            String display = reg.displayOfKey(key);
            if (display == null || com.hub.room.GameMaps.mapNames(key).isEmpty()) {
                continue;   // 没登记显示名 / 没地图：跳过（审计会单独报）
            }
            String action = "game:" + display;
            if (findItemSlot(subBase + ".items", action) >= 0) {
                continue;
            }
            int slot = firstFreeSlot(subBase + ".items", size(Place.SUB, "迷你小游戏"));
            if (slot < 0) {
                plugin.getLogger().warning("[菜单派生] 「迷你小游戏」子菜单没有空槽了，"
                        + display + " 需手动加：/hub menu edit");
                continue;
            }
            String path = subBase + ".items." + slot;
            plugin.getConfig().set(path + ".material", "PAPER");
            plugin.getConfig().set(path + ".name", "&f" + display);
            plugin.getConfig().set(path + ".action", action);
            addedSub++;
        }

        // —— 其余游戏 → 根菜单 ——
        for (String key : reg.keys()) {
            if (subGames.contains(key)) {
                continue;
            }
            if (com.hub.room.GameMaps.mapNames(key).isEmpty()) {
                continue;   // 没地图的游戏补了也点不开（quark / holdthefort），留给 key 审计报
            }
            String display = reg.displayOfKey(key);
            if (display == null) {
                continue;
            }
            String action = "game:" + display;
            if (findItemSlot("menu.items", action) >= 0) {
                continue;
            }
            int slot = firstFreeSlot("menu.items", size(Place.MAIN, null));
            if (slot < 0) {
                plugin.getLogger().warning("[菜单派生] 主菜单没有空槽了，" + display
                        + " 需手动加：/hub menu edit");
                continue;
            }
            plugin.getConfig().set(itemPathMain(slot) + ".material", "PAPER");
            plugin.getConfig().set(itemPathMain(slot) + ".name", "&f" + display);
            plugin.getConfig().set(itemPathMain(slot) + ".action", action);
            addedRoot++;
        }

        // —— 清理"同一游戏两份入口"里的坏那份（上游手配的 `cmd:<游戏> join`）——
        // 现在每个游戏已经有 `game:<显示名>` 入口了，而旧的 `cmd:` 直连在房间化后是**点不通的**
        //（`/buildrun join` 少了场地名、`/gtb join` 虽然能跑但不带房间上下文）。
        int removed = 0;
        removed += dropLegacyCmdEntries("menu.items");
        removed += dropLegacyCmdEntries(subBase + ".items");
        if (removed > 0) {
            plugin.getLogger().info("[菜单派生] 清掉 " + removed
                    + " 个重复的旧 `cmd:<游戏> join` 入口（同一游戏已有 game: 入口）");
        }

        if (addedRoot > 0 || addedSub > 0) {
            save();
            plugin.getLogger().info("[菜单派生] 已补入口：根菜单 " + addedRoot
                    + " 个、「迷你小游戏」子菜单 " + addedSub + " 个（只填空槽，未改动已有布局）");
        } else {
            plugin.getLogger().info("[菜单派生] 菜单入口齐全，无需补充");
        }
    }

    /** 在某个 items 段里找第一处 action 等于给定值（null = 找第一个空格）。 */
    private int findItemSlot(String path, String action) {
        var items = plugin.getConfig().getConfigurationSection(path);
        if (items == null) {
            return action == null ? Integer.MIN_VALUE : -1;
        }
        for (String key : items.getKeys(false)) {
            String a = items.getString(key + ".action");
            if (action == null ? a == null : action.equals(a)) {
                return slotOfKey(key);
            }
        }
        return action == null ? Integer.MIN_VALUE : -1;
    }

    /** items 段里第一个没有被占用的槽位；没有则 -1。 */
    private int firstFreeSlot(String path, int limit) {
        var items = plugin.getConfig().getConfigurationSection(path);
        java.util.Set<Integer> used = new java.util.HashSet<>();
        if (items != null) {
            for (String key : items.getKeys(false)) {
                int sl = slotOfKey(key);
                if (sl >= 0) {
                    used.add(sl);
                }
            }
        }
        for (int slot = 0; slot < limit; slot++) {
            if (slot == limit - 1) {
                continue;   // 右下角留给头衔/返回
            }
            if (!used.contains(slot)) {
                return slot;
            }
        }
        return -1;
    }

    public void ensureMiniGameList() {
        if (!hasMain()) {
            return;
        }
        String base = "menu.sub.迷你小游戏";
        boolean exists = plugin.getConfig().isConfigurationSection(base);
        int version = plugin.getConfig().getInt(base + ".version", 0);
        if (exists && version >= MINI_LIST_VERSION) {
            return;
        }
        List<Integer> freed = new ArrayList<>();
        boolean hasFolder = false;
        ConfigurationSection items = plugin.getConfig().getConfigurationSection("menu.items");
        if (items != null) {
            for (String key : new ArrayList<>(items.getKeys(false))) {
                ConfigurationSection entry = items.getConfigurationSection(key);
                if (entry == null) {
                    continue;
                }
                String rawAction = entry.getString("action");
                String[] parsed = parseAction(rawAction);
                if (parsed == null) {
                    continue;
                }
                if ("open".equals(parsed[0]) && "迷你小游戏".equals(parsed[1])) {
                    hasFolder = true;
                }
                boolean miniGame = ("game".equals(parsed[0]) || "open".equals(parsed[0]))
                        && MINI_GAMES.contains(plugin.getRegistry().canonicalName(parsed[1]));
                boolean jumpCmd = "cmd".equals(parsed[0]) && parsed[3] != null
                        && parsed[3].toLowerCase(Locale.ROOT).startsWith("jump");
                if (miniGame || jumpCmd) {
                    int slot = slotOfKey(key);
                    plugin.getConfig().set("menu.items." + key, null);
                    if (slot >= 0) {
                        freed.add(slot);
                    }
                }
            }
        }

        ConfigurationSection oldItems = plugin.getConfig().getConfigurationSection(base + ".items");
        if (oldItems != null) {
            for (String key : new ArrayList<>(oldItems.getKeys(false))) {
                plugin.getConfig().set(base + ".items." + key, null);
            }
        }
        plugin.getConfig().set(base + ".version", MINI_LIST_VERSION);
        plugin.getConfig().set(base + ".rows", 6);
        plugin.getConfig().set(base + ".title", "&b&l迷你小游戏");
        miniItem(base, 10, "POTATO", "&e&l烫手山芋",
                List.of("&7在 TNT 爆炸前传给别人"), "game:烫手山芋");
        miniItem(base, 12, "SNOWBALL", "&b&l雪塔大作战",
                List.of("&7踩雪雪塌，雪球砸人"), "game:雪塔大作战");
        miniItem(base, 14, "MAGENTA_CONCRETE", "&d&l色盲派对",
                List.of("&7站对颜色，站错塌陷"), "game:色盲派对");
        miniItem(base, 16, "WATER_BUCKET", "&b&l跳水填格",
                List.of("&7从高台跳进水池", "&7落点变成你的颜色"), "cmd:jump join");
        miniItem(base, 49, "ARROW", "&7返回主菜单",
                List.of("&7回到游戏大厅"), "cmd:hub menu");

        int folderSlot = freed.isEmpty() ? firstEmptyMainSlot() : freed.get(0);
        if (!hasFolder && folderSlot >= 0) {
            String path = itemPathMain(folderSlot);
            plugin.getConfig().set(path + ".material", "SLIME_BALL");
            plugin.getConfig().set(path + ".name", "&b&l迷你小游戏");
            plugin.getConfig().set(path + ".lore",
                    List.of("&7烫手山芋 / 雪塔 / 色盲派对 / 跳水填格", "&7点击打开子菜单"));
            plugin.getConfig().set(path + ".action", "open:迷你小游戏");
        } else if (!hasFolder) {
            plugin.getLogger().warning("主菜单没有空槽，\"迷你小游戏\"入口未自动放入；可用 /hub menu edit 手动添加。");
        }
        save();
        plugin.getLogger().info("已创建/升级「迷你小游戏」子菜单（6 行，含跳水填格）。");
    }

    private void miniItem(String base, int slot, String material, String name, List<String> lore, String action) {
        String path = base + ".items." + slot;
        plugin.getConfig().set(path + ".material", material);
        plugin.getConfig().set(path + ".name", name);
        plugin.getConfig().set(path + ".lore", lore);
        plugin.getConfig().set(path + ".action", action);
    }

    private int firstEmptyMainSlot() {
        int limit = size(Place.MAIN, null);
        for (int slot = 0; slot < limit; slot++) {
            // 右下角留给头衔入口；其余空槽都可以用
            if (slot == limit - 1) {
                continue;
            }
            if (!plugin.getConfig().isConfigurationSection(itemPathMain(slot))) {
                return slot;
            }
        }
        return -1;
    }

    // ---------- 首次运行：按注册表/实时场地生成默认布局 ----------

    /** menu.items 为空时，按已登记游戏生成主菜单：第一行居中排，避开中间那格菜单道具。 */
    public void generateDefaultMain() {
        List<GameInfo> games = plugin.getRegistry().getGames();
        if (games.isEmpty()) {
            return;
        }
        List<GameInfo> sorted = new ArrayList<>(games);
        sorted.sort((a, b) -> Integer.compare(menuOrder(a.displayName()), menuOrder(b.displayName())));
        plugin.getConfig().set("menu.items", null);
        int keep = menuItemSlot(); // 菜单道具占的那格不要放游戏入口，否则玩家进来没指南针
        for (int i = 0; i < sorted.size(); i++) {
            int slot = defaultSlot(i, sorted.size(), keep);
            if (slot < 0) {
                break;
            }
            plugin.getConfig().set(itemPathMain(slot) + ".material", matchMaterialName(sorted.get(i).icon()));
            plugin.getConfig().set(itemPathMain(slot) + ".action", "game:" + sorted.get(i).displayName());
        }
        plugin.getConfig().set("menu.rows", rows(Place.MAIN, null));
        save();
        plugin.getLogger().info("已按已登记游戏生成 " + sorted.size() + " 个主菜单槽位（可用 /hub menu edit 调整）。");
    }

    /**
     * 默认布局的第 i 个槽位：第一行按数量居中（看着整齐），并跳过菜单道具所在格。
     * 超出一行就换到下一行继续居中的相对位置。
     */
    private int defaultSlot(int index, int count, int keep) {
        int start = Math.max(0, (9 - Math.min(count, 9)) / 2);
        int slot = 0;
        int placed = 0;
        for (int i = 0; i < 54 && placed <= index; i++) {
            int row = i / 9;
            int col = i % 9;
            if (col < start) {
                continue;
            }
            if (row == 0 && i == keep) {
                continue;
            }
            slot = i;
            placed++;
        }
        return placed > index ? slot : -1;
    }

    /** 首次打开某游戏的场地子菜单时，按实时场地列表生成槽位（之后 OP 的修改会保留）。 */
    public void generateDefaultSub(String game) {
        if (hasSub(game)) {
            return;
        }
        int i = 0;
        int limit = size(Place.SUB, game);
        // 动作里写注册表显示名，之后改菜单键/换别名也不会点不动
        String canon = plugin.getRegistry().canonicalName(game);
        for (String arena : plugin.getManager().arenasOf(game).keySet()) {
            int slot = firstEmptySubSlot(game, limit);
            if (slot < 0) {
                break;
            }
            plugin.getConfig().set(itemPathSub(game, slot) + ".action", "arena:" + canon + "|" + arena);
            i++;
        }
        plugin.getConfig().set("menu.sub." + game + ".rows", rows(Place.SUB, game));
        save();
        plugin.getLogger().info("已为「" + game + "」生成 " + i + " 个场地子菜单槽位。");
    }

    private int firstEmptySubSlot(String game, int limit) {
        for (int s = 0; s < limit; s++) {
            if (!plugin.getConfig().isConfigurationSection(itemPathSub(game, s))) {
                return s;
            }
        }
        return -1;
    }

    private int menuOrder(String name) {
        int i = 0;
        for (Map<?, ?> raw : plugin.getConfig().getMapList("games")) {
            Object n = raw.get("name");
            if (n != null && String.valueOf(n).equals(name)) {
                return i;
            }
            i++;
        }
        return 1000 + Math.abs(name.hashCode() % 1000);
    }

    // ---------- 渲染 ----------

    /** 主菜单：只放 OP 摆好的槽位。 */
    public Inventory buildMain(Player p) {
        MenuHolder holder = new MenuHolder(Place.MAIN, null);
        Inventory inv = Bukkit.createInventory(holder, size(Place.MAIN, null), LEGACY.deserialize(title(Place.MAIN, null)));
        holder.setInventory(inv);
        for (String key : itemKeys("menu.items")) {
            int slot = slotOfKey(key);
            if (slot < 0) {
                continue;
            }
            Entry e = entry(Place.MAIN, null, slot);
            if (e == null) {
                continue;
            }
            if (e.opOnly() && !p.isOp()) {
                continue;
            }
            inv.setItem(slot, render(e.material(), e.name(), e.lore(), defaultIconLore(actionGame(e.action()))));
        }
        return inv;
    }

    /** 场地子菜单：场地槽位（绿=等待中 / 红=游戏中）+ OP 自己加的装饰槽位。 */
    public Inventory buildSub(Player p, String game) {
        // 游戏名归一化成注册表显示名：场地状态是按显示名存的，子菜单键也以显示名为准
        game = plugin.getRegistry().canonicalName(game);
        MenuHolder holder = new MenuHolder(Place.SUB, game);
        Inventory inv = Bukkit.createInventory(holder, size(Place.SUB, game), LEGACY.deserialize(title(Place.SUB, game)));
        holder.setInventory(inv);
        Map<String, SubItem> subs = subItems(game);
        List<Integer> used = new ArrayList<>();
        for (Map.Entry<String, com.hub.HubManager.ArenaInfo> e : plugin.getManager().arenasOf(game).entrySet()) {
            int slot = slotForSub(game, subs, e.getKey(), used);
            if (slot < 0) {
                continue;
            }
            used.add(slot);
            inv.setItem(slot, renderArena(game, e.getKey(), e.getValue(), subs.get(e.getKey())));
        }
        for (String key : itemKeys("menu.sub." + game + ".items")) {
            int slot = slotOfKey(key);
            if (slot < 0 || used.contains(slot)) {
                continue;
            }
            SubItem it = subs.get(key);
            if (it == null) {
                continue;
            }
            inv.setItem(slot, render(it.material(), it.name(), it.lore(), List.of()));
        }
        return inv;
    }

    /** 场地用哪个槽位：OP 给该场地写过条目就用它的槽位，否则取第一个空槽。 */
    private int slotForSub(String game, Map<String, SubItem> subs, String arena, List<Integer> used) {
        if (subs.containsKey(arena)) {
            int configured = configuredSubSlot(game, arena);
            if (configured >= 0 && !used.contains(configured)) {
                return configured;
            }
        }
        int limit = size(Place.SUB, game);
        for (int s = 0; s < limit; s++) {
            if (!used.contains(s)) {
                return s;
            }
        }
        return -1;
    }

    /** 场地条目写在了哪个槽位（键是数字时）。 */
    private int configuredSubSlot(String game, String arena) {
        if (arena != null && isSlotKey(arena)) {
            return slotOfKey(arena);
        }
        ConfigurationSection items = plugin.getConfig().getConfigurationSection("menu.sub." + game + ".items");
        if (items == null || arena == null) {
            return -1;
        }
        String action = items.getString(arena + ".action");
        if (action != null && action.startsWith("slot:")) {
            return slotOfKey(action.substring("slot:".length()));
        }
        // 没有显式槽位：跟 arena 同名的数字键也认
        return -1;
    }

    /**
     * 场地羊毛的默认提示：第一行状态（没人/等待中/游戏中 + 人数），
     * 接着是 OP 写的场地介绍（/hub arena desc），最后一行点击提示。
     */
    private List<String> defaultArenaLore(String game, String arena, ArenaState state, int players, String extra) {
        List<String> lore = new ArrayList<>();
        String line = "&" + state.color() + state.text();
        if (state == ArenaState.WAITING) {
            line += " &7(&e" + players + "&7 人等待)";
        } else if (state == ArenaState.PLAYING) {
            line += extra.isEmpty()
                    ? " &7(&c" + players + "&7 人)"
                    : " &7(&c" + players + "&7 人，&e" + extra + "&7)";
        }
        lore.add(line);
        lore.addAll(arenaDesc(game, arena));
        lore.add(state == ArenaState.PLAYING ? "&8游戏中，暂时进不去" : "&a点击加入这个场地");
        return lore;
    }

    /**
     * 场地羊毛：绿=没人 / 黄=等待中 / 红=游戏中，名字就是场地名。
     * OP 写过的 name/lore 优先（状态行由默认 lore 提供，OP 自己写了 lore 就全按 OP 的来）。
     */
    private ItemStack renderArena(String game, String arena, com.hub.HubManager.ArenaInfo info, SubItem it) {
        ArenaState state = ArenaState.of(info);
        int players = info == null ? 0 : info.players();
        String extra = (info == null || info.extra() == null || info.extra().isEmpty()) ? "" : info.extra();
        Material m = state.wool();
        if (it != null && it.material() != null) {
            Material custom = matchMaterial(it.material());
            if (custom != null) {
                m = custom; // OP 在子菜单里给这个场地指定过材质就听 OP 的
            }
        }
        ItemStack stack = new ItemStack(m);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        String defName = "&" + state.color() + arena;
        List<String> defLore = defaultArenaLore(game, arena, state, players, extra);
        String custom = it == null ? null : it.name();
        meta.displayName(Chat.noItalic(LEGACY.deserialize(custom == null || custom.isEmpty() ? defName : resolve(custom, arena, info))));
        List<net.kyori.adventure.text.Component> loreOut = new ArrayList<>();
        for (String line : resolveLore(it != null ? it.lore() : null, defLore, arena, info)) {
            loreOut.add(LEGACY.deserialize(line));
        }
        meta.lore(loreOut);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 普通物品（主菜单槽位 / 子菜单装饰槽位）。 */
    private ItemStack render(String material, String name, List<String> lore, List<String> fallbackLore) {
        Material m = matchMaterial(material);
        ItemStack stack = new ItemStack(m == null ? Material.STONE : m);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        List<String> lines = (lore == null || lore.isEmpty()) ? fallbackLore : lore;
        if (name != null && !name.isEmpty()) {
            meta.displayName(Chat.noItalic(LEGACY.deserialize(resolve(name, null, null))));
        }
        if (lines != null && !lines.isEmpty()) {
            List<net.kyori.adventure.text.Component> out = new ArrayList<>();
            for (String line : lines) {
                out.add(LEGACY.deserialize(resolve(line, null, null)));
            }
            meta.lore(out);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** 游戏图标的默认提示（游戏 + 场地数 + 没人/等待/游戏中），有场地信息才生成。 */
    public List<String> defaultIconLore(String game) {
        if (game == null || game.isEmpty()) {
            return List.of();
        }
        Map<String, com.hub.HubManager.ArenaInfo> arenas = plugin.getManager().arenasOf(game);
        List<String> lore = new ArrayList<>();
        lore.add(arenas.size() == 1 ? "&7点击加入该场地" : "&7点击打开场地列表");
        if (arenas.isEmpty()) {
            lore.add("&7暂无场地信息");
        } else {
            int empty = 0;
            int waiting = 0;
            int playing = 0;
            for (com.hub.HubManager.ArenaInfo a : arenas.values()) {
                switch (ArenaState.of(a)) {
                    case EMPTY -> empty++;
                    case WAITING -> waiting++;
                    case PLAYING -> playing++;
                }
            }
            lore.add("&7场地 &f" + arenas.size() + "&7（&a" + empty + "&7 没人 / &e" + waiting + "&7 等待 / &c" + playing + "&7 游戏中）");
        }
        lore.add("&8SHIFT+左键 只看提示");
        return lore;
    }

    /** {arena} {players} {extra} {state} 占位符（主菜单物品也用，前三个为空）。 */
    public static String resolve(String text, String arena, com.hub.HubManager.ArenaInfo info) {
        if (text == null) {
            return null;
        }
        ArenaState state = ArenaState.of(info);
        String extra = (info == null || info.extra() == null) ? "" : info.extra();
        return text.replace("{arena}", arena == null ? "" : arena)
                .replace("{players}", String.valueOf(info == null ? 0 : info.players()))
                .replace("{extra}", extra)
                .replace("{state}", info == null ? "" : state.text());
    }

    private static List<String> resolveLore(List<String> lore, List<String> def, String arena, com.hub.HubManager.ArenaInfo info) {
        List<String> src = (lore == null || lore.isEmpty()) ? def : lore;
        List<String> out = new ArrayList<>();
        for (String line : src) {
            String r = resolve(line, arena, info);
            if (r != null && !r.isEmpty()) {
                out.add(r);
            }
        }
        return out;
    }

    private List<String> itemKeys(String sectionPath) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection(sectionPath);
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    // ---------- 动作字符串 ----------

    /** 返回 {type, game, arena, command}；无法解析返回 null。 */
    public static String[] parseAction(String action) {
        if (action == null || action.isEmpty()) {
            return null;
        }
        int colon = action.indexOf(':');
        if (colon < 0) {
            // 不带冒号的写法里，「spectate」是内置的观战入口，其余仍按游戏名处理
            if ("spectate".equals(action.trim())) {
                return new String[]{"spectate", null, null, null};
            }
            return new String[]{"game", action, null, null};
        }
        String type = action.substring(0, colon).trim();
        String rest = action.substring(colon + 1).trim();
        if ("game".equals(type)) {
            return new String[]{"game", rest, null, null};
        }
        if ("open".equals(type)) {
            return new String[]{"open", rest, null, null};
        }
        if ("spectate".equals(type)) {
            return new String[]{"spectate", null, null, null};
        }
        if ("arena".equals(type)) {
            int bar = rest.indexOf('|');
            return bar < 0 ? new String[]{"arena", rest, null, null}
                    : new String[]{"arena", rest.substring(0, bar).trim(), rest.substring(bar + 1).trim(), null};
        }
        if ("cmd".equals(type)) {
            return new String[]{"cmd", null, null, rest};
        }
        return new String[]{"none", null, null, null};
    }

    public static String actionGame(String action) {
        String[] a = parseAction(action);
        return a == null ? null : a[1];
    }

    // ---------- 工具 ----------

    /** 材质名解析：无效返回 null（调用方决定兜底）。 */
    public static Material matchMaterial(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return Material.matchMaterial(name.trim().toUpperCase());
    }

    private static String matchMaterialName(String name) {
        Material m = matchMaterial(name);
        return m == null ? "STONE" : m.name();
    }

    public void save() {
        plugin.saveConfig();
    }

    /**
     * 删掉"某个游戏已经有 `game:` 入口"时残留的 `cmd:<前缀> join` 旧入口。
     *
     * <p>判断依据：`games.yml` 里每个游戏的 join-command 首词（`br join` → `br`）。
     * 只有**该游戏已经有 `game:` 入口**时才删，避免把唯一入口删掉。
     */
    private int dropLegacyCmdEntries(String path) {
        var items = plugin.getConfig().getConfigurationSection(path);
        if (items == null) {
            return 0;
        }
        java.util.Set<String> reached = new java.util.HashSet<>();
        for (String key : items.getKeys(false)) {
            String a = items.getString(key + ".action");
            if (a != null && a.startsWith("game:")) {
                reached.add(a.substring("game:".length()));
            }
        }
        // ⚠️ join-command 在**独立的 games.yml**里，不在 HubCore 的 config.yml
        var gamesCfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.File(plugin.getDataFolder(), "games.yml"));
        var games = gamesCfg.getConfigurationSection("games");
        int removed = 0;
        for (String key : new java.util.ArrayList<>(items.getKeys(false))) {
            String action = items.getString(key + ".action");
            if (action == null || !action.startsWith("cmd:")) {
                continue;
            }
            String cmd = action.substring(4).trim();
            String head = cmd.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
            String gameKey = null;
            if (games != null) {
                for (String g : games.getKeys(false)) {
                    String join = games.getString(g + ".join-command", "");
                    if (!join.isBlank()
                            && join.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT).equals(head)) {
                        gameKey = g;
                        break;
                    }
                }
            }
            if (gameKey == null) {
                continue;   // 不是游戏入口（例如 cmd:titles / cmd:hub menu）
            }
            String display = plugin.getRegistry() == null ? null
                    : plugin.getRegistry().displayOfKey(gameKey);
            if (display != null && reached.contains(display)) {
                plugin.getConfig().set(path + "." + key, null);
                removed++;
            }
        }
        return removed;
    }

}
