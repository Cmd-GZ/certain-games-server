package com.tntbattle;

import com.hub.util.Chat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class TNTBattleCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "join", "leave", "list", "stats", "top", "help", "create", "remove",
            "pos1", "pos2", "setspawn", "addspawn", "clearspawns",
            "setlobby", "setvoid", "setbuildheight", "setmaxplayers",
            "settop", "removetop", "save", "start", "stop", "reload", "music");

    private final TNTBattlePlugin plugin;
    private final TNTBattleCore core;

    public TNTBattleCommand(TNTBattlePlugin plugin, TNTBattleCore core) {
        this.plugin = plugin;
        this.core = core;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> join(sender, args);
            case "leave" -> leave(sender);
            case "list" -> list(sender);
            case "stats" -> stats(sender, args);
            case "top" -> top(sender, args);
            case "help" -> help(sender);
            case "create" -> create(sender, args);
            case "remove" -> remove(sender, args);
            case "pos1" -> setCorner(sender, args, 1);
            case "pos2" -> setCorner(sender, args, 2);
            case "setspawn" -> setSpawn(sender, args);
            case "addspawn" -> addSpawn(sender, args);
            case "clearspawns" -> clearSpawns(sender, args);
            case "setlobby" -> setLobby(sender, args);
            case "setvoid" -> setVoid(sender, args);
            case "setbuildheight" -> setBuildHeight(sender, args);
            case "setmaxplayers" -> setMaxPlayers(sender, args);
            case "settop" -> setTop(sender);
            case "removetop" -> removeTop(sender);
            case "save" -> save(sender, args);
            case "start" -> start(sender, args);
            case "stop" -> stop(sender, args);
            case "reload" -> reload(sender);
            case "music" -> music(sender, args);
            default -> Chat.send(sender, "&c未知子指令，输入 &f/tntbattle help &c查看帮助。");
        }
        return true;
    }

    private List<String> arenaNames() {
        List<String> names = new ArrayList<>();
        for (TNTBattleManager manager : core.matches()) {
            names.add(manager.arena().name());
        }
        return names;
    }

    /** 指令里的第一个参数如果是场名就用它，否则只有一个场时省略场名。 */
    private TNTBattleManager resolve(String[] args, int index, CommandSender sender) {
        if (args.length > index) {
            TNTBattleManager named = core.match(args[index]);
            if (named != null) {
                return named;
            }
        }
        String def = core.defaultArenaName();
        if (def != null) {
            return core.match(def);
        }
        Chat.send(sender, "&c当前有多个场，请在指令里写场名。可用：&f" + String.join("、", arenaNames()));
        return null;
    }

    private boolean namedArena(String[] args, int index) {
        return args.length > index && core.match(args[index]) != null;
    }

    private void join(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c只有玩家可以加入游戏。");
            return;
        }
        TNTBattleManager manager = resolve(args, 1, sender);
        if (manager != null) {
            manager.join(player);
        }
    }

    private void leave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c只有玩家可以离开游戏。");
            return;
        }
        TNTBattleManager manager = core.managerFor(player);
        if (manager == null || !manager.isPlaying(player)) {
            Chat.send(player, "&7你不在游戏中。");
            return;
        }
        manager.leave(player, false);
        Chat.send(player, "&7已离开 TNT 对战。");
    }

    private void list(CommandSender sender) {
        Chat.send(sender, "&c===== TNT 对战（共 " + arenaNames().size() + " 个场）=====");
        for (TNTBattleManager manager : core.matches()) {
            TNTBattleArena arena = manager.arena();
            String state = manager.isRunning() ? "&c游戏中"
                    : (manager.isEnding() ? "&c结算中" : "&e等待中");
            Chat.send(sender, "&e" + arena.name() + " &7[" + arena.worldName() + "] " + state
                    + " &7| 玩家 &f" + manager.statesCount()
                    + " &7| 区域 &f" + arena.sizeText()
                    + " &7| 虚空 &fY " + arena.voidY()
                    + " &7| 建筑上限 " + (manager.buildHeightLimit() < 0
                    ? "&f无限制" : "&fY " + manager.buildHeightLimit())
                    + " &7| 人数上限 &f" + manager.maxPlayers());
            Chat.send(sender, "&7  出生点 红 &f" + arena.spawns(TNTBattleArena.Team.RED).size()
                    + " &7/ 蓝 &f" + arena.spawns(TNTBattleArena.Team.BLUE).size()
                    + " &7| 等待点 " + (arena.lobby() == null ? "&c未设置" : "&a已设置")
                    + " &7| " + (arena.ready() ? "&a可开局" : "&c未配置完整"));
            if (manager.isRunning()) {
                Chat.send(sender, "&7  红队存活 &f" + manager.aliveCountTotal(TNTBattleArena.Team.RED)
                        + "&7/&f" + manager.teamSize(TNTBattleArena.Team.RED)
                        + " &8| &7蓝队存活 &f" + manager.aliveCountTotal(TNTBattleArena.Team.BLUE)
                        + "&7/&f" + manager.teamSize(TNTBattleArena.Team.BLUE));
            }
        }
        Chat.send(sender, "&7配置场地：/tntbattle create <场名>，然后对该场执行 pos1/pos2/setspawn/setlobby/save");
    }

    private void stats(CommandSender sender, String[] args) {
        String name;
        UUID id;
        if (args.length >= 2) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            id = target.getUniqueId();
            name = target.getName() == null ? args[1] : target.getName();
        } else if (sender instanceof Player player) {
            id = player.getUniqueId();
            name = player.getName();
        } else {
            Chat.send(sender, "&c用法: /tntbattle stats <玩家>");
            return;
        }
        Chat.send(sender, "&c" + name + " &7的 TNT 对战胜场：&e" + core.firsts(id));
    }

    private void top(CommandSender sender, String[] args) {
        int limit = 10;
        if (args.length >= 2) {
            try {
                limit = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                Chat.send(sender, "&c数量必须是数字，例如 /tntbattle top 10");
                return;
            }
        }
        limit = Math.max(1, Math.min(50, limit));
        List<TNTBattleManager.WinEntry> entries = core.leaderboard(limit);
        Chat.send(sender, "&c&lTNT 对战 胜场排行榜 &7(前 " + limit + " 名)");
        if (entries.isEmpty()) {
            Chat.send(sender, "&7暂无胜场记录。");
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            TNTBattleManager.WinEntry entry = entries.get(i);
            Chat.send(sender, "&e#" + (i + 1) + " &f" + entry.name()
                    + " &7- &c" + entry.wins() + " &7胜");
        }
    }

    private void help(CommandSender sender) {
        Chat.send(sender, "&cTNT 对战指令（多场：在指令里写场名；只有一个场时可省略）:");
        Chat.send(sender, "&f/tntbattle join [场] &7- 加入等待队列");
        Chat.send(sender, "&f/tntbattle leave &7- 离开游戏");
        Chat.send(sender, "&f/tntbattle list &7- 查看全部场状态");
        Chat.send(sender, "&f/tntbattle stats [玩家] &7- 查看胜场");
        Chat.send(sender, "&f/tntbattle top [数量] &7- 胜场排行榜（默认 10）");
        if (sender.hasPermission("tntbattle.admin")) {
            Chat.send(sender, "&6-- 场地管理 --");
            Chat.send(sender, "&f/tntbattle create <场名> &7- 新建场地（场名 1~24 个字母/数字/下划线/横线）");
            Chat.send(sender, "&f/tntbattle remove <场名> confirm &7- 删除场地（有玩家或对局中会拒绝）");
            Chat.send(sender, "&f/tntbattle pos1|pos2 [场] &7- 设置区域 X/Z 对角（Y 自动覆盖世界全高）");
            Chat.send(sender, "&f/tntbattle setspawn [场] red|blue &7- 设置该队出生点（替换原有）");
            Chat.send(sender, "&f/tntbattle addspawn [场] red|blue &7- 给该队添加出生点");
            Chat.send(sender, "&f/tntbattle clearspawns [场] red|blue &7- 清空该队出生点");
            Chat.send(sender, "&f/tntbattle setlobby [场] &7- 设置该场等待点");
            Chat.send(sender, "&f/tntbattle setvoid [场] <Y> &7- 设置该场虚空淘汰高度");
            Chat.send(sender, "&f/tntbattle setbuildheight [场] [Y|off] &7- 该场建筑高度上限（off 关闭）");
            Chat.send(sender, "&f/tntbattle setmaxplayers [场] <n> &7- 该场人数上限（0 = 用全局 max-players）");
            Chat.send(sender, "&f/tntbattle save [场] &7- 保存该场配置");
            Chat.send(sender, "&f/tntbattle start|stop [场] &7- 立即开始 / 强制结束该场");
            Chat.send(sender, "&f/tntbattle settop | removetop &7- 大厅悬浮排行榜（全局，以主场地为准）");
            Chat.send(sender, "&f/tntbattle reload &7- 重新读取配置");
            Chat.send(sender, "&f/tntbattle music <pos1|pos2|record|stop|play|info|clear|reload> &7- 背景音乐（全局）");
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("tntbattle.admin")) {
            Chat.send(sender, "&c你没有权限执行该指令。");
            return false;
        }
        return true;
    }

    private void create(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (args.length < 2) {
            Chat.send(sender, "&c用法: /tntbattle create <场名>");
            return;
        }
        String name = args[1];
        TNTBattleManager manager = core.createArena(name);
        if (manager == null) {
            Chat.send(sender, "&c创建失败：场名不合法或已存在（场名 1~24 个字母/数字/下划线/横线）。");
            return;
        }
        Chat.send(sender, "&a已创建场地 &f" + name + " &7（" + manager.arena().worldName() + "）。");
        Chat.send(sender, "&7接下来：传送到该地图后执行 &f/tntbattle pos1 " + name
                + "&7、&fpos2 " + name + "&7、&fsetspawn " + name + " red/blue&7、&fsetlobby " + name
                + "&7、&fsave " + name);
    }

    private void remove(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (args.length < 2 || !core.exists(args[1])) {
            Chat.send(sender, "&c用法: /tntbattle remove <场名> confirm");
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            Chat.send(sender, "&c这会删除场地 &f" + args[1]
                    + " &c的配置（地图方块不受影响）。确认执行：&f/tntbattle remove " + args[1] + " confirm");
            return;
        }
        if (!core.removeArena(args[1])) {
            Chat.send(sender, "&c无法删除：该场还有玩家或正在对局。");
            return;
        }
        Chat.send(sender, "&a已删除场地 &f" + args[1] + "&a。");
    }

    private void setSpawn(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c只有玩家可以设置出生点。");
            return;
        }
        TNTBattleManager manager = resolve(args, 1, sender);
        if (manager == null) {
            return;
        }
        TNTBattleArena.Team team = teamArg(args, namedArena(args, 1) ? 2 : 1);
        if (team == null) {
            Chat.send(sender, "&c用法: /tntbattle setspawn [" + manager.arena().name()
                    + "] red|blue（会清空该队原有出生点；要多个用 addspawn）");
            return;
        }
        manager.arena().setSpawn(team, player.getLocation());
        manager.saveArena();
        Chat.send(player, "&a已设置 &f" + manager.arena().name() + " &a的" + team.display()
                + "出生点（1 个，已保存）。要多个出生点请用 /tntbattle addspawn。");
    }

    private void addSpawn(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c只有玩家可以添加出生点。");
            return;
        }
        TNTBattleManager manager = resolve(args, 1, sender);
        if (manager == null) {
            return;
        }
        TNTBattleArena.Team team = teamArg(args, namedArena(args, 1) ? 2 : 1);
        if (team == null) {
            Chat.send(sender, "&c用法: /tntbattle addspawn [" + manager.arena().name() + "] red|blue");
            return;
        }
        manager.arena().addSpawn(team, player.getLocation());
        manager.saveArena();
        Chat.send(player, "&a已给 &f" + manager.arena().name() + " &a的" + team.display()
                + "添加出生点，共 &f" + manager.arena().spawns(team).size() + " &a个（已保存）。");
    }

    private void clearSpawns(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        TNTBattleManager manager = resolve(args, 1, sender);
        if (manager == null) {
            return;
        }
        TNTBattleArena.Team team = teamArg(args, namedArena(args, 1) ? 2 : 1);
        if (team == null) {
            Chat.send(sender, "&c用法: /tntbattle clearspawns [" + manager.arena().name() + "] red|blue");
            return;
        }
        manager.arena().clearSpawns(team);
        manager.saveArena();
        Chat.send(sender, "&a已清空 &f" + manager.arena().name() + " &a的" + team.display() + "出生点（已保存）。");
    }

    private TNTBattleArena.Team teamArg(String[] args, int index) {
        if (args.length <= index) {
            return null;
        }
        if (args[index].equalsIgnoreCase("red")) {
            return TNTBattleArena.Team.RED;
        }
        if (args[index].equalsIgnoreCase("blue")) {
            return TNTBattleArena.Team.BLUE;
        }
        return null;
    }

    private void setCorner(CommandSender sender, String[] args, int index) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c只有玩家可以设置区域角。");
            return;
        }
        TNTBattleManager manager = resolve(args, 1, sender);
        if (manager == null) {
            return;
        }
        manager.arena().setCorner(index, player.getLocation());
        manager.saveArena();
        Chat.send(player, "&a已设置 &f" + manager.arena().name() + " &a的区域角" + index + "：&f"
                + player.getWorld().getName() + " "
                + player.getLocation().getBlockX() + ", " + player.getLocation().getBlockZ()
                + " &7（只取 X/Z，Y 自动覆盖整个世界高度）");
        if (manager.arena().regionConfigured()) {
            Chat.send(player, "&7区域大小: &f" + manager.arena().sizeText());
        }
    }

    private void setLobby(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c只有玩家可以设置等待点。");
            return;
        }
        TNTBattleManager manager = resolve(args, 1, sender);
        if (manager == null) {
            return;
        }
        manager.arena().setLobby(player.getLocation());
        manager.saveArena();
        Chat.send(player, "&a已设置 &f" + manager.arena().name() + " &a的等待点（已保存）。");
    }

    private void setTop(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c只有玩家可以放置排行榜。");
            return;
        }
        Location at = player.getLocation().add(0, 1.5, 0);
        core.setLeaderboardLocation(at);
        Chat.send(player, "&a已在这里放置大厅胜场排行榜并保存（&f" + at.getWorld().getName()
                + " " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ()
                + "&a）；胜场变化会自动刷新。");
    }

    private void removeTop(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return;
        }
        core.removeLeaderboard();
        Chat.send(sender, "&a已移除大厅胜场排行榜（已保存）。");
    }

    private void setVoid(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        TNTBattleManager manager = resolve(args, 1, sender);
        if (manager == null) {
            return;
        }
        int valueIndex = namedArena(args, 1) ? 2 : 1;
        int y;
        if (args.length > valueIndex) {
            try {
                y = Integer.parseInt(args[valueIndex]);
            } catch (NumberFormatException exception) {
                Chat.send(sender, "&c虚空高度必须是整数。");
                return;
            }
        } else if (sender instanceof Player player) {
            y = player.getLocation().getBlockY() - 10;
        } else {
            Chat.send(sender, "&c用法: /tntbattle setvoid [" + manager.arena().name() + "] <Y>");
            return;
        }
        manager.arena().setVoidY(y);
        manager.saveArena();
        Chat.send(sender, "&a已设置 &f" + manager.arena().name() + " &a的虚空淘汰高度：&fY " + y);
    }

    private void setBuildHeight(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        TNTBattleManager manager = resolve(args, 1, sender);
        if (manager == null) {
            return;
        }
        int valueIndex = namedArena(args, 1) ? 2 : 1;
        int y;
        if (args.length > valueIndex
                && (args[valueIndex].equalsIgnoreCase("off") || args[valueIndex].equalsIgnoreCase("none"))) {
            y = -1;
        } else if (args.length > valueIndex) {
            try {
                y = Integer.parseInt(args[valueIndex]);
            } catch (NumberFormatException exception) {
                Chat.send(sender, "&c高度必须是整数，或写 off 关闭。");
                return;
            }
        } else if (sender instanceof Player player) {
            y = player.getLocation().getBlockY();
        } else {
            Chat.send(sender, "&c用法: /tntbattle setbuildheight [" + manager.arena().name() + "] [Y|off]");
            return;
        }
        manager.arena().setBuildHeightLimit(y);
        manager.saveArena();
        Chat.send(sender, y < 0
                ? "&a已关闭 &f" + manager.arena().name() + " &a的建筑高度限制。"
                : "&a" + manager.arena().name() + " 的建筑高度上限已设为 &fY " + y
                + "&a（只能在这层及以下放羊毛）。");
    }

    private void setMaxPlayers(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        TNTBattleManager manager = resolve(args, 1, sender);
        if (manager == null) {
            return;
        }
        int valueIndex = namedArena(args, 1) ? 2 : 1;
        if (args.length <= valueIndex) {
            Chat.send(sender, "&c用法: /tntbattle setmaxplayers [" + manager.arena().name() + "] <人数，0 = 用全局>");
            return;
        }
        int value;
        try {
            value = Integer.parseInt(args[valueIndex]);
        } catch (NumberFormatException exception) {
            Chat.send(sender, "&c人数必须是整数。");
            return;
        }
        manager.arena().setMaxPlayers(value);
        manager.saveArena();
        Chat.send(sender, value <= 0
                ? "&a" + manager.arena().name() + " 的人数上限已改为跟随全局（&f"
                + manager.maxPlayers() + " &a人）。"
                : "&a" + manager.arena().name() + " 的人数上限已设为 &f" + manager.maxPlayers() + " &a人。");
    }

    private void save(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        TNTBattleManager manager = resolve(args, 1, sender);
        if (manager == null) {
            return;
        }
        if (!manager.arena().ready()) {
            Chat.send(sender, "&c至少需要设置 pos1/pos2 区域、等待点和红蓝两队各至少一个出生点。");
            return;
        }
        manager.saveArena();
        Chat.send(sender, "&a已保存 &f" + manager.arena().name() + " &a的场地配置。");
    }

    private void start(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        TNTBattleManager manager = resolve(args, 1, sender);
        if (manager == null) {
            return;
        }
        if (!manager.startGame(true)) {
            Chat.send(sender, "&c无法开始 &f" + manager.arena().name() + " &c：检查场地配置和等待玩家。");
        }
    }

    private void stop(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        TNTBattleManager manager = resolve(args, 1, sender);
        if (manager == null) {
            return;
        }
        if (!manager.isRunning()) {
            Chat.send(sender, "&7" + manager.arena().name() + " 当前没有进行中的对局。");
            return;
        }
        manager.endGame(null);
        Chat.send(sender, "&a已结束 &f" + manager.arena().name() + " &a对局。");
    }

    private void reload(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return;
        }
        for (TNTBattleManager manager : core.matches()) {
            if (manager.inUse()) {
                Chat.send(sender, "&c场地 &f" + manager.arena().name() + " &c还有玩家或正在对局，不能重载配置。");
                return;
            }
        }
        core.reload();
        Chat.send(sender, "&a配置已重新载入（共 " + arenaNames().size() + " 个场）。");
    }

    private void music(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        TNTBattleMusic music = core.musicRecorder();
        if (music == null) {
            Chat.send(sender, "&c还没有任何场地，无法录制背景音乐。");
            return;
        }
        if (args.length < 2) {
            Chat.send(sender, "&6/tntbattle music <pos1|pos2|record|stop|play|info|clear|reload>");
            Chat.send(sender, "&7录制：站在红石音乐装置两个对角执行 &fmusic pos1&7 / &fmusic pos2&7，"
                    + "再 &fmusic record&7，启动红石机，最后 &fmusic stop");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "pos1", "pos2" -> {
                if (!(sender instanceof Player player)) {
                    Chat.send(sender, "&c只有玩家可以设置录制范围。");
                    return;
                }
                music.setCorner(args[1].equalsIgnoreCase("pos1") ? 1 : 2, player.getLocation(), sender);
            }
            case "record" -> music.startRecording(sender);
            case "stop" -> {
                music.finishRecording(sender, false);
                core.reloadMusicAll();
            }
            case "play" -> {
                if (!(sender instanceof Player player)) {
                    Chat.send(sender, "&c只有玩家可以试听。");
                    return;
                }
                music.playTest(player);
            }
            case "info" -> music.info(sender);
            case "clear" -> {
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    Chat.send(sender, "&c这会删除已录制的全部音符。确认执行：&f/tntbattle music clear confirm");
                    return;
                }
                music.clear(sender);
                core.reloadMusicAll();
            }
            case "reload" -> {
                core.reloadMusicAll();
                Chat.send(sender, "&a背景音乐已从 music.yml 重新载入（全部场地）。");
            }
            default -> Chat.send(sender, "&c用法：/tntbattle music <pos1|pos2|record|stop|play|info|clear|reload>");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (isAdminSub(sub) && !sender.hasPermission("tntbattle.admin")) {
                    continue;
                }
                options.add(sub);
            }
            return filter(options, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("stats")) {
                List<String> names = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    names.add(player.getName());
                }
                return filter(names, args[1]);
            }
            if (sub.equals("top")) {
                return filter(List.of("5", "10", "20", "50"), args[1]);
            }
            if (sub.equals("music")) {
                return filter(List.of("pos1", "pos2", "record", "stop", "play", "info", "clear", "reload"), args[1]);
            }
            if (sub.equals("remove")) {
                return filter(arenaNames(), args[1]);
            }
            if (sub.equals("setspawn") || sub.equals("addspawn") || sub.equals("clearspawns")) {
                List<String> options = new ArrayList<>(arenaNames());
                options.add("red");
                options.add("blue");
                return filter(options, args[1]);
            }
            if (isArenaScoped(sub)) {
                return filter(arenaNames(), args[1]);
            }
            return List.of();
        }
        if (args.length == 3) {
            if ((sub.equals("setspawn") || sub.equals("addspawn") || sub.equals("clearspawns"))
                    && namedArena(args, 1)) {
                return filter(List.of("red", "blue"), args[2]);
            }
            if (sub.equals("remove")) {
                return filter(List.of("confirm"), args[2]);
            }
            if (sub.equals("music") && args[1].equalsIgnoreCase("clear")) {
                return filter(List.of("confirm"), args[2]);
            }
            if (sub.equals("setvoid") && namedArena(args, 1)) {
                return filter(List.of("-64", "-32", "0"), args[2]);
            }
        }
        return List.of();
    }

    private boolean isArenaScoped(String sub) {
        return switch (sub) {
            case "join", "pos1", "pos2", "setlobby", "setvoid", "setbuildheight",
                    "setmaxplayers", "save", "start", "stop", "create" -> true;
            default -> false;
        };
    }

    private boolean isAdminSub(String sub) {
        return switch (sub) {
            case "create", "remove", "pos1", "pos2", "setspawn", "addspawn", "clearspawns",
                    "setlobby", "setvoid", "setbuildheight", "setmaxplayers", "settop", "removetop",
                    "save", "start", "stop", "reload", "music" -> true;
            default -> false;
        };
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
