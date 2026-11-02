package com.murdermystery;

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

/** /mm 命令。 */
public class MurderMysteryCommand implements CommandExecutor, TabCompleter {

    private final MurderMysteryPlugin plugin;
    private final Map<UUID, String> editing = new LinkedHashMap<>(); // OP 当前编辑的场

    public MurderMysteryCommand(MurderMysteryPlugin plugin) {
        this.plugin = plugin;
    }

    /** 管理权限：OP 或持有 plugin.yml 里声明的 mm.admin 节点。 */
    private boolean isAdmin(Player p) {
        return p.isOp() || p.hasPermission("mm.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        MurderMysteryManager m = plugin.getManager();
        boolean admin = sender instanceof Player p ? isAdmin(p) : true;
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/mm create <场名>"); return true; }
                if (m.createArena(args[1])) {
                    if (sender instanceof Player p) {
                        editing.put(p.getUniqueId(), args[1]);
                    }
                    m.saveAll();
                    Chat.send(sender, "&a已创建 &e" + args[1] + "&a。用 /mm setup " + args[1] + " 进入配置模式。");
                } else {
                    Chat.send(sender, "&c场已存在。");
                }
                return true;
            }
            case "pos1", "pos2", "center", "lobby" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                MurderMysteryArena a = editing(p);
                if (a == null) return true;
                Location loc = p.getLocation();
                switch (sub) {
                    case "pos1" -> a.setPos1(loc);
                    case "pos2" -> a.setPos2(loc);
                    case "center" -> a.setCenter(loc);
                    default -> a.setLobbyPoint(loc);
                }
                Chat.send(sender, "&a已设置 &e" + a.getName() + "&a 的 " + sub + (a.isReady() ? "（配置齐全）" : ""));
                m.saveAll();
                return true;
            }
            case "save" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                MurderMysteryArena a = editing(p);
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
                for (MurderMysteryArena a : m.getArenas().values()) {
                    Chat.send(sender, "&e" + a.getName() + " &f[" + a.getState().name() + "] "
                            + (a.isReady() ? "&7已配置" : "&c未配置") + " &7" + a.getPlayers().size() + "人");
                }
                return true;
            }
            case "join" -> {
                if (args.length < 2) { Chat.send(sender, "&c用法：/mm join <场名>"); return true; }
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
                if (!(sender instanceof Player p)) return true;
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
                String arenaName = editing.getOrDefault(p.getUniqueId(), null);
                if (arenaName == null) {
                    Chat.send(sender, "&c请先 /mm edit <场名>。");
                    return true;
                }
                m.start(p, arenaName);
                return true;
            }
            case "rename" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 3) { Chat.send(sender, "&c用法：/mm rename <旧场名> <新场名>"); return true; }
                String oldName = args[1];
                String newName = args[2];
                if (oldName.equals(newName)) { Chat.send(sender, "&c新旧场名相同。"); return true; }
                if (!m.renameArena(oldName, newName)) {
                    Chat.send(sender, "&c改名失败：场不存在、正在对局中，或新场名需 1~20 个中英文字母/数字/下划线/短横线且不与现有场重名。");
                    return true;
                }
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
                if (args.length < 2) { Chat.send(sender, "&c用法：/mm edit <场名>"); return true; }
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
                if (args.length < 2) { Chat.send(sender, "&c用法：/mm remove <场名>"); return true; }
                if (m.removeArena(args[1])) {
                    m.saveAll();
                    Chat.send(sender, "&a已删除场 &e" + args[1] + "&a（含加入告示牌）。");
                } else {
                    Chat.send(sender, "&c删除失败：场不存在，或有人/正在游戏。");
                }
                return true;
            }
            case "setup" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                if (args.length < 2) { Chat.send(sender, "&c用法：/mm setup <场名>（进入配置模式，发配置道具）"); return true; }
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
                if (args.length < 2) { Chat.send(sender, "&c用法：/mm joinsign <场名>（随后右键点击一个告示牌）"); return true; }
                if (!(sender instanceof Player p)) return true;
                m.placeJoinSign(p, args[1]);
                return true;
            }
            case "watch" -> {
                if (!(sender instanceof Player p)) {
                    Chat.send(sender, "&c该命令需要玩家执行。");
                    return true;
                }
                m.openWatchMenu(p);
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
                    Chat.send(sender, "&a已清空全部战绩。");
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
                Chat.send(sender, "&6" + target.getName() + " 的密室杀手战绩：&e胜场 &f" + m.getWins(target.getUniqueId())
                        + " &6｜ &e击杀 &f" + m.getKillsStat(target.getUniqueId()));
                return true;
            }
            case "goldblocks", "gold" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/mm goldblocks <场名>"); return true; }
                MurderMysteryArena a = m.getArena(args[1]);
                if (a == null) { Chat.send(sender, "&c场不存在。"); return true; }
                Chat.send(sender, "&6场地「" + a.getName() + "」的金块：" + m.goldScanStatus(a));
                List<Location> golds = m.goldBlockList(a);
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < golds.size(); i++) {
                    Location l = golds.get(i);
                    line.append("&7#").append(i).append(" &f(")
                            .append(l.getBlockX()).append(",").append(l.getBlockY()).append(",").append(l.getBlockZ())
                            .append(")");
                    if ((i + 1) % 3 == 0 || i == golds.size() - 1) {
                        Chat.send(sender, line.toString());
                        line.setLength(0);
                    } else {
                        line.append("  ");
                    }
                }
                return true;
            }
            case "reload" -> {
                if (!admin) { noPerm(sender); return true; }
                int count = m.reloadConfigAndRescanGold();
                if (count < 0) {
                    Chat.send(sender, "&cconfig.yml 有语法错误，本次重载已取消（原配置继续生效）。"
                            + "&7控制台会给出具体行号——常见原因是「键: 值」的冒号后面漏了空格。");
                    return true;
                }
                Chat.send(sender, "&a已重新加载配置，并开始重新扫描 &e" + count
                        + " &a个场地的金块（后台分帧扫描，几秒后可用 &e/mm goldblocks <场名>&a 查看）。");
                Chat.send(sender, "&7注意：场地范围/等待点等坐标改动仍需重启服务器才能生效。");
                return true;
            }
            case "help" -> help(sender);
            default -> Chat.send(sender, "&c未知子命令。");
        }
        return true;
    }

    private MurderMysteryArena editing(Player p) {
        String name = editing.get(p.getUniqueId());
        if (name == null) {
            Chat.send(p, "&c请先 /mm create 或 /mm edit <场名>。");
            return null;
        }
        MurderMysteryArena a = plugin.getManager().getArena(name);
        if (a == null) {
            editing.remove(p.getUniqueId());
            Chat.send(p, "&c场已被删除。");
            return null;
        }
        return a;
    }

    private void help(CommandSender sender) {
        boolean admin = sender instanceof Player p ? isAdmin(p) : true;
        Chat.send(sender, "&6====== 密室杀手 /mm ======");
        Chat.send(sender, "&e/mm join <场名>&f 加入  &e/mm leave&f 离开  &e/mm list&f 场列表  &e/mm stats [玩家]&f 战绩");
        Chat.send(sender, "&e/mm watch&f 观战：打开「存活玩家」菜单，点玩家头像传送到 TA 那里（被打死变旁观者后可用）");
        MurderMysteryManager m = plugin.getManager();
        Chat.send(sender, "&7玩法：杀手持铁剑行凶（不隐身）。开局人人有商店道具，右键开金锭商店："
                + m.swordCost() + " 金锭=一击必杀石剑，" + m.crossbowCost() + " 金锭=弩，" + m.arrowCost() + " 金锭=1 支箭，"
                + m.deathNoteCost() + " 金锭=死亡名单，" + m.steakCost() + " 金锭=断头饭（牛排）×" + m.steakAmount()
                + "。石剑/弩误伤无辜者都是两人同归于尽。");
        if (admin) {
            Chat.send(sender, "&6-- OP --");
            Chat.send(sender, "&e/mm create <名>&f 创建  &e/mm remove <名>&f 删除  &e/mm rename <旧> <新>&f 改名");
            Chat.send(sender, "&e/mm setup <名>&f 配置模式（木棍圈范围、金锄头中心点、纸等待点、绿宝石保存、屏障退出）");
            Chat.send(sender, "&e/mm edit <名>&f 命令编辑  &e/mm pos1 / pos2 / center&f 圈范围与中心点  &e/mm lobby&f 等待点");
            Chat.send(sender, "&e/mm joinsign <名>&f 放加入告示牌  &e/mm save&f 保存  &e/mm start&f 开始");
            Chat.send(sender, "&e/mm goldblocks <名>&f 列出该场所有金块坐标  &e/mm reload&f 重载配置并重新扫描金块");
            Chat.send(sender, "&e/mm stats reset&f 清空全部战绩");
        }
    }

    private void noPerm(CommandSender sender) {
        Chat.send(sender, "&c只有管理员（OP 或 mm.admin）可以使用。");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("join", "leave", "list", "stats", "watch"));
            boolean admin = sender instanceof Player p ? isAdmin(p) : true;
            if (admin) {
                subs.addAll(List.of("create", "remove", "edit", "rename", "setup", "setup-exit",
                        "pos1", "pos2", "center", "lobby", "save", "start", "joinsign",
                        "goldblocks", "reload"));
            }
            subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).forEach(out::add);
        } else if (args.length == 2) {
            if (List.of("join", "edit", "rename", "setup", "remove", "joinsign", "goldblocks")
                    .contains(args[0].toLowerCase())) {
                out.addAll(plugin.getManager().getArenas().keySet().stream()
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList()));
            }
        }
        return out;
    }
}
