package com.duel.arena;

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

/** /duel 命令：场管理（OP）+ 加入对战（玩家）。 */
public class DuelCommand implements CommandExecutor, TabCompleter {

    private final DuelPlugin plugin;
    private final Map<UUID, String> editingArena = new LinkedHashMap<>(); // OP 当前编辑的场

    public DuelCommand(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        DuelManager m = plugin.getManager();
        boolean admin = isAdmin(sender);

        switch (sub) {
            case "create" -> {
                if (!requireAdmin(sender, admin)) return true;
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/duel create <场名>");
                    return true;
                }
                if (m.createArena(args[1])) {
                    if (sender instanceof Player p) {
                        editingArena.put(p.getUniqueId(), args[1]);
                    }
                    m.saveAll(); // 立即落盘，防止重启丢失
                    Chat.send(sender, "&a已创建决斗场 &e" + args[1] + "&a。接着用 /duel pos1、pos2 圈范围，spawn1、spawn2 定出生点。");
                } else {
                    Chat.send(sender, "&c场名 &e" + args[1] + "&c 已存在。");
                }
                return true;
            }
            case "pos1", "pos2", "spawn1", "spawn2", "lobby" -> {
                if (!requireAdmin(sender, admin)) return true;
                Player p = asPlayer(sender);
                if (p == null) return true;
                DuelArena a = editing(p);
                if (a == null) return true;
                Location loc = p.getLocation();
                switch (sub) {
                    case "pos1" -> a.setPos1(loc);
                    case "pos2" -> a.setPos2(loc);
                    case "spawn1" -> a.setSpawn1(loc);
                    case "lobby" -> a.setManualLobby(loc);
                    default -> a.setSpawn2(loc);
                }
                Chat.send(sender, "&a已设置 &e" + a.getName() + "&a 的 &e" + sub + "&a："
                        + loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ()
                        + (a.isReady() ? "（配置齐全）" : ""));
                m.saveAll(); // 立即落盘，防止重启丢失
                return true;
            }
            case "setup" -> {
                if (!requireAdmin(sender, admin)) return true;
                Player p = asPlayer(sender);
                if (p == null) return true;
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/duel setup <场名>（进入配置模式，发配置道具）");
                    return true;
                }
                if (plugin.getManager().enterSetup(p, args[1])) {
                    editingArena.put(p.getUniqueId(), args[1]);
                }
                return true;
            }
            case "setup-exit" -> {
                if (!requireAdmin(sender, admin)) return true;
                Player p = asPlayer(sender);
                if (p == null) return true;
                if (plugin.getManager().isInSetup(p.getUniqueId())) {
                    plugin.getManager().exitSetup(p);
                } else {
                    Chat.send(sender, "&c你不在配置模式中。");
                }
                return true;
            }
            case "rename" -> {
                if (!requireAdmin(sender, admin)) return true;
                if (args.length < 3) {
                    Chat.send(sender, "&c用法：/duel rename <旧场名> <新场名>");
                    return true;
                }
                String oldName = args[1];
                String newName = args[2];
                if (oldName.equals(newName)) {
                    Chat.send(sender, "&c新旧场名相同。");
                    return true;
                }
                if (!m.renameArena(oldName, newName)) {
                    Chat.send(sender, "&c改名失败：场不存在、正在对战中，或新场名需 1~20 个中英文/数字/下划线/短横线且不与现有场重名。");
                    return true;
                }
                // 命令层维护的编辑中引用同步
                for (Map.Entry<UUID, String> e : editingArena.entrySet()) {
                    if (e.getValue().equals(oldName)) {
                        e.setValue(newName);
                    }
                }
                Chat.send(sender, "&a已将决斗场 &e" + oldName + "&a 改名为 &e" + newName + "&a，菜单与告示牌已同步。");
                return true;
            }
            case "edit" -> {
                if (!requireAdmin(sender, admin)) return true;
                Player p = asPlayer(sender);
                if (p == null) return true;
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/duel edit <场名>（进入该场的编辑状态，随后用命令或 /duel setup 配置）");
                    return true;
                }
                DuelArena e = m.getArena(args[1]);
                if (e == null) {
                    Chat.send(sender, "&c决斗场 &e" + args[1] + "&c 不存在。");
                    return true;
                }
                editingArena.put(p.getUniqueId(), args[1]);
                Chat.send(sender, "&a已进入 &e" + args[1] + "&a 的编辑状态：/duel pos1、pos2 圈范围，spawn1、spawn2 定出生点，/duel kit 配物品，/duel save 保存；也可 /duel setup " + args[1] + " 用道具快速配置。");
                return true;
            }
            case "kit" -> {
                if (!requireAdmin(sender, admin)) return true;
                Player p = asPlayer(sender);
                if (p == null) return true;
                DuelArena a = editing(p);
                if (a == null) return true;
                m.openKitEditor(p, a);
                return true;
            }
            case "save" -> {
                if (!requireAdmin(sender, admin)) return true;
                if (!(sender instanceof Player p)) return true;
                DuelArena a = editing(p);
                if (a == null) return true;
                m.saveAll();
                Chat.send(sender, "&a已保存场 &e" + a.getName() + "&a。");
                return true;
            }
            case "remove" -> {
                if (!requireAdmin(sender, admin)) return true;
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/duel remove <场名>");
                    return true;
                }
                if (m.removeArena(args[1])) {
                    m.saveAll(); // 删除后立即落盘（场与告示牌）
                    Chat.send(sender, "&a已删除决斗场 &e" + args[1] + "&a（含告示牌）。");
                } else {
                    Chat.send(sender, "&c删除失败：场不存在或正在对战中。");
                }
                return true;
            }
            case "list" -> {
                if (m.getArenas().isEmpty()) {
                    Chat.send(sender, "&7暂无决斗场。OP 可用 /duel create <场名> 创建。");
                    return true;
                }
                Chat.send(sender, "&6决斗场列表：");
                for (DuelArena a : m.getArenas().values()) {
                    Chat.send(sender, "&e" + a.getName() + "&f [&a" + a.getState().name() + "&f] "
                            + (a.isReady() ? "&7已配置" : "&c未配置") + " &7(等待 " + a.getQueue().size() + "/2)");
                }
                return true;
            }
            case "info" -> {
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/duel info <场名>");
                    return true;
                }
                DuelArena a = m.getArena(args[1]);
                if (a == null) {
                    Chat.send(sender, "&c场不存在。");
                    return true;
                }
                Chat.send(sender, "&6场：&e" + a.getName());
                Chat.send(sender, "&7范围：&f" + (a.getPos1() == null ? "?" : shortLoc(a.getPos1()))
                        + " &f->&f " + (a.getPos2() == null ? "?" : shortLoc(a.getPos2())));
                Chat.send(sender, "&7出生点1：&f" + (a.getSpawn1() == null ? "?" : shortLoc(a.getSpawn1())));
                Chat.send(sender, "&7出生点2：&f" + (a.getSpawn2() == null ? "?" : shortLoc(a.getSpawn2())));
                Chat.send(sender, "&7物品配置：&f" + (a.getKit().isEmpty() ? "空（/duel kit 配置）" : "已配置"));
                Chat.send(sender, "&7状态：&f" + a.getState().name() + " 等待 " + a.getQueue().size() + "/2");
                return true;
            }
            case "sign" -> {
                if (!requireAdmin(sender, admin)) return true;
                Player p = asPlayer(sender);
                if (p == null) return true;
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/duel sign <场名>（站在场边实心方块上）");
                    return true;
                }
                DuelArena a = m.getArena(args[1]);
                if (a == null) {
                    Chat.send(sender, "&c场不存在。");
                    return true;
                }
                m.placeSign(p, a);
                return true;
            }
            case "joinsign" -> {
                if (!requireAdmin(sender, admin)) return true;
                Player p = asPlayer(sender);
                if (p == null) return true;
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/duel joinsign <场名>（站在场边实心方块上，右键点击即可加入）");
                    return true;
                }
                DuelArena a = m.getArena(args[1]);
                if (a == null) {
                    Chat.send(sender, "&c场不存在。");
                    return true;
                }
                m.placeJoinSign(p, a);
                return true;
            }
            case "topsign" -> {
                if (!requireAdmin(sender, admin)) return true;
                Player p = asPlayer(sender);
                if (p == null) return true;
                m.placeTopSign(p);
                return true;
            }
            case "start" -> {
                // D21：主持人或管理员开局（/hub start 也走这里）
                if (!(sender instanceof Player sp)) {
                    Chat.send(sender, "&c该命令需要玩家执行。");
                    return true;
                }
                m.handleStartPaper(sp);
            }
            case "join" -> {
                if (args.length < 2) {
                    Chat.send(sender, "&c用法：/duel join <场名>（可 /duel list 查看）");
                    return true;
                }
                Player p = asPlayer(sender);
                if (p == null) return true;
                m.join(p, args[1]);
                return true;
            }
            case "leave" -> {
                Player p = asPlayer(sender);
                if (p == null) return true;
                if (!m.leaveArena(p.getUniqueId())) {
                    Chat.send(sender, "&c你不在任何决斗场中。");
                }
                return true;
            }
            case "stats" -> {
                // /duel stats reset —— 管理员清空全部战绩
                if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
                    if (!isAdmin(sender)) {
                        Chat.send(sender, "&c只有管理员（OP）可以清空记录。");
                        return true;
                    }
                    m.resetWins();
                    for (String n : new ArrayList<>(m.getArenas().keySet())) {
                        m.refreshArenaSigns(n);
                    }
                    m.refreshJoinSigns();
                    m.refreshTopSigns();
                    Chat.send(sender, "&a已清空全部对战记录（含总胜场与分场胜场，告示牌已刷新）。");
                    return true;
                }
                Player target = null;
                if (args.length >= 2) {
                    target = plugin.getServer().getPlayerExact(args[1]);
                    if (target == null) {
                        Chat.send(sender, "&c玩家不在线。");
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    Chat.send(sender, "&c请指定玩家。");
                    return true;
                }
                UUID id = target.getUniqueId();
                Chat.send(sender, "&6" + target.getName() + " 的对战战绩：");
                Chat.send(sender, "&7总胜场：&e" + m.getTotalWins(id));
                List<String> per = new ArrayList<>();
                for (DuelArena a : m.getArenas().values()) {
                    int n = m.getWins(a.getName(), id);
                    if (n > 0) {
                        per.add("&e" + a.getName() + "&f " + n);
                    }
                }
                Chat.send(sender, "&7分场胜场：&f" + (per.isEmpty() ? "无" : String.join("  ", per)));
                return true;
            }
            case "help", "?" -> help(sender);
            case "reload" -> {
                plugin.reloadConfig();
                if (plugin.getManager() != null) {
                    plugin.getManager().load();
                }
                sender.sendMessage("配置已重新载入。");
            }
            default -> Chat.send(sender, "&c未知子命令。输入 /duel help 查看帮助。");
        }
        return true;
    }

    private DuelArena editing(Player p) {
        String name = editingArena.get(p.getUniqueId());
        if (name == null) {
            Chat.send(p, "&c请先 /duel create <场名> 创建场，或用 /duel edit <场名> 进入编辑。");
            return null;
        }
        DuelArena a = plugin.getManager().getArena(name);
        if (a == null) {
            editingArena.remove(p.getUniqueId());
            Chat.send(p, "&c当前编辑的场已被删除。");
            return null;
        }
        return a;
    }

    private void help(CommandSender sender) {
        boolean admin = isAdmin(sender);
        Chat.send(sender, "&6====== 单挑 PvP (/duel) ======");
        Chat.send(sender, "&e/duel join <场名>&f 加入决斗场等待对手");
        Chat.send(sender, "&e/duel leave&f 离开决斗场");
        Chat.send(sender, "&e/duel list&f 场列表  &e/duel stats [玩家]&f 战绩");
        if (admin) {
            Chat.send(sender, "&6-- OP 管理 --");
            Chat.send(sender, "&e/duel create <场名>&f 创建场");
            Chat.send(sender, "&e/duel edit <场名>&f 进入该场编辑状态（命令配置）");
            Chat.send(sender, "&e/duel setup <场名>&f 进入配置模式（发道具：木棍圈范围、金锄头定出生点、绿宝石配物品、纸保存、屏障退出）");
            Chat.send(sender, "&e/duel pos1 &f/&e pos2&f 圈范围（站在两个对角）");
            Chat.send(sender, "&e/duel spawn1 &f/&e spawn2&f 设置两个出生点  &e/duel lobby&f 等待点");
            Chat.send(sender, "&e/duel kit&f 打开物品配置窗口（两位玩家共用）");
            Chat.send(sender, "&e/duel save&f 保存当前编辑的场");
            Chat.send(sender, "&e/duel remove <场名>&f 删除场");
            Chat.send(sender, "&e/duel rename <旧名> <新名>&f 场地改名（同步菜单与告示牌）");
            Chat.send(sender, "&e/duel info <场名>&f 查看场信息");
            Chat.send(sender, "&e/duel sign <场名>&f 放置该场战绩告示牌（场胜场榜）");
            Chat.send(sender, "&e/duel joinsign <场名>&f 放置加入告示牌（右键点击加入该场）");
            Chat.send(sender, "&e/duel topsign&f 放置总胜场排行榜告示牌");
            Chat.send(sender, "&e/duel stats reset&f 清空全部对战记录");
        }
    }

    /** 管理权限：OP 或持有 plugin.yml 里声明的 duel.admin 节点。 */
    private boolean isAdmin(CommandSender sender) {
        return sender instanceof Player p ? (p.isOp() || p.hasPermission("duel.admin")) : true;
    }

    private boolean requireAdmin(CommandSender sender, boolean admin) {
        if (!admin) {
            Chat.send(sender, "&c只有 OP 或拥有 duel.admin 权限的玩家可以使用该命令。");
            return false;
        }
        return true;
    }

    private Player asPlayer(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Chat.send(sender, "&c该命令需要玩家执行。");
            return null;
        }
        return p;
    }

    private String shortLoc(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = isAdmin(sender);
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("join", "leave", "list", "stats"));
            if (admin) {
                subs.addAll(List.of("create", "edit", "rename", "setup", "setup-exit", "pos1", "pos2", "spawn1", "spawn2", "lobby", "kit", "save", "remove", "info", "sign", "joinsign", "topsign"));
            }
            String cur = args[0].toLowerCase();
            for (String s : subs) {
                if (s.startsWith(cur)) {
                    out.add(s);
                }
            }
        } else if (args.length == 2) {
            String cur = args[1].toLowerCase();
            if (args[0].equalsIgnoreCase("stats")) {
                if ("reset".startsWith(cur) && admin) {
                    out.add("reset");
                }
            } else if (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("info")
                    || args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("sign")
                    || args[0].equalsIgnoreCase("joinsign") || args[0].equalsIgnoreCase("edit")
                    || args[0].equalsIgnoreCase("rename") || args[0].equalsIgnoreCase("setup")) {
                out.addAll(plugin.getManager().getArenas().keySet().stream()
                        .filter(n -> n.toLowerCase().startsWith(cur)).collect(Collectors.toList()));
            }
        }
        return out;
    }
}
