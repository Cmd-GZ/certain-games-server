package com.jumpfill;

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
import java.util.Map;
import java.util.UUID;

public final class JumpFillCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "join", "leave", "list", "stats", "help",
            "setwater", "addstart", "clearstarts", "setlobby", "save", "start", "stop", "reload");

    private final JumpFillPlugin plugin;
    /** D22：按房间分桶的状态；每个入口按发送者/玩家路由到他自己那一份。 */
    private final JumpFillRooms rooms;

    public JumpFillCommand(JumpFillPlugin plugin, JumpFillRooms rooms) {
        this.plugin = plugin;
        this.rooms = rooms;
    }

    /** 这个发送者应该操作的房间状态：玩家 → 他自己那一份；控制台 → 当前房间上下文/最近有人加入的那一份。 */
    private JumpFillManager stateOf(CommandSender sender) {
        return sender instanceof Player p ? rooms.forPlayer(p) : rooms.current();
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
            case "join" -> join(sender);
            case "leave" -> leave(sender);
            case "list" -> list(sender);
            case "stats" -> stats(sender, args);
            case "help" -> help(sender);
            case "setwater" -> setWater(sender, args);
            case "addstart" -> addStart(sender);
            case "clearstarts" -> clearStarts(sender);
            case "setlobby" -> setLobby(sender);
            case "save" -> save(sender);
            case "start" -> start(sender);
            case "stop" -> stop(sender);
            case "reload" -> reload(sender);
            default -> Chat.send(sender, "&c未知子指令，输入 &f/jump help &c查看帮助。");
        }
        return true;
    }

    private void join(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c只有玩家可以加入游戏。");
            return;
        }
        rooms.join(player);
    }

    private void leave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c只有玩家可以离开游戏。");
            return;
        }
        if (!rooms.forPlayer(player).isPlaying(player)) {
            Chat.send(player, "&7你不在游戏中。");
            return;
        }
        rooms.leave(player, false);
        Chat.send(player, "&7已离开跳水填格。");
    }

    private void list(CommandSender sender) {
        JumpFillManager manager = stateOf(sender);
        Chat.send(sender, "&6===== 跳水填格 =====");
        String state = manager.isRunning() ? "&c游戏中" : (manager.isEnding() ? "&c结算中" : "&e等待中");
        Chat.send(sender, "&7状态: " + state + " &7| 玩家: &f" + manager.statesCount()
                + "&7/&f" + manager.maxPlayers() + " &7| 剩余水格: &f" + manager.remaining());
        Chat.send(sender, "&7水域: &f" + manager.arena().width() + "x" + manager.arena().depth()
                + " &7(" + manager.arena().volume() + " 格) &7| 出发点: &f"
                + manager.arena().starts().size() + " &7个");
        if (!manager.arena().ready()) {
            Chat.send(sender, "&c场地尚未配置完整：/jump setwater 1|2、/jump addstart、/jump save");
        }
        if (manager.statesCount() == 0) {
            return;
        }
        for (Map.Entry<UUID, Integer> entry : manager.scores().entrySet()) {
            Chat.send(sender, " &7- &f" + manager.nameOf(entry.getKey())
                    + " &7(" + manager.colorNameOf(entry.getKey()) + ") &e" + entry.getValue() + " 分");
        }
    }

    private void stats(CommandSender sender, String[] args) {
        JumpFillManager manager = stateOf(sender);
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
            Chat.send(sender, "&c用法: /jump stats <玩家>");
            return;
        }
        Chat.send(sender, "&6" + name + " &7的跳水填格冠军数：&e" + manager.firsts(id));
    }

    private void help(CommandSender sender) {
        Chat.send(sender, "&6跳水填格指令:");
        Chat.send(sender, "&f/jump join &7- 加入等待队列");
        Chat.send(sender, "&f/jump leave &7- 离开游戏");
        Chat.send(sender, "&f/jump list &7- 查看状态与得分");
        Chat.send(sender, "&f/jump stats [玩家] &7- 查看冠军数");
        if (sender.hasPermission("jumpfill.admin")) {
            Chat.send(sender, "&6-- 管理 --");
            Chat.send(sender, "&f/jump setwater 1|2 &7- 站在水域对角设置水域（必须一格高）");
            Chat.send(sender, "&f/jump addstart &7- 站在高台出发点添加一个出生点");
            Chat.send(sender, "&f/jump clearstarts &7- 清空所有出发点");
            Chat.send(sender, "&f/jump setlobby &7- 设置等待点");
            Chat.send(sender, "&f/jump save &7- 保存场地配置");
            Chat.send(sender, "&f/jump start &7- 立即开始");
            Chat.send(sender, "&f/jump stop &7- 强制结束本局");
            Chat.send(sender, "&f/jump reload &7- 重新读取配置");
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("jumpfill.admin")) {
            Chat.send(sender, "&c你没有权限执行该指令。");
            return false;
        }
        return true;
    }

    private void setWater(CommandSender sender, String[] args) {
        JumpFillManager manager = stateOf(sender);
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c只有玩家可以设置水域。");
            return;
        }
        if (args.length < 2 || !(args[1].equals("1") || args[1].equals("2"))) {
            Chat.send(sender, "&c用法: /jump setwater 1|2（站在水域的两个对角）");
            return;
        }
        int index = Integer.parseInt(args[1]);
        Location location = player.getLocation().getBlock().getLocation();
        manager.arena().setWaterCorner(index, location);
        Chat.send(player, "&a已设置水域角" + index + ": &f"
                + location.getWorld().getName() + " " + location.getBlockX() + ", "
                + location.getBlockY() + ", " + location.getBlockZ());
        if (manager.arena().waterConfigured()) {
            Chat.send(player, "&7水域大小: &f" + manager.arena().width() + "x" + manager.arena().depth()
                    + " &7(" + manager.arena().volume() + " 格)");
        } else if (manager.arena().water1() != null && manager.arena().water2() != null) {
            Chat.send(player, "&c两个角的 Y 坐标必须相同（水域只有一格高），且必须在同一个世界。");
        }
        Chat.send(player, "&7设置完成后执行 &f/jump start &7测试，或等玩家加入。");
    }

    private void addStart(CommandSender sender) {
        JumpFillManager manager = stateOf(sender);
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c只有玩家可以添加出发点。");
            return;
        }
        manager.arena().addStart(player.getLocation());
        Chat.send(player, "&a已添加出发点，共 &f" + manager.arena().starts().size()
                + " &a个。记得 &f/jump save &a保存。");
    }

    private void clearStarts(CommandSender sender) {
        JumpFillManager manager = stateOf(sender);
        if (!requireAdmin(sender)) {
            return;
        }
        manager.arena().clearStarts();
        Chat.send(sender, "&a已清空所有高台出发点。");
    }

    private void setLobby(CommandSender sender) {
        JumpFillManager manager = stateOf(sender);
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c只有玩家可以设置等待点。");
            return;
        }
        manager.arena().setLobby(player.getLocation());
        Chat.send(player, "&a已设置等待点。");
    }

    private void save(CommandSender sender) {
        JumpFillManager manager = stateOf(sender);
        if (!requireAdmin(sender)) {
            return;
        }
        if (!manager.arena().waterConfigured()) {
            Chat.send(sender, "&c水域还没设置好：站在两个对角执行 /jump setwater 1 和 2，Y 必须相同。");
            return;
        }
        if (manager.arena().starts().isEmpty()) {
            Chat.send(sender, "&c至少需要一个高台出发点：站在高台上执行 /jump addstart。");
            return;
        }
        manager.arena().save();
        Chat.send(sender, "&a已保存：水域 &f" + manager.arena().width() + "x" + manager.arena().depth()
                + " &7(" + manager.arena().volume() + " 格)&a，出发点 &f"
                + manager.arena().starts().size() + " &a个。");
    }

    private void start(CommandSender sender) {
        // 控制台开局（派对就是这么开的：dispatchCommand(控制台, "jump start")）：
        // 控制台没有房间上下文，两场派对同时开局时"只开最近加入的那一间"会让另一间永远停在等待。
        if (!(sender instanceof Player)) {
            int started = rooms.startAllWaiting();
            if (started == 0) {
                Chat.send(sender, "&e没有正在等待开局的房间。");
            }
            return;
        }
        JumpFillManager manager = stateOf(sender);
        // D21：开始权在主持人手上；主持人**不享受管理员特权**（人数不够不能开）
        boolean isAdminS = !(sender instanceof Player sp) || sp.hasPermission("jumpfill.admin");
        if (!isAdminS) {
            if (!manager.isHost(sender)) {
                Chat.send(sender, "&c只有本局**主持人**或管理员可以开始。");
                return;
            }
            if (manager.waitingCountPublic() < manager.minPlayersPublic()) {
                Chat.send(sender, "&c人数还没达到 &f" + manager.minPlayersPublic()
                        + " 人&c，主持人也不能开始。");
                return;
            }
        }
        if (!manager.startGame(true)) {
            Chat.send(sender, "&c无法开始：检查场地配置和等待玩家。");
        }
    }

    private void stop(CommandSender sender) {
        JumpFillManager manager = stateOf(sender);
        if (!requireAdmin(sender)) {
            return;
        }
        if (!manager.isRunning()) {
            Chat.send(sender, "&7当前没有进行中的对局。");
            return;
        }
        manager.endGame(false);
        Chat.send(sender, "&a已结束本局。");
    }

    private void reload(CommandSender sender) {
        JumpFillManager manager = stateOf(sender);
        if (!requireAdmin(sender)) {
            return;
        }
        if (manager.isRunning() || manager.isEnding()) {
            Chat.send(sender, "&c对局进行中，不能重载配置。");
            return;
        }
        plugin.reloadConfig();
        manager.reload();
        Chat.send(sender, "&a配置已重新载入。");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (isAdminSub(sub) && !sender.hasPermission("jumpfill.admin")) {
                    continue;
                }
                options.add(sub);
            }
            return filter(options, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("setwater")) {
                return filter(List.of("1", "2"), args[1]);
            }
            if (sub.equals("stats")) {
                List<String> names = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    names.add(player.getName());
                }
                return filter(names, args[1]);
            }
        }
        return List.of();
    }

    private boolean isAdminSub(String sub) {
        return switch (sub) {
            case "setwater", "addstart", "clearstarts", "setlobby", "save", "start", "stop", "reload" -> true;
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
