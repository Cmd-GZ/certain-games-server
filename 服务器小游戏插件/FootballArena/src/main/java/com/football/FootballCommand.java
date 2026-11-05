package com.football;

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

/** /fb 命令。 */
public class FootballCommand implements CommandExecutor, TabCompleter {

    private final FootballPlugin plugin;
    private final Map<UUID, String> editing = new LinkedHashMap<>(); // OP 当前编辑的场

    public FootballCommand(FootballPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isAdmin(Player p) {
        return p.isOp() || p.hasPermission("fb.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        FootballManager m = plugin.getManager();
        boolean admin = sender instanceof Player p ? isAdmin(p) : true;
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/fb create <场名>"); return true; }
                if (m.createArena(args[1])) {
                    if (sender instanceof Player p) {
                        editing.put(p.getUniqueId(), args[1]);
                    }
                    m.saveAll();
                    Chat.send(sender, "&a已创建 &e" + args[1] + "&a。建议 /fb setup <场名> 用道具圈地。");
                } else {
                    Chat.send(sender, "&c场已存在。");
                }
                return true;
            }
            case "setup" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                if (args.length < 2) { Chat.send(sender, "&c用法：/fb setup <场名>"); return true; }
                if (m.enterSetup(p, args[1])) {
                    editing.put(p.getUniqueId(), args[1]);
                }
                return true;
            }
            case "setup-exit" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                if (m.isInSetup(p.getUniqueId())) {
                    m.exitSetup(p);
                } else {
                    Chat.send(sender, "&c你不在配置模式中。");
                }
                return true;
            }
            case "pos1", "pos2", "center", "lobby", "spawn-red", "spawn-blue",
                 "goal-red-1", "goal-red-2", "goal-blue-1", "goal-blue-2" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                FootballArena a = editing(p);
                if (a == null) return true;
                Location loc = p.getLocation();
                switch (sub) {
                    case "pos1" -> a.setPos1(loc);
                    case "pos2" -> a.setPos2(loc);
                    case "center" -> a.setCenter(loc);
                    case "lobby" -> a.setLobbyPoint(loc);
                    case "spawn-red" -> a.setSpawnRed(loc);
                    case "spawn-blue" -> a.setSpawnBlue(loc);
                    case "goal-red-1" -> a.setGoalRedPos1(loc);
                    case "goal-red-2" -> a.setGoalRedPos2(loc);
                    case "goal-blue-1" -> a.setGoalBluePos1(loc);
                    default -> a.setGoalBluePos2(loc);
                }
                Chat.send(sender, "&a已设置 &e" + a.getName() + "&a 的 " + sub
                        + (a.isReady() ? "（配置齐全）" : "，还缺：" + String.join("、", a.missingPoints())));
                m.saveAll();
                return true;
            }
            case "save" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                FootballArena a = editing(p);
                if (a == null) return true;
                m.saveAll();
                Chat.send(sender, "&a已保存 &e" + a.getName());
                return true;
            }
            case "list" -> {
                if (m.getArenas().isEmpty()) {
                    Chat.send(sender, "&7暂无场。");
                    return true;
                }
                for (FootballArena a : m.getArenas().values()) {
                    Chat.send(sender, "&e" + a.getName() + " &f[" + a.getState().name() + "] "
                            + (a.isReady() ? "&7已配置" : "&c缺 " + String.join("、", a.missingPoints()))
                            + " &7" + a.getPlayers().size() + "人"
                            + (a.getState() == FootballArena.State.RUNNING
                            ? " &7比分 &c" + a.getScoreRed() + "&f:&9" + a.getScoreBlue() : ""));
                }
                return true;
            }
            case "join" -> {
                if (args.length < 2) { Chat.send(sender, "&c用法：/fb join <场名>"); return true; }
                if (!(sender instanceof Player p)) return true;
                m.join(p, args[1]);
                return true;
            }
            case "leave" -> {
                if (!(sender instanceof Player p)) return true;
                m.leave(p);
                return true;
            }
            case "start" -> {
                if (!(sender instanceof Player p)) {
                    // 控制台开局（派对/脚本）：/fb start [场名]，manager 自己找房间上下文/有人那一间
                    m.start(null, args.length >= 2 ? args[1] : null);
                    return true;
                }
                // D21：开始权在主持人手上；主持人**不享受管理员特权**（人数不够不能开）
                if (!admin) {
                    String tryArena = editing.getOrDefault(p.getUniqueId(), null);
                    if (tryArena == null || !m.isHostOf(p, tryArena)) { noPerm(sender); return true; }
                    if (m.waitingCountOf(tryArena) < m.minPlayersOf(tryArena)) {
                        Chat.send(sender, "&c人数还没达到 &f" + m.minPlayersOf(tryArena)
                                + " 人&c，主持人也不能开始（管理员可以强制开始）。");
                        return true;
                    }
                }
                String arenaName = editing.get(p.getUniqueId());
                if (arenaName == null) {
                    Chat.send(sender, "&c请先 /fb edit <场名>。");
                    return true;
                }
                m.start(p, arenaName);
                return true;
            }
            case "edit" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/fb edit <场名>"); return true; }
                if (!(sender instanceof Player p)) return true;
                if (m.getArena(args[1]) == null) {
                    Chat.send(sender, "&c场不存在。");
                    return true;
                }
                editing.put(p.getUniqueId(), args[1]);
                Chat.send(sender, "&a已进入 &e" + args[1] + "&a 编辑。");
                return true;
            }
            case "rename" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 3) { Chat.send(sender, "&c用法：/fb rename <旧场名> <新场名>"); return true; }
                String oldName = args[1];
                String newName = args[2];
                if (oldName.equals(newName)) { Chat.send(sender, "&c新旧场名相同。"); return true; }
                if (m.renameArena(oldName, newName)) {
                    for (Map.Entry<UUID, String> e : editing.entrySet()) {
                        if (e.getValue().equals(oldName)) {
                            e.setValue(newName);
                        }
                    }
                    Chat.send(sender, "&a已将场 &e" + oldName + "&a 改名为 &e" + newName + "&a。");
                } else {
                    Chat.send(sender, "&c改名失败：场不存在、正在对局中，或新场名需 1~20 个中英文/数字/下划线/短横线且不重名。");
                }
                return true;
            }
            case "remove" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/fb remove <场名>"); return true; }
                if (m.removeArena(args[1])) {
                    m.saveAll();
                    Chat.send(sender, "&a已删除场 &e" + args[1] + "&a（含加入告示牌）。");
                } else {
                    Chat.send(sender, "&c删除失败：场不存在，或有人/正在游戏。");
                }
                return true;
            }
            case "joinsign" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/fb joinsign <场名>（随后右键点击一个告示牌）"); return true; }
                if (!(sender instanceof Player p)) return true;
                m.placeJoinSign(p, args[1]);
                return true;
            }
            case "stats" -> {
                if (!(sender instanceof Player p)) {
                    Chat.send(sender, "&c该命令需要玩家执行。");
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
                    if (!admin) { noPerm(sender); return true; }
                    m.resetStats();
                    Chat.send(sender, "&a已清空全部绿茵足球战绩。");
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
                Chat.send(sender, "&6" + target.getName() + " 的绿茵足球战绩：&e获胜 &f"
                        + m.getWins(target.getUniqueId()) + " &6场");
                return true;
            }
            case "help" -> help(sender);
            case "reload" -> {
                plugin.reloadConfig();
                if (plugin.getManager() != null) {
                    plugin.getManager().load();
                }
                sender.sendMessage("配置已重新载入。");
            }
            default -> Chat.send(sender, "&c未知子命令，/fb help 查看帮助。");
        }
        return true;
    }

    private FootballArena editing(Player p) {
        String name = editing.get(p.getUniqueId());
        if (name == null) {
            Chat.send(p, "&c请先 /fb create 或 /fb edit <场名>。");
            return null;
        }
        FootballArena a = plugin.getManager().getArena(name);
        if (a == null) {
            editing.remove(p.getUniqueId());
            Chat.send(p, "&c场已被删除。");
            return null;
        }
        return a;
    }

    private void help(CommandSender sender) {
        boolean admin = sender instanceof Player p ? isAdmin(p) : true;
        Chat.send(sender, "&6====== 绿茵足球 /fb ======");
        Chat.send(sender, "&e/fb join <场名>&f 加入  &e/fb leave&f 离开  &e/fb list&f 场列表  &e/fb stats [玩家]&f 胜场");
        if (admin) {
            Chat.send(sender, "&6-- OP --");
            Chat.send(sender, "&e/fb create <名>&f 创建  &e/fb remove <名>&f 删除  &e/fb rename <旧> <新>&f 改名");
            Chat.send(sender, "&e/fb setup <名>&f 配置模式（木棍范围、金锄开球点、红/蓝羊毛出生点、红砖/石砖球门、绿宝石保存、屏障退出）");
            Chat.send(sender, "&e/fb pos1 / pos2 / center / lobby / spawn-red / spawn-blue");
            Chat.send(sender, "&e/fb goal-red-1 / goal-red-2 / goal-blue-1 / goal-blue-2&f 球门范围（站位置上设）");
            Chat.send(sender, "&e/fb joinsign <名>&f 加入告示牌  &e/fb save&f 保存  &e/fb start&f 开始  &e/fb stats reset&f 清空战绩");
        }
    }

    private void noPerm(CommandSender sender) {
        Chat.send(sender, "&c只有管理员（OP 或 fb.admin）可以使用。");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("join", "leave", "list", "stats"));
            boolean admin = sender instanceof Player p ? isAdmin(p) : true;
            if (admin) {
                subs.addAll(List.of("create", "remove", "edit", "rename", "setup", "setup-exit",
                        "pos1", "pos2", "center", "lobby", "spawn-red", "spawn-blue",
                        "goal-red-1", "goal-red-2", "goal-blue-1", "goal-blue-2",
                        "save", "start", "joinsign"));
            }
            subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).forEach(out::add);
        } else if (args.length == 2) {
            if (List.of("join", "edit", "rename", "setup", "remove", "joinsign").contains(args[0].toLowerCase())) {
                out.addAll(plugin.getManager().getArenas().keySet().stream()
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList()));
            }
        }
        return out;
    }
}
