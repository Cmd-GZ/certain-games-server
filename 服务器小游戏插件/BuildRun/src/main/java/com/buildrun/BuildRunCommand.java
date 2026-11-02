package com.buildrun;

import com.hub.util.Chat;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** /br 命令（你建我跑）。 */
public class BuildRunCommand implements CommandExecutor, TabCompleter {

    private final BuildRunPlugin plugin;
    /** 命令方式编辑场地时的"当前编辑目标"。 */
    private final Map<UUID, String> editing = new LinkedHashMap<>();

    public BuildRunCommand(BuildRunPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isAdmin(Player p) {
        return p.isOp() || p.hasPermission("br.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        BuildRunManager m = plugin.getManager();
        boolean admin = sender instanceof Player p ? isAdmin(p) : true;
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "help" -> help(sender);
            case "join" -> {
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/br join <场名>");
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    Chat.send(sender, "&c该命令需要玩家执行。");
                    return true;
                }
                m.join(p, args[1]);
            }
            case "leave" -> {
                if (!(sender instanceof Player p)) {
                    Chat.send(sender, "&c该命令需要玩家执行。");
                    return true;
                }
                m.leave(p);
            }
            case "list" -> {
                if (m.getArenas().isEmpty()) {
                    Chat.send(sender, "&7暂无场。");
                    return true;
                }
                for (BuildRunArena a : m.getArenas().values()) {
                    Chat.send(sender, "&e" + a.getName() + " &f[" + a.getPhase().name() + "] "
                            + (a.isReady() ? "&7已配置" : "&c未配置")
                            + " &7方向 &f" + a.getDirection().toUpperCase()
                            + " &7间隔 &f" + a.getGap()
                            + " &7" + a.getPlayers().size() + "人");
                }
            }
            case "stats" -> {
                if (!(sender instanceof Player p)) {
                    Chat.send(sender, "&c该命令需要玩家执行。");
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
                    if (!admin) {
                        noPerm(sender);
                        return true;
                    }
                    m.resetFirsts();
                    Chat.send(sender, "&a已清空全部第一名记录。");
                    return true;
                }
                Player target = p;
                if (args.length >= 2) {
                    target = plugin.getServer().getPlayerExact(args[1]);
                    if (target == null) {
                        Chat.send(sender, "&c玩家不在线。");
                        return true;
                    }
                }
                Chat.send(sender, "&6" + target.getName() + " 的 " + BuildRunManager.GAME
                        + " 战绩：&e获得第一名 &f" + m.getFirsts(target.getUniqueId()) + " &6次");
            }
            case "create" -> {
                if (!admin) {
                    noPerm(sender);
                    return true;
                }
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/br create <场名>");
                    return true;
                }
                if (!m.createArena(args[1])) {
                    Chat.send(sender, "&c名字不合法（1~20 个字）或已存在。");
                    return true;
                }
                if (sender instanceof Player p) {
                    editing.put(p.getUniqueId(), args[1]);
                }
                Chat.send(sender, "&a已创建 &e" + args[1] + "&a。下一步：&e/br setup " + args[1]);
            }
            case "remove" -> {
                if (!admin) {
                    noPerm(sender);
                    return true;
                }
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/br remove <场名>");
                    return true;
                }
                if (m.removeArena(args[1])) {
                    Chat.send(sender, "&a已删除场 &e" + args[1] + "&a。");
                } else {
                    Chat.send(sender, "&c删不了：场不存在、有人在里面，或正在游戏。");
                }
            }
            case "edit" -> {
                if (!admin) {
                    noPerm(sender);
                    return true;
                }
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/br edit <场名>");
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    Chat.send(sender, "&c该命令需要玩家执行。");
                    return true;
                }
                if (m.getArena(args[1]) == null) {
                    Chat.send(sender, "&c场不存在。");
                    return true;
                }
                editing.put(p.getUniqueId(), args[1]);
                Chat.send(sender, "&a已进入 &e" + args[1] + "&a 编辑（/br pos1 pos2 spawn lobby dir gap 逐项设）。");
            }
            case "pos1", "pos2", "spawn", "lobby" -> {
                if (!admin) {
                    noPerm(sender);
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    Chat.send(sender, "&c该命令需要玩家执行。");
                    return true;
                }
                BuildRunArena a = editing(p);
                if (a == null) {
                    return true;
                }
                Location loc = p.getLocation();
                switch (sub) {
                    case "pos1" -> a.setPos1(loc);
                    case "pos2" -> a.setPos2(loc);
                    case "spawn" -> a.setSpawn(loc);
                    case "reload" -> {
                        // 框架会在 /hub join 时调用它做**场地懒加载**：
                        // 配置里的 `tpl:<模板键>` 只有存活房间才解析得出来，插件启用时解析必然失败。
                        plugin.reloadConfig();
                        if (plugin.getManager() != null) {
                            plugin.getManager().load();
                        }
                        sender.sendMessage(net.kyori.adventure.text.Component.text("配置已重新载入。"));
                    }
                    default -> a.setLobby(loc);
                }
                Chat.send(sender, "&a已设置 &e" + sub + "&7：" + locText(loc)
                        + (a.isReady() ? "&7（齐了）" : ""));
            }
            case "dir" -> {
                if (!admin) {
                    noPerm(sender);
                    return true;
                }
                BuildRunArena a = editing(sender);
                if (a == null) {
                    return true;
                }
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/br dir <+x|-x|+z|-z>（当前 " + a.getDirection().toUpperCase() + "）");
                    return true;
                }
                if (!a.setDirection(args[1])) {
                    Chat.send(sender, "&c方向只能是 +x / -x / +z / -z。");
                    return true;
                }
                Chat.send(sender, "&a复制方向：&e" + a.getDirection().toUpperCase()
                        + "&7（每块间隔 " + a.step() + " 格）");
            }
            case "gap" -> {
                if (!admin) {
                    noPerm(sender);
                    return true;
                }
                BuildRunArena a = editing(sender);
                if (a == null) {
                    return true;
                }
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/br gap <格数>（当前 " + a.getGap() + "）");
                    return true;
                }
                try {
                    a.setGap(Integer.parseInt(args[1]));
                } catch (NumberFormatException ex) {
                    Chat.send(sender, "&c格数要填非负整数。");
                    return true;
                }
                Chat.send(sender, "&a间隔设好了：每块隔 &e" + a.getGap() + "&a 格（共 &e" + a.step() + "&a 格）。");
            }
            case "save" -> {
                if (!admin) {
                    noPerm(sender);
                    return true;
                }
                BuildRunArena a = editing(sender);
                if (a == null) {
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    m.saveAll();
                    Chat.send(sender, "&a已保存全部场地。");
                    return true;
                }
                m.saveArena(p, a);
            }
            case "setup" -> {
                if (!admin) {
                    noPerm(sender);
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    Chat.send(sender, "&c该命令需要玩家执行。");
                    return true;
                }
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/br setup <场名>（进建场模式，发建场道具）");
                    return true;
                }
                if (m.enterSetup(p, args[1])) {
                    editing.put(p.getUniqueId(), args[1]);
                }
            }
            case "setup-exit" -> {
                if (!admin) {
                    noPerm(sender);
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    Chat.send(sender, "&c该命令需要玩家执行。");
                    return true;
                }
                if (m.isInSetup(p.getUniqueId())) {
                    m.exitSetup(p);
                } else {
                    Chat.send(sender, "&c你不在建场模式中。");
                }
            }
            case "start" -> {
                String name;
                if (args.length >= 2) {
                    name = args[1];
                } else {
                    BuildRunArena a = editing(sender);
                    name = a == null ? null : a.getName();
                }
                if (name == null) {
                    Chat.send(sender, "&c用法：/br start <场名>");
                    return true;
                }
                // D21：开始权在主持人手上；主持人**不享受管理员特权**（人数不够不能开）
                if (!admin) {
                    Player sp = sender instanceof Player p2 ? p2 : null;
                    if (sp == null || !m.isHostOf(sp, name)) {
                        noPerm(sender);
                        return true;
                    }
                    if (m.waitingCountOf(name) < m.minPlayersOf(name)) {
                        Chat.send(sender, "&c人数还没达到 &f" + m.minPlayersOf(name)
                                + " 人&c，主持人也不能开始（管理员可以强制开始）。");
                        return true;
                    }
                }
                Player op = sender instanceof Player p ? p : null;
                if (op == null) {
                    Chat.send(sender, "&c该命令需要玩家执行。");
                    return true;
                }
                m.start(op, name);
            }
            case "stop" -> {
                if (!admin) {
                    noPerm(sender);
                    return true;
                }
                String name;
                if (args.length >= 2) {
                    name = args[1];
                } else if (sender instanceof Player p) {
                    name = m.arenaOf(p.getUniqueId());
                    if (name == null) {
                        BuildRunArena a = editing(p);
                        name = a == null ? null : a.getName();
                    }
                } else {
                    name = null;
                }
                if (name == null) {
                    Chat.send(sender, "&c用法：/br stop <场名>");
                    return true;
                }
                BuildRunArena a = m.getArena(name);
                if (a == null) {
                    Chat.send(sender, "&c场不存在。");
                    return true;
                }
                m.stop(a);
                Chat.send(sender, "&a已结束 &e" + name + "&a 的本局并清理场地。");
            }
            default -> Chat.send(sender, "&c未知子命令，/br help 看用法。");
        }
        return true;
    }

    private BuildRunArena editing(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Chat.send(sender, "&c该命令需要玩家执行。");
            return null;
        }
        String name = editing.get(p.getUniqueId());
        if (name == null) {
            Chat.send(p, "&c请先 /br create <场名> 或 /br edit <场名>。");
            return null;
        }
        BuildRunArena a = plugin.getManager().getArena(name);
        if (a == null) {
            editing.remove(p.getUniqueId());
            Chat.send(p, "&c场已被删除。");
        }
        return a;
    }

    private void help(CommandSender sender) {
        boolean admin = sender instanceof Player p ? isAdmin(p) : true;
        Chat.send(sender, "&6====== 你建我跑 /br ======");
        Chat.send(sender, "&e/br join <场名> &f加入 · &e/br leave &f离开 · &e/br list &f场列表 · &e/br stats [玩家]");
        if (admin) {
            Chat.send(sender, "&6-- OP --");
            Chat.send(sender, "&e/br create <名> &f创建 · &e/br remove <名> &f删除 · &e/br edit <名> &f改");
            Chat.send(sender, "&e/br setup <名> &f建场模式（发建场道具，跟着道具提示做）");
            Chat.send(sender, "&e/br pos1 &f/ &e/br pos2 &f范围 · &e/br spawn &f出生点 · &e/br lobby &f等待点");
            Chat.send(sender, "&e/br dir <+x|-x|+z|-z> &f复制方向 · &e/br gap <格数> &f间隔 · &e/br save &f保存");
            Chat.send(sender, "&e/br start [场名] &f开始 · &e/br stop [场名] &f强停 · &e/br stats reset &f清第一名");
        }
    }

    private void noPerm(CommandSender sender) {
        Chat.send(sender, "&c只有管理员（OP 或 br.admin）可以使用。");
    }

    private static String locText(Location loc) {
        return "&f" + loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("help", "join", "leave", "list", "stats"));
            boolean admin = sender instanceof Player p ? isAdmin(p) : true;
            if (admin) {
                subs.addAll(List.of("create", "remove", "edit", "setup", "setup-exit", "pos1", "pos2",
                        "spawn", "lobby", "dir", "gap", "save", "start", "stop"));
            }
            subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).forEach(out::add);
            return out;
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            if (List.of("join", "edit", "setup", "remove", "start", "stop").contains(sub)) {
                out.addAll(plugin.getManager().getArenas().keySet().stream()
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList()));
            } else if ("dir".equals(sub)) {
                for (String d : BuildRunArena.DIRECTIONS) {
                    if (d.startsWith(args[1].toLowerCase())) {
                        out.add(d);
                    }
                }
            } else if ("stats".equals(sub)) {
                out.add("reset");
            }
        }
        return out;
    }
}
