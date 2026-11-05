package com.luckypillar;

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

/** /lp 命令（别名 /lpz、/lucky）。 */
public class LuckyPillarCommand implements CommandExecutor, TabCompleter {

    private final LuckyPillarPlugin plugin;

    public LuckyPillarCommand(LuckyPillarPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isAdmin(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            return true;
        }
        return p.isOp() || p.hasPermission("lp.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        LuckyPillarManager m = plugin.getManager();
        if (args.length == 0) {
            help(sender);
            return true;
        }
        boolean admin = isAdmin(sender);
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "join" -> {
                if (!(sender instanceof Player p)) {
                    Chat.send(sender, "&c该命令需要玩家执行。");
                    return true;
                }
                if (args.length < 2) {
                    if (m.getArenas().size() == 1) {
                        m.join(p, m.getArenas().keySet().iterator().next());
                        return true;
                    }
                    Chat.send(sender, "&c用法：/lp join <场名>（/lp list 看场列表）");
                    return true;
                }
                m.join(p, args[1]);
                return true;
            }
            case "leave" -> {
                if (!(sender instanceof Player p)) {
                    Chat.send(sender, "&c该命令需要玩家执行。");
                    return true;
                }
                m.leave(p);
                return true;
            }
            case "list" -> {
                if (m.getArenas().isEmpty()) {
                    Chat.send(sender, "&7暂无场。OP 用 &e/lp create <名>&7 新建。");
                    return true;
                }
                for (LuckyPillarArena a : m.getArenas().values()) {
                    String state = switch (a.getState()) {
                        case WAITING -> "&a等待";
                        case RUNNING -> "&c游戏中";
                        case ENDING -> "&e结算中";
                    };
                    Chat.send(sender, "&e" + a.getName() + " &f[" + state + "&f] "
                            + (a.isReady() ? "&7已配置" : "&c未配置") + " &7" + a.getPlayers().size() + " 人");
                }
                return true;
            }
            case "stats" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
                    if (!admin) {
                        noPerm(sender);
                        return true;
                    }
                    m.resetFirsts();
                    Chat.send(sender, "&a已清空幸运之柱的全部第一名记录。");
                    return true;
                }
                Player target = (sender instanceof Player p) ? p : null;
                if (args.length >= 2) {
                    target = plugin.getServer().getPlayerExact(args[1]);
                    if (target == null) {
                        Chat.send(sender, "&c玩家不在线。");
                        return true;
                    }
                }
                if (target == null) {
                    Chat.send(sender, "&c控制台请用 /lp stats <玩家>。");
                    return true;
                }
                Chat.send(sender, "&5" + target.getName() + "&f 的幸运之柱战绩：&e冠军 &f"
                        + m.getFirsts(target.getUniqueId()) + " &e次");
                return true;
            }
            case "create" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/lp create <场名>"); return true; }
                if (m.createArena(args[1])) {
                    if (sender instanceof Player p) {
                        m.setEditing(p, args[1]);
                    }
                    Chat.send(sender, "&a已创建 &e" + args[1] + "&a。接着 &e/lp setup " + args[1] + "&a 进配置模式点两个点。");
                } else {
                    Chat.send(sender, "&c创建失败：场已存在，或场名需 1~20 个中英文/数字/下划线/短横线。");
                }
                return true;
            }
            case "remove" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/lp remove <场名>"); return true; }
                if (m.removeArena(args[1])) {
                    Chat.send(sender, "&a已删除场 &e" + args[1] + "&a。");
                } else {
                    Chat.send(sender, "&c删除失败：场不存在，或场里有人/正在游戏。");
                }
                return true;
            }
            case "rename" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 3) { Chat.send(sender, "&c用法：/lp rename <旧名> <新名>"); return true; }
                if (m.renameArena(args[1], args[2])) {
                    Chat.send(sender, "&a已将场 &e" + args[1] + "&a 改名为 &e" + args[2] + "&a，菜单已同步。");
                } else {
                    Chat.send(sender, "&c改名失败：场不存在、正在游戏中，或新名不合法/重名。");
                }
                return true;
            }
            case "setup" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) { Chat.send(sender, "&c该命令需要玩家执行。"); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/lp setup <场名>（进入配置模式发工具）"); return true; }
                if (m.enterSetup(p, args[1])) {
                    m.setEditing(p, args[1]);
                }
                return true;
            }
            case "setup-exit" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) { Chat.send(sender, "&c该命令需要玩家执行。"); return true; }
                if (m.isInSetup(p.getUniqueId())) {
                    m.exitSetup(p);
                } else {
                    Chat.send(sender, "&c你不在配置模式中。");
                }
                return true;
            }
            case "center", "lobby", "save", "preview", "start", "stop" -> {
                if (!admin) { noPerm(sender); return true; }
                String arenaName = args.length >= 2 ? args[1]
                        : (sender instanceof Player p ? m.editingArena(p) : null);
                if (arenaName == null) {
                    Chat.send(sender, "&c请先 /lp create <名> 或 /lp setup <名>，或写成 /lp " + sub + " <场名>。");
                    return true;
                }
                LuckyPillarArena a = m.getArena(arenaName);
                if (a == null) {
                    Chat.send(sender, "&c场 &e" + arenaName + "&c 不存在。");
                    return true;
                }
                switch (sub) {
                    case "center" -> {
                        if (!(sender instanceof Player p)) { Chat.send(sender, "&c该命令需要玩家执行。"); return true; }
                        Location loc = p.getLocation();
                        a.setCenter(loc);
                        m.saveAll();
                        Chat.send(sender, "&a已设置 &e" + a.getName() + "&a 中心点：&f"
                                + loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
                        Chat.send(sender, "&7柱顶高度 = 这个 Y，柱阵按人数围着它自动生成。");
                    }
                    case "lobby" -> {
                        if (!(sender instanceof Player p)) { Chat.send(sender, "&c该命令需要玩家执行。"); return true; }
                        Location loc = p.getLocation();
                        a.setLobby(loc);
                        m.ensurePlatform(loc);
                        m.saveAll();
                        Chat.send(sender, "&a已设置 &e" + a.getName() + "&a 等待点：&f"
                                + loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
                    }
                    case "save" -> {
                        m.saveAll();
                        Chat.send(sender, "&a已保存场 &e" + a.getName() + "&a（"
                                + (a.isReady() ? "两个点齐全" : "还缺 center / lobby") + "）。");
                    }
                    case "preview" -> {
                        if (!(sender instanceof Player p)) { Chat.send(sender, "&c该命令需要玩家执行。"); return true; }
                        m.preview(p, a);
                    }
                    case "start" -> {
                        Player p = sender instanceof Player pl ? pl : null;
                        // **控制台开局**（派对/脚本）：没有"编辑中的场"，交给 manager 自己找
                        // （房间上下文 → 有人等待的那一间）。以前这里直接判"只有主持人"→ 永不开局。
                        if (p == null) {
                            m.start(null, args.length >= 2 ? args[1] : null);
                            return true;
                        }
                        // D21：开始权在主持人手上；主持人不能突破 min-players
                        boolean isAdmin = p.hasPermission("luckypillar.admin");
                        if (!isAdmin) {
                            if (p == null || !m.isHostOf(p, a.getName())) {
                                Chat.send(sender, "&c只有本场**主持人**或管理员可以开始。");
                                return true;
                            }
                            if (m.waitingCountOf(a.getName()) < m.minPlayersOf(a.getName())) {
                                Chat.send(sender, "&c人数还没达到 &f" + m.minPlayersOf(a.getName())
                                        + " 人&c，主持人也不能开始。");
                                return true;
                            }
                        }
                        m.start(p, a.getName());
                    }
                    default -> {
                        Player p = sender instanceof Player pl ? pl : null;
                        m.stop(p, a.getName());
                    }
                }
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
            default -> Chat.send(sender, "&c未知子命令，用 &e/lp help&c 看用法。");
        }
        return true;
    }

    private void help(CommandSender sender) {
        boolean admin = isAdmin(sender);
        Chat.send(sender, "&5&l====== 幸运之柱 /lp ======");
        Chat.send(sender, "&e/lp join <场名>&f 加入  &e/lp leave&f 离开  &e/lp list&f 场列表  &e/lp stats [玩家]&f 冠军次数");
        if (admin) {
            Chat.send(sender, "&5-- OP --");
            Chat.send(sender, "&e/lp create <名>&f 新建  &e/lp remove <名>&f 删除  &e/lp rename <旧> <新>&f 改名");
            Chat.send(sender, "&e/lp setup <名>&f 配置模式：金锄头=中心点 · 纸=等待点 · 时钟=预览 · 绿宝石=保存 · 屏障=退出");
            Chat.send(sender, "&e/lp center&f / &e/lp lobby&f 用当前位置设点  &e/lp save&f 保存  &e/lp preview&f 预览柱阵");
            Chat.send(sender, "&e/lp start&f 开局（≥min-players 人）  &e/lp stop&f 强制结束  &e/lp stats reset&f 清空冠军记录");
        }
        Chat.send(sender, "&7游戏里每一步都有动作栏提示；场地只要两个点，柱阵按人数自动生成。");
    }

    private void noPerm(CommandSender sender) {
        Chat.send(sender, "&c只有管理员（OP 或 lp.admin）可以使用。");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = isAdmin(sender);
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("join", "leave", "list", "stats", "help"));
            if (admin) {
                subs.addAll(List.of("create", "remove", "rename", "setup", "setup-exit",
                        "center", "lobby", "save", "preview", "start", "stop"));
            }
            subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).forEach(out::add);
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (List.of("join", "remove", "rename", "setup", "center", "lobby", "save",
                    "preview", "start", "stop").contains(sub)) {
                out.addAll(plugin.getManager().getArenas().keySet().stream()
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList()));
            } else if (sub.equals("stats")) {
                out.add("reset");
                out.addAll(plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList()));
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("rename")) {
            out.addAll(plugin.getManager().getArenas().keySet().stream()
                    .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList()));
        }
        return out;
    }
}
