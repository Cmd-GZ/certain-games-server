package com.digdown;

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

/** /dig 命令（别名 /dgb、/digdown）。 */
public class DigToBottomCommand implements CommandExecutor, TabCompleter {

    private final DigToBottomPlugin plugin;
    private final Map<UUID, String> editing = new LinkedHashMap<>();

    public DigToBottomCommand(DigToBottomPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isAdmin(Player p) {
        return p.isOp() || p.hasPermission("dig.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        DigToBottomManager m = plugin.getManager();
        boolean admin = sender instanceof Player p ? isAdmin(p) : true;
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/dig create <场名>"); return true; }
                if (m.createArena(args[1])) {
                    if (sender instanceof Player p) {
                        editing.put(p.getUniqueId(), args[1]);
                    }
                    Chat.send(sender, "&a已创建 &e" + args[1] + "&a。下一步：站在柱子最底层位置 &e/dig center&a，再 &e/dig lanes / radius / build / lobby / save&a。");
                } else {
                    Chat.send(sender, "&c场已存在，或场名不合法（1~20 个中英文/数字/下划线/短横线）。");
                }
                return true;
            }
            case "remove" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/dig remove <场名>"); return true; }
                if (m.removeArena(args[1])) {
                    Chat.send(sender, "&a已删除场 &e" + args[1] + "&a。");
                } else {
                    Chat.send(sender, "&c删除失败：场不存在、有人在场中或正在游戏。");
                }
                return true;
            }
            case "rename" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 3) { Chat.send(sender, "&c用法：/dig rename <旧场名> <新场名>"); return true; }
                if (m.renameArena(args[1], args[2])) {
                    for (Map.Entry<UUID, String> e : editing.entrySet()) {
                        if (e.getValue().equals(args[1])) {
                            e.setValue(args[2]);
                        }
                    }
                    Chat.send(sender, "&a已将场 &e" + args[1] + "&a 改名为 &e" + args[2] + "&a。");
                } else {
                    Chat.send(sender, "&c改名失败：场不存在、新场名不合法/重复，或有人在场中。");
                }
                return true;
            }
            case "edit" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/dig edit <场名>"); return true; }
                if (m.getArena(args[1]) == null) { Chat.send(sender, "&c场不存在。"); return true; }
                if (sender instanceof Player p) {
                    editing.put(p.getUniqueId(), args[1]);
                }
                Chat.send(sender, "&a已进入 &e" + args[1] + "&a 编辑。");
                return true;
            }
            case "center" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                DigToBottomArena a = editing(p);
                if (a == null) return true;
                a.setCenter(p.getLocation());
                m.saveAll();
                Chat.send(sender, "&a已设置圆心 &e" + shortLoc(p.getLocation())
                        + "&a（这里的 Y = 柱子最底层方块的高度）。");
                Chat.send(sender, "&7环形坑位按 &e/dig lanes&7 数量、&e/dig radius&7 半径围绕这里生成。");
                return true;
            }
            case "lobby" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                DigToBottomArena a = editing(p);
                if (a == null) return true;
                a.setLobby(p.getLocation());
                m.saveAll();
                Chat.send(sender, "&a等待点已设为 &e" + shortLoc(p.getLocation()) + "&a。");
                return true;
            }
            case "spectator" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                DigToBottomArena a = editing(p);
                if (a == null) return true;
                a.setSpectator(p.getLocation());
                m.saveAll();
                Chat.send(sender, "&a观战落点已设为 &e" + shortLoc(p.getLocation())
                        + "&a（不设则默认在圆心上方 &e" + m.height() + "+6 &a格）。");
                return true;
            }
            case "lanes" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/dig lanes <1-32>"); return true; }
                if (!(sender instanceof Player p)) return true;
                DigToBottomArena a = editing(p);
                if (a == null) return true;
                int n;
                try {
                    n = Integer.parseInt(args[1]);
                } catch (NumberFormatException ex) {
                    Chat.send(sender, "&c请输入 1~32 的数字。");
                    return true;
                }
                if (n < 1 || n > 32) { Chat.send(sender, "&c坑位数需在 1~32 之间。"); return true; }
                a.setLanes(n);
                m.saveAll();
                Chat.send(sender, "&a坑位数设为 &e" + n + "&a。改完记得 &e/dig build&a 重新生成。");
                return true;
            }
            case "radius" -> {
                if (!admin) { noPerm(sender); return true; }
                if (args.length < 2) { Chat.send(sender, "&c用法：/dig radius <3-64>"); return true; }
                if (!(sender instanceof Player p)) return true;
                DigToBottomArena a = editing(p);
                if (a == null) return true;
                int r;
                try {
                    r = Integer.parseInt(args[1]);
                } catch (NumberFormatException ex) {
                    Chat.send(sender, "&c请输入 3~64 的数字。");
                    return true;
                }
                if (r < 3 || r > 64) { Chat.send(sender, "&c半径需在 3~64 之间。"); return true; }
                a.setRadius(r);
                m.saveAll();
                Chat.send(sender, "&a半径设为 &e" + r + "&a。改完记得 &e/dig build&a 重新生成。");
                return true;
            }
            case "build" -> {
                if (!admin) { noPerm(sender); return true; }
                if (!(sender instanceof Player p)) return true;
                DigToBottomArena a = editing(p);
                if (a == null) return true;
                m.build(p, a);
                return true;
            }
            case "save" -> {
                if (!admin) { noPerm(sender); return true; }
                DigToBottomArena a = editing(sender);
                if (a == null) return true;
                m.saveAll();
                Chat.send(sender, "&a场 &e" + a.getName() + "&a 已保存到 config.yml。");
                return true;
            }
            case "debug" -> {
                if (!admin) { noPerm(sender); return true; }
                boolean on = m.toggleDebugBreak();
                Chat.send(sender, on
                        ? "&a[DigDebug] 诊断日志已开启（控制台会打印 [DigDebug] 行）；再执行一次 /dig debug 关闭。"
                        : "&7[DigDebug] 诊断日志已关闭。");
                if (sender instanceof Player p) {
                    m.debugInfo(p);
                }
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
                    DigToBottomArena a = editing(player);
                    if (a == null) return true;
                    arenaName = a.getName();
                } else {
                    // 控制台不指定场地（派对/倒计时开局）：把每个有待开队列的房间都开起来
                    int started = m.startAllWaiting();
                    if (started == 0) {
                        Chat.send(sender, "&e没有正在等待开局的场地。");
                    }
                    return true;
                }
                m.start(player, arenaName);
                return true;
            }
            case "join" -> {
                if (args.length < 2) { Chat.send(sender, "&c用法：/dig join <场名>"); return true; }
                if (!(sender instanceof Player p)) return true;
                m.join(p, args[1]);
                return true;
            }
            case "leave" -> {
                if (!(sender instanceof Player p)) return true;
                m.leave(p);
                return true;
            }
            case "list" -> {
                if (m.displayArenas().isEmpty()) {
                    Chat.send(sender, "&7暂无场。");
                    return true;
                }
                for (DigToBottomArena a : m.displayArenas()) {
                    Chat.send(sender, "&e" + a.getName() + " &f[" + a.getState().name() + "] "
                            + (a.isReady() ? "&a已建好" : a.isBuilt() ? "&c缺等待点" : "&c未建场")
                            + " &7" + a.totalPlayers() + "人 &7坑位=" + a.getLanes() + " 半径=" + a.getRadius()
                            + (a.isBuilt() && a.getBuiltHeight() != m.height() ? " &c柱高不一致，需 /dig build" : ""));
                }
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
                    Chat.send(sender, "&a已清空全部前三名记录。");
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
                UUID id = target.getUniqueId();
                Chat.send(sender, "&6" + target.getName() + " 的 " + DigToBottomManager.GAME + " 战绩："
                        + "&e第一名 &f" + m.getStat("firsts", id)
                        + " &6第二名 &f" + m.getStat("seconds", id)
                        + " &6第三名 &f" + m.getStat("thirds", id));
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
            default -> Chat.send(sender, "&c未知子命令。");
        }
        return true;
    }

    private DigToBottomArena editing(Player p) {
        String name = editing.get(p.getUniqueId());
        if (name == null) {
            Chat.send(p, "&c请先 /dig create 或 /dig edit <场名>。");
            return null;
        }
        DigToBottomArena a = plugin.getManager().resolveArena(name);   // D22：房间上下文优先
        if (a == null) {
            editing.remove(p.getUniqueId());
            Chat.send(p, "&c场已被删除。");
            return null;
        }
        return a;
    }

    private DigToBottomArena editing(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Chat.send(sender, "&c该命令需要玩家执行。");
            return null;
        }
        return editing(p);
    }

    private String shortLoc(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
    }

    private void help(CommandSender sender) {
        boolean admin = sender instanceof Player p ? isAdmin(p) : true;
        Chat.send(sender, "&6====== 一掘到底 /dig ======");
        Chat.send(sender, "&e/dig join <场名>&f 加入  &e/dig leave&f 离开  &e/dig list&f 场列表  &e/dig stats [玩家]&f 战绩");
        if (admin) {
            Chat.send(sender, "&6-- OP 建场 --");
            Chat.send(sender, "&e/dig create <场名>&f 创建  &e/dig edit <场名>&f 编辑  &e/dig remove / rename");
            Chat.send(sender, "&e/dig center&f 圆心（站在柱子最底层方块处）  &e/dig lobby&f 等待点  &e/dig spectator&f 观战落点");
            Chat.send(sender, "&e/dig lanes <1-32>&f 坑位数  &e/dig radius <3-64>&f 环形半径");
            Chat.send(sender, "&e/dig build&f 生成玻璃外壳 + 基岩  &e/dig save&f 保存配置  &e/dig start&f 开始");
            Chat.send(sender, "&e/dig debug&f 开关诊断日志（排查方块挖不动：出生点保护）  &e/dig stats reset&f 清空前三名记录");
            Chat.send(sender, "&7柱高 / 方块池 / 每局时长在 config.yml 的 pillar、game-seconds、shorten-seconds。");
        }
    }

    private void noPerm(CommandSender sender) {
        Chat.send(sender, "&c只有管理员（OP 或 dig.admin）可以使用。");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("join", "leave", "list", "stats", "help"));
            if (sender instanceof Player p ? isAdmin(p) : true) {
                subs.addAll(List.of("create", "remove", "edit", "rename", "center", "lobby", "spectator",
                        "lanes", "radius", "build", "save", "start", "debug"));
            }
            subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).forEach(out::add);
        } else if (args.length == 2) {
            if (List.of("join", "edit", "rename", "remove").contains(args[0].toLowerCase())) {
                out.addAll(plugin.getManager().getArenas().keySet().stream()
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList()));
            } else if ("stats".equalsIgnoreCase(args[0])) {
                out.add("reset");
            }
        }
        return out;
    }
}
