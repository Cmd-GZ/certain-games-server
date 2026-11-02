package com.knockback;

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

/** /kb 命令。 */
public final class KnockbackCommand implements CommandExecutor, TabCompleter {

    private final KnockbackPlugin plugin;
    private final KnockbackManager manager;

    public KnockbackCommand(KnockbackPlugin plugin, KnockbackManager manager) {
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
            case "setpos1" -> setCorner(sender, true);
            case "setpos2" -> setCorner(sender, false);
            case "setlobby" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player == null) {
                    return true;
                }
                manager.arena().setLobby(player.getLocation());
                Chat.send(sender, "&a等待点已设置为 " + describe(player.getLocation()) + "，记得 /kb save。");
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
                        + "（共 " + manager.arena().getSpawns().size() + " 个），记得 /kb save。");
            }
            case "clearspawns" -> {
                if (!admin(sender)) {
                    return true;
                }
                manager.arena().clearSpawns();
                Chat.send(sender, "&a已清空出生点，记得 /kb save。");
            }
            case "setvoid" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player == null) {
                    return true;
                }
                double y = player.getLocation().getY();
                if (args.length > 1) {
                    try {
                        y = Double.parseDouble(args[1]);
                    } catch (NumberFormatException ex) {
                        Chat.send(sender, "&cY 必须是数字，例如 /kb setvoid -20");
                        return true;
                    }
                }
                manager.arena().setVoidY(y);
                Chat.send(sender, "&a淘汰高度已设置为 Y=" + format(y) + "，记得 /kb save。");
            }
            case "save" -> {
                if (!admin(sender)) {
                    return true;
                }
                manager.arena().save();
                Chat.send(sender, "&a场地已写入 config.yml。");
            }
            case "reload" -> {
                if (!admin(sender)) {
                    return true;
                }
                if (manager.isRunning()) {
                    Chat.send(sender, "&c对局进行中不能重载配置。");
                    return true;
                }
                plugin.reloadConfig();
                manager.reload();
                Chat.send(sender, "&a配置已重载。场地状态："
                        + (manager.arena().isReady() ? "&a已配置" : "&c缺 " + manager.arena().missing()));
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        Chat.send(sender, "&b&l" + KnockbackManager.GAME_NAME + " &7(KnockbackBattle)");
        Chat.send(sender, "&b/kb join &7- 加入等待队列");
        Chat.send(sender, "&b/kb leave &7- 离开对局");
        Chat.send(sender, "&b/kb list &7- 查看对局状态");
        if (adminOnly(sender)) {
            Chat.send(sender, "&b/kb start &7- 立即开始（至少 1 人）");
            Chat.send(sender, "&b/kb stop &7- 结束当前对局");
            Chat.send(sender, "&b/kb setpos1 &7/ &b/kb setpos2 &7- 设置场地 X/Z 对角");
            Chat.send(sender, "&b/kb setlobby &7- 设置等待点");
            Chat.send(sender, "&b/kb addspawn &7- 添加出生点（可多次）");
            Chat.send(sender, "&b/kb clearspawns &7- 清空出生点");
            Chat.send(sender, "&b/kb setvoid [Y] &7- 设置淘汰高度（不写 Y 取当前高度）");
            Chat.send(sender, "&b/kb save &7- 保存场地到 config.yml");
            Chat.send(sender, "&b/kb reload &7- 重载 config.yml");
        }
    }

    private void list(CommandSender sender) {
        Chat.send(sender, "&b[" + KnockbackManager.GAME_NAME + "] &f状态："
                + (manager.isRunning() ? "&a对局中 &7(剩余 " + manager.secondsLeft() + "s)" : "&e等待中"));
        Chat.send(sender, "&7场地：" + (manager.arena().isReady()
                ? "&a已配置 &7(出生点 " + manager.arena().getSpawns().size() + " 个，淘汰高度 Y="
                        + format(manager.arena().getVoidY()) + ")"
                : "&c缺 " + manager.arena().missing()));
    }

    private void setCorner(CommandSender sender, boolean first) {
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
        Chat.send(sender, "&a已设置 " + (first ? "pos1" : "pos2") + " 为 " + describe(loc)
                + "，记得 /kb save。");
    }

    private static String describe(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        Chat.send(sender, "&c这个命令只能由玩家执行。");
        return null;
    }

    private boolean admin(CommandSender sender) {
        if (sender.isOp() || sender.hasPermission("kb.admin")) {
            return true;
        }
        Chat.send(sender, "&c你没有权限执行这个命令。");
        return false;
    }

    private boolean adminOnly(CommandSender sender) {
        return sender.isOp() || sender.hasPermission("kb.admin");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(List.of("join", "leave", "list", "help"));
        if (adminOnly(sender)) {
            options.addAll(List.of("start", "stop", "setpos1", "setpos2", "setlobby",
                    "addspawn", "clearspawns", "setvoid", "save", "reload"));
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.startsWith(prefix));
        return options;
    }
}
