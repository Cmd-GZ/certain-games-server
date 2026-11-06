package com.memoryflip;

import com.hub.util.Chat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /memory 命令（别名 /mem、/memoryflip、/flip）。 */
public final class MemoryFlipCommand implements CommandExecutor, TabCompleter {

    private final MemoryFlipPlugin plugin;
    private final MemoryFlipManager manager;

    public MemoryFlipCommand(MemoryFlipPlugin plugin, MemoryFlipManager manager) {
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
                Player p = player(sender);
                if (p != null) {
                    manager.join(p);
                }
            }
            case "leave" -> {
                Player p = player(sender);
                if (p != null) {
                    manager.leave(p, false);
                }
            }
            case "list" -> list(sender);
            case "stats" -> stats(sender, args);
            case "start" -> {
                // D21：开始权在主持人手上；但主持人**不享受管理员特权**（人数不够不能开）
                boolean isAdmin = !(sender instanceof org.bukkit.entity.Player sp) || manager.isAdmin(sp);
                if (!isAdmin) {
                    if (!manager.isHost(sender)) {
                        Chat.send(sender, "&c只有本局**主持人**或管理员可以开始。"
                                + "&7（主持人 = 最早加入等待队列的人）");
                        return true;
                    }
                    if (manager.waitingCount() < manager.minPlayers()) {
                        Chat.send(sender, "&c人数还没达到 &f" + manager.minPlayers()
                                + " 人&c，主持人也不能开始。");
                        return true;
                    }
                }
                if (manager.isRunning()) {
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
                Player p = player(sender);
                if (p == null) {
                    return true;
                }
                manager.arenaFor(p).setLobby(p.getLocation());
                manager.saveAll();
                Chat.send(sender, "&a等待点已设置并保存。");
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
                if (manager.isRunning()) {
                    Chat.send(sender, "&c对局进行中，不能重载配置。");
                    return true;
                }
                plugin.reloadConfig();
                manager.reload();
                Chat.send(sender, "&a配置已重新载入。");
            }
            default -> Chat.send(sender, "&c未知子指令，输入 &f/memory help &c查看帮助。");
        }
        return true;
    }

    /** 以玩家脚下为基准自动建 count 面翻牌墙。 */
    private void build(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        int rows = Math.max(2, Math.min(8, plugin.getConfig().getInt("board.rows", 4)));
        int cols = Math.max(2, Math.min(8, plugin.getConfig().getInt("board.cols", 4)));
        if ((rows * cols) % 2 != 0) {
            Chat.send(sender, "&crows × cols 必须是偶数（现在是 " + rows + "×" + cols + "）。");
            return;
        }
        int spacing = Math.max(cols + 2, Math.min(32, plugin.getConfig().getInt("board.spacing", 6)));
        if (spacing % 2 != 0) {
            spacing++; // 偶数间距，四排才能严格镜像
        }
        int count = manager.maxPlayers();
        if (args.length > 1) {
            try {
                count = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                Chat.send(sender, "&c人数必须是数字，例如 /memory build 16");
                return;
            }
        }
        if (count < 4 || count > 32) {
            Chat.send(sender, "&c人数需在 4~32 之间。");
            return;
        }
        if (count % 2 != 0) {
            count--;
            Chat.send(sender, "&e人数必须是偶数，已按 &f" + count + " &e生成。");
        }
        Material wall = Material.matchMaterial(
                plugin.getConfig().getString("board.wall-material", "SMOOTH_STONE"), false);
        Material floor = Material.matchMaterial(
                plugin.getConfig().getString("board.floor-material", "SMOOTH_STONE"), false);
        if (wall == null || !wall.isBlock()) {
            wall = Material.SMOOTH_STONE;
        }
        if (floor == null || !floor.isBlock()) {
            floor = Material.SMOOTH_STONE;
        }
        int built = manager.arenaFor(player).build(player, count, rows, cols, spacing, wall, floor);
        manager.saveAll();
        Chat.send(sender, "&a已清掉旧场地并生成 &e" + built + " &a面翻牌墙（" + rows + "×" + cols
                + "，共 " + (rows * cols) + " 格），面向你站的方向。");
        Chat.send(sender, "&7接着站到场外执行 &f/memory setlobby &7，然后 &f/memory start &7测试。");
    }

    private void list(CommandSender sender) {
        Chat.send(sender, "&6===== 记忆翻牌 =====");
        Chat.send(sender, "&7状态：&e" + manager.state() + " &7等待 &e" + manager.waitingCount()
                + " &7场内 &e" + manager.playingCount());
        Chat.send(sender, "&7场地：&f" + (manager.arena().isReady() ? "已配置" : "未配置（缺 "
                + manager.arena().missing() + "）")
                + " &7| 翻牌墙 &e" + manager.arena().getBoards().size() + " 面"
                + " &7| 每面 &e" + (plugin.getConfig().getInt("board.rows", 4) * plugin.getConfig().getInt("board.cols", 4)) + " 格");
        Chat.send(sender, "&7时长 &e" + manager.gameSeconds() + "s &7| 最多 &e" + manager.effectiveMaxPlayers() + " 人");
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
        Chat.send(sender, "&6" + target.getName() + " 的 " + MemoryFlipManager.GAME + " 战绩："
                + "&e积分 &f" + manager.getPoints(target.getUniqueId())
                + " &7| &e第一 &f" + manager.getStat("firsts", target.getUniqueId())
                + " &6第二 &f" + manager.getStat("seconds", target.getUniqueId())
                + " &6第三 &f" + manager.getStat("thirds", target.getUniqueId()));
    }

    private void help(CommandSender sender) {
        Chat.send(sender, "&6====== 记忆翻牌 /memory ======");
        Chat.send(sender, "&e/memory join&f 加入  &e/memory leave&f 离开  &e/memory list&f 状态  &e/memory stats [玩家]&f 战绩");
        if (admin(sender)) {
            Chat.send(sender, "&6-- OP --");
            Chat.send(sender, "&e/memory build [人数]&f 站好位置自动生成翻牌墙（面向你的朝向）");
            Chat.send(sender, "&e/memory setlobby&f 等待点  &e/memory save&f 保存");
            Chat.send(sender, "&e/memory start&f 开始  &e/memory stop&f 结束  &e/memory reload&f 重载");
            Chat.send(sender, "&7牌面尺寸/间距/物品池在 config.yml 的 board 段。");
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("join", "leave", "list", "stats", "help"));
            boolean admin = !(sender instanceof Player player) || manager.isAdmin(player);
            if (admin) {
                subs.addAll(List.of("build", "setlobby", "save", "start", "stop", "reload"));
            }
            subs.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).forEach(out::add);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("build")) {
            for (String option : List.of("4", "8", "12", "16")) {
                if (option.startsWith(args[1])) {
                    out.add(option);
                }
            }
        }
        return out;
    }
}
