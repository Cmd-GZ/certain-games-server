package com.hub;

import com.hub.api.HubAPI;
import com.hub.diagnostics.Failures;
import com.hub.game.GameInfo;
import com.hub.game.GameRegistry;
import com.hub.menu.MenuEditor;
import com.hub.menu.MenuLayout;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 主城插件：大厅、中央菜单、统一 tab、小游戏插件发现。
 * <p>
 * 面向小游戏插件的接口放在公共库 MiniGameLib 里（{@code com.hub.api.HubAPI}）：
 * 本插件在 onEnable 把 {@link HubManager} 作为实现注册进去，onDisable 注销。
 */
public class HubCorePlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private HubManager manager;
    private GameRegistry registry;
    private MenuLayout menuLayout;
    private MenuEditor menuEditor;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    /** 主城固定时间：1000 = 上午（0 日出 / 6000 正午 / 12000 日落 / 18000 午夜）。 */
    private static final long HUB_TIME = 1000;
    /** 时间跑偏超过这么多 tick 才拉回来，避免无意义的 setTime 造成客户端跳时间。 */
    private static final long HUB_TIME_TOLERANCE = 100L;
    /** 上次打印"已恢复白天晴天"的时间戳（毫秒），同类提示每分钟最多一条，避免刷屏。 */
    private long lastRestoreLog;

    @Override
    public void onEnable() {
        // 升级时把 games.yml 里**缺失**的游戏补上（只增不改）。放在启动时做，
        // 运维一眼能在日志里看到补了哪些，而不是等第一次 /hub join 才发现是旧的。
        try {
            gamesConfig();
        } catch (RuntimeException ex) {
            com.hub.diagnostics.Failures.recordAndWarn("HubCore.games.yml 启动合并", ex);
        }
        saveDefaultConfig();
        manager = new HubManager(this);
        registry = new GameRegistry(this);
        registry.rescan();
        menuLayout = new MenuLayout(this);
        menuEditor = new MenuEditor(this, menuLayout);
        // 头衔插件存在时，菜单右下角自动放一个头衔入口（已占用则不覆盖）
        if (getServer().getPluginManager().getPlugin("PlayerTitles") != null) {
            menuLayout.ensureTitleMenuEntry();
        }
        // 街机派对存在时，主菜单加一个仅 OP 可见的「派对设置」入口
        menuLayout.ensureArcadeMenuEntry();
        // 观战入口固定在主菜单左下角；先占这一格，后面的「迷你小游戏」迁移就挪到别的空槽
        menuLayout.ensureSpectateMenuEntry();
        // 把三个小游戏收进「迷你小游戏」子菜单（只迁移一次）
        menuLayout.ensureMiniGameList();
        // 把小游戏插件要用的能力交给公共库（HubManager 实现 HubService）
        HubAPI.register(manager);

        getServer().getPluginManager().registerEvents(new HubListener(this), this);
        // 菜单 GUI 的点击/拖拽/关闭 + 编辑用的聊天输入
        getServer().getPluginManager().registerEvents(menuEditor, this);

        // 主城世界规则：永久白天 + 永久晴天 + 不刷生物（重启自动应用，
        // 不依赖 Paper 配置——Paper 26.2 的 paper-world.yml 已无 gamerules 段）
        var mainWorld = Bukkit.getWorld("world");
        if (mainWorld != null) {
            applyHubWorldRules(mainWorld);
            getLogger().info("主城世界规则已应用：永久白天（时间固定 " + HUB_TIME + "）、永久晴天、不刷生物。");
        } else {
            getLogger().warning("未找到主世界 world，主城规则未应用。");
        }
        // 兜底：关掉昼夜/天气循环后时间与天气不会自己变，但 OP 的 /time set、/weather
        // 或别的插件仍能改；每 5 秒纠正一次，做到"永久"白天晴天。
        getServer().getScheduler().runTaskTimer(this, this::enforceHubWorldRules, 100L, 100L);

        PluginCommand hub = getCommand("hub");
        if (hub != null) {
            hub.setExecutor(this);
            hub.setTabCompleter(this);
        }

        // 每秒刷新 tab
        getServer().getScheduler().runTaskTimer(this, manager::refreshTab, 20L, 20L);
        // 每秒刷新观战提示；被观战的对局一结束就把观众送回大厅
        getServer().getScheduler().runTaskTimer(this, manager::tickSpectate, 20L, 20L);

        com.hub.gui.RoomGui.init(this);
        com.hub.RoomActions.init(this);
        getLogger().info("HubCore 已启用。大厅：" + manager.getLobby());
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            HubAPI.unregister(manager);
        }
    }

    /**
     * 一次性套用主城世界规则：固定早晨 + 关昼夜循环 + 关天气循环 + 清雨/清雷 + 不刷生物。
     * 关掉两个循环后，时间和天气都不会再自然变化（"永久白天晴天"）。
     */
    private void applyHubWorldRules(World w) {
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setTime(HUB_TIME);
        w.setStorm(false);
        w.setThundering(false);
        w.setWeatherDuration(0);
        w.setThunderDuration(0);
    }

    /**
     * 每 5 秒兜底：规则被改回去（/gamerule）、时间被 /time set 改跑偏、被 /weather 弄下雨打雷，
     * 都自动恢复。没被改动时这里只有几次读取，不写世界。
     */
    private void enforceHubWorldRules() {
        World w = Bukkit.getWorld("world");
        if (w == null) {
            return;
        }
        if (Boolean.TRUE.equals(w.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE))
                || Boolean.TRUE.equals(w.getGameRuleValue(GameRule.DO_WEATHER_CYCLE))) {
            applyHubWorldRules(w);
            getLogger().warning("主城昼夜/天气循环被打开，已重新关闭并恢复白天晴天。");
            return;
        }
        boolean changed = false;
        if (w.hasStorm() || w.isThundering()) {
            w.setStorm(false);
            w.setThundering(false);
            w.setWeatherDuration(0);
            w.setThunderDuration(0);
            changed = true;
        }
        if (Math.abs(w.getTime() - HUB_TIME) > HUB_TIME_TOLERANCE) {
            w.setTime(HUB_TIME);
            changed = true;
        }
        if (changed) {
            long now = System.currentTimeMillis();
            if (now - lastRestoreLog > 60_000L) {
                lastRestoreLog = now;
                getLogger().info("主城时间/天气被改动，已恢复为白天晴天（同类提示每分钟最多一条）。");
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // /hub 传送大厅
            if (sender instanceof Player p) {
                manager.teleportToLobby(p);
                p.sendMessage(LEGACY.deserialize("&7已传送回大厅。"));
            } else {
                sender.sendMessage("只有玩家可以执行。");
            }
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "set" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("只有玩家可以设置大厅。");
                    return true;
                }
                if (!isAdmin(p)) {
                    p.sendMessage(LEGACY.deserialize("&c只有 OP 或拥有 hub.admin 权限的玩家可以设置大厅。"));
                    return true;
                }
                manager.setLobby(p);
                p.sendMessage(LEGACY.deserialize("&a已将当前位置设为大厅。"));
            }
            case "menu" -> {
                if (sender instanceof Player p) {
                    // /hub menu = 玩家打开菜单；/hub menu <编辑子命令> = OP 编辑，见 editMenu
                    if (args.length == 1) {
                        manager.openMenu(p);
                    } else {
                        editMenu(p, args);
                    }
                } else {
                    sender.sendMessage("控制台不能打开菜单；编辑请用 /hub menu set ...");
                }
            }
            case "reload" -> {
                if (!isAdmin(sender)) {
                    sender.sendMessage(LEGACY.deserialize("&c只有 OP 或拥有 hub.admin 权限的玩家可以重载。"));
                    return true;
                }
                reloadConfig();
                registry.rescan();
                manager.refreshTab();
                sender.sendMessage(LEGACY.deserialize("&a已重载配置并重新扫描小游戏插件。"));
            }
            case "item" -> {
                Player target;
                if (args.length > 1) {
                    if (!isAdmin(sender)) {
                        sender.sendMessage(LEGACY.deserialize("&c只有 OP 或拥有 hub.admin 权限的玩家可以把回主城道具发给别人。"));
                        return true;
                    }
                    target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(LEGACY.deserialize("&c玩家 &e" + args[1] + "&c 不在线。"));
                        return true;
                    }
                } else if (sender instanceof Player sp) {
                    target = sp;
                } else {
                    sender.sendMessage("控制台请指定玩家：/hub item <玩家名>");
                    return true;
                }
                manager.giveHubItem(target);
                if (target != sender) {
                    sender.sendMessage(LEGACY.deserialize("&a已把回主城道具发给 &e" + target.getName() + "&a。"));
                }
            }
            case "lock" -> {
                if (!isAdmin(sender)) {
                    sender.sendMessage(LEGACY.deserialize("&c只有 OP 或拥有 hub.admin 权限的玩家可以切换物品栏锁定。"));
                    return true;
                }
                boolean next = !getConfig().getBoolean("lock-inventory", true);
                getConfig().set("lock-inventory", next);
                saveConfig();
                sender.sendMessage(LEGACY.deserialize(next
                        ? "&a已开启大厅物品栏锁定：在大厅不能捡/丢/移动物品。"
                        : "&c已关闭大厅物品栏锁定：在大厅可以自由捡/丢/移动物品。"));
            }
            case "games" -> {
                sender.sendMessage(LEGACY.deserialize("&7已登记游戏："));
                for (GameInfo g : registry.getGames()) {
                    sender.sendMessage(LEGACY.deserialize(" &7- &f" + g.displayName() + " &7(" + g.id() + "&7)"));
                }
            }
            case "arena" -> arenaCommand(sender, args);
            case "doctor" -> doctor(sender);
            case "room" -> roomCommand(sender, args);
            case "join" -> joinRoom(sender, args);
            case "create" -> createRoom(sender, args);
            case "start" -> startOrStop(sender, args, true);
            case "stop" -> startOrStop(sender, args, false);
            default -> sender.sendMessage(LEGACY.deserialize(
                    "&c用法：/hub [menu|set|reload|lock|item|games|arena|doctor|room|start|stop] &7，菜单编辑见 &f/hub menu help"));
        }
        return true;
    }

    // ---------- /hub arena：场地介绍（选场地菜单里那块羊毛的 lore） ----------

    private void arenaCommand(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) {
            sender.sendMessage(LEGACY.deserialize("&c只有 OP 或拥有 hub.admin 权限的玩家可以改场地介绍。"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(LEGACY.deserialize("&c用法：/hub arena desc <游戏> <场地> <介绍…> &7（多行用 &f|&7 分隔，&fclear&7 清除）"));
            sender.sendMessage(LEGACY.deserialize("&7游戏名见 &f/hub games&7；介绍会显示在选场地菜单对应羊毛的提示里。"));
            return;
        }
        String sub = args[1].toLowerCase();
        if (!sub.equals("desc") && !sub.equals("info")) {
            sender.sendMessage(LEGACY.deserialize("&c用法：/hub arena desc <游戏> <场地> <介绍…>"));
            return;
        }
        String game = registry.canonicalName(args[2]);
        if (registry.get(game) == null) {
            sender.sendMessage(LEGACY.deserialize("&e" + args[2] + "&c 不是已登记的游戏，用 &f/hub games&7 看看名字。"));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(LEGACY.deserialize("&c用法：/hub arena desc " + game + " <场地> <介绍…>"));
            return;
        }
        String arena = args[3];
        if (args.length == 4) {
            List<String> lines = menuLayout.arenaDesc(game, arena);
            if (lines.isEmpty()) {
                sender.sendMessage(LEGACY.deserialize("&7「" + game + "」的场地 &e" + arena + "&7 还没写过介绍。"));
            } else {
                sender.sendMessage(LEGACY.deserialize("&7「" + game + "」场地 &e" + arena + "&7 的介绍："));
                for (String line : lines) {
                    sender.sendMessage(LEGACY.deserialize("  " + line));
                }
            }
            return;
        }
        String text = join(args, 4);
        if (text.equalsIgnoreCase("clear")) {
            menuLayout.setArenaDesc(game, arena, null);
            menuLayout.save();
            sender.sendMessage(LEGACY.deserialize("&a已清除「" + game + "」场地 &e" + arena + "&a 的介绍。"));
            return;
        }
        menuLayout.setArenaDesc(game, arena, text);
        menuLayout.save();
        sender.sendMessage(LEGACY.deserialize("&a已设置「" + game + "」场地 &e" + arena + "&a 的介绍（&f|&a 分行）。"));
    }

    // ---------- /hub menu <子命令>：菜单可视化编辑 + 命令式编辑 ----------

    private void editMenu(Player p, String[] args) {
        if (!isAdmin(p)) {
            p.sendMessage(LEGACY.deserialize("&c只有 OP 或拥有 hub.admin 权限的玩家可以编辑菜单。"));
            return;
        }
        String sub = args[1].toLowerCase();
        if (sub.equals("help")) {
            menuHelp(p);
            return;
        }
        // 第 2 个参数是游戏名时 = 编辑该游戏的场地子菜单；否则是槽位或直接跟子命令
        String game = null;
        int idx = 2;
        if (args.length > 2 && !isNumber(args[2])) {
            game = args[2];
            idx = 3;
        }
        MenuLayout.Place place = game == null ? MenuLayout.Place.MAIN : MenuLayout.Place.SUB;
        if (place == MenuLayout.Place.SUB && !manager.arenasOf(game).isEmpty()) {
            menuLayout.generateDefaultSub(game); // 首次编辑某游戏时按实时场地铺好槽位
        }
        if (sub.equals("edit")) {
            menuEditor.startEdit(p, place, game);
            return;
        }
        if (sub.equals("cancel")) {
            menuEditor.stopEdit(p, true);
            return;
        }
        int slot = (idx < args.length && isNumber(args[idx])) ? Integer.parseInt(args[idx]) : -1;
        if (slot >= 0) {
            slot = menuEditor.resolveSlot(p, game, slot);
        }
        if (slot < 0) {
            p.sendMessage(LEGACY.deserialize("&c请给出槽位：/hub menu " + sub + (game == null ? "" : " " + game) + " <槽位> ..."));
            return;
        }
        switch (sub) {
            case "set" -> {
                String material = (idx + 1 < args.length) ? args[idx + 1] : null;
                String action = (idx + 2 < args.length) ? args[idx + 2] : null;
                String name = (idx + 3 < args.length) ? String.join(" ", Arrays.copyOfRange(args, idx + 3, args.length)) : null;
                MenuLayout.Entry old = menuLayout.entry(place, game, slot);
                String newMat = material != null ? checkMaterial(p, material) : null;
                if (newMat == null) {
                    newMat = itemInHandName(p);
                }
                if (newMat == null) {
                    newMat = old != null ? old.material() : "STONE";
                }
                menuEditor.setSlot(p, place, game, slot, newMat,
                        name != null ? name : (old != null ? old.name() : null),
                        old != null ? old.lore() : null,
                        action != null ? action : (old != null ? old.action() : null));
            }
            case "move" -> {
                if (idx + 1 >= args.length || !isNumber(args[idx + 1])) {
                    p.sendMessage(LEGACY.deserialize("&c用法：/hub menu move <从槽位> <到槽位>"));
                    return;
                }
                menuEditor.moveSlot(p, place, game, slot, Integer.parseInt(args[idx + 1]));
            }
            case "name" -> {
                if (idx + 1 >= args.length) {
                    p.sendMessage(LEGACY.deserialize("&c用法：/hub menu name <槽位> <显示名>&7（用 &f&7 写颜色，如 &f&c红&7）"));
                    return;
                }
                menuLayout.setName(place, game, slot, join(args, idx + 1));
                menuLayout.save();
                p.sendMessage(LEGACY.deserialize("&a显示名已更新。"));
                menuEditor.reopen(p);
            }
            case "lore" -> {
                if (idx + 1 >= args.length) {
                    p.sendMessage(LEGACY.deserialize("&c用法：/hub menu lore <槽位> <第1行>|<第2行> &7（用 &f|&7 分行，&f|clear&7 恢复默认）"));
                    return;
                }
                String text = join(args, idx + 1);
                if (text.equalsIgnoreCase("clear")) {
                    menuLayout.setLore(place, game, slot, null);
                } else {
                    List<String> lines = new ArrayList<>();
                    for (String line : text.split("\\|")) {
                        lines.add(line.trim());
                    }
                    menuLayout.setLore(place, game, slot, lines);
                }
                menuLayout.save();
                p.sendMessage(LEGACY.deserialize("&a提示已更新。"));
                menuEditor.reopen(p);
            }
            case "del", "delete" -> menuEditor.deleteSlot(p, place, game, slot);
            case "action" -> {
                String[] actionArgs = (idx + 1 < args.length) ? Arrays.copyOfRange(args, idx + 1, args.length) : new String[0];
                menuEditor.bindAction(p, place, game, slot, actionArgs);
            }
            case "rows" -> {
                if (idx >= args.length || !isNumber(args[idx])) {
                    p.sendMessage(LEGACY.deserialize("&c用法：/hub menu rows <行数1-6>"));
                    return;
                }
                int rows = Math.max(1, Math.min(6, Integer.parseInt(args[idx])));
                getConfig().set(place == MenuLayout.Place.SUB ? ("menu.sub." + game + ".rows") : "menu.rows", rows);
                saveConfig();
                p.sendMessage(LEGACY.deserialize("&a菜单已改为 &e" + rows + " &a行。"));
            }
            case "title" -> {
                if (idx >= args.length) {
                    p.sendMessage(LEGACY.deserialize("&c用法：/hub menu title <标题>&7 或 &f/hub menu title <游戏> <标题>"));
                    return;
                }
                menuLayout.setTitle(place, game, join(args, idx));
                menuLayout.save();
                p.sendMessage(LEGACY.deserialize("&a标题已更新为 &f" + join(args, idx)));
            }
            case "reset" -> {
                if (place == MenuLayout.Place.SUB) {
                    getConfig().set("menu.sub." + game + ".items", null);
                } else {
                    getConfig().set("menu.items", null);
                }
                saveConfig();
                if (place == MenuLayout.Place.MAIN) {
                    menuLayout.generateDefaultMain();
                } else if (!manager.arenasOf(game).isEmpty()) {
                    menuLayout.generateDefaultSub(game);
                }
                p.sendMessage(LEGACY.deserialize("&a已恢复自动生成的布局。"));
                menuEditor.reopen(p);
            }
            default -> menuHelp(p);
        }
    }

    private void menuHelp(Player p) {
        p.sendMessage(LEGACY.deserialize("&6===== 菜单编辑 ====="));
        p.sendMessage(LEGACY.deserialize("&e/hub menu edit &7[游戏] &8- &7可视化编辑（拖动物品、点格子改外观）"));
        p.sendMessage(LEGACY.deserialize("&e/hub menu set &7[游戏] <槽位> [材质] [动作] [显示名]"));
        p.sendMessage(LEGACY.deserialize("&e/hub menu move &7[游戏] <从> <到> &8｜&e/hub menu del &7[游戏] <槽位>"));
        p.sendMessage(LEGACY.deserialize("&e/hub menu name &7[游戏] <槽位> <显示名> &8｜&e/hub menu lore &7[游戏] <槽位> <行1|行2>"));
        p.sendMessage(LEGACY.deserialize("&e/hub menu action &7[游戏] <槽位> game <游戏> | open <游戏> | arena <游戏> <场地> | cmd <命令> | none"));
        p.sendMessage(LEGACY.deserialize("&7动作写法用空格分隔（&fcmd gtb join&7）；写 &fcmd:gtb join&7 这种带冒号的也认。"));
        p.sendMessage(LEGACY.deserialize("&e/hub menu rows &7<行数> &8｜&e/hub menu title &7[游戏] <标题> &8｜&e/hub menu reset"));
        p.sendMessage(LEGACY.deserialize("&e/hub arena desc &7<游戏> <场地> <介绍…> &8- &7写选场地菜单里那块羊毛的提示（&f|&7 分行，&fclear&7 清除）"));
        p.sendMessage(LEGACY.deserialize("&7不带游戏名 = 主菜单；带游戏名 = 那个游戏的场地子菜单（如 /hub menu edit 单挑 PvP）。"));
    }

    private String checkMaterial(Player p, String name) {
        if (MenuLayout.matchMaterial(name) == null) {
            p.sendMessage(LEGACY.deserialize("&e没有叫 &f" + name + "&e 的材质，忽略这一项。"));
            return null;
        }
        return name.toUpperCase();
    }

    private String itemInHandName(Player p) {
        var item = p.getInventory().getItemInMainHand();
        return (item == null || item.getType().isAir()) ? null : item.getType().name();
    }

    private static String join(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    private static boolean isNumber(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return startsWith(Arrays.asList("menu", "set", "reload", "lock", "item", "games", "arena", "doctor", "room", "start", "stop"), args[0]);
        }
        if (args[0].equalsIgnoreCase("arena")) {
            if (args.length == 2) {
                return startsWith(Arrays.asList("desc"), args[1]);
            }
            if (args.length == 3) {
                return startsWith(gameNames(), args[2]);
            }
            if (args.length == 4) {
                String game = registry.canonicalName(args[2]);
                List<String> out = new ArrayList<>(manager.arenasOf(game).keySet());
                out.add("clear");
                return startsWith(out, args[3]);
            }
            return List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("menu")) {
            return startsWith(Arrays.asList("edit", "set", "move", "name", "lore", "action", "del", "rows", "title", "reset", "cancel", "help"), args[1]);
        }
        if (args[0].equalsIgnoreCase("menu")) {
            if (args.length == 3) {
                List<String> out = new ArrayList<>(gameNames());
                for (int i = 0; i <= 53; i++) {
                    out.add(String.valueOf(i));
                }
                return startsWith(out, args[2]);
            }
            String sub = args[1].toLowerCase();
            if (sub.equals("action") && args.length >= 5) {
                return startsWith(Arrays.asList("game:", "open:", "arena:", "cmd:", "none"), args[args.length - 1]);
            }
            if ((sub.equals("set") || sub.equals("move") || sub.equals("action")) && args.length >= 4) {
                List<String> out = new ArrayList<>(gameNames());
                for (int i = 0; i <= 53; i++) {
                    out.add(String.valueOf(i));
                }
                return startsWith(out, args[args.length - 1]);
            }
        }
        return List.of();
    }

    private List<String> gameNames() {
        List<String> out = new ArrayList<>();
        for (GameInfo g : registry.getGames()) {
            out.add(g.displayName());
        }
        return out;
    }

    private static List<String> startsWith(List<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase().startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    /** 管理权限：OP 或 plugin.yml 里声明的 hub.admin。 */
    /** 把 {@code com.foo.Bar#baz:123} 缩成 {@code Bar#baz:123}，doctor 里好读。 */
    private static String shortFrame(String frame) {
        if (frame == null) {
            return "?";
        }
        int slash = frame.lastIndexOf('.');
        return slash < 0 ? frame : frame.substring(slash + 1);
    }

    // ---------- /hub join | /hub create：高层语义（D24） ----------
    //
    // 分层：/hub 面向玩家与运维，只做「校验 → 以框架身份做原子操作 → 初始化玩家状态」；
    // 真正的世界操作在 /gw（create room / tp），玩家**不需要**拥有 /gw 的权限。
    // 这样 GUI 与命令走同一条路径，将来换实现只改一处。

    /** `/hub join <房间>`：校验 → 传送进房间世界（框架身份）→ 跑该游戏的 join-command。 */
    private void joinRoom(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player p)) {
            sender.sendMessage(LEGACY.deserialize("&c该命令需要玩家执行（控制台用 /hub room list 查看房间）。"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(LEGACY.deserialize("&c用法：/hub join <房间名>"));
            sender.sendMessage(LEGACY.deserialize("&7房间列表：&f/hub room list"));
            return;
        }
        if (!com.hub.RoomActions.join(p, args[1])) {
            sender.sendMessage(LEGACY.deserialize("&c没有这个房间，或房间世界还没加载好：&f" + args[1]));
        }
    }

    /**
     * `/hub create <游戏> <地图>`：校验 → `map → 模板世界` → 建房间 → 初始化。
     *
     * <p>⚠️ **地图与模板世界不是 1:1**（一个模板世界可能装多张图）。当前快照里模板登记只有
     * `key` + `folder`，没有"地图"这一层，所以这里的 `<地图>` 先按**模板键或模板目录名**匹配；
     * 等模板登记加上地图清单后，这里换成"按地图查所属模板"即可，**命令形式不用变**。
     */
    private void createRoom(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(LEGACY.deserialize("&c用法：/hub create <游戏> <地图> [房间名]"));
            sender.sendMessage(LEGACY.deserialize("&7可用模板：&f/hub room templates"));
            return;
        }
        String game = args[1];
        String map = args[2];

        // ① **地图登记表**（maps.yml）优先：新模型下这是唯一事实源。
        //    一张地图可能散在多个世界（派对就是 3 个），所以这里可能一次造出多个世界实例。
        com.hub.room.GameMap gameMap = com.hub.room.GameMaps.get(game, map);
        if (gameMap == null) {
            String canonical = registry.canonicalName(game);
            if (canonical != null && !canonical.equalsIgnoreCase(game)) {
                gameMap = com.hub.room.GameMaps.get(canonical, map);
            }
        }
        if (gameMap != null) {
            final String desiredId = args.length >= 4 ? args[3] : null;
            sender.sendMessage(LEGACY.deserialize("&7正在按地图 &f" + map + " &7创建房间（"
                    + gameMap.worlds().size() + " 个世界）…"));
            com.hub.room.RoomManager.Room room =
                    com.hub.room.RoomManager.createForMap(gameMap, desiredId);
            if (room == null) {
                sender.sendMessage(LEGACY.deserialize("&c创建失败（详情见控制台）：&f" + map));
                return;
            }
            sender.sendMessage(LEGACY.deserialize("&a已创建房间 &f" + room.roomId
                    + "&a（地图 &f" + map + "&a，&f" + room.instances().size()
                    + "&a 个世界）。加入：&f/hub join " + room.roomId));
            return;
        }

        // 解析 <地图>：
        //   ① 先看它是不是某个模板里声明的**场地名**（模板 regions[].name）——
        //      这是"一个模板世界装多张地图"的正解（如 world_mini_game 里的 群系 / hillking）；
        //   ② 再退回按模板键 / 目录名匹配（单图模板的常见写法）。
        String templateKey = null;
        String arenaName = "";
        if (templateKey == null) {
            // 兼容旧用法：把 <map> 当作**模板键或目录名**直接匹配（模板已不属于任何游戏）
            for (com.hub.room.WorldTemplate t : com.hub.room.RoomManager.templates()) {
                if (t.key().equalsIgnoreCase(map) || t.folder().equalsIgnoreCase(map)
                        || t.folder().equalsIgnoreCase("tmpl_" + map) || t.key().equalsIgnoreCase("tmpl_" + map)) {
                    templateKey = t.key();
                    break;
                }
            }
        }
        if (templateKey == null) {
            sender.sendMessage(LEGACY.deserialize("&c游戏 &f" + game + " &c下没有地图 &f" + map
                    + "&c（模板列表见 &f/hub room templates&c）"));
            return;
        }
        final String tpl = templateKey;
        final String arenaForRoom = arenaName;
        sender.sendMessage(LEGACY.deserialize("&7正在从模板 &f" + tpl + " &7创建房间…"));
        String desired = args.length >= 4 ? args[3] : null;   // 可选：指定房间名
        com.hub.room.RoomManager.createAsync(tpl, desired, arenaForRoom, room -> {
            if (room == null) {
                sender.sendMessage(LEGACY.deserialize("&c创建失败（模板目录缺失 / 达到上限），详情见控制台与 /hub doctor。"));
                return;
            }
            getLogger().info("[/hub create] " + sender.getName() + " 建房间 " + room.roomId
                    + "（游戏 " + game + "，地图 " + map + "，模板 " + tpl
                    + (arenaForRoom.isBlank() ? "" : "，场地名 " + arenaForRoom) + "）");
            sender.sendMessage(LEGACY.deserialize("&a已创建房间 &f" + room.roomId
                    + "&7（模板 &f" + tpl + "&7）。加入：&f/hub join " + room.roomId));
        });
    }

    // ---------- /hub start|stop：统一控制面（D18「先配置转发，后真接口」） ----------

    /**
     * 统一的"开一局 / 停一局"。
     *
     * <p>为什么要有：25 个游戏各有一套 {@code /hill start}、{@code /tntrun start {arena}}……
     * 运维要记 25 套。这里把它们收成一套 {@code /hub start <房间>}，映射放在
     * {@code plugins/HubCore/games.yml}。
     *
     * <p>顺序：先让房间里的玩家各自执行 {@code join-command}（多数游戏的 join 是玩家命令），
     * 再由控制台执行 {@code start-command}（多数游戏的 start 是管理命令）。
     */
    private void startOrStop(CommandSender sender, String[] args, boolean start) {
        if (!isAdmin(sender)) {
            sender.sendMessage(LEGACY.deserialize("&c只有 OP 或拥有 hub.admin 权限的玩家可以执行。"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(LEGACY.deserialize("&c用法：/hub " + (start ? "start" : "stop") + " <房间名>"));
            sender.sendMessage(LEGACY.deserialize("&7房间列表：&f/hub room list"));
            return;
        }
        String roomId = args[1];
        var room = com.hub.room.RoomManager.room(roomId);
        if (room == null) {
            sender.sendMessage(LEGACY.deserialize("&c没有这个房间：&f" + roomId + " &7（用 &f/hub room list&7 看）"));
            return;
        }
        String gameKey = room.game().isEmpty() ? room.template.key() : room.game();
        var cfg = gamesConfig();
        String path = "games." + gameKey + "." + (start ? "start" : "stop") + "-command";
        String cmd = cfg.getString(path);
        if (cmd == null || cmd.isBlank()) {
            sender.sendMessage(LEGACY.deserialize("&c游戏 &f" + gameKey + " &c没有配置 "
                    + (start ? "start" : "stop") + "-command。"));
            sender.sendMessage(LEGACY.deserialize("&7在 &fplugins/HubCore/games.yml &7里加一行："
                    + "&f" + path + ": \"<命令>\""));
            return;
        }
        cmd = cmd.replace("{arena}", "").trim();

        if (start) {
            String join = cfg.getString("games." + gameKey + ".join-command");
            if (join != null && !join.isBlank()) {
                join = join.replace("{arena}", "").trim();
                int n = 0;
                for (org.bukkit.entity.Player p : new java.util.ArrayList<>(room.world.getPlayers())) {
                    p.performCommand(join);
                    n++;
                }
                sender.sendMessage(LEGACY.deserialize("&7已让 &f" + n + " &7名玩家执行 &f/" + join));
            }
        }
        org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), cmd);
        sender.sendMessage(LEGACY.deserialize("&a已执行 &f/" + cmd + " &7（房间 &f" + roomId
                + "&7，游戏 &f" + gameKey + "&7）"));
        getLogger().info("[/hub " + (start ? "start" : "stop") + "] " + sender.getName()
                + " -> 房间 " + roomId + "（" + gameKey + "）：/" + cmd);
    }

    /**
     * 读（并按需生成、**按需补齐**）plugins/HubCore/games.yml。
     *
     * <p>{@code saveResource} **只在文件不存在时**写入，所以老服务器升级后，
     * 新加的游戏的 join-command 永远不会出现在磁盘上 —— 表现是 `/hub join` 对那个游戏
     * **静默什么都不做**（真实事故：升到 27 个游戏后，运行副本里还是 15 个的旧表）。
     *
     * <p>这里做的是**只增不改**的合并：以 jar 内的资源为准，把磁盘上**缺失**的
     * `games.<key>` 补进去；运维已经手改过的条目**一个字节都不动**（单一写者，R2）。
     */
    private org.bukkit.configuration.file.FileConfiguration gamesConfig() {
        java.io.File f = new java.io.File(getDataFolder(), "games.yml");
        if (!f.isFile()) {
            saveResource("games.yml", false);
            return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        }
        var onDisk = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        var shipped = new org.bukkit.configuration.file.YamlConfiguration();
        try (java.io.InputStream in = getResource("games.yml")) {
            if (in == null) {
                return onDisk;
            }
            shipped.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException
                 | RuntimeException ex) {
            // R3：失败要可见，但不该让整个插件起不来
            com.hub.diagnostics.Failures.recordAndWarn("HubCore.games.yml 合并", ex);
            return onDisk;
        }
        var shippedGames = shipped.getConfigurationSection("games");
        if (shippedGames == null) {
            return onDisk;
        }
        java.util.List<String> added = new java.util.ArrayList<>();
        for (String key : shippedGames.getKeys(false)) {
            if (onDisk.isConfigurationSection("games." + key) || onDisk.contains("games." + key)) {
                continue;   // 已存在（哪怕被运维改过）—— 一律不动
            }
            onDisk.set("games." + key, shipped.get("games." + key));
            added.add(key);
        }
        if (!added.isEmpty()) {
            try {
                onDisk.save(f);
                getLogger().info("games.yml 已补齐 " + added.size() + " 个游戏："
                        + String.join(", ", added) + "（已有的条目未改动）");
            } catch (java.io.IOException ex) {
                com.hub.diagnostics.Failures.recordAndWarn("HubCore.games.yml 保存", ex);
            }
        }
        return onDisk;
    }

    // ---------- /hub room：房间引擎的管理入口（R1 的验收入口，R3 之后保留给 OP 排障） ----------

    private void roomCommand(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) {
            sender.sendMessage(LEGACY.deserialize("&c只有 OP 或拥有 hub.admin 权限的玩家可以执行。"));
            return;
        }
        String sub = args.length < 2 ? "list" : args[1].toLowerCase();
        switch (sub) {
            case "actions", "act" -> {
                // 房间动作页的文本版（GUI 里是点出来的；控制台用它来验证内容）
                if (args.length < 3) {
                    sender.sendMessage(LEGACY.deserialize("&c用法：/hub room actions <房间名>"));
                    return;
                }
                for (String line : com.hub.gui.RoomGui.describe(
                        null, com.hub.gui.RoomGui.Mode.ROOM_ACTIONS, args[2])) {
                    sender.sendMessage(LEGACY.deserialize(line));
                }
            }
            case "gui", "ui" -> {
                // 选房 UI：玩家打开箱子界面；控制台输出同样的文本版（无客户端也能验证）
                String game = args.length >= 3 ? args[2] : null;
                if (sender instanceof org.bukkit.entity.Player p) {
                    com.hub.gui.RoomGui.open(p, game, com.hub.gui.RoomGui.Mode.ROOMS);
                } else {
                    for (String line : com.hub.gui.RoomGui.describe(game, com.hub.gui.RoomGui.Mode.ROOMS)) {
                        sender.sendMessage(LEGACY.deserialize(line));
                    }
                    for (String line : com.hub.gui.RoomGui.describe(game, com.hub.gui.RoomGui.Mode.TEMPLATES)) {
                        sender.sendMessage(LEGACY.deserialize(line));
                    }
                }
            }
            case "templates" -> {
                var tpls = com.hub.room.RoomManager.templates();
                if (tpls.isEmpty()) {
                    sender.sendMessage(LEGACY.deserialize("&7没有登记任何模板。"
                            + "把设计好的世界目录改名成 &ftmpl_xxx &7放进 "
                            + "&fworld/dimensions/minecraft/&7，再在 "
                            + "&fplugins/MiniGameLib/templates.yml &7里登记。"));
                    return;
                }
                sender.sendMessage(LEGACY.deserialize("&7模板 " + tpls.size() + " 个："));
                for (var t : tpls) {
                    sender.sendMessage(LEGACY.deserialize(" &7- &f" + t.key()
                            + " &7服务的地图见 &f/hub room templates"
                            + " &7目录=&f" + t.folder()
                            + " &7体积=&f" + (t.bytes() / 1024 / 1024) + "MB"
                            + (t.dir().isDirectory() ? "" : " &c(目录缺失)")));
                }
            }
            case "create" -> {
                if (args.length < 3) {
                    sender.sendMessage(LEGACY.deserialize("&c用法：/hub room create <模板键> [房间名]"));
                    return;
                }
                String tpl = args[2];
                String name = args.length >= 4 ? args[3] : null;
                final CommandSender replyTo = sender;
                sender.sendMessage(LEGACY.deserialize("&7正在从模板 &f" + tpl + " &7创建房间…"));
                // 异步创建：复制在异步线程，createWorld 回主线程（避免卡主线程 → 全服卡顿）
                com.hub.room.RoomManager.createAsync(tpl, name, room -> {
                    if (room == null) {
                        replyTo.sendMessage(LEGACY.deserialize("&c创建失败，原因见控制台与 /hub doctor。"));
                    } else {
                        replyTo.sendMessage(LEGACY.deserialize("&a已创建房间 &f" + room.roomId
                                + " &7（世界 &f" + room.world.getName() + "&7）。"
                                + "用 &f/gw tp " + room.roomId + " &7进去看。"));
                    }
                });
            }
            case "delete", "del" -> {
                if (args.length < 3) {
                    sender.sendMessage(LEGACY.deserialize("&c用法：/hub room delete <房间名>"));
                    return;
                }
                boolean ok = com.hub.room.RoomManager.delete(args[2]);
                sender.sendMessage(LEGACY.deserialize(ok
                        ? "&a已删除房间 &f" + args[2]
                        : "&c没有这个房间：&f" + args[2]));
            }
            case "info" -> {
                if (args.length < 3) {
                    sender.sendMessage(LEGACY.deserialize("&c用法：/hub room info <房间名>"));
                    return;
                }
                var room = com.hub.room.RoomManager.room(args[2]);
                if (room == null) {
                    sender.sendMessage(LEGACY.deserialize("&c没有这个房间：&f" + args[2]));
                    return;
                }
                sender.sendMessage(LEGACY.deserialize("&7房间 &f" + room.roomId));
                sender.sendMessage(LEGACY.deserialize(" &7模板 &f" + room.template.key()
                        + " &7游戏 &f" + (room.game().isEmpty() ? "-" : room.game()) + "&7地图 &f" + room.map()));
                sender.sendMessage(LEGACY.deserialize(" &7世界 &f"
                        + (room.world == null ? "未加载" : room.world.getName())
                        + " &7人数 &f" + room.playerCount()
                        + " &7年龄 &f" + room.ageSeconds() + "s"
                        + " &7空置 &f" + room.idleSeconds() + "s"));
            }
            default -> {
                var rooms = com.hub.room.RoomManager.rooms();
                sender.sendMessage(LEGACY.deserialize("&7房间 " + rooms.size() + " 个："));
                for (var r : rooms) {
                    sender.sendMessage(LEGACY.deserialize(" &7- &f" + r.describe()));
                }
                if (rooms.isEmpty()) {
                    sender.sendMessage(LEGACY.deserialize(" &7（空）用 &f/hub room templates &7看可用模板"));
                }
            }
        }
    }

    // ---------- /hub doctor：体检面板 ----------

    /**
     * 输出一份服务器体检报告。
     *
     * <p>对应 {@code docs/框架不变量.md} 的 <b>R3 失败可见</b>：让"悄悄出错的地方"有一个
     * 能被非开发者看懂的地方。目前包含四块：插件版本、被吞异常、玩家记录、世界规则。
     *
     * <p>设计约束：**只读**，不修改任何状态；控制台也能跑（方便进冒烟台做断言）。
     */
    private void doctor(CommandSender sender) {
        if (!isAdmin(sender)) {
            sender.sendMessage(LEGACY.deserialize("&c只有 OP 或拥有 hub.admin 权限的玩家可以执行。"));
            return;
        }
        List<String> out = new ArrayList<>();
        out.add("&8&m                                             ");
        out.add("&e&l小游戏服体检 &7(/hub doctor)");

        // ---- 1. 插件版本 ----
        var plugins = Bukkit.getPluginManager().getPlugins();
        List<String> names = new ArrayList<>();
        for (var pl : plugins) {
            names.add(pl.getName() + " " + pl.getDescription().getVersion());
        }
        names.sort(String::compareTo);
        out.add("&b[插件] &f" + plugins.length + " 个：&7" + String.join("&7, &f", names));

        // ---- 2. 被吞掉的异常 ----
        if (Failures.isEmpty()) {
            out.add("&b[被吞异常] &a0 条 &7（没有东西在悄悄失败）");
        } else {
            out.add("&b[被吞异常] &c" + Failures.total() + " 次 &7，最近 " + Failures.recent().size() + " 条：");
            List<Failures.Entry> recent = Failures.recent();
            for (int i = Math.max(0, recent.size() - 5); i < recent.size(); i++) {
                out.add("  &7- &f" + recent.get(i).line());
            }
            out.add("  &7按来源统计：");
            for (var e : Failures.counts()) {
                out.add("  &7- &f" + e.getKey() + " &7× &c" + e.getValue());
            }
        }

        // ---- 3. 玩家记录 ----
        var f = com.hub.data.PlayerStats.file();
        if (f.isFile()) {
            long size = f.length();
            String when = new java.text.SimpleDateFormat("MM-dd HH:mm")
                    .format(new java.util.Date(f.lastModified()));
            out.add("&b[玩家记录] &f" + f.getPath() + " &7(" + size + " B，改于 " + when + ")");
            out.add("  &7" + com.hub.data.PlayerStats.summary());
        } else {
            out.add("&b[玩家记录] &c文件不存在 &7" + f.getPath() + " &7（战绩会从空开始）");
        }

        // ---- 4. 区域声明（I2） ----
        var regions = com.hub.region.RegionRegistry.all();
        var conflicts = com.hub.region.RegionRegistry.conflicts();
        if (regions.isEmpty()) {
            out.add("&b[区域] &e还没有任何游戏声明地盘 &7（I2 未落地）");
            out.add("  &7→ 「玩 A 游戏影响 B 游戏」这类问题目前仍只能靠人记");
        } else {
            out.add("&b[区域] &f已声明 " + regions.size() + " 块，来自 "
                    + com.hub.region.RegionRegistry.owners() + " 个游戏");
            int shown = 0;
            for (var r : regions) {
                if (shown++ >= 8) {
                    out.add("  &7… 还有 " + (regions.size() - 8) + " 块（完整表见启动日志）");
                    break;
                }
                out.add("  &7- &f" + r.describe());
            }
            long cross = conflicts.stream()
                    .filter(c -> c.type() == com.hub.region.RegionRegistry.Conflict.Type.CROSS_GAME).count();
            if (conflicts.isEmpty()) {
                out.add("  &a未发现重叠");
            } else {
                out.add("  &c冲突 " + conflicts.size() + " 处（跨游戏 " + cross + "）");
                for (var c : conflicts) {
                    out.add("  &7- " + c.describe());
                }
            }
        }
        out.add("  &7提示：未声明地盘的游戏，别在它附近给别的游戏建场（会互相踩）");

        // ---- 5. 对局与会话（I1 / I4 / I5） ----
        var activeSessions = com.hub.session.Sessions.active();
        var byGame = com.hub.session.Sessions.activeByGame();
        if (activeSessions.isEmpty()) {
            out.add("&b[对局] &70 个活动对局 &7（会话模型已就绪，暂无游戏接入）");
        } else {
            out.add("&b[对局] &f" + activeSessions.size() + " 个活动对局：&7" + byGame);
            int shown = 0;
            for (var ses : activeSessions) {
                if (shown++ >= 6) {
                    out.add("  &7… 还有 " + (activeSessions.size() - 6) + " 个");
                    break;
                }
                out.add("  &7- &f" + ses.describe());
            }
        }
        out.add("&b[归属] &f" + com.hub.session.PlayerSessions.size() + " 人有归属"
                + " &7（在某个对局里；其余在大厅）");
        var i1Problems = com.hub.session.PlayerSessions.audit();
        if (i1Problems.isEmpty()) {
            out.add("  &a归属链自检通过 &7（I1：没有并列归属）");
        } else {
            out.add("  &c归属链自检发现 " + i1Problems.size() + " 个问题：");
            for (String problem : i1Problems) {
                out.add("  &7- &c" + problem);
            }
        }

        // ---- 6. 主线程卡顿（回答"为什么会突然卡一段"） ----
        long stalls = com.hub.diagnostics.TickWatchdog.totalStalls();
        if (stalls == 0) {
            out.add("&b[卡顿] &a未检测到异常阻塞 &7（阈值 " + 200 + "ms/次）");
        } else {
            out.add("&b[卡顿] &c" + stalls + " 次 &7，最严重 &c"
                    + com.hub.diagnostics.TickWatchdog.worstMs() + "ms");
            var recent = com.hub.diagnostics.TickWatchdog.recent();
            int shown = 0;
            for (int i = recent.size() - 1; i >= 0 && shown < 5; i--, shown++) {
                var st2 = recent.get(i);
                out.add("  &7- &f" + st2.ms() + "ms"
                        + (st2.suspect() == null ? "" : " &7@ &f" + st2.suspect()));
            }
            var offenders = com.hub.diagnostics.TickWatchdog.offenders();
            if (!offenders.isEmpty()) {
                out.add("  &7可疑来源（采样命中）：");
                int n = 0;
                for (var o : offenders) {
                    if (n++ >= 3) {
                        break;
                    }
                    out.add("  &7- &c" + o.getKey() + " &7× &f" + o.getValue());
                }
            } else {
                out.add("  &7（还没采到可疑帧——卡顿可能发生在启动早期，或卡在纯 JVM 代码里）");
            }
        }

        // ---- 7. 房间（模板 → 实例世界） ----
        var tpls = com.hub.room.RoomManager.templates();
        var rooms = com.hub.room.RoomManager.rooms();
        if (!com.hub.room.RoomManager.isReady()) {
            out.add("&b[房间] &70 个房间 &7（引擎未就绪，见 MiniGameLib/templates.yml）");
        } else {
            out.add("&b[房间] &f" + rooms.size() + " 个在用，模板 &f" + tpls.size() + " 个");
            for (var t : tpls) {
                out.add("  &7模板 &f" + t.describe()
                        + (t.dir().isDirectory() ? "" : " &c(目录缺失)"));
            }
            int shown = 0;
            for (var r : rooms) {
                if (shown++ >= 6) {
                    out.add("  &7… 还有 " + (rooms.size() - 6) + " 个");
                    break;
                }
                out.add("  &7- &f" + r.describe());
            }
            if (rooms.isEmpty() && !tpls.isEmpty()) {
                out.add("  &7提示：/hub room create <模板> 建一个试试");
            }
        }

        // ---- 8. 堆内存（决定"还能不能再塞常驻房间世界"） ----
        var rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long maxMb = rt.maxMemory() / 1024 / 1024;
        long pct = maxMb == 0 ? 0 : usedMb * 100 / maxMb;
        out.add("&b[内存] &f" + usedMb + " / " + maxMb + " MB &7(" + pct + "%)"
                + (pct >= 80 ? " &c堆快满了：要么调大 -Xmx，要么减少常驻世界/房间" : ""));
        out.add("  &7注意：物理内存 ≠ 堆。服务端 -Xmx 限制的是上面的 MB，"
                + "32GB 物理内存也用不上，除非改启动参数");

        // ---- 9. 世界规则 ----
        List<World> worlds = Bukkit.getWorlds();
        out.add("&b[世界] &f" + worlds.size() + " 个");
        for (World w : worlds) {
            Object pvp = w.getGameRuleValue(GameRule.PVP);
            Object keep = w.getGameRuleValue(GameRule.KEEP_INVENTORY);
            out.add("  &7- &f" + w.getName() + " &7pvp=&f" + pvp + " &7keepInventory=&f" + keep);
        }

        // ---- 10. 指向文档（不在此处重复不变量状态，避免与文档不一致）----
        out.add("&b[不变量] &7状态见 &fdocs/框架不变量.md&7 第 6 节（本文档是唯一权威）");
        out.add("&8&m                                             ");

        for (String line : out) {
            sender.sendMessage(LEGACY.deserialize(line));
        }
        getLogger().info("/hub doctor 由 " + sender.getName() + " 执行；被吞异常 " + Failures.total() + " 次");
    }

    private boolean isAdmin(CommandSender sender) {
        return sender instanceof Player p ? (p.isOp() || p.hasPermission("hub.admin")) : true;
    }

    public HubManager getManager() {
        return manager;
    }

    public GameRegistry getRegistry() {
        return registry;
    }

    public MenuLayout getMenuLayout() {
        return menuLayout;
    }

    public MenuEditor getMenuEditor() {
        return menuEditor;
    }
}
