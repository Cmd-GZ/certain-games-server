package com.grouprace;

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

/** /race 命令。 */
public final class GroupRaceCommand implements CommandExecutor, TabCompleter {

    private final GroupRacePlugin plugin;
    /** D22：按房间分桶的状态；每个入口按发送者/玩家路由到他自己那一份。 */
    private final GroupRaceRooms rooms;

    public GroupRaceCommand(GroupRacePlugin plugin, GroupRaceRooms rooms) {
        this.plugin = plugin;
        this.rooms = rooms;
    }

    /** 这个发送者应该操作的房间状态：玩家 → 他自己那一份；控制台 → 最近有人加入的那一份。 */
    private GroupRaceManager stateOf(CommandSender sender) {
        return sender instanceof Player p ? rooms.forPlayer(p) : rooms.current();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        GroupRaceManager manager = stateOf(sender);
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
            case "start" -> {
                // D21：开始权在主持人手上；主持人**不享受管理员特权**（人数不够不能开）
                boolean isAdminS = !(sender instanceof Player sp) || sp.hasPermission("grouprace.admin");
                if (!isAdminS) {
                    if (!manager.isHost(sender)) {
                        Chat.send(sender, "&c只有本局**主持人**或管理员可以开始。");
                        return true;
                    }
                    if (manager.waitingCountPublic() < manager.minPlayersPublic()) {
                        Chat.send(sender, "&c人数还没达到 &f" + manager.minPlayersPublic()
                                + " 人&c，主持人也不能开始。");
                        return true;
                    }
                }
                if (manager.isRunning()) {
                    Chat.send(sender, "&e比赛已经在进行中。");
                } else if (manager.arena().isReady()) {
                    manager.startGame(true);
                } else {
                    Chat.send(sender, "&c赛道未配置完整（缺 " + manager.arena().missing() + "）。");
                }
            }
            case "stop" -> {
                if (!admin(sender)) {
                    return true;
                }
                manager.stopGame();
            }
            case "setlobby" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player == null) {
                    return true;
                }
                manager.arena().setLobby(player.getLocation());
                manager.arena().save();
                Chat.send(sender, "&a等待点已设置并保存：" + describe(player.getLocation()));
            }
            case "setstart" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player == null) {
                    return true;
                }
                manager.arena().setStart(player.getLocation());
                manager.arena().save();
                Chat.send(sender, "&a起跑点已设置并保存：" + describe(player.getLocation())
                        + "（面朝方向就是赛道方向）");
            }
            case "pos1" -> setCorner(sender, true);
            case "pos2" -> setCorner(sender, false);
            case "addring" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player != null) {
                    manager.addRing(player, false);
                }
            }
            case "addfinish" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player != null) {
                    manager.addRing(player, true);
                }
            }
            case "clearrings" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player != null) {
                    manager.clearRings(player);
                }
            }
            case "listrings" -> listRings(sender);
            case "save" -> {
                if (!admin(sender)) {
                    return true;
                }
                manager.arena().save();
                Chat.send(sender, "&a赛道已写入 config.yml。");
            }
            case "reload" -> {
                if (!admin(sender)) {
                    return true;
                }
                if (manager.isRunning()) {
                    Chat.send(sender, "&c比赛中不能重载配置。");
                    return true;
                }
                plugin.reloadConfig();
                manager.reload();
                Chat.send(sender, "&a配置已重载。赛道状态："
                        + (manager.arena().isReady() ? "&a已配置" : "&c缺 " + manager.arena().missing()));
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        Chat.send(sender, "&e&l" + GroupRaceManager.GAME_NAME + " &7(GroupRace)");
        Chat.send(sender, "&e/race join &7- 加入等待队列");
        Chat.send(sender, "&e/race leave &7- 离开比赛");
        Chat.send(sender, "&e/race list &7- 查看状态");
        if (adminOnly(sender)) {
            Chat.send(sender, "&e/race start &7/ &e/race stop &7- 开始 / 结束比赛");
            Chat.send(sender, "&e/race setlobby &7/ &e/race setstart &7- 设置等待点 / 起跑点");
            Chat.send(sender, "&e/race pos1 &7/ &e/race pos2 &7- 设置出界方框两个对角");
            Chat.send(sender, "&e/race addring &7- 在当前位置生成普通圆环（面向=赛道方向）");
            Chat.send(sender, "&e/race addfinish &7- 生成终点圆环（必须最后）");
            Chat.send(sender, "&e/race clearrings &7- 清除全部圆环方块和数据");
            Chat.send(sender, "&e/race listrings &7- 列出圆环");
            Chat.send(sender, "&e/race save &7/ &e/race reload &7- 保存 / 重载配置");
        }
    }

    private void list(CommandSender sender) {
        GroupRaceManager manager = stateOf(sender);
        Chat.send(sender, "&e[" + GroupRaceManager.GAME_NAME + "] &f状态："
                + (manager.isRunning() ? "&a比赛中 &7(剩余 " + manager.secondsLeft() + "s)" : "&e等待中")
                + " &7| 等待 &e" + manager.waitingCount() + " &7| 参赛 &e" + manager.playingCount());
        Chat.send(sender, "&7赛道：" + (manager.arena().isReady()
                ? "&a已配置 &7(圆环 " + manager.arena().rings().size() + " 个)"
                : "&c缺 " + manager.arena().missing()));
    }

    private void listRings(CommandSender sender) {
        GroupRaceManager manager = stateOf(sender);
        List<GroupRaceRing> rings = manager.arena().rings();
        if (rings.isEmpty()) {
            Chat.send(sender, "&e还没有圆环。");
            return;
        }
        Chat.send(sender, "&e共 " + rings.size() + " 个圆环：");
        for (GroupRaceRing ring : rings) {
            Chat.send(sender, "&7#" + (ring.index() + 1) + " " + describe(ring.center())
                    + (ring.finish() ? " &6[终点]" : " &7半径 " + ring.radius()));
        }
    }

    private void setCorner(CommandSender sender, boolean first) {
        GroupRaceManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        Location loc = player.getLocation();
        if (first) {
            manager.arena().setPos1(loc);
        } else {
            manager.arena().setPos2(loc);
        }
        manager.arena().save();
        Chat.send(sender, "&a已设置 " + (first ? "pos1" : "pos2") + " 并保存：" + describe(loc));
    }

    private static String describe(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        Chat.send(sender, "&c这个命令只能由玩家执行。");
        return null;
    }

    private boolean admin(CommandSender sender) {
        if (sender.isOp() || sender.hasPermission("race.admin")) {
            return true;
        }
        Chat.send(sender, "&c你没有权限执行这个命令。");
        return false;
    }

    private boolean adminOnly(CommandSender sender) {
        return sender.isOp() || sender.hasPermission("race.admin");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(List.of("join", "leave", "list", "help"));
        if (adminOnly(sender)) {
            options.addAll(List.of("start", "stop", "setlobby", "setstart", "pos1", "pos2",
                    "addring", "addfinish", "clearrings", "listrings", "save", "reload"));
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.startsWith(prefix));
        return options;
    }
}
