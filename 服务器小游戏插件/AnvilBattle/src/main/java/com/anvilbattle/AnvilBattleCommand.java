package com.anvilbattle;

import com.hub.util.Chat;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /anvil 命令（别名 /ab、/anvilbattle）。 */
public final class AnvilBattleCommand implements CommandExecutor, TabCompleter {

    private final AnvilBattlePlugin plugin;
    private final AnvilBattleManager manager;

    public AnvilBattleCommand(AnvilBattlePlugin plugin, AnvilBattleManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> {
                Player player = player(sender);
                if (player != null) {
                    manager.join(player);
                }
            }
            case "leave" -> {
                Player player = player(sender);
                if (player != null) {
                    manager.leave(player, false);
                }
            }
            case "list" -> list(sender);
            case "stats" -> stats(sender, args);
            case "start" -> {
                // D21：开始权在主持人手上；主持人**不享受管理员特权**（人数不够不能开）
                boolean isAdminS = !(sender instanceof Player sp) || manager.isAdmin(sp);
                if (!isAdminS) {
                    if (!manager.isHost(sender)) {
                        Chat.send(sender, "&c只有本局**主持人**或管理员可以开始。");
                        return true;
                    }
                    if (manager.waitingCount() < manager.minPlayersPublic()) {
                        Chat.send(sender, "&c人数还没达到 &f" + manager.minPlayersPublic()
                                + " 人&c，主持人也不能开始。");
                        return true;
                    }
                }
                if (manager.isRunning()) {
                    Chat.send(sender, "&e对局已经在进行中。");
                } else if (manager.arena().isReady()) {
                    manager.startGame(true);
                } else {
                    Chat.send(sender, "&c场地未配置完整（缺 " + manager.arena().missing() + "）。");
                }
            }
            case "stop" -> {
                if (!admin(sender)) {
                    return true;
                }
                manager.stopGame();
            }
            case "setpos1" -> setCorner(sender, true);
            case "setpos2" -> setCorner(sender, false);
            case "setlobby" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player == null) {
                    return true;
                }
                manager.arena().setLobby(player.getLocation());
                Chat.send(sender, "&a等待点已设置为 " + describe(player.getLocation()) + "，记得 /anvil save。");
            }
            case "addspawn" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player == null) {
                    return true;
                }
                manager.arena().addSpawn(player.getLocation());
                Chat.send(sender, "&a已添加出生点 " + describe(player.getLocation())
                        + "（共 " + manager.arena().getSpawns().size() + " 个），记得 /anvil save。");
            }
            case "clearspawns" -> {
                if (!admin(sender)) {
                    return true;
                }
                manager.arena().clearSpawns();
                Chat.send(sender, "&a已清空出生点，记得 /anvil save。");
            }
            case "genspawns" -> {
                if (!admin(sender)) {
                    return true;
                }
                if (!manager.arena().hasRegion()) {
                    Chat.send(sender, "&c先 /anvil setpos1 和 /anvil setpos2 圈出场地。");
                    return true;
                }
                int count = manager.maxPlayers();
                if (args.length > 1) {
                    try {
                        count = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ex) {
                        Chat.send(sender, "&c数量必须是数字，例如 /anvil genspawns 12");
                        return true;
                    }
                }
                if (count < 1 || count > 64) {
                    Chat.send(sender, "&c数量需在 1~64 之间。");
                    return true;
                }
                int generated = manager.generateSpawns(count);
                manager.saveAll();
                if (generated <= 0) {
                    Chat.send(sender, "&c没找到可站立的方块：检查 arena.pos1/pos2 的 X/Z 是否圈住平台、"
                            + "void-y（当前 &eY=" + String.format(Locale.ROOT, "%.1f", manager.arena().getVoidY())
                            + "&c）是否在平台下方。");
                } else {
                    Chat.send(sender, "&a已清空旧出生点，自动生成 &e" + generated + " &a个（请求 " + count + " 个）并保存。");
                    if (generated < count) {
                        Chat.send(sender, "&7部分位置是洞/水或平台太小，只生成了 " + generated + " 个。");
                    }
                }
            }
            case "setvoid" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player == null) {
                    return true;
                }
                double y = player.getLocation().getY();
                if (args.length > 1) {
                    try {
                        y = Double.parseDouble(args[1]);
                    } catch (NumberFormatException ex) {
                        Chat.send(sender, "&cY 必须是数字，例如 /anvil setvoid -20");
                        return true;
                    }
                }
                manager.arena().setVoidY(y);
                Chat.send(sender, "&a淘汰高度已设置为 Y=" + String.format(Locale.ROOT, "%.1f", y) + "，记得 /anvil save。");
            }
            case "sety" -> {
                if (!admin(sender)) {
                    return true;
                }
                Player player = player(sender);
                if (player == null) {
                    return true;
                }
                double y = player.getLocation().getY();
                if (args.length > 1) {
                    try {
                        y = Double.parseDouble(args[1]);
                    } catch (NumberFormatException ex) {
                        Chat.send(sender, "&cY 必须是数字，例如 /anvil sety 100（0 = 自动）");
                        return true;
                    }
                }
                manager.arena().setAnvilY(y);
                Chat.send(sender, "&a铁砧起始 Y 已设置为 " + String.format(Locale.ROOT, "%.1f", y)
                        + (Math.abs(y) < 0.0001 ? "（自动：柱顶+spawn-height）" : "（绝对高度，需高于平台）")
                        + "，记得 /anvil save。");
            }
            case "save" -> {
                if (!admin(sender)) {
                    return true;
                }
                manager.saveAll();
                Chat.send(sender, "&a场地配置已保存。");
            }
            case "reload" -> {
                if (!admin(sender)) {
                    return true;
                }
                if (manager.isRunning()) {
                    Chat.send(sender, "&c对局进行中，不能重载配置。");
                    return true;
                }
                plugin.reloadConfig();
                manager.reload();
                Chat.send(sender, "&a配置已重新载入。");
            }
            default -> Chat.send(sender, "&c未知子指令，输入 &f/anvil help &c查看帮助。");
        }
        return true;
    }

    private void setCorner(CommandSender sender, boolean first) {
        if (!admin(sender)) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (first) {
            manager.arena().setPos1(player.getLocation());
        } else {
            manager.arena().setPos2(player.getLocation());
        }
        Chat.send(sender, "&a已设置范围角 " + (first ? "1" : "2") + "：" + describe(player.getLocation())
                + "，记得 /anvil save。");
    }

    private void list(CommandSender sender) {
        Chat.send(sender, "&6===== 铁砧掘战 =====");
        Chat.send(sender, "&7状态：&e" + manager.state() + " &7等待 &e" + manager.waitingCount()
                + " &7存活 &e" + manager.playingCount() + " &7淘汰 &e" + manager.eliminatedCount());
        Chat.send(sender, "&7场地：&f" + (manager.arena().isReady() ? "已配置" : "未配置（缺 "
                + manager.arena().missing() + "）")
                + " &7| 出生点 &e" + manager.arena().getSpawns().size());
        Chat.send(sender, "&7淘汰高度：&eY=" + String.format(Locale.ROOT, "%.1f", manager.arena().getVoidY())
                + " &7| 每波 &e" + manager.currentAnvilAmount() + " &7个铁砧 / &e"
                + plugin.getConfig().getInt("anvil.interval-seconds", 1) + "s"
                + " &7| 递增上限 &e" + plugin.getConfig().getInt("anvil.ramp.max-amount", 12));
        double anvilY = manager.arena().getAnvilY();
        Chat.send(sender, "&7落砧 Y：&e"
                + (Math.abs(anvilY) < 0.0001 ? "自动（柱顶+高度）" : String.format(Locale.ROOT, "%.1f", anvilY))
                + " &7| 破坏半径 &e" + plugin.getConfig().getInt("anvil.break-radius", 1)
                + " &7深度 &e" + plugin.getConfig().getInt("anvil.break-depth", 2));
    }

    private void stats(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&c该命令需要玩家执行。");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
            if (!admin(sender)) {
                return;
            }
            manager.resetStats();
            Chat.send(sender, "&a已清空全部前三名与积分记录。");
            return;
        }
        Player target = player;
        if (args.length >= 2) {
            target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) {
                Chat.send(sender, "&c玩家不在线。");
                return;
            }
        }
        Chat.send(sender, "&6" + target.getName() + " 的 " + AnvilBattleManager.GAME + " 战绩："
                + "&e积分 &f" + manager.getPoints(target.getUniqueId())
                + " &7| &e第一 &f" + manager.getStat("firsts", target.getUniqueId())
                + " &6第二 &f" + manager.getStat("seconds", target.getUniqueId())
                + " &6第三 &f" + manager.getStat("thirds", target.getUniqueId()));
    }

    private void help(CommandSender sender) {
        Chat.send(sender, "&6====== 铁砧掘战 /anvil ======");
        Chat.send(sender, "&e/anvil join&f 加入  &e/anvil leave&f 离开  &e/anvil list&f 状态  &e/anvil stats [玩家]&f 战绩");
        if (admin(sender)) {
            Chat.send(sender, "&6-- OP --");
            Chat.send(sender, "&e/anvil setpos1 | setpos2&f 站在两个对角设置场地范围（X/Y/Z 都取）");
            Chat.send(sender, "&e/anvil setlobby&f 等待点  &e/anvil addspawn&f 添加出生点  &e/anvil clearspawns&f 清空出生点");
            Chat.send(sender, "&e/anvil genspawns [数量]&f 自动生成出生点（默认最多人数，会清空旧的）");
            Chat.send(sender, "&e/anvil setvoid [Y]&f 淘汰高度（不写 Y 用当前高度）");
            Chat.send(sender, "&e/anvil sety [Y]&f 铁砧起始 Y（不写 Y 用当前高度；0 = 自动柱顶+高度）");
            Chat.send(sender, "&e/anvil save&f 保存  &e/anvil start&f 开始  &e/anvil stop&f 结束  &e/anvil reload&f 重载");
            Chat.send(sender, "&e/anvil stats reset&f 清空前三名与积分记录");
            Chat.send(sender, "&7铁砧波次/数量/递增/破坏半径/下钻时长在 config.yml 的 anvil 段。");
        }
    }

    private boolean admin(CommandSender sender) {
        if (sender instanceof Player player) {
            if (!manager.isAdmin(player)) {
                Chat.send(sender, "&c你没有权限执行该指令。");
                return false;
            }
            return true;
        }
        return true;
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        Chat.send(sender, "&c该命令需要玩家执行。");
        return null;
    }

    private String describe(Location loc) {
        return loc.getWorld().getName() + " " + String.format(Locale.ROOT, "%.1f %.1f %.1f",
                loc.getX(), loc.getY(), loc.getZ());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("join", "leave", "list", "stats", "help"));
            boolean admin = !(sender instanceof Player player) || manager.isAdmin(player);
            if (admin) {
                subs.addAll(List.of("start", "stop", "setpos1", "setpos2", "setlobby",
                        "addspawn", "clearspawns", "genspawns", "setvoid", "sety", "save", "reload"));
            }
            subs.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).forEach(out::add);
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("stats")) {
                out.add("reset");
            } else if (args[0].equalsIgnoreCase("genspawns")) {
                for (String option : List.of("4", "8", "12", "16")) {
                    if (option.startsWith(args[1])) {
                        out.add(option);
                    }
                }
            } else if (args[0].equalsIgnoreCase("setvoid")) {
                for (String option : List.of("-64", "-32", "-16", "0")) {
                    if (option.startsWith(args[1])) {
                        out.add(option);
                    }
                }
            } else if (args[0].equalsIgnoreCase("sety")) {
                for (String option : List.of("80", "100", "120", "0")) {
                    if (option.startsWith(args[1])) {
                        out.add(option);
                    }
                }
            }
        }
        return out;
    }
}
