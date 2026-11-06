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
        // **整份菜单里已经出现过的游戏**（根 + 所有子菜单；三种 action 写法都会解析成 key）
        java.util.Set<String> presentKeys = gameKeysAnywhere();
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
            // **按游戏 key 判重**：上游可能写成 cmd:<插件命令> join，那也算"已经有了"
            if (presentKeys.contains(key)) {
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
            presentKeys.add(key);
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
            if (presentKeys.contains(key)) {
                continue;   // 上游已经用它自己的写法摆过了（game:/cmd:/arena:），不要重复添加
            }
            // **根菜单不自动补**：主菜单是玩家第一眼看到的东西，上游已经摆好了位置；
            // 自动塞一格 PAPER 只会变成"多出来的纸"（2026-11-07 的 bug）。缺的只提示 OP。
            plugin.getLogger().info("[菜单派生] 游戏 " + display + "（" + key
                    + "）在主菜单还没有入口，需要时用 /hub menu edit 手动加");
        }

        // —— 上游的 `cmd:<游戏> join` 入口：**就地翻译**成 `game:<显示名>`（布局/图标/文案一律不动）——
        // 房间化之后直连命令少了场地参数、点不动，但上游摆的位置是对的。
        int normalized = 0;
        normalized += normalizeLegacyCmdEntries("menu.items");
        var subSec = plugin.getConfig().getConfigurationSection("menu.sub");
        if (subSec != null) {
            for (String name : subSec.getKeys(false)) {
                normalized += normalizeLegacyCmdEntries("menu.sub." + name + ".items");
            }
        }
        if (normalized > 0) {
            plugin.getLogger().info("[菜单派生] 把 " + normalized
                    + " 个旧 `cmd:<游戏> join` 入口翻译成 game: 动作（保留原槽位与图标）");
        }

        if (addedRoot > 0 || addedSub > 0 || normalized > 0) {
            save();
            plugin.getLogger().info("[菜单派生] 已补入口：根菜单 " + addedRoot
                    + " 个、「迷你小游戏」子菜单 " + addedSub + " 个（只填空槽，未改动已有布局）");
        } else {
            plugin.getLogger().info("[菜单派生] 菜单入口齐全，无需补充");
        }
    }


    /**
     * **把一个菜单项的 action 解析成"它属于哪个游戏"（游戏 key）**。
     *
     * <p>为什么需要：上游菜单里同一个游戏有三种写法 ——`game:<显示名>`、`cmd:<插件命令> join`、
     * `arena:<游戏>|<场地>`。以前派生入口时按 **action 字符串** 比对，于是上游写的
     * `cmd:jump join` 和我们要补的 `game:跳水填格` 被当成两回事 → **同一个游戏在菜单里出现两次**
     * （实测：迷你小游戏子菜单里出现了"击退大战 / 击退大作战"、"battlefield 1 / 占地风云1"两份）。
     *
     * <p>这里统一按"命令首词 → games.yml 的 join-command 首词"反查游戏 key。
     */
    private String gameKeyOfAction(String action) {
        String[] parsed = parseAction(action);
        if (parsed == null) {
            return null;
        }
        switch (parsed[0]) {
            case "game" -> {
                return resolveGameKey(parsed[1]);
            }
            case "arena" -> {
                return resolveGameKey(parsed[1]);
            }
            case "cmd" -> {
                String command = parsed[3];   // parseAction 把 cmd 的命令文本放在 [3]，不是 [1]
                if (command == null) {
                    return null;
                }
                String[] parts = command.trim().split("\\s+");
                String head = parts[0].toLowerCase(java.util.Locale.ROOT);
                // **只有 `<命令> join` 才是"进这个游戏"**；`arcade menu` / `xxx start` 不是入口
                // **第二个词必须是 join**（`dig join 一号场` 也算；`arcade menu` 不算）
                if (parts.length < 2 || !"join".equalsIgnoreCase(parts[1])) {
                    return null;
                }
                var file = new java.io.File(plugin.getDataFolder(), "games.yml");
                if (!file.isFile()) {
                    return null;
                }
                var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
                var gamesSec = yaml.getConfigurationSection("games");
                if (gamesSec == null) {
                    return null;
                }
                var cmdObj = org.bukkit.Bukkit.getPluginCommand(head);
                for (String key : gamesSec.getKeys(false)) {
                    String join = gamesSec.getString(key + ".join-command", "");
                    if (join == null || join.isBlank()) {
                        continue;
                    }
                    String joinHead = join.trim().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
                    if (joinHead.equals(head)) {
                        return key;
                    }
                    // **命令别名**：上游菜单写 `cmd:kb join`，games.yml 里是 `knockback join` ——
                    // 但 `kb` 和 `knockback` 是同一个插件命令，按插件身份认作同一个游戏。
                    if (cmdObj != null) {
                        var joinCmd = org.bukkit.Bukkit.getPluginCommand(joinHead);
                        if (joinCmd != null && joinCmd.getPlugin() == cmdObj.getPlugin()) {
                            return key;
                        }
                    }
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** 某一段 items 里已经出现过的游戏 key 集合（root 或某个子菜单）。 */
    private java.util.Set<String> gameKeysIn(String path) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        var items = plugin.getConfig().getConfigurationSection(path);
        if (items == null) {
            return keys;
        }
        for (String k : items.getKeys(false)) {
            String key = gameKeyOfAction(items.getString(k + ".action"));
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** 整个菜单（根 + 所有子菜单）里出现过的游戏 key。 */
    private java.util.Set<String> gameKeysAnywhere() {
        java.util.Set<String> all = new java.util.HashSet<>(gameKeysIn("menu.items"));
        var sub = plugin.getConfig().getConfigurationSection("menu.sub");
        if (sub != null) {
            for (String name : sub.getKeys(false)) {
                all.addAll(gameKeysIn("menu.sub." + name + ".items"));
            }
        }
        return all;
    }

    /**
     * **把"玩家看到的名字"解析成游戏 key**。
     *
     * <p>两道：① 注册表（`plugin.yml` 的 `minigame.name` / key）——这是我们的权威名；
     * ② **maps.yml 的区域名**——上游菜单里写的是区域名（"色盲派对 / 烫手山芋 / 雪塔大作战"），
     * 而这些区域在自己的 `regions[].game` 上标了 key，所以能直接反查。
     *
     * <p>为什么需要：上游菜单用区域名、我们的注册表用另一套显示名（用户拍板"以注册表为准"），
     * 两套名字对不上 → 派生入口时认不出"这个游戏已经有入口了"，于是**同一个游戏在菜单里出现两份**
     * （玩家看到的"一排多出来的纸"，2026-11-07）。
     */
    private String resolveGameKey(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        var reg = plugin.getRegistry();
        if (reg != null) {
            // ① 注册表：**keyOf 返回小写 key**（canonicalName 返回的是显示名，别混用）
            String key = reg.keyOf(name);
            if (key != null) {
                return key;
            }
            // ② 宽松匹配：去掉空格/颜色码/大小写差异（上游写 "单挑PVP"，注册表是 "单挑 PvP"）
            String norm = normalizeName(name);
            for (String k : reg.keys()) {
                String display = reg.displayOfKey(k);
                if (display != null && normalizeName(display).equals(norm)) {
                    return k;
                }
                if (normalizeName(k).equals(norm)) {
                    return k;
                }
            }
        }
        // ③ 派对配置里的 display（上游菜单用的就是这些名字：击退大作战 / 一掘到底 …）
        java.io.File partyCfg = new java.io.File(plugin.getDataFolder().getParentFile(),
                "ArcadeParty/config.yml");
        if (partyCfg.isFile()) {
            var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(partyCfg);
            var games = yaml.getConfigurationSection("games");
            if (games != null) {
                for (String key : games.getKeys(false)) {
                    String display = games.getString(key + ".display", "");
                    if (display != null && normalizeName(display).equals(normalizeName(name))) {
                        return key;
                    }
                }
            }
        }
        // ④ maps.yml：区域名 → 该区域声明的 game
        String want = normalizeName(name);
        for (String game : com.hub.room.GameMaps.games()) {
            for (String map : com.hub.room.GameMaps.mapNames(game)) {
                var gm = com.hub.room.GameMaps.get(game, map);
                if (gm == null) {
                    continue;
                }
                for (var w : gm.worlds()) {
                    if (w.regions() == null) {
                        continue;
                    }
                    for (var r : w.regions()) {
                        if (r.name() != null && normalizeName(r.name()).equals(want)) {
                            return game;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 名字归一化：去颜色码、去空白、小写（上游与注册表的写法差异只在这些地方）。 */
    private static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = org.bukkit.ChatColor.stripColor(raw);
        s = s.replaceAll("[\\s\\u00a0]+", "");
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * **菜单体检**：把"玩家能看见的菜单"与"注册表/地图表"对一遍。
     *
     * <p>为什么要有：2026-11-07 那一批 bug（主菜单多出一排纸、子小游戏重复两份、
     * 派对设置被改成"游戏入口"）全是**菜单与游戏清单不一致**，靠玩家截图才发现。
     * 这里把它变成可断言的一行文字，`/hub doctor` 每次都报。
     *
     * @return 问题列表（空 = 干净）
     */
    public java.util.List<String> audit() {
        java.util.List<String> out = new java.util.ArrayList<>();
        var reg = plugin.getRegistry();
        if (reg == null || !hasMain()) {
            return out;
        }
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        java.util.Map<String, Integer> rootCount = new java.util.HashMap<>();
        countGames("menu.items", seen);
        countGames("menu.items", rootCount);
        var sub = plugin.getConfig().getConfigurationSection("menu.sub");
        java.util.Set<String> subKeys = new java.util.HashSet<>();
        if (sub != null) {
            for (String name : sub.getKeys(false)) {
                countGames("menu.sub." + name + ".items", seen);
                subKeys.addAll(gameKeysIn("menu.sub." + name + ".items"));
            }
        }
        for (var e : rootCount.entrySet()) {
            if (e.getValue() > 1) {
                out.add("重复入口（主菜单）：" + e.getKey() + " 出现 " + e.getValue() + " 次");
            }
        }
        for (String key : reg.keys()) {
            if (com.hub.room.GameMaps.mapNames(key).isEmpty()) {
                continue;   // 没地图的游戏本来就点不开（quark / skypit），不算菜单问题
            }
            if (!seen.containsKey(key)) {
                out.add("没有入口：" + key + "（" + reg.displayOfKey(key) + "）");
                continue;
            }
            boolean subGame = com.hub.room.PartyGames.isSubGame(key);
            if (subGame && !subKeys.contains(key)) {
                out.add("子小游戏不在「迷你小游戏」子菜单：" + key);
            }
        }
        return out;
    }

    /**
     * 统计某段 items 里每个游戏 key 出现几次（**只算"游戏入口"**）。
     *
     * <p>`arena:<游戏>|<场地>` 是"这个游戏的场地子菜单"（上游的 单挑 PvP / 密室杀手 就是这种），
     * 同一个游戏有它**不算重复**；只有两个 `game:` 入口指向同一游戏才是重复。
     */
    private void countGames(String path, java.util.Map<String, Integer> seen) {
        var items = plugin.getConfig().getConfigurationSection(path);
        if (items == null) {
            return;
        }
        for (String k : items.getKeys(false)) {
            String action = items.getString(k + ".action");
            String[] parsed = parseAction(action);
            if (parsed == null || !("game".equals(parsed[0]) || "cmd".equals(parsed[0]))) {
                continue;
            }
            String key = gameKeyOfAction(action);
            if (key != null) {
                seen.merge(key, 1, Integer::sum);
            }
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
     * 把上游菜单里的 `cmd:<插件命令> join` 入口**就地翻译**成 `game:<显示名>`。
     *
     * <p>为什么翻译而不是删除：菜单是**玩家看得见的东西**，上游已经把图标/名字/坐标摆好了；
     * 房间化之后 `cmd:buildrun join` 这类直连少了场地参数，点不动，但那份**布局是对的**。
     * 以前这里是"删掉旧入口 + 在空槽补一个 PAPER"，结果同一个游戏在菜单里出现两份、
     * 还多出一排纸（玩家看到的 bug，2026-11-07）。现在只改 action，槽位/图标/文案全保留。
     *
     * @return 翻译了几项
     */
    private int normalizeLegacyCmdEntries(String path) {
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
            // **管理员入口不翻译**：`op-only` 的格子是设置面板（派对设置 → `/arcade menu`），
            // 不是"进游戏"；以前它被翻译成 `game:街机派对`，于是在主菜单里和真正的派对入口撞车。
            if (items.getBoolean(key + ".op-only", false)) {
                continue;
            }
            String cmd = action.substring(4).trim();
            String head = cmd.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
            // 命令别名也要认：`party` 是 `/arcade` 的别名（同一个插件命令），
            // 不识别就会留下 `cmd:party join` 这种和 `game:街机派对` 重复的坏入口。
            var cmdObj = org.bukkit.Bukkit.getPluginCommand(head);
            String gameKey = null;
            if (games != null) {
                for (String g : games.getKeys(false)) {
                    String join = games.getString(g + ".join-command", "");
                    if (join.isBlank()) {
                        continue;
                    }
                    String joinHead = join.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
                    boolean same = joinHead.equals(head);
                    if (!same && cmdObj != null) {
                        var joinCmd = org.bukkit.Bukkit.getPluginCommand(joinHead);
                        same = joinCmd != null && joinCmd.getPlugin() == cmdObj.getPlugin();
                    }
                    if (same) {
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
            if (display != null && !reached.contains(display)) {
                plugin.getConfig().set(path + "." + key + ".action", "game:" + display);
                removed++;
            }
        }
        return removed;
    }

}
