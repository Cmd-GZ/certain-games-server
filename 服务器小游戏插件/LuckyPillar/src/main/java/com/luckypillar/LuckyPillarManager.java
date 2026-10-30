package com.luckypillar;

import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.data.PlayerStats;
import com.hub.util.Chat;
import com.hub.util.LiveSidebar;
import com.hub.util.Top3;
import com.hub.util.StartCountdown;
import com.hub.util.SetupMode;
import com.hub.util.WaitCountdown;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 幸运之柱核心：状态机、开局生成柱阵、世界边境缩圈、天灾调度、结算、播报文案（设计稿 §1、§3、§4、§6）。
 * <p>
 * 一局：等待（公共库 WaitCountdown）→ 开局（清场、按人数生成柱阵、传送、空背包）→ 缩圈期
 * → 终局 TNT 雨 → 结算 8 秒 → 回主城。
 */
public class LuckyPillarManager {

    /** HubCore 里登记的游戏显示名（必须与 plugin.yml 的 minigame.name / HubCore games.name 一致）。 */
    public static final String GAME = "幸运之柱";

    /** 箭雨伤害（设计稿 §11-3：写死在常量里，方便后续调）。 */
    private static final double ARROW_RAIN_DAMAGE = 4.0;
    /** 箭矢在场内存活多少 tick 后强制清理（"命中/落地后消失"的兜底）。 */
    private static final int ARROW_LIFE_TICKS = 200;
    /** 苦力怕雨的引信（tick）：从 +30 格落下约 30 tick 落地，留一点余量在地上炸。 */
    private static final int CREEPER_FUSE_TICKS = 40;
    /** 分批清场每 tick 检查多少方块（约 9000 → 12 人场 0.5 秒内清完）。 */
    private static final int CLEAR_BUDGET_PER_TICK = 9000;
    /** 结算展示秒数（设计稿 §1）。 */
    private static final int SETTLE_SECONDS = 8;
    /** 等待室道具槽位：8 = 退出床，4 = OP 开始纸（与其它游戏一致）。 */
    private static final int LEAVE_BED_SLOT = 8;
    private static final int START_SLOT = 4;

    private static final Random RANDOM = new Random();

    /** 箭雨开场白（每次随机一句，别太长）。 */
    private static final String[] ARROW_LINES = {
            "&c🏹 箭雨来袭！&7 抬头看天，低头看命——箭可不认人。",
            "&c🏹 天上掉箭了！&7 再站着发呆，你就成刺猬了。",
            "&c🏹 箭雨！&7 这一波免费，运费由你身上的洞来付。",
            "&c🏹 箭雨来了！&7 跑起来——命中率是 100% 的随机。",
    };

    /** 苦力怕雨开场白（每次随机一句）。 */
    private static final String[] CREEPER_LINES = {
            "&c💥 苦力怕雨！&7 它们摔不死，还专挑你脚边炸。",
            "&c💥 天降苦力怕！&7 听见「嘶——」的时候，你只剩两秒。",
            "&c💥 苦力怕雨！&7 一群不请自来的绿色房客，炸完还不赔方块。",
            "&c💥 苦力怕雨来了！&7 它们不拆方块，只拆你。",
    };

    private final LuckyPillarPlugin plugin;
    private final LiveSidebar liveSidebar;
    private final Map<String, LuckyPillarArena> arenas = new LinkedHashMap<>();
    private final Map<UUID, String> playerArena = new LinkedHashMap<>();
    private final Map<String, WaitCountdown> waits = new LinkedHashMap<>();
    private final Map<String, MatchTasks> tasks = new LinkedHashMap<>();
    /** 世界名 -> 开赛前的世界边境设置（局末/关服还原）。 */
    private final Map<String, BorderSnapshot> borders = new LinkedHashMap<>();
    /** 命令/配置模式层"正在编辑的场"（命令里的 center/lobby/save/start/stop 用得上）。 */
    private final Map<UUID, String> editing = new LinkedHashMap<>();
    /** 圈外方块被吃掉时的低频音效节流（场名 -> 上次播放时刻）。 */
    private final Map<String, Long> lastCrumble = new LinkedHashMap<>();

    /** 公共库：OP 建场模式（备份背包/工具/还原）。 */
    private final SetupMode setup;
    /** 等待室"开始游戏"纸的标记（只认本插件发的纸，不误判玩家自己的纸）。 */
    private final NamespacedKey startPaperKey = new NamespacedKey("luckypillar", "start-paper");
    private final Map<UUID, LuckyPillarArena> setupTargets = new LinkedHashMap<>();

    private YamlConfiguration data;
    private BukkitTask voidTask;

    public LuckyPillarManager(LuckyPillarPlugin plugin) {
        this.plugin = plugin;
        this.liveSidebar = new LiveSidebar(plugin);
        this.setup = new SetupMode(plugin, "lp-setup-tool");
    }

    // ---------- 生命周期 ----------

