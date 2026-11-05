package com.firejumper;

import com.hub.util.Chat;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /firejumper 命令（别名 /fj）。 */
public final class FireJumperCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "join", "leave", "list", "help",
            "pos1", "pos2", "setspawn", "addspawn", "clearspawns", "setlobby", "setground", "setdirection",
            "setlength", "setrange", "setlineheight", "save",
            "start", "stop", "reload", "debug");

    private final FireJumperPlugin plugin;
    /** D22：按房间分桶的状态；每个入口按发送者/玩家路由到他自己那一份。 */
    private final FireJumperRooms rooms;

    public FireJumperCommand(FireJumperPlugin plugin, FireJumperRooms rooms) {
        this.plugin = plugin;
        this.rooms = rooms;
    }

    /** 这个发送者应该操作的房间状态：玩家 → 他自己那一份；控制台 → 当前房间上下文/最近有人加入的那一份。 */
    private FireJumperManager stateOf(CommandSender sender) {
        return sender instanceof Player p ? rooms.forPlayer(p) : rooms.current();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        FireJumperManager manager = stateOf(sender);
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> {
                Player player = player(sender);
                if (player != null) {
                    rooms.join(player);
                }
            }
            case "leave" -> {
                Player player = player(sender);
                if (player != null) {
                    rooms.leave(player, false);
                }
            }
            case "list" -> list(sender);
            case "pos1" -> setCorner(sender, 1);
            case "pos2" -> setCorner(sender, 2);
            case "setlobby" -> setLobby(sender);
            case "setspawn" -> setSpawn(sender);
            case "addspawn" -> addSpawn(sender);
            case "clearspawns" -> clearSpawns(sender);
            case "setground" -> setGround(sender, args);
            case "setdirection" -> setDirection(sender, args);
            case "setlength" -> setLength(sender, args);
            case "setrange" -> setRange(sender, args);
            case "setlineheight" -> setLineHeight(sender, args);
            case "save" -> save(sender);
            case "start" -> start(sender);
            case "stop" -> stop(sender);
            case "reload" -> reload(sender);
            case "debug" -> debug(sender);
            default -> Chat.send(sender, "&c未知子指令，输入 &f/firejumper help &c查看帮助。");
        }
        return true;
    }

    private Player player(CommandSender sender) {
        FireJumperManager manager = stateOf(sender);
        if (sender instanceof Player player) {
            return player;
        }
        Chat.send(sender, "&c只有玩家可以执行这个指令。");
        return null;
    }

    private boolean admin(CommandSender sender) {
        FireJumperManager manager = stateOf(sender);
        if (!sender.hasPermission("firejumper.admin")) {
            Chat.send(sender, "&c你没有权限执行该指令。");
            return false;
        }
        return true;
    }

    private void list(CommandSender sender) {
        FireJumperManager manager = stateOf(sender);
        FireJumperArena arena = manager.arena();
        Chat.send(sender, "&6===== " + FireJumperManager.GAME + " =====");
        Chat.send(sender, "&7状态: " + switch (manager.state()) {
            case WAITING -> "&e等待中";
            case RUNNING -> "&c游戏中";
            case ENDING -> "&c结算中";
        } + " &7| 玩家: &f" + manager.aliveCount()
                + " &7| 轮次/时间: &f" + manager.roundText()
                + " &7| 跳跃提升 &f" + manager.jumpBoostLevel());
        Chat.send(sender, "&7场地: &f" + arena.sizeText()
                + " &7| 等待点 " + (arena.lobby() == null ? "&c未设置" : "&a已设置")
                + " &7| 出生点 &f" + arena.spawns().size() + " &7个");
        Chat.send(sender, "&7方向: &f" + manager.directionText()
                + " &7| 线长: &f" + (arena.lineLength() <= 0 ? "自动" : arena.lineLength() + " 格")
                + " &7| 移动范围: &f" + (arena.travelRange() <= 0 ? "自动" : arena.travelRange() + " 格")
                + " &7| 线高: &f" + arena.lineHeight());
        if (arena.ready()) {
            Chat.send(sender, "&a可开局。");
        } else {
            Chat.send(sender, "&c未配置完整：缺 " + arena.missing()
                    + "（/firejumper pos1、pos2、setlobby、save）");
        }
    }

    private void setCorner(CommandSender sender, int index) {
        FireJumperManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        manager.arena().setCorner(index, player.getLocation());
        manager.arena().save();
        Chat.send(player, "&a已设置区域角" + index + "：&f" + player.getWorld().getName() + " "
                + player.getLocation().getBlockX() + ", " + player.getLocation().getBlockZ()
                + " &7（地面 Y " + manager.arena().groundY() + "）");
        if (manager.arena().regionConfigured()) {
            Chat.send(player, "&7区域大小: &f" + manager.arena().sizeText());
        }
    }

    private void setLobby(CommandSender sender) {
        FireJumperManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        manager.arena().setLobby(player.getLocation());
        manager.arena().save();
        Chat.send(player, "&a已设置等待点（已保存）。");
    }

    private void setSpawn(CommandSender sender) {
        FireJumperManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        manager.arena().setSpawn(player.getLocation());
        manager.arena().save();
        Chat.send(player, "&a已把当前位置设为唯一起点（已保存，共 1 个）。要多个起点请用 /firejumper addspawn。");
    }

    private void addSpawn(CommandSender sender) {
        FireJumperManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        manager.arena().addSpawn(player.getLocation());
        manager.arena().save();
        Chat.send(player, "&a已添加出生点，共 &f" + manager.arena().spawns().size() + " &a个（已保存）。");
    }

    private void clearSpawns(CommandSender sender) {
        FireJumperManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        manager.arena().clearSpawns();
        manager.arena().save();
        Chat.send(sender, "&a已清空全部出生点（已保存）。");
    }

    private void setGround(CommandSender sender, String[] args) {
        FireJumperManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        int y;
        if (args.length >= 2) {
            try {
                y = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                Chat.send(sender, "&c地面高度必须是整数。");
                return;
            }
        } else if (sender instanceof Player player) {
            y = player.getLocation().getBlockY();
        } else {
            Chat.send(sender, "&c用法: /firejumper setground <Y>");
            return;
        }
        manager.arena().setGround(y);
        manager.arena().save();
        Chat.send(sender, "&a地面高度已设为 &fY " + y + "&a（玩家站立时的脚部高度）。");
    }

    private void setDirection(CommandSender sender, String[] args) {
        FireJumperManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        if (args.length < 2) {
            Chat.send(sender, "&c用法: /firejumper setdirection x+|x-|z+|z-");
            Chat.send(sender, "&7x+ = 沿 +X 移动（线沿 Z 展开）；z+ = 沿 +Z 移动（线沿 X 展开）。");
            return;
        }
        if (!manager.arena().setDirection(args[1])) {
            Chat.send(sender, "&c方向只能是 x+ / x- / z+ / z-。");
            return;
        }
        manager.arena().save();
        Chat.send(sender, "&a线的移动方向已设为 &f" + manager.directionText() + "&a。");
    }

    private void setLength(CommandSender sender, String[] args) {
        FireJumperManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        double value = number(sender, args, "/firejumper setlength <格数|auto>");
        if (Double.isNaN(value)) {
            return;
        }
        manager.arena().setLineLength(value);
        manager.arena().save();
        Chat.send(sender, "&a线长已设为 &f" + (value <= 0 ? "自动（场地垂直方向总长）" : value + " 格") + "&a。");
    }

    private void setRange(CommandSender sender, String[] args) {
        FireJumperManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        double value = number(sender, args, "/firejumper setrange <格数|auto>");
        if (Double.isNaN(value)) {
            return;
        }
        manager.arena().setTravelRange(value);
        manager.arena().save();
        Chat.send(sender, "&a移动范围已设为 &f" + (value <= 0 ? "自动（场地移动方向总长）" : value + " 格") + "&a。");
    }

    private void setLineHeight(CommandSender sender, String[] args) {
        FireJumperManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        double value = number(sender, args, "/firejumper setlineheight <格数>");
        if (Double.isNaN(value)) {
            return;
        }
        manager.arena().setLineHeight(value);
        manager.arena().save();
        Chat.send(sender, "&a线的判定高度已设为 &f" + manager.arena().lineHeight() + " 格&a。");
    }

    /** auto / 0 = 用场地自动尺寸。 */
    private double number(CommandSender sender, String[] args, String usage) {
        if (args.length < 2) {
            Chat.send(sender, "&c用法: " + usage);
            return Double.NaN;
        }
        if (args[1].equalsIgnoreCase("auto")) {
            return 0;
        }
        try {
            return Math.max(0, Double.parseDouble(args[1]));
        } catch (NumberFormatException exception) {
            Chat.send(sender, "&c必须是数字或 auto。");
            return Double.NaN;
        }
    }

    private void save(CommandSender sender) {
        FireJumperManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        if (!manager.arena().ready()) {
            Chat.send(sender, "&c还缺 " + manager.arena().missing() + "，无法保存为可开局场地。");
            return;
        }
        manager.arena().save();
        Chat.send(sender, "&a场地已保存。");
    }

    private void start(CommandSender sender) {
        // 控制台开局（派对就是这么开的：dispatchCommand(控制台, "fj start")）：
        // 控制台没有房间上下文，两场派对同时开局时"只开最近加入的那一间"会让另一间永远停在等待。
        if (!(sender instanceof Player)) {
            int started = rooms.startAllWaiting();
            if (started == 0) {
                Chat.send(sender, "&e没有正在等待开局的房间。");
            }
            return;
        }
        FireJumperManager manager = stateOf(sender);
        // D21：开始权在主持人手上；但主持人**不享受管理员特权**（人数不够不能开）
        boolean isAdmin = !(sender instanceof org.bukkit.entity.Player sp) || sp.hasPermission("firejumper.admin");
        if (!isAdmin) {
            if (!manager.isHost(sender)) {
                Chat.send(sender, "&c只有本局**主持人**或管理员可以开始。");
                return;
            }
            if (manager.waitingCount() < manager.minPlayersPublic()) {
                Chat.send(sender, "&c人数还没达到 &f" + manager.minPlayersPublic()
                        + " 人&c，主持人也不能开始。");
                return;
            }
        }
        if (manager.state() != FireJumperManager.State.WAITING) {
            Chat.send(sender, "&e对局已经在进行中。");
        } else if (!manager.arena().ready()) {
            Chat.send(sender, "&c场地未配置完整（缺 " + manager.arena().missing() + "）。");
        } else {
            manager.startGame(true);
        }
    }

    private void stop(CommandSender sender) {
        FireJumperManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        if (manager.state() == FireJumperManager.State.WAITING) {
            Chat.send(sender, "&7当前没有进行中的对局。");
            return;
        }
        manager.abortGame();
        Chat.send(sender, "&a已结束本局。");
    }

    private void reload(CommandSender sender) {
        FireJumperManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        if (manager.state() != FireJumperManager.State.WAITING) {
            Chat.send(sender, "&c对局进行中，不能重载配置。");
            return;
        }
        plugin.reloadConfig();
        manager.reload();
        Chat.send(sender, "&a配置已重新载入。");
    }

    private void debug(CommandSender sender) {
        FireJumperManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        for (String line : manager.debugLines()) {
            Chat.send(sender, "&7" + line);
        }
    }

    private void help(CommandSender sender) {
        Chat.send(sender, "&6" + FireJumperManager.GAME + " 指令:");
        Chat.send(sender, "&f/firejumper join &7- 加入等待队列");
        Chat.send(sender, "&f/firejumper leave &7- 离开游戏");
        Chat.send(sender, "&f/firejumper list &7- 查看场地与状态");
        if (sender.hasPermission("firejumper.admin")) {
            Chat.send(sender, "&6-- 管理 --");
            Chat.send(sender, "&f/firejumper pos1 | pos2 &7- 站在地面两个对角设置区域（只取 XZ，地面 Y 取 pos1 脚下）");
            Chat.send(sender, "&f/firejumper setlobby &7- 设置等待点");
            Chat.send(sender, "&f/firejumper setspawn &7- 把当前位置设为唯一起点（清空原有）");
            Chat.send(sender, "&f/firejumper addspawn &7- 追加一个固定出生点（按人数循环使用）");
            Chat.send(sender, "&f/firejumper clearspawns &7- 清空全部出生点");
            Chat.send(sender, "&f/firejumper setground [Y] &7- 设置地面高度（默认取当前位置）");
            Chat.send(sender, "&f/firejumper setdirection x+|x-|z+|z- &7- 线移动方向（线沿垂直方向展开）");
            Chat.send(sender, "&f/firejumper setlength <格|auto> &7- 线的长度（auto = 场地垂直方向总长）");
            Chat.send(sender, "&f/firejumper setrange <格|auto> &7- 线的移动距离（auto = 场地移动方向总长）");
            Chat.send(sender, "&f/firejumper setlineheight <格> &7- 线的判定高度（默认 1.0）");
            Chat.send(sender, "&f/firejumper save &7- 保存场地");
            Chat.send(sender, "&f/firejumper start | stop &7- 开始 / 强制结束（管理员可 1 人开局测试，不计胜场）");
            Chat.send(sender, "&f/firejumper reload &7- 重新读取配置");
            Chat.send(sender, "&f/firejumper debug &7- 打印场地/火线实际坐标（排查看不见线用）");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (isAdmin(sub) && !sender.hasPermission("firejumper.admin")) {
                    continue;
                }
                options.add(sub);
            }
            return filter(options, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("setdirection")) {
                return filter(List.of("x+", "x-", "z+", "z-"), args[1]);
            }
            if (sub.equals("setlength") || sub.equals("setrange") || sub.equals("setlineheight")) {
                return filter(List.of("auto", "10", "20", "40"), args[1]);
            }
            if (sub.equals("setground")) {
                return filter(List.of("60", "64", "70", "100"), args[1]);
            }
        }
        return List.of();
    }

    private boolean isAdmin(String sub) {
        return switch (sub) {
            case "pos1", "pos2", "setspawn", "addspawn", "clearspawns",
                    "setlobby", "setground", "setdirection",
                    "setlength", "setrange", "setlineheight", "save",
                    "start", "stop", "reload", "debug" -> true;
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
