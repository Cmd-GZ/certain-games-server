package com.oneshot;

import com.hub.util.Chat;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /oneshot 命令（别名 /os、/one）。 */
public final class OneShotCommand implements CommandExecutor, TabCompleter {

    private final OneShotPlugin plugin;
    private final OneShotManager manager;

    public OneShotCommand(OneShotPlugin plugin, OneShotManager manager) {
        this.plugin = plugin;
        this.manager = manager;
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
                    manager.join(player);
                }
            }
            case "leave" -> {
                Player player = player(sender);
                if (player != null) {
                    manager.leave(player, false);
                }
            }
            case "list" -> list(sender);
            case "stats" -> stats(sender, args);
            case "start" -> {
                if (!admin(sender)) {
                    return true;
                }
                if (manager.state() != OneShotManager.State.WAITING) {
                    Chat.send(sender, "&e对局已经在进行中。");
                } else if (manager.arena().isReady()) {
                    manager.startGame(true);
                } else {
                    Chat.send(sender, "&c场地未配置完整（缺 " + manager.arena().missing() + "）。");
                }
            }
            case "stop" -> {
                if (!admin(sender)) {
                    return true;
                }
                manager.stopGame();
            }
            case "build" -> build(sender, args);
            case "pos1" -> setPos(sender, true);
            case "pos2" -> setPos(sender, false);
            case "setlobby" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player == null) {
                    return true;
                }
                manager.arena().setLobby(player.getLocation());
                manager.saveAll();
                Chat.send(sender, "&a等待点已设置为 " + describe(player.getLocation()) + "。");
            }
            case "addspawn" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player == null) {
                    return true;
                }
                manager.arena().addSpawn(player.getLocation());
                manager.saveAll();
                Chat.send(sender, "&a已添加出生点 " + describe(player.getLocation())
                        + "（共 " + manager.arena().getSpawns().size() + " 个）。");
            }
            case "clearspawns" -> {
                if (!admin(sender)) {
                    return true;
                }
                manager.arena().clearSpawns();
                manager.saveAll();
                Chat.send(sender, "&a已清空出生点。");
            }
            case "genspawns" -> {
                if (!admin(sender)) {
                    return true;
                }
                if (!manager.arena().hasCircle()) {
                    Chat.send(sender, "&c先用 /oneshot build <半径> [台高] 建好场地。");
                    return true;
                }
                int count = manager.maxPlayers();
                if (args.length > 1) {
                    try {
                        count = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ex) {
                        Chat.send(sender, "&c数量必须是数字，例如 /oneshot genspawns 16");
                        return true;
                    }
                }
                if (count < 1 || count > 64) {
                    Chat.send(sender, "&c数量需在 1~64 之间。");
                    return true;
                }
                int generated = manager.generateSpawns(count);
                if (generated <= 0) {
                    Chat.send(sender, "&c没生成出生点：确认场地已经建好。");
                } else {
                    Chat.send(sender, "&a已重新生成 &e" + generated + " &a个出生点并保存。");
                }
            }
            case "save" -> {
                if (!admin(sender)) {
                    return true;
                }
                manager.saveAll();
                Chat.send(sender, "&a场地配置已保存。");
            }
            case "reload" -> {
                if (!admin(sender)) {
                    return true;
                }
                if (manager.state() != OneShotManager.State.WAITING) {
                    Chat.send(sender, "&c对局进行中，不能重载配置。");
                    return true;
                }
                plugin.reloadConfig();
                manager.reload();
                Chat.send(sender, "&a配置已重新载入。");
            }
            default -> Chat.send(sender, "&c未知子指令，输入 &f/oneshot help &c查看帮助。");
        }
        return true;
    }

    /** 一键建场：站在地面执行，场地抬升 height 格并自动生成出生点。 */
    private void build(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        int radius = manager.arena().getRadius();
        int height = manager.arena().getHeight();
        if (args.length >= 2) {
            try {
                radius = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                Chat.send(sender, "&c半径必须是数字，例如 /oneshot build 20 3");
                return;
            }
        }
        if (args.length >= 3) {
            try {
                height = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                Chat.send(sender, "&c台高必须是数字（台面比你脚下高几格），例如 /oneshot build 20 3");
                return;
            }
        }
        if (radius < 6 || radius > 64) {
            Chat.send(sender, "&c半径需在 6~64 之间。");
            return;
        }
        if (height < 0 || height > 32) {
            Chat.send(sender, "&c台高需在 0~32 之间。");
            return;
        }
        Location center = player.getLocation().clone();
        center.setX(center.getBlockX() + 0.5);
        center.setZ(center.getBlockZ() + 0.5);
        center.setY(center.getBlockY() + height);
        manager.arena().setHeight(height);
        int[] result = manager.build(center, radius, manager.arena().getDepth(), true);
        Location top = manager.arena().center();
        if (top != null) {
            player.teleport(top);
        }
        Chat.send(sender, "&a场地已建好：圆心 " + describe(manager.arena().center())
                + "，半径 &e" + radius + " &a，台高 &e" + height
                + " &a| 铺设方块 &e" + result[0] + " &a格");
        if (result[1] > 0) {
            Chat.send(sender, "&7已清掉上一版场地残留 &e" + result[1] + " &7格。");
        }
        Chat.send(sender, "&a已自动生成 &e" + result[2] + " &a个出生点并保存。"
                + "&7接着站到场外执行 &f/oneshot setlobby&7。");
    }

    /** 手动建场：站在场地的一角记录 pos1/pos2。 */
    private void setPos(CommandSender sender, boolean first) {
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (first) {
            manager.arena().setPos1(player.getLocation());
        } else {
            manager.arena().setPos2(player.getLocation());
        }
        manager.saveAll();
        Chat.send(sender, "&a已记录 " + (first ? "pos1" : "pos2") + "：" + describe(player.getLocation())
                + " &7（只取 XZ，Y 轴自动取区域内最高/最低方块）");
        if (manager.arena().hasBox()) {
            Chat.send(sender, "&a手动区域已就绪（XZ 范围 + 地面高度）。接着执行 &f/oneshot genspawns "
                    + manager.maxPlayers() + " &a和 &f/oneshot setlobby&a。");
        } else {
            Chat.send(sender, "&7再站到对角执行 &f/oneshot " + (first ? "pos2" : "pos1") + "&7。");
        }
    }

    private void list(CommandSender sender) {
        Chat.send(sender, "&6===== One Shot =====");
        Chat.send(sender, "&7状态：&e" + manager.state() + " &7等待 &e" + manager.waitingCount()
                + " &7存活 &e" + manager.playingCount());
        Chat.send(sender, "&7场地：&f" + (manager.arena().isReady() ? "已配置" : "未配置（缺 "
                + manager.arena().missing() + "）")
                + " &7| 模式 &e" + (manager.arena().hasBox() ? "手动 pos1/pos2" : "自动圆形")
                + " &7| 半径 &e" + manager.arena().getRadius()
                + " &7| 台高 &e" + manager.arena().getHeight()
                + " &7| 出生点 &e" + manager.arena().getSpawns().size());
        Chat.send(sender, "&7时长 &e" + manager.gameSeconds() + "s &7| 一箭秒杀 &7| 击杀补箭 &7| 摔死人头算最后攻击者");
    }

    private void stats(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c该命令需要玩家执行。");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
            if (!admin(sender)) {
                return;
            }
            manager.resetStats();
            Chat.send(sender, "&a已清空全部前三名与积分记录。");
            return;
        }
        Player target = player;
        if (args.length >= 2) {
            target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) {
                Chat.send(sender, "&c玩家不在线。");
                return;
            }
        }
        Chat.send(sender, "&6" + target.getName() + " 的 " + OneShotManager.GAME + " 战绩："
                + "&e积分 &f" + manager.getPoints(target.getUniqueId())
                + " &7| &e第一 &f" + manager.getStat("firsts", target.getUniqueId())
                + " &6第二 &f" + manager.getStat("seconds", target.getUniqueId())
                + " &6第三 &f" + manager.getStat("thirds", target.getUniqueId()));
    }

    private void help(CommandSender sender) {
        Chat.send(sender, "&6====== One Shot /oneshot ======");
        Chat.send(sender, "&e/oneshot join&f 加入  &e/oneshot leave&f 离开  &e/oneshot list&f 状态  &e/oneshot stats [玩家]&f 战绩");
        if (admin(sender)) {
            Chat.send(sender, "&6-- OP --");
            Chat.send(sender, "&e/oneshot build [半径] [台高]&f 站地面一键建场（自动生成出生点）"
                    + "  &e/oneshot pos1&f / &e/oneshot pos2&f 手动选两个角（自己搭的场地）");
            Chat.send(sender, "&e/oneshot setlobby&f 等待点  &e/oneshot addspawn&f 添加出生点  &e/oneshot clearspawns&f 清空出生点");
            Chat.send(sender, "&e/oneshot genspawns [数量]&f 重新生成出生点（默认最多人数）");
            Chat.send(sender, "&e/oneshot save&f 保存  &e/oneshot start&f 开始  &e/oneshot stop&f 结束  &e/oneshot reload&f 重载");
            Chat.send(sender, "&e/oneshot stats reset&f 清空前三名与积分记录");
            Chat.send(sender, "&7时长/人头归属时间/道具材质在 config.yml。");
        }
    }

    private boolean admin(CommandSender sender) {
        if (sender instanceof Player player) {
            if (!manager.isAdmin(player)) {
                Chat.send(sender, "&c你没有权限执行该指令。");
                return false;
            }
            return true;
        }
        return true;
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        Chat.send(sender, "&c该命令需要玩家执行。");
        return null;
    }

    private String describe(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return "未设置";
        }
        return loc.getWorld().getName() + " " + String.format(Locale.ROOT, "%.1f %.1f %.1f",
                loc.getX(), loc.getY(), loc.getZ());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("join", "leave", "list", "stats", "help"));
            boolean admin = !(sender instanceof Player player) || manager.isAdmin(player);
            if (admin) {
                subs.addAll(List.of("start", "stop", "build", "pos1", "pos2", "setlobby",
                        "addspawn", "clearspawns", "genspawns", "save", "reload"));
            }
            subs.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).forEach(out::add);
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("stats")) {
                out.add("reset");
            } else if (args[0].equalsIgnoreCase("genspawns")) {
                for (String option : List.of("4", "8", "12", "16")) {
                    if (option.startsWith(args[1])) {
                        out.add(option);
                    }
                }
            } else if (args[0].equalsIgnoreCase("build")) {
                for (String option : List.of("12", "16", "20", "24", "32")) {
                    if (option.startsWith(args[1])) {
                        out.add(option);
                    }
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("build")) {
            for (String option : List.of("0", "1", "3", "5")) {
                if (option.startsWith(args[2])) {
                    out.add(option);
                }
            }
        }
        return out;
    }
}