    public void load() {
        startCountdown();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("arenas");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                LuckyPillarArena a = LuckyPillarArena.load(name, sec.getConfigurationSection(name));
                arenas.put(name, a);
                if (!a.isReady()) {
                    plugin.getLogger().warning("场地 " + name + " 还没配好（缺 center / lobby 或世界未加载）。");
                }
            }
        }
        // 玩家记录（唯一文件 players.yml，命名空间 luckypillar）
        data = PlayerStats.load("luckypillar", new File(plugin.getDataFolder(), "data.yml"));
        LuckyPillarLoot.configure(plugin.getConfig().getDouble("loot-gear-chance", 0.3),
                plugin.getConfig().getDouble("loot-enchant-chance", 0.35),
                plugin.getConfig().getInt("loot-enchant-max-level", 1));
        plugin.getLogger().info("幸运之柱已加载：场地 " + arenas.size() + " 个，物品池 " + LuckyPillarLoot.poolSize()
                + " 件（其中低价值 " + LuckyPillarLoot.lowValueCount() + " 件，权重 1/4；"
                + "武器/工具/装备 " + LuckyPillarLoot.gearCount() + " 件，命中率 "
                + Math.round(LuckyPillarLoot.gearChance() * 100) + "%，附魔率 "
                + Math.round(LuckyPillarLoot.enchantChance() * 100) + "%，附魔上限 "
                + LuckyPillarLoot.enchantMaxLevel() + " 级）。");

        // 启动后把已有场地上报给 HubCore 中央菜单（延迟等 HubCore 就绪）
        Bukkit.getScheduler().runTaskLater(plugin, this::reportAllArenas, 40L);
        startVoidTask();
    }

    /** 关服：取消所有任务、还原边境、清场、落盘。 */
    public void shutdown() {
        liveSidebar.hideAll();
        if (voidTask != null) {
            voidTask.cancel();
            voidTask = null;
        }
        for (LuckyPillarArena a : arenas.values()) {
            if (a.getState() == LuckyPillarArena.State.WAITING) {
                continue;
            }
            MatchTasks t = tasks.get(a.getName());
            if (t != null) {
                t.cancelAll();
            }
            broadcast(a, "&c服务器关闭，本局终止。");
            // 关服等不到分批清场，直接同步清完（不留残留方块）
            a.clearAllNow();
            a.setClean(true);
            restoreBorder(a);
        }
        for (UUID id : new ArrayList<>(playerArena.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.setGameMode(GameMode.SURVIVAL);
            }
            HubAPI.clearGame(id);
        }
        playerArena.clear();
        editing.clear();
        for (LuckyPillarArena a : arenas.values()) {
            a.getPlayers().clear();
            a.getEliminated().clear();
            a.getElimOrder().clear();
            a.getOutSince().clear();
            a.setState(LuckyPillarArena.State.WAITING);
        }
        saveAll();
    }

    /**
     * 声明地盘（{@code docs/框架不变量.md} 的 I2）：圆形场地转成外接 AABB。
     * 只在内存里读字段，不碰配置；还没建场的场地会被 {@code Regions} 静默跳过。
     */
    public void publishRegions() {
        com.hub.region.Regions.begin("luckypillar");
        arenas.forEach((name, a) -> {
            // 幸运之柱的场地半径是「按人数动态算」的（ring-radius: 0 = 自动，clamp 5~32），
            // 静态配置里没有固定值。地盘声明要保守：拿不到就按上限 32 声明（宁可偏大）。
            double r = a.getRadius() > 0 ? a.getRadius() : 32;
            com.hub.region.Regions.disc("luckypillar", name, a.getCenter(), r,
                    com.hub.region.Regions.DEFAULT_DISC_HEIGHT);
        });
    }

    public void saveAll() {
        publishRegions();   // 场地可能刚被改过，重新声明（I2）
        ConfigurationSection sec = plugin.getConfig().createSection("arenas");
        for (Map.Entry<String, LuckyPillarArena> e : arenas.entrySet()) {
            e.getValue().save(sec.createSection(e.getKey()));
        }
        plugin.saveConfig();
        saveData();
        reportAllArenas();
    }

    /**
     * 把当前所有场地上报给 HubCore 中央菜单。
     * <p>必须跟着"配置落盘"一起做（create / setup 保存 / remove / rename），
     * 否则运行中新建的场在中央菜单里根本不存在（点进去一块羊毛都没有）。
     */
    private void reportAllArenas() {
        if (!plugin.isEnabled()) {
            return;
        }
        for (LuckyPillarArena a : arenas.values()) {
            reportArena(a);
        }
    }

    private void reportArena(LuckyPillarArena a) {
        boolean playing = a.getState() != LuckyPillarArena.State.WAITING;
        String extra = null;
        if (playing) {
            extra = a.isFinalPhase() ? "终局 · " + a.getPlayers().size() + " 人" : "缩圈中 · " + a.getPlayers().size() + " 人";
        }
        HubAPI.setArena(GAME, a.getName(),
                playing ? GameState.PLAYING : GameState.WAITING, a.getPlayers().size(), extra);
        HubAPI.setArenaSpectator(GAME, a.getName(), a.getCenter());
    }

    // ---------- 场地增删改 ----------

    public LuckyPillarArena getArena(String name) {
        return arenas.get(name);
    }

    public Map<String, LuckyPillarArena> getArenas() {
        return arenas;
    }

    public boolean createArena(String name) {
        if (!validName(name) || arenas.containsKey(name)) {
            return false;
        }
        arenas.put(name, new LuckyPillarArena(name));
        saveAll();
        return true;
    }

    public boolean removeArena(String name) {
        LuckyPillarArena a = arenas.get(name);
        if (a == null) {
            return false;
        }
        if (a.getState() != LuckyPillarArena.State.WAITING || !a.getPlayers().isEmpty() || !a.getEliminated().isEmpty()) {
            return false;
        }
        arenas.remove(name);
        WaitCountdown w = waits.remove(name);
        if (w != null) {
            w.cancel();
        }
        plugin.getConfig().set("arenas." + name, null);
        plugin.saveConfig();
        HubAPI.removeArena(GAME, name);
        return true;
    }

    public boolean renameArena(String oldName, String newName) {
        if (oldName == null || oldName.equals(newName) || !validName(newName) || arenas.containsKey(newName)) {
            return false;
        }
        LuckyPillarArena a = arenas.get(oldName);
        if (a == null || a.getState() != LuckyPillarArena.State.WAITING) {
            return false;
        }
        arenas.remove(oldName);
        a.setName(newName);
        arenas.put(newName, a);
        for (Map.Entry<UUID, String> e : playerArena.entrySet()) {
            if (e.getValue().equals(oldName)) {
                e.setValue(newName);
            }
        }
        for (Map.Entry<UUID, String> e : editing.entrySet()) {
            if (e.getValue().equals(oldName)) {
                e.setValue(newName);
            }
        }
        WaitCountdown wt = waits.remove(oldName);
        if (wt != null) {
            waits.put(newName, wt);
        }
        MatchTasks t = tasks.remove(oldName);
        if (t != null) {
            t.cancelAll();
        }
        saveAll();
        HubAPI.removeArena(GAME, oldName);
        reportArena(a);
        return true;
    }

    /** 场地名校验：1~20 字符，仅中英文/数字/下划线/短横线（与其它插件一致）。 */
    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > 20) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }

    // ---------- 玩家会话 ----------

    public String arenaOf(UUID id) {
        return playerArena.get(id);
    }

    public LuckyPillarArena activeArenaOf(UUID id) {
        String name = playerArena.get(id);
        return name == null ? null : arenas.get(name);
    }

    public void setEditing(Player p, String arenaName) {
        if (arenaName == null) {
            editing.remove(p.getUniqueId());
        } else {
            editing.put(p.getUniqueId(), arenaName);
        }
    }

    public String editingArena(Player p) {
        return editing.get(p.getUniqueId());
    }

    public boolean join(Player p, String arenaName) {
        LuckyPillarArena a = arenas.get(arenaName);
        if (a == null) {
            msg(p, "&c场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        UUID id = p.getUniqueId();
        if (playerArena.containsKey(id)) {
            msg(p, "&c你已在其他场。");
            return false;
        }
        if (a.getState() != LuckyPillarArena.State.WAITING) {
            msg(p, "&c该场正在游戏中，等下一局吧。");
            return false;
        }
        if (!a.isReady()) {
            msg(p, "&c该场未配置完成（需要 center 与 lobby 两个点）。");
            return false;
        }
        int max = plugin.getConfig().getInt("max-players", 12);
        if (a.getPlayers().size() >= max) {
            msg(p, "&c人数已满（&e" + max + "&c）。");
            return false;
        }
        playerArena.put(id, arenaName);
        a.getPlayers().add(id);
        resetPlayer(p);
        Location lobby = a.getLobby() != null ? a.getLobby() : a.getCenter();
        p.teleport(lobby);
        HubAPI.setGame(id, GAME);
        giveLeaveBed(p);
        if (canAdmin(p)) {
            giveStartPaper(p);
        }
        msg(p, "&a你已加入 &e" + arenaName + "&a，等待开局…");
        broadcast(a, "&e" + p.getName() + "&f 加入了（&e" + a.getPlayers().size() + "&f 人）");
        HubAPI.announceJoin(plugin.getName(), p.getName(), a.getName());
        wait(a).update();
        reportArena(a);
        return true;
    }

    public void leave(Player p) {
        leave(p, false);
    }

    /**
     * 离开场地。{@code silent=true} 用于主城"复原大厅状态"的回调：玩家本来就不在场中时
     * 直接返回，不打任何提示（否则每个注册 lobby handler 的游戏插件都会各喊一句）。
     */
    public void leave(Player p, boolean silent) {
        UUID id = p.getUniqueId();
        String name = playerArena.remove(id);
        if (name == null) {
            if (!silent) {
                msg(p, "&c你不在任何场中。");
            }
            return;
        }
        LuckyPillarArena a = arenas.get(name);
        if (a != null) {
            boolean wasAlive = a.getPlayers().remove(id);
            a.getEliminated().remove(id);
            a.getElimOrder().remove(id);
            a.getOutSince().remove(id);
            HubAPI.clearGame(id);
            if (a.getState() == LuckyPillarArena.State.RUNNING) {
                broadcast(a, "&e" + p.getName() + "&f 离开了（剩 &e" + a.getPlayers().size() + "&f 人）");
                if (wasAlive) {
                    afterEliminate(a);
                }
            }
            reportArena(a);
        }
        sendHome(p);
        if (!silent) {
            msg(p, "&7你已离开。");
        }
        if (a != null && a.getState() == LuckyPillarArena.State.WAITING) {
            wait(a).update();
        }
    }

    /** 掉线：视为淘汰（设计稿 §6）。 */
    public void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        String name = playerArena.remove(id);
        if (name == null) {
            return;
        }
        LuckyPillarArena a = arenas.get(name);
        if (a == null) {
            return;
        }
        boolean wasAlive = a.getPlayers().contains(id);
        a.getPlayers().remove(id);
        a.getEliminated().remove(id);
        a.getOutSince().remove(id);
        HubAPI.clearGame(id);
        if (a.getState() == LuckyPillarArena.State.RUNNING) {
            if (wasAlive) {
                a.getElimOrder().add(id); // 掉线算一次淘汰（排在最后），保证冠亚季军名次不乱
                broadcast(a, "&c" + p.getName() + " &f掉线，视为淘汰。");
                afterEliminate(a);
            }
        }
        reportArena(a);
    }

    /** 重连/进服：不在场里却还留在对局世界 → 送回主城（设计稿 §6）。 */
    public void handleJoin(Player p) {
        if (p == null || !p.isOnline()) {
            return;
        }
        if (playerArena.containsKey(p.getUniqueId())) {
            return;
        }
        if (p.getGameMode() == GameMode.SPECTATOR) {
            p.setGameMode(GameMode.SURVIVAL);
        }
        World w = p.getWorld();
        if (w != null && isArenaWorld(w)) {
            HubAPI.teleportToLobby(p);
            plugin.getLogger().info("玩家 " + p.getName() + " 不在对局中但位于幸运之柱世界，已送回主城。");
        }
    }

    /** 该世界是否被某个场地占用（用于事件拦截的"本世界"判定）。 */
    public boolean isArenaWorld(World w) {
        if (w == null) {
            return false;
        }
        for (LuckyPillarArena a : arenas.values()) {
            if (w.equals(a.getWorld())) {
                return true;
            }
        }
        return false;
    }

    /** 该世界里正在进行的对局（爆炸拦截用；没有则 null）。 */
    public LuckyPillarArena runningArenaIn(World w) {
        if (w == null) {
            return null;
        }
        for (LuckyPillarArena a : arenas.values()) {
            if (a.getState() == LuckyPillarArena.State.RUNNING && w.equals(a.getWorld())) {
                return a;
            }
        }
        return null;
    }

    // ---------- 开局 ----------

    public boolean start(Player op, String arenaName) {
        LuckyPillarArena a = arenas.get(arenaName);
        if (a == null) {
            msg(op, "&c场不存在。");
            return false;
        }
        if (a.getState() != LuckyPillarArena.State.WAITING) {
            msg(op, "&c该场正在游戏中。");
            return false;
        }
        int min = plugin.getConfig().getInt("min-players", 2);
        List<Player> online = onlinePlayers(a);
        if (online.size() < min) {
            msg(op, "&c人数不足（至少 &e" + min + "&c 人，当前 &e" + online.size() + "&c）。");
            return false;
        }
        if (startMatch(a, online)) {
            if (op != null) {
                msg(op, "&a已开局 &e" + a.getName() + "&a（&e" + online.size() + "&a 人）。");
            }
            return true;
        }
        return false;
    }

    /** 无权限校验的开局（等待倒计时 / 命令 / 开始道具都走这里）。 */
    private StartCountdown startCountdown;

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

    public boolean startMatch(LuckyPillarArena a, List<Player> online) {
        if (!a.isReady()) {
            plugin.getLogger().warning("无法开局：" + a.getName() + " 缺 center / lobby 或世界未加载。");
            return false;
        }
        if (a.getState() != LuckyPillarArena.State.WAITING) {
            plugin.getLogger().info("忽略开局请求：" + a.getName() + " 状态是 " + a.getState() + "。");
            return false;
        }
        if (startCountdown().begin(a.getName(), online.stream().map(Player::getUniqueId).toList(),
                () -> startMatch(a, online.stream().filter(Player::isOnline).toList()))) {
            return true;
        }
        MatchTasks t = tasksFor(a);
        t.cancelAll();
        cancelWait(a);

        // 上一局残留（崩溃/异常退出）兜底：状态不是"已清空"就同步清一遍
        if (!a.isClean()) {
            a.clearAllNow();
            a.setClean(true);
        }

        long gen = a.nextMatch();
        a.getPlayers().clear();
        a.getEliminated().clear();
        a.getElimOrder().clear();
        a.getOutSince().clear();
        for (Player p : online) {
            a.getPlayers().add(p.getUniqueId());
        }

        int n = online.size();
        int depth = plugin.getConfig().getInt("pillar-depth", 10);
        double spacing = plugin.getConfig().getDouble("pillar-spacing", 16);
        double fixed = plugin.getConfig().getDouble("ring-radius", 0);
        double startGap = Math.max(0.0, plugin.getConfig().getDouble("shrink-start-gap", 8));
        int platform = plugin.getConfig().getInt("center-platform-radius",
                LuckyPillarArena.DEFAULT_PLATFORM_RADIUS);
        int pillars = a.generate(n, depth, spacing, fixed, startGap, platform);
        a.setClean(false);

        long now = System.currentTimeMillis();
        double seconds = plugin.getConfig().getDouble("shrink-total-seconds", 0);
        if (seconds <= 0) {
            seconds = 45 + 10.0 * n;
        }
        int delaySeconds = Math.max(0, plugin.getConfig().getInt("shrink-delay-seconds", 15));
        a.beginShrink(now, delaySeconds * 1000L, (long) (seconds * 1000L),
                plugin.getConfig().getDouble("shrink-target-radius", 2));
        a.setState(LuckyPillarArena.State.RUNNING);

        // 传送：一人一根柱顶（柱数 = 开局人数，不多不少）
        List<Location> tops = a.getPillarTops();
        for (int i = 0; i < online.size(); i++) {
            Player p = online.get(i);
            resetPlayer(p);
            if (!tops.isEmpty()) {
                p.teleport(tops.get(Math.min(i, tops.size() - 1)));
            }
            HubAPI.setPlaying(p.getUniqueId(), GAME);
        }

        applyBorder(a);
        startTasks(a, gen);
        reportArena(a);

        plugin.getLogger().info("开局：" + a.getName() + "（" + n + " 人，" + pillars + " 根柱，环半径 "
                + String.format("%.1f", a.getStartRadius() - startGap) + "，圈半径 "
                + String.format("%.1f", a.getStartRadius()) + "，" + delaySeconds + "s 后缩圈 "
                + (int) seconds + "s）");

        broadcast(a, "&6&l========== 幸运之柱 ==========");
        broadcast(a, "&e" + n + "&f 人，&e" + pillars + "&f 根哭泣黑曜石柱，间距 &e"
                + (int) spacing + "&f 格——跳不过去。");
        broadcast(a, "&f空手开局：每 &e" + plugin.getConfig().getInt("loot-interval", 2)
                + "&f 秒每人一件随机物品，&b"
                + Math.round(plugin.getConfig().getDouble("loot-gear-chance", 0.3) * 100)
                + "%&f 命中武器/工具/装备（可能带低阶附魔）。");
        if (delaySeconds > 0) {
            broadcast(a, "&c世界边境将在 &e" + delaySeconds + "&c 秒后开始收缩，&f圈外方块会被吃掉，掉下去即淘汰。");
        } else {
            broadcast(a, "&c世界边境开始收缩，&f圈外方块会被吃掉，掉下去即淘汰。");
        }
        titleToAll(online, "&5&l幸运之柱", "&7空手开局 · 每 2 秒一件幸运物品", 2);
        return true;
    }

    /** OP 强制停止（不结算冠军）。 */
    public boolean stop(Player op, String arenaName) {
        LuckyPillarArena a = arenas.get(arenaName);
        if (a == null) {
            msg(op, "&c场不存在。");
            return false;
        }
        if (a.getState() == LuckyPillarArena.State.WAITING) {
            msg(op, "&c该场没有正在进行的对局。");
            return false;
        }
        MatchTasks t = tasksFor(a);
        t.cancelAll();
        broadcast(a, "&c本局已被管理员终止。");
        restoreBorder(a);
        finish(a, a.getMatch());
        // finish() 里会起分批清场；管理员停止要立刻清完，先把那个任务撤掉
        if (t.clear != null) {
            t.clear.cancel();
            t.clear = null;
        }
        a.clearAllNow();
        a.setClean(true);
        plugin.getLogger().info("管理员 " + (op == null ? "控制台" : op.getName())
                + " 终止了 " + a.getName() + " 的对局。");
        return true;
    }

    // ---------- 各种任务 ----------

    private void startTasks(LuckyPillarArena a, long gen) {
        MatchTasks t = tasksFor(a);
        int loot = Math.max(1, plugin.getConfig().getInt("loot-interval", 2));
        t.loot = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!guarded(a, gen)) {
                t.cancelGameplay();
                return;
            }
            for (UUID id : new ArrayList<>(a.getPlayers())) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) {
                    giveLoot(p);
                }
            }
        }, loot * 20L, loot * 20L);

        int disaster = Math.max(5, plugin.getConfig().getInt("disaster-interval", 22));
        t.disaster = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!guarded(a, gen)) {
                t.cancelGameplay();
                return;
            }
            disasterRound(a);
        }, disaster * 20L, disaster * 20L);

        t.second = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!guarded(a, gen)) {
                t.cancelGameplay();
                return;
            }
            onSecond(a, gen);
        }, 20L, 20L);

        t.trim = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!guarded(a, gen)) {
                t.cancelGameplay();
                return;
            }
            onTrim(a);
        }, 5L, 5L);

        startVoidTask();
    }

    /** 每秒：同步边境墙 + 圈外侵蚀（设计稿 §3）。 */
    private void onSecond(LuckyPillarArena a, long gen) {
        long now = System.currentTimeMillis();
        if (!a.isFinalPhase()) {
            double r = a.radiusAt(now);
            a.setRadius(r);
            World w = a.getWorld();
            if (w != null) {
                WorldBorder border = w.getWorldBorder();
                border.setSize(Math.max(1.0, r * 2.0), 1);
            }
            if (r <= a.getTargetRadius() + 0.01) {
                enterFinal(a, gen);
            }
        }
        // 圈外惩罚：出圈超过 grace 秒后每秒掉血
        int damage = Math.max(1, plugin.getConfig().getInt("out-of-zone-damage", 1));
        long grace = Math.max(0, plugin.getConfig().getInt("out-of-zone-grace-seconds", 3)) * 1000L;
        for (UUID id : new ArrayList<>(a.getPlayers())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || p.isDead()) {
                continue;
            }
            if (a.horizDist(p.getLocation()) > a.getRadius() + 0.5) {
                Long since = a.getOutSince().get(id);
                if (since == null) {
                    since = now;
                    a.getOutSince().put(id, since);
                }
                if (now - since >= grace) {
                    Chat.actionBar(p, "&c你在圈外，正在被侵蚀！");
                    p.damage(damage);
                }
            } else {
                a.getOutSince().remove(id);
            }
        }
        updateLiveBoard(a);
    }

    private void updateLiveBoard(LuckyPillarArena a) {
        List<UUID> online = new ArrayList<>(a.getPlayers());
        online.sort(Comparator.comparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        lines.add(new LiveSidebar.Line("§7存活 §f" + online.size(), 1));
        for (UUID id : online) {
            lines.add(new LiveSidebar.Line("§e" + nameOf(id), 0));
        }
        liveSidebar.update("luckypillar:" + a.getName(), "&6&l幸运之柱 · 存活", online, lines);
    }

    /** 每 5 tick：圈外方块被吃掉（设计稿 §3）。 */
    private void onTrim(LuckyPillarArena a) {
        long now = System.currentTimeMillis();
        if (!a.isFinalPhase()) {
            a.setRadius(a.radiusAt(now));
        }
        int removed = a.trimOutside(a.getRadius());
        World w = a.getWorld();
        if (removed > 0 && w != null) {
            long stamp = System.currentTimeMillis();
            Long last = lastCrumble.get(a.getName());
            if (last == null || stamp - last >= 900L) { // 低频音效：最多约 1 秒响一次
                lastCrumble.put(a.getName(), stamp);
                w.playSound(a.getCenter(), Sound.BLOCK_STONE_BREAK, 0.35f, 0.5f);
            }
        }
    }

    /** 天灾池：箭雨 / 苦力怕雨，每次随机抽一种（设计稿 §4）。 */
    private void disasterRound(LuckyPillarArena a) {
        int alive = Math.max(1, a.getPlayers().size());
        if (RANDOM.nextBoolean()) {
            arrowRain(a, alive);
        } else {
            creeperRain(a, alive);
        }
    }

    private void arrowRain(LuckyPillarArena a, int alive) {
        World w = a.getWorld();
        if (w == null) {
            return;
        }
        broadcast(a, ARROW_LINES[RANDOM.nextInt(ARROW_LINES.length)]);
        int count = Math.min(20, 8 + alive);
        for (int i = 0; i < count; i++) {
            Location loc = randomInCircle(a, LuckyPillarArena.BOX_ABOVE);
            if (loc == null) {
                continue;
            }
            AbstractArrow arrow = w.spawnArrow(loc, new Vector(0, -1, 0), 1.2f, 0.0f);
            arrow.setDamage(ARROW_RAIN_DAMAGE);
            arrow.setCritical(false);
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            a.trackEntity(arrow);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (arrow.isValid()) {
                    arrow.remove();
                }
            }, ARROW_LIFE_TICKS);
        }
        w.playSound(a.getCenter(), Sound.ENTITY_ARROW_SHOOT, 0.9f, 1.0f);
    }

    private void creeperRain(LuckyPillarArena a, int alive) {
        World w = a.getWorld();
        if (w == null) {
            return;
        }
        broadcast(a, CREEPER_LINES[RANDOM.nextInt(CREEPER_LINES.length)]);
        int count = Math.min(8, 3 + alive / 3);
        for (int i = 0; i < count; i++) {
            Location loc = randomInCircle(a, LuckyPillarArena.BOX_ABOVE);
            if (loc == null) {
                continue;
            }
            Creeper creeper = w.spawn(loc, Creeper.class);
            creeper.setPowered(false);
            // 1.21+ 的 setFuseTicks 不允许超过 maxFuseTicks（默认 30），要先抬上限，否则抛
            // IllegalArgumentException: ticks > maxFuseTicks（实测踩过）
            if (creeper.getMaxFuseTicks() < CREEPER_FUSE_TICKS) {
                creeper.setMaxFuseTicks(CREEPER_FUSE_TICKS);
            }
            creeper.setIgnited(true);
            creeper.setFuseTicks(CREEPER_FUSE_TICKS);
            a.trackEntity(creeper);
        }
        w.playSound(a.getCenter(), Sound.ENTITY_CREEPER_PRIMED, 0.8f, 1.0f);
    }

    /** 终局：边境冻结 + TNT 雨（设计稿 §1、§4）。 */
    private void enterFinal(LuckyPillarArena a, long gen) {
        long now = System.currentTimeMillis();
        a.enterFinal(now, a.getPlayers().size());
        World w = a.getWorld();
        if (w != null) {
            w.getWorldBorder().setSize(Math.max(1.0, a.getTargetRadius() * 2.0), 0);
        }
        MatchTasks t = tasksFor(a);
        if (t.disaster != null) {
            t.disaster.cancel();
            t.disaster = null;
        }
        broadcast(a, "&c&l终局！&f 边境已冻结，&cTNT 雨&f 开始——中心柱底有 3×3 草台，柱顶的落脚点自己搭。");
        titleToAll(onlinePlayers(a), "&c&l终局", "&7TNT 雨开始 · 自己搭台", 2);
        if (w != null) {
            w.playSound(a.getCenter(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.0f);
        }
        a.setNextWaveMs(now);
        t.finalWave = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!guarded(a, gen)) {
                t.cancelGameplay();
                return;
            }
            long time = System.currentTimeMillis();
            long limit = Math.max(10, plugin.getConfig().getInt("final-max-seconds", 120)) * 1000L;
            if (time - a.getFinalStartMs() > limit) {
                settleByRank(a);
                return;
            }
            if (time < a.getNextWaveMs()) {
                return;
            }
            tntWave(a);
            a.setNextWaveMs(time + (long) (tntIntervalSeconds(a) * 1000L));
        }, 10L, 10L);
        reportArena(a);
    }

    /** TNT 雨间隔：存活人少时压缩（设计稿 §4 "每波 1.2~3 s（按存活人数压缩）"）。 */
    private double tntIntervalSeconds(LuckyPillarArena a) {
        double base = plugin.getConfig().getDouble("final-tnt-interval", 2.5);
        int alive = Math.max(1, a.getPlayers().size());
        double scaled = base * alive / Math.max(1.0, a.getFinalEnterAlive());
        return Math.max(1.2, Math.min(base, scaled));
    }

    private void tntWave(LuckyPillarArena a) {
        World w = a.getWorld();
        if (w == null) {
            return;
        }
        int fuse = Math.max(5, plugin.getConfig().getInt("final-tnt-fuse", 20));
        int count = 1 + RANDOM.nextInt(2);
        int bx = a.getCenter().getBlockX();
        int bz = a.getCenter().getBlockZ();
        for (int i = 0; i < count; i++) {
            int x = bx + RANDOM.nextInt(3) - 1; // 3×3 上方落下
            int z = bz + RANDOM.nextInt(3) - 1;
            Location loc = new Location(w, x + 0.5, a.getCenter().getBlockY() + 8, z + 0.5);
            TNTPrimed tnt = w.spawn(loc, TNTPrimed.class);
            tnt.setFuseTicks(fuse);
            a.trackEntity(tnt);
        }
        for (UUID id : new ArrayList<>(a.getPlayers())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.actionBar(p, "&c💣 TNT 雨！&7 别在一块方块上站太久。");
            }
        }
    }

    /** 终局封顶：按剩余人数 / 血量排名结算（设计稿 §13）。 */
    private void settleByRank(LuckyPillarArena a) {
        List<UUID> alive = new ArrayList<>(a.getPlayers());
        alive.sort((x, y) -> {
            Player px = Bukkit.getPlayer(x);
            Player py = Bukkit.getPlayer(y);
            double hx = px == null ? 0 : px.getHealth();
            double hy = py == null ? 0 : py.getHealth();
            return Double.compare(hy, hx);
        });
        UUID winner = alive.isEmpty() ? null : alive.get(0);
        broadcast(a, "&7终局时间到，按剩余人数 / 血量排名结算。");
        endMatch(a, winner, winner == null);
    }

    // ---------- 淘汰 / 结算 ----------

    public void handleDeath(Player p) {
        LuckyPillarArena a = activeArenaOf(p.getUniqueId());
        if (a == null) {
            return;
        }
        if (a.getState() == LuckyPillarArena.State.RUNNING) {
            if (!a.getPlayers().contains(p.getUniqueId())) {
                return;
            }
            eliminate(a, p, "&c☠ " + p.getName() + " &f被淘汰了！");
            msg(p, "&7你已被淘汰，转为旁观模式（物品已掉在原地）；比赛结束后自动回主城。");
        }
        // 没血之后立刻变旁观：RUNNING 和 ENDING（结算展示期）都一样 —— 下一 tick 走服务端重生
        // （不用 setHealth，避开 README §「重生键变黑」那个坑），PlayerRespawnEvent 里落到旁观点 + SPECTATOR。
        // **最后一个死的人也是旁观**（他这一死会立刻结算进 ENDING），8 秒展示期到点由 finish() 统一回主城，
        // 不会像以前那样被单独提前踢回主城。
        scheduleSpectate(p);
    }

    /** 下一 tick 让死者重生成旁观：跳过死亡界面，位置/模式在 PlayerRespawnEvent 里给。 */
    private void scheduleSpectate(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) {
                return;
            }
            if (p.getHealth() <= 0.0) {
                p.spigot().respawn();
            }
            if (p.isOnline() && p.getHealth() > 0.0) {
                p.setGameMode(GameMode.SPECTATOR);
            }
        });
    }

    /**
     * 死亡重生：落到中心上方旁观点 + 旁观模式。
     * <p>
     * RUNNING（被打死，继续看别人打完）和 ENDING（最后一死 / 展示期里摔死，看这 8 秒的结算画面）
     * 都走这条路；WAITING 里的意外重生不插手，交给主城兜底。
     */
    public void handleRespawn(Player p, org.bukkit.event.player.PlayerRespawnEvent event) {
        LuckyPillarArena a = activeArenaOf(p.getUniqueId());
        if (a == null) {
            return;
        }
        boolean eliminated = a.getEliminated().contains(p.getUniqueId());
        if (!eliminated && a.getState() != LuckyPillarArena.State.ENDING) {
            return;
        }
        Location spec = a.spectateLocation();
        if (spec != null) {
            event.setRespawnLocation(spec);
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                p.setGameMode(GameMode.SPECTATOR);
            }
        });
    }

    /** 把一名存活玩家淘汰为旁观（不推进胜负判定，由调用方统一 afterEliminate）。 */
    public void eliminate(LuckyPillarArena a, Player p, String message) {
        UUID id = p.getUniqueId();
        if (!a.getPlayers().remove(id)) {
            return;
        }
        a.getEliminated().add(id);
        a.getElimOrder().add(id);
        a.getOutSince().remove(id);
        // **不要在这里 HubAPI.clearGame**：清掉游戏状态后 HubCore 会认为他"已在大厅"，
        // 他死的下一 tick 再走 PlayerRespawnEvent 时 HubCore 直接 teleportToLobby →
        // 人被拉回主城（旁观就没了）。淘汰的人继续挂在游戏里（tab 仍显示在场），
        // 由 finish()/leave()/掉线 统一 clearGame。
        if (message != null) {
            broadcast(a, message);
        }
        reportArena(a);
        afterEliminate(a);
    }

    /** 淘汰后的统一收尾：判定胜负。 */
    private void afterEliminate(LuckyPillarArena a) {
        if (a.getState() != LuckyPillarArena.State.RUNNING) {
            return;
        }
        if (a.getPlayers().isEmpty()) {
            endMatch(a, null, true);
        } else if (a.getPlayers().size() == 1) {
            endMatch(a, a.getPlayers().get(0), false);
        }
    }

    /** 结算：广播冠/亚/季军 → 冠军 +1 → 结算展示 8 秒（场地保留）→ 回主城 → 清场。 */
    private void endMatch(LuckyPillarArena a, UUID winner, boolean draw) {
        if (a.getState() != LuckyPillarArena.State.RUNNING) {
            return;
        }
        liveSidebar.hide("luckypillar:" + a.getName());
        long gen = a.getMatch();
        a.setState(LuckyPillarArena.State.ENDING);
        MatchTasks t = tasksFor(a);
        t.cancelGameplay();
        restoreBorder(a);

        broadcast(a, "&6&l========== 游戏结束 ==========");
        if (draw || winner == null) {
            broadcast(a, "&c本局全员阵亡，平局，没有冠军。");
        } else {
            List<UUID> firsts = new ArrayList<>();
            List<UUID> seconds = new ArrayList<>();
            List<UUID> thirds = new ArrayList<>();
            firsts.add(winner);
            List<UUID> order = a.getElimOrder();
            if (!order.isEmpty()) {
                seconds.add(order.get(order.size() - 1));
            }
            if (order.size() >= 2) {
                thirds.add(order.get(order.size() - 2));
            }
            Top3.broadcast(msg -> broadcast(a, msg), this::nameOf, firsts, seconds, thirds);
            Top3.award(data, firsts, seconds, thirds);
            saveData();
        }
        broadcast(a, "&6===============================");
        titleToAll(onlinePlayers(a), draw ? "&c平局" : "&6&l冠军 &e" + nameOf(winner), "", 3);

        // 结算展示这 8 秒里场地保持原样（柱子/圈还在，冠军能在原地看到结果），
        // 清场推迟到 finish() 里"全体已回主城"之后再开始。
        t.settle = Bukkit.getScheduler().runTaskLater(plugin, () -> finish(a, gen), SETTLE_SECONDS * 20L);
        reportArena(a);
    }

    /**
     * 送回主城 —— 但**死亡界面还没点"重生"的玩家不能走这条路**。
     * <p>
     * 原因（2026-10-18 实测踩过，重生键变黑按不动）：{@code HubAPI.teleportToLobby} 最后会
     * {@code p.setHealth(20)}。对一个血量还是 0、死亡界面还开着的玩家这么做，等于在服务端把"尸体"
     * 复活了，但客户端仍然停在死亡界面；玩家点"重生"发来的
     * {@code ServerboundClientCommandPacket(PERFORM_RESPAWN)} 会被
     * {@code ServerGamePacketListenerImpl#handleClientCommand} 里的
     * {@code if (player.getHealth() > 0.0F) return;} 直接丢掉（javap 核对过 26.2 的字节码），
     * 于是重生键<b>永远按不动，只能重连</b>。
     * <p>
     * 所以这种人只清状态、不传送：让他自己点"重生"（服务端血量是 0，重生包正常处理），重生后由主城的
     * {@code PlayerRespawnEvent} 兜底送回大厅。
     */
    private void sendHome(Player p) {
        if (p.getHealth() <= 0.0) {
            HubAPI.clearGame(p.getUniqueId());
            return;
        }
        p.setGameMode(GameMode.SURVIVAL);
        HubAPI.teleportToLobby(p);
    }

    /** 结算展示结束：全体回主城，之后才开始清场（先送人、再拆场地）。 */
    private void finish(LuckyPillarArena a, long gen) {
        if (a.getMatch() != gen) {
            return;
        }
        liveSidebar.hide("luckypillar:" + a.getName());
        List<UUID> all = new ArrayList<>(a.getPlayers());
        all.addAll(a.getEliminated());
        for (UUID id : all) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                sendHome(p);
            } else {
                HubAPI.clearGame(id);
            }
            playerArena.remove(id);
        }
        a.getPlayers().clear();
        a.getEliminated().clear();
        a.getElimOrder().clear();
        a.getOutSince().clear();
        a.setState(LuckyPillarArena.State.WAITING);
        MatchTasks t = tasksFor(a);
        if (t.settle != null) {
            t.settle = null;
        }
        // 顺序要求：先把所有人送回主城，再拆场地方块（见 endMatch 的注释）
        startClear(a);
        reportArena(a);
        wait(a).update();
    }

    /** 分批清场（局末 / /lp stop；不卡服）。 */
    private void startClear(LuckyPillarArena a) {
        MatchTasks t = tasksFor(a);
        if (t.clear != null) {
            t.clear.cancel();
        }
        a.beginClear();
        t.clear = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (a.clearStep(CLEAR_BUDGET_PER_TICK)) {
                a.setClean(true);
                if (t.clear != null) {
                    t.clear.cancel();
                    t.clear = null;
                }
                plugin.getLogger().info("场地 " + a.getName() + " 已清空（清掉 " + a.getClearBlocksChanged() + " 个方块）。");
            }
        }, 1L, 1L);
    }

    /** 兜底淘汰：每 10 tick 检查有对局的世界，掉到 柱底-3 以下即淘汰（设计稿 §6）。 */
    private void startVoidTask() {
        if (voidTask != null) {
            return;
        }
        voidTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (LuckyPillarArena a : arenas.values()) {
                if (a.getState() != LuckyPillarArena.State.RUNNING) {
                    continue;
                }
                for (UUID id : new ArrayList<>(a.getPlayers())) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null || !p.isOnline()) {
                        continue;
                    }
                    if (p.getLocation().getY() >= a.getVoidY()) {
                        continue;
                    }
                    Location spec = a.spectateLocation();
                    if (spec != null) {
                        p.teleport(spec);
                    }
                    p.setGameMode(GameMode.SPECTATOR);
                    eliminate(a, p, "&c☠ " + p.getName() + " &f掉下去了！");
                }
            }
        }, 10L, 10L);
    }

    // ---------- 幸运物品 ----------

    /** 每 2 秒一件：入包 → 动作栏；背包满 → 静默掉脚下（设计稿 §5.1）。 */
    private void giveLoot(Player p) {
        ItemStack item = LuckyPillarLoot.random();
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
        if (leftover.isEmpty()) {
            p.sendActionBar(Component.text("🎁 ").append(Component.translatable(item.getType().translationKey())));
            return;
        }
        for (ItemStack rest : leftover.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    // ---------- 世界边境 ----------

    private void applyBorder(LuckyPillarArena a) {
        World w = a.getWorld();
        if (w == null) {
            return;
        }
        WorldBorder border = w.getWorldBorder();
        String key = w.getName();
        if (!borders.containsKey(key)) {
            borders.put(key, new BorderSnapshot(border.getCenter(), border.getSize(),
                    border.getDamageAmount(), border.getWarningDistance(), border.getWarningTimeTicks()));
        }
        border.setCenter(a.circleX(), a.circleZ());
        border.setDamageAmount(0);
        border.setWarningDistance(4);
        border.setWarningTime(10);
        border.setSize(Math.max(1.0, a.getStartRadius() * 2.0), 0);
    }

    private void restoreBorder(LuckyPillarArena a) {
        World w = a.getWorld();
        if (w == null) {
            return;
        }
        BorderSnapshot snap = borders.remove(w.getName());
        if (snap == null) {
            return;
        }
        WorldBorder border = w.getWorldBorder();
        border.setSize(snap.size());
        border.setCenter(snap.center());
        border.setDamageAmount(snap.damage());
        border.setWarningDistance(snap.warningDistance());
        border.setWarningTimeTicks(snap.warningTicks());
    }

    private record BorderSnapshot(Location center, double size, double damage, int warningDistance, int warningTicks) {
    }

    // ---------- 等待倒计时 ----------

    private WaitCountdown wait(LuckyPillarArena a) {
        return waits.computeIfAbsent(a.getName(), n -> new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return a.getPlayers();
            }

            @Override
            public int minPlayers() {
                return plugin.getConfig().getInt("min-players", 2);
            }

            @Override
            public int maxPlayers() {
                return plugin.getConfig().getInt("max-players", 12);
            }

            @Override
            public boolean hasOpQueued() {
                for (UUID id : a.getPlayers()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOp()) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onTick(long secondsLeft) {
                for (UUID id : a.getPlayers()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        Chat.actionBar(p, "&6开局倒计时：&e" + secondsLeft + "s");
                    }
                }
            }

            @Override
            public void onMessage(String message) {
                broadcast(a, message);
            }

            @Override
            public void onStart() {
                List<Player> online = onlinePlayers(a);
                if (online.isEmpty()) {
                    return;
                }
                startMatch(a, online);
            }
        }));
    }

    private void cancelWait(LuckyPillarArena a) {
        WaitCountdown w = waits.get(a.getName());
        if (w != null) {
            w.cancel();
        }
    }

    // ---------- 配置模式（只有两个点） ----------

    private final SetupMode.Handler setupHandler = new SetupMode.Handler() {

        @Override
        public void onTool(Player p, String arenaName, String toolId, Action action, Block clicked) {
            LuckyPillarArena a = setupTargets.get(p.getUniqueId());
            if (a == null) {
                a = arenas.get(arenaName);
            }
            if (a == null) {
                exitSetup(p);
                return;
            }
            switch (toolId) {
                case "center" -> {
                    Location loc = p.getLocation();
                    a.setCenter(loc);
                    msg(p, "&a已设置 &e" + a.getName() + "&a 中心点：&f" + shortLoc(loc));
                    msg(p, "&7柱顶高度 = 这个 Y，柱阵按人数围着它自动生成。");
                    saveAll();
                }
                case "lobby" -> {
                    Location loc = p.getLocation();
                    a.setLobby(loc);
                    ensurePlatform(loc);
                    msg(p, "&a已设置 &e" + a.getName() + "&a 等待点：&f" + shortLoc(loc));
                    saveAll();
                }
                case "preview" -> preview(p, a);
                default -> {
                    // save / exit 由公共库自己处理
                }
            }
        }

        @Override
        public void onSave(Player p, String arenaName) {
            LuckyPillarArena a = setupTargets.get(p.getUniqueId());
            saveAll();
            msg(p, "&a已保存场 &e" + (a != null ? a.getName() : arenaName) + "&a（"
                    + (a != null && a.isReady() ? "两个点齐全，可以 /lp start" : "还缺点，用金锄头/纸补上") + "）。");
        }
    };

    private List<SetupMode.Tool> setupTools() {
        return List.of(
                SetupMode.Tool.right(Material.GOLDEN_HOE, "center", "&e中心点工具", "&7点一下就设中心点（圆心 + 柱顶高度）"),
                SetupMode.Tool.right(Material.PAPER, "lobby", "&f等待点工具", "&7点一下设等待点（脚下没方块会自动铺 5×5）"),
                SetupMode.Tool.right(Material.CLOCK, "preview", "&b预览柱阵", "&7按当前在线人数预览柱阵（只粒子）"),
                SetupMode.Tool.right(Material.EMERALD, "save", "&6保存场", "&7保存当前配置"),
                SetupMode.Tool.right(Material.BARRIER, "exit", "&c退出配置模式", "&7结束配置并恢复背包"));
    }

    public boolean enterSetup(Player p, String arenaName) {
        LuckyPillarArena a = arenas.get(arenaName);
        if (a == null) {
            msg(p, "&c场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        if (setup.isEditing(p)) {
            msg(p, "&c你已在配置模式中，先用屏障工具退出。");
            return false;
        }
        setupTargets.put(p.getUniqueId(), a);
        setup.enter(p, arenaName, setupTools(), setupHandler);
        msg(p, "&a已进入 &e" + arenaName + "&a 的配置模式：");
        msg(p, "&e金锄头&7 = 中心点（圆心 + 柱顶高度）  &f纸&7 = 等待点");
        msg(p, "&b时钟&7 = 预览柱阵  &6绿宝石&7 = 保存  &c屏障&7 = 退出");
        return true;
    }

    public void exitSetup(Player p) {
        setupTargets.remove(p.getUniqueId());
        setup.exit(p);
        msg(p, "&7已退出配置模式，背包已恢复。");
    }

    public boolean isInSetup(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null && !setup.isEditing(p)) {
            setupTargets.remove(id); // 玩家用屏障工具退出（公共库内部处理），清理镜像表
        }
        return setupTargets.containsKey(id);
    }

    public boolean handleSetupTool(Player p, Action action, Block clicked) {
        return setup.handleInteract(p, action, null, clicked);
    }

    /** 等待点脚下没方块时自动铺 5×5 平台（设计稿 §2）。 */
    public void ensurePlatform(Location loc) {
        World w = loc.getWorld();
        if (w == null) {
            return;
        }
        Block below = w.getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
        if (!below.getType().isAir()) {
            return;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                w.getBlockAt(loc.getBlockX() + dx, loc.getBlockY() - 1, loc.getBlockZ() + dz)
                        .setType(LuckyPillarArena.LOBBY_PLATFORM_MATERIAL, false);
            }
        }
    }

    // ---------- 预览 ----------

    public void preview(Player p, LuckyPillarArena a) {
        if (!a.isReady()) {
            msg(p, "&c场未配置完成（缺 center 或 lobby），先点两个点。");
            return;
        }
        int n = Math.max(1, Bukkit.getOnlinePlayers().size());
        int depth = plugin.getConfig().getInt("pillar-depth", 10);
        double spacing = plugin.getConfig().getDouble("pillar-spacing", 16);
        double fixed = plugin.getConfig().getDouble("ring-radius", 0);
        double r0 = LuckyPillarArena.ringRadius(n, spacing, fixed);
        World w = a.getWorld();
        if (w == null) {
            msg(p, "&c场地的世界没加载。");
            return;
        }
        int topY = a.getCenter().getBlockY();
        int bx = a.getCenter().getBlockX();
        int bz = a.getCenter().getBlockZ();
        for (int i = 0; i < n; i++) {
            double angle = Math.PI * 2 * i / n;
            int x = (int) Math.round(bx + r0 * Math.cos(angle));
            int z = (int) Math.round(bz + r0 * Math.sin(angle));
            for (double y = topY; y > topY - depth; y -= 0.5) {
                w.spawnParticle(Particle.END_ROD, x + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0);
            }
        }
        for (double ang = 0; ang < Math.PI * 2; ang += 0.04) {
            w.spawnParticle(Particle.END_ROD, bx + 0.5 + r0 * Math.cos(ang), topY + 0.5,
                    bz + 0.5 + r0 * Math.sin(ang), 1, 0, 0, 0, 0);
        }
        msg(p, "&a预览：&e" + n + "&a 根柱，环半径 &e" + String.format("%.1f", r0)
                + "&a，柱顶 Y = &e" + topY + "&a（只是粒子，没改方块）。");
    }

    // ---------- 战绩 ----------

    public void recordFirst(UUID id) {
        if (data == null) {
            return;
        }
        data.set("firsts." + id, getFirsts(id) + 1);
        saveData();
    }

    public int getFirsts(UUID id) {
        return data == null ? 0 : data.getInt("firsts." + id, 0);
    }

    public void resetFirsts() {
        if (data != null) {
            data.set("firsts", null);
            saveData();
        }
    }

    private void saveData() {
        if (data == null) {
            return;
        }
        try {
            PlayerStats.save("luckypillar", data);
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("保存战绩失败：" + ex.getMessage());
        }
    }

    // ---------- 道具 ----------

    public void giveLeaveBed(Player p) {
        ItemStack bed = new ItemStack(Material.RED_BED);
        var meta = bed.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color("&c退出游戏 &7(右键)")));
            bed.setItemMeta(meta);
        }
        p.getInventory().setItem(LEAVE_BED_SLOT, bed);
    }

    public void handleLeaveBed(Player p) {
        leave(p);
    }

    /** 等待期间的 OP 开始纸（开局时 `resetPlayer` 会连同背包一起清掉）。 */
    public void giveStartPaper(Player p) {
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color("&a&l开始游戏 &7(右键)")));
            meta.lore(List.of(Chat.color("&7人数够了再右键开局")));
            meta.getPersistentDataContainer().set(startPaperKey, PersistentDataType.BYTE, (byte) 1);
            paper.setItemMeta(meta);
        }
        p.getInventory().setItem(START_SLOT, paper);
    }

    /** 是不是本插件发的开始纸（玩家自己的纸 / 局中抽到的纸都不算）。 */
    public boolean isStartPaper(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(startPaperKey, PersistentDataType.BYTE);
    }

    /** 等待室右键开始纸：只有管理员、且人数够了才开（校验都在 {@link #start} 里）。 */
    public void handleStartPaper(Player p) {
        LuckyPillarArena a = activeArenaOf(p.getUniqueId());
        if (a == null) {
            return;
        }
        if (a.getState() != LuckyPillarArena.State.WAITING) {
            msg(p, "&c该场正在游戏中。");
            return;
        }
        if (!canAdmin(p)) {
            msg(p, "&c只有管理员（OP 或 lp.admin）可以开始。");
            return;
        }
        start(p, a.getName());
    }

    private boolean canAdmin(Player p) {
        return p.isOp() || p.hasPermission("lp.admin");
    }

    // ---------- 事件回调（由 Listener 调） ----------

    /** 玩家放置/流体蔓延出的方块记进本局方块集合（缩圈 + 清场都靠它）。 */
    public void trackBlock(Block block) {
        if (block == null) {
            return;
        }
        LuckyPillarArena a = runningArenaIn(block.getWorld());
        if (a == null) {
            return;
        }
        a.trackBlock(block);
    }

    /** 本局实体（箭/苦力怕/TNT）判定，用于取消苦力怕摔落伤害。 */
    public boolean isTrackedEntity(org.bukkit.entity.Entity entity) {
        if (entity == null) {
            return false;
        }
        LuckyPillarArena a = runningArenaIn(entity.getWorld());
        return a != null && a.isTrackedEntity(entity);
    }

    // ---------- 辅助 ----------

    private boolean guarded(LuckyPillarArena a, long gen) {
        return a.getMatch() == gen && a.getState() == LuckyPillarArena.State.RUNNING;
    }

    private MatchTasks tasksFor(LuckyPillarArena a) {
        return tasks.computeIfAbsent(a.getName(), n -> new MatchTasks());
    }

    private List<Player> onlinePlayers(LuckyPillarArena a) {
        List<Player> list = new ArrayList<>();
        for (UUID id : new ArrayList<>(a.getPlayers())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                list.add(p);
            }
        }
        return list;
    }

    /** 恢复/清空玩家状态（开局与进场共用）。 */
    public void resetPlayer(Player p) {
        if (p == null || !p.isOnline()) {
            return;
        }
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.setSaturation(5);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
    }

    private Location randomInCircle(LuckyPillarArena a, int yOffset) {
        World w = a.getWorld();
        if (w == null || a.getCenter() == null) {
            return null;
        }
        double r = Math.max(0.5, a.getRadius());
        double ang = RANDOM.nextDouble() * Math.PI * 2;
        double dist = Math.sqrt(RANDOM.nextDouble()) * r;
        double x = a.circleX() + Math.cos(ang) * dist;
        double z = a.circleZ() + Math.sin(ang) * dist;
        return new Location(w, x, a.getCenter().getBlockY() + yOffset, z);
    }

    private void broadcast(LuckyPillarArena a, String message) {
        for (UUID id : new ArrayList<>(a.getPlayers())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                msg(p, message);
            }
        }
        for (UUID id : new ArrayList<>(a.getEliminated())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                msg(p, message);
            }
        }
    }

    private void msg(Player p, String message) {
        Chat.sendTitled(p, message);
    }

    private void titleToAll(List<Player> players, String title, String subtitle, int seconds) {
        for (Player p : players) {
            Chat.title(p, title, subtitle, seconds);
        }
    }

    private String nameOf(UUID id) {
        if (id == null) {
            return "—";
        }
        Player p = Bukkit.getPlayer(id);
        return p == null ? id.toString().substring(0, 6) : p.getName();
    }

    private String shortLoc(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
    }

    /** 一个场的对局任务集合（开局创建，结束/关服全取消）。 */
    private static final class MatchTasks {
        BukkitTask loot;
        BukkitTask second;
        BukkitTask trim;
        BukkitTask disaster;
        BukkitTask finalWave;
        BukkitTask settle;
        BukkitTask clear;

        /** 只取消"对局玩法"任务，保留结算与清场任务。 */
        void cancelGameplay() {
            for (BukkitTask t : new BukkitTask[]{loot, second, trim, disaster, finalWave}) {
                if (t != null) {
                    t.cancel();
                }
            }
            loot = null;
            second = null;
            trim = null;
            disaster = null;
            finalWave = null;
        }

        void cancelAll() {
            cancelGameplay();
            if (settle != null) {
                settle.cancel();
                settle = null;
            }
            if (clear != null) {
                clear.cancel();
                clear = null;
            }
        }
    }
}
