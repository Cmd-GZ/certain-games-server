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
    // ---------- 世界命名：**统一走 com.hub.world.WorldNames**（决策 D19） ----------
    // 规则只有一份实现（MiniGameLib），这里不再自己判前缀 —— 之前就是两处实现、迟早漂移。
    static final String PREFIX_TEMPLATE = com.hub.world.WorldNames.TEMPLATE;
    static final String PREFIX_ROOM = com.hub.world.WorldNames.ROOM;
    static final String PREFIX_LEGACY = com.hub.world.WorldNames.LEGACY;

    static boolean hasKnownPrefix(String name) {
        return com.hub.world.WorldNames.hasKnownPrefix(name);
    }

    static boolean isTemplate(String name) {
        return com.hub.world.WorldNames.isTemplate(name);
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

    // ---------- no-preserve-world 的三次确认（危险操作门） ----------

    /** 待确认的危险操作：key = 发送者|动作|名字。 */
    private final java.util.Map<String, int[]> pendingDanger = new java.util.concurrent.ConcurrentHashMap<>();
    /** 危险确认的状态：上次操作时间（用于超时失效）。 */
    private final java.util.Map<String, Long> pendingDangerAt = new java.util.concurrent.ConcurrentHashMap<>();
    /** 确认次数与超时：必须**连发三次同一条命令**，且两次间隔不超过 2 分钟。 */
    private static final int DANGER_CONFIRMS = 3;
    private static final long DANGER_TIMEOUT_MS = 2 * 60 * 1000L;

    /**
     * 危险操作门：`no-preserve-world` 必须确认 {@value #DANGER_CONFIRMS} 次。
     *
     * <p>为什么是三次：普通 `delete` 本身已经是"命令 + confirm"两次；危险形态再高一级。
     * 实现方式是**连发同一条命令**（而不是记一堆待确认队列），因为运维最自然的动作就是
     * "把刚才那条再敲一遍"；超时或中间做了别的操作都会让计数失效。
     *
     * @return true = 已确认够次数，可以执行
     */
    private boolean dangerGate(CommandSender sender, String action, String name) {
        String key = sender.getName() + "|" + action + "|" + name;
        long now = System.currentTimeMillis();
        Long at = pendingDangerAt.get(key);
        int[] cnt = pendingDanger.get(key);
        if (at == null || cnt == null || now - at > DANGER_TIMEOUT_MS) {
            cnt = new int[]{0};
        }
        cnt[0]++;
        pendingDanger.put(key, cnt);
        pendingDangerAt.put(key, now);
        if (cnt[0] >= DANGER_CONFIRMS) {
            pendingDanger.remove(key);
            pendingDangerAt.remove(key);
            getLogger().warning("[危险操作已确认 " + DANGER_CONFIRMS + " 次] " + sender.getName()
                    + " -> " + action + " " + name);
            return true;
        }
        int left = DANGER_CONFIRMS - cnt[0];
        send(sender, "&c&l⚠ 危险操作：&e" + action + " " + name);
        send(sender, "&c这会对世界做 &l不做任何前缀/归属检查&c 的操作，可能造出"
                + "框架不认识的世界，或删掉不该删的东西。");
        send(sender, "&6确认进度 &e" + cnt[0] + "&7/&e" + DANGER_CONFIRMS
                + "&7，还需要 &e" + left + " &7次（2 分钟内连发同一条命令）：");
        send(sender, "&7  &f" + (action.startsWith("create") ? "/gw create no-preserve-world " : "/gw delete no-preserve-world ")
                + name);
        return false;
    }

    /**
     * `/gw create room <模板> [房间名]` —— 从模板**复制**一份房间。
     *
     * <p>逻辑全在 {@code MiniGameLib.RoomManager}：复制模板（异步）→ 删世界身份 →
     * 主线程 createWorld → 套规则 → 注册区域 → 记账。这里只做命令解析和回执，
     * **不复制任何房间逻辑**（复制一份就等于又造了个"两处实现"）。
     */
    private void createRoom(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "&c用法：/gw create room <模板> [房间名]");
            send(sender, "&7可用模板：&f/gw list &7里的 &b[模板]&7 项，或 &f/hub room templates");
            return;
        }
        String tpl = args[2];
        String desired = args.length >= 4 ? args[3] : null;
        send(sender, "&7正在从模板 &f" + tpl + " &7复制房间…");
        com.hub.room.RoomManager.createAsync(tpl, desired, room -> {
            if (room == null) {
                send(sender, "&c创建房间失败（模板不存在 / 目录缺失 / 达到上限）。详情见控制台与 /hub doctor。");
            } else {
                send(sender, "&a已创建房间 &f" + room.roomId + " &7（游戏 &f"
                        + room.template.gameKey() + "&7）。"
                        + "开局：&f/hub start " + room.roomId);
            }
        });
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
            case "load" -> {
                // `/gw create template X` 只**登记**不建目录（模板不自动加载），
                // 而它的提示语一直写着"用 /gw load X 进入编辑" —— 但以前**没有这个子命令**，
                // 提示指向了不存在的命令（重构前的残留）。这里把它补上：
                // 按需创建/加载一个已登记的世界（模板也能加载，用于建图）。
                if (!admin(sender)) return true;
                if (args.length < 2) {
                    send(sender, "&c用法：/gw load <世界名>");
                    return true;
                }
                String name = args[1];
                World loaded = Bukkit.getWorld(name);
                if (loaded != null) {
                    send(sender, "&7世界 &f" + name + " &7已经是加载状态。");
                    managed.put(name, loaded);
                    return true;
                }
                ConfigurationSection wsL = getConfig().getConfigurationSection("worlds." + name);
                if (wsL == null) {
                    send(sender, "&c世界 &e" + name + "&c 没有登记过（先 /gw create template " + name + "）。");
                    return true;
                }
                World wL = loadOrCreate(name, wsL);
                if (wL == null) {
                    send(sender, "&c加载失败，见控制台日志。");
                    return true;
                }
                managed.put(name, wL);
                send(sender, "&a已加载世界 &e" + name + "&7（用 /gw tp " + name + " 进去）");
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
                // 语法（D19 修订）：/gw create <kind> <名字...>
                //   template          造模板（自动补 tmpl_，不自动加载）
                //   persist           造持久世界（拒绝 tmpl_/room_ 前缀，开服加载）
                //   room              从模板复制一个房间（名字可省，由引擎生成）
                //   no-preserve-world 危险逃生舱：**不做任何前缀限制**（类 rm --no-preserve-root）
                String kind = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
                int nameIdx = 2;
                if (!kind.equals("template") && !kind.equals("persist")
                        && !kind.equals("room") && !kind.equals("no-preserve-world")) {
                    send(sender, "&c用法：/gw create <template|persist|room|no-preserve-world> <名字…>\n"
                            + "&7  &ftemplate&7          造模板（自动补 tmpl_，不自动加载）\n"
                            + "&7  &fpersist&7           造持久世界（名字原样，开服自动加载）\n"
                            + "&7  &froom&7              从模板复制一个房间（/gw create room <模板> [房间名]）\n"
                            + "&7  &fno-preserve-world&7 &c⚠ 危险：不做任何前缀限制，只在明确知道后果时使用");
                    return true;
                }
                if (kind.equals("room")) {
                    createRoom(sender, args);
                    return true;
                }
                if (args.length <= nameIdx) {
                    send(sender, "&c用法：/gw create " + kind + " <世界名> [void]");
                    return true;
                }
                boolean persist = kind.equals("persist");
                boolean dangerous = kind.equals("no-preserve-world");
                String raw = args[nameIdx].toLowerCase(java.util.Locale.ROOT);
                if (raw.matches(".*[^a-z0-9_\\-].*")) {
                    send(sender, "&c世界名只能包含小写字母、数字、下划线和连字符。");
                    return true;
                }
                // 前缀与"造什么"必须一致，矛盾就拒绝（D19）：
                //   room_      任何情况下都拒绝（引擎独占、会被自动回收）
                //   tmpl_ + --persist  拒绝（持久世界不能是模板）
                //   world_ + 默认      拒绝（那是持久世界的名字，建成模板会误导）
                if (dangerous) {
                    // ⚠ 逃生舱：完全不检查前缀，但必须三次确认（见 dangerGate）。
                    if (!dangerGate(sender, "create no-preserve-world", raw)) {
                        return true;
                    }
                    World w0 = createManagedWorld(raw, sender, args.length > nameIdx + 1
                            && args[nameIdx + 1].equalsIgnoreCase("void"));
                    return true;
                }
                if (raw.startsWith(PREFIX_ROOM)) {
                    send(sender, "&croom_ 前缀是房间引擎独占的（房间会被自动回收），不能用 /gw create 创建。"
                            + "\n&7要开房间请用 &f/gw create room <模板>");
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
                // 语法（与 create 对称）：/gw delete <kind> <名字> confirm
                //   kind 是**安全断言**：你声明它属于哪一类；名字对不上就拒绝。
                //   no-preserve-world = 不做任何检查的强制删除（危险）。
                String kind = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
                boolean kindKnown = kind.equals("template") || kind.equals("persist")
                        || kind.equals("room") || kind.equals("no-preserve-world");
                String name;
                boolean confirm;
                if (kindKnown) {
                    if (args.length < 3) {
                        send(sender, "&c用法：/gw delete " + kind + " <名字> confirm");
                        return true;
                    }
                    name = args[2].toLowerCase();
                    confirm = args.length >= 4 && args[3].equalsIgnoreCase("confirm");
                    if (!kind.equals("no-preserve-world")) {
                        String actual = switch (com.hub.world.WorldNames.classify(name)) {
                            case TEMPLATE -> "template";
                            case ROOM -> "room";
                            case PERSISTENT -> "persist";
                        };
                        if (!actual.equals(kind)) {
                            send(sender, "&c类目不匹配：&e" + name + "&c 实际是 &e" + actual
                                    + "&c，你声明的是 &e" + kind + "&c。");
                            send(sender, "&7（这是安全断言：写错就拒绝，避免误删）");
                            return true;
                        }
                    } else {
                        // 危险删除同样三次确认；通过后视为已 confirm，不再要求 confirm 关键字
                        if (!dangerGate(sender, "delete no-preserve-world", name)) {
                            return true;
                        }
                        confirm = true;
                    }
                } else {
                    // 兼容旧用法：/gw delete <世界名> [confirm]（按名字自动判定种类）
                    if (args.length < 2) {
                        send(sender, "&c用法：/gw delete <template|persist|room|no-preserve-world> <名字> confirm");
                        return true;
                    }
                    name = args[1].toLowerCase();
                    confirm = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
                    kind = "auto";
                }
                // 房间必须交给房间引擎删：它要先踢人 → unload → 删目录 → 撤销区域注册
                if (com.hub.world.WorldNames.isRoom(name)) {
                    if (!confirm) {
                        send(sender, "&c删除房间会清空该房间的所有内容且不可恢复！确认请再次输入：");
                        send(sender, "&e/gw delete room " + name + " confirm");
                        return true;
                    }
                    boolean ok = com.hub.room.RoomManager.delete(name);
                    send(sender, ok ? "&a房间 &e" + name + "&a 已删除（世界、目录与区域注册一并清除）。"
                            : "&c房间 &e" + name + "&c 不在房间引擎的登记里（可能是手工建的世界，"
                            + "用 /gw delete no-preserve-world " + name + " confirm 强删）。");
                    return true;
                }
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
                    send(sender, "&e/gw delete " + (kind.equals("auto") ? "no-preserve-world" : kind)
                            + " " + name + " confirm");
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
            case "close" -> {
                // /gw close [template] <名字> —— 保存并卸载模板世界（设计者改完图收工用）。
                // 为什么只对模板：持久世界（主城）不能关；房间归房间引擎管（有 /hub room）。
                if (!admin(sender)) return true;
                String nm = args.length >= 2 && args[1].equalsIgnoreCase("template") && args.length >= 3
                        ? args[2] : (args.length >= 2 ? args[1] : "");
                if (nm.isBlank()) {
                    send(sender, "&c用法：/gw close <模板名>（保存并卸载；模板未加载时说一声就好）");
                    return true;
                }
                nm = nm.toLowerCase(java.util.Locale.ROOT);
                if (!com.hub.world.WorldNames.isTemplate(nm)) {
                    send(sender, "&c/gw close 只用于**模板**世界（" + PREFIX_TEMPLATE + " 前缀）。");
                    send(sender, "&7持久世界（主城这类）不能关；房间用 &f/gw delete room <房间>&7。");
                    return true;
                }
                World w = Bukkit.getWorld(nm);
                if (w == null) {
                    send(sender, "&7模板 &e" + nm + "&7 本来就没加载（不用关）。");
                    return true;
                }
                if (!w.getPlayers().isEmpty()) {
                    send(sender, "&c模板 &e" + nm + "&c 里还有 &e" + w.getPlayers().size()
                            + " &c名玩家，先让他们离开再关（避免正在编辑的内容被截断）。");
                    return true;
                }
                // unloadWorld(name, true) 会先把区块存盘再卸载
                if (!Bukkit.unloadWorld(nm, true)) {
                    send(sender, "&c模板 &e" + nm + "&c 卸载失败（可能有实体/ticket 占着），详见控制台。");
                    return true;
                }
                managed.remove(nm);
                send(sender, "&a模板 &e" + nm + "&a 已保存并卸载。"
                        + "&7（配置里的登记保留，下次 &f/gw tp " + nm + "&7 会自动加载）");
                getLogger().info("[/gw close] " + sender.getName() + " 保存并卸载模板 " + nm);
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
