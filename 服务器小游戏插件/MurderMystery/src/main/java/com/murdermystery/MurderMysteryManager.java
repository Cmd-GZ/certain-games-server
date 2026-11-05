package com.murdermystery;

import com.hub.session.EndReason;

import com.hub.api.GameState;
import com.hub.data.PlayerStats;
import com.hub.api.HubAPI;
import com.hub.util.Chat;
import com.hub.util.LiveSidebar;
import com.hub.util.Top3;
import com.hub.util.StartCountdown;
import com.hub.util.SetupMode;
import com.hub.util.WaitCountdown;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 密室杀手核心：排队、角色分配、杀手行凶、金锭商店购买武器、胜负判定、
 * 出界淘汰、HubCore 大厅联动、战绩存储、配置模式与加入告示牌。
 */
public class MurderMysteryManager {

    /** D22：每个场地一个会话（多图游戏）。 */
    private final java.util.Map<String, MurderMysterySession> sessions = new java.util.LinkedHashMap<>();
    private boolean pendingInnocentsWin;
    private String pendingOutcome;

    /** 玩家 → 房间键；房间键 → 场地名。D22：键用房间 id，没有房间时退回场地名。 */
    private final java.util.Map<UUID, String> playerKey = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, String> keyArena = new java.util.LinkedHashMap<>();
    /** 每房间一份运行时场地实例（配置字段抄自模板，本局状态独立）。 */
    private final java.util.Map<String, MurderMysteryArena> liveArenas = new java.util.LinkedHashMap<>();

    public String keyOf(UUID id) {
        String k = playerKey.get(id);
        if (k != null) {
            return k;
        }
        String name = playerArena.get(id);
        return name == null ? null : name;
    }

    public String arenaNameOfKey(String key) {
        return keyArena.getOrDefault(key, key);
    }

    public MurderMysteryArena liveArena(String key) {
 if (key == null) {
     // **静默 null 是最难查的 bug**：join() 里 key 还没算出来就调 liveArena
     // 会得到 null → 上层"场不存在"→ 命令返回 true、控制台无日志。
     throw new IllegalStateException("MurderMystery: liveArena(null) —— 调用点没有先算房间键");
 }
        MurderMysteryArena live = liveArenas.get(key);
        if (live != null) {
            return live;
        }
        MurderMysteryArena tpl = arenas.get(arenaNameOfKey(key));
        if (tpl == null) {
            return null;
        }
        MurderMysteryArena copy = new MurderMysteryArena(tpl.getName());
        copy.setPos1(tpl.getPos1());
        copy.setPos2(tpl.getPos2());
        copy.setCenter(tpl.getCenter());
        copy.setLobbyPoint(tpl.getLobbyPoint());
        copy.setRoomKey(key);
        liveArenas.put(key, copy);
        return copy;
    }

    /** 控制台开局用：房间上下文优先，其次按场地名；都没有就退回现有那一间。 */
    private MurderMysteryArena liveArenaByName(String arenaName) {
        String ctx = com.hub.room.RoomContext.currentRoomId();
        if (ctx != null) {
            MurderMysteryArena live = liveArenas.get(ctx);
            if (live != null) {
                return live;
            }
        }
        // 控制台没有房间上下文时，**优先挑"真的有玩家在等"的那一间**：
        // 同一个场地名可能有多份房间实例，光按名字挑会挑到空的那份（实测：报"人数不足 当前 0"）。
        MurderMysteryArena best = null;
        for (MurderMysteryArena live : liveArenas.values()) {
            boolean nameOk = arenaName == null || arenaName.equals(live.getName());
            if (!nameOk || live.getPlayers().isEmpty()) {
                continue;
            }
            if (best == null || live.getPlayers().size() > best.getPlayers().size()) {
                best = live;
            }
        }
        if (best != null) {
            return best;
        }
        if (arenaName != null) {
            for (MurderMysteryArena live : liveArenas.values()) {
                if (arenaName.equals(live.getName())) {
                    return live;
                }
            }
        }
        return liveArenas.isEmpty() ? null : liveArenas.values().iterator().next();
    }

    /** 控制台开局用：房间上下文优先，否则退回最近用到的那一间（无玩家时没有 keyOf 可用）。 */
    private MurderMysteryArena liveArenaInRoom() {
        String ctx = com.hub.room.RoomContext.currentRoomId();
        if (ctx != null) {
            MurderMysteryArena live = liveArenas.get(ctx);
            if (live != null) {
                return live;
            }
        }
        return liveArenas.isEmpty() ? null : liveArenas.values().iterator().next();
    }

    private MurderMysteryArena arenaByName(String arenaName) {
        for (MurderMysteryArena live : liveArenas.values()) {
            if (arenaName.equals(live.getName())) {
                return live;
            }
        }
        return arenas.get(arenaName);
    }

    private MurderMysterySession ensureSession(String key) {
        MurderMysterySession s = sessions.get(key);
        if (s == null || s.state().isFinal()) {
            s = new MurderMysterySession(this, arenaNameOfKey(key));
            sessions.put(key, s);
        }
        return s;
    }


    /** 与 HubCore 菜单登记一致的显示名（alias）。 */
    public static final String GAME_NAME = "密室杀手";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** 杀手飞剑冷却（毫秒）。 */
    private static final int THROW_COOLDOWN_MS = 5000;

    /** 杀手近战刀冷却（毫秒）：一次近战击杀后 2 秒内不能再刀人（飞剑另有自己的 5 秒冷却）。 */
    private static final int KNIFE_COOLDOWN_MS = 2000;

    /** 开局身份倒数时长（秒）：屏幕中央 3/2/1，结束后公布各自身份。 */
    private static final int ROLE_COUNTDOWN_SECONDS = 3;
    /**
     * 公布身份后的第二段倒数（秒）：屏幕中央 3/2/1，结束后才发放**职业道具**
     * （杀手铁剑/指南针、猎人弓）。商店道具（绿宝石）人人都有，开局就发，不参与这段延后。
     */
    private static final int ITEM_COUNTDOWN_SECONDS = 3;
    /** 金块扫描每 tick 处理多少列（一列要扫一遍场地的 Y 范围）。 */
    private static final int GOLD_SCAN_COLUMNS_PER_TICK = 60;

    private static final String SETUP_TOOL_KEY = "mm-setup-tool";
    private static final String THROW_KEY = "mm-throw";
    private static final String SHOP_KEY = "mm-shop";
    private static final String SWORD_KEY = "mm-sword";
    private static final String SHOP_RANGED_KEY = "mm-shop-ranged";
    private static final String TRUTH_KEY = "mm-truth";
    /** 猎人的弓（开局发放，命中杀手一击必杀、误伤好人同归于尽）。 */
    private static final String HUNTER_BOW_KEY = "mm-hunter-bow";
    /** 弓的无限箭弹（真实箭矢但不消耗）：原版弓没有箭就射不出去，靠它实现「无限箭」。 */
    private static final String AMMO_KEY = "mm-hunter-ammo";
    private static final String CORPSE_OWNER_KEY = "mm-corpse-owner";
    private static final String DEATH_NOTE_KEY = "mm-death-note";
    private static final String TRACK_COMPASS_KEY = "mm-track-compass";
    /** 职业装备标记（杀手铁剑/追踪指南针、猎人弓）：局内一律不可丢弃。 */
    private static final String ROLE_ITEM_KEY = "mm-role-item";
    /** 杀手的追踪指南针固定放在快捷栏最后一格。 */
    public static final int COMPASS_SLOT = 8;

    /**
     * 尸体相对死亡位置的下移格数（0 = 就在死亡点）。
     * 游戏里看起来悬空就调小（负数），陷进地面就调大。
     */
    private static final double CORPSE_Y_OFFSET = 0.0;
    /** 尸体的姿势：Mannequin 只接受 STANDING / CROUCHING / SWIMMING / FALL_FLYING / SLEEPING，躺下用这两个之一。 */
    private static final Pose CORPSE_POSE = Pose.SLEEPING;
    /** 金锭商店 GUI 标题。 */
    public static final String SHOP_TITLE = "金锭商店";
    /** 观战者「存活玩家」菜单的标题前缀（真正显示时会补上人数）。 */
    public static final String WATCH_TITLE = "存活玩家";
    /** 真理之石右键「选择探测目标」菜单的标题前缀（真正显示时会补上人数）。 */
    public static final String TRUTH_TITLE = "真理之石 · 选择探测目标";
    /** 商店道具固定栏位。 */
    public static final int SHOP_SLOT = 4;
    /**
     * 职业道具（杀手铁剑 / 猎人弓）固定放的快捷栏格。
     * 只写这一格、**不碰手持槽**：发完道具后所有人手上都还是中间的绿宝石，别人没法靠"谁手里拿武器"猜职业。
     */
    public static final int WEAPON_SLOT = 0;

    private final MurderMysteryPlugin plugin;
    private final LiveSidebar liveSidebar;
    private final Map<String, MurderMysteryArena> arenas = new LinkedHashMap<>();
    private final Map<UUID, String> playerArena = new LinkedHashMap<>();
    /** 铁剑补发日志节流（玩家 -> 上次补发日志时间）。 */
    private final Map<UUID, Long> lastSwordHeal = new LinkedHashMap<>();
    private final Map<String, BukkitTask> gameTasks = new LinkedHashMap<>();
    /** 指南针自动跟踪任务（每场一个，跟 gameTasks 一起停）。 */
    private final Map<String, BukkitTask> compassTasks = new LinkedHashMap<>();
    /** 每个场地一个等待倒计时（按场名）。 */
    private final Map<String, WaitCountdown> waits = new LinkedHashMap<>();
    private BukkitTask boundsTask;
    private final Map<String, Boolean> worldPvpSaved = new LinkedHashMap<>();
    private final Map<String, Integer> worldRunning = new LinkedHashMap<>();
    private final Map<String, List<Mannequin>> corpses = new LinkedHashMap<>();
    private final Map<String, Long> lastGoldSpawn = new LinkedHashMap<>();
    /** 正在开局身份倒数的场地名（倒数期间禁止一切伤害）。 */
    private final Set<String> roleCountdown = new HashSet<>();
    /** 飞行中的飞剑：雪球实体（判定用，已隐形）-> 跟它同步的铁剑展示实体。 */
    private final Map<UUID, ItemDisplay> flyingSwords = new LinkedHashMap<>();
    /** 同步飞剑外形的 1 tick 任务（只要有飞剑在飞就保持运行）。 */
    private BukkitTask flyingSwordTask;

    // ---------- 角色分配的公平性历史（详见 assignRoles） ----------
    /** 角色历史文件名（单独一份，不塞进会被 saveAll 回写的 config.yml）。 */
    private static final String ROLE_HISTORY_FILE = "role-history.yml";
    /** 场地 -> 上一局的杀手（禁连庄用）。 */
    private final Map<String, List<UUID>> lastMurderers = new LinkedHashMap<>();
    /** 开局局号，只在 assignRoles 里自增。 */
    private long roleRound;
    /** 每个职业独立累计“连续几局没当上该职业”；权重 = base + misses * step（封顶 max）。 */
    private final Map<Role, Map<UUID, Integer>> roleMisses = new EnumMap<>(Role.class);
    /** [base, step, max] 缓存：模拟 4000 次时不要反复读 config。 */
    private final Map<Role, double[]> roleConfig = new EnumMap<>(Role.class);
    /** 等待室显示用的实际中选概率：场地 -> 玩家 -> [杀手, 侦探, 猎人]。 */
    private final Map<String, Map<UUID, double[]>> roleOdds = new LinkedHashMap<>();
    /** 等待室每秒刷新职业概率的行动栏任务。 */
    private BukkitTask waitingTask;

    /** 三个特殊职业（每人每局最多一个；概率各自独立累计）。 */
    private enum Role {
        MURDERER("murderer", "杀手"),
        DETECTIVE("detective", "侦探"),
        HUNTER("hunter", "猎人");

        private final String key;
        private final String display;

        Role(String key, String display) {
            this.key = key;
            this.display = display;
        }

        public String key() {
            return key;
        }

        public String display() {
            return display;
        }
    }

    // 数据与配置模式
    private YamlConfiguration data;
    /** 公共库的建场模式（工具/背包备份/退场都由它负责）。 */
    private final SetupMode setup;
    /** 公共库回调：按 toolId 分派到本插件的场地字段。 */
    private final SetupMode.Handler setupHandler = new SetupMode.Handler() {
        @Override
        public void onTool(Player p, String arenaName, String toolId, Action action, Block clicked) {
            applySetupTool(p, arenaName, toolId, action, clicked);
        }

        @Override
        public void onSave(Player p, String arenaName) {
            saveAll();
            msg(p, "&a已保存场 &e" + arenaName + "&a。");
        }
    };
    private final Map<String, List<Location>> joinSigns = new LinkedHashMap<>();
    private final Map<UUID, String> pendingSign = new LinkedHashMap<>();

    private final NamespacedKey throwKey;
    private final NamespacedKey shopKey;
    private final NamespacedKey swordKey;
    private final NamespacedKey shopRangedKey;
    private final NamespacedKey truthKey;
    /** 猎人的弓标记。 */
    private final NamespacedKey hunterBowKey;
    /** 弓的无限箭弹标记。 */
    private final NamespacedKey ammoKey;
    /** 尸体上记录死者名字（不显示在名牌上，右键认尸时读出来）。 */
    private final NamespacedKey corpseOwnerKey;
    /** 死亡名单道具标记。 */
    private final NamespacedKey deathNoteKey;
    /** 杀手指南针标记。 */
    private final NamespacedKey compassKey;
    /** 职业装备标记（不可丢弃）。 */
    private final NamespacedKey roleItemKey;
    /** I3：会话里的搬迁代码要用到 plugin。 */
    public MurderMysteryPlugin plugin() {
        return plugin;
    }


    public MurderMysteryManager(MurderMysteryPlugin plugin) {
        this.plugin = plugin;
        this.liveSidebar = new LiveSidebar(plugin);
        this.setup = new SetupMode(plugin, SETUP_TOOL_KEY);
        this.throwKey = new NamespacedKey(plugin, THROW_KEY);
        this.shopKey = new NamespacedKey(plugin, SHOP_KEY);
        this.swordKey = new NamespacedKey(plugin, SWORD_KEY);
        this.shopRangedKey = new NamespacedKey(plugin, SHOP_RANGED_KEY);
        this.truthKey = new NamespacedKey(plugin, TRUTH_KEY);
        this.hunterBowKey = new NamespacedKey(plugin, HUNTER_BOW_KEY);
        this.ammoKey = new NamespacedKey(plugin, AMMO_KEY);
        this.corpseOwnerKey = new NamespacedKey(plugin, CORPSE_OWNER_KEY);
        this.deathNoteKey = new NamespacedKey(plugin, DEATH_NOTE_KEY);
        this.compassKey = new NamespacedKey(plugin, TRACK_COMPASS_KEY);
        this.roleItemKey = new NamespacedKey(plugin, ROLE_ITEM_KEY);
        for (Role role : Role.values()) {
            roleMisses.put(role, new LinkedHashMap<>());
        }
    }

    // ---------- 配置 ----------

    private int cfgInt(String key, int def) {
        return plugin.getConfig().getInt(key, def);
    }

    public int minPlayers() {
        return cfgInt("min-players", 3);
    }

    public int maxPlayers() {
        return cfgInt("max-players", 16);
    }

    public int murdererCount() {
        return cfgInt("murderer-count", 1);
    }

    public int gameSeconds() {
        return cfgInt("game-seconds", 180);
    }

    /** 最后多少秒开始给杀手画粒子指引；0 = 关闭。 */
    private int endgameRevealSeconds() {
        return Math.max(0, cfgInt("endgame-reveal.seconds", 60));
    }

