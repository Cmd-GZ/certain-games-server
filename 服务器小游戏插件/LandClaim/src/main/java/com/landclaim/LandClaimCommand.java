package com.landclaim;

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

public final class LandClaimCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "join", "leave", "list", "stats", "help",
            "setcenter", "setradius", "generate", "genspawns", "addspawn",
            "clearspawns", "setlobby", "save", "start", "stop", "reload");

    private final LandClaimPlugin plugin;
    /** D22：按房间分桶的状态；每个入口按发送者/玩家路由到他自己那一份。 */
    private final LandClaimRooms rooms;

    public LandClaimCommand(LandClaimPlugin plugin, LandClaimRooms rooms) {
        this.plugin = plugin;
        this.rooms = rooms;
    }

    /** 这个发送者应该操作的房间状态：玩家 → 他自己那一份；控制台 → 当前房间上下文/最近有人加入的那一份。 */
    private LandClaimManager stateOf(CommandSender sender) {
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
            case "setcenter" -> setCenter(sender);
            case "setradius" -> setRadius(sender, args);
            case "generate" -> generate(sender);
            case "genspawns" -> genSpawns(sender, args);
            case "addspawn" -> addSpawn(sender);
            case "clearspawns" -> clearSpawns(sender);
            case "setlobby" -> setLobby(sender);
            case "save" -> save(sender);
            case "start" -> start(sender);
            case "stop" -> stop(sender);
            case "reload" -> reload(sender);
            default -> Chat.send(sender, "&c未知子指令，输入 &f/land help &c查看帮助。");
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
        Chat.send(player, "&7已离开 battlefield 1 (占地1)。");
    }

    private void list(CommandSender sender) {
        LandClaimManager manager = stateOf(sender);
        Chat.send(sender, "&a===== battlefield 1 (占地1) =====");
        String state = manager.isRunning() ? "&c游戏中" : (manager.isEnding() ? "&c结算中" : "&e等待中");
        Chat.send(sender, "&7状态: " + state + " &7| 玩家: &f" + manager.statesCount()
                + "&7/&f" + manager.maxPlayers() + " &7| 剩余地面: &f" + manager.remaining());
        Chat.send(sender, "&7场地: 半径 &f" + manager.arena().radius()
                + " &7(" + manager.arena().totalBlocks() + " 格) &7| 出生点: &f"
                + manager.arena().spawns().size() + " &7个");
        if (!manager.arena().ready()) {
            Chat.send(sender, "&c场地尚未配置完整：/land setcenter、/land setradius、/land generate、/land genspawns、/land save");
        }
        for (Map.Entry<UUID, Integer> entry : manager.scores().entrySet()) {
            Chat.send(sender, " &7- &f" + manager.nameOf(entry.getKey())
                    + " &7(" + manager.colorNameOf(entry.getKey()) + ") &a" + entry.getValue() + " 格");
        }
    }

    private void stats(CommandSender sender, String[] args) {
        LandClaimManager manager = stateOf(sender);
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
            Chat.send(sender, "&c用法: /land stats <玩家>");
            return;
        }
        Chat.send(sender, "&a" + name + " &7的 battlefield 1 (占地1) 冠军数：&e" + manager.firsts(id));
    }

    private void help(CommandSender sender) {
        Chat.send(sender, "&abattlefield 1 (占地1) 指令:");
        Chat.send(sender, "&f/land join &7- 加入等待队列");
        Chat.send(sender, "&f/land leave &7- 离开游戏");
        Chat.send(sender, "&f/land list &7- 查看状态与占地");
        Chat.send(sender, "&f/land stats [玩家] &7- 查看冠军数");
        if (sender.hasPermission("landclaim.admin")) {
            Chat.send(sender, "&6-- 管理 --");
            Chat.send(sender, "&f/land setcenter &7- 站在地面中心设置圆形场地中心");
            Chat.send(sender, "&f/land setradius <半径> &7- 设置圆形半径（1-128）");
            Chat.send(sender, "&f/land generate &7- 按半径生成草方块场地并清空上方");
            Chat.send(sender, "&f/land genspawns <数量> &7- 在圆周自动生成出生点（1-16）");
            Chat.send(sender, "&f/land addspawn &7- 站在位置添加一个出生点");
            Chat.send(sender, "&f/land clearspawns &7- 清空所有出生点");
            Chat.send(sender, "&f/land setlobby &7- 设置等待点");
            Chat.send(sender, "&f/land save &7- 保存场地配置");
            Chat.send(sender, "&f/land start &7- 立即开始");
            Chat.send(sender, "&f/land stop &7- 强制结束本局");
            Chat.send(sender, "&f/land reload &7- 重新读取配置");
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("landclaim.admin")) {
            Chat.send(sender, "&c你没有权限执行该指令。");
            return false;
        }
        return true;
    }

    private void setCenter(CommandSender sender) {
        LandClaimManager manager = stateOf(sender);
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c只有玩家可以设置中心。");
            return;
        }
        Location ground = player.getLocation().clone().subtract(0, 1, 0).getBlock().getLocation();
        manager.arena().setCenter(ground);
        Chat.send(player, "&a已设置圆形场地中心: &f" + ground.getWorld().getName() + " "
                + ground.getBlockX() + ", " + ground.getBlockY() + ", " + ground.getBlockZ());
        Chat.send(player, "&7接着用 &f/land setradius <半径> &7和 &f/land generate &7生成场地。");
    }

    private void setRadius(CommandSender sender, String[] args) {
        LandClaimManager manager = stateOf(sender);
        if (!requireAdmin(sender)) {
            return;
        }
        if (args.length < 2) {
            Chat.send(sender, "&c用法: /land setradius <半径 1-128>");
            return;
        }
        int radius;
        try {
            radius = Integer.parseInt(args[1]);
        } catch (NumberFormatException exception) {
            Chat.send(sender, "&c半径必须是整数。");
            return;
        }
        if (radius < 1 || radius > 128) {
            Chat.send(sender, "&c半径范围是 1-128。");
            return;
        }
        manager.arena().setRadius(radius);
        Chat.send(sender, "&a已设置半径 &f" + radius + "&a，地面约 &f"
                + manager.arena().totalBlocks() + " &a格。");
    }

    private void generate(CommandSender sender) {
        LandClaimManager manager = stateOf(sender);
        if (!requireAdmin(sender)) {
            return;
        }
        if (!manager.arena().configured()) {
            Chat.send(sender, "&c先设置中心和半径：/land setcenter、/land setradius");
            return;
        }
        manager.arena().resetFloor();
        Chat.send(sender, "&a已生成圆形场地：半径 &f" + manager.arena().radius()
                + "&a，地面 &f" + manager.arena().totalBlocks() + " &a格（草方块）。");
    }

    private void genSpawns(CommandSender sender, String[] args) {
        LandClaimManager manager = stateOf(sender);
        if (!requireAdmin(sender)) {
            return;
        }
        if (!manager.arena().configured()) {
            Chat.send(sender, "&c先设置中心和半径。");
            return;
        }
        int count = 8;
        if (args.length >= 2) {
            try {
                count = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                Chat.send(sender, "&c数量必须是整数。");
                return;
            }
        }
        if (count < 1 || count > 16) {
            Chat.send(sender, "&c出生点数量范围是 1-16。");
            return;
        }
        int made = manager.arena().generateSpawns(count);
        Chat.send(sender, "&a已自动生成 &f" + made + " &a个出生点（圆周内侧）。");
    }

    private void addSpawn(CommandSender sender) {
        LandClaimManager manager = stateOf(sender);
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c只有玩家可以添加出生点。");
            return;
        }
        manager.arena().addSpawn(player.getLocation());
        Chat.send(player, "&a已添加出生点，共 &f" + manager.arena().spawns().size()
                + " &a个。记得 &f/land save &a保存。");
    }

    private void clearSpawns(CommandSender sender) {
        LandClaimManager manager = stateOf(sender);
        if (!requireAdmin(sender)) {
            return;
        }
        manager.arena().clearSpawns();
        Chat.send(sender, "&a已清空所有出生点。");
    }

    private void setLobby(CommandSender sender) {
        LandClaimManager manager = stateOf(sender);
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
        LandClaimManager manager = stateOf(sender);
        if (!requireAdmin(sender)) {
            return;
        }
        if (!manager.arena().configured()) {
            Chat.send(sender, "&c场地还没配置好：/land setcenter、/land setradius。");
            return;
        }
        if (manager.arena().spawns().isEmpty()) {
            Chat.send(sender, "&c至少需要一个出生点：/land genspawns <数量> 或 /land addspawn。");
            return;
        }
        manager.arena().save();
        Chat.send(sender, "&a已保存：半径 &f" + manager.arena().radius()
                + "&a，地面 &f" + manager.arena().totalBlocks()
                + " &a格，出生点 &f" + manager.arena().spawns().size() + " &a个。");
    }

    private void start(CommandSender sender) {
        // 控制台开局（派对就是这么开的：dispatchCommand(控制台, "land start")）：
        // 控制台没有房间上下文，两场派对同时开局时"只开最近加入的那一间"会让另一间永远停在等待。
        if (!(sender instanceof Player)) {
            int started = rooms.startAllWaiting();
            if (started == 0) {
                Chat.send(sender, "&e没有正在等待开局的房间。");
            }
            return;
        }
        LandClaimManager manager = stateOf(sender);
        // D21：开始权在主持人手上；主持人**不享受管理员特权**（人数不够不能开）
        boolean isAdminS = !(sender instanceof Player sp) || sp.hasPermission("landclaim.admin");
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
        LandClaimManager manager = stateOf(sender);
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
        LandClaimManager manager = stateOf(sender);
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
                if (isAdminSub(sub) && !sender.hasPermission("landclaim.admin")) {
                    continue;
                }
                options.add(sub);
            }
            return filter(options, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("stats")) {
                List<String> names = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    names.add(player.getName());
                }
                return filter(names, args[1]);
            }
            if (sub.equals("setradius")) {
                return filter(List.of("8", "12", "16", "20"), args[1]);
            }
            if (sub.equals("genspawns")) {
                return filter(List.of("4", "8", "12", "16"), args[1]);
            }
        }
        return List.of();
    }

    private boolean isAdminSub(String sub) {
        return switch (sub) {
            case "setcenter", "setradius", "generate", "genspawns", "addspawn",
                    "clearspawns", "setlobby", "save", "start", "stop", "reload" -> true;
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
