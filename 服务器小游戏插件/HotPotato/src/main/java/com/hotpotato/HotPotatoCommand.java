package com.hotpotato;

import com.hub.util.Chat;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** /hp 命令。 */
public class HotPotatoCommand implements CommandExecutor, TabCompleter {

    private final HotPotatoPlugin plugin;
    private final Map<UUID, String> editing = new LinkedHashMap<>(); // OP 当前编辑的场

    public HotPotatoCommand(HotPotatoPlugin plugin) {
        this.plugin = plugin;
    }

    /** 管理权限：OP 或持有 plugin.yml 里声明的 hp.admin 节点。 */
    private boolean isAdmin(Player p) {
        return p.isOp() || p.hasPermission("hp.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        HotPotatoManager m = plugin.getManager();
        boolean admin = sender instanceof Player p ? isAdmin(p) : true;
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/hp create <场名>"); return true; }
                if (m.createArena(args[1])) {
                    if (sender instanceof Player p) {
                        editing.put(p.getUniqueId(), args[1]);
                    }
                    m.saveAll(); // 立即落盘，防止重启丢失
                    Chat.send(sender, "&a已创建 &e" + args[1] + "&a。/hp pos1、pos2 圈范围，/hp center 定中央点。");
                } else {
                    Chat.send(sender, "&c场已存在。");
                }
                return true;
            }
            case "pos1", "pos2", "center", "lobby" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                HotPotatoArena a = editing(p);
                if (a == null) return true;
                Location loc = p.getLocation();
                switch (sub) {
                    case "pos1" -> a.setPos1(loc);
                    case "pos2" -> a.setPos2(loc);
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
                Chat.send(sender, "&a已设置 &e" + a.getName() + "&a 的 " + sub + (a.isReady() ? "（配置齐全）" : ""));
                m.saveAll(); // 立即落盘，防止重启丢失
                return true;
            }
            case "save" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                HotPotatoArena a = editing(p);
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
                for (HotPotatoArena a : m.getArenas().values()) {
                    Chat.send(sender, "&e" + a.getName() + " &f[" + a.getState().name() + "] "
                            + (a.isReady() ? "&7已配置" : "&c未配置") + " &7" + a.getPlayers().size() + "人");
                }
                return true;
            }
            case "join" -> {
                if (args.length < 2) { Chat.send(sender, "&c用法：/hp join <场名>"); return true; }
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
                // D21：开始权在主持人手上；主持人**不享受管理员特权**（人数不够不能开）
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
                }
                Player player = player0;
                String arenaName;
                if (args.length >= 2) {
                    arenaName = args[1];
                } else if (player != null) {
                    arenaName = editing.getOrDefault(player.getUniqueId(), null);
                    if (arenaName == null) {
                        Chat.send(sender, "&c请先 /hp edit <场名>。");
                        return true;
                    }
                } else {
                    Chat.send(sender, "&c控制台启动需要指定场地：/hp start <场名>");
                    return true;
                }
                m.start(player, arenaName);
                return true;
            }
            case "rename" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 3) { Chat.send(sender, "&c用法：/hp rename <旧场名> <新场名>"); return true; }
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
                if (args.length < 2) { Chat.send(sender, "&c用法：/hp edit <场名>"); return true; }
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
                if (args.length < 2) { Chat.send(sender, "&c用法：/hp remove <场名>"); return true; }
                if (m.removeArena(args[1])) {
                    m.saveAll(); // 删除后立即落盘（场与加入告示牌）
                    Chat.send(sender, "&a已删除场 &e" + args[1] + "&a（含加入告示牌）。");
                } else {
                    Chat.send(sender, "&c删除失败：场不存在，或有人/正在游戏。");
                }
                return true;
            }
            case "setup" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                if (args.length < 2) { Chat.send(sender, "&c用法：/hp setup <场名>（进入配置模式，发配置道具）"); return true; }
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
                if (args.length < 2) { Chat.send(sender, "&c用法：/hp joinsign <场名>（随后右键点击一个告示牌）"); return true; }
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
                Chat.send(sender, "&6" + target.getName() + " 的烫手山芋战绩：&e获得第一名 &f" + m.getFirsts(target.getUniqueId()) + " &6次");
                return true;
            }
            case "help" -> help(sender);
            default -> Chat.send(sender, "&c未知子命令。");
        }
        return true;
    }

    private HotPotatoArena editing(Player p) {
        String name = editing.get(p.getUniqueId());
        if (name == null) {
            Chat.send(p, "&c请先 /hp create 或 /hp edit <场名>。");
            return null;
        }
        HotPotatoArena a = plugin.getManager().getArena(name);
        if (a == null) {
            editing.remove(p.getUniqueId());
            Chat.send(p, "&c场已被删除。");
            return null;
        }
        return a;
    }

    private void help(CommandSender sender) {
        boolean admin = sender instanceof Player p ? isAdmin(p) : true;
        Chat.send(sender, "&6====== 烫手山芋 /hp ======");
        Chat.send(sender, "&e/hp join <场名>&f 加入  &e/hp leave&f 离开  &e/hp list&f 场列表  &e/hp stats [玩家]&f 第一名次数");
        if (admin) {
            Chat.send(sender, "&6-- OP --");
            Chat.send(sender, "&e/hp create <名>&f 创建  &e/hp remove <名>&f 删除");
            Chat.send(sender, "&e/hp setup <名>&f 配置模式（道具：木棍圈范围、金锄头中央点、纸等待点、绿宝石保存、屏障退出）");
            Chat.send(sender, "&e/hp edit <名>&f 命令编辑  &e/hp pos1 / pos2 / center&f 圈范围与中央点  &e/hp lobby&f 等待点");
            Chat.send(sender, "&e/hp joinsign <名>&f 放置加入告示牌  &e/hp save&f 保存  &e/hp start&f 开始");
            Chat.send(sender, "&e/hp rename <旧名> <新名>&f 场地改名（同步菜单与告示牌）");
            Chat.send(sender, "&e/hp stats reset&f 清空全部第一名记录");
        }
    }

    private void noPerm(CommandSender sender) {
        Chat.send(sender, "&c只有管理员（OP 或 hp.admin）可以使用。");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("join", "leave", "list", "stats"));
            boolean admin = sender instanceof Player p ? isAdmin(p) : true;
            if (admin) {
                subs.addAll(List.of("create", "remove", "edit", "rename", "setup", "setup-exit", "pos1", "pos2", "center", "lobby", "save", "start", "joinsign"));
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
