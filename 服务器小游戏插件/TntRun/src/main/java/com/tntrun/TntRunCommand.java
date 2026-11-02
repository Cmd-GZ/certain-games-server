package com.tntrun;

import com.hub.util.Chat;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** /tr 命令（雪塔大作战）。 */
public class TntRunCommand implements CommandExecutor, TabCompleter {

    private final TntRunPlugin plugin;
    private final Map<UUID, String> editing = new LinkedHashMap<>();

    public TntRunCommand(TntRunPlugin plugin) {
        this.plugin = plugin;
    }

    /** 管理权限：OP 或持有 plugin.yml 里声明的 tr.admin 节点。 */
    private boolean isAdmin(Player p) {
        return p.isOp() || p.hasPermission("tr.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        TntRunManager m = plugin.getManager();
        boolean admin = sender instanceof Player p ? isAdmin(p) : true;
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/tr create <场名>"); return true; }
                if (m.createArena(args[1])) {
                    if (sender instanceof Player p) {
                        editing.put(p.getUniqueId(), args[1]);
                    }
                    Chat.send(sender, "&a已创建 &e" + args[1] + "&a。/tr pos1 pos2 圈范围，/tr spawn 出生点，/tr cutoff 淘汰线高度。");
                } else {
                    Chat.send(sender, "&c场已存在。");
                }
                return true;
            }
            case "pos1", "pos2", "spawn", "lobby", "center" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                TntRunArena a = editing(p);
                if (a == null) return true;
                Location loc = p.getLocation();
                switch (sub) {
                    case "pos1" -> a.setPos1(loc);
                    case "pos2" -> a.setPos2(loc);
                    case "spawn" -> a.setSpawn(loc);
                    case "center" -> a.setCenter(loc);
                    case "reload" -> {
                        // 框架会在 /hub join 时调用它做**场地懒加载**：
                        // 配置里的 `tpl:<模板键>` 只有存活房间才解析得出来，插件启用时解析必然失败。
                        plugin.reloadConfig();
                        if (plugin.getManager() != null) {
                            plugin.getManager().load();
                        }
                        sender.sendMessage(net.kyori.adventure.text.Component.text("配置已重新载入。"));
                    }
                    default -> a.setLobbyPoint(loc);
                }
                Chat.send(sender, "&a已设置 " + sub + (a.isReady() ? "（配置齐全）" : ""));
                if ("center".equals(sub)) {
                    Chat.send(sender, "&7开局按在场人数把它当圆心、半径 5 格画圆，玩家平均站在圆的边缘上。");
                    int bad = a.circleOutOfRange();
                    if (bad > 0) {
                        Chat.send(sender, "&c警告：半径 5 的圆有 &e" + bad + " &c个点在圈地范围外（先 /tr pos1 /tr pos2 圈大一点）。");
                    }
                }
                return true;
            }
            case "cutoff" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                TntRunArena a = editing(p);
                if (a == null) return true;
                a.setCutoffY(p.getLocation().getBlockY());
                Chat.send(sender, "&a淘汰线高度设为 &eY=" + a.getCutoffY() + "&a（玩家低于此高度淘汰）");
                return true;
            }
            case "save" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                TntRunArena a = editing(p);
                if (a == null) return true;
                m.saveWithSnapshot(p, a); // 配置 + 地图快照（arena-<场名>.snap，每局结束按它重置地图）
                return true;
            }
            case "list" -> {
                if (m.getArenas().isEmpty()) {
                    Chat.send(sender, "&7暂无场。");
                    return true;
                }
                for (TntRunArena a : m.getArenas().values()) {
                    Chat.send(sender, "&e" + a.getName() + " &f[" + a.getState().name() + "] "
                            + (a.isReady() ? "&7已配置" : "&c未配置") + " &7" + a.getPlayers().size() + "人");
                }
                return true;
            }
            case "join" -> {
                if (args.length < 2) { Chat.send(sender, "&c用法：/tr join <场名>"); return true; }
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
                Player player0 = sender instanceof Player p0 ? p0 : null;
                // D21：开始权在主持人手上 —— 但主持人**不享受管理员特权**：
                //   管理员可以强制开（人不够也开），主持人必须等人数达标。
                boolean hostOnly = false;
                if (!admin) {
                    String tryArena = args.length >= 2 ? args[1]
                            : (player0 == null ? null : editing.get(player0.getUniqueId()));
                    if (player0 == null || tryArena == null || !m.isHostOf(player0, tryArena)) {
                        noPerm(sender);
                        return true;
                    }
                    if (m.waitingCountOf(tryArena) < m.minPlayersOf(tryArena)) {
                        Chat.send(sender, "&c人数还没达到 &f" + m.minPlayersOf(tryArena)
                                + " 人&c，主持人也不能开始（管理员可以强制开始）。");
                        return true;
                    }
                    hostOnly = true;
                }
                Player player = player0;
                String arenaName;
                if (args.length >= 2) {
                    arenaName = args[1];
                } else if (player != null) {
                    arenaName = editing.get(player.getUniqueId());
                    if (arenaName == null) {
                        Chat.send(sender, "&c请先 /tr edit <场名>。");
                        return true;
                    }
                } else {
                    Chat.send(sender, "&c控制台启动需要指定场地：/tr start <场名>");
                    return true;
                }
                m.start(player, arenaName);
                return true;
            }
            case "rename" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 3) { Chat.send(sender, "&c用法：/tr rename <旧场名> <新场名>"); return true; }
                String oldName = args[1];
                String newName = args[2];
                if (oldName.equals(newName)) { Chat.send(sender, "&c新旧场名相同。"); return true; }
                if (!m.renameArena(oldName, newName)) {
                    Chat.send(sender, "&c改名失败：场不存在、正在对局中，或新场名需 1~20 个中英文/数字/下划线/短横线且不与现有场重名。");
                    return true;
                }
                // 命令层维护的编辑中引用同步
                for (Map.Entry<UUID, String> e : editing.entrySet()) {
                    if (e.getValue().equals(oldName)) {
                        e.setValue(newName);
                    }
                }
                Chat.send(sender, "&a已将场 &e" + oldName + "&a 改名为 &e" + newName + "&a，菜单与告示牌已同步。");
                return true;
            }
            case "edit" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/tr edit <场名>"); return true; }
                if (!(sender instanceof Player p)) return true;
                if (m.getArena(args[1]) == null) {
                    Chat.send(sender, "&c场不存在。");
                    return true;
                }
                editing.put(p.getUniqueId(), args[1]);
                Chat.send(sender, "&a已进入 &e" + args[1] + "&a 编辑。");
                return true;
            }
            case "remove" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/tr remove <场名>"); return true; }
                if (m.removeArena(args[1])) {
                    Chat.send(sender, "&a已删除场 &e" + args[1] + "&a（含加入告示牌）。");
                } else {
                    Chat.send(sender, "&c删除失败：场不存在，或有人/正在游戏。");
                }
                return true;
            }
            case "setup" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                if (args.length < 2) { Chat.send(sender, "&c用法：/tr setup <场名>（进入配置模式，发配置道具）"); return true; }
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
            case "joinsign" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/tr joinsign <场名>（随后右键点击一个告示牌）"); return true; }
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
                    m.resetFirsts();
                    Chat.send(sender, "&a已清空全部第一名记录。");
                    return true;
                }
                org.bukkit.entity.Player target = p;
                if (args.length >= 2) {
                    target = plugin.getServer().getPlayerExact(args[1]);
                    if (target == null) {
                        Chat.send(sender, "&c玩家不在线。");
                        return true;
                    }
                }
                Chat.send(sender, "&6" + target.getName() + " 的 " + TntRunManager.GAME + " 战绩：&e获得第一名 &f" + m.getFirsts(target.getUniqueId()) + " &6次");
                return true;
            }
            case "help" -> help(sender);
            default -> Chat.send(sender, "&c未知子命令。");
        }
        return true;
    }

    private TntRunArena editing(Player p) {
        String name = editing.get(p.getUniqueId());
        if (name == null) {
            Chat.send(p, "&c请先 /tr create 或 /tr edit <场名>。");
            return null;
        }
        TntRunArena a = plugin.getManager().getArena(name);
        if (a == null) {
            editing.remove(p.getUniqueId());
            Chat.send(p, "&c场已被删除。");
            return null;
        }
        return a;
    }

    private void help(CommandSender sender) {
        boolean admin = sender instanceof Player p ? isAdmin(p) : true;
        Chat.send(sender, "&6====== 雪塔大作战 /tr ======");
        Chat.send(sender, "&e/tr join <场名>&f 加入  &e/tr leave&f 离开  &e/tr list&f 场列表  &e/tr stats [玩家]&f 第一名次数");
        if (admin) {
            Chat.send(sender, "&6-- OP --");
            Chat.send(sender, "&e/tr create <名>&f 创建  &e/tr remove <名>&f 删除");
            Chat.send(sender, "&e/tr setup <名>&f 配置模式（道具：木棍圈范围、金锄头出生点、纸等待点、指南针最上层中心、绿宝石保存、屏障退出）");
            Chat.send(sender, "&e/tr edit <名>&f 命令编辑  &e/tr pos1 / pos2&f 圈范围  &e/tr spawn&f 出生点  &e/tr lobby&f 等待点  &e/tr cutoff&f 淘汰线");
            Chat.send(sender, "&e/tr center&f 最上层中心（开局站位的圆心，半径 5 格）");
            Chat.send(sender, "&e/tr joinsign <名>&f 放置加入告示牌  &e/tr save&f 保存配置+地图快照（每局结束按快照重置）  &e/tr start&f 开始");
            Chat.send(sender, "&e/tr rename <旧名> <新名>&f 场地改名（同步菜单与告示牌）");
            Chat.send(sender, "&e/tr stats reset&f 清空全部第一名记录");
        }
    }

    private void noPerm(CommandSender sender) {
        Chat.send(sender, "&c只有管理员（OP 或 tr.admin）可以使用。");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("join", "leave", "list", "stats"));
            boolean admin = sender instanceof Player p ? isAdmin(p) : true;
            if (admin) {
                subs.addAll(List.of("create", "remove", "edit", "rename", "setup", "setup-exit", "pos1", "pos2", "spawn", "lobby", "center", "cutoff", "save", "start", "joinsign"));
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
