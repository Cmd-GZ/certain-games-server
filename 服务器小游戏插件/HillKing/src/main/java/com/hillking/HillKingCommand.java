package com.hillking;

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

/** /hill 命令（别名 /king、/koth、/hillking）。 */
public final class HillKingCommand implements CommandExecutor, TabCompleter {

    private final HillKingPlugin plugin;
    /** D22：按房间分桶的状态；每个入口按发送者/玩家路由到他自己那一份。 */
    private final HillKingRooms rooms;

    public HillKingCommand(HillKingPlugin plugin, HillKingRooms rooms) {
        this.plugin = plugin;
        this.rooms = rooms;
    }

    /** 这个发送者应该操作的房间状态：玩家 → 他自己那一份；控制台 → 当前房间上下文/最近有人加入的那一份。 */
    private HillKingManager stateOf(CommandSender sender) {
        return sender instanceof Player p ? rooms.forPlayer(p) : rooms.current();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        HillKingManager manager = stateOf(sender);
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
            case "stats" -> stats(sender, args);
            case "start" -> {
                // 控制台开局（派对就是这么开的：dispatchCommand(控制台, "hill start")）：
                // 控制台没有房间上下文，两场派对同时开局时"只开最近加入的那一间"会让另一间永远停在等待。
                if (!(sender instanceof Player)) {
                    int started = rooms.startAllWaiting();
                    if (started == 0) {
                        Chat.send(sender, "&e没有正在等待开局的房间。");
                    }
                    return true;
                }
                // D21：开始权在主持人手上，但**主持人不享受管理员的特权**：
                //   - 管理员可以强制开（人不够也开）；
                //   - 主持人必须等人数达到 minPlayers 才能开，也不能在人数不足时启动倒计时。
                boolean isAdmin = !(sender instanceof Player sp) || manager.isAdmin(sp);
                if (!isAdmin) {
                    if (!manager.isHost(sender)) {
                        Chat.send(sender, "&c只有本局**主持人**或管理员可以开始。"
                                + "&7（主持人 = 最早加入等待队列的人）");
                        return true;
                    }
                    if (manager.waitingCount() < manager.minPlayers()) {
                        Chat.send(sender, "&c人数还没达到 &f" + manager.minPlayers()
                                + " 人&c，主持人也不能开始（管理员可以强制开始）。");
                        return true;
                    }
                }
                if (manager.state() != HillKingManager.State.WAITING) {
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
                    Chat.send(sender, "&c先用 /hill build <半径> [台高] 建好高台。");
                    return true;
                }
                int count = manager.maxPlayers();
                if (args.length > 1) {
                    try {
                        count = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ex) {
                        Chat.send(sender, "&c数量必须是数字，例如 /hill genspawns 12");
                        return true;
                    }
                }
                if (count < 1 || count > 64) {
                    Chat.send(sender, "&c数量需在 1~64 之间。");
                    return true;
                }
                int generated = manager.generateSpawns(count);
                if (generated <= 0) {
                    Chat.send(sender, "&c没生成出生点：确认高台已经建好。");
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
                if (manager.state() != HillKingManager.State.WAITING) {
                    Chat.send(sender, "&c对局进行中，不能重载配置。");
                    return true;
                }
                plugin.reloadConfig();
                manager.reload();
                Chat.send(sender, "&a配置已重新载入。");
            }
            default -> Chat.send(sender, "&c未知子指令，输入 &f/hill help &c查看帮助。");
        }
        return true;
    }