    /** 最后 N 秒粒子线使用的粒子类型（配置写 Bukkit Particle 名）。 */
    private Particle endgameRevealParticle() {
        String raw = plugin.getConfig().getString("endgame-reveal.particle", "END_ROD");
        try {
            Particle particle = Particle.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT));
            // 只接受不需要额外数据的粒子（END_ROD / GLOW 等），DUST 之类会要求 DustOptions
            return particle.getDataType() == Void.class ? particle : Particle.END_ROD;
        } catch (IllegalArgumentException ex) {
            return Particle.END_ROD;
        }
    }

    /** 粒子线每隔多少格放一个粒子。 */
    private double endgameRevealStep() {
        return Math.max(0.2, plugin.getConfig().getDouble("endgame-reveal.step", 1.5));
    }

    public int goldPerDeath() {
        return cfgInt("gold-per-death", 1);
    }

    public int swordCost() {
        return cfgInt("sword-cost", 8);
    }

    public int crossbowCost() {
        return cfgInt("crossbow-cost", cfgInt("bow-cost", 8));
    }

    public int arrowCost() {
        return cfgInt("arrow-cost", 2);
    }

    public int truthStoneCost() {
        return cfgInt("truth-stone-cost", 10);
    }

    /** 死亡名单（右键得知谁死了）的价格。 */
    public int deathNoteCost() {
        return cfgInt("death-note-cost", 1);
    }

    /** 断头饭（牛排 ×N）的价格。 */
    public int steakCost() {
        return cfgInt("steak-cost", 1);
    }

    /** 断头饭一次给几块牛排。 */
    public int steakAmount() {
        return Math.max(1, cfgInt("steak-amount", 5));
    }

    /** 本局侦探数量（无辜者阵营）。 */
    public int detectiveCount() {
        return Math.max(0, cfgInt("detective-count", 1));
    }

    /** 本局猎人数量（无辜者阵营，开局 1 把无限箭的弓；与侦探并存）。 */
    public int hunterCount() {
        return Math.max(0, cfgInt("hunter-count", 1));
    }

    /** 侦探每隔多少秒白得一颗真理之石。 */
    public int detectiveStoneSeconds() {
        return Math.max(5, cfgInt("detective-stone-seconds", 30));
    }

    public int goldSpawnInterval() {
        return cfgInt("gold-spawn-interval", 10);
    }

    public int goldSpawnAmount() {
        return cfgInt("gold-spawn-amount", 2);
    }

    // ---------- 加载与保存 ----------

    public void load() {
        startCountdown();
        invalidateGoldScan(); // 场地可能要重读，金块位置缓存也一起作废
        arenas.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("arenas");
        if (sec == null || sec.getKeys(false).isEmpty()) {
            plugin.getLogger().warning("config.yml 里没有任何场地（arenas 为空）。本来就配好了场地的话，说明这份 config.yml 没被读到："
                    + "请检查 " + plugin.getDataFolder().getPath() + " 下的 config.yml；"
                    + "如果控制台上面有 Cannot load ... InvalidConfigurationException，就是 YAML 语法错误"
                    + "（注意「键: 值」的冒号后面必须有一个空格）。");
        } else {
            for (String name : sec.getKeys(false)) {
                try {
                    arenas.put(name, MurderMysteryArena.load(name, sec.getConfigurationSection(name)));
                } catch (Exception e) {
                    plugin.getLogger().warning("场地「" + name + "」加载失败（已跳过）：" + e);
                }
            }
        }
        if (arenas.isEmpty()) {
            plugin.getLogger().warning("已加载 0 个场地。");
        } else {
            plugin.getLogger().info("已加载 " + arenas.size() + " 个场地：" + String.join("、", arenas.keySet()));
        }
        // 战绩数据（唯一文件 plugins/MiniGameLib/players.yml，命名空间 murdermystery）
        data = PlayerStats.load("murdermystery", new File(plugin.getDataFolder(), "data.yml"));
        loadRoleHistory(); // 角色累计计数（跨重启继续）
        ensureRoleChanceDefaults();
        ConfigurationSection js = plugin.getConfig().getConfigurationSection("join-signs");
        if (js != null) {
            for (String name : js.getKeys(false)) {
                List<?> list = js.getList(name);
                if (list == null) {
                    continue;
                }
                List<Location> locs = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Location loc) {
                        locs.add(loc);
                    }
                }
                if (!locs.isEmpty()) {
                    joinSigns.put(name, locs);
                }
            }
        }
        // 启动后把配置中已有场地全部上报给 HubCore 中央菜单（延迟等待 HubCore 就绪）
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (MurderMysteryArena a : arenas.values()) {
                reportArena(a);
                startGoldScan(a); // 开服就把金块扫出来缓存好，开局时才能把玩家分散到金块上
            }
        }, 40L);
        startBoundsCheck();
        roleConfig.clear();
        roleOdds.clear();
        startWaitingTask();
        // 场地每次重读都要重新声明地盘（I2）：`tpl:` 场地的世界是**运行时**才解析出来的
        // （模板没有存活房间 → pos1 是 null → 那块地盘当时根本声明不出来），
        // 所以 `/mm reload` 之后必须再声明一次，否则建好房间也不会补上。
        // Regions.begin() 会先撤掉本游戏上一轮的全部声明，不会累积幽灵区域。
        publishRegions();
    }

    /** 声明地盘（{@code docs/框架不变量.md} 的 I2）。 */
    public void publishRegions() {
        com.hub.region.Regions.begin("murdermystery");
        arenas.forEach((name, a) ->
                com.hub.region.Regions.box("murdermystery", name, a.getPos1(), a.getPos2()));
    }

    public void saveAll() {
                    // **未就绪的场地跳过回写**：世界缺失/没加载成功时，内存里的坐标是空的，
            // 写回去等于把磁盘上的场地坐标抹掉（真实事故：删了 world_tr 后 TntRun 的场地被清空）。
            // 房间只替换"世界"，坐标仍以这里为准，所以绝不能丢。
publishRegions();   // 场地可能刚被改过，重新声明（I2）
        // R2 单一写者（docs/框架不变量.md）：先把磁盘上的当前值读进内存，再只重写自己负责的段。
        // 否则关服时（onDisable -> saveAll -> saveConfig）会用内存里的旧值整份覆盖 config.yml：
        // 线上手改了别的键，传上去又被旧值写回去，表现为"改了配置没生效"（和 BuildRun 同一个坑）。
        plugin.reloadConfig();

        // config.yml 根本没读进来（YAML 语法错误）时千万不能回写：内存里是空的，
        // 一写就会把玩家手写的场地配置整份清掉。这里只保存战绩，配置文件原样留着等人修。
        if (plugin.getConfig().getKeys(false).isEmpty()) {
            plugin.getLogger().warning("config.yml 未成功载入（可能是 YAML 语法错误），已跳过写入以免覆盖原文件；"
                    + "请修好语法后重启服务器。");
            saveData();
            return;
        }
        invalidateGoldScan(); // 场地被改过，金块位置重新扫
        // ⚠️ 不能 createSection("arenas")：那会把整段**替换成空段**，
        // 于是"某个场地没加载成功（比如模板作用域没有存活房间）"时它的坐标就被擦掉了
        // —— 实测能把 4 个场地全写成 `{}`，配置彻底丢失。
        // 正确做法：拿**已存在**的段，只往里写（写不出来的键由 MurderMysteryArena.save 跳过）。
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("arenas");
        if (sec == null) {
            sec = plugin.getConfig().createSection("arenas");
        }
        for (Map.Entry<String, MurderMysteryArena> e : arenas.entrySet()) {
            // 场地没加载成功（世界缺失）时内存里没有坐标：整段跳过，别动磁盘上已有的值
            if (e.getValue().getCenter() == null) {
                plugin.getLogger().warning("[保存] 场地 " + e.getKey()
                        + " 未加载（世界缺失？），跳过回写以免丢坐标");
                continue;
            }
            ConfigurationSection arenaSec = sec.getConfigurationSection(e.getKey());
            if (arenaSec == null) {
                arenaSec = sec.createSection(e.getKey());
            }
            e.getValue().save(arenaSec);
        }
        // 同理不能 createSection("join-signs")：那是"新建+覆盖"。告示牌的世界读不出来（世界没加载）时
        // 内存里是空的，覆盖回去就把磁盘上的一整段擦了 —— 用"已有段就复用"。
        ConfigurationSection js = com.hub.world.Worlds.getOrCreate(plugin.getConfig(), "join-signs");
        for (Map.Entry<String, List<Location>> e : joinSigns.entrySet()) {
            js.set(e.getKey(), e.getValue());
        }
        plugin.saveConfig();
        saveData();
    }

    private void saveData() {
        if (data == null) {
            return;
        }
        try {
            PlayerStats.save("murdermystery", data);
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("保存战绩失败：" + ex.getMessage());
        }
    }

    // ---------- 场地管理 ----------

    public MurderMysteryArena getArena(String name) {
        return arenas.get(name);
    }

    public Map<String, MurderMysteryArena> getArenas() {
        return arenas;
    }

    public boolean createArena(String name) {
        if (arenas.containsKey(name)) {
            return false;
        }
        arenas.put(name, new MurderMysteryArena(name));
        return true;
    }

    public boolean removeArena(String name) {
        MurderMysteryArena a = arenas.get(name);
        if (a == null) {
            return false;
        }
        if (a.getState() != MurderMysteryArena.State.WAITING || !a.getPlayers().isEmpty() || !a.getEliminated().isEmpty()) {
            return false;
        }
        arenas.remove(name);
        plugin.getConfig().set("arenas." + name, null);
        joinSigns.remove(name);
        plugin.getConfig().set("join-signs." + name, null);
        plugin.saveConfig();
        cancelWaitCountdown(name);
        lastMurderers.remove(name); // 场地没了，禁连庄记录也一起清掉
        roleOdds.remove(name);
        HubAPI.removeArena(GAME_NAME, name);
        return true;
    }

    public boolean renameArena(String oldName, String newName) {
        if (oldName == null || oldName.equals(newName) || !validName(newName) || arenas.containsKey(newName)) {
            return false;
        }
        MurderMysteryArena a = arenas.get(oldName);
        if (a == null || a.getState() == MurderMysteryArena.State.RUNNING) {
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
        // 正在用工具配置该场的 OP：公共库按场名记录，改名后重新进入配置模式（工具与背包还原由公共库负责）
        for (Player ep : Bukkit.getOnlinePlayers()) {
            if (oldName.equals(setup.editingArena(ep))) {
                setup.exit(ep);
                enterSetupState(ep, newName);
            }
        }
        for (Map.Entry<UUID, String> e : pendingSign.entrySet()) {
            if (e.getValue().equals(oldName)) {
                e.setValue(newName);
            }
        }
        WaitCountdown w = waits.remove(oldName);
        if (w != null) {
            waits.put(newName, w);
        }
        List<Location> locs = joinSigns.remove(oldName);
        if (locs != null) {
            joinSigns.put(newName, locs);
            refreshJoinSigns(newName);
        }
        List<UUID> prevMurderers = lastMurderers.remove(oldName);
        if (prevMurderers != null) {
            lastMurderers.put(newName, prevMurderers);
        }
        Map<UUID, double[]> odds = roleOdds.remove(oldName);
        if (odds != null) {
            roleOdds.put(newName, odds);
        }
        saveAll();
        HubAPI.removeArena(GAME_NAME, oldName);
        reportArena(a);
        return true;
    }

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

    /** 上报场地状态到 HubCore 中央菜单（羊毛行：绿=等待，红=游戏中）。 */
    private void reportArena(MurderMysteryArena a) {
        boolean playing = a.getState() == MurderMysteryArena.State.RUNNING;
        HubAPI.setArena(GAME_NAME, a.getName(),
                playing ? GameState.PLAYING : GameState.WAITING, a.getPlayers().size(), null);
        HubAPI.setArenaSpectator(GAME_NAME, a.getName(), a.getCenter());
    }

    // ---------- 玩家会话 ----------

    public String arenaOf(UUID id) {
        return playerArena.get(id);
    }

    public MurderMysteryArena.Role roleOf(UUID id) {
        String name = playerArena.get(id);
        if (name == null) {
            return MurderMysteryArena.Role.NONE;
        }
        MurderMysteryArena a = arenas.get(name);
        if (a == null) {
            return MurderMysteryArena.Role.NONE;
        }
        if (a.isMurderer(id)) {
            return MurderMysteryArena.Role.MURDERER;
        }
        if (a.isDetective(id)) {
            return MurderMysteryArena.Role.DETECTIVE;
        }
        if (a.isHunter(id)) {
            return MurderMysteryArena.Role.HUNTER;
        }
        if (a.getPlayers().contains(id)) {
            return MurderMysteryArena.Role.INNOCENT;
        }
        return MurderMysteryArena.Role.NONE;
    }

    /** 玩家加入。 */
    public boolean join(Player p, String arenaName) {
        MurderMysteryArena a = arenas.get(arenaName);   // 模板：坐标来自配置
        if (a == null) {
            msg(p, "&c场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        UUID id = p.getUniqueId();
        String key = com.hub.room.RoomContext.roomKeyFor(p, "murdermystery", arenaName);
        if (playerArena.containsKey(id)) {
            msg(p, "&c你已在其他场。");
            return false;
        }
        if (a.getState() == MurderMysteryArena.State.RUNNING) {
            msg(p, "&c该场正在游戏中。");
            return false;
        }
        if (!a.isReady()) {
            msg(p, "&c该场未配置完成。");
            return false;
        }
        int max = maxPlayers();
        if (a.getPlayers().size() >= max) {
            msg(p, "&c人数已满（&e" + max + "&c）。");
            return false;
        }
        keyArena.put(key, arenaName);
        playerKey.put(id, key);
        MurderMysteryArena live = liveArena(key);
        MurderMysteryArena stateArena = live == null ? a : live;
        if (stateArena.getState() == MurderMysteryArena.State.RUNNING) {
            playerKey.remove(id);
            msg(p, "&c该场正在游戏中。");
            return false;
        }
        MurderMysterySession sess = ensureSession(key);
        if (!sess.join(p)) {
            if (sess.players().isEmpty()) {
                sessions.remove(key);
            }
            playerKey.remove(id);
            return false;
        }
        playerArena.put(id, arenaName);
        // **房间实例才是"本局状态"的家**：以前只把玩家加到模板 `a` 上，
        // 于是 `liveArena(key)` 那份房间实例永远 0 人 → 控制台/派对开局时报"人数不足"，一局都开不起来。
        // 模板那份也照旧记（老代码仍在读它），但**房间实例必须同步**。
        a.getPlayers().add(id);
        a.recordJoin(id);   // D21：记加入 tick + 随机名次
        if (stateArena != a) {
            stateArena.getPlayers().add(id);
            stateArena.recordJoin(id);
        }
        p.getInventory().clear();
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(20);
        p.setFoodLevel(20);
        Location lobby = stateArena.getLobbyPoint() != null
                ? stateArena.getLobbyPoint() : stateArena.getCenter();
        p.teleport(lobby);
        HubAPI.setGame(id, GAME_NAME);
        giveLeaveBed(p);
        if (p.isOp()) {
            giveStartPaper(p);
        }
        msg(p, "&a你已加入 &e" + arenaName + "&a，等待开始。");
        broadcast(a, "&e" + p.getName() + "&f 加入了（&e" + a.getPlayers().size() + "&f 人）");
        HubAPI.announceJoin(plugin.getName(), p.getName()); // 全服公屏播报
        recomputeRoleOdds(a);
        updateWaitCountdown(a);
        reportArena(a);
        return true;
    }

    /** 玩家主动离开。 */
    public void leave(Player p) {
        leave(p, false);
    }

    /**
     * 离开场地。{@code silent=true} 用于主城"复原大厅状态"的回调（进服复原 / 右键回主城道具 / 回到大厅）：
     * 玩家本来就不在场中时，只做无副作用的显示复原（名字牌、药水效果），不打任何提示、
     * 也不再回调 HubAPI.teleportToLobby——否则每个注册了 lobby handler 的游戏插件都会各喊一句
     * "你不在任何场中"，进服一次刷三行。
     */
    public void leave(Player p, boolean silent) {
        UUID id = p.getUniqueId();
        showNameTag(p);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        String name = playerArena.remove(id);
        // I1：从该场地的会话上摘掉自己（漏了会让玩家卡在"已在等待中"）
        if (name != null) {
            MurderMysterySession sess = sessions.get(name);
            if (sess != null && !sess.state().isFinal()) {
                sess.leave(p);
            }
        }
        if (name == null) {
            if (!silent) {
                msg(p, "&c你不在任何场中。");
            }
            return;
        }
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a != null) {
            // 必须在移除之前记录身份：isMurderer 就是查 murderers 集合
            boolean wasMurderer = a.isMurderer(id);
            a.getPlayers().remove(id);
            a.getEliminated().remove(id);
            a.getMurderers().remove(id);
            a.getDetectives().remove(id);
            a.getHunters().remove(id);
            a.getKills().remove(id);
            a.getThrowCooldown().remove(id);
            a.getKnifeCooldown().remove(id);
            forgetCompassTarget(a, id);
            updateCompassTargets(a);
            HubAPI.clearGame(id);
            boolean wasRunning = a.getState() == MurderMysteryArena.State.RUNNING;
            if (wasRunning) {
                handleLeaveDuringGame(a, p, id, wasMurderer);
            } else {
                broadcast(a, "&e" + p.getName() + "&f 离开了（剩 &e" + a.getPlayers().size() + "&f 人）");
            }
            reportArena(a);
        }
        if (a != null) {
            recomputeRoleOdds(a);
        }
        p.setGameMode(GameMode.SURVIVAL);
        removeHunterBow(p);
        HubAPI.teleportToLobby(p);
        msg(p, "&7你已离开。");
        if (a != null && a.getState() == MurderMysteryArena.State.WAITING) {
            updateWaitCountdown(a);
        }
    }

    /** 游戏中有人离开：杀手退=无辜者胜；无辜者全走光=杀手胜。 */
    private void handleLeaveDuringGame(MurderMysteryArena a, Player p, UUID id, boolean wasMurderer) {
        if (wasMurderer) {
            endGame(a, true, "&a&l无辜者获胜！&f杀手（&e" + p.getName() + "&f）退出了游戏。");
            return;
        }
        broadcast(a, "&e有人离开了游戏&f（剩余无辜者 &e" + a.innocentsLeft() + "&f）");
        if (a.innocentsLeft() <= 0) {
            endGame(a, false, "&c&l杀手获胜！&f无辜者都离开了。");
        }
    }

    /** 退出服务器清理。 */
    public void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        showNameTag(p);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        String name = playerArena.remove(id);
        p.setGameMode(GameMode.SURVIVAL);
        if (name == null) {
            return;
        }
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null) {
            return;
        }
        removeHunterBow(p);
        // 必须在移除之前记录身份：isMurderer 就是查 murderers 集合
        boolean wasMurderer = a.isMurderer(id);
        a.getPlayers().remove(id);
        a.getEliminated().remove(id);
        a.getMurderers().remove(id);
        a.getDetectives().remove(id);
        a.getHunters().remove(id);
        a.getKills().remove(id);
        a.getThrowCooldown().remove(id);
        forgetCompassTarget(a, id);
        updateCompassTargets(a);
        HubAPI.clearGame(id);
        if (a.getState() == MurderMysteryArena.State.RUNNING) {
            if (wasMurderer) {
                endGame(a, true, "&a&l无辜者获胜！&f杀手退出了游戏。");
            } else if (a.innocentsLeft() <= 0) {
                endGame(a, false, "&c&l杀手获胜！&f无辜者都已离开。");
            } else {
                reportArena(a);
            }
        } else {
            reportArena(a);
        }
        recomputeRoleOdds(a);
    }

    /** 加入服务器：清理观战模式残留，防止滞留在对局世界。 */
    public void handleJoin(Player p) {
        if (p == null || !p.isOnline()) {
            return;
        }
        if (playerArena.get(p.getUniqueId()) != null) {
            return;
        }
        if (p.getGameMode() == GameMode.SPECTATOR) {
            p.setGameMode(GameMode.SURVIVAL);
            plugin.getLogger().info("玩家 " + p.getName() + " 加入时处于观战模式，已恢复为生存模式。");
        }
        Set<String> mmWorlds = new HashSet<>();
        for (MurderMysteryArena a : arenas.values()) {
            if (a.getCenter() != null && a.getCenter().getWorld() != null) {
                mmWorlds.add(a.getCenter().getWorld().getName());
            }
            if (a.getPos1() != null && a.getPos1().getWorld() != null) {
                mmWorlds.add(a.getPos1().getWorld().getName());
            }
            if (a.getPos2() != null && a.getPos2().getWorld() != null) {
                mmWorlds.add(a.getPos2().getWorld().getName());
            }
        }
        if (mmWorlds.contains(p.getWorld().getName())) {
            HubAPI.teleportToLobby(p);
            plugin.getLogger().info("玩家 " + p.getName() + " 不在场但位于对局世界，已送回主城。");
        }
    }

    // ---------- 等待倒计时 ----------

    public void giveLeaveBed(Player p) {
        ItemStack bed = new ItemStack(Material.RED_BED);
        var meta = bed.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(LEGACY.deserialize("&c退出游戏&7(右键)")));
            bed.setItemMeta(meta);
        }
        p.getInventory().setItem(8, bed);
    }

    public void handleLeaveBed(Player p) {
        leave(p);
    }

    public void giveStartPaper(Player p) {
        ItemStack paper = new ItemStack(Material.PAPER);
        var meta = paper.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(LEGACY.deserialize("&a&l开始游戏&7(右键)")));
            paper.setItemMeta(meta);
        }
        p.getInventory().setItem(4, paper);
    }

    public void handleStartPaper(Player p) {
        String arenaName = playerArena.get(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || a.getState() != MurderMysteryArena.State.WAITING) {
            return;
        }
        if (!p.isOp() && !p.hasPermission("mm.admin")) {
            msg(p, "&c只有管理员（OP 或 mm.admin）可以开始。");
            return;
        }
        start(p, arenaName);
    }

    /** 取（或创建）某场地的等待倒计时；公共库负责人数/OP 规则与计时。 */
    public int waitingCountOf(String arenaName) {
        MurderMysteryArena a = arenaByName(arenaName);
        return a == null ? 0 : a.getPlayers().size();
    }

    public int minPlayersOf(String arenaName) {
        return plugin.getConfig().getInt("min-players", 2);
    }

    public boolean isHostOf(Player p, String arenaName) {
        if (!"host".equalsIgnoreCase(plugin.getConfig().getString("flow", "upstream"))) {
            return false;
        }
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || p == null) {
            return false;
        }
        UUID host = wait(a).currentHost();
        return host != null && host.equals(p.getUniqueId());
    }

    private WaitCountdown wait(MurderMysteryArena a) {
        return waits.computeIfAbsent(a.getName(), n -> new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return a.getPlayers();
            }

            @Override
            public int minPlayers() {
                return MurderMysteryManager.this.minPlayers();
            }

            @Override
            public int maxPlayers() {
                return MurderMysteryManager.this.maxPlayers();
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
                        Chat.actionBar(p, "&6开始倒计时：&e" + secondsLeft + "s &7| " + roleOddsBar(a, id));
                    }
                }
            }

            @Override
            public void onMessage(String message) {
                broadcast(a, message);
            }

            // ---------- D21 主持人流程（配置 flow: host 才启用，默认走上游） ----------

            @Override

            public boolean hostFlow() {

                return "host".equalsIgnoreCase(plugin.getConfig().getString("flow", "upstream"));

            }


            @Override

            public int waitSeconds() {

                return Math.max(5, plugin.getConfig().getInt("wait-seconds", 300));

            }


            @Override

            public java.util.List<UUID> waitingOrder() {

                java.util.List<UUID> order = new ArrayList<>(a.getPlayers());

                order.sort(java.util.Comparator

                        .comparingLong((UUID id) -> a.joinTickOf(id))

                        .thenComparingLong(id -> a.joinSeedOf(id)));

                return order;

            }


            @Override

            public void onHostTimeout(UUID host) {

                Player p = Bukkit.getPlayer(host);

                String name = p == null ? host.toString() : p.getName();

                a.getPlayers().remove(host);

                playerArena.remove(host);

                MurderMysterySession sess = sessions.get(a.getName());

                if (sess != null && p != null && !sess.state().isFinal()) {

                    sess.leave(p);

                }

                if (p != null && p.isOnline()) {

                    msg(p, "&c倒计时结束还没开始，你被移出本局（主持人超时）。");

                    com.hub.api.HubAPI.teleportToLobby(p);

                }

                plugin.getLogger().info("[MurderMystery] 场 " + a.getName() + "：主持人 " + name

                        + " 超时未开始，已移出（剩余 " + a.getPlayers().size() + " 人）");

                broadcast(a, "&e主持人 &f" + name + " &e超时未开始，已移出本局。");

                updateWaitCountdown(a);

            }


            @Override
            public void onStart() {
                // 内部无权限校验的开局方法（命令层的那个带 mm.admin 校验）
                Player any = a.getPlayers().isEmpty() ? null : Bukkit.getPlayer(a.getPlayers().get(0));
                if (any != null) {
                    start(any, a.getName());
                }
            }
        }));
    }

    /** 人数/状态变化后刷新等待倒计时（保留原调用点的方法名）。 */
    private void updateWaitCountdown(MurderMysteryArena a) {
        wait(a).update();
    }

    /** 取消某场地的等待倒计时。 */
    private void cancelWaitCountdown(String arenaName) {
        WaitCountdown w = waits.remove(arenaName);
        if (w != null) {
            w.cancel();
        }
    }

    // ---------- 开局 ----------

    /** OP 开始游戏。 */
    private StartCountdown startCountdown;

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

    public boolean start(Player op, String arenaName) {
        MurderMysteryArena a = op != null ? liveArena(keyOf(op.getUniqueId()))
                : liveArenaByName(arenaName);   // 控制台开局：按场地名找这一间的活实例
        if (a == null) {
            msg(op, "&c场不存在（场地=" + arenaName + "，已知=" + liveArenas.values().stream()
                    .map(MurderMysteryArena::getName).toList()
                    + "，房间上下文=" + com.hub.room.RoomContext.currentRoomId() + "）");
            return false;
        }
        int min = minPlayers();
        if (a.getPlayers().size() < min) {
            msg(op, "&c人数不足（至少 &e" + min + "&c 人，当前 &e" + a.getPlayers().size() + "&c）。");
            return false;
        }
        if (a.getPlayers().size() < 2) {
            msg(op, "&c至少需要 2 名玩家开局。");
            return false;
        }
        if (!a.isReady()) {
            msg(op, "&c该场未配置完成（缺少 pos1/pos2/lobby）。");
            return false;
        }
        if (startCountdown().begin(arenaName, new ArrayList<>(a.getPlayers()), () -> start(op, arenaName))) {
            return true;
        }
        a.setState(MurderMysteryArena.State.RUNNING);
        // I5：场地进入 RUNNING 时**把会话也推进到 RUNNING**。
        // 以前没人调 session.start() → 会话永远停在 WAITING，end() 会跳过 ENDING 阶段，
        // doctor 里也一直显示 [WAITING]（2026-11-05 审计发现，7 个游戏都有这个问题）。
        {
            var sess = sessions.get(a.roomKey());
            if (sess != null && sess.state() == com.hub.session.SessionState.WAITING) {
                sess.start();
            }
        }
        plugin.getLogger().info("游戏开始：" + arenaName + "（" + a.getPlayers().size() + " 人）");
        reportArena(a);
        enableWorldPvp(a);
        wait(a).cancel();
        a.getEliminated().clear();
        a.getElimOrder().clear();
        a.getKills().clear();
        a.getThrowCooldown().clear();
        a.getKnifeCooldown().clear();
        a.getDetectiveStoneAt().clear();
        a.getCompassTargets().clear();
        cancelGameTask(a); // 清掉上一局可能残留的计时/指南针任务
        lastGoldSpawn.remove(a.roomKey());
        goldCursor.remove(a.getName()); // 新一局从第一个金块重新开始
        assignRoles(a);
        startGoldScan(a); // 顺便开始后台扫金块（分帧，不影响 TPS），扫完就能稳稳刷在金块上
        // 开局随机分散到金块上（每人一个不同位置），不再全部堆在场地中心
        List<Location> spawns = pickSpawnPoints(a, a.getPlayers().size());
        int spawnIndex = 0;
        for (UUID id : new ArrayList<>(a.getPlayers())) {
            HubAPI.setPlaying(id, GAME_NAME);
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                resetPlayer(p);
                Location spawn = spawnIndex < spawns.size() ? spawns.get(spawnIndex++) : a.getCenter();
                spawn.setYaw(yawTowards(spawn, a.getCenter()));
                p.teleport(spawn);
                // 只发商店道具（人人一个、不暴露身份）；职业武器要等身份公布后的 3 秒倒数结束。
                // 用 holdShopItem：绿宝石进中间的 4 号槽并手持它，开局所有人手上都一样。
                holdShopItem(p);
            }
        }
        hideNameTags(a);
        broadcast(a, "&6&l========== 密室杀手开始 ==========");
        broadcast(a, "&f找出杀手（持铁剑者），用商店武器消灭 TA；或作为杀手杀光所有人！");
        clearDroppedItems(a); // 开局清掉上一局残留的掉落物（金锭/武器）
        startRoleCountdown(a, ROLE_COUNTDOWN_SECONDS);
        return true;
    }

    /**
     * 开局出生点：随机挑金块顶面，**每人一个互不重复的位置**。
     * 金块还没扫完/数量不够时，用场地中心周围的分散点补齐（同样不会重合）。
     * 金块都是扫描选区内部得到的，所以都在场内、不会被判出界。
     */
    private List<Location> pickSpawnPoints(MurderMysteryArena a, int count) {
        List<Location> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        List<Location> golds = goldBlocks.get(a.getName());
        if (golds != null && !golds.isEmpty()) {
            List<Location> pool = new ArrayList<>(golds);
            Collections.shuffle(pool);
            for (Location g : pool) {
                if (out.size() >= count) {
                    break;
                }
                World w = g.getWorld();
                if (w == null) {
                    continue;
                }
                int x = g.getBlockX(), y = g.getBlockY(), z = g.getBlockZ();
                // 金块上方要有两格能站人的空间（脚 + 头），否则玩家会卡在方块里
                if (!w.getBlockAt(x, y + 1, z).isPassable() || !w.getBlockAt(x, y + 2, z).isPassable()) {
                    continue;
                }
                if (!used.add(x + "," + (y + 1) + "," + z)) {
                    continue;
                }
                out.add(new Location(w, x + 0.5, y + 1.0, z + 0.5));
            }
        }
        if (out.size() < count) {
            plugin.getLogger().warning("场地「" + a.getName() + "」只有 " + out.size() + " 个可用的金块出生点（需要 "
                    + count + " 个），其余玩家改用场地中心的分散点。");
        }
        // 补齐：以场地中心为圆心按黄金角散开，同样要求能站人
        Location center = a.getCenter();
        for (int i = 0; out.size() < count && i < count * 60; i++) {
            double angle = Math.toRadians(i * 137.5);
            double r = 1.0 + 0.9 * Math.sqrt(i);
            Location cand = new Location(center.getWorld(),
                    center.getX() + Math.cos(angle) * r, center.getY(), center.getZ() + Math.sin(angle) * r);
            if (!cand.getBlock().isPassable() || !cand.clone().add(0, 1, 0).getBlock().isPassable()) {
                continue;
            }
            if (used.add(cand.getBlockX() + "," + cand.getBlockY() + "," + cand.getBlockZ())) {
                out.add(cand);
            }
        }
        return out;
    }

    /** 从 from 看向 to 的水平朝向（用于出生时面朝场地中心）。 */
    private static float yawTowards(Location from, Location to) {
        return (float) Math.toDegrees(Math.atan2(-(to.getX() - from.getX()), to.getZ() - from.getZ()));
    }

    /**
     * 开局倒数，**两段式**：
     * <ol>
     *   <li>第一段 3/2/1：屏幕中央大字幕，结束后公布各自身份（{@link #revealRoles}），
     *       对局计时也从这一刻开始算（{@code gameEndMs}）；</li>
     *   <li>第二段 3/2/1：公布身份后**再倒数 3 秒才发放职业道具**（{@link #giveRoleItems}：
     *       杀手铁剑+指南针、猎人弓）。这样身份公布前／倒数期间没人拿着铁剑，
     *       不会"看谁手里有剑就知道谁是杀手"。</li>
     * </ol>
     * 两段倒数期间都在 {@link #roleCountdown} 里 → 由 {@link #isRoleCountdown} 拦掉一切伤害（免得有人抢跑）。
     */
    private void startRoleCountdown(MurderMysteryArena a, int seconds) {
        roleCountdown.add(a.getName());
        BukkitTask old = gameTasks.remove(a.getName());
        if (old != null) {
            old.cancel();
        }
        gameTasks.put(a.roomKey(), Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            /** 1 = 开局倒数（还没公布身份）；2 = 公布身份后的发道具倒数。 */
            private int phase = 1;
            private int left = seconds;

            @Override
            public void run() {
                if (a.getState() != MurderMysteryArena.State.RUNNING) {
                    roleCountdown.remove(a.getName());
                    cancelGameTask(a);
                    return;
                }
                if (left > 0) {
                    for (UUID id : a.getPlayers()) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) {
                            // 第二段倒数把身份写进副标题（每人不同），免得公布身份的大字幕被数字顶掉后看不到身份
                            Chat.title(p, "&e&l" + left,
                                    phase == 1 ? "&7准备…" : roleTitleTag(a, id) + "&7职业道具 " + left + " 秒后发放…", 1);
                        }
                    }
                    left--;
                    return;
                }
                if (phase == 1) {
                    // 第一段结束：公布身份 → 正式开始计时（本任务继续跑第二段，所以这里不调 startGameTask）
                    revealRoles(a);
                    a.setGameEndMs(System.currentTimeMillis() + gameSeconds() * 1000L);
                    phase = 2;
                    left = ITEM_COUNTDOWN_SECONDS;
                    return;
                }
                // 第二段结束：发放职业道具 → 正式开始（startGameTask 会替换掉本任务）
                roleCountdown.remove(a.getName());
                for (UUID id : new ArrayList<>(a.getPlayers())) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        giveRoleItems(a, p);
                    }
                }
                broadcast(a, "&a&l职业道具已发放！&f找出杀手（持铁剑者），开始行动！");
                startGameTask(a);
            }
        }, 0L, 20L));
    }

    /** 该玩家是否处在开局/发道具倒数期间（此时禁止一切伤害）。 */
    public boolean isRoleCountdown(Player p) {
        String name = playerArena.get(p.getUniqueId());
        return name != null && roleCountdown.contains(name);
    }

    /** 公布各自身份：屏幕中央大字幕 + 聊天栏细节（只有本人看得到）。 */
    private void revealRoles(MurderMysteryArena a) {
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            if (a.isMurderer(id)) {
                Chat.title(p, "&c&l你是杀手！", "&f手持铁剑一击必杀，杀光所有人", 3);
                msg(p, "&c&l你是杀手！&f手持铁剑一击必杀（右键可掷飞剑），杀光所有人或活到时间结束。");
                msg(p, "&7注意：&f只有&c手持铁剑&f命中才能秒人，其他武器（弓/徒手…）一律无效。");
                msg(p, "&c追踪指南针&7（快捷栏第 9 格）：&f自动指向最近的存活玩家，右键显示离你多远。");
                // 身份音效只发给本人：用 Player#playSound，不要用 world.playSound，否则附近玩家能听见、暴露职业
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 1f, 0.8f);
            } else if (a.isDetective(id)) {
                a.getDetectiveStoneAt().put(id, System.currentTimeMillis()); // 第一颗从此刻起算
                Chat.title(p, "&b&l你是侦探！", "&f每 " + detectiveStoneSeconds() + " 秒获得一颗真理之石", 3);
                msg(p, "&b&l你是侦探！&f你和无辜者同一阵营，每 &e" + detectiveStoneSeconds()
                        + "&f 秒自动获得一颗真理之石——右键打开菜单选玩家探测（也可左键直接点人）：杀手→红石块，无辜者→钻石块）。");
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.4f);
            } else if (a.isHunter(id)) {
                Chat.title(p, "&6&l你是猎人！", "&f手持弓：打中杀手一击必杀，打到好人同归于尽", 3);
                msg(p, "&6&l你是猎人！&f你和无辜者同一阵营。开局给你 &e1 把猎人之弓&f（&a无限箭&f，"
                        + "&e按住右键蓄力，松开发射&f）："
                        + "箭矢命中杀手一击必杀，&c打到好人则两人同归于尽&f。");
                p.playSound(p.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1.2f);
            } else {
                Chat.title(p, "&a&l你是无辜者", "&f拾取金锭，买武器消灭杀手", 3);
                msg(p, "&a你是无辜者！&f拾取金锭，用商店道具购买一击必杀武器消灭杀手，活到最后。");
            }
        }
    }

    /**
     * 分配本局角色（具体身份在开局倒数结束后由 {@link #revealRoles} 公布）。
     *
     * <p>三个特殊职业各自独立累计概率：
     * <ul>
     *   <li>每人每个职业记一个“连续几局没当上该职业”的计数；权重 = base + misses × step（封顶 max）；</li>
     *   <li>按 杀手 → 侦探 → 猎人 的顺序按权重无放回抽，每人最多拿一个职业；</li>
     *   <li>当上某职业 → 该职业计数清零；没当上 → 该职业计数 +1（三个职业互不影响）；</li>
     *   <li>保留禁连庄：上一局杀手只要剔除后候选还够，就不参与本局杀手抽取。</li>
     * </ul>
     * 累计计数落盘在 {@code plugins/MurderMystery/role-history.yml}，重启后继续。
     */
    private void assignRoles(MurderMysteryArena a) {
        roleRound++;
        List<UUID> pool = new ArrayList<>(a.getPlayers());
        a.getMurderers().clear();
        a.getDetectives().clear();
        a.getHunters().clear();
        Random random = new Random();

        int mCount = Math.min(murdererCount(), pool.size());
        List<UUID> murderers = pickWeighted(murdererCandidates(a, pool, mCount), mCount, Role.MURDERER, random);
        a.getMurderers().addAll(murderers);
        lastMurderers.put(a.getName(), new ArrayList<>(murderers));

        List<UUID> rest = new ArrayList<>(pool);
        rest.removeAll(murderers);
        int dCount = Math.min(detectiveCount(), rest.size());
        List<UUID> detectives = pickWeighted(rest, dCount, Role.DETECTIVE, random);
        a.getDetectives().addAll(detectives);
        rest.removeAll(detectives);
        int hCount = Math.min(hunterCount(), rest.size());
        List<UUID> hunters = pickWeighted(rest, hCount, Role.HUNTER, random);
        a.getHunters().addAll(hunters);

        // 每人每个职业独立结算：当上清零，没当上 +1
        for (UUID id : pool) {
            bumpMisses(id, Role.MURDERER, murderers.contains(id));
            bumpMisses(id, Role.DETECTIVE, detectives.contains(id));
            bumpMisses(id, Role.HUNTER, hunters.contains(id));
        }
        // 本局身份已定，等待室缓存作废；下局重新按新计数计算
        roleOdds.remove(a.getName());
        saveRoleHistory();
    }

    /** 禁连庄：上一局杀手在候选里剔除（只有剔除后仍够数才剔）。 */
    private List<UUID> murdererCandidates(MurderMysteryArena a, List<UUID> pool, int mCount) {
        List<UUID> prev = lastMurderers.get(a.getName());
        if (prev == null || prev.isEmpty() || pool.size() <= mCount) {
            return new ArrayList<>(pool);
        }
        List<UUID> filtered = new ArrayList<>();
        for (UUID id : pool) {
            if (!prev.contains(id)) {
                filtered.add(id);
            }
        }
        return filtered.size() >= mCount ? filtered : new ArrayList<>(pool);
    }

    /** 按权重无放回抽 count 个；权重全为 0 时退回等概率。 */
    private List<UUID> pickWeighted(List<UUID> candidates, int count, Role role, Random random) {
        List<UUID> pickable = new ArrayList<>(candidates);
        List<UUID> chosen = new ArrayList<>();
        for (int n = 0; n < count && !pickable.isEmpty(); n++) {
            double total = 0;
            for (UUID id : pickable) {
                total += Math.max(0.0, roleWeight(id, role));
            }
            UUID pick;
            if (total <= 0) {
                pick = pickable.get(random.nextInt(pickable.size()));
            } else {
                double r = random.nextDouble() * total;
                pick = pickable.get(pickable.size() - 1);
                for (UUID id : pickable) {
                    r -= Math.max(0.0, roleWeight(id, role));
                    if (r <= 0) {
                        pick = id;
                        break;
                    }
                }
            }
            pickable.remove(pick);
            chosen.add(pick);
        }
        return chosen;
    }

    private double[] roleChance(Role role) {
        double[] values = roleConfig.get(role);
        if (values == null) {
            double base = Math.max(0.0, plugin.getConfig().getDouble("role-chance." + role.key() + ".base", 1.0));
            double step = Math.max(0.0, plugin.getConfig().getDouble("role-chance." + role.key() + ".step", 2.0));
            double max = Math.max(base, plugin.getConfig().getDouble("role-chance." + role.key() + ".max", 50.0));
            values = new double[]{base, step, max};
            roleConfig.put(role, values);
        }
        return values;
    }

    private double roleBase(Role role) {
        return roleChance(role)[0];
    }

    private double roleStep(Role role) {
        return roleChance(role)[1];
    }

    private double roleMax(Role role) {
        return roleChance(role)[2];
    }

    private double roleWeight(UUID id, Role role) {
        int misses = roleMisses.get(role).getOrDefault(id, 0);
        return Math.min(roleMax(role), roleBase(role) + misses * roleStep(role));
    }

    /** 计数封顶：再多次没当上也不会让权重超过 max。 */
    private int roleMaxMisses(Role role) {
        double step = roleStep(role);
        if (step <= 0) {
            return 0;
        }
        return (int) Math.ceil((roleMax(role) - roleBase(role)) / step);
    }

    private void bumpMisses(UUID id, Role role, boolean gotRole) {
        Map<UUID, Integer> counts = roleMisses.get(role);
        int current = counts.getOrDefault(id, 0);
        counts.put(id, gotRole ? 0 : Math.min(roleMaxMisses(role), current + 1));
    }

    /** 给旧配置补上 role-chance / crossbow-cost 默认值（只补缺失键，不改已有值）。 */
    private void ensureRoleChanceDefaults() {
        // R2：补默认值前先读盘，否则这个 saveConfig() 会用内存里的旧值整份覆盖 config.yml。
        plugin.reloadConfig();
        if (plugin.getConfig().getKeys(false).isEmpty()) {
            return; // 配置文件没读进来时别回写，避免清掉手写内容
        }
        boolean changed = false;
        for (Role role : Role.values()) {
            changed |= setConfigDefault("role-chance." + role.key() + ".base", 1.0);
            changed |= setConfigDefault("role-chance." + role.key() + ".step", 2.0);
            changed |= setConfigDefault("role-chance." + role.key() + ".max", 50.0);
        }
        changed |= setConfigDefault("crossbow-cost", cfgInt("bow-cost", 8));
        if (changed) {
            plugin.saveConfig();
        }
    }

    private boolean setConfigDefault(String path, Object value) {
        if (plugin.getConfig().isSet(path)) {
            return false;
        }
        plugin.getConfig().set(path, value);
        return true;
    }

    /** 角色分配历史文件（独立文件，避免被 config.yml 的回写覆盖）。 */
    private File roleHistoryFile() {
        return new File(plugin.getDataFolder(), ROLE_HISTORY_FILE);
    }

    private void loadRoleHistory() {
        File f = roleHistoryFile();
        if (!f.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        roleRound = Math.max(0, y.getLong("round", 0));
        lastMurderers.clear();
        for (Role role : Role.values()) {
            Map<UUID, Integer> counts = roleMisses.get(role);
            counts.clear();
            ConfigurationSection misses = y.getConfigurationSection("misses." + role.key());
            if (misses == null) {
                continue;
            }
            for (String key : misses.getKeys(false)) {
                try {
                    counts.put(UUID.fromString(key), Math.max(0, misses.getInt(key)));
                } catch (IllegalArgumentException ignored) {
                    // 不是 UUID 的键直接跳过
                }
            }
        }
        for (String line : y.getStringList("previous-murderers")) {
            int bar = line.indexOf('|');
            if (bar <= 0) {
                continue;
            }
            List<UUID> ids = new ArrayList<>();
            for (String s : line.substring(bar + 1).split(",")) {
                if (s.trim().isEmpty()) {
                    continue;
                }
                try {
                    ids.add(UUID.fromString(s.trim()));
                } catch (IllegalArgumentException ignored) {
                    // 脏数据跳过
                }
            }
            if (!ids.isEmpty()) {
                lastMurderers.put(line.substring(0, bar), ids);
            }
        }
        Set<UUID> recorded = new HashSet<>();
        for (Map<UUID, Integer> counts : roleMisses.values()) {
            recorded.addAll(counts.keySet());
        }
        plugin.getLogger().info("角色分配历史已载入（第 " + roleRound + " 局，"
                + recorded.size() + " 名玩家有累计记录）。");
    }

    private void saveRoleHistory() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("round", roleRound);
        for (Role role : Role.values()) {
            for (Map.Entry<UUID, Integer> e : roleMisses.get(role).entrySet()) {
                y.set("misses." + role.key() + "." + e.getKey(), e.getValue());
            }
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<UUID>> e : lastMurderers.entrySet()) {
            StringBuilder sb = new StringBuilder(e.getKey()).append('|');
            for (int i = 0; i < e.getValue().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(e.getValue().get(i));
            }
            lines.add(sb.toString());
        }
        y.set("previous-murderers", lines);
        try {
            y.save(roleHistoryFile());
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("保存角色分配历史失败：" + ex.getMessage());
        }
    }

    /**
     * 按当前等待队列模拟每个玩家三个职业的**实际中选率**：
     * 会考虑人数、各职业数量、禁连庄和“每人最多一个职业”的互斥关系。
     * 权重仍是 role-chance 里的 base/step/max；结果只用于等待室显示。
     */
    private static final int ROLE_ODDS_RUNS = 4000;

    private void recomputeRoleOdds(MurderMysteryArena a) {
        Map<UUID, double[]> odds = new LinkedHashMap<>();
        List<UUID> pool = new ArrayList<>(a.getPlayers());
        if (!pool.isEmpty()) {
            int mCount = Math.min(murdererCount(), pool.size());
            int dLimit = detectiveCount();
            int hLimit = hunterCount();
            List<UUID> murdererPool = murdererCandidates(a, pool, mCount);
            Map<UUID, int[]> hits = new LinkedHashMap<>();
            for (UUID id : pool) {
                hits.put(id, new int[Role.values().length]);
            }
            Random random = new Random();
            for (int run = 0; run < ROLE_ODDS_RUNS; run++) {
                List<UUID> murderers = pickWeighted(murdererPool, mCount, Role.MURDERER, random);
                List<UUID> rest = new ArrayList<>(pool);
                rest.removeAll(murderers);
                List<UUID> detectives = pickWeighted(rest,
                        Math.min(dLimit, rest.size()), Role.DETECTIVE, random);
                rest.removeAll(detectives);
                List<UUID> hunters = pickWeighted(rest,
                        Math.min(hLimit, rest.size()), Role.HUNTER, random);
                for (UUID id : murderers) {
                    hits.get(id)[Role.MURDERER.ordinal()]++;
                }
                for (UUID id : detectives) {
                    hits.get(id)[Role.DETECTIVE.ordinal()]++;
                }
                for (UUID id : hunters) {
                    hits.get(id)[Role.HUNTER.ordinal()]++;
                }
            }
            for (UUID id : pool) {
                int[] c = hits.get(id);
                odds.put(id, new double[]{
                        (double) c[Role.MURDERER.ordinal()] / ROLE_ODDS_RUNS,
                        (double) c[Role.DETECTIVE.ordinal()] / ROLE_ODDS_RUNS,
                        (double) c[Role.HUNTER.ordinal()] / ROLE_ODDS_RUNS
                });
            }
        }
        roleOdds.put(a.getName(), odds);
    }

    /** 等待室行动栏：三个职业的当前实际中选率，分别显示。 */
    private String roleOddsBar(MurderMysteryArena a, UUID id) {
        Map<UUID, double[]> odds = roleOdds.get(a.getName());
        if (odds == null) {
            recomputeRoleOdds(a);
            odds = roleOdds.get(a.getName());
        }
        double[] chance = odds == null ? null : odds.get(id);
        if (chance == null) {
            return "&7职业概率：等待人数不足";
        }
        return "&c" + Role.MURDERER.display() + " &e" + percent(chance[0])
                + " &7| &b" + Role.DETECTIVE.display() + " &e" + percent(chance[1])
                + " &7| &6" + Role.HUNTER.display() + " &e" + percent(chance[2]);
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100.0);
    }

    private void startWaitingTask() {
        if (waitingTask != null) {
            waitingTask.cancel();
        }
        waitingTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWaiting, 20L, 20L);
    }

    /** 等待室每秒刷新职业概率；倒计时在跑时由 WaitCountdown#onTick 一起发，避免互相覆盖。 */
    private void tickWaiting() {
        for (MurderMysteryArena a : new ArrayList<>(arenas.values())) {
            if (a.getState() != MurderMysteryArena.State.WAITING || a.getPlayers().isEmpty()) {
                continue;
            }
            if (wait(a).isRunning()) {
                continue;
            }
            for (UUID id : new ArrayList<>(a.getPlayers())) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) {
                    Chat.actionBar(p, roleOddsBar(a, id));
                }
            }
        }
    }

    /** 商店道具（绿宝石）：人人都有、不暴露身份，所以开局就发（不等职业道具那次倒数）。 */
    private void giveShopItem(Player p) {
        p.getInventory().setItem(SHOP_SLOT, shopItem());
    }

    /**
     * 按角色发放**职业道具**（杀手铁剑+追踪指南针、猎人弓+无限箭弹；无辜者/侦探这次不发武器）。
     * 由 {@link #startRoleCountdown} 在"公布身份后的 3 秒倒数"结束时统一调用。
     */
    private void giveRoleItems(MurderMysteryArena a, Player p) {
        UUID id = p.getUniqueId();
        // 武器一律写死 WEAPON_SLOT，不用 setItemInMainHand：后者会跟着"当前手持槽"写，
        // 玩家当时若正拿着 4 号槽的绿宝石，武器就会覆盖掉商店道具，而且"手里突然多出一把剑"当场暴露职业。
        if (a.isMurderer(id)) {
            p.getInventory().setItem(WEAPON_SLOT, murdererSword());
            p.getInventory().setItem(COMPASS_SLOT, murdererCompass()); // 追踪指南针
            msg(p, "&c职业道具已发放：&f铁剑在&e快捷栏第 1 格&f（按 &e1 &f切出来才能秒人），指南针在&e第 9 格&f。");
        } else if (a.isHunter(id)) {
            // 猎人：一把原版弓（按住右键蓄力，松开发射），命中杀手一击必杀、误伤好人同归于尽
            p.getInventory().setItem(WEAPON_SLOT, hunterBow());
            ensureHunterAmmo(p);
            msg(p, "&6职业道具已发放：&f弓在&e快捷栏第 1 格&f（按 &e1 &f切出来），&a无限箭&f，"
                    + "&e按住右键蓄力，松开发射&f。");
        }
        // 收尾：所有人（含杀手/猎人）的绿宝石回到中间 4 号槽，且手持槽停在这一格 —— 手上一律是绿宝石。
        holdShopItem(p);
        plugin.getLogger().info("角色发放：" + p.getName() + " 角色=" + roleName(a, id)
                + " 主手=" + p.getInventory().getItemInMainHand().getType()
                + " 手持槽=" + p.getInventory().getHeldItemSlot()
                + " 武器槽=" + p.getInventory().getItem(WEAPON_SLOT));
    }

    /**
     * 把商店道具（绿宝石）放回中间的 {@link #SHOP_SLOT} 格，并把手持槽停在这一格。
     * 绿宝石不可丢弃但玩家可以在背包里挪位置，所以先全背包找一遍：找到就搬回 4 号槽，找不到才补发一个。
     * <b>只在开局和发职业道具这两个时机调用</b>，不能塞进每秒都跑的 {@link #selfHealItems}，
     * 否则会把杀手的手持槽一直拽回 4 号槽，他就永远拿不到铁剑了。
     */
    private void holdShopItem(Player p) {
        if (!isShopItem(p.getInventory().getItem(SHOP_SLOT))) {
            int found = -1;
            ItemStack[] contents = p.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                if (i != SHOP_SLOT && isShopItem(contents[i])) {
                    found = i;
                    break;
                }
            }
            if (found >= 0) {
                p.getInventory().setItem(found, null);
            }
            giveShopItem(p);
        }
        p.getInventory().setHeldItemSlot(SHOP_SLOT);
    }

    /** 发道具倒数时写进副标题的短身份标记（每人不同，只给本人看）。 */
    private String roleTitleTag(MurderMysteryArena a, UUID id) {
        if (a.isMurderer(id)) {
            return "&c你是杀手！ ";
        }
        if (a.isDetective(id)) {
            return "&b你是侦探！ ";
        }
        if (a.isHunter(id)) {
            return "&6你是猎人！ ";
        }
        return "&a你是无辜者 ";
    }

    /** 角色中文名（日志用）。 */
    private String roleName(MurderMysteryArena a, UUID id) {
        if (a.isMurderer(id)) {
            return "杀手";
        }
        if (a.isDetective(id)) {
            return "侦探";
        }
        if (a.isHunter(id)) {
            return "猎人";
        }
        return "无辜者";
    }

    /** 商店道具（右键打开金锭商店，不可丢弃）。 */
    public ItemStack shopItem() {
        ItemStack it = new ItemStack(Material.EMERALD);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(LEGACY.deserialize("&a商店 &7(右键打开)")));
            meta.lore(List.of(LEGACY.deserialize("&7用金锭兑换一击必杀武器")));
            meta.getPersistentDataContainer().set(shopKey, PersistentDataType.STRING, "1");
            it.setItemMeta(meta);
        }
        return it;
    }

    public boolean isShopItem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(shopKey, PersistentDataType.STRING);
    }

    private void resetPlayer(Player p) {
        p.getInventory().clear();
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        p.setHealth(20);
        p.setFoodLevel(20);
    }

    // ---------- 对局运行 ----------

    private void startGameTask(MurderMysteryArena a) {
        BukkitTask old = gameTasks.remove(a.getName());
        if (old != null) {
            old.cancel();
        }
        gameTasks.put(a.roomKey(), Bukkit.getScheduler().runTaskTimer(plugin, () -> tickGame(a), 20L, 20L));
        // 指南针每 0.25 秒追一次目标位置；目标死亡/离开后自动换下一个存活无辜者
        BukkitTask oldCompass = compassTasks.remove(a.getName());
        if (oldCompass != null) {
            oldCompass.cancel();
        }
        compassTasks.put(a.getName(), Bukkit.getScheduler().runTaskTimer(
                plugin, () -> updateCompassTargets(a), 1L, 5L));
    }

    private void tickGame(MurderMysteryArena a) {
        if (a.getState() != MurderMysteryArena.State.RUNNING) {
            cancelGameTask(a);
            return;
        }
        long now = System.currentTimeMillis();
        long left = (a.getGameEndMs() - now) / 1000;
        if (left <= 0) {
            // 时间到：杀手没能杀光所有人 → 无辜者获胜
            endGame(a, true, "&6&l时间到！&f杀手没能杀光所有人 —— &a&l无辜者获胜！");
            return;
        }
        maybeSpawnGold(a);
        drawEndgameReveal(a);
        String time = String.format("%02d:%02d", left / 60, left % 60);
        int innocentsLeft = a.innocentsLeft();
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                continue;
            }
            selfHealItems(a, p, id);
            if (a.isMurderer(id)) {
                int kills = a.getKills().getOrDefault(id, 0);
                Chat.actionBar(p, "&c杀手 | 击杀 &e" + kills + "&f ｜ 剩余无辜者 &e" + innocentsLeft
                        + "&f ｜ 时间 &e" + time);
            } else if (a.isDetective(id)) {
                // 侦探：每 detectiveStoneSeconds 秒白得一颗真理之石（死了就不再发，因为不在存活列表里）
                long intervalMs = detectiveStoneSeconds() * 1000L;
                long last = a.getDetectiveStoneAt().getOrDefault(id, now);
                if (now - last >= intervalMs) {
                    giveDetectiveStone(a, p, id, now);
                    last = now;
                }
                long next = Math.max(0, (intervalMs - (now - last)) / 1000);
                Chat.actionBar(p, "&b侦探 | 金币 &e" + goldCount(p) + "&f ｜ 真理之石 &e" + countTruthStones(p)
                        + "&f ｜ 下颗 &e" + next + "s&f ｜ 剩余无辜者 &e" + innocentsLeft + "&f ｜ 时间 &e" + time);
            } else if (a.isHunter(id)) {
                Chat.actionBar(p, "&6猎人 | 金币 &e" + goldCount(p) + "&f ｜ 弓：&a无限箭"
                        + "&f ｜ 剩余无辜者 &e" + innocentsLeft + "&f ｜ 时间 &e" + time);
            } else {
                Chat.actionBar(p, "&a无辜者 | 金币 &e" + goldCount(p)
                        + "&f（右键商店购买武器）｜ 剩余无辜者 &e" + innocentsLeft + "&f ｜ 时间 &e" + time);
            }
        }
        updateLiveBoard(a, left);
    }

    /** 右侧只显示局面信息（存活/无辜者/时间），不显示任何人头数。 */
    private void updateLiveBoard(MurderMysteryArena a, long secondsLeft) {
        List<UUID> online = new ArrayList<>(a.getPlayers());
        List<LiveSidebar.Line> lines = new ArrayList<>();
        lines.add(new LiveSidebar.Line("§7存活", online.size()));
        lines.add(new LiveSidebar.Line("§7剩余无辜者", a.innocentsLeft()));
        lines.add(new LiveSidebar.Line("§7时间", (int) Math.max(0, secondsLeft)));
        liveSidebar.update("mm:" + a.getName(), "&8&l密室杀手", online, lines);
        // LiveSidebar 会给玩家换计分板；换完重新挂匿名队伍，否则头顶名字会重新出现
        for (UUID id : online) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                hideNameTag(p);
            }
        }
    }

    /** 给侦探发一颗真理之石（背包满就掉在脚下），并记录时间戳。 */
    private void giveDetectiveStone(MurderMysteryArena a, Player p, UUID id, long now) {
        a.getDetectiveStoneAt().put(id, now);
        Map<Integer, ItemStack> leftOver = p.getInventory().addItem(truthStone());
        for (ItemStack it : leftOver.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), it);
        }
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.6f);
        msg(p, "&b你获得了一颗真理之石！&7右键打开菜单选玩家探测（也可左键直接点人）：杀手→红石块，无辜者→钻石块。");
    }

    /** 数一数背包里还有几颗真理之石（侦探状态栏用）。 */
    private int countTruthStones(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (isTruthStone(it)) {
                n += it.getAmount();
            }
        }
        return n;
    }

    /** 自愈：杀手背包丢失铁剑时补发回主手；任何人丢失商店道具时补发（先扫全背包避免重复发放）。 */
    private void selfHealItems(MurderMysteryArena a, Player p, UUID id) {
        boolean hasSword = false;
        boolean hasShop = false;
        boolean hasCompass = false;
        boolean hasBow = false;
        boolean hasAmmo = false;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null) {
                continue;
            }
            if (it.getType() == Material.IRON_SWORD) {
                hasSword = true;
            }
            if (isShopItem(it)) {
                hasShop = true;
            }
            if (isTrackCompass(it)) {
                hasCompass = true;
            }
            if (isHunterBow(it)) {
                hasBow = true;
            }
            if (isHunterAmmo(it)) {
                hasAmmo = true;
            }
        }
        // 弓的无限箭弹被清掉（重生/换世界）时补回来，否则原版弓射不出去
        if (hasBow && !hasAmmo) {
            ensureHunterAmmo(p);
        }
        // 顺序同 giveRoleItems：武器一律写死 WEAPON_SLOT，不切手持槽、不碰 4 号槽的商店道具，
        // 否则补发铁剑会把玩家手里的绿宝石顶掉，而且当场暴露杀手身份
        if (!hasShop) {
            p.getInventory().setItem(SHOP_SLOT, shopItem());
        }
        if (a.isMurderer(id) && !hasCompass) {
            p.getInventory().setItem(COMPASS_SLOT, murdererCompass());
        }
        if (a.isMurderer(id) && !hasSword) {
            p.getInventory().setItem(WEAPON_SLOT, murdererSword());
            long now = System.currentTimeMillis();
            if (now - lastSwordHeal.getOrDefault(id, 0L) > 10000) {
                lastSwordHeal.put(id, now);
                plugin.getLogger().info("铁剑补发：" + p.getName());
            }
        }
    }

    private void cancelGameTask(MurderMysteryArena a) {
        BukkitTask t = gameTasks.remove(a.getName());
        if (t != null) {
            t.cancel();
        }
        BukkitTask compass = compassTasks.remove(a.getName());
        if (compass != null) {
            compass.cancel();
        }
    }

    /**
     * 杀手近战刀的冷却：刀死一个人后 {@link #KNIFE_COOLDOWN_MS}（2 秒）内不能再次近战秒人。
     * <p>
     * 只拦「近战一击必杀」（铁剑 / 商店石剑）；飞剑走自己的 5 秒冷却（{@code THROW_COOLDOWN_MS}），
     * 互不影响。冷却中的挥砍不生效，只在杀手本人 actionbar 提示——公屏/聊天提示会暴露杀手在哪。
     *
     * @return true = 不在冷却中（本次可以出手，已开始计时）；false = 冷却中（本次击杀作废）
     */
    public boolean startKnifeCooldown(MurderMysteryArena a, Player murderer) {
        UUID id = murderer.getUniqueId();
        long now = System.currentTimeMillis();
        long cd = a.getKnifeCooldown().getOrDefault(id, 0L);
        if (now < cd) {
            Chat.actionBar(murderer, "&c杀手之剑冷却中（&e" + Math.max(1, (cd - now + 999) / 1000) + "s&c）");
            return false;
        }
        a.getKnifeCooldown().put(id, now + KNIFE_COOLDOWN_MS);
        return true;
    }

    /** 杀手击杀：受害者淘汰为观战、掉金锭、杀手之剑升级。 */
    public void handleMurdererKill(MurderMysteryArena a, Player murderer, Player victim) {
        UUID mid = murderer.getUniqueId();
        int kills = a.getKills().merge(mid, 1, Integer::sum);
        recordKill(mid);
        upgradeSword(murderer, kills);
        victim.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, victim.getLocation(), 1, 0, 0, 0, 0);
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f);
        eliminate(a, victim); // 死亡不出任何提示（谁死了要自己买死亡名单）
        victim.getWorld().dropItemNaturally(victim.getLocation(),
                new ItemStack(Material.GOLD_INGOT, Math.max(1, goldPerDeath())));
        // 升级提示只发给杀手本人：公屏播报会把杀手身份暴露给全场
        Chat.send(murderer, "&6你的杀手之剑升级到 &e" + Math.min(5, kills) + "&6 级！");
        checkMurdererWin(a);
    }

    /** 升级杀手主手铁剑的锋利附魔（反馈用途；伤害本身一击必杀，不可毁标记一并保住）。 */
    private void upgradeSword(Player murderer, int kills) {
        ItemStack sword = murderer.getInventory().getItemInMainHand();
        if (sword == null || sword.getType() != Material.IRON_SWORD) {
            return;
        }
        ItemMeta meta = sword.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.setUnbreakable(true);
        meta.addEnchant(Enchantment.SHARPNESS, Math.min(5, kills), true);
        sword.setItemMeta(meta);
    }

    /** 无辜者用商店武器（石剑/弓）消灭杀手：一击必杀，无辜者获胜。 */
    public void handleInnocentKill(MurderMysteryArena a, Player killer, Player murderer) {
        handleInnocentKill(a, killer, murderer, null);
    }

    /**
     * 无辜者阵营（含猎人）消灭杀手：一击必杀，无辜者获胜。
     *
     * @param weapon 武器名（如「弩」）；只用于结算横幅，为 null 时不写
     */
    public void handleInnocentKill(MurderMysteryArena a, Player killer, Player murderer, String weapon) {
        recordKill(killer.getUniqueId());
        murderer.getWorld().dropItemNaturally(murderer.getLocation(),
                new ItemStack(Material.GOLD_INGOT, Math.max(1, goldPerDeath())));
        eliminate(a, murderer);
        // 结算横幅写明"杀手被谁消灭了"
        endGame(a, true, "&a&l无辜者获胜！&f杀手（&c" + murderer.getName() + "&f）被 &e" + killer.getName()
                + (weapon == null ? "" : "&f 的 &e" + weapon) + "&f 消灭了。");
    }

    /**
     * 误伤无辜者（石剑/弓都一样）：两人同归于尽（都淘汰为观战、都掉金锭与尸体）。
     *
     * @param weapon 武器名；死亡提示已删除（2026-10-16），此参数当前只用于签名兼容
     */
    public void handleMutualDeath(MurderMysteryArena a, Player attacker, Player victim, String weapon) {
        recordKill(attacker.getUniqueId());
        victim.getWorld().dropItemNaturally(victim.getLocation(),
                new ItemStack(Material.GOLD_INGOT, Math.max(1, goldPerDeath())));
        eliminate(a, victim);
        attacker.getWorld().dropItemNaturally(attacker.getLocation(),
                new ItemStack(Material.GOLD_INGOT, Math.max(1, goldPerDeath())));
        eliminate(a, attacker);
        checkMurdererWin(a);
    }

    /**
     * 将一名存活玩家淘汰为观战者（先在被杀位置生成尸体）。
     * <p>
     * 死亡**不上公屏**（2026-10-16 按需求删掉了死亡播报，想知道谁死了要买死亡名单 / 看尸体 / 看剩余无辜者），
     * 但**当事人自己在聊天栏会收到两行**：一行「你被打死了」，一行告诉 TA 用 {@code /mm watch}
     * 打开存活玩家菜单点头像传送（2026-10-18 加）。
     * 后台会打一行 {@code 淘汰：<名字> 场地=…}，淘汰本身不消耗任何道具。
     */
    private void eliminate(MurderMysteryArena a, Player p) {
        dropHunterBow(a, p);
        spawnCorpse(p);
        hideNameTag(p);
        UUID id = p.getUniqueId();
        a.getPlayers().remove(id);
        a.getEliminated().add(id);
        a.getElimOrder().add(id);
        forgetCompassTarget(a, id);
        p.playHurtAnimation(0);
        p.getInventory().clear();
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        p.setGameMode(GameMode.SPECTATOR);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.teleport(a.getCenter());
        // tab 里**不区分死活**：淘汰者仍挂在「密室杀手 · 已加入」分区，名字格式与存活者完全一样，
        // 否则他一死就从游戏分区掉到大厅分区，等于当众公布"谁死了"（死亡本身也不出任何提示）。
        HubAPI.setPlaying(id, GAME_NAME);
        // 公屏仍然不播报死亡（谁死了要买死亡名单），但当事人自己在聊天栏要收到一行提示 + 观战用法
        Chat.send(p, "&c&l你被打死了！&7你现在是旁观者，可以自由飞行看别人打。");
        Chat.send(p, "&7输入 &e/mm watch&7 打开「存活玩家」菜单，点玩家头像就能传送到 TA 那里。");
        plugin.getLogger().info("淘汰：" + p.getName() + " 场地=" + a.getName());
        updateCompassTargets(a);
        reportArena(a);
    }

    /** 死亡时把猎人弓掉在原地：任何捡走弓的人再死亡都会再次掉落并再次播报。 */
    private void dropHunterBow(MurderMysteryArena a, Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        boolean dropped = false;
        // 掉出场地（虚空/出界）时原地可能是空的，改掉在场地中心，避免弩直接掉进虚空
        Location dropAt = a.contains(p.getLocation())
                ? p.getLocation().add(0.0, 1.0, 0.0)
                : a.getCenter().clone().add(0.0, 1.0, 0.0);
        World dropWorld = dropAt.getWorld();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!isHunterBow(item)) {
                continue;
            }
            ItemStack drop = item.clone();
            p.getInventory().setItem(slot, null);
            Item entity = dropWorld.dropItem(dropAt, drop);
            entity.setGlowing(true);
            entity.setPickupDelay(10);
            entity.setInvulnerable(true);
            dropped = true;
        }
        if (dropped) {
            broadcastTitle(a, "&6&l猎人的弓掉落了", "&7其他玩家可以捡起它来消灭杀手", 2);
            dropWorld.playSound(dropAt, Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
        }
    }

    /** 给场地内所有存活/观战玩家发屏幕中央大字幕。 */
    private void broadcastTitle(MurderMysteryArena a, String title, String subtitle, int staySeconds) {
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.title(p, title, subtitle, staySeconds);
            }
        }
        for (UUID id : a.getEliminated()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.title(p, title, subtitle, staySeconds);
            }
        }
    }

    /** 局末/离开时把弓和它的无限箭弹从背包拿走，避免玩家把对局道具带出去。 */
    private void removeHunterBow(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isHunterBow(contents[slot]) || isHunterAmmo(contents[slot])) {
                p.getInventory().setItem(slot, null);
            }
        }
    }

    /**
     * 杀手被消灭：无辜者获胜。
     * <p>
     * 走这里的是"非商店武器"的死亡（典型：无辜者徒手把杀手打死 → {@code PlayerDeathEvent}），
     * 所以用 {@link Player#getKiller()} 取凶手；取不到（/kill、环境伤等）就只写"已被消灭"。
     */
    public void murdererEliminated(MurderMysteryArena a, Player murderer) {
        Player killer = murderer.getKiller();
        boolean byPlayer = killer != null && !killer.getUniqueId().equals(murderer.getUniqueId());
        eliminate(a, murderer);
        endGame(a, true, byPlayer
                ? "&a&l无辜者获胜！&f杀手（&c" + murderer.getName() + "&f）被 &e" + killer.getName() + "&f 消灭了。"
                : "&a&l无辜者获胜！&f杀手已被消灭（凶手未知）。");
    }

    /** 存活无辜者/侦探为 0 → 杀手获胜。 */
    private void checkMurdererWin(MurderMysteryArena a) {
        if (a.getState() != MurderMysteryArena.State.RUNNING) {
            return;
        }
        if (a.innocentsLeft() <= 0) {
            endGame(a, false, "&c&l杀手获胜！&f无辜者全部阵亡。");
        }
    }

    /**
     * 结束对局并还原所有玩家。
     *
     * @param innocentsWin true 表示无辜者阵营获胜（决定战绩记给谁）
     * @param outcome      结算横幅，由调用方按实际结束原因给出（时间到 / 杀手被消灭 / 无辜者全灭…）
     */
    /** 收场入口（I4）：先让会话 end()，再由 onEnd 回到 doEndGame。 */
    private void endGame(MurderMysteryArena a, boolean innocentsWin, String outcome) {
        pendingInnocentsWin = innocentsWin;
        pendingOutcome = outcome;
        MurderMysterySession sess = sessions.get(a.getName());
        if (sess == null || sess.state().isFinal()) {
            doEndGame(a.getName());
            return;
        }
        sess.end(EndReason.ROUND_END);
    }

    /** 真正的收场逻辑（由 session.onEnd 回调）。结算参数透传，玩家看到的结果不变。 */
    void doEndGame(String arenaName) {
        MurderMysteryArena a = arenaByName(arenaName);
        if (a == null) {
            return;
        }
        boolean innocentsWin = pendingInnocentsWin;
        String outcome = pendingOutcome != null ? pendingOutcome : "";
        pendingInnocentsWin = false;
        pendingOutcome = null;
        if (a.getState() != MurderMysteryArena.State.RUNNING) {
            return;
        }
        cancelGameTask(a);
        liveSidebar.hide("mm:" + a.getName());
        clearCorpses(a);
        clearDroppedItems(a); // 局末清掉地上剩下的金锭，别留到下一局
        showNameTags(a);
        a.setState(MurderMysteryArena.State.WAITING);
        restoreWorldPvp(a);
        reportArena(a);
        broadcast(a, "&6&l========== 游戏结束 ==========");
        broadcast(a, outcome);
        // 结算时揭晓本局杀手（原来这里显示的是"首个被杀害"，按需求去掉）
        if (!a.getMurderers().isEmpty()) {
            List<String> murderers = new ArrayList<>();
            for (UUID id : a.getMurderers()) {
                murderers.add(nameOf(id));
            }
            broadcast(a, "&c本局杀手：&f" + String.join("&7, &f", murderers));
        }
        List<UUID> ranked = new ArrayList<>(a.getPlayers());
        ranked.addAll(a.getEliminated());
        ranked.sort(Comparator.comparingInt((UUID id) -> a.getKills().getOrDefault(id, 0)).reversed()
                .thenComparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        List<UUID> firsts = new ArrayList<>();
        List<UUID> seconds = new ArrayList<>();
        List<UUID> thirds = new ArrayList<>();
        if (ranked.size() >= 1) firsts.add(ranked.get(0));
        if (ranked.size() >= 2) seconds.add(ranked.get(1));
        if (ranked.size() >= 3) thirds.add(ranked.get(2));
        Top3.broadcast(msg -> broadcast(a, msg), id -> nameOf(id) + " &7("
                + a.getKills().getOrDefault(id, 0) + " 击杀)", firsts, seconds, thirds);
        Top3.award(data, firsts, seconds, thirds);
        saveData();
        broadcast(a, "&6===============================");
        // 记录战绩
        for (UUID id : a.getPlayers()) {
            if (innocentsWin ? !a.isMurderer(id) : a.isMurderer(id)) {
                recordWin(id);
            }
        }
        for (UUID id : a.getEliminated()) {
            if (innocentsWin ? !a.isMurderer(id) : a.isMurderer(id)) {
                recordWin(id);
            }
        }
        // 还原所有玩家：自动回主城并拿菜单道具
        for (UUID id : new ArrayList<>(a.getPlayers())) {
            Player pp = Bukkit.getPlayer(id);
            if (pp != null && pp.isOnline()) {
                pp.setGameMode(GameMode.SURVIVAL);
                removeHunterBow(pp);
                pp.getActivePotionEffects().forEach(e -> pp.removePotionEffect(e.getType()));
                HubAPI.teleportToLobby(pp);
            } else {
                HubAPI.clearGame(id);
            }
            playerArena.remove(id);
        }
        for (UUID id : new ArrayList<>(a.getEliminated())) {
            Player pp = Bukkit.getPlayer(id);
            if (pp != null && pp.isOnline()) {
                pp.setGameMode(GameMode.SURVIVAL);
                removeHunterBow(pp);
                pp.getActivePotionEffects().forEach(e -> pp.removePotionEffect(e.getType()));
                HubAPI.teleportToLobby(pp);
            } else {
                HubAPI.clearGame(id);
            }
            playerArena.remove(id);
        }
        a.getPlayers().clear();
        a.getEliminated().clear();
        a.getElimOrder().clear();
        a.getMurderers().clear();
        a.getDetectives().clear();
        a.getHunters().clear();
        a.getKills().clear();
        a.getThrowCooldown().clear();
        a.getKnifeCooldown().clear();
        a.getDetectiveStoneAt().clear();
        a.getCompassTargets().clear();
        lastGoldSpawn.remove(a.roomKey());
        goldCursor.remove(a.getName()); // 下一局重新从第一个金块开始按顺序刷
        roleCountdown.remove(a.getName());
        clearFlyingSwords(); // 场上还在飞的飞剑（雪球 + 铁剑展示实体）一起清掉
    }

    // ---------- 出界淘汰 ----------

    /** 每 0.5 秒检查所有进行中对局的存活玩家是否出界。 */
    private void startBoundsCheck() {
        if (boundsTask != null) {
            return;
        }
        boundsTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (MurderMysteryArena a : arenas.values()) {
                if (a.getState() != MurderMysteryArena.State.RUNNING) {
                    continue;
                }
                for (UUID id : new ArrayList<>(a.getPlayers())) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null || !p.isOnline()) {
                        continue;
                    }
                    handleMove(p);
                }
            }
        }, 10L, 10L);
    }

    /**
     * 出界淘汰：**只看选区**（pos1/pos2 围出的长方体，含 Y 范围）。
     * 只要人在选区里就不淘汰——之前这里还有"低于场地中心 1 格就算掉出地图"之类的额外判定，
     * 会导致站在地图下层（但在选区内）的玩家被误判淘汰，已去掉。
     */
    public void handleMove(Player p) {
        String name = playerArena.get(p.getUniqueId());
        if (name == null) {
            return;
        }
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || a.getState() != MurderMysteryArena.State.RUNNING) {
            return;
        }
        if (!a.getPlayers().contains(p.getUniqueId())) {
            return;
        }
        if (a.contains(p.getLocation())) {
            return; // 在选区内 → 安全
        }
        boolean wasMurderer = a.isMurderer(p.getUniqueId());
        eliminate(a, p); // 掉出世界同样不出提示（死亡一律不显示）
        p.teleport(a.getCenter());
        if (wasMurderer) {
            endGame(a, true, "&a&l无辜者获胜！&f杀手掉出了世界。");
        } else {
            checkMurdererWin(a);
        }
    }

    // ---------- 金币与兑换 ----------

    /** 统计玩家背包内金锭总数。 */
    public int goldCount(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == Material.GOLD_INGOT) {
                n += it.getAmount();
            }
        }
        return n;
    }

    /** 从背包移除 n 个金锭。 */
    private void removeGold(Player p, int n) {
        if (n <= 0) {
            return;
        }
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && n > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != Material.GOLD_INGOT) {
                continue;
            }
            int take = Math.min(n, it.getAmount());
            it.setAmount(it.getAmount() - take);
            n -= take;
            if (it.getAmount() <= 0) {
                p.getInventory().setItem(i, null);
            } else {
                p.getInventory().setItem(i, it);
            }
        }
    }

    // ---------- 金锭商店 ----------

    /** 打开金锭商店（石剑 / 弓 / 箭 / 真理之石 / 死亡名单 / 断头饭）。 */
    public void openShop(Player p) {
        String name = playerArena.get(p.getUniqueId());
        if (name == null) {
            return;
        }
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || a.getState() != MurderMysteryArena.State.RUNNING) {
            return;
        }
        if (!a.getPlayers().contains(p.getUniqueId())) {
            return;
        }
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 9, SHOP_TITLE);
        inv.setItem(0, shopButton(Material.STONE_SWORD, "&f一击必杀石剑", "&e" + swordCost() + " 金锭",
                "&7近战：命中杀手一击必杀",
                "&7误伤无辜者：两人同归于尽"));
        inv.setItem(1, shopButton(Material.CROSSBOW, "&f一击必杀弩", "&e" + crossbowCost() + " 金锭",
                "&7远程：弩矢命中杀手一击必杀",
                "&7误伤无辜者：两人同归于尽"));
        inv.setItem(2, shopButton(Material.ARROW, "&f箭 ×1", "&e" + arrowCost() + " 金锭",
                "&7搭配弩使用"));
        inv.setItem(3, shopButton(Material.STONE, "&f真理之石", "&e" + truthStoneCost() + " 金锭",
                "&7右键打开菜单选玩家探测（也可左键直接点人）",
                "&7对方是杀手 → 变成 &c红石块",
                "&7对方是无辜者 → 变成 &a钻石块"));
        inv.setItem(4, shopButton(Material.BOOK, "&f死亡名单", "&e" + deathNoteCost() + " 金锭",
                "&7右键使用：按死亡顺序告诉你已经死了哪些人",
                "&7（死亡不上公屏，想知道都有谁出局了就买这个）"));
        inv.setItem(5, shopButton(Material.COOKED_BEEF, "&6断头饭 ×" + steakAmount(), "&e" + steakCost() + " 金锭",
                "&7吃饱了才有力气逃命（或者追杀）",
                "&7饱腹 8 点，凶手与无辜者皆可食用"));
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.displayName(Chat.noItalic(LEGACY.deserialize(" ")));
            filler.setItemMeta(fm);
        }
        for (int i = 6; i < 9; i++) {
            inv.setItem(i, filler);
        }
        p.openInventory(inv);
    }

    public String shopTitle() {
        return SHOP_TITLE;
    }

    // ---------- 观战者：存活玩家菜单（/mm watch） ----------

    /**
     * 打开「存活玩家」菜单：只有**已被淘汰的旁观者**能用，一格里一颗存活玩家的头颅，
     * 点头像传送到该玩家那里（见 {@link #onWatchClick}）。
     * <p>
     * 只列本场的存活玩家（别的场不串台）；头颅上不写身份，免得旁观者拿着身份去指认。
     */
    public void openWatchMenu(Player p) {
        String name = playerArena.get(p.getUniqueId());
        if (name == null) {
            msg(p, "&c你现在不在对局中。");
            return;
        }
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || a.getState() != MurderMysteryArena.State.RUNNING) {
            msg(p, "&c对局还没开始或已经结束了。");
            return;
        }
        if (!a.getEliminated().contains(p.getUniqueId())) {
            msg(p, "&c你还在场上：被打死变成旁观者之后，才能用 &e/mm watch&c 传送到别人那里。");
            return;
        }
        List<Player> alive = new ArrayList<>();
        for (UUID id : a.getPlayers()) {
            Player t = Bukkit.getPlayer(id);
            if (t != null && t.isOnline()) {
                alive.add(t);
            }
        }
        if (alive.isEmpty()) {
            msg(p, "&7场上已经没有存活玩家了。");
            return;
        }
        int size = Math.min(54, ((alive.size() + 8) / 9) * 9);
        WatchMenuHolder holder = new WatchMenuHolder();
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(holder, size,
                LEGACY.deserialize("&8" + WATCH_TITLE + " &7(" + alive.size() + " 人存活)"));
        holder.bind(inv);
        for (int i = 0; i < alive.size() && i < size; i++) {
            Player t = alive.get(i);
            inv.setItem(i, headButton(p, t));
            holder.target(i, t.getUniqueId());
        }
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.4f);
    }

    /** 存活玩家菜单里的那颗头颅：名字 + 「点击传送」+ 距离（不写身份）。 */
    private ItemStack headButton(Player viewer, Player target) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta skull) {
            skull.setOwningPlayer(target); // 皮肤 = 该玩家
            skull.displayName(Chat.noItalic(LEGACY.deserialize("&f" + target.getName())));
            List<Component> lore = new ArrayList<>();
            lore.add(LEGACY.deserialize("&7点击传送到 &e" + target.getName() + " &7那里"));
            lore.add(LEGACY.deserialize("&8状态：存活中"));
            if (viewer.getWorld().equals(target.getWorld())) {
                int dist = (int) Math.round(viewer.getLocation().distance(target.getLocation()));
                lore.add(LEGACY.deserialize("&8距离：&7" + dist + " 格"));
            }
            skull.lore(lore);
            it.setItemMeta(skull);
        }
        return it;
    }

    /**
     * 存活玩家菜单点击：把旁观者传送到点中的那个存活玩家那里。
     * <p>
     * 点的时候 TA 可能刚被淘汰/下线：那就提示一句并重开一份最新的名单（下一 tick，避免在点击事件里重开界面）。
     */
    public void onWatchClick(Player p, WatchMenuHolder holder, int slot) {
        UUID targetId = holder.targetAt(slot);
        if (targetId == null) {
            return;
        }
        String name = playerArena.get(p.getUniqueId());
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || a.getState() != MurderMysteryArena.State.RUNNING || !a.getEliminated().contains(p.getUniqueId())) {
            p.closeInventory();
            msg(p, "&c只能在对局中、且你已经被淘汰之后使用这个菜单。");
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline() || !a.getPlayers().contains(targetId)) {
            p.closeInventory();
            msg(p, "&cTA 已经不在了（刚被淘汰或离开了）。");
            Bukkit.getScheduler().runTask(plugin, () -> openWatchMenu(p));
            return;
        }
        p.closeInventory();
        p.teleport(target.getLocation());
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
        msg(p, "&7已传送到 &e" + target.getName() + " &7那里。");
    }

    /** 商店点击购买（slot 为行内下标）。 */
    public void onShopBuy(Player p, int slot, org.bukkit.inventory.Inventory inv) {
        String name = playerArena.get(p.getUniqueId());
        if (name == null) {
            return;
        }
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || a.getState() != MurderMysteryArena.State.RUNNING || !a.getPlayers().contains(p.getUniqueId())) {
            return;
        }
        boolean killer = a.isMurderer(p.getUniqueId());
        if (slot == 0) {
            if (killer) {
                msg(p, "&c你是杀手，铁剑已经够用，无需购买石剑。");
            } else if (!hasTaggedItem(p, swordKey)) {
                buyItem(p, stoneSword(), swordCost(), "已购买一击必杀石剑！");
            } else {
                msg(p, "&c你已经有一把石剑了。");
            }
        } else if (slot == 1) {
            if (killer) {
                msg(p, "&c你是杀手，只有铁剑能秒人，买弩没用。");
            } else if (!hasTaggedItem(p, shopRangedKey)) {
                buyItem(p, shopCrossbow(), crossbowCost(), "已购买一击必杀弩！");
            } else {
                msg(p, "&c你已经有一把弩了。");
            }
        } else if (slot == 2) {
            if (killer) {
                msg(p, "&c你是杀手，只有铁剑能秒人，不需要箭。");
            } else {
                buyItem(p, new ItemStack(Material.ARROW, 1), arrowCost(), "已购买 1 支箭！");
            }
        } else if (slot == 3) {
            if (!hasTaggedItem(p, truthKey)) {
                buyItem(p, truthStone(), truthStoneCost(), "已购买真理之石！右键打开菜单选择要探测的玩家。");
            } else {
                msg(p, "&c你已经有一颗真理之石了。");
            }
        } else if (slot == 4) {
            buyItem(p, deathNote(), deathNoteCost(), "已购买死亡名单！右键使用即可知道谁死了。");
        } else if (slot == 5) {
            buyItem(p, steak(steakAmount()), steakCost(),
                    "已购买 " + steakAmount() + " 块断头饭！吃饱了才有力气逃命。");
        }
    }

    /**
     * 死亡名单道具（{@link #deathNoteCost()} 金锭）：右键得知本局已经死了哪些人。
     * 死亡既不上公屏也不私聊（{@link #eliminate} 不出任何提示），所以这是"花钱查名单"的唯一主动手段。
     */
    public void useDeathNote(Player p) {
        String name = playerArena.get(p.getUniqueId());
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || a.getState() != MurderMysteryArena.State.RUNNING) {
            msg(p, "&c你现在不在对局中。");
            return;
        }
        List<UUID> dead = new ArrayList<>(a.getEliminated()); // 淘汰列表本身按死亡先后追加，顺序即死亡顺序
        if (dead.isEmpty()) {
            msg(p, "&7目前还没有人死亡，这张名单先留着吧。");
            return; // 没死人就不消耗
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dead.size(); i++) {
            if (i > 0) {
                sb.append("&7, &f");
            }
            sb.append("&e").append(i + 1).append(". &f").append(nameOf(dead.get(i)));
        }
        msg(p, "&6死亡名单 &7(共 " + dead.size() + " 人，按死亡顺序)：&f" + sb);
        // 用掉一张
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getAmount() > 1) {
            hand.setAmount(hand.getAmount() - 1);
        } else {
            p.getInventory().setItemInMainHand(null);
        }
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1.2f);
    }

    private ItemStack deathNote() {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(LEGACY.deserialize("&f死亡名单")));
            meta.lore(List.of(
                    LEGACY.deserialize("&7右键使用：告诉你已经死了哪些人"),
                    LEGACY.deserialize("&7死亡不播报，想确认都有谁出局了就买这个")));
            meta.getPersistentDataContainer().set(deathNoteKey, PersistentDataType.STRING, "1");
            it.setItemMeta(meta);
        }
        return it;
    }

    public boolean isDeathNote(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(deathNoteKey, PersistentDataType.STRING);
    }

    /** 断头饭（{@link #steakCost()} 金锭买 {@link #steakAmount()} 块）：纯食物，没有任何特殊行为。 */
    private ItemStack steak(int amount) {
        ItemStack it = new ItemStack(Material.COOKED_BEEF, amount);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(LEGACY.deserialize("&6断头饭")));
            meta.lore(List.of(
                    LEGACY.deserialize("&7吃完这顿，可能就没有下一顿了"),
                    LEGACY.deserialize("&7饱腹 8 点，跑路更有力气")));
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 扣除金锭并发放物品（背包满则退款）。 */
    private void buyItem(Player p, ItemStack item, int cost, String okMsg) {
        int gold = goldCount(p);
        if (gold < cost) {
            msg(p, "&c金锭不足（&e" + cost + "&c 个，当前 &e" + gold + "&c）。");
            return;
        }
        removeGold(p, cost);
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        if (!left.isEmpty()) {
            p.getInventory().addItem(new ItemStack(Material.GOLD_INGOT, cost));
            msg(p, "&c背包已满，已退款。");
            return;
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.4f);
        msg(p, "&a" + okMsg);
    }

    private boolean hasTaggedItem(Player p, NamespacedKey key) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.hasItemMeta()
                    && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 杀手的铁剑（开局与 {@link #selfHealItems} 补发共用）。
     * <p>
     * 写成不可毁：用户要求"游戏期间不要掉耐久度"，否则铁剑可能在局中打坏，
     * 杀手就变成空手（{@link #upgradeSword} 只加锋利、不动不可毁标记，所以升级后仍不损耗）。
     */
    private ItemStack murdererSword() {
        ItemStack it = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            // 打上职业装备标记：局内不可丢弃（丢到地上别人能捡，等于把杀手武器送给对手）
            meta.getPersistentDataContainer().set(roleItemKey, PersistentDataType.STRING, "1");
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 是不是职业装备（杀手铁剑/追踪指南针、猎人弓）：这些局内不可丢弃。 */
    public boolean isRoleItem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(roleItemKey, PersistentDataType.STRING);
    }

    private ItemStack stoneSword() {
        ItemStack it = new ItemStack(Material.STONE_SWORD);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true); // 局内不掉耐久（用户要求：游戏期间不掉耐久度）
            meta.displayName(Chat.noItalic(LEGACY.deserialize("&f一击必杀石剑")));
            meta.lore(List.of(LEGACY.deserialize("&7命中杀手一击必杀；误伤无辜者两人同归于尽")));
            meta.getPersistentDataContainer().set(swordKey, PersistentDataType.STRING, "1");
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 商店的一击必杀弩（消耗普通箭；与猎人自带的无限弓箭不同）。 */
    private ItemStack shopCrossbow() {
        ItemStack it = new ItemStack(Material.CROSSBOW);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true); // 局内不掉耐久
            meta.displayName(Chat.noItalic(LEGACY.deserialize("&f一击必杀弩")));
            meta.lore(List.of(LEGACY.deserialize("&7弩矢命中杀手一击必杀；误伤无辜者两人同归于尽")));
            meta.getPersistentDataContainer().set(shopRangedKey, PersistentDataType.STRING, "1");
            it.setItemMeta(meta);
        }
        return it;
    }

    public boolean isSwordItem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(swordKey, PersistentDataType.STRING);
    }

    /** 猎人的弓（开局发放；箭矢判定与商店弩一致，靠 EntityShootBowEvent 不消耗箭 = 无限箭）。 */
    private ItemStack hunterBow() {
        ItemStack it = new ItemStack(Material.BOW);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true); // 局内不掉耐久
            meta.displayName(Chat.noItalic(LEGACY.deserialize("&6猎人之弓")));
            meta.lore(List.of(
                    LEGACY.deserialize("&7箭矢命中杀手一击必杀"),
                    LEGACY.deserialize("&7误伤好人：两人同归于尽"),
                    LEGACY.deserialize("&a无限箭&7：按住右键蓄力，松开发射")));
            meta.getPersistentDataContainer().set(hunterBowKey, PersistentDataType.STRING, "1");
            meta.getPersistentDataContainer().set(roleItemKey, PersistentDataType.STRING, "1");
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 弓的无限箭弹：原版弓背包里没有箭就射不出去；这支箭靠 `EntityShootBowEvent` 不消耗，等于无限箭。 */
    private ItemStack hunterAmmo() {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.STRING, "1");
            // 和弓一样不可丢弃，避免玩家手滑把弹药扔了
            meta.getPersistentDataContainer().set(roleItemKey, PersistentDataType.STRING, "1");
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 是不是弓的无限箭弹（用来清理、补发，别动商店买的普通箭）。 */
    public boolean isHunterAmmo(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(ammoKey, PersistentDataType.STRING);
    }

    /** 背包里有猎人之弓却没有无限箭弹时补一支（放在背包最后一格，不占快捷栏）。 */
    public void ensureHunterAmmo(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        boolean hasBow = false;
        for (ItemStack it : contents) {
            if (isHunterBow(it)) {
                hasBow = true;
            } else if (isHunterAmmo(it)) {
                return;
            }
        }
        if (!hasBow) {
            return;
        }
        for (int slot = Math.min(35, contents.length - 1); slot >= 9; slot--) {
            ItemStack it = contents[slot];
            if (it == null || it.getType() == Material.AIR) {
                p.getInventory().setItem(slot, hunterAmmo());
                return;
            }
        }
        p.getInventory().addItem(hunterAmmo());
    }

    /** 是不是猎人那把弓（用来判定弹射物提示词与身份相关逻辑）。 */
    public boolean isHunterBow(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(hunterBowKey, PersistentDataType.STRING);
    }

    /** 真理之石（商店购买/侦探定期补给）：**右键打开菜单选玩家**，也可以左键直接点人；杀手→红石，无辜者→钻石。 */
    private ItemStack truthStone() {
        ItemStack it = new ItemStack(Material.STONE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true); // 局内不掉耐久
            meta.displayName(Chat.noItalic(LEGACY.deserialize("&f真理之石")));
            meta.lore(List.of(
                    LEGACY.deserialize("&7右键：打开菜单选择要探测的玩家"),
                    LEGACY.deserialize("&7左键点玩家：也能直接探测"),
                    LEGACY.deserialize("&7杀手→&c红石块&7，无辜者→&a钻石块")));
            meta.getPersistentDataContainer().set(truthKey, PersistentDataType.STRING, "1");
            it.setItemMeta(meta);
        }
        return it;
    }

    public boolean isTruthStone(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(truthKey, PersistentDataType.STRING);
    }

    /** 真理之石验人后变成的结果方块：写明探测对象与身份，留给侦探当证据（不带 truthKey，不能再用）。 */
    private ItemStack probeResultBlock(Material type, String displayName, String targetName, String identity) {
        ItemStack it = new ItemStack(type);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(LEGACY.deserialize(displayName)));
            meta.lore(List.of(
                    LEGACY.deserialize("&7真理之石探测：&f" + targetName),
                    LEGACY.deserialize("&7身份：" + identity),
                    LEGACY.deserialize("&8可丢给队友分享，对局中不能放置")));
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 真理之石探测的核心：目标是杀手则给红石块，否则给钻石块，并给本人提示 + 音效（结果仅本人收到）。
     *  杀手使用无意义且会换掉铁剑，直接拒绝不消耗并返回 {@code null}。 */
    private ItemStack truthResult(MurderMysteryArena a, Player user, Player target) {
        if (a.isMurderer(user.getUniqueId())) {
            msg(user, "&c你是杀手，真理之石对你没有意义，铁剑留好。");
            return null;
        }
        if (a.isMurderer(target.getUniqueId())) {
            user.getWorld().playSound(user.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.7f);
            msg(user, "&c" + target.getName() + " &f是杀手！真理之石变成了 &c红石块&f。");
            return probeResultBlock(Material.REDSTONE_BLOCK, "&c杀手罪证", target.getName(), "&c杀手");
        }
        user.getWorld().playSound(user.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        msg(user, "&a" + target.getName() + " &f是无辜者，真理之石变成了 &a钻石块&f。");
        return probeResultBlock(Material.DIAMOND_BLOCK, "&a无辜证明", target.getName(), "&a无辜者");
    }

    /** 真理之石左键直接点人探测：结果方块换掉主手那颗石头（右键菜单走 {@link #onTruthMenuClick}）。 */
    public void handleTruthStone(MurderMysteryArena a, Player user, Player target) {
        ItemStack result = truthResult(a, user, target);
        if (result != null) {
            user.getInventory().setItemInMainHand(result);
        }
    }

    /** 背包里真理之石所在的格子（主手优先，没有返回 -1）。 */
    public int findTruthStoneSlot(Player p) {
        int held = p.getInventory().getHeldItemSlot();
        if (isTruthStone(p.getInventory().getItem(held))) {
            return held;
        }
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isTruthStone(contents[i])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 真理之石右键菜单：一格里一颗本场**存活玩家**的头颅，点谁就探测谁。
     * <p>
     * 只列自己以外的存活玩家（已被淘汰的旁观者不列，免得白花一颗石头）；
     * 杀手打开只会被劝退（真理之石对杀手无意义，用了也只会换掉铁剑）。
     */
    public void openTruthMenu(Player p) {
        String name = playerArena.get(p.getUniqueId());
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || a.getState() != MurderMysteryArena.State.RUNNING
                || !a.getPlayers().contains(p.getUniqueId()) || a.getEliminated().contains(p.getUniqueId())) {
            msg(p, "&c对局中才能使用真理之石。");
            return;
        }
        if (a.isMurderer(p.getUniqueId())) {
            msg(p, "&c你是杀手，真理之石对你没有意义，铁剑留好。");
            return;
        }
        if (findTruthStoneSlot(p) < 0) {
            msg(p, "&c你身上已经没有真理之石了。");
            return;
        }
        List<Player> targets = new ArrayList<>();
        for (UUID id : a.getPlayers()) {
            if (id.equals(p.getUniqueId()) || a.getEliminated().contains(id)) {
                continue;
            }
            Player t = Bukkit.getPlayer(id);
            if (t != null && t.isOnline()) {
                targets.add(t);
            }
        }
        if (targets.isEmpty()) {
            msg(p, "&7场上没有其他存活玩家可以探测。");
            return;
        }
        int size = Math.min(54, ((targets.size() + 8) / 9) * 9);
        TruthMenuHolder holder = new TruthMenuHolder();
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(holder, size,
                LEGACY.deserialize("&8" + TRUTH_TITLE + " &7(" + targets.size() + " 人)"));
        holder.bind(inv);
        for (int i = 0; i < targets.size() && i < size; i++) {
            Player t = targets.get(i);
            inv.setItem(i, truthTargetButton(t));
            holder.target(i, t.getUniqueId());
        }
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.2f);
    }

    /** 真理之石菜单里的那颗头颅：名字 +「点击探测」（不写身份，也没有距离/状态等多余信息）。 */
    private ItemStack truthTargetButton(Player target) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta skull) {
            skull.setOwningPlayer(target);
            skull.displayName(Chat.noItalic(LEGACY.deserialize("&f" + target.getName())));
            skull.lore(List.of(
                    LEGACY.deserialize("&7点击：用真理之石探测 TA 的身份"),
                    LEGACY.deserialize("&8杀手→&c红石块&8，无辜者→&a钻石块")));
            it.setItemMeta(skull);
        }
        return it;
    }

    /**
     * 真理之石菜单点击：探测点中的那名玩家，并消耗一颗真理之石。
     * <p>
     * 点的时候 TA 可能刚被淘汰/下线：那就提示一句并重开一份最新的名单（下一 tick，避免在点击事件里重开界面）。
     * 界面开着的时候石头被别的插件挪走了也兜底处理（找不到石头就不消耗、只提示）。
     */
    public void onTruthMenuClick(Player p, TruthMenuHolder holder, int slot) {
        UUID targetId = holder.targetAt(slot);
        if (targetId == null || targetId.equals(p.getUniqueId())) {
            return;
        }
        String name = playerArena.get(p.getUniqueId());
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || a.getState() != MurderMysteryArena.State.RUNNING
                || !a.getPlayers().contains(p.getUniqueId()) || a.getEliminated().contains(p.getUniqueId())) {
            p.closeInventory();
            msg(p, "&c对局已经结束了。");
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline() || !a.getPlayers().contains(targetId)
                || a.getEliminated().contains(targetId)) {
            p.closeInventory();
            msg(p, "&cTA 已经不在了（刚被淘汰或离开了）。");
            Bukkit.getScheduler().runTask(plugin, () -> openTruthMenu(p));
            return;
        }
        int stoneSlot = findTruthStoneSlot(p);
        if (stoneSlot < 0) {
            p.closeInventory();
            msg(p, "&c你身上已经没有真理之石了。");
            return;
        }
        p.closeInventory();
        ItemStack result = truthResult(a, p, target);
        if (result == null) {
            return; // 杀手：不消耗
        }
        consumeTruthStone(p, stoneSlot, result);
    }

    /**
     * 用掉一颗真理之石：石头所在格换成结果方块。
     * 一叠多于一颗时只扣一颗，多出来的结果方块塞进背包空位（塞不下就掉在脚下）。
     */
    private void consumeTruthStone(Player p, int slot, ItemStack result) {
        ItemStack cur = p.getInventory().getItem(slot);
        if (!isTruthStone(cur)) {
            giveOrDrop(p, result);
            return;
        }
        if (cur.getAmount() > 1) {
            cur.setAmount(cur.getAmount() - 1);
            p.getInventory().setItem(slot, cur);
            giveOrDrop(p, result);
            return;
        }
        p.getInventory().setItem(slot, result);
    }

    /** 背包塞不下就掉在脚下（真理之石结果方块用）。 */
    private void giveOrDrop(Player p, ItemStack it) {
        Map<Integer, ItemStack> leftOver = p.getInventory().addItem(it);
        for (ItemStack l : leftOver.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), l);
        }
    }

    // ---------- 杀手的追踪指南针 ----------

    /** 杀手的追踪指南针：右键显示最近的存活玩家有多远，并把指针指向 TA。 */
    private ItemStack murdererCompass() {
        ItemStack it = new ItemStack(Material.COMPASS);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true); // 局内不掉耐久
            meta.displayName(Chat.noItalic(LEGACY.deserialize("&c追踪指南针")));
            meta.lore(List.of(
                    LEGACY.deserialize("&7自动指向最近的存活玩家"),
                    LEGACY.deserialize("&7目标死亡后自动换下一个"),
                    LEGACY.deserialize("&7右键：显示离你多远")));
            meta.getPersistentDataContainer().set(compassKey, PersistentDataType.STRING, "1");
            meta.getPersistentDataContainer().set(roleItemKey, PersistentDataType.STRING, "1");
            it.setItemMeta(meta);
        }
        return it;
    }

    public boolean isTrackCompass(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(compassKey, PersistentDataType.STRING);
    }

    /**
     * 用指南针找人：报出最近的存活玩家距离（**不报名字**，保持匿名），
     * 并把指南针的指针设到 TA 的位置。右键是"立刻重新锁最近的"，平时的自动指向由
     * {@link #updateCompassTargets(MurderMysteryArena)} 每 5 tick 刷新。
     */
    public void handleTrackCompass(Player p) {
        String name = playerArena.get(p.getUniqueId());
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || a.getState() != MurderMysteryArena.State.RUNNING) {
            msg(p, "&7指南针只在密室杀手对局中有效。");
            return;
        }
        Player nearest = nearestInnocent(a, p);
        if (nearest == null) {
            msg(p, "&7追踪指南针：附近没有其他存活的无辜者。");
            return;
        }
        a.getCompassTargets().put(p.getUniqueId(), nearest.getUniqueId());
        p.setCompassTarget(nearest.getLocation());
        p.playSound(p.getLocation(), Sound.ITEM_LODESTONE_COMPASS_LOCK, 1f, 1.4f);
        double distance = Math.sqrt(nearest.getLocation().distanceSquared(p.getLocation()));
        msg(p, "&c追踪指南针：&f最近的存活玩家在 &e" + String.format("%.1f", distance)
                + " &f格外&7（指针已指向 TA）。");
        // 最后 N 秒内，右键还能立即重画一次全图粒子线（0.5 秒节流，避免连点刷粒子）
        if (isEndgameRevealActive(a) && p.getCooldown(Material.COMPASS) == 0) {
            drawRevealLines(a, p);
            p.setCooldown(Material.COMPASS, 10);
        }
    }

    /**
     * 自动跟踪：每个杀手维护一个目标，每 5 tick 把指南针重新指向该目标当前位置；
     * 目标被淘汰/离开/换世界后，自动改指最近的存活无辜者。
     */
    private void updateCompassTargets(MurderMysteryArena a) {
        if (a.getState() != MurderMysteryArena.State.RUNNING) {
            return;
        }
        for (UUID mid : new ArrayList<>(a.getMurderers())) {
            Player killer = Bukkit.getPlayer(mid);
            if (killer == null || !killer.isOnline() || !a.getPlayers().contains(mid)) {
                a.getCompassTargets().remove(mid);
                continue;
            }
            UUID targetId = a.getCompassTargets().get(mid);
            Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
            boolean valid = target != null && target.isOnline()
                    && a.getPlayers().contains(targetId)
                    && !a.isMurderer(targetId)
                    && target.getWorld().equals(killer.getWorld());
            if (!valid) {
                target = nearestInnocent(a, killer);
                if (target == null) {
                    a.getCompassTargets().remove(mid);
                    continue;
                }
                a.getCompassTargets().put(mid, target.getUniqueId());
            }
            killer.setCompassTarget(target.getLocation());
        }
    }

    /** 最近的其他存活无辜者（杀手不算；观战者/离线玩家不算）。 */
    private Player nearestInnocent(MurderMysteryArena a, Player from) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (UUID id : a.getPlayers()) {
            if (id.equals(from.getUniqueId()) || a.isMurderer(id)) {
                continue;
            }
            Player other = Bukkit.getPlayer(id);
            if (other == null || !other.isOnline() || !other.getWorld().equals(from.getWorld())) {
                continue;
            }
            double distance = other.getLocation().distanceSquared(from.getLocation());
            if (distance < best) {
                best = distance;
                nearest = other;
            }
        }
        return nearest;
    }

    /** 目标被淘汰/离开时清掉相关映射（其他杀手的下一次刷新会自动换人）。 */
    private void forgetCompassTarget(MurderMysteryArena a, UUID id) {
        a.getCompassTargets().remove(id);
        a.getCompassTargets().values().removeIf(target -> target.equals(id));
    }

    /** 是否已进入"最后 N 秒杀手粒子指引"窗口。 */
    private boolean isEndgameRevealActive(MurderMysteryArena a) {
        int seconds = endgameRevealSeconds();
        if (seconds <= 0) {
            return false;
        }
        long leftMs = a.getGameEndMs() - System.currentTimeMillis();
        return leftMs > 0 && leftMs <= seconds * 1000L;
    }

    /** 最后 N 秒：每秒自动给每个杀手画一次粒子线，指向所有存活好人。 */
    private void drawEndgameReveal(MurderMysteryArena a) {
        if (!isEndgameRevealActive(a)) {
            return;
        }
        for (UUID mid : new ArrayList<>(a.getMurderers())) {
            Player killer = Bukkit.getPlayer(mid);
            if (killer == null || !killer.isOnline() || !a.getPlayers().contains(mid)) {
                continue;
            }
            drawRevealLines(a, killer);
        }
    }

    /** 给指定杀手画"到每个存活好人"的粒子线；只发给这个杀手本人。 */
    private void drawRevealLines(MurderMysteryArena a, Player killer) {
        if (!isEndgameRevealActive(a) || !a.isMurderer(killer.getUniqueId())) {
            return;
        }
        Particle particle = endgameRevealParticle();
        double step = endgameRevealStep();
        Location from = killer.getEyeLocation();
        for (UUID id : a.getPlayers()) {
            if (id.equals(killer.getUniqueId()) || a.isMurderer(id)) {
                continue;
            }
            Player target = Bukkit.getPlayer(id);
            if (target == null || !target.isOnline() || !target.getWorld().equals(from.getWorld())) {
                continue;
            }
            Location to = target.getLocation().add(0.0, 1.0, 0.0);
            drawParticleLine(killer, from, to, particle, step);
            // 终点再竖着补一小段，方便看清人在哪
            for (double y = 0; y <= 2.0; y += 0.5) {
                spawnRevealParticle(killer, particle, to.clone().add(0.0, y, 0.0));
            }
        }
    }

    /** 从 from 到 to 按 step 取样，只把粒子发给 viewer。 */
    private void drawParticleLine(Player viewer, Location from, Location to,
                                  Particle particle, double step) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.1) {
            return;
        }
        for (double distance = 0.0; distance <= length; distance += step) {
            double ratio = distance / length;
            Location point = new Location(from.getWorld(),
                    from.getX() + dx * ratio,
                    from.getY() + dy * ratio,
                    from.getZ() + dz * ratio);
            spawnRevealParticle(viewer, particle, point);
        }
    }

    /** 只给 viewer 一个人发一个粒子（force=true 让远距离也能看到）。 */
    private void spawnRevealParticle(Player viewer, Particle particle, Location location) {
        viewer.spawnParticle(particle, location.getX(), location.getY(), location.getZ(),
                1, 0.0, 0.0, 0.0, 0.0, null, true);
    }

    private ItemStack shopButton(Material mat, String name, String costLine, String... loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(LEGACY.deserialize(name)));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(LEGACY.deserialize(costLine));
            for (String l : loreLines) {
                lore.add(LEGACY.deserialize(l));
            }
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 商店道具被丢弃时提示。 */
    public void notifyShopLocked(Player p) {
        msg(p, "&c商店道具不能丢弃，右键打开商店。");
    }

    /** 职业装备（铁剑/追踪指南针/弩）被丢弃时提示：只提示本人，不会暴露身份给其他人。 */
    public void notifyRoleItemLocked(Player p) {
        msg(p, "&c职业装备不能丢弃（铁剑 / 追踪指南针 / 弩），局内只能自己用。");
    }

    // ---------- 尸体（被击杀掉落） ----------

    /**
     * 在被杀位置生成一具躺倒的尸体。
     * <p>
     * 用 Paper 的 {@link Mannequin}（完整玩家模型，不是盔甲架拼的），皮肤取自死者的
     * {@code PlayerProfile}，所以整个人都是死者的皮肤；不带任何标签，
     * 死者名字写进实体 PDC（右键认尸用，见 {@link #corpseOwner}）。
     */
    private void spawnCorpse(Player victim) {
        String arenaName = playerArena.get(victim.getUniqueId());
        if (arenaName == null) {
            return;
        }
        Location loc = victim.getLocation().clone();
        World w = loc.getWorld();
        if (w == null || loc.getY() < w.getMinHeight() + 1) {
            return; // 虚空 / 世界底部不生成
        }
        Location spawnAt = loc.clone().add(0, CORPSE_Y_OFFSET, 0);
        Mannequin corpse = w.spawn(spawnAt, Mannequin.class, m -> {
            m.setProfile(ResolvableProfile.resolvableProfile(victim.getPlayerProfile())); // 全身皮肤
            m.setPose(CORPSE_POSE);        // 躺在地上
            m.setImmovable(true);          // 不会被推走 / 不自己走动
            m.setGravity(false);
            m.setInvulnerable(true);
            m.setSilent(true);
            m.setCollidable(false);
            m.setPersistent(false);        // 局末由插件清理，不写进存档
            m.setDescription(Component.empty()); // Mannequin 自带说明文字，清掉免得变成头顶标签
            m.setCustomNameVisible(false);
            m.getPersistentDataContainer().set(corpseOwnerKey, PersistentDataType.STRING, victim.getName());
        });
        corpses.computeIfAbsent(keyOf(victim.getUniqueId()), k -> new ArrayList<>()).add(corpse);
    }

    /** 该实体是不是本插件生成的尸体；是则返回死者名字，否则 null。 */
    public String corpseOwner(org.bukkit.entity.Entity entity) {
        return entity == null ? null
                : entity.getPersistentDataContainer().get(corpseOwnerKey, PersistentDataType.STRING);
    }

    private void clearCorpses(MurderMysteryArena a) {
        List<Mannequin> list = corpses.remove(a.roomKey());
        if (list == null) {
            return;
        }
        for (Mannequin c : list) {
            if (!c.isDead()) {
                c.remove();
            }
        }
    }

    /** 服务器停止时清理全部尸体。 */
    public void clearAllCorpses() {
        for (List<Mannequin> list : corpses.values()) {
            for (Mannequin c : list) {
                if (!c.isDead()) {
                    c.remove();
                }
            }
        }
        corpses.clear();
    }

    // ---------- 杀手飞剑 ----------

    /** 杀手右键铁剑：投掷飞剑（冷却 THROW_COOLDOWN_MS）。 */
    public void handleSwordThrow(Player p) {
        String name = playerArena.get(p.getUniqueId());
        if (name == null) {
            return;
        }
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null || a.getState() != MurderMysteryArena.State.RUNNING) {
            return;
        }
        UUID id = p.getUniqueId();
        if (!a.isMurderer(id) || !a.getPlayers().contains(id)) {
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() != Material.IRON_SWORD) {
            return;
        }
        long now = System.currentTimeMillis();
        long cd = a.getThrowCooldown().getOrDefault(id, 0L);
        if (now < cd) {
            msg(p, "&c飞剑冷却中（&e" + Math.max(0, (cd - now) / 1000 + 1) + "s&c）。");
            return;
        }
        a.getThrowCooldown().put(id, now + THROW_COOLDOWN_MS);
        org.bukkit.util.Vector dir = p.getLocation().getDirection();
        Projectile proj = p.launchProjectile(org.bukkit.entity.Snowball.class);
        proj.setVelocity(dir.clone().multiply(2.0));
        proj.getPersistentDataContainer().set(throwKey, PersistentDataType.STRING, "1");
        // 判定用的雪球藏起来（不隐形的话玩家会看到一颗雪球跟着铁剑一起飞）
        if (proj instanceof org.bukkit.entity.ThrowableProjectile throwable) {
            throwable.setItem(new ItemStack(Material.AIR));
        }
        proj.setInvisible(true);
        spawnFlyingSwordDisplay(proj, Material.IRON_SWORD);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.9f);
        Chat.actionBar(p, "&c飞剑已掷出！");
    }

    /**
     * 给一把刚掷出的飞剑生成外形：ItemDisplay 拿一把铁剑，每个 tick 贴到雪球位置上、剑尖指向速度方向。
     * 用 ItemDisplay 而不是直接改雪球的材质，是因为原版飞出去的投掷物是**翻滚的平面图标**，
     * 剑尖不会指着飞行方向；ItemDisplay 可以自己写旋转。
     */
    private void spawnFlyingSwordDisplay(Projectile proj, Material material) {
        ItemDisplay display = proj.getWorld().spawn(proj.getLocation(), ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(material));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setViewRange(64f);
            d.setShadowRadius(0f);
            d.setShadowStrength(0f);
            d.setInterpolationDuration(1);
            d.setTeleportDuration(1);
            // 0.8 是"拿在手里那把剑"的观感尺寸，太大像门板、太小看不清
            d.setTransformation(new Transformation(new Vector3f(), swordRotation(proj.getVelocity()),
                    new Vector3f(0.8f, 0.8f, 0.8f), new Quaternionf()));
        });
        if (display == null) {
            return;
        }
        flyingSwords.put(proj.getUniqueId(), display);
        if (flyingSwordTask == null) {
            flyingSwordTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFlyingSwords, 1L, 1L);
        }
    }

    /** 飞剑命中/被清掉时立刻收走它的外形（否则要等下一个 tick 的任务兜底）。 */
    public void removeFlyingSwordDisplay(java.util.UUID projectileId) {
        ItemDisplay display = flyingSwords.remove(projectileId);
        if (display != null && !display.isDead()) {
            display.remove();
        }
    }

    /** 每 tick 把外形贴到雪球上；雪球一没（命中/超时/换世界）就把外形一起收掉。 */
    private void tickFlyingSwords() {
        if (flyingSwords.isEmpty()) {
            if (flyingSwordTask != null) {
                flyingSwordTask.cancel();
                flyingSwordTask = null;
            }
            return;
        }
        Iterator<Map.Entry<UUID, ItemDisplay>> it = flyingSwords.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ItemDisplay> e = it.next();
            ItemDisplay display = e.getValue();
            org.bukkit.entity.Entity projectile = Bukkit.getEntity(e.getKey());
            if (display == null || display.isDead() || projectile == null || projectile.isDead()) {
                if (display != null && !display.isDead()) {
                    display.remove();
                }
                it.remove();
                continue;
            }
            Location loc = projectile.getLocation();
            display.teleport(loc);
            display.setTransformation(new Transformation(new Vector3f(), swordRotation(projectile.getVelocity()),
                    new Vector3f(0.8f, 0.8f, 0.8f), new Quaternionf()));
        }
    }

    public boolean isThrownProjectile(Projectile proj) {
        return proj != null && proj.getPersistentDataContainer().has(throwKey, PersistentDataType.STRING);
    }

    // ---------- 对局期间强制开启 PvP ----------

    private void enableWorldPvp(MurderMysteryArena a) {
        if (a.getCenter() == null || a.getCenter().getWorld() == null) {
            plugin.getLogger().warning("无法开启 PvP：" + a.getName() + " 中心点或世界为空。");
            return;
        }
        String worldName = a.getCenter().getWorld().getName();
        int count = worldRunning.getOrDefault(worldName, 0) + 1;
        worldRunning.put(worldName, count);
        if (count == 1) {
            boolean prev = a.getCenter().getWorld().getPVP();
            worldPvpSaved.put(worldName, prev);
            a.getCenter().getWorld().setPVP(true);
            plugin.getLogger().info("对局开始：世界 " + worldName + " PvP 已临时开启（原 " + prev + "）。");
        }
    }

    private void restoreWorldPvp(MurderMysteryArena a) {
        if (a.getCenter() == null || a.getCenter().getWorld() == null) {
            return;
        }
        String worldName = a.getCenter().getWorld().getName();
        int count = worldRunning.getOrDefault(worldName, 0) - 1;
        if (count <= 0) {
            worldRunning.remove(worldName);
            Boolean prev = worldPvpSaved.remove(worldName);
            if (prev != null) {
                a.getCenter().getWorld().setPVP(prev);
                plugin.getLogger().info("对局结束：世界 " + worldName + " PvP 已恢复为 " + prev);
            }
        } else {
            worldRunning.put(worldName, count);
        }
    }

    /**
     * 关服兜底：把临时开启的 PvP 全部还原。
     * worldPvpSaved 只在内存里，对局中关服/热重载会让世界永久保持 PvP=true。
     */
    public void restoreAllWorldPvp() {
        for (Map.Entry<String, Boolean> e : worldPvpSaved.entrySet()) {
            World w = Bukkit.getWorld(e.getKey());
            if (w != null) {
                w.setPVP(e.getValue());
                plugin.getLogger().info("关服：世界 " + e.getKey() + " PvP 已恢复为 " + e.getValue());
            }
        }
        worldPvpSaved.clear();
        worldRunning.clear();
    }

    // ---------- 杀手飞剑的铁剑外形 ----------

    /**
     * 把飞剑的判定实体（雪球）和外形实体（ItemDisplay 铁剑）一起清掉。
     * <p>
     * 雪球是命中判定用的（箭矢/弹射物伤害走它），所以不能换成 ItemDisplay——
     * ItemDisplay 不产生伤害事件。做法是：雪球设为隐形（{@code setItem(AIR)} + {@code setInvisible(true)}），
     * 再另开一个只有外形、没有碰撞的 ItemDisplay 拿着铁剑，每个 tick 贴到雪球的位置并朝向它的速度方向，
     * 于是玩家看到的是一把铁剑飞出去、剑尖始终指着飞行方向，命中判定却仍然是原来那颗雪球。
     */
    public void clearFlyingSwords() {
        if (flyingSwordTask != null) {
            flyingSwordTask.cancel();
            flyingSwordTask = null;
        }
        for (ItemDisplay display : flyingSwords.values()) {
            if (display != null && !display.isDead()) {
                display.remove();
            }
        }
        flyingSwords.clear();
    }

    /** 铁剑默认是竖直朝上的（尖端指向 +Y），绕 X 轴转 135° 后剑尖指向 -Y，正好对齐"飞行速度方向"。 */
    private static final Quaternionf SWORD_TIP_DOWN = new Quaternionf().rotationX((float) Math.toRadians(135));

    /** 出一把新飞剑：剑尖与速度方向同向的旋转。 */
    private static Quaternionf swordRotation(org.bukkit.util.Vector dir) {
        Vector3f d = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ());
        if (d.lengthSquared() < 1.0e-6f) {
            return new Quaternionf(SWORD_TIP_DOWN);
        }
        // 把"竖直向上"转到飞行方向，再叠上"剑尖朝下"的基础朝向
        Quaternionf align = new Quaternionf().rotationTo(new Vector3f(0f, 1f, 0f), d.normalize());
        return align.mul(SWORD_TIP_DOWN, new Quaternionf());
    }

    /** 关服/热重载兜底：停掉后台金块扫描和飞剑同步任务，并把场上还在飞的飞剑清掉（不动别的插件）。 */
    public void cancelAllTasksAndGlow() {
        if (waitingTask != null) {
            waitingTask.cancel();
            waitingTask = null;
        }
        if (goldScanTask != null) {
            goldScanTask.cancel();
            goldScanTask = null;
        }
        for (BukkitTask t : compassTasks.values()) {
            t.cancel();
        }
        compassTasks.clear();
        goldScans.clear();
        clearFlyingSwords();
    }

    // ---------- 场内金锭定时刷新 ----------

    /** 按间隔在场地范围内随机生成掉落金锭（玩家可拾取）。 */
    private void maybeSpawnGold(MurderMysteryArena a) {
        long now = System.currentTimeMillis();
        long last = lastGoldSpawn.getOrDefault(a.getName(), 0L);
        if (now - last < goldSpawnInterval() * 1000L) {
            return;
        }
        lastGoldSpawn.put(a.getName(), now);
        spawnGold(a);
    }

    /**
     * 补金锭：**按金块列表的顺序**，每隔 gold-spawn-interval 给「接下来 gold-spawn-amount 个还没有金锭的
     * 金块」各补 1 个（默认每 10 秒 1 个），指针循环前进，到底了从头再来。
     * <p>
     * 所以金锭是慢慢铺开的，不会一瞬间铺满整张图；已经摆着金锭的金块会被跳过（每个金块上最多 1 个），
     * 玩家捡走后等指针绕回来就会补上。地图里一个金块都没有（或还没扫完）时才退回老的"随机地面"逻辑。
     */
    private void spawnGold(MurderMysteryArena a) {
        Location p1 = a.getPos1();
        Location p2 = a.getPos2();
        if (p1 == null || p2 == null || p1.getWorld() == null) {
            return;
        }
        World w = p1.getWorld();
        int minX = Math.min(p1.getBlockX(), p2.getBlockX());
        int maxX = Math.max(p1.getBlockX(), p2.getBlockX());
        int minZ = Math.min(p1.getBlockZ(), p2.getBlockZ());
        int maxZ = Math.max(p1.getBlockZ(), p2.getBlockZ());
        int minY = Math.min(p1.getBlockY(), p2.getBlockY());
        int maxY = Math.max(p1.getBlockY(), p2.getBlockY());

        // 主逻辑：按顺序从上次的位置往后找，补满 want 个（最多绕一圈）
        List<Location> golds = goldBlocks.get(a.getName());
        if (golds != null && !golds.isEmpty()) {
            int want = Math.max(1, goldSpawnAmount());
            int cursor = goldCursor.getOrDefault(a.getName(), 0);
            int placed = 0;
            for (int step = 0; step < golds.size() && placed < want; step++) {
                int idx = (cursor + step) % golds.size();
                Location g = golds.get(idx);
                World gw = g.getWorld();
                if (gw == null) {
                    continue;
                }
                int x = g.getBlockX(), y = g.getBlockY(), z = g.getBlockZ();
                if (!gw.isChunkLoaded(x >> 4, z >> 4)) {
                    continue; // 没加载的区块跳过（不强行加载区块；下次指针绕回来时再刷）
                }
                if (!canHoldIngot(gw.getBlockAt(x, y + 1, z))) {
                    continue; // 上方被实心方块压住，这个金块不刷
                }
                if (hasIngotOn(gw, x, y, z)) {
                    continue; // 上面已经躺着一个了，轮下一个
                }
                dropGold(gw, x, y + 1, z);
                placed++;
                cursor = idx + 1;
            }
            if (placed > 0) {
                goldCursor.put(a.getName(), cursor % golds.size());
            }
            return;
        }

        // 兜底：地图里没有金块 / 还没扫完 → 随机柱子从 maxY 往下找可到达的地面
        for (int i = 0; i < Math.max(1, goldSpawnAmount()); i++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                int x = minX + (int) (Math.random() * (maxX - minX + 1));
                int z = minZ + (int) (Math.random() * (maxZ - minZ + 1));
                int y = maxY;
                boolean found = false;
                for (; y >= minY; y--) {
                    if (w.getBlockAt(x, y, z).getType().isAir()
                            && !w.getBlockAt(x, y - 1, z).getType().isAir()) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    dropGold(w, x, y, z);
                    break;
                }
            }
        }
    }

    /**
     * 金块上方能不能放金锭：空气，或者能穿过去、又不是液体的装饰方块（草、花、火把、牌子…）。
     * 用"空气"太严会把压着装饰方块的金块永久排除掉，用"可穿过"又会让金锭掉进水里漂走。
     */
    private static boolean canHoldIngot(Block above) {
        return above.getType().isAir() || (above.isPassable() && !above.isLiquid());
    }

    /** 某个金块的顶面是不是已经躺着一个掉落金锭了（用于"只补空的"）。 */
    private boolean hasIngotOn(World w, int x, int y, int z) {
        Location center = new Location(w, x + 0.5, y + 1.25, z + 0.5);
        for (org.bukkit.entity.Entity e : w.getNearbyEntities(center, 0.75, 0.75, 0.75)) {
            if (e instanceof Item it && it.getItemStack().getType() == Material.GOLD_INGOT) {
                return true;
            }
        }
        return false;
    }

    /** 清掉场地范围内掉落的物品（开局与局末各清一次，免得上一局剩的金锭混进本局）。 */
    private void clearDroppedItems(MurderMysteryArena a) {
        Location p1 = a.getPos1();
        Location p2 = a.getPos2();
        if (p1 == null || p2 == null || p1.getWorld() == null) {
            return;
        }
        World w = p1.getWorld();
        double minX = Math.min(p1.getX(), p2.getX());
        double maxX = Math.max(p1.getX(), p2.getX());
        double minY = Math.min(p1.getY(), p2.getY());
        double maxY = Math.max(p1.getY(), p2.getY());
        double minZ = Math.min(p1.getZ(), p2.getZ());
        double maxZ = Math.max(p1.getZ(), p2.getZ());
        int removed = 0;
        for (Item it : w.getEntitiesByClass(Item.class)) {
            Location l = it.getLocation();
            if (l.getX() >= minX && l.getX() <= maxX && l.getY() >= minY && l.getY() <= maxY
                    && l.getZ() >= minZ && l.getZ() <= maxZ) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("场地「" + a.getName() + "」清理了 " + removed + " 个掉落物。");
        }
    }

    // ---------- 金块发现（分帧扫描 + 缓存） ----------

    /** 场地内已发现的金块坐标（地图是静态的，扫一次就长期复用）。 */
    private final Map<String, List<Location>> goldBlocks = new LinkedHashMap<>();
    /** 每个场地「下次从哪个金块开始补」的指针（按顺序轮着刷）。 */
    private final Map<String, Integer> goldCursor = new LinkedHashMap<>();
    /** 还没扫完的场地 -> 扫描进度。 */
    private final Map<String, GoldScan> goldScans = new LinkedHashMap<>();
    private BukkitTask goldScanTask;

    /**
     * 开始扫描某场地的金块（已经扫过/正在扫就跳过）。
     * <p>
     * 整块区域按列分帧扫，每 tick 只扫 {@link #GOLD_SCAN_COLUMNS_PER_TICK} 列，所以不会卡服；
     * 扫的过程中金锭先按老的兜底逻辑生成，扫完后就能稳稳刷在金块上。
     */
    private void startGoldScan(MurderMysteryArena a) {
        if (goldBlocks.containsKey(a.getName()) || goldScans.containsKey(a.getName())) {
            return;
        }
        GoldScan scan = GoldScan.of(a);
        if (scan == null) {
            goldBlocks.put(a.getName(), List.of()); // 场地没配好：记个空表，别反复扫
            return;
        }
        goldScans.put(a.getName(), scan);
        if (goldScanTask == null) {
            goldScanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::advanceGoldScans, 1L, 1L);
        }
    }

    /** 推进所有未完成的扫描；全部扫完就把循环任务停掉。 */
    private void advanceGoldScans() {
        Iterator<Map.Entry<String, GoldScan>> it = goldScans.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, GoldScan> e = it.next();
            GoldScan scan = e.getValue();
            scan.step(GOLD_SCAN_COLUMNS_PER_TICK);
            if (scan.finished()) {
                goldBlocks.put(e.getKey(), scan.found);
                it.remove();
                plugin.getLogger().info("场地「" + e.getKey() + "」扫描到 " + scan.found.size() + " 个金块。");
            }
        }
        if (goldScans.isEmpty() && goldScanTask != null) {
            goldScanTask.cancel();
            goldScanTask = null;
        }
    }

    /** 场地被编辑后要重新扫（金块位置可能变了）。 */
    private void invalidateGoldScan() {
        goldBlocks.clear();
        goldCursor.clear();
        goldScans.clear();
        if (goldScanTask != null) {
            goldScanTask.cancel();
            goldScanTask = null;
        }
    }

    /** 金块扫描状态描述（/mm goldblocks 用）。 */
    public String goldScanStatus(MurderMysteryArena a) {
        List<Location> done = goldBlocks.get(a.getName());
        if (done != null) {
            return "&a已扫描完成：&e" + done.size() + " &a个金块，补货指针在 &e#"
                    + goldCursor.getOrDefault(a.getName(), 0);
        }
        GoldScan scan = goldScans.get(a.getName());
        if (scan != null) {
            return "&e正在扫描：&f" + scan.progressText() + "&e，暂时找到 &f"
                    + scan.found.size() + " &e个";
        }
        return "&7尚未扫描（开局、开服或 /mm reload 时自动扫）";
    }

    /** 已扫描到的金块坐标（还没扫完就是空表）。 */
    public List<Location> goldBlockList(MurderMysteryArena a) {
        return goldBlocks.getOrDefault(a.getName(), List.of());
    }

    /**
     * 重载配置 + 重新扫描所有场地的金块。
     *
     * @return 处理的场地数；config.yml 有语法错误时返回 -1（此时原配置保持不动）
     */
    public int reloadConfigAndRescanGold() {
        try {
            plugin.reloadConfig();
        } catch (Exception e) {
            plugin.getLogger().warning("config.yml 有语法错误，本次重载已取消（原配置继续生效）：" + e.getMessage());
            return -1;
        }
        // 场地列表也来自 config.yml，所以顺手重读一遍；有对局在跑时先不动场地，免得把进行中的对局换掉
        boolean running = false;
        for (MurderMysteryArena a : arenas.values()) {
            if (a.getState() == MurderMysteryArena.State.RUNNING) {
                running = true;
                break;
            }
        }
        if (running) {
            plugin.getLogger().info("有对局正在进行：本次只重载规则数值，场地列表等这局结束再重载。");
        } else {
            load(); // 重读场地/告示牌/战绩
        }
        invalidateGoldScan();
        int count = 0;
        for (MurderMysteryArena a : arenas.values()) {
            startGoldScan(a);
            count++;
        }
        return count;
    }

    /** 按列（x,z）遍历场地范围内的方块找金块；每 tick 只走一部分列。 */
    private static final class GoldScan {
        private final World world;
        /** 需要检查的区块坐标 {cx, cz} —— **只存坐标，不加载区块**（加载挪到 step 里分帧做）。 */
        private final List<int[]> chunks;
        private final List<Location> found = new ArrayList<>();
        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;
        private final int minY;
        private final int maxY;
        /** 当前区块里待逐格检查的列。 */
        private final List<int[]> pendingColumns = new ArrayList<>();
        private int chunkIndex;
        private int columnIndex;

        private GoldScan(World world, List<int[]> chunks,
                         int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
            this.world = world;
            this.chunks = chunks;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.minY = minY;
            this.maxY = maxY;
        }

        /**
         * 场地没配置好（没 pos1/pos2/世界）时返回 null。
         * 先用 {@link org.bukkit.Chunk#contains} 过一遍区块：它查的是区块的调色板（很快），
         * 能确定"这个区块里根本没有金块"的直接跳过，只有可能有金块的区块才逐格确认。
         * （调色板判定不会漏报，最坏情况是多扫几个区块。）
         */
        static GoldScan of(MurderMysteryArena a) {
            Location p1 = a.getPos1();
            Location p2 = a.getPos2();
            if (p1 == null || p2 == null || p1.getWorld() == null) {
                return null;
            }
            World w = p1.getWorld();
            int minX = Math.min(p1.getBlockX(), p2.getBlockX());
            int maxX = Math.max(p1.getBlockX(), p2.getBlockX());
            int minZ = Math.min(p1.getBlockZ(), p2.getBlockZ());
            int maxZ = Math.max(p1.getBlockZ(), p2.getBlockZ());
            int minY = Math.max(w.getMinHeight(), Math.min(p1.getBlockY(), p2.getBlockY()));
            int maxY = Math.min(w.getMaxHeight() - 1, Math.max(p1.getBlockY(), p2.getBlockY()));

            // ⚠️ 这里**不能**调 w.getChunkAt()：它会**同步加载区块**（读 region 文件），
            // 一个大场地几十个区块就是几秒钟的主线程阻塞 —— 正是"开局卡一下 / 开服卡四秒"的真凶。
            // 现在只收集区块坐标，真正的加载 + 调色板检查都放到 step() 里分帧做。
            List<int[]> chunks = new ArrayList<>();
            for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    chunks.add(new int[]{cx, cz});
                }
            }
            return new GoldScan(w, chunks, minX, maxX, minZ, maxZ, minY, maxY);
        }

        /**
         * 推进扫描。**每 tick 最多加载 1 个区块**（这是最贵的操作），
         * 然后把该区块的列按 {@code budgetColumns} 逐格检查。
         */
        void step(int budgetColumns) {
            int done = 0;
            int chunkLoads = 0;
            while (done < budgetColumns) {
                if (columnIndex >= pendingColumns.size()) {
                    pendingColumns.clear();
                    columnIndex = 0;
                    if (chunkIndex >= chunks.size()) {
                        return;                        // 全部扫完
                    }
                    if (chunkLoads >= 1) {
                        return;                        // 这一 tick 已经加载过一个区块了，剩下的下次再说
                    }
                    chunkLoads++;
                    loadColumnsOf(chunks.get(chunkIndex++));
                    if (pendingColumns.isEmpty()) {
                        continue;                      // 该区块没有金块，接着看下一个（但受 chunkLoads 限制）
                    }
                }
                int[] c = pendingColumns.get(columnIndex++);
                for (int y = minY; y <= maxY; y++) {
                    if (world.getBlockAt(c[0], y, c[1]).getType() == Material.GOLD_BLOCK) {
                        found.add(new Location(world, c[0], y, c[1]));
                    }
                }
                done++;
            }
        }

        /** 加载一个区块并用调色板判定是否有金块；有则填出该区块内与选区相交的列。 */
        private void loadColumnsOf(int[] chunkCoords) {
            int cx = chunkCoords[0];
            int cz = chunkCoords[1];
            org.bukkit.Chunk chunk = world.getChunkAt(cx, cz);   // ← 唯一的同步加载点，已分帧
            if (!chunk.contains(Material.GOLD_BLOCK.createBlockData())) {
                return;                                          // 调色板里没有金块 → 不用逐格扫
            }
            int x0 = Math.max(minX, cx << 4);
            int x1 = Math.min(maxX, (cx << 4) + 15);
            int z0 = Math.max(minZ, cz << 4);
            int z1 = Math.min(maxZ, (cz << 4) + 15);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    pendingColumns.add(new int[]{x, z});
                }
            }
        }

        boolean finished() {
            return chunkIndex >= chunks.size() && columnIndex >= pendingColumns.size();
        }

        /** 进度：已处理区块 / 总区块（比"列数"更能反映真实进度，因为区块数才是耗时大头）。 */
        String progressText() {
            return "区块 " + chunkIndex + "/" + chunks.size();
        }
    }

    /** 在指定方块顶面生成一颗贴地的掉落金锭（无弹跳，稳稳落在表面）。 */
    private void dropGold(World w, int x, int y, int z) {
        Item drop = w.dropItem(new Location(w, x + 0.5, y + 0.35, z + 0.5),
                new ItemStack(Material.GOLD_INGOT, 1));
        drop.setVelocity(new Vector(0, 0, 0));
    }

    // ---------- 名字标签隐藏 ----------

    private static final String NAME_TAG_TEAM = "mm_nametag";

    /** 隐藏玩家头顶名字标签（对局中全员匿名，只能靠外观辨认持剑杀手）。 */
    private void hideNameTag(Player p) {
        if (p == null) {
            return;
        }
        // 对局中用了 LiveSidebar（另一块计分板），必须同时挂当前计分板和主计分板
        nameTagTeam(p.getScoreboard()).addEntry(p.getName());
        nameTagTeam(Bukkit.getScoreboardManager().getMainScoreboard()).addEntry(p.getName());
    }

    private Team nameTagTeam(Scoreboard board) {
        Team team = board.getTeam(NAME_TAG_TEAM);
        if (team == null) {
            team = board.registerNewTeam(NAME_TAG_TEAM);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
        return team;
    }

    private void hideNameTags(MurderMysteryArena a) {
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                hideNameTag(p);
            }
        }
    }

    /** 恢复玩家名字标签（当前计分板和主计分板都清掉）。 */
    private void showNameTag(Player p) {
        if (p == null) {
            return;
        }
        showNameTag(p, p.getScoreboard());
        showNameTag(p, Bukkit.getScoreboardManager().getMainScoreboard());
    }

    private void showNameTag(Player p, Scoreboard board) {
        if (board == null) {
            return;
        }
        Team team = board.getTeam(NAME_TAG_TEAM);
        if (team == null) {
            return;
        }
        team.removeEntry(p.getName());
        if (team.getEntries().isEmpty()) {
            team.unregister();
        }
    }

    private void showNameTags(MurderMysteryArena a) {
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                showNameTag(p);
            }
        }
        for (UUID id : a.getEliminated()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                showNameTag(p);
            }
        }
    }

    // ---------- 战绩（胜场 / 击杀） ----------

    public void recordWin(UUID id) {
        if (data == null) {
            return;
        }
        data.set("wins." + id, getWins(id) + 1);
        saveData();
    }

    public void recordKill(UUID id) {
        if (data == null) {
            return;
        }
        data.set("kills." + id, getKillsStat(id) + 1);
        saveData();
    }

    public int getWins(UUID id) {
        return data == null ? 0 : data.getInt("wins." + id, 0);
    }

    public int getKillsStat(UUID id) {
        return data == null ? 0 : data.getInt("kills." + id, 0);
    }

    public void resetStats() {
        if (data != null) {
            data.set("wins", null);
            data.set("kills", null);
            saveData();
        }
    }

    // ---------- 配置模式（道具快速配置） ----------

    public boolean isInSetup(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null && setup.isEditing(p);
    }

    public MurderMysteryArena getSetupArena(UUID id) {
        Player p = Bukkit.getPlayer(id);
        String name = p == null ? null : setup.editingArena(p);
        return name == null ? null : arenas.get(name);
    }

    /** 进入配置模式（备份背包、发工具、工具识别与退场还原都由公共库 SetupMode 负责）。 */
    public boolean enterSetup(Player p, String arenaName) {
        if (arenas.get(arenaName) == null) {
            msg(p, "&c场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        if (!enterSetupState(p, arenaName)) {
            msg(p, "&c你已在配置模式中，先退出（屏障工具）再进入其他场。");
            return false;
        }
        msg(p, "&a已进入 &e" + arenaName + "&a 的配置模式：");
        msg(p, "&b木棍&7 左键=范围角1 右键=范围角2");
        msg(p, "&e金锄头&7 右键=中心点 &f纸&7 右键=等待点");
        msg(p, "&6绿宝石&7 保存 &c屏障&7 退出");
        return true;
    }

    /** 静默进入配置模式（场地改名后续配用，不重复发提示）。 */
    private boolean enterSetupState(Player p, String arenaName) {
        return setup.enter(p, arenaName, List.of(
                SetupMode.Tool.both(Material.STICK, "pos",
                        "&b范围工具", "&7左键=范围角1 &8/&7右键=范围角2"),
                SetupMode.Tool.right(Material.GOLDEN_HOE, "center",
                        "&e中心点工具", "&7右键=中心点（当前位置）"),
                SetupMode.Tool.right(Material.PAPER, "lobby",
                        "&f等待点工具", "&7右键=等待点（当前位置）"),
                SetupMode.Tool.right(Material.EMERALD, "save",
                        "&6保存场", "&7右键=保存当前配置"),
                SetupMode.Tool.right(Material.BARRIER, "exit",
                        "&c退出配置模式", "&7右键=结束配置并恢复背包")
        ), setupHandler);
    }

    public void exitSetup(Player p) {
        setup.exit(p);
        msg(p, "&7已退出配置模式，背包已恢复。");
    }

    /** 配置工具交互处理。返回是否被本插件消费。 */
    public boolean handleSetupTool(Player p, Action action, Block clicked) {
        String arenaName = setup.editingArena(p);
        if (arenaName == null) {
            return false;
        }
        // 场已被删除：退出配置模式（保留原行为）
        if (arenas.get(arenaName) == null) {
            exitSetup(p);
            return true;
        }
        // 公共库负责识别工具、分发 save/exit，并把其余工具回调给 onTool
        return setup.handleInteract(p, action, null, clicked);
    }

    /** 公共库回调：按 toolId 写入本插件场地字段（save 由 onSave 处理）。 */
    private void applySetupTool(Player p, String arenaName, String toolId, Action action, Block clicked) {
        MurderMysteryArena a = liveArena(keyOf(p.getUniqueId()));
        if (a == null) {
            return;
        }
        switch (toolId) {
            case "pos" -> {
                if (action == Action.LEFT_CLICK_BLOCK && clicked != null) {
                    a.setPos1(clicked.getLocation());
                    msg(p, "&a已设置 &e" + arenaName + "&a 范围角1：&f" + shortLoc(clicked.getLocation())
                            + (a.getPos2() != null ? "（范围已定）" : "（接着右键定范围角2）"));
                    saveAll();
                } else if (action == Action.RIGHT_CLICK_BLOCK && clicked != null) {
                    a.setPos2(clicked.getLocation());
                    msg(p, "&a已设置 &e" + arenaName + "&a 范围角2：&f" + shortLoc(clicked.getLocation())
                            + (a.getPos1() != null ? "（范围已定）" : "（还差范围角1）"));
                    saveAll();
                }
            }
            case "center" -> {
                if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                    a.setCenter(p.getLocation());
                    msg(p, "&a已设置 &e" + arenaName + "&a 中心点：&f" + shortLoc(p.getLocation()));
                    saveAll();
                }
            }
            case "lobby" -> {
                if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                    a.setLobbyPoint(p.getLocation());
                    msg(p, "&a已设置 &e" + arenaName + "&a 等待点：&f" + shortLoc(p.getLocation()));
                    saveAll();
                }
            }
            default -> {
                // save / exit 由公共库处理，其余工具无动作
            }
        }
    }

    // ---------- 加入告示牌 ----------

    public void placeJoinSign(Player p, String arenaName) {
        if (arenas.get(arenaName) == null) {
            msg(p, "&c场不存在。");
            return;
        }
        pendingSign.put(p.getUniqueId(), arenaName);
        msg(p, "&a请放置/选择一个告示牌，然后 &e右键点击它&a，将其变为 &e" + arenaName + "&a 的加入牌。");
    }

    public boolean handleSignBind(Player p, Location loc) {
        String arenaName = pendingSign.remove(p.getUniqueId());
        if (arenaName == null) {
            return false;
        }
        joinSigns.computeIfAbsent(arenaName, k -> new ArrayList<>()).removeIf(x -> sameBlock(x, loc));
        joinSigns.computeIfAbsent(arenaName, k -> new ArrayList<>()).add(loc);
        refreshJoinSigns(arenaName);
        saveAll();
        msg(p, "&a已将告示牌变为 &e" + arenaName + "&a 的加入牌，右键点击即可加入。");
        return true;
    }

    public boolean isJoinSign(Location loc) {
        for (List<Location> locs : joinSigns.values()) {
            for (Location l : locs) {
                if (sameBlock(l, loc)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void onSignClick(Player p, Location loc) {
        for (Map.Entry<String, List<Location>> e : joinSigns.entrySet()) {
            for (Location l : e.getValue()) {
                if (sameBlock(l, loc)) {
                    join(p, e.getKey());
                    return;
                }
            }
        }
    }

    public void refreshJoinSigns(String arenaName) {
        List<Location> locs = joinSigns.get(arenaName);
        if (locs == null) {
            return;
        }
        MurderMysteryArena a = arenaByName(arenaName);
        int waiting = a == null ? 0 : a.getPlayers().size();
        boolean running = a != null && a.getState() == MurderMysteryArena.State.RUNNING;
        for (Location loc : locs) {
            if (!(loc.getBlock().getState() instanceof Sign sign)) {
                continue;
            }
            sign.line(0, LEGACY.deserialize("&a[加入] &e" + arenaName));
            sign.line(1, LEGACY.deserialize("&7右键点击加入"));
            sign.line(2, LEGACY.deserialize(running ? "&c游戏中" : "&7等待：" + waiting + "人"));
            sign.line(3, LEGACY.deserialize("&7战绩：/mm stats"));
            sign.update();
        }
    }

    private String shortLoc(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
    }

    private boolean sameBlock(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        return a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    // ---------- 辅助 ----------

    private void broadcast(MurderMysteryArena a, String msg) {
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                msg(p, msg);
            }
        }
        for (UUID id : a.getEliminated()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                msg(p, msg);
            }
        }
    }

    private void msg(Player p, String msg) {
        Chat.sendTitled(p, msg);
        if (p == null) {
            // **控制台驱动**（派对/脚本）时也要留痕：否则失败完全静默（R3）
            plugin.getLogger().info("[控制台] " + org.bukkit.ChatColor.stripColor(
                    org.bukkit.ChatColor.translateAlternateColorCodes('&', msg)));
        }
    }

    private String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p == null ? id.toString().substring(0, 6) : p.getName();
    }
}
