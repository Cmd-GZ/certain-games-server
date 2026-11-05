package com.arrowrain;

import com.hub.util.Chat;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /arrowrain 命令（别名 /ar、/arrows）。 */
public final class ArrowRainCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "join", "leave", "list", "help",
            "pos1", "pos2", "setspawn", "addspawn", "clearspawns",
            "setlobby", "setground", "setpvpround", "save",
            "start", "stop", "reload", "debug");

    private final ArrowRainPlugin plugin;
    /** D22：按房间分桶的状态；每个入口按发送者/玩家路由到他自己那一份。 */
    private final ArrowRainRooms rooms;

    public ArrowRainCommand(ArrowRainPlugin plugin, ArrowRainRooms rooms) {
        this.plugin = plugin;
        this.rooms = rooms;
    }

    /** 这个发送者应该操作的房间状态：玩家 → 他自己那一份；控制台 → 当前房间上下文/最近有人加入的那一份。 */
    private ArrowRainManager stateOf(CommandSender sender) {
        return sender instanceof Player p ? rooms.forPlayer(p) : rooms.current();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
            case "setspawn" -> setSpawn(sender);
            case "addspawn" -> addSpawn(sender);
            case "clearspawns" -> clearSpawns(sender);
            case "setlobby" -> setLobby(sender);
            case "setground" -> setGround(sender, args);
            case "setpvpround" -> setPvpRound(sender, args);
            case "save" -> save(sender);
            case "start" -> start(sender);
            case "stop" -> stop(sender);
            case "reload" -> reload(sender);
            case "debug" -> debug(sender);
            default -> Chat.send(sender, "&c未知子指令，输入 &f/arrowrain help &c查看帮助。");
        }
        return true;
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        Chat.send(sender, "&c只有玩家可以执行这个指令。");
        return null;
    }

    private boolean admin(CommandSender sender) {
        if (!sender.hasPermission("arrowrain.admin")) {
            Chat.send(sender, "&c你没有权限执行该指令。");
            return false;
        }
        return true;
    }

    private void list(CommandSender sender) {
        ArrowRainArena arena = stateOf(sender).arena();
        Chat.send(sender, "&6===== " + ArrowRainManager.GAME + " =====");
        Chat.send(sender, "&7状态: " + switch (stateOf(sender).state()) {
            case WAITING -> "&e等待中";
            case RUNNING -> "&c游戏中";
            case ENDING -> "&c结算中";
        } + " &7| 存活 &f" + stateOf(sender).aliveCount()
                + " &7| 回合/时间 &f" + stateOf(sender).roundText()
                + " &7| 掩体 &f" + stateOf(sender).coverCount()
                + " &7| PvP 从第 &f" + stateOf(sender).pvpStartRound() + " &7回合");
        Chat.send(sender, "&7场地: &f" + arena.sizeText()
                + " &7| 等待点 " + (arena.lobby() == null ? "&c未设置" : "&a已设置")
                + " &7| 出生点 &f" + arena.spawns().size() + " &7个");
        if (arena.ready()) {
            Chat.send(sender, "&a可开局。");
        } else {
            Chat.send(sender, "&c未配置完整：缺 " + arena.missing()
                    + "（/arrowrain pos1、pos2、addspawn、setlobby、save）");
        }
    }

    private void setCorner(CommandSender sender, int index) {
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        stateOf(sender).arena().setCorner(index, player.getLocation());
        stateOf(sender).arena().save();
        Chat.send(player, "&a已设置区域角" + index + "：&f" + player.getWorld().getName() + " "
                + player.getLocation().getBlockX() + ", " + player.getLocation().getBlockZ()
                + " &7（地面 Y " + stateOf(sender).arena().groundY() + "）");
        if (stateOf(sender).arena().regionConfigured()) {
            Chat.send(player, "&7区域大小: &f" + stateOf(sender).arena().sizeText());
        }
    }

    private void setSpawn(CommandSender sender) {
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        stateOf(sender).arena().setSpawn(player.getLocation());
        stateOf(sender).arena().save();
        Chat.send(player, "&a已把当前位置设为唯一起点（已保存）。要多个起点请用 /arrowrain addspawn。");
    }

    private void addSpawn(CommandSender sender) {
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        stateOf(sender).arena().addSpawn(player.getLocation());
        stateOf(sender).arena().save();
        Chat.send(player, "&a已添加出生点，共 &f" + stateOf(sender).arena().spawns().size() + " &a个（已保存）。");
    }

    private void clearSpawns(CommandSender sender) {
        if (!admin(sender)) {
            return;
        }
        stateOf(sender).arena().clearSpawns();
        stateOf(sender).arena().save();
        Chat.send(sender, "&a已清空全部出生点（已保存）。");
    }

    private void setLobby(CommandSender sender) {
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        stateOf(sender).arena().setLobby(player.getLocation());
        stateOf(sender).arena().save();
        Chat.send(player, "&a已设置等待点（已保存）。");
    }

    private void setGround(CommandSender sender, String[] args) {
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
            Chat.send(sender, "&c用法: /arrowrain setground <Y>");
            return;
        }
        stateOf(sender).arena().setGround(y);
        stateOf(sender).arena().save();
        Chat.send(sender, "&a地面高度已设为 &fY " + y + "&a（玩家站立时的脚部高度）。");
    }

    private void setPvpRound(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return;
        }
        if (args.length < 2) {
            Chat.send(sender, "&c用法: /arrowrain setpvpround <回合数>（当前 " + stateOf(sender).pvpStartRound() + "）");
            return;
        }
        int round;
        try {
            round = Math.max(1, Integer.parseInt(args[1]));
        } catch (NumberFormatException exception) {
            Chat.send(sender, "&c回合数必须是整数。");
            return;
        }
        plugin.getConfig().set("round.pvp-start-round", round);
        plugin.saveConfig();
        Chat.send(sender, "&a已设置从第 &f" + round + " &a回合开始 PvP（对下一局生效）。");
    }

    private void save(CommandSender sender) {
        if (!admin(sender)) {
            return;
        }
        if (!stateOf(sender).arena().ready()) {
            Chat.send(sender, "&c还缺 " + stateOf(sender).arena().missing() + "，无法保存为可开局场地。");
            return;
        }
        stateOf(sender).arena().save();
        Chat.send(sender, "&a场地已保存。");
    }

    private void start(CommandSender sender) {
        // 控制台开局（派对就是这么开的：dispatchCommand(控制台, "arrowrain start")）：
        // 控制台没有房间上下文，两场派对同时开局时"只开最近加入的那一间"会让另一间永远停在等待。
        if (!(sender instanceof Player)) {
            int started = rooms.startAllWaiting();
            if (started == 0) {
                Chat.send(sender, "&e没有正在等待开局的房间。");
            }
            return;
        }
        // D21：开始权在主持人手上；但主持人**不享受管理员特权**（人数不够不能开）
        boolean isAdmin = !(sender instanceof org.bukkit.entity.Player sp) || sp.hasPermission("arrowrain.admin");
        if (!isAdmin) {
            if (!stateOf(sender).isHost(sender)) {
                Chat.send(sender, "&c只有本局**主持人**或管理员可以开始。");
                return;
            }
            if (stateOf(sender).waitingCount() < stateOf(sender).minPlayersPublic()) {
                Chat.send(sender, "&c人数还没达到 &f" + stateOf(sender).minPlayersPublic()
                        + " 人&c，主持人也不能开始。");
                return;
            }
        }
        if (stateOf(sender).state() != ArrowRainManager.State.WAITING) {
            Chat.send(sender, "&e对局已经在进行中。");
        } else if (!stateOf(sender).arena().ready()) {
            Chat.send(sender, "&c场地未配置完整（缺 " + stateOf(sender).arena().missing() + "）。");
        } else {
            stateOf(sender).startGame(true);
        }
    }

    private void stop(CommandSender sender) {
        if (!admin(sender)) {
            return;
        }
        if (stateOf(sender).state() == ArrowRainManager.State.WAITING) {
            Chat.send(sender, "&7当前没有进行中的对局。");
            return;
        }
        stateOf(sender).abortGame();
        Chat.send(sender, "&a已结束本局。");
    }

    private void reload(CommandSender sender) {
        if (!admin(sender)) {
            return;
        }
        if (stateOf(sender).state() != ArrowRainManager.State.WAITING) {
            Chat.send(sender, "&c对局进行中，不能重载配置。");
            return;
        }
        plugin.reloadConfig();
        stateOf(sender).reload();
        Chat.send(sender, "&a配置已重新载入。");
    }

    private void debug(CommandSender sender) {
        if (!admin(sender)) {
            return;
        }
        for (String line : stateOf(sender).debugLines()) {
            Chat.send(sender, "&7" + line);
        }
    }

    private void help(CommandSender sender) {
        Chat.send(sender, "&6" + ArrowRainManager.GAME + " 指令:");
        Chat.send(sender, "&f/arrowrain join &7- 加入等待队列");
        Chat.send(sender, "&f/arrowrain leave &7- 离开游戏");
        Chat.send(sender, "&f/arrowrain list &7- 查看场地与状态");
        if (sender.hasPermission("arrowrain.admin")) {
            Chat.send(sender, "&6-- 管理 --");
            Chat.send(sender, "&f/arrowrain pos1 | pos2 &7- 站在地面两个对角设置区域（只取 XZ）");
            Chat.send(sender, "&f/arrowrain setspawn / addspawn / clearspawns &7- 固定出生点");
            Chat.send(sender, "&f/arrowrain setlobby &7- 设置等待点");
            Chat.send(sender, "&f/arrowrain setground [Y] &7- 设置地面高度（默认当前位置）");
            Chat.send(sender, "&f/arrowrain setpvpround <回合> &7- 第几回合开始 PvP（默认 10）");
            Chat.send(sender, "&f/arrowrain save &7- 保存场地");
            Chat.send(sender, "&f/arrowrain start | stop &7- 开始 / 强制结束（管理员可 1 人测试）");
            Chat.send(sender, "&f/arrowrain reload &7- 重新读取配置");
            Chat.send(sender, "&f/arrowrain debug &7- 打印回合/掩体/箭雨状态");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (isAdmin(sub) && !sender.hasPermission("arrowrain.admin")) {
                    continue;
                }
                options.add(sub);
            }
            return filter(options, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("setpvpround")) {
                return filter(List.of("5", "10", "15", "20"), args[1]);
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
                    "setlobby", "setground", "setpvpround", "save",
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
