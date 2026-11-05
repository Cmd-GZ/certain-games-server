package com.buildbattle.gtb.command;

import com.buildbattle.gtb.GuessBuildPlugin;
import com.buildbattle.gtb.arena.Arena;
import com.buildbattle.gtb.game.GameManager;
import com.buildbattle.gtb.util.Chat;
import com.buildbattle.gtb.words.WordManager;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /gtb 主命令：join / leave / start / stop / setup / word / reload / info / help */
public class GTBCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS =
            List.of("join", "leave", "start", "stop", "setup", "remove", "list", "arena", "word", "theme", "floor", "reload", "info", "help", "blacklist", "stats", "joinsign");
    private static final List<String> SETUP_SUBS = List.of("wand", "save", "cancel", "info", "clear");
    private static final List<String> WORD_SUBS = List.of("add", "remove", "list");
    private static final List<String> ARENA_SUBS = List.of("list", "add", "remove", "del", "edit", "select", "use", "info", "rename");

    private final GuessBuildPlugin plugin;

    public GTBCommand(GuessBuildPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 取"这次操作该用哪一份运行时状态"（D22）。
     *
     * <p>玩家：他在哪个房间就用那一份（{@code GTBRooms.forPlayer} 走的是玩家→房间记录）；
     * 控制台：当前房间上下文 → 最近有人加入的房间 → 默认实例。
     */
    private GameManager stateOf(CommandSender sender) {
        if (sender instanceof Player p) {
            // forPlayer 走"玩家 → 房间"记录；没记录的玩家拿到默认实例（不猜"最近的房间"）
            return plugin.rooms().forPlayer(p);
        }
        return plugin.rooms().current();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            // 控制台只支持"不需要玩家上下文"的几条：派对就是用 dispatchCommand(控制台, "gtb start") 开局的
            console(sender, args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT));
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> {
                // D22：键由房间上下文决定；指定场地名只决定"进哪张图"
                if (args.length > 1) {
                    plugin.rooms().join(player, args[1]);
                } else {
                    plugin.rooms().join(player, null);
                }
            }
            case "leave" -> plugin.rooms().leave(player, false);
            case "start" -> {
                // D21：开始权在主持人手上；主持人不能突破 min-players（管理员可以）
                GameManager game = stateOf(sender);
                boolean isAdminS = player.hasPermission("gtb.admin");
                if (isAdminS) {
                    game.start(player);
                } else if (game.isHost(player)) {
                    if (game.waitingCountPublic() < game.minPlayersPublic()) {
                        Chat.send(player, "&c人数还没达到 &f" + game.minPlayersPublic()
                                + " 人&c，主持人也不能开始（管理员可以强制开始）。");
                    } else {
                        game.start(player);
                    }
                } else {
                    Chat.send(player, "&c只有本局**主持人**或管理员可以开始。");
                }
            }
            case "stop" -> {
                if (checkAdmin(player, true)) {
                    stateOf(sender).stop(player);
                }
            }
            case "reload" -> {
                if (checkAdmin(player, false)) {
                    plugin.reloadAll();
                    Chat.send(player, "&a配置、词库、场地已重载。");
                }
            }
            case "lobby" -> {
                if (checkAdmin(player, true)) {
                    stateOf(sender).setLobbyPoint(player.getLocation());
                    var loc = player.getLocation();
                    Chat.send(player, "&a等待点已设置：&e" + loc.getWorld().getName() + " "
                            + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
                            + "&a。玩家加入后会被传送到这里等待开局。");
                }
            }
            case "setup" -> setup(player, args);
            case "remove", "del", "delete" -> arenaRemove(player, args);
            case "list" -> arenaList(player);
            case "arena" -> arena(player, args);
            case "word" -> word(player, args);
            case "theme" -> theme(player, args);
            case "floor" -> floor(player, args);
            case "blacklist" -> blacklist(player, args);
            case "stats" -> stats(player, args);
            case "joinsign" -> {
                if (checkAdmin(player, true)) {
                    plugin.placeJoinSign(player);
                }
            }
            case "info" -> info(player);
            default -> help(player);
        }
        return true;
    }

    /**
     * 控制台入口（D22）。
     *
     * <p>为什么必须有：派对开局是 {@code Bukkit.dispatchCommand(控制台, "gtb start")}，
     * 原来这里第一行就把控制台挡掉了（"该命令仅限游戏内玩家使用"）—— 被包装成派对子游戏后
     * **一关都开不起来**。控制台只放行不需要玩家上下文的那几条。
     */
    private void console(CommandSender sender, String sub) {
        switch (sub) {
            case "start" -> {
                int n = plugin.rooms().startAllWaiting();
                log(sender, "gtb start：已尝试开局 " + n + " 间。");
            }
            case "stop" -> {
                int n = plugin.rooms().stopAll();
                log(sender, "gtb stop：已结束 " + n + " 局。");
            }
            case "reload" -> {
                plugin.reloadAll();
                log(sender, "配置、词库、场地已重载。");
            }
            case "list" -> {
                java.util.List<Arena> all = plugin.getArenas().getAll();
                log(sender, "===== 场地列表（共 " + all.size() + " 个，生效 "
                        + plugin.getArenas().getActiveName() + "） =====");
                for (Arena a : all) {
                    log(sender, "  " + a.getName() + (a.isReady()
                            ? " - 已设置（" + a.getWorld().getName() + " " + a.volume() + " 格）"
                            : " - 未设置"));
                }
                for (GameManager m : plugin.rooms().allManagers()) {
                    if (!m.roomScoped()) {
                        continue;
                    }
                    log(sender, "  房间实例 gtb/" + m.roomKey() + " 场地 " + m.arenaLabel()
                            + " 状态 " + m.getPhase() + " " + m.getPlayerCount() + " 人");
                }
            }
            case "info" -> {
                for (GameManager m : plugin.rooms().allManagers()) {
                    log(sender, "gtb/" + m.roomKey() + " 场地 " + m.arenaLabel()
                            + " 状态 " + m.getPhase() + " " + m.getPlayerCount() + " 人");
                }
            }
            default -> log(sender, "控制台只支持 start / stop / reload / list / info（其它命令需要玩家身份）。");
        }
    }

    /** 控制台输出的统一出口（前缀与游戏内一致；去掉 & 颜色代码，控制台不认）。 */
    private void log(CommandSender sender, String msg) {
        if (sender != null) {
            sender.sendMessage("[建筑猜猜乐] " + (msg == null ? "" : msg.replaceAll("&[0-9a-fk-orA-FK-OR]", "")));
        }
    }

    // ---------- 战绩 ----------

    /** /gtb stats [玩家]：查看第一名次数；/gtb stats reset：OP 清空记录。 */
    private void stats(Player p, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
            if (!p.isOp() && !p.hasPermission("gtb.admin")) {
                Chat.send(p, "&c只有管理员（OP）可以清空记录。");
                return;
            }
            plugin.rooms().defaultManager().resetFirsts();
            Chat.send(p, "&a已清空全部第一名记录。");
            return;
        }
        org.bukkit.entity.Player target = p;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                Chat.send(p, "&c玩家不在线。");
                return;
            }
        }
        Chat.send(p, "&6" + target.getName() + " 的建筑猜猜乐战绩：&e获得第一名 &f"
                + plugin.rooms().defaultManager().getFirsts(target.getUniqueId()) + " &6次");
    }

    // ---------- 子命令 ----------

    private void setup(Player p, String[] args) {
        if (!checkAdmin(p, true)) {
            return;
        }
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "wand";
        String target = plugin.getArenas().getEditingName();

        // /gtb setup <场地名>：与其它游戏一致——不存在则创建，存在则进入编辑，并进入建场模式
        if (args.length > 1 && !SETUP_SUBS.contains(sub)) {
            String name = args[1];
            var mgr = plugin.getArenas();
            if (!mgr.hasArena(name)) {
                if (mgr.createArena(name) == null) {
                    Chat.send(p, "&c创建失败：名称需 1~20 个中英文/数字/下划线/短横线，且不能与现有场地重名。");
                    return;
                }
                Chat.send(p, "&a已创建场地「&e" + name + "&a」并进入选区模式。");
            } else {
                mgr.setEditing(name);
                Chat.send(p, "&a正在编辑场地「&e" + name + "&a」：重新选两个角点会覆盖保存。");
            }
            startWand(p, name);
            Chat.send(p, "&7左键/右键选两个角点后输入 &e/gtb setup save&7 保存。");
            return;
        }

        switch (sub) {
            case "wand" -> {
                if (plugin.getSetupMode().isEditing(p)) {
                    plugin.exitSetup(p);
                    Chat.send(p, "&c已退出选区模式。");
                    return;
                }
                startWand(p);
                Chat.send(p, "&a当前选区将保存到场地「&e" + (target == null ? "默认场地" : target)
                        + "&a」，可用 &e/gtb arena edit <名称>&a 切换编辑目标。");
            }
            case "save" -> plugin.saveSetupArena(p);
            case "cancel" -> {
                // 公共库建场模式负责还原背包；这里保持原有提示
                plugin.exitSetup(p);
                Chat.send(p, "&c已取消选区。");
            }
            case "clear" -> {
                if (plugin.rooms().anyPlaying()) {
                    Chat.send(p, "&c游戏进行中，无法删除场地。");
                    return;
                }
                if (target == null || !plugin.getArenas().removeArena(target)) {
                    Chat.send(p, "&c没有可删除的场地。");
                    return;
                }
                Chat.send(p, "&a已删除场地「&e" + target + "&a」的配置与快照。管理员可用 &e/gtb arena add <名称>&a 重新添加。");
            }
            case "info" -> info(p);
            default -> Chat.send(p, "&c用法：/gtb setup [wand|save|cancel|clear|info]");
        }
    }

    // ---------- 场地管理（多场地） ----------

    /** /gtb list：列出全部场地（与 /gtb arena list 相同）。 */
    private void arenaList(Player p) {
        if (!checkAdmin(p, true)) {
            return;
        }
        var mgr = plugin.getArenas();
        java.util.List<Arena> all = mgr.getAll();
        if (all.isEmpty()) {
            Chat.send(p, "&7还没有任何场地，使用 &e/gtb setup <场地名>&7 添加第一个场地。");
            return;
        }
        Chat.send(p, "&6===== 场地列表（共 " + all.size() + " 个） =====");
        String active = mgr.getActiveName();
        String editing = mgr.getEditingName();
        for (Arena a : all) {
            StringBuilder line = new StringBuilder();
            if (a.getName().equals(active)) {
                line.append("&a[使用中] ");
            } else if (a.getName().equals(editing)) {
                line.append("&e[编辑中] ");
            }
            line.append("&e").append(a.getName());
            line.append(a.isReady()
                    ? "&7 - &a已设置&7（" + a.getWorld().getName() + " " + a.volume() + " 格）"
                    : "&7 - &c未设置&7（/gtb setup " + a.getName() + " 选区保存）");
            Chat.sendRaw(p, line.toString());
        }
        Chat.sendRaw(p, "&7切换生效场地：/gtb arena select <名称>");
    }

    /** /gtb remove <名称>：删除场地（与 /gtb arena remove 相同）。 */
    private void arenaRemove(Player p, String[] args) {
        if (!checkAdmin(p, true)) {
            return;
        }
        if (args.length < 2) {
            Chat.send(p, "&c用法：/gtb remove <场地名>");
            return;
        }
        if (plugin.rooms().anyPlaying()) {
            Chat.send(p, "&c游戏进行中，无法删除场地。");
            return;
        }
        if (!plugin.getArenas().removeArena(args[1])) {
            Chat.send(p, "&c找不到场地 &e" + args[1] + "&c，可用 &e/gtb list&c 查看。");
            return;
        }
        Chat.send(p, "&a已删除场地「&e" + args[1] + "&a」的配置与快照。");
        plugin.getGame().reportArenaToHub(); // 刷新中央菜单场地列表
    }

    /** /gtb arena list|add|remove|edit|select|info：多场地管理（仅管理员）。 */
    private void arena(Player p, String[] args) {
        if (!checkAdmin(p, true)) {
            return;
        }
        var mgr = plugin.getArenas();
        if (args.length < 2) {
            Chat.send(p, "&c用法：/gtb arena list | add <名称> | edit <名称> | select <名称> | remove <名称> | rename <旧名> <新名> | info [名称]");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                java.util.List<Arena> all = mgr.getAll();
                if (all.isEmpty()) {
                    Chat.send(p, "&7还没有任何场地，使用 &e/gtb arena add <名称>&7 添加第一个场地。");
                    return;
                }
                Chat.send(p, "&6===== 场地列表（共 " + all.size() + " 个） =====");
                String active = mgr.getActiveName();
                String editing = mgr.getEditingName();
                for (Arena a : all) {
                    StringBuilder line = new StringBuilder();
                    if (a.getName().equals(active)) {
                        line.append("&a[使用中] ");
                    } else if (a.getName().equals(editing)) {
                        line.append("&e[编辑中] ");
                    }
                    line.append("&e").append(a.getName());
                    line.append(a.isReady()
                            ? "&7 - &a已设置&7（" + a.getWorld().getName() + " " + a.volume() + " 格）"
                            : "&7 - &c未设置&7（/gtb arena edit " + a.getName() + " 选区保存）");
                    Chat.sendRaw(p, line.toString());
                }
                Chat.sendRaw(p, "&7切换生效场地：/gtb arena select <名称>");
            }
            case "add" -> {
                if (args.length < 3) {
                    Chat.send(p, "&c用法：/gtb arena add <名称>");
                    return;
                }
                String name = args[2];
                String created = mgr.createArena(name);
                if (created == null) {
                    Chat.send(p, "&c创建失败：名称需 1~20 个中英文/数字/下划线/短横线，且不能与现有场地重名。");
                    return;
                }
                startWand(p, name);
                Chat.send(p, "&a已创建场地「&e" + name + "&a」并进入选区模式：左键/右键选两个角点，然后 &e/gtb setup save&a 保存。");
            }
            case "remove", "del", "delete" -> {
                if (args.length < 3) {
                    Chat.send(p, "&c用法：/gtb arena remove <名称>");
                    return;
                }
                if (plugin.rooms().anyPlaying()) {
                    Chat.send(p, "&c游戏进行中，无法删除场地。");
                    return;
                }
                if (!mgr.removeArena(args[2])) {
                    Chat.send(p, "&c找不到场地 &e" + args[2] + "&c，可用 &e/gtb arena list&c 查看。");
                    return;
                }
                Chat.send(p, "&a已删除场地「&e" + args[2] + "&a」的配置与快照。");
                plugin.getGame().reportArenaToHub(); // 刷新中央菜单场地列表
            }
            case "rename" -> {
                if (args.length < 4) {
                    Chat.send(p, "&c用法：/gtb arena rename <旧名称> <新名称>");
                    return;
                }
                if (plugin.rooms().anyPlaying()) {
                    Chat.send(p, "&c游戏进行中，无法重命名场地。");
                    return;
                }
                String oldName = args[2];
                String newName = args[3];
                if (oldName.equals(newName)) {
                    Chat.send(p, "&c新旧名称相同。");
                    return;
                }
                if (!mgr.renameArena(oldName, newName)) {
                    Chat.send(p, "&c改名失败：场地不存在，或新名称需 1~20 个中英文/数字/下划线/短横线且不与现有场地重名。");
                    return;
                }
                Chat.send(p, "&a已将场地「&e" + oldName + "&a」改名为「&e" + newName + "&a」，中央菜单已同步。");
                plugin.getGame().reportArenaToHub(); // 新名上报中央菜单（旧名已在 renameArena 中移除）
            }
            case "edit" -> {
                if (args.length < 3) {
                    Chat.send(p, "&c用法：/gtb arena edit <名称>");
                    return;
                }
                if (!mgr.hasArena(args[2])) {
                    Chat.send(p, "&c找不到场地 &e" + args[2] + "&c，可用 &e/gtb arena list&c 查看。");
                    return;
                }
                mgr.setEditing(args[2]);
                startWand(p, args[2]);
                Chat.send(p, "&a正在编辑场地「&e" + args[2] + "&a」：重新选两个角点后 &e/gtb setup save&a 会覆盖保存。");
            }
            case "select", "use" -> {
                if (args.length < 3) {
                    Chat.send(p, "&c用法：/gtb arena select <名称>");
                    return;
                }
                if (plugin.rooms().anyPlaying()) {
                    Chat.send(p, "&c游戏进行中，无法切换生效场地。");
                    return;
                }
                if (!mgr.setActive(args[2])) {
                    Chat.send(p, "&c切换失败：场地不存在或尚未设置（先 /gtb arena edit 选区保存）。");
                    return;
                }
                Chat.send(p, "&a已将生效场地切换为「&e" + args[2] + "&a」，新玩家与下一局将使用该场地。");
                plugin.getGame().reportArenaToHub(); // 刷新中央菜单场地列表
            }
            case "info" -> {
                String name = args.length > 2 ? args[2] : mgr.getActiveName();
                Arena a = name == null ? null : mgr.getArena(name);
                if (a == null) {
                    Chat.send(p, "&c找不到场地 &e" + (name == null ? "（尚未设置生效场地）" : name));
                    return;
                }
                infoArena(p, a);
            }
            default -> Chat.send(p, "&c用法：/gtb arena list | add <名称> | edit <名称> | select <名称> | remove <名称> | rename <旧名> <新名> | info [名称]");
        }
    }

    /** 进入公共库建场模式（备份背包并发放木棍/纸/屏障工具）。 */
    private void startWand(Player p) {
        startWand(p, null);
    }

    /** 进入公共库建场模式；arenaName 为 null 时使用当前编辑目标场地。 */
    private void startWand(Player p, String arenaName) {
        plugin.enterSetup(p, arenaName);
        Chat.send(p, "&a已进入选区模式：左键点击场地一角，右键点击对角，然后输入 &e/gtb setup save&a 保存。");
    }

    private void infoArena(Player p, Arena a) {
        Chat.send(p, "&6===== 场地「" + a.getName() + "」 =====");
        Chat.sendRaw(p, "&7状态: " + (a.isReady() ? "&a已设置" : "&c未设置"));
        Chat.sendRaw(p, "&7是否生效: " + (a.getName().equals(plugin.getArenas().getActiveName()) ? "&a是（游戏使用该场地）" : "&7否（/gtb arena select " + a.getName() + " 切换）"));
        if (a.isReady()) {
            Chat.sendRaw(p, "&7世界: &f" + a.getWorld().getName());
            Chat.sendRaw(p, "&7范围: &f" + a.getMin().getBlockX() + "," + a.getMin().getBlockY() + "," + a.getMin().getBlockZ()
                    + " → " + a.getMax().getBlockX() + "," + a.getMax().getBlockY() + "," + a.getMax().getBlockZ());
            Chat.sendRaw(p, "&7体积: &f" + a.volume() + " 格");
            Chat.sendRaw(p, "&7当前地板: &f" + (a.getFloor() == null ? "橡木原木（默认）" : a.getFloor().name()));
            Chat.sendRaw(p, "&7建造起点: &f" + a.getBuildStart().getBlockX() + ", "
                    + a.getBuildStart().getBlockY() + ", " + a.getBuildStart().getBlockZ());
        }
        Chat.sendRaw(p, "&7编辑此场地：/gtb arena edit " + a.getName() + "（选区后 /gtb setup save 覆盖保存）");
    }

    private void word(Player p, String[] args) {
        if (!checkAdmin(p, true)) {
            return;
        }
        WordManager wm = plugin.getWordManager();
        if (args.length < 2) {
            Chat.send(p, "&c用法：/gtb word add <词> [分类] | /gtb word remove <词> | /gtb word list [分类]");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "add" -> {
                if (args.length < 3) {
                    Chat.send(p, "&c用法：/gtb word add <词> [分类]");
                    return;
                }
                String word = args[2];
                if (word.length() > 12) {
                    Chat.send(p, "&c词语过长（最多 12 个字）。");
                    return;
                }
                String category = args.length > 3 ? args[3] : "其他";
                if (wm.addWord(word, category)) {
                    Chat.send(p, "&a已添加词语 &e" + word + "&a（分类：&e" + category + "&a）。");
                } else {
                    Chat.send(p, "&c词语已存在。");
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    Chat.send(p, "&c用法：/gtb word remove <词>");
                    return;
                }
                if (wm.removeWord(args[2])) {
                    Chat.send(p, "&a已删除词语 &e" + args[2] + "&a。");
                } else {
                    Chat.send(p, "&c词库中找不到该词语。");
                }
            }
            case "list" -> {
                Map<String, List<String>> all = wm.getAll();
                if (args.length > 2) {
                    List<String> list = all.get(args[2]);
                    if (list == null || list.isEmpty()) {
                        Chat.send(p, "&c该分类不存在或为空。");
                        return;
                    }
                    Chat.send(p, "&6分类「" + args[2] + "」&7(" + list.size() + " 词)&f: " + String.join("、", list));
                    return;
                }
                int total = wm.totalCount();
                Chat.send(p, "&6词库共 " + total + " 个词：" );
                for (Map.Entry<String, List<String>> e : all.entrySet()) {
                    Chat.sendRaw(p, "&7- &e" + e.getKey() + "&7 (" + e.getValue().size() + ")&f: "
                            + String.join("、", e.getValue().subList(0, Math.min(12, e.getValue().size())))
                            + (e.getValue().size() > 12 ? " …" : ""));
                }
            }
            default -> Chat.send(p, "&c用法：/gtb word add <词> [分类] | /gtb word remove <词> | /gtb word list [分类]");
        }
    }

    /** /gtb theme <1~6|题目名>：建造者在开局前选择本轮题目（6=自创词）。 */
    private void theme(Player p, String[] args) {
        if (args.length < 2) {
            Chat.send(p, "&c用法：/gtb theme <1|2|3|4|5|6|题目名>");
            return;
        }
        GameManager game = stateOf(p);
        if (!game.selectTheme(p, args[1]) && game.getPhase() != GameManager.Phase.PLAYING) {
            Chat.send(p, "&c当前不是选择题目的阶段。");
        }
    }

    /** /gtb floor <1~9|名称|npc|sign>：建造者打开地板商店或直接切换地板；OP 可生成地板商店 NPC/告示牌。 */
    private void floor(Player p, String[] args) {
        if (args.length >= 2) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("npc") || sub.equals("sign")) {
                if (!checkAdmin(p, true)) {
                    return;
                }
                if (sub.equals("npc")) {
                    plugin.placeFloorNpc(p);
                } else {
                    plugin.placeFloorSign(p);
                }
                return;
            }
        }
        GameManager game = stateOf(p);
        if (!game.isInGame(p.getUniqueId()) || !game.isBuilder(p.getUniqueId())) {
            Chat.send(p, "&c只有当前建造者能更换地板！");
            return;
        }
        if (args.length < 2) {
            game.arena().openFloorMenu(p);
            return;
        }
        game.arena().applyFloorSelection(p, args[1]);
    }

    /** /gtb blacklist add|remove|list <玩家> | word add|remove|list <词>：管理自创词黑名单（管理员）。 */
    private void blacklist(Player p, String[] args) {
        if (!checkAdmin(p, true)) {
            return;
        }
        if (args.length < 2) {
            Chat.send(p, "&c用法：/gtb blacklist add <玩家> | /gtb blacklist remove <玩家> | /gtb blacklist list | /gtb blacklist word add|remove|list [词]");
            return;
        }
        GameManager game = stateOf(p);
        if (args[1].equalsIgnoreCase("word")) {
            // ---- 词黑名单（评分过低被拉黑的自创词） ----
            var wm = plugin.getWordManager();
            if (args.length < 3) {
                Chat.send(p, "&c用法：/gtb blacklist word add <词> | /gtb blacklist word remove <词> | /gtb blacklist word list");
                return;
            }
            switch (args[2].toLowerCase(java.util.Locale.ROOT)) {
                case "add" -> {
                    if (args.length < 4) {
                        Chat.send(p, "&c用法：/gtb blacklist word add <词>");
                        return;
                    }
                    if (wm.addSelfmadeBannedWord(args[3])) {
                        Chat.send(p, "&a已将自创词 &e" + args[3] + "&a 加入词黑名单。");
                    } else {
                        Chat.send(p, "&c该词已在词黑名单中。");
                    }
                }
                case "remove" -> {
                    if (args.length < 4) {
                        Chat.send(p, "&c用法：/gtb blacklist word remove <词>");
                        return;
                    }
                    if (wm.removeSelfmadeBannedWord(args[3])) {
                        Chat.send(p, "&a已把自创词 &e" + args[3] + "&a 移出词黑名单。");
                    } else {
                        Chat.send(p, "&c词黑名单中找不到该词语。");
                    }
                }
                case "list" -> {
                    java.util.List<String> banned = wm.getSelfmadeBannedWords();
                    if (banned.isEmpty()) {
                        Chat.send(p, "&7自创词黑名单（词）为空。");
                        return;
                    }
                    Chat.send(p, "&6自创词黑名单（词，共 " + banned.size() + " 个）：");
                    Chat.sendRaw(p, "&f" + String.join("、", banned));
                }
                default -> Chat.send(p, "&c用法：/gtb blacklist word add <词> | /gtb blacklist word remove <词> | /gtb blacklist word list");
            }
            return;
        }
        switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "add" -> {
                if (args.length < 3) {
                    Chat.send(p, "&c用法：/gtb blacklist add <玩家>");
                    return;
                }
                Player t = Bukkit.getPlayerExact(args[2]);
                if (t == null) {
                    Chat.send(p, "&c找不到在线玩家 &e" + args[2]);
                    return;
                }
                game.addSelfmadeBan(t.getUniqueId());
                Chat.send(p, "&a已将 &e" + t.getName() + "&a 加入自创词黑名单。");
            }
            case "remove" -> {
                if (args.length < 3) {
                    Chat.send(p, "&c用法：/gtb blacklist remove <玩家>");
                    return;
                }
                Player t = Bukkit.getPlayerExact(args[2]);
                if (t == null) {
                    Chat.send(p, "&c找不到在线玩家 &e" + args[2]);
                    return;
                }
                game.removeSelfmadeBan(t.getUniqueId());
                Chat.send(p, "&a已将 &e" + t.getName() + "&a 移出自创词黑名单。");
            }
            case "list" -> {
                java.util.List<String> bans = game.getSelfmadeBans();
                if (bans.isEmpty()) {
                    Chat.send(p, "&7自创词黑名单为空。");
                    return;
                }
                Chat.send(p, "&6自创词黑名单（" + bans.size() + " 人）：");
                for (String id : bans) {
                    try {
                        Player pp = Bukkit.getPlayer(java.util.UUID.fromString(id));
                        Chat.send(p, "&e- &f" + (pp != null ? pp.getName() : id));
                    } catch (IllegalArgumentException ex) {
                        Chat.send(p, "&e- &f" + id + " &7(已离线)");
                    }
                }
            }
            default -> Chat.send(p, "&c用法：/gtb blacklist add <玩家> | /gtb blacklist remove <玩家> | /gtb blacklist list");
        }
    }

    private void info(Player p) {
        GameManager game = stateOf(p);
        Arena arena = game.arena();
        String activeName = arena.getName();
        int arenaCount = plugin.getArenas().getAll().size();
        Chat.send(p, "&6===== 建筑猜猜乐 · 信息 =====");
        Chat.sendRaw(p, "&7插件版本: &f" + plugin.getPluginMeta().getVersion());
        Chat.sendRaw(p, "&7运行时状态: &f" + game.roomKey()
                + (game.roomScoped() ? "（房间实例）" : "（默认实例）"));
        Chat.sendRaw(p, "&7当前状态: &f" + (game.getPhase() == GameManager.Phase.PLAYING
                ? "&a进行中（" + game.getPlayerCount() + " 人）"
                : "&e等待开始（" + game.getPlayerCount() + " 人）"));
        Chat.sendRaw(p, "&7场地: &f" + (arena.isReady()
                ? "&a「" + activeName + "」已设置（" + arena.getWorld().getName() + " " + arena.volume() + " 格，共 "
                + arenaCount + " 个场地）"
                : "&c未设置，使用 /gtb arena add <名称>"));
        Chat.sendRaw(p, "&7词库: &f" + plugin.getWordManager().totalCount() + " 个词 / "
                + plugin.getWordManager().getAll().size() + " 个分类");
        Chat.sendRaw(p, "&7玩法: &f每轮 1 人建造，其他人聊天猜词，轮流当建造者");
    }

    private void help(Player p) {
        Chat.send(p, "&6===== 建筑猜猜乐 · 帮助 =====");
        Chat.sendRaw(p, "&e/gtb join&7 - 加入游戏队列");
        Chat.sendRaw(p, "&e/gtb leave&7 - 离开游戏");
        Chat.sendRaw(p, "&e/gtb stats [玩家]&7 - 查询获得第一名的次数");
        Chat.sendRaw(p, "&e/gtb start&7 - 开始游戏（至少 " + plugin.getConfig().getInt("game.min-players", 3) + " 人，需要 gtb.admin）");
        if (checkAdmin(p, false)) {
            Chat.sendRaw(p, "&e/gtb stop&7 - 强制结束游戏（管理员）");
            Chat.sendRaw(p, "&e/gtb list&7 - 查看全部场地（管理员）");
            Chat.sendRaw(p, "&e/gtb setup <场地名>&7 - 添加新场地或编辑现有场地并选区（管理员）");
            Chat.sendRaw(p, "&e/gtb setup save&7 - 保存选区到当前编辑的场地并生成快照（管理员）");
            Chat.sendRaw(p, "&e/gtb remove <场地名>&7 - 删除指定场地（管理员）");
            Chat.sendRaw(p, "&e/gtb arena select <名称>&7 - 切换游戏使用的生效场地（管理员）");
            Chat.sendRaw(p, "&e/gtb arena info [名称]&7 - 查看场地详情（管理员）");
            Chat.sendRaw(p, "&e/gtb joinsign&7 - 在面前放置加入告示牌（管理员）");
            Chat.sendRaw(p, "&e/gtb stats reset&7 - 清空全部第一名记录（管理员）");
            Chat.sendRaw(p, "&e/gtb word add <词> [分类]&7 - 添加词语（管理员）");
            Chat.sendRaw(p, "&e/gtb word remove <词>&7 - 删除词语（管理员）");
            Chat.sendRaw(p, "&e/gtb word list&7 - 查看词库（管理员）");
            Chat.sendRaw(p, "&e/gtb lobby&7 - 把当前位置设为等待点（管理员，玩家加入后在此等待开局）");
            Chat.sendRaw(p, "&e/gtb reload&7 - 重载配置（管理员）");
            Chat.sendRaw(p, "&e/gtb blacklist add <玩家>&7 - 加入自创词黑名单（管理员）");
            Chat.sendRaw(p, "&e/gtb blacklist remove <玩家>&7 - 移出自创词黑名单（管理员）");
            Chat.sendRaw(p, "&e/gtb blacklist list&7 - 查看自创词黑名单（管理员）");
            Chat.sendRaw(p, "&e/gtb blacklist word add/remove/list <词>&7 - 管理自创词词黑名单（管理员）");
        }
        Chat.sendRaw(p, "&e/gtb info&7 - 查看插件状态");
        Chat.sendRaw(p, "&e/gtb theme <编号>&7 - 建造者选择本轮题目（6=自创词，或直接点击选项）");
        Chat.sendRaw(p, "&e/gtb floor&7 - 打开地板商店（或右键大厅的「地板商店」NPC）");
        Chat.sendRaw(p, "&7玩法提示：建造者开局从 5 个题目或「自创词」中选一个，用创造模式搭出来，其他人聊天猜词！");
    }

    private boolean checkAdmin(Player p, boolean sendMsg) {
        boolean ok = p.isOp() || p.hasPermission("gtb.admin");
        if (!ok && sendMsg) {
            Chat.send(p, "&c你没有权限（需要 gtb.admin）。");
        }
        return ok;
    }

    // ---------- Tab 补全 ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUB_COMMANDS, args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "setup" -> {
                    // 输入是保留字前缀时补全保留字（wand/save/…），否则补全场地名
                    List<String> subs = filter(SETUP_SUBS, args[1]);
                    if (!subs.isEmpty()) {
                        yield subs;
                    }
                    List<String> names = new ArrayList<>();
                    for (Arena a : plugin.getArenas().getAll()) {
                        names.add(a.getName());
                    }
                    yield filter(names, args[1]);
                }
                case "arena" -> filter(ARENA_SUBS, args[1]);
                case "remove", "del", "delete" -> {
                    List<String> names = new ArrayList<>();
                    for (Arena a : plugin.getArenas().getAll()) {
                        names.add(a.getName());
                    }
                    yield filter(names, args[1]);
                }
                case "word" -> filter(WORD_SUBS, args[1]);
                case "blacklist" -> filter(java.util.List.of("add", "remove", "list", "word"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("blacklist") && args[1].equalsIgnoreCase("word")) {
            return filter(java.util.List.of("add", "remove", "list"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("blacklist") && args[1].equalsIgnoreCase("word")) {
            return List.of();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("arena")) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("add")) {
                return List.of();
            }
            if (sub.equals("remove") || sub.equals("del") || sub.equals("delete")
                    || sub.equals("edit") || sub.equals("select") || sub.equals("use") || sub.equals("info") || sub.equals("rename")) {
                List<String> names = new ArrayList<>();
                for (Arena a : plugin.getArenas().getAll()) {
                    names.add(a.getName());
                }
                return filter(names, args[2]);
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("rename")) {
            List<String> names = new ArrayList<>();
            for (Arena a : plugin.getArenas().getAll()) {
                names.add(a.getName());
            }
            return filter(names, args[3]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("word") && args[1].equalsIgnoreCase("list")) {
            return filter(new ArrayList<>(plugin.getWordManager().getAll().keySet()), args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> candidates, String prefix) {
        List<String> out = new ArrayList<>();
        for (String s : candidates) {
            if (s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(s);
            }
        }
        return out;
    }
}
