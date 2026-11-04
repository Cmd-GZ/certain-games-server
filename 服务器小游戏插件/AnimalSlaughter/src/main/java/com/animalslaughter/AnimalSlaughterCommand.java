package com.animalslaughter;

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

/** /animal 命令（别名 /as、/slaughter）。 */
public final class AnimalSlaughterCommand implements CommandExecutor, TabCompleter {

    private final AnimalSlaughterPlugin plugin;
    /** D22：按房间分桶的状态；每个入口按发送者/玩家路由到他自己那一份。 */
    private final AnimalSlaughterRooms rooms;

    public AnimalSlaughterCommand(AnimalSlaughterPlugin plugin, AnimalSlaughterRooms rooms) {
        this.plugin = plugin;
        this.rooms = rooms;
    }

    /** 这个发送者应该操作的房间状态：玩家 → 他自己那一份；控制台 → 最近有人加入的那一份。 */
    private AnimalSlaughterManager stateOf(CommandSender sender) {
        return sender instanceof Player p ? rooms.forPlayer(p) : rooms.lastJoined();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        AnimalSlaughterManager manager = stateOf(sender);
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
                if (manager.state() != AnimalSlaughterManager.State.WAITING) {
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
            case "setarena" -> setArena(sender, args);
            case "setlobby" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player == null) {
                    return true;
                }
                manager.arena().setLobby(player.getLocation());
                Chat.send(sender, "&a等待点已设置为 " + describe(player.getLocation()) + "，记得 /animal save。");
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
                        + "（共 " + manager.arena().getSpawns().size() + " 个），记得 /animal save。");
            }
            case "clearspawns" -> {
                if (!admin(sender)) {
                    return true;
                }
                manager.arena().clearSpawns();
                Chat.send(sender, "&a已清空出生点，记得 /animal save。");
            }
            case "genspawns" -> {
                if (!admin(sender)) {
                    return true;
                }
                if (!manager.arena().hasCircle()) {
                    Chat.send(sender, "&c先站到场地中心执行 /animal setarena <半径>。");
                    return true;
                }
                int count = manager.maxPlayers();
                if (args.length > 1) {
                    try {
                        count = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ex) {
                        Chat.send(sender, "&c数量必须是数字，例如 /animal genspawns 12");
                        return true;
                    }
                }
                if (count < 1 || count > 64) {
                    Chat.send(sender, "&c数量需在 1~64 之间。");
                    return true;
                }
                int generated = manager.generateSpawns(count);
                manager.saveAll();
                if (generated <= 0) {
                    Chat.send(sender, "&c没找到可站立的方块：确认圆内已经铺了平台（/animal setarena 会自动铺草方块）。");
                } else {
                    Chat.send(sender, "&a已清空旧出生点，自动生成 &e" + generated + " &a个（请求 " + count + " 个）并保存。");
                    if (generated < count) {
                        Chat.send(sender, "&7圆形平台太小或位置不够，只生成了 " + generated + " 个。");
                    }
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
                if (manager.state() != AnimalSlaughterManager.State.WAITING) {
                    Chat.send(sender, "&c对局进行中，不能重载配置。");
                    return true;
                }
                plugin.reloadConfig();
                manager.reload();
                Chat.send(sender, "&a配置已重新载入。");
            }
            default -> Chat.send(sender, "&c未知子指令，输入 &f/animal help &c查看帮助。");
        }
        return true;
    }

    /** 站到场地中心后执行：写入圆心+半径，铺一格厚草方块圆盘，并清空旧出生点。 */
    private void setArena(CommandSender sender, String[] args) {
        AnimalSlaughterManager manager = stateOf(sender);
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            Chat.send(sender, "&c用法：/animal setarena <半径>（站在圆形场地中心执行）");
            return;
        }
        int radius;
        try {
            radius = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            Chat.send(sender, "&c半径必须是数字，例如 /animal setarena 16");
            return;
        }
        if (radius < 4 || radius > 64) {
            Chat.send(sender, "&c半径需在 4~64 之间。");
            return;
        }
        Location center = player.getLocation().clone();
        center.setX(center.getBlockX() + 0.5);
        center.setZ(center.getBlockZ() + 0.5);
        Location oldCenter = manager.arena().center();
        int oldRadius = manager.arena().getRadius();
        boolean sameArena = oldCenter != null && oldCenter.getWorld() != null && center.getWorld() != null
                && oldCenter.getWorld().getUID().equals(center.getWorld().getUID())
                && oldCenter.getBlockX() == center.getBlockX()
                && oldCenter.getBlockY() == center.getBlockY()
                && oldCenter.getBlockZ() == center.getBlockZ()
                && oldRadius == radius;
        manager.arena().setCenter(center);
        manager.arena().setRadius(radius);
        manager.arena().clearSpawns();
        int cleared = sameArena ? 0 : manager.arena().clearPlatform(oldCenter, oldRadius);
        int changed = manager.arena().buildPlatform();
        manager.saveAll();
        Chat.send(sender, "&a圆形场地已设置：圆心 " + describe(center) + "，半径 &e" + radius
                + " &a| 铺设草方块 &e" + changed + " &a格（一格厚）");
        if (cleared > 0) {
            Chat.send(sender, "&7已清掉上一版圆盘残留 &e" + cleared + " &7格草方块。");
        }
        Chat.send(sender, "&7出生点已清空，接着执行 &f/animal genspawns " + manager.maxPlayers()
                + " &7自动生成，再 &f/animal setlobby &7设等待点。");
    }

    private void list(CommandSender sender) {
        AnimalSlaughterManager manager = stateOf(sender);
        Chat.send(sender, "&6===== 动物屠宰 =====");
        Chat.send(sender, "&7状态：&e" + manager.state() + " &7等待 &e" + manager.waitingCount()
                + " &7场内 &e" + manager.playingCount());
        Chat.send(sender, "&7场地：&f" + (manager.arena().isReady() ? "已配置" : "未配置（缺 "
                + manager.arena().missing() + "）")
                + " &7| 半径 &e" + manager.arena().getRadius()
                + " &7| 出生点 &e" + manager.arena().getSpawns().size());
        Chat.send(sender, "&7时长 &e" + manager.gameSeconds() + "s &7| 开局倒计时 &e"
                + plugin.getConfig().getInt("countdown-seconds", 3) + "s"
                + " &7| 每 &e" + plugin.getConfig().getInt("mobs.spawn-interval-ticks", 20)
                + " tick &7补 &e" + plugin.getConfig().getInt("mobs.spawn-per-cycle", 2) + " &7只");
        Chat.send(sender, "&7上限 鸡 &e" + plugin.getConfig().getInt("mobs.limits.chicken", 10)
                + " &7猪 &e" + plugin.getConfig().getInt("mobs.limits.pig", 6)
                + " &7牛 &e" + plugin.getConfig().getInt("mobs.limits.cow", 3)
                + " &8| &7干扰项 &e" + plugin.getConfig().getInt("mobs.decoy-limits.chicken", 2)
                + "/" + plugin.getConfig().getInt("mobs.decoy-limits.pig", 1)
                + "/" + plugin.getConfig().getInt("mobs.decoy-limits.cow", 1));
        Chat.send(sender, "&7得分 鸡 &e" + plugin.getConfig().getInt("mobs.score.chicken", 1)
                + " &7猪 &e" + plugin.getConfig().getInt("mobs.score.pig", 3)
                + " &7牛 &e" + plugin.getConfig().getInt("mobs.score.cow", 5)
                + " &8| &7干扰项 &c-" + plugin.getConfig().getInt("mobs.decoy-percent", 50) + "%");
    }

    private void stats(CommandSender sender, String[] args) {
        AnimalSlaughterManager manager = stateOf(sender);
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
        Chat.send(sender, "&6" + target.getName() + " 的 " + AnimalSlaughterManager.GAME + " 战绩："
                + "&e积分 &f" + manager.getPoints(target.getUniqueId())
                + " &7| &e第一 &f" + manager.getStat("firsts", target.getUniqueId())
                + " &6第二 &f" + manager.getStat("seconds", target.getUniqueId())
                + " &6第三 &f" + manager.getStat("thirds", target.getUniqueId()));
    }

    private void help(CommandSender sender) {
        Chat.send(sender, "&6====== 动物屠宰 /animal ======");
        Chat.send(sender, "&e/animal join&f 加入  &e/animal leave&f 离开  &e/animal list&f 状态  &e/animal stats [玩家]&f 战绩");
        if (admin(sender)) {
            Chat.send(sender, "&6-- OP --");
            Chat.send(sender, "&e/animal setarena <半径>&f 站在圆心设置圆形场地（自动铺一格厚草方块平台）");
            Chat.send(sender, "&e/animal setlobby&f 等待点  &e/animal addspawn&f 添加出生点  &e/animal clearspawns&f 清空出生点");
            Chat.send(sender, "&e/animal genspawns [数量]&f 自动生成出生点（默认最多人数，会清空旧的）");
            Chat.send(sender, "&e/animal save&f 保存  &e/animal start&f 开始  &e/animal stop&f 结束  &e/animal reload&f 重载");
            Chat.send(sender, "&e/animal stats reset&f 清空前三名与积分记录");
            Chat.send(sender, "&7动物上限/血量/分数/逃跑速度/掉落清理在 config.yml。");
        }
    }

    private boolean admin(CommandSender sender) {
        AnimalSlaughterManager manager = stateOf(sender);
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
            boolean admin = !(sender instanceof Player player) || rooms.forPlayer(player).isAdmin(player);
            if (admin) {
                subs.addAll(List.of("start", "stop", "setarena", "setlobby", "addspawn",
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
            } else if (args[0].equalsIgnoreCase("setarena")) {
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
