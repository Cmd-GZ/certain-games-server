package com.snakeparty;

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

/** /snake 命令（别名 /snakeparty）。 */
public final class SnakePartyCommand implements CommandExecutor, TabCompleter {

    private final SnakePartyPlugin plugin;
    /** D22：按房间分桶的状态；每个入口按发送者/玩家路由到他自己那一份。 */
    private final SnakePartyRooms rooms;

    public SnakePartyCommand(SnakePartyPlugin plugin, SnakePartyRooms rooms) {
        this.plugin = plugin;
        this.rooms = rooms;
    }

    /** 这个发送者应该操作的房间状态：玩家 → 他自己那一份；控制台 → 最近有人加入的那一份。 */
    private SnakePartyManager stateOf(CommandSender sender) {
        return sender instanceof Player p ? rooms.forPlayer(p) : rooms.lastJoined();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        SnakePartyManager manager = stateOf(sender);
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
                // D21：开始权在主持人手上；主持人**不享受管理员特权**（人数不够不能开）
                boolean isAdminS = !(sender instanceof Player sp) || manager.isAdmin(sp);
                if (!isAdminS) {
                    if (!manager.isHost(sender)) {
                        Chat.send(sender, "&c只有本局**主持人**或管理员可以开始。");
                        return true;
                    }
                    if (manager.waitingCount() < manager.minPlayersPublic()) {
                        Chat.send(sender, "&c人数还没达到 &f" + manager.minPlayersPublic()
                                + " 人&c，主持人也不能开始。");
                        return true;
                    }
                }
                if (manager.state() != SnakePartyManager.State.WAITING) {
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
            case "setarena" -> setArea(sender, args, true);
            case "setarea" -> setArea(sender, args, false);
            case "setlobby" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player == null) {
                    return true;
                }
                manager.arena().setLobby(player.getLocation());
                Chat.send(sender, "&a等待点已设置为 " + describe(player.getLocation()) + "，记得 /snake save。");
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
                Chat.send(sender, "&a已添加出生点 " + describe(player.getLocation())
                        + "（共 " + manager.arena().getSpawns().size() + " 个），记得 /snake save。");
            }
            case "clearspawns" -> {
                if (!admin(sender)) {
                    return true;
                }
                manager.arena().clearSpawns();
                Chat.send(sender, "&a已清空出生点，记得 /snake save。");
            }
            case "genspawns" -> {
                if (!admin(sender)) {
                    return true;
                }
                if (!manager.arena().hasArea()) {
                    Chat.send(sender, "&c先站到场地中心执行 /snake setarena <半径>。");
                    return true;
                }
                int count = manager.maxPlayers();
                if (args.length > 1) {
                    try {
                        count = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ex) {
                        Chat.send(sender, "&c数量必须是数字，例如 /snake genspawns 8");
                        return true;
                    }
                }
                if (count < 1 || count > 64) {
                    Chat.send(sender, "&c数量需在 1~64 之间。");
                    return true;
                }
                List<Location> points = manager.arena().ringSpawns(count);
                manager.arena().clearSpawns();
                for (Location point : points) {
                    manager.arena().addSpawn(point);
                }
                manager.saveAll();
                Chat.send(sender, "&a已清空旧出生点，沿场地内圈生成 &e" + points.size()
                        + " &a个出生点（朝向切线方向）并保存。");
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
                if (manager.state() != SnakePartyManager.State.WAITING) {
                    Chat.send(sender, "&c对局进行中，不能重载配置。");
                    return true;
                }
                plugin.reloadConfig();
                manager.reload();
                Chat.send(sender, "&a配置已重新载入。");
            }
            default -> Chat.send(sender, "&c未知子指令，输入 &f/snake help &c查看帮助。");
        }
        return true;
    }

    /**
     * setarena：以脚下为圆心写入半径并铺平台；setarea：只划范围不动方块（自己搭平台用）。
     */
    private void setArea(CommandSender sender, String[] args, boolean build) {
        SnakePartyManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            Chat.send(sender, "&c用法：/" + (build ? "setarena" : "setarea") + " <半径>（站在场地中心执行）");
            return;
        }
        int radius;
        try {
            radius = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            Chat.send(sender, "&c半径必须是数字，例如 /snake setarena 16");
            return;
        }
        if (radius < 4 || radius > 64) {
            Chat.send(sender, "&c半径需在 4~64 之间（场地是边长 2*半径+1 的方形）。");
            return;
        }
        Location center = player.getLocation().clone();
        center.setX(center.getBlockX() + 0.5);
        center.setZ(center.getBlockZ() + 0.5);
        Location oldCenter = manager.arena().center();
        int oldRadius = manager.arena().getRadius();
        boolean same = oldCenter != null && oldCenter.getWorld() != null && center.getWorld() != null
                && oldCenter.getWorld().getUID().equals(center.getWorld().getUID())
                && oldCenter.getBlockX() == center.getBlockX()
                && oldCenter.getBlockY() == center.getBlockY()
                && oldCenter.getBlockZ() == center.getBlockZ()
                && oldRadius == radius;
        manager.arena().setCenter(center);
        manager.arena().setRadius(radius);
        manager.arena().clearSpawns();
        int cleared = 0;
        int changed = 0;
        if (build) {
            cleared = same ? 0 : manager.arena().clearPlatform(oldCenter, oldRadius);
            changed = manager.arena().buildPlatform();
        }
        manager.saveAll();
        Chat.send(sender, "&a场地已设置：中心 " + describe(center) + "，半径 &e" + radius
                + " &a（方形 " + (radius * 2 + 1) + "×" + (radius * 2 + 1) + "）");
        if (build) {
            Chat.send(sender, "&7已铺平台 &e" + changed + " &7格"
                    + (cleared > 0 ? "，并清掉旧平台残留 &e" + cleared + " &7格" : "") + "。");
        } else {
            Chat.send(sender, "&7只划定了范围，没有动方块；请自己搭好平台。");
        }
        Chat.send(sender, "&7出生点已清空，接着执行 &f/snake genspawns " + manager.maxPlayers()
                + " &7自动生成，再 &f/snake setlobby &7设等待点。");
    }

    private void list(CommandSender sender) {
        SnakePartyManager manager = stateOf(sender);
        Chat.send(sender, "&6===== 蛇行派对 =====");
        Chat.send(sender, "&7状态：&e" + manager.state() + " &7等待 &e" + manager.waitingCount()
                + " &7场内 &e" + manager.playingCount() + " &7淘汰 &e" + manager.eliminatedCount());
        Chat.send(sender, "&7场地：&f" + (manager.arena().isReady() ? "已配置" : "未配置（缺 "
                + manager.arena().missing() + "）")
                + " &7| 半径 &e" + manager.arena().getRadius()
                + " &7| 平台 &f" + manager.arena().getFloorMaterial().name()
                + "×" + manager.arena().getFloorLayers()
                + " &7| 出生点 &e" + manager.arena().getSpawns().size());
        Chat.send(sender, "&7时长 &e" + manager.gameSeconds() + "s &7| 开局倒计时 &e"
                + plugin.getConfig().getInt("countdown-seconds", 3) + "s"
                + " &7| 前进速度 &e" + String.format(Locale.ROOT, "%.2f",
                plugin.getConfig().getDouble("movement.speed", 0.28)));
        Chat.send(sender, "&7玻璃板存活 &e"
                + String.format(Locale.ROOT, "%.1f", plugin.getConfig().getDouble("plate.lifetime-seconds", 3.0))
                + "s &7起，每秒 &e+" + plugin.getConfig().getInt("plate.lifetime-growth-ms-per-second", 150)
                + "ms &7，上限 &e"
                + String.format(Locale.ROOT, "%.1f", plugin.getConfig().getDouble("plate.max-lifetime-seconds", 45.0))
                + "s");
    }

    private void stats(CommandSender sender, String[] args) {
        SnakePartyManager manager = stateOf(sender);
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
        Chat.send(sender, "&6" + target.getName() + " 的 " + SnakePartyManager.GAME + " 战绩："
                + "&e积分 &f" + manager.getPoints(target.getUniqueId())
                + " &7| &e第一 &f" + manager.getStat("firsts", target.getUniqueId())
                + " &6第二 &f" + manager.getStat("seconds", target.getUniqueId())
                + " &6第三 &f" + manager.getStat("thirds", target.getUniqueId()));
    }

    private void help(CommandSender sender) {
        Chat.send(sender, "&6====== 蛇行派对 /snake ======");
        Chat.send(sender, "&e/snake join&f 加入  &e/snake leave&f 离开  &e/snake list&f 状态  &e/snake stats [玩家]&f 战绩");
        if (admin(sender)) {
            Chat.send(sender, "&6-- OP --");
            Chat.send(sender, "&e/snake setarena <半径>&f 站中心设置方形场地并自动铺平台");
            Chat.send(sender, "&e/snake setarea <半径>&f 只划范围不动方块（自己搭平台时用）");
            Chat.send(sender, "&e/snake setlobby&f 等待点  &e/snake addspawn&f 添加出生点  &e/snake clearspawns&f 清空出生点");
            Chat.send(sender, "&e/snake genspawns [数量]&f 沿场地内圈自动生成出生点（默认最多人数）");
            Chat.send(sender, "&e/snake save&f 保存  &e/snake start&f 开始  &e/snake stop&f 结束  &e/snake reload&f 重载");
            Chat.send(sender, "&e/snake stats reset&f 清空前三名与积分记录");
            Chat.send(sender, "&7速度/玻璃板存活时间/场地材质在 config.yml。");
        }
    }

    private boolean admin(CommandSender sender) {
        SnakePartyManager manager = stateOf(sender);
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
        return loc.getWorld().getName() + " " + String.format(Locale.ROOT, "%.1f %.1f %.1f",
                loc.getX(), loc.getY(), loc.getZ());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("join", "leave", "list", "stats", "help"));
            SnakePartyManager manager = stateOf(sender);
            boolean admin = !(sender instanceof Player player) || manager.isAdmin(player);
            if (admin) {
                subs.addAll(List.of("start", "stop", "setarena", "setarea", "setlobby", "addspawn",
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
            } else if (args[0].equalsIgnoreCase("setarena") || args[0].equalsIgnoreCase("setarea")) {
                for (String option : List.of("8", "12", "16", "24", "32")) {
                    if (option.startsWith(args[1])) {
                        out.add(option);
                    }
                }
            }
        }
        return out;
    }
}
