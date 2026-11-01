package com.gameworlds;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GameWorlds —— 小游戏多世界管理。
 * 启动时（load: STARTUP，早于四个游戏插件）自动创建/加载各游戏世界并按配置套用规则，
 * 让每个游戏在独立世界中运行：禁刷怪、固定白天、禁天气/火蔓延/爆炸破坏地形，
 * 单挑世界单独开启 PvP，主城世界保持服务器默认（pvp=false）。
 */
public final class GameWorldsPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    /** 本插件托管的世界（world_xxx）。 */
    // ---------- 世界命名前缀（docs/世界命名与前缀.md，决策 D19） ----------
    /** 模板世界：设计者编辑的地图源，**永不当场地加载**（需要时 /gw load 手动加载）。 */
    static final String PREFIX_TEMPLATE = "tmpl_";
    /** 房间实例：**房间引擎独占**（会自动回收），不接受手工创建。 */
    static final String PREFIX_ROOM = "room_";
    /** 历史遗留的持久游玩世界前缀（只用于识别，新世界不再自动补它）。 */
    static final String PREFIX_LEGACY = "world_";

    /** 名字是否已经带了已知前缀（含裸名 {@code world}）。 */
    static boolean hasKnownPrefix(String name) {
        return name.startsWith(PREFIX_TEMPLATE)
                || name.startsWith(PREFIX_ROOM)
                || name.startsWith(PREFIX_LEGACY)
                || name.equals("world");
    }

    /** 模板世界不自动加载。 */
    static boolean isTemplate(String name) {
        return name.startsWith(PREFIX_TEMPLATE);
    }

    private final Map<String, World> managed = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadWorlds();
        var cmd = getCommand("gw");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info("GameWorlds 已启用。托管世界：" + (managed.isEmpty() ? "无" : String.join(", ", managed.keySet())));
    }

    @Override
    public void onDisable() {
        // 世界由服务器统一保存/卸载，插件无需额外处理
    }

    // ---------- 世界加载与规则 ----------

    private void loadWorlds() {
        managed.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("worlds");
        if (sec == null) {
            getLogger().warning("config.yml 缺少 worlds 段，无世界可管理。");
            return;
        }
        int templates = 0;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection ws = sec.getConfigurationSection(name);
            if (ws != null && !ws.getBoolean("enabled", true)) {
                continue;
            }
            // D19：模板世界**不自动加载**（它只是被房间引擎复制的源；加载它既浪费内存，
            // 也会让"模板永不加载"这条约定失效）。要用就 /gw load <名字>。
            if (isTemplate(name)) {
                templates++;
                continue;
            }
            World w = loadOrCreate(name, ws);
            if (w != null) {
                managed.put(name, w);
            }
        }
        if (templates > 0) {
            getLogger().info("跳过 " + templates + " 个模板世界（" + PREFIX_TEMPLATE
                    + " 前缀，按需 /gw load 加载）。");
        }
    }

    private World loadOrCreate(String name, ConfigurationSection ws) {
        World w = Bukkit.getWorld(name);
        if (w == null) {
            WorldCreator creator = new WorldCreator(name);
            try {
                creator.environment(World.Environment.valueOf(ws.getString("environment", "NORMAL")));
            } catch (IllegalArgumentException ex) {
                creator.environment(World.Environment.NORMAL);
            }
            String type = ws.getString("type", "FLAT");
            if ("VOID".equalsIgnoreCase(type)) {
                // 空层超平坦 + 虚空生物群系 = 完全没有方块的空世界
                creator.type(WorldType.FLAT);
                creator.generateStructures(false);
                creator.generatorSettings("{\"layers\":[],\"biome\":\"minecraft:the_void\"}");
            } else if ("FLAT".equalsIgnoreCase(type)) {
                creator.type(WorldType.FLAT);
            }
            w = Bukkit.createWorld(creator);
        }
        if (w == null) {
            // 服务器可能还没就绪，延迟重试一次
            getLogger().warning("世界 " + name + " 创建/加载失败，3 秒后重试…");
            final String fname = name;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                World retry = Bukkit.getWorld(fname);
                if (retry == null) {
                    retry = Bukkit.createWorld(new WorldCreator(fname));
                }
                if (retry != null) {
                    applyRules(retry, ws);
                    managed.put(fname, retry);
                    getLogger().info("世界 " + fname + " 重试加载成功。");
                } else {
                    getLogger().severe("世界 " + fname + " 加载失败，请检查磁盘空间与权限。");
                }
            }, 60L);
            return null;
        }
        applyRules(w, ws);
        getLogger().info("世界 " + name + " 就绪：pvp=" + w.getPVP() + " 难度=" + w.getDifficulty()
                + " 怪物=" + w.getGameRuleValue(GameRule.DO_MOB_SPAWNING));
        return w;
    }

    /** 按配置套用世界规则（每次启动都会执行，保证与 config.yml 一致）。 */
    private void applyRules(World w, ConfigurationSection ws) {
        w.setPVP(ws.getBoolean("pvp", false));
        String diff = ws.getString("difficulty", "NORMAL");
        try {
            w.setDifficulty(Difficulty.valueOf(diff.toUpperCase()));
        } catch (IllegalArgumentException ex) {
            w.setDifficulty(Difficulty.NORMAL);
        }
        long time = ws.getLong("fixed-time", -1);
        if (time >= 0) {
            w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            w.setTime(time);
        } else {
            w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
        }
        w.setGameRule(GameRule.DO_MOB_SPAWNING, ws.getBoolean("mob-spawning", false));
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, ws.getBoolean("weather-cycle", false));
        w.setGameRule(GameRule.DO_FIRE_TICK, ws.getBoolean("fire-spread", false));
        w.setGameRule(GameRule.MOB_GRIEFING, ws.getBoolean("mob-griefing", false));
        w.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        w.setGameRule(GameRule.DO_MOB_LOOT, ws.getBoolean("mob-loot", false));
        // 死亡不掉落（默认开启，避免小游戏死亡丢装备）
        w.setGameRule(GameRule.KEEP_INVENTORY, ws.getBoolean("keep-inventory", true));
        w.setStorm(false);
        w.setThundering(false);
    }

    /** 应用单条规则到单个世界并写入配置段（供 /gw global 使用，重启后由 applyRules 保持）。 */
    private void applyRule(World w, String name, String rule, String value) {
        String key = "worlds." + name + "." + rule;
        switch (rule) {
            case "pvp" -> {
                boolean b = Boolean.parseBoolean(value);
                w.setPVP(b);
                getConfig().set(key, b);
            }
            case "difficulty" -> {
                Difficulty d = Difficulty.valueOf(value.toUpperCase());
                w.setDifficulty(d);
                getConfig().set(key, d.name());
            }
            case "keep-inventory" -> {
                boolean b = Boolean.parseBoolean(value);
                w.setGameRule(GameRule.KEEP_INVENTORY, b);
                getConfig().set(key, b);
            }
            case "mob-spawning" -> {
                boolean b = Boolean.parseBoolean(value);
                w.setGameRule(GameRule.DO_MOB_SPAWNING, b);
                getConfig().set(key, b);
            }
            case "weather-cycle" -> {
                boolean b = Boolean.parseBoolean(value);
                w.setGameRule(GameRule.DO_WEATHER_CYCLE, b);
                getConfig().set(key, b);
            }
            case "fire-spread" -> {
                boolean b = Boolean.parseBoolean(value);
                w.setGameRule(GameRule.DO_FIRE_TICK, b);
                getConfig().set(key, b);
            }
            case "mob-griefing" -> {
                boolean b = Boolean.parseBoolean(value);
                w.setGameRule(GameRule.MOB_GRIEFING, b);
                getConfig().set(key, b);
            }
            case "fixed-time" -> {
                long t = value.equalsIgnoreCase("off") ? -1 : Long.parseLong(value);
                if (t >= 0) {
                    w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                    w.setTime(t);
                } else {
                    w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
                }
                getConfig().set(key, t);
            }
            default -> {
            }
        }
    }

    /** 应用单条规则到所有托管世界（已加载的立即生效；未加载的写入配置重启生效）。返回是否成功。 */
    private boolean applyGlobalRule(String rule, String value, CommandSender sender) {
        String canonical = rule.toLowerCase().replace("-", "").replace("_", "");
        String keyRule = switch (canonical) {
            case "keepinventory", "keepinv", "keep" -> "keep-inventory";
            case "pvp" -> "pvp";
            case "difficulty", "diff" -> "difficulty";
            case "mobspawning", "spawning", "mob" -> "mob-spawning";
            case "weathercycle", "weather" -> "weather-cycle";
            case "firespread", "fire" -> "fire-spread";
            case "mobgriefing", "griefing", "explosion" -> "mob-griefing";
            case "fixedtime", "time" -> "fixed-time";
            default -> null;
        };
        if (keyRule == null) {
            return false;
        }
        // 校验值
        if ("difficulty".equals(keyRule)) {
            try {
                Difficulty.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException ex) {
                return false;
            }
        } else if ("fixed-time".equals(keyRule)) {
            if (!value.equalsIgnoreCase("off") && !value.matches("-?\\d+")) {
                return false;
            }
        } else if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            return false;
        }
        ConfigurationSection sec = getConfig().getConfigurationSection("worlds");
        boolean any = false;
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                ConfigurationSection ws = sec.getConfigurationSection(name);
                if (ws == null || !ws.getBoolean("enabled", true)) {
                    continue;
                }
                World w = Bukkit.getWorld(name);
                if (w == null) {
                    // 世界未加载：只写配置，重启后由 applyRules 套用
                    ws.set(keyRule, value);
                    any = true;
                    continue;
                }
                applyRule(w, name, keyRule, value);
                any = true;
            }
        }
        if (any) {
            saveConfig();
            send(sender, "&a已把规则 &e" + keyRule + " = " + value + "&a 应用到所有托管世界（重启后仍生效）。");
        }
        return any;
    }

    /** 创建新世界并写入配置（重启后仍自动加载）。 */
    private World createManagedWorld(String name, CommandSender sender) {
        return createManagedWorld(name, sender, false);
    }

    private World createManagedWorld(String name, CommandSender sender, boolean voidWorld) {
        if (Bukkit.getWorld(name) != null) {
            send(sender, "&c世界 &e" + name + "&c 已存在。");
            return null;
        }
        ConfigurationSection ws = getConfig().createSection("worlds." + name);
        ws.set("enabled", true);
        ws.set("environment", "NORMAL");
        ws.set("type", voidWorld ? "VOID" : "FLAT");
        ws.set("fixed-time", 6000);
        ws.set("difficulty", "NORMAL");
        ws.set("pvp", false);
        ws.set("mob-spawning", false);
        ws.set("weather-cycle", false);
        ws.set("fire-spread", false);
        ws.set("mob-griefing", false);
        ws.set("keep-inventory", true);
        saveConfig();
        if (isTemplate(name)) {
            // 模板只登记、不加载：设计者要建图时再 /gw load
            send(sender, "&a已登记模板世界 &e" + name + "&7（未加载，用 &f/gw load " + name + "&7 进入编辑）");
            return null;
        }
        World w = loadOrCreate(name, ws);
        if (w != null) {
            managed.put(name, w);
        }
        return w;
    }

    // ---------- 命令 ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "list" -> {
                send(sender, "&6====== GameWorlds 世界列表 ======");
                for (World w : Bukkit.getWorlds()) {
                    String tag = managed.containsKey(w.getName()) ? "&a[托管]" : "&7[普通]";
                    send(sender, tag + " &f" + w.getName() + " &7(pvp=" + w.getPVP()
                            + " 难度=" + w.getDifficulty() + " 玩家=" + w.getPlayers().size() + ")");
                }
                // 已登记但**没加载**的世界（模板默认就是这种）也要列出来，
                // 否则设计者 /gw list 根本看不到自己能编辑哪些地图。
                ConfigurationSection cfgSec = getConfig().getConfigurationSection("worlds");
                if (cfgSec != null) {
                    java.util.List<String> unloaded = new java.util.ArrayList<>();
                    for (String name : cfgSec.getKeys(false)) {
                        if (Bukkit.getWorld(name) == null) {
                            unloaded.add(name);
                        }
                    }
                    if (!unloaded.isEmpty()) {
                        send(sender, "&7------ 未加载（用 &f/gw tp <名字>&7 自动加载）------");
                        for (String name : unloaded) {
                            String tag = isTemplate(name) ? "&b[模板]" : "&7[持久·未加载]";
                            send(sender, tag + " &f" + name);
                        }
                    }
                }
                return true;
            }
            case "tp" -> {
                if (!admin(sender)) return true;
                if (!(sender instanceof Player p)) {
                    send(sender, "&c该命令需要玩家执行。");
                    return true;
                }
                if (args.length < 2) {
                    send(sender, "&c用法：/gw tp <世界名>");
                    return true;
                }
                World w = Bukkit.getWorld(args[1]);
                if (w == null) {
                    // 已登记但未加载（模板默认不加载）→ 自动加载再传送，别让设计者记两条命令
                    ConfigurationSection ws = getConfig()
                            .getConfigurationSection("worlds." + args[1]);
                    if (ws == null) {
                        send(sender, "&c世界 &e" + args[1] + "&c 不存在（用 /gw list 查看）。");
                        return true;
                    }
                    send(sender, "&7世界 &f" + args[1] + " &7还没加载，正在加载…");
                    w = loadOrCreate(args[1], ws);
                    if (w == null) {
                        send(sender, "&c加载失败，见控制台日志。");
                        return true;
                    }
                    managed.put(args[1], w);
                }
                Location spawn = w.getSpawnLocation();
                p.teleport(spawn);
                send(sender, "&a已传送到世界 &e" + w.getName() + "&a 的出生点。");
                return true;
            }
            case "create" -> {
                if (!admin(sender)) return true;
                // 参数：[--persist] <世界名> [void]
                // 语义（D19，见 docs/世界命名与前缀.md）：
                //   默认          -> 造**模板**：无已知前缀则自动补 tmpl_，**不自动加载**
                //   --persist     -> 造**持久世界**：名字原样，开服自动加载（主城这类）
                //   room_ 前缀    -> 一律拒绝（房间世界由 /hub room create 建，引擎会自动回收）
                boolean persist = false;
                int nameIdx = 1;
                while (nameIdx < args.length && args[nameIdx].startsWith("--")) {
                    String opt = args[nameIdx].toLowerCase(java.util.Locale.ROOT);
                    if (opt.equals("--persist") || opt.equals("-p")) {
                        persist = true;
                    } else {
                        send(sender, "&c未知选项 &e" + args[nameIdx]
                                + "&c。用法：/gw create [--persist] <世界名> [void]");
                        return true;
                    }
                    nameIdx++;
                }
                if (args.length <= nameIdx) {
                    send(sender, "&c用法：/gw create [--persist] <世界名> [void]\n"
                            + "&7  默认造**模板**（自动补 tmpl_，不自动加载）\n"
                            + "&7  --persist 造**持久世界**（名字原样，开服自动加载）");
                    return true;
                }
                String raw = args[nameIdx].toLowerCase(java.util.Locale.ROOT);
                if (raw.matches(".*[^a-z0-9_\\-].*")) {
                    send(sender, "&c世界名只能包含小写字母、数字、下划线和连字符。");
                    return true;
                }
                // 前缀与"造什么"必须一致，矛盾就拒绝（D19）：
                //   room_      任何情况下都拒绝（引擎独占、会被自动回收）
                //   tmpl_ + --persist  拒绝（持久世界不能是模板）
                //   world_ + 默认      拒绝（那是持久世界的名字，建成模板会误导）
                if (raw.startsWith(PREFIX_ROOM)) {
                    send(sender, "&croom_ 前缀是房间引擎独占的（房间会被自动回收），不能用 /gw 创建。"
                            + "\n&7要开房间请用 &f/hub room create <模板>");
                    return true;
                }
                if (persist && raw.startsWith(PREFIX_TEMPLATE)) {
                    send(sender, "&c--persist 要造的是**持久世界**，名字不能带 " + PREFIX_TEMPLATE
                            + " 前缀（那是模板）。\n&7要么去掉 --persist（造模板），要么换个名字。");
                    return true;
                }
                if (!persist && raw.startsWith(PREFIX_LEGACY)) {
                    send(sender, "&c" + PREFIX_LEGACY + " 是持久世界的名字前缀，建成模板会误导。"
                            + "\n&7要造持久世界请加 &f--persist&7；要造模板请换个不以 " + PREFIX_LEGACY + " 开头的名字。");
                    return true;
                }
                String name;
                if (persist) {
                    name = raw;                       // 持久世界：原样
                } else if (raw.startsWith(PREFIX_TEMPLATE)) {
                    name = raw;                       // 已经是模板名，不重复补
                } else {
                    name = PREFIX_TEMPLATE + raw;     // 默认造模板
                    send(sender, "&7世界名未带前缀，按模板处理 → &f" + name
                            + "&7（要持久世界请加 &f--persist&7）");
                }
                boolean voidWorld = args.length > nameIdx + 1
                        && args[nameIdx + 1].equalsIgnoreCase("void");
                World w = createManagedWorld(name, sender, voidWorld);
                if (w != null) {
                    send(sender, "&a已创建" + (voidWorld ? "虚空" : "超平坦") + "世界 &e" + name
                            + "&a（固定白天，禁刷怪）。用 /gw tp " + name + " 前往。");
                    if (voidWorld) {
                        send(sender, "&7虚空世界没有任何方块：先 &f/gamemode creative &7并飞起来再建场地。");
                    }
                } else {
                    send(sender, "&c创建失败，请查看控制台日志。");
                }
                return true;
            }
            case "rules" -> {
                if (!admin(sender)) return true;
                String name = args.length >= 2 ? args[1] : null;
                if (name == null) {
                    send(sender, "&c用法：/gw rules <世界名>（查看当前生效的世界规则）");
                    return true;
                }
                World w = Bukkit.getWorld(name);
                if (w == null) {
                    send(sender, "&c世界 &e" + name + "&c 不存在。");
                    return true;
                }
                send(sender, "&6世界 &e" + name + "&6 当前规则：");
                send(sender, "&7pvp=&e" + w.getPVP() + " &7难度=&e" + w.getDifficulty()
                        + " &7怪物=&e" + w.getGameRuleValue(GameRule.DO_MOB_SPAWNING));
                send(sender, "&7时间流逝=&e" + w.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE)
                        + " &7天气循环=&e" + w.getGameRuleValue(GameRule.DO_WEATHER_CYCLE));
                send(sender, "&7火蔓延=&e" + w.getGameRuleValue(GameRule.DO_FIRE_TICK)
                        + " &7爆炸破坏地形=&e" + w.getGameRuleValue(GameRule.MOB_GRIEFING)
                        + " &7当前时间=&e" + w.getTime());
                return true;
            }
            case "delete" -> {
                if (!admin(sender)) return true;
                if (args.length < 2) {
                    send(sender, "&c用法：/gw delete <世界名> confirm（删除托管世界，文件夹与配置一并清除）");
                    return true;
                }
                String name = args[1].toLowerCase();
                boolean confirm = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
                if (!managed.containsKey(name)) {
                    send(sender, "&c世界 &e" + name + "&c 不是 GameWorlds 托管的世界（主城等系统世界不可删除）。");
                    return true;
                }
                World w = Bukkit.getWorld(name);
                if (w != null && !w.getPlayers().isEmpty()) {
                    send(sender, "&c世界 &e" + name + "&c 中还有玩家，请先让他们离开再删除。");
                    return true;
                }
                if (!confirm) {
                    send(sender, "&c删除世界将清空该世界所有内容且不可恢复！确认请再次输入：");
                    send(sender, "&e/gw delete " + name + " confirm");
                    return true;
                }
                File folder = w != null ? w.getWorldFolder()
                        : new File(Bukkit.getWorldContainer(), "world/dimensions/minecraft/" + name);
                if (w != null && !Bukkit.unloadWorld(name, true)) {
                    send(sender, "&c世界 &e" + name + "&c 卸载失败，无法删除。");
                    return true;
                }
                if (folder != null && folder.exists() && !deleteFolder(folder)) {
                    send(sender, "&c世界文件夹删除失败，请手动删除：" + folder.getAbsolutePath());
                }
                managed.remove(name);
                getConfig().set("worlds." + name, null);
                saveConfig();
                send(sender, "&a世界 &e" + name + "&a 已删除（文件夹与配置已清除）。");
                return true;
            }
            case "global" -> {
                if (!admin(sender)) return true;
                if (args.length < 3) {
                    send(sender, "&c用法：/gw global <规则> <值>（应用到所有托管世界并保存，重启后仍生效）");
                    send(sender, "&7规则：&ekeepinventory&7 死亡不掉落 ｜ &epvp&7 允许PVP ｜ &edifficulty&7 难度 ｜ &emob-spawning&7 刷怪");
                    send(sender, "&7规则：&efire-spread&7 火蔓延 ｜ &emob-griefing&7 爆炸破坏地形 ｜ &eweather-cycle&7 天气循环 ｜ &efixed-time&7 固定时间(数字或off)");
                    send(sender, "&7示例：&e/gw global keepinventory true &7（所有世界开启死亡不掉落）");
                    return true;
                }
                if (!applyGlobalRule(args[1].toLowerCase(), args[2], sender)) {
                    send(sender, "&c无效规则或值：&e" + args[1] + " " + args[2] + "&c（/gw global 查看可用规则）");
                }
                return true;
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                loadWorlds();
                send(sender, "&a已重载配置并重新套用世界规则。托管世界：" + (managed.isEmpty() ? "无" : String.join(", ", managed.keySet())));
                return true;
            }
            default -> help(sender);
        }
        return true;
    }

    /** 递归删除文件夹（删除世界用）。 */
    private boolean deleteFolder(File folder) {
        File[] all = folder.listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.isDirectory()) {
                    if (!deleteFolder(f)) return false;
                } else {
                    if (!f.delete()) return false;
                }
            }
        }
        return folder.delete();
    }

    private boolean admin(CommandSender sender) {
        if (sender instanceof Player p ? p.isOp() || p.hasPermission("gameworlds.admin") : true) {
            return true;
        }
        send(sender, "&c只有管理员（OP）可以使用该命令。");
        return false;
    }

    private void help(CommandSender sender) {
        send(sender, "&6====== 小游戏主指令 ======");
        send(sender, "&e/hp &f- 烫手山芋  &e/tr &f- TNT Run  &e/duel &f- 单挑 PvP");
        send(sender, "&e/gtb &f- 建筑猜猜乐  &e/gw &f- 多世界管理");
        send(sender, "&7游戏世界：&fworld_gtb&7 猜猜乐 ｜ &fworld_duel&7 单挑 ｜ &fworld_hp&7 山芋 ｜ &fworld_tr&7 TNT Run");
        send(sender, "&6====== GameWorlds (/gw) 指令用法 ======");
        send(sender, "&e/gw list&f - 查看所有世界及状态（PvP/难度/在线人数）");
        boolean isAdmin = sender instanceof Player p ? p.isOp() || p.hasPermission("gameworlds.admin") : true;
        if (isAdmin) {
            send(sender, "&e/gw tp <世界名>&f - 传送到世界出生点（去新世界搭场地用）");
            send(sender, "&e/gw create <世界名> [void]&f - 创建新世界并写入配置，重启后自动加载（加 void = 虚空世界）");
            send(sender, "&e/gw delete <世界名> confirm&f - 删除托管世界（文件夹与配置一并清除，需二次确认）");
            send(sender, "&e/gw rules <世界名>&f - 查看指定世界当前生效的规则");
            send(sender, "&e/gw global <规则> <值>&f - 把规则应用到所有托管世界并保存（如 keepinventory true 死亡不掉落）");
            send(sender, "&7规则：&ekeepinventory&7 死亡不掉落 ｜ &epvp&7 ｜ &edifficulty&7 ｜ &emob-spawning&7 ｜ &efire-spread&7 ｜ &emob-griefing&7 ｜ &eweather-cycle&7 ｜ &efixed-time(数字或off)");
            send(sender, "&e/gw reload&f - 重载配置并重新套用所有世界规则");
            send(sender, "&7世界名示例：&fworld_gtb / world_duel / world_hp / world_tr");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("list", "tp", "create", "delete", "rules", "global", "reload")) {
                if (s.startsWith(args[0].toLowerCase())) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("global")) {
            for (String s : List.of("keep-inventory", "pvp", "difficulty", "mob-spawning",
                    "weather-cycle", "fire-spread", "mob-griefing", "fixed-time")) {
                if (s.startsWith(args[1].toLowerCase())) {
                    out.add(s);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("global")) {
            if (args[1].equalsIgnoreCase("difficulty")) {
                for (String s : List.of("PEACEFUL", "EASY", "NORMAL", "HARD")) {
                    if (s.toLowerCase().startsWith(args[2].toLowerCase())) {
                        out.add(s);
                    }
                }
            } else if (args[1].equalsIgnoreCase("fixed-time")) {
                out.add("off");
            } else {
                for (String s : List.of("true", "false")) {
                    if (s.startsWith(args[2].toLowerCase())) {
                        out.add(s);
                    }
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("tp") || args[0].equalsIgnoreCase("rules"))) {
            for (World w : Bukkit.getWorlds()) {
                if (w.getName().startsWith(args[1].toLowerCase())) {
                    out.add(w.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
            for (String name : managed.keySet()) {
                if (name.startsWith(args[1].toLowerCase())) {
                    out.add(name);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("delete") && args[2].toLowerCase().startsWith("c")) {
            out.add("confirm");
        }
        return out;
    }

    private void send(CommandSender sender, String msg) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }
}