    /** 一键建场：站在场地中心执行，建圆形红羊毛高台 + 自动生成出生点并保存。 */
    private void build(CommandSender sender, String[] args) {
        HillKingManager manager = stateOf(sender);
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
                Chat.send(sender, "&c半径必须是数字，例如 /hill build 12 3");
                return;
            }
        }
        if (args.length >= 3) {
            try {
                height = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                Chat.send(sender, "&c台高必须是数字（台面比你脚下高几格），例如 /hill build 12 3");
                return;
            }
        }
        if (radius < 4 || radius > 64) {
            Chat.send(sender, "&c半径需在 4~64 之间。");
            return;
        }
        if (height < 0 || height > 32) {
            Chat.send(sender, "&c台高需在 0~32 之间（0 = 和你脚下一样高）。");
            return;
        }
        Location center = player.getLocation().clone();
        center.setX(center.getBlockX() + 0.5);
        center.setZ(center.getBlockZ() + 0.5);
        center.setY(center.getBlockY() + height);
        manager.arena().setHeight(height);
        int[] result = manager.build(center, radius, manager.arena().getDepth(), true);
        // 建完把人送到台面上，省得还要自己飞上去
        Location top = manager.arena().center();
        if (top != null) {
            player.teleport(top);
        }
        Chat.send(sender, "&a高台已建好：圆心 " + describe(manager.arena().center())
                + "，半径 &e" + radius + " &a，台高 &e" + height
                + " &a| 铺设方块 &e" + result[0] + " &a格");
        if (result[1] > 0) {
            Chat.send(sender, "&7已清掉上一版高台残留 &e" + result[1] + " &7格。");
        }
        Chat.send(sender, "&a已自动生成 &e" + result[2] + " &a个出生点并保存。"
                + "&7接着站到场外执行 &f/hill setlobby&7。");
    }

    private void list(CommandSender sender) {
        HillKingManager manager = stateOf(sender);
        Chat.send(sender, "&6===== 高台之王 =====");
        Chat.send(sender, "&7状态：&e" + manager.state() + " &7等待 &e" + manager.waitingCount()
                + " &7场内 &e" + manager.playingCount());
        Chat.send(sender, "&7场地：&f" + (manager.arena().isReady() ? "已配置" : "未配置（缺 "
                + manager.arena().missing() + "）")
                + " &7| 半径 &e" + manager.arena().getRadius()
                + " &7| 台高 &e" + manager.arena().getHeight()
                + " &7| 底座 &e" + manager.arena().getDepth()
                + " &7| 出生点 &e" + manager.arena().getSpawns().size());
        Chat.send(sender, "&7时长 &e" + manager.gameSeconds() + "s &7| 每秒 +&e"
                + plugin.getConfig().getInt("points-per-second", 1)
                + " &7分| 咸鱼击退 &e" + plugin.getConfig().getInt("fish.knockback-level", 2) + " 级");
    }

    private void stats(CommandSender sender, String[] args) {
        HillKingManager manager = stateOf(sender);
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
        Chat.send(sender, "&6" + target.getName() + " 的 " + HillKingManager.GAME + " 战绩："
                + "&e积分 &f" + manager.getPoints(target.getUniqueId())
                + " &7| &e第一 &f" + manager.getStat("firsts", target.getUniqueId())
                + " &6第二 &f" + manager.getStat("seconds", target.getUniqueId())
                + " &6第三 &f" + manager.getStat("thirds", target.getUniqueId()));
    }

    private void help(CommandSender sender) {
        Chat.send(sender, "&6====== 高台之王 /hill ======");
        Chat.send(sender, "&e/hill join&f 加入  &e/hill leave&f 离开  &e/hill list&f 状态  &e/hill stats [玩家]&f 战绩");
        if (admin(sender)) {
            Chat.send(sender, "&6-- OP --");
            Chat.send(sender, "&e/hill build [半径] [台高]&f 站在地面上一键建台（台高 = 台面比你脚下高几格，自动生成出生点）");
            Chat.send(sender, "&e/hill setlobby&f 等待点  &e/hill addspawn&f 添加出生点  &e/hill clearspawns&f 清空出生点");
            Chat.send(sender, "&e/hill genspawns [数量]&f 重新生成出生点（默认最多人数）");
            Chat.send(sender, "&e/hill save&f 保存  &e/hill start&f 开始  &e/hill stop&f 结束  &e/hill reload&f 重载");
            Chat.send(sender, "&e/hill stats reset&f 清空前三名与积分记录");
            Chat.send(sender, "&7时长/每秒得分/咸鱼击退在 config.yml。");
        }
    }

    private boolean admin(CommandSender sender) {
        if (sender instanceof Player player) {
            if (!stateOf(sender).isAdmin(player)) {
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
            boolean admin = !(sender instanceof Player player) || stateOf(sender).isAdmin(player);
            if (admin) {
                subs.addAll(List.of("start", "stop", "build", "setlobby", "addspawn",
                        "clearspawns", "genspawns", "save", "reload"));
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
                for (String option : List.of("8", "12", "16", "24", "32")) {
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
