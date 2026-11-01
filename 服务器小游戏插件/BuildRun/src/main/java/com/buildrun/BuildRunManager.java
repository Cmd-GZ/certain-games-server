package com.buildrun;

import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.data.PlayerStats;
import com.hub.util.Chat;
import com.hub.util.LiveSidebar;
import com.hub.util.Top3;
import com.hub.util.StartCountdown;
import com.hub.util.SetupMode;
import com.hub.util.WaitCountdown;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 你建我跑核心：
 * <ol>
 *   <li>OP 圈一块模板场地 + 设衍生方向；开局按玩家人数沿方向复制出 N 块一模一样的场地；</li>
 *   <li>第一阶段（build-seconds，默认 180s = 3 分钟）：每人在自己场地里创造模式建跑酷，
 *       用道具菜单设置起点/终点；</li>
 *   <li>第二阶段：按加入顺序轮流——先建造者自己跑，通过后其他选手一起跑；
 *       碰到地板 / 跑出场地 / 超时都判失败并转旁观；建造者没通过就跳过这条；</li>
 *   <li>双向计分：建造者每被 1 人通过 +1，跑者每通过 1 条 +1；总分最高获胜并记入 players.yml。</li>
 * </ol>
 */
public class BuildRunManager {

    /** 游戏注册名（HubCore 菜单 / tab / 战绩播报都用这个显示名）。 */
    public static final String GAME = "你建我跑";

    /** 无碰撞计分板队伍（跑者之间不挤）。 */
    private static final String COLLIDE_TEAM = "br_nocollide";
    /** 建造阶段工具菜单在快捷栏第 1 格。 */
    private static final int MENU_SLOT = 0;
    /** 退出床在快捷栏第 9 格。 */
    private static final int LEAVE_SLOT = 8;
    /** 跑者道具「隐藏其他玩家」在快捷栏第 1 格（跑的时候背包是空的，只有这一件）。 */
    private static final int HIDE_SLOT = 0;
    /** 跑者道具的道具 id（PDC，和菜单道具同一套 br-tool 键）。 */
    private static final String HIDE_ITEM = "hide";
    /** OP 开始纸。 */
    private static final int START_SLOT = 4;
    /** 菜单里「设置起点/终点」的格子：点它领一根烈焰棒（左键方块 = 起点，右键方块 = 终点）。 */
    private static final int POINT_SLOT = 11;
    /** 菜单里「羊毛效果图鉴」的格子：点它进子菜单，16 种羊毛一排一格，鼠标悬停看效果。 */
    private static final int WOOL_INFO_SLOT = 13;
    /** 菜单里「完成建造」的格子：起点 + 终点都设好后点它 = 提前收工去旁观。 */
    private static final int DONE_SLOT = 15;
    /** 羊毛图鉴子菜单里「返回工具菜单」的格子（子菜单 27 格，前 16 格是 16 种羊毛）。 */
    static final int WOOL_BACK_SLOT = 26;

    /**
     * 默认羊毛效果表：材质名 → "效果名:等级"（等级 0 = I 级），config.yml 的 {@code wool.effects} 没写时用这份。
     * <p>
     * 绿色 / 粉红色羊毛是起点 / 终点标志，故意不给效果（跑者一开局就站在绿色羊毛上）；
     * 红色羊毛是死亡方块（{@code wool.death-blocks}），也不给药水效果。
     */
    private static final Map<String, String> DEFAULT_WOOL_EFFECTS = new LinkedHashMap<>();

    /** 说明道具里用的羊毛名：Minecraft 官方中文译名（zh_CN，如「淡蓝色羊毛」「黄绿色羊毛」）。 */
    private static final Map<String, String> WOOL_CN = new LinkedHashMap<>();

    static {
        DEFAULT_WOOL_EFFECTS.put("WHITE_WOOL", "JUMP_BOOST:0");
        DEFAULT_WOOL_EFFECTS.put("ORANGE_WOOL", "JUMP_BOOST:1");
        DEFAULT_WOOL_EFFECTS.put("MAGENTA_WOOL", "REGENERATION:0");
        DEFAULT_WOOL_EFFECTS.put("LIGHT_BLUE_WOOL", "DOLPHINS_GRACE:0");
        DEFAULT_WOOL_EFFECTS.put("YELLOW_WOOL", "SPEED:1");
        DEFAULT_WOOL_EFFECTS.put("LIME_WOOL", "LEVITATION:0");
        DEFAULT_WOOL_EFFECTS.put("CYAN_WOOL", "WATER_BREATHING:0");
        DEFAULT_WOOL_EFFECTS.put("BLUE_WOOL", "SLOWNESS:0");
        DEFAULT_WOOL_EFFECTS.put("BROWN_WOOL", "MINING_FATIGUE:0");
        // 红色羊毛是死亡方块（wool.death-blocks），不给药水效果：跑的时候踩到直接淘汰
        DEFAULT_WOOL_EFFECTS.put("BLACK_WOOL", "BLINDNESS:0");
        DEFAULT_WOOL_EFFECTS.put("GRAY_WOOL", "INVISIBILITY:0");
        DEFAULT_WOOL_EFFECTS.put("LIGHT_GRAY_WOOL", "GLOWING:0");
        DEFAULT_WOOL_EFFECTS.put("PURPLE_WOOL", "LEVITATION:1");

        WOOL_CN.put("WHITE_WOOL", "白色羊毛");
        WOOL_CN.put("ORANGE_WOOL", "橙色羊毛");
        WOOL_CN.put("MAGENTA_WOOL", "品红色羊毛");
        WOOL_CN.put("LIGHT_BLUE_WOOL", "淡蓝色羊毛");
        WOOL_CN.put("YELLOW_WOOL", "黄色羊毛");
        WOOL_CN.put("LIME_WOOL", "黄绿色羊毛");
        WOOL_CN.put("PINK_WOOL", "粉红色羊毛");
        WOOL_CN.put("GRAY_WOOL", "灰色羊毛");
        WOOL_CN.put("LIGHT_GRAY_WOOL", "淡灰色羊毛");
        WOOL_CN.put("CYAN_WOOL", "青色羊毛");
        WOOL_CN.put("PURPLE_WOOL", "紫色羊毛");
        WOOL_CN.put("BLUE_WOOL", "蓝色羊毛");
        WOOL_CN.put("BROWN_WOOL", "棕色羊毛");
        WOOL_CN.put("GREEN_WOOL", "绿色羊毛");
        WOOL_CN.put("RED_WOOL", "红色羊毛");
        WOOL_CN.put("BLACK_WOOL", "黑色羊毛");
    }

    /** 「羊毛效果一览」里的羊毛顺序（16 色，和创造背包一致）。绿色 / 粉红色是起点 / 终点标志，故意没效果。 */
    private static final List<Material> WOOL_ORDER = List.of(
            Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL, Material.LIGHT_BLUE_WOOL,
            Material.YELLOW_WOOL, Material.LIME_WOOL, Material.PINK_WOOL, Material.GRAY_WOOL,
            Material.LIGHT_GRAY_WOOL, Material.CYAN_WOOL, Material.PURPLE_WOOL, Material.BLUE_WOOL,
            Material.BROWN_WOOL, Material.GREEN_WOOL, Material.RED_WOOL, Material.BLACK_WOOL);

    private final BuildRunPlugin plugin;
    private final LiveSidebar liveSidebar;
    private final Map<String, BuildRunArena> arenas = new LinkedHashMap<>();
    private final Map<UUID, String> playerArena = new LinkedHashMap<>();
    private final Map<String, WaitCountdown> waits = new LinkedHashMap<>();
    private final SetupMode setup;
    private final NamespacedKey toolKey;
    private YamlConfiguration data;
    private BukkitTask ticker;
    /** 踩羊毛给效果 + 终点站满 1 秒的细粒度检查任务（不走 1s 的 ticker）。 */
    private BukkitTask woolTicker;
    /** 建造者开跑前的「看建筑」展示 + 失败后「多看几秒」的推进任务（2 tick）。 */
    private BukkitTask cueTicker;
    /** 跑者在终点方块上开始站的时刻（毫秒）；一离开方块就移除 = 重新计时。 */
    private final Map<UUID, Long> endHoldSince = new LinkedHashMap<>();
    /**
     * 跑者把谁藏起来了：viewer（跑者）UUID → 被这个跑者藏起来的玩家 UUID。
     * <p>
     * 只记我们自己藏的人：恢复时只 show 这些，不会顺手把别的插件（如密室杀手）藏的玩家一起放出来。
     */
    private final Map<UUID, Set<UUID>> hiddenFrom = new LinkedHashMap<>();
    /**
     * 隐藏道具的去重时间戳：viewer UUID → 上一次成功切换的毫秒。
     * <p>
     * 为什么需要（2026-10-18 用户实测"对空气能切、对方块没反应"）：一次物理右键，客户端会连着发
     * {@code ServerboundUseItemOnPacket}（点方块）和 {@code ServerboundUseItemPacket}（用物品）两个包。
     * 服务端本来自己去重（{@code ServerGamePacketListenerImpl#handleUseItem}：{@code firedInteract} +
     * 同一位置/手 + {@code ItemStack.isSameItemSameComponents}），但我们的道具一按就换名字和图标
     * （组件变了）→ 去重条件不成立 → 再补发一次 {@code RIGHT_CLICK_BLOCK} → 藏完立刻又放出来，
     * 净效果为零；对空气点只走一条分支，所以"空气能、方块不能"。
     * 两次真实点击之间至少隔 4 tick（客户端 rightClickDelay），150ms 足够把同一次点击的两个包并掉。
     */
    private final Map<UUID, Long> lastHideToggle = new LinkedHashMap<>();
    /** 隐藏道具去重窗口（毫秒）：同一次物理右键的两个事件间距 &lt; 1 tick。 */
    private static final long HIDE_TOGGLE_DEBOUNCE_MS = 150L;
    /** 解析好的羊毛效果（材质 → 效果列表），第一次用到时从 config.yml 读一次。 */
    private final Map<Material, List<WoolEffect>> woolEffects = new LinkedHashMap<>();
    private boolean woolEffectsLoaded;
    /** 死亡方块（默认红色羊毛）：跑的时候踩到立刻淘汰；第一次用到时从 config.yml 读一次。 */
    private final Set<Material> deathBlocks = new LinkedHashSet<>();
    private boolean deathBlocksLoaded;

    public BuildRunManager(BuildRunPlugin plugin) {
        this.plugin = plugin;
        this.liveSidebar = new LiveSidebar(plugin);
        this.setup = new SetupMode(plugin, "br-setup-tool");
        this.toolKey = new NamespacedKey(plugin, "br-tool");
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    public void load() {
        startCountdown();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("arenas");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                ConfigurationSection one = sec.getConfigurationSection(name);
                if (one == null) {
                    continue;
                }
                BuildRunArena a = BuildRunArena.load(name, one);
                arenas.put(name, a);
            }
        }
        data = PlayerStats.load("buildrun", new File(plugin.getDataFolder(), "data.yml"));

        // 启动时把实际生效的两个时长打进控制台：改配置后不用开一局就能确认读到了没有。
        // 面板上"本地 180、线上还是 120"这种问题，第一条要看的就是这两行：读完的是哪个文件、
        // 文件多大、最后修改时间、build-seconds 到底取自文件还是内置默认值。
        int buildSeconds = Math.max(10, cfg("build-seconds", 180));
        plugin.getLogger().info("时长：建造阶段 " + buildSeconds
                + "s，每条跑酷限时 " + Math.max(10, cfg("run-seconds", 90)) + "s（来自 config.yml）");
        plugin.getLogger().info(describeConfigFile());

        // 启动后把已有场地上报给 HubCore（延迟等 HubCore 就绪）；顺带兜底登记本游戏
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HubAPI.registerGame(GAME, GAME, "LADDER", "3");
            for (BuildRunArena a : arenas.values()) {
                reportArena(a);
            }
        }, 40L);

        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 20L);
        int woolPeriod = Math.max(1, cfg("wool.refresh-ticks", 5));
        woolTicker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWoolAndEnd, woolPeriod, woolPeriod);
        cueTicker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickCue, 2L, 2L);
    }

    /** 关服：停任务、把所有复制出来的场地清回去、还原配置模式里的 OP。 */
    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        if (woolTicker != null) {
            woolTicker.cancel();
            woolTicker = null;
        }
        if (cueTicker != null) {
            cueTicker.cancel();
            cueTicker = null;
        }
        endHoldSince.clear();
        for (BuildRunArena a : arenas.values()) {
            a.cancelTask();
            PlotCopier c = a.getCopier();
            a.setCopier(null);
            // 关服也算"结束游戏"：先把终点高亮实体删掉，再清场内实体、清回方块
            clearEndGlow(a);
            clearFieldEntities(a);
            if (c != null) {
                if (a.getPhase() == BuildRunArena.Phase.COPYING) {
                    c.cancel();
                }
                c.restoreSilently();
            }
            a.setPhase(BuildRunArena.Phase.WAITING);
            a.getPlayers().clear();
            a.getPlots().clear();
            a.getScores().clear();
            a.clearCourse();
        }
        // 关服 / 插件卸载：把"看不见别人"的跑者全部恢复，别把隐身状态带出去
        for (UUID id : new ArrayList<>(hiddenFrom.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                showOthers(p);
            }
        }
        hiddenFrom.clear();
        setup.exitAll();
    }

    public Map<String, BuildRunArena> getArenas() {
        return arenas;
    }

    public BuildRunArena getArena(String name) {
        return arenas.get(name);
    }

    public String arenaOf(UUID id) {
        return playerArena.get(id);
    }

    // ==================================================================
    // 配置 / 战绩落盘
    // ==================================================================

    /**
     * 声明地盘（{@code docs/框架不变量.md} 的 I2）：把每个场地的边界报给框架。
     * 只在内存里读字段，不碰配置；还没建场的场地会被 {@code Regions} 静默跳过。
     */
    public void publishRegions() {
        com.hub.region.Regions.begin("buildrun");
        arenas.forEach((name, a) ->
                com.hub.region.Regions.box("buildrun", name, a.getPos1(), a.getPos2()));
    }

    public void saveAll() {
        publishRegions();   // 场地可能刚被改过，重新声明（I2）
        // 先把磁盘上的当前值读进内存，再只重写 arenas 段。
        // 否则关服时（onDisable -> saveAll -> saveConfig）会用内存里的旧值覆盖 config.yml：
        // 线上手改了 build-seconds 之类的键，传上去又被旧值写回去，表现为"改了配置没生效"。
        plugin.reloadConfig();
        ConfigurationSection sec = com.hub.world.Worlds.getOrCreate(plugin.getConfig(), "arenas");
        for (Map.Entry<String, BuildRunArena> e : arenas.entrySet()) {
            e.getValue().save(com.hub.world.Worlds.getOrCreate(sec, e.getKey()));
        }
        plugin.saveConfig();
        plugin.getLogger().info("config.yml 已写回（只重写 arenas 段；build-seconds 等键保持磁盘上的值）");
        saveData();
    }

    private void saveData() {
        if (data == null) {
            return;
        }
        try {
            PlayerStats.save("buildrun", data);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存战绩失败：" + ex.getMessage());
        }
    }

    /** 保存场地配置（不抓快照：模板区域永远不被修改，不需要还原）。 */
    public void saveArena(Player p, BuildRunArena a) {
        saveAll();
        msg(p, "&a已保存场 &e" + a.getName() + "&a：" + a.describe());
        if (a.getSpawn() == null) {
            msg(p, "&c还没设出生点（&e/br spawn&c），不能开局。");
        }
    }

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

    // ==================================================================
    // 场地管理（命令层调用）
    // ==================================================================

    public boolean createArena(String name) {
        if (!validName(name) || arenas.containsKey(name)) {
            return false;
        }
        arenas.put(name, new BuildRunArena(name));
        return true;
    }

    /** 删除场（仅等待且无人时）。 */
    public boolean removeArena(String name) {
        BuildRunArena a = arenas.get(name);
        if (a == null || a.getPhase() != BuildRunArena.Phase.WAITING || !a.getPlayers().isEmpty()) {
            return false;
        }
        arenas.remove(name);
        waits.remove(name);
        plugin.getConfig().set("arenas." + name, null);
        plugin.saveConfig();
        HubAPI.removeArena(GAME, name);
        return true;
    }

    /** 场地名校验：1~20 字符，仅中英文/数字/下划线/短横线（与其他游戏一致）。 */
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

    // ==================================================================
    // 加入 / 离开
    // ==================================================================

    public boolean join(Player p, String arenaName) {
        BuildRunArena a = arenas.get(arenaName);
        if (a == null) {
            msg(p, "&c场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        UUID id = p.getUniqueId();
        if (playerArena.containsKey(id)) {
            msg(p, "&c你已在其他场。");
            return false;
        }
        if (a.getPhase() != BuildRunArena.Phase.WAITING) {
            msg(p, "&c该场正在游戏中（或正在清理场地）。");
            return false;
        }
        if (!a.isReady()) {
            msg(p, "&c这个场还没建好（范围、出生点、方向）。");
            return false;
        }
        // 人数上限已彻底取消：这里**不读** max-players（老配置里写 8 也不生效），来几个人就复制几块场地
        playerArena.put(id, arenaName);
        a.getPlayers().add(id);

        p.setGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        p.setHealth(20);
        p.setFoodLevel(20);
        p.setFallDistance(0);
        Location loc = a.getLobby() != null ? a.getLobby() : a.getSpawn();
        if (loc != null) {
            p.teleport(loc);
        }
        HubAPI.setGame(id, GAME);
        giveWaitingItems(p);
        msg(p, "&a你已加入 &e" + arenaName + "&a，等待开始…");
        broadcast(a, "&e" + p.getName() + "&f 加入了（&e" + a.getPlayers().size() + "&f 人）");
        HubAPI.announceJoin(GAME, p.getName(), arenaName);
        updateWaitCountdown(a);
        reportArena(a);
        return true;
    }

    public void leave(Player p) {
        leave(p, false);
    }

    /**
     * 离开场地。{@code silent=true} 用于主城"复原大厅状态"的回调（进服复原 / 右键回主城道具）：
     * 玩家本来就不在场中时直接返回，不打任何提示。
     */
    public void leave(Player p, boolean silent) {
        UUID id = p.getUniqueId();
        String arenaName = playerArena.remove(id);
        if (arenaName == null) {
            if (!silent) {
                msg(p, "&c你不在任何场中。");
            }
            return;
        }
        BuildRunArena a = arenas.get(arenaName);
        if (a != null) {
            onPlayerRemoved(a, id, p.getName(), p);
        }
        removeCollideEntry(p);
        showOthers(p);
        forgetHidden(p);
        p.setGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        HubAPI.clearGame(id);
        goHome(p);
        if (!silent) {
            msg(p, "&7你已离开。");
        }
        if (a != null) {
            if (a.getPhase() == BuildRunArena.Phase.WAITING) {
                updateWaitCountdown(a);
            }
            reportArena(a);
        }
    }

    /** 玩家掉线：不走传送 / 回主城，只把状态收干净。 */
    public void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        String arenaName = playerArena.remove(id);
        if (arenaName == null) {
            return;
        }
        BuildRunArena a = arenas.get(arenaName);
        if (a != null) {
            onPlayerRemoved(a, id, p.getName(), null);
            reportArena(a);
        }
        removeCollideEntry(p);
        // 掉线：状态直接清掉（对面已经收不到包了，重进时按新会话重新算）
        forgetHidden(p);
    }

    /**
     * 从对局里摘掉一个人（退出 / 掉线共用）：必要时跳过当前跑酷、结束游戏。
     *
     * @param p 玩家本人（掉线时为 null，避免对离线玩家操作）
     */
    private void onPlayerRemoved(BuildRunArena a, UUID id, String name, Player p) {
        boolean wasBuilder = id.equals(a.getBuilder());
        a.getPlayers().remove(id);
        a.getPlots().remove(id);
        a.getScores().remove(id);
        boolean wasActive = a.getActive().remove(id);
        a.getPassed().remove(id);
        a.getFailed().remove(id);
        a.getBuildDone().remove(id);
        if (a.getPhase() == BuildRunArena.Phase.WAITING) {
            return;
        }
        if (a.getPlayers().isEmpty()) {
            broadcast(a, "&7场上没人了，结束本局。");
            endGame(a);
            return;
        }
        if (a.getPhase() != BuildRunArena.Phase.RUN) {
            if (a.getPhase() == BuildRunArena.Phase.COPYING || a.getPhase() == BuildRunArena.Phase.BUILDING) {
                broadcast(a, "&e" + name + "&f 离开了（剩 &e" + a.getPlayers().size() + "&f 人）。");
            }
            return;
        }
        if (wasBuilder && !a.isOthersRun()) {
            broadcast(a, "&c" + name + "&f 离开了，这条跑酷直接跳过。");
            skipCourse(a);
            return;
        }
        if (wasActive && a.getActive().isEmpty()) {
            finishCourse(a);
        }
    }

    // ==================================================================
    // 开局流程
    // ==================================================================

    private StartCountdown startCountdown;

    private StartCountdown startCountdown() {
        if (startCountdown == null) {
            startCountdown = new StartCountdown(plugin);
        }
        return startCountdown;
    }

    public boolean start(Player op, String arenaName) {
        BuildRunArena a = arenas.get(arenaName);
        if (a == null) {
            msg(op, "&c场不存在。");
            return false;
        }
        if (a.getPhase() != BuildRunArena.Phase.WAITING) {
            msg(op, "&c该场正在游戏中（或正在清理场地）。");
            return false;
        }
        int min = cfg("min-players", 2);
        if (a.getPlayers().size() < min) {
            msg(op, "&c人数不足（至少 &e" + min + "&c 人）。");
            return false;
        }
        int[] tb = a.templateBounds();
        if (tb == null || a.getSpawn() == null || a.getWorld() == null) {
            msg(op, "&c场地没配置好：需要圈范围 pos1/pos2 + 出生点 spawn。");
            return false;
        }
        long volume = BuildRunArena.volume(tb);
        long maxVolume = cfg("max-volume", 100000);
        if (volume > maxVolume) {
            msg(op, "&c场地太大（&e" + volume + "&c 格 > 上限 &e" + maxVolume + "&c），每块都要复制，先圈小一点。");
            return false;
        }

        if (startCountdown().begin(arenaName, new ArrayList<>(a.getPlayers()), () -> start(op, arenaName))) {
            return true;
        }
        a.getScores().clear();
        a.getPlots().clear();
        a.setTurn(0);
        a.clearCourse();
        a.getPlayers().forEach(id -> a.getScores().put(id, 0));

        World world = a.getWorld();
        int n = a.getPlayers().size();
        List<int[]> dests = new ArrayList<>();
        List<UUID> order = new ArrayList<>(a.getPlayers());
        for (int i = 0; i < n; i++) {
            UUID id = order.get(i);
            int[] b = a.plotBounds(i + 1);
            if (b == null) {
                msg(op, "&c计算第 " + (i + 1) + " 块场地范围失败。");
                return false;
            }
            BuildRunPlot plot = new BuildRunPlot(id, i + 1, b, world, a.plotSpawn(i + 1));
            a.getPlots().put(id, plot);
            dests.add(b);
        }

        a.setPhase(BuildRunArena.Phase.COPYING);
        a.nextGuard();
        wait(a).cancel();
        // 开局：先清场——模板 + 本局每块复制体范围里的非玩家实体全删（上一局留下的生物/载具/掉落物）
        clearFieldEntities(a);
        PlotCopier copier = new PlotCopier(plugin, world, tb, dests, cfg("copy-blocks-per-tick", 4000),
                new PlotCopier.Callback() {
                    @Override
                    public void onProgress(int done, int total) {
                        for (UUID id : a.getPlayers()) {
                            Player p = Bukkit.getPlayer(id);
                            if (p != null && p.isOnline()) {
                                Chat.actionBar(p, "&b正在复制场地 &e" + done + "&7/&e" + total + "&b …");
                            }
                        }
                    }

                    @Override
                    public void onDone() {
                        if (a.getPhase() == BuildRunArena.Phase.COPYING) {
                            beginBuild(a);
                        }
                    }
                });
        a.setCopier(copier);
        copier.startCopy();

        for (UUID id : a.getPlayers()) {
            HubAPI.setPlaying(id, GAME);
        }
        broadcast(a, "&3&l— 你建我跑开始！正在复制 &e" + n + " &3&l块场地… —");
        reportArena(a);
        return true;
    }

    /** 复制完成：把每人送到自己的场地，发创造模式 + 道具菜单，开始建造倒计时。 */
    private void beginBuild(BuildRunArena a) {
        if (a.getPhase() != BuildRunArena.Phase.COPYING) {
            return;
        }
        a.setPhase(BuildRunArena.Phase.BUILDING);
        int seconds = Math.max(10, cfg("build-seconds", 180));
        a.setDeadline(System.currentTimeMillis() + seconds * 1000L);
        // 开建前先把场地里的掉落物清干净（上一局 / 复制过程留下的都算）
        clearPlotItems(a);
        for (UUID id : new ArrayList<>(a.getPlayers())) {
            Player p = Bukkit.getPlayer(id);
            BuildRunPlot plot = a.getPlots().get(id);
            if (p == null || !p.isOnline() || plot == null) {
                continue;
            }
            p.setGameMode(GameMode.CREATIVE);
            p.setAllowFlight(true);
            p.setFlying(true);
            p.setHealth(20);
            p.setFoodLevel(20);
            p.setFireTicks(0);
            p.setFallDistance(0);
            p.getInventory().clear();
            p.teleport(plot.getSpawn());
            giveBuildItems(p);
            Chat.title(p, "&b&l建造开始", "&f右键第 1 格道具，领烈焰棒设起点/终点", 3);
        }
        broadcast(a, "&b&l建造开始！&f每人造一条跑酷，&e" + seconds + "s&f 后轮流跑。");
        broadcast(a, "&7① 右键 &b第 1 格道具&7 → 领 &6烈焰棒&7：&a左键方块设起点&7、&c右键方块设终点&7。");
        broadcast(a, "&7② 起点要比地板高 1 格；地板和起点/终点方块都拆不掉。");
        announceDeathBlock(a);
        broadcast(a, "&7③ 建完点「&a完成建造&7」；终点站满 1 秒算通过，踩地板/掉出去/超时算失败。");
        broadcast(a, "&7得分：自己跑过 1 条 &e+1&7，建造者按别人通过率拿 &e0~3&7 分。");
    }

    /** 建造时间到：进入轮流跑。 */
    private void endBuild(BuildRunArena a) {
        if (a.getPhase() != BuildRunArena.Phase.BUILDING) {
            return;
        }
        a.setPhase(BuildRunArena.Phase.RUN);
        a.setTurn(0);
        a.nextGuard();
        // 建造结束的那一刻再清一遍掉落物：接下来就是生存跑，地上不许有东西可捡
        clearPlotItems(a);
        broadcast(a, "&b&l建造结束！&7开始轮流跑，顺序按加入顺序。");
        for (UUID id : new ArrayList<>(a.getPlayers())) {
            BuildRunPlot plot = a.getPlots().get(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && (plot == null || !plot.isReady())) {
                msg(p, "&c你没设起点/终点，这条会被跳过。");
            }
        }
        nextCourse(a);
    }

    // ==================================================================
    // 轮流跑
    // ==================================================================

    /** 找下一位建造者开跑；都跑完就结算。 */
    private void nextCourse(BuildRunArena a) {
        if (a.getPhase() != BuildRunArena.Phase.RUN) {
            return;
        }
        a.clearCourse();
        while (a.getTurn() < a.getPlayers().size()) {
            UUID id = a.getPlayers().get(a.getTurn());
            a.setTurn(a.getTurn() + 1);
            BuildRunPlot plot = a.getPlots().get(id);
            Player p = Bukkit.getPlayer(id);
            String name = p == null ? shortId(id) : p.getName();
            if (plot == null) {
                continue;
            }
            if (p == null || !p.isOnline()) {
                broadcast(a, "&7" + name + " 不在线，跳过。");
                continue;
            }
            if (!plot.isReady()) {
                broadcast(a, "&7" + name + " 没设置起点/终点，跳过这条跑酷。");
                continue;
            }
            startCourse(a, id, plot);
            return;
        }
        endGame(a);
    }

    /**
     * 轮到某位建造者：先让所有人（含建造者本人）旁观看这条跑酷 {@code run-preview-seconds} 秒，
     * 到点才传送就位开跑（展示期不计时、不判失败；0 = 不展示，直接开跑）。
     * <p>
     * 展示期只报"这是谁的建筑"（聊天/标题/行动条都是建造者名字），不再喊「先看 Ns」。
     */
    private void startCourse(BuildRunArena a, UUID builderId, BuildRunPlot plot) {
        a.clearCourse();
        endHoldSince.clear();
        // 上一条的终点高亮（如果有）跟着这条一起换掉；本条的高亮只在"其他人跑"时才挂
        clearEndGlow(a);
        a.setBuilder(builderId);
        a.setOthersRun(false);
        int preview = runPreviewSeconds();
        if (preview <= 0) {
            beginBuilderRun(a);
            return;
        }
        a.setPreviewUntil(System.currentTimeMillis() + preview * 1000L);
        a.setDeadline(0L);
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                spectator(p, plot);
            }
        }
        setCollide(a, true);
        String name = nameOf(builderId);
        broadcast(a, "&3&l第 " + a.getTurn() + " 条 &8» &e" + name + " &7的建筑");
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.title(p, "&e" + name + " 的建筑", "&7" + preview + "s 后开跑", 3);
            }
        }
    }

    /** 展示结束（或 {@code run-preview-seconds: 0}）：建造者到起点、其他人旁观，开始计时。 */
    private void beginBuilderRun(BuildRunArena a) {
        UUID builderId = a.getBuilder();
        BuildRunPlot plot = a.currentPlot();
        if (builderId == null || plot == null) {
            skipCourse(a);
            return;
        }
        Player builder = Bukkit.getPlayer(builderId);
        if (builder == null || !builder.isOnline()) {
            broadcast(a, "&7" + nameOf(builderId) + " 不在线，跳过这条。");
            skipCourse(a);
            return;
        }
        int seconds = Math.max(10, cfg("run-seconds", 90));
        long now = System.currentTimeMillis();
        a.setPreviewUntil(0L);
        // 传送即开跑：没有"不许动"冻结，计时从这一刻起算
        a.setDeadline(now + seconds * 1000L);

        // 先传送、再把人加进"正在跑"：传送瞬间即使有 PlayerMoveEvent 也不会误判失败
        setUpRunner(builder, plot);
        a.getActive().add(builderId);
        for (UUID id : a.getPlayers()) {
            if (id.equals(builderId)) {
                continue;
            }
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                spectator(p, plot);
            }
        }
        setCollide(a, true);
        broadcast(a, "&3&l第 " + a.getTurn() + " 条 &8» &e" + builder.getName() + " &7的跑酷，&e建造者先跑！");
        announceDeathBlock(a);
        Chat.title(builder, "&e你的跑酷", "&f" + seconds + "s 内到终点！", 2);
        if (!plot.isSafeHeight(plot.getStart(), floorTolerance())) {
            msg(builder, "&c起点在地板线下，一落地就失败。");
        }
    }

    /** 建造者通过：其他选手一起开跑。 */
    private void startOthersRun(BuildRunArena a) {
        BuildRunPlot plot = a.currentPlot();
        if (plot == null) {
            return;
        }
        a.setOthersRun(true);
        endHoldSince.clear();
        int seconds = Math.max(10, cfg("run-seconds", 90));
        long now = System.currentTimeMillis();
        // 传送即开跑：没有"不许动"冻结，计时从这一刻起算
        a.setDeadline(now + seconds * 1000L);
        Player builder = Bukkit.getPlayer(a.getBuilder());
        if (builder != null) {
            spectator(builder, plot);
        }
        for (UUID id : a.getPlayers()) {
            if (id.equals(a.getBuilder())) {
                continue;
            }
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                continue;
            }
            setUpRunner(p, plot);
            a.getActive().add(id);
        }
        // 计分分母：这一刻有资格跑的人（后面掉线的照样算"没通过"，避免分母缩水把难度刷低）
        a.setRunners(a.getActive().size());
        if (a.getActive().isEmpty()) {
            finishCourse(a);
            return;
        }
        // 非建造者（其他选手）一起跑：把终点方块点亮，跑者在起点就能看到终点在哪
        updateEndGlow(a);
        broadcast(a, "&a&l其他人开跑！&f碰地板、掉出去就算失败。");
        announceDeathBlock(a);
        for (UUID id : a.getActive()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Chat.title(p, "&a开跑！", "&f" + seconds + "s 内到终点", 2);
            }
        }
    }

    /** 某个人跑完（成功 / 失败）。 */
    private void onRunnerDone(BuildRunArena a, Player p, boolean success) {
        onRunnerDone(a, p, success, null);
    }

    /**
     * 某个人跑完（成功 / 失败）。
     *
     * @param failCause 这次失败的原因短语（如「踩到死亡方块了」）；null = 默认的「碰到地板/跑出场地了」。
     */
    private void onRunnerDone(BuildRunArena a, Player p, boolean success, String failCause) {
        UUID id = p.getUniqueId();
        if (!a.getActive().remove(id)) {
            return;
        }
        endHoldSince.remove(id);
        BuildRunPlot plot = a.currentPlot();
        if (!a.isOthersRun()) {
            if (success) {
                broadcast(a, "&a&l" + p.getName() + " 通过了！&f其他人开跑。");
                Chat.title(p, "&a通过！", "&7看别人跑你的跑酷", 2);
                startOthersRun(a);
            } else {
                broadcast(a, "&c&l" + p.getName()
                        + (failCause == null ? " 没跑完自己的跑酷" : " " + failCause) + "&7，跳过这条。");
                builderFailed(a, p);
            }
            return;
        }
        if (success) {
            a.getPassed().add(id);
            broadcast(a, "&a" + p.getName() + " 通过了！&7（&e" + a.getPassed().size() + "&7 人）");
        } else {
            a.getFailed().add(id);
            broadcast(a, "&c" + p.getName() + (failCause == null ? " 碰到地板了！" : " " + failCause + "！")
                    + "&7（剩 &e" + a.getActive().size() + "&7 人）");
        }
        spectator(p, plot);
        if (a.getActive().isEmpty()) {
            finishCourse(a);
        }
    }

    /**
     * 建造者得分：按通过率给分（通过的人越少 = 跑酷越难 = 分越高）。
     * <pre>
     * 通过 ≥ 3/4 → 0 分；≥ 1/2 → +1；≥ 1/4 → +2；&lt; 1/4 → +3
     * 分母 = 建造者通过那一刻有资格跑的其他人（{@link BuildRunArena#getRunners()}）。
     * 全用整数比较，避免浮点边界抖动；"≥" 一律取含边界（等于 3/4 也算 3/4 以上）。
     * </pre>
     */
    private int builderPoints(int passed, int runners) {
        if (runners <= 0) {
            return 0;
        }
        if (passed * 4 >= runners * 3) {
            return 0;
        }
        if (passed * 2 >= runners) {
            return 1;
        }
        if (passed * 4 >= runners) {
            return 2;
        }
        return 3;
    }

    /** 一条跑酷结束：算分、播报、下一条。 */
    private void finishCourse(BuildRunArena a) {
        // 这条跑完了：终点高亮实体跟着删掉（下一条的会在"其他人跑"那一刻重新挂）
        clearEndGlow(a);
        UUID builderId = a.getBuilder();
        int passedCount = a.getPassed().size();
        int runnerCount = a.getRunners();
        if (builderId != null && a.getPlayers().contains(builderId)) {
            int points = builderPoints(passedCount, runnerCount);
            a.getScores().merge(builderId, points, Integer::sum);
            for (UUID id : a.getPassed()) {
                a.getScores().merge(id, 1, Integer::sum);
            }
            String name = nameOf(builderId);
            if (runnerCount <= 0) {
                broadcast(a, "&7" + name + " 的跑酷：没别人能跑，这条不计分。");
            } else {
                broadcast(a, "&7" + name + " 的跑酷：&e" + passedCount + "&7/&e" + runnerCount
                        + "&7 人通过 → 建造者 &e+" + points + "&7 分。");
            }
        }
        broadcastScores(a);
        a.clearCourse();
        nextCourse(a);
    }

    /** 跳过一条跑酷（建造者没通过 / 掉线 / 没设置）。 */
    private void skipCourse(BuildRunArena a) {
        clearEndGlow(a);
        if (a.getBuilder() == null && a.getActive().isEmpty()) {
            nextCourse(a);
            return;
        }
        BuildRunPlot plot = a.currentPlot();
        if (plot != null) {
            Player builder = Bukkit.getPlayer(a.getBuilder());
            if (builder != null && builder.isOnline() && a.isOthersRun()) {
                spectator(builder, plot);
            }
        }
        a.clearCourse();
        nextCourse(a);
    }

    /**
     * 建造者没通过（碰地板 / 跑出场地 / 超时）：先别切成旁观——就留在原地（对局中本来就免伤）
     * 让观众看清失败那一下，{@code fail-linger-seconds} 秒后再由 {@link #tickCue()} 转旁观并跳过这条；
     * 0 = 和以前一样立刻旁观 + 跳过。
     */
    private void builderFailed(BuildRunArena a, Player p) {
        long linger = failLingerMs();
        if (linger > 0L && p != null && p.isOnline()) {
            a.setLingerUntil(System.currentTimeMillis() + linger);
            Chat.title(p, "&c没通过", "&f" + (linger / 1000L) + "s 后换下一条", 2);
            broadcast(a, "&7再看 " + (linger / 1000L) + "s，然后继续。");
            return;
        }
        spectator(p, a.currentPlot());
        skipCourse(a);
    }

    /** 失败后的"多看几秒"结束：建造者转旁观，跳过这条。 */
    private void endBuilderLinger(BuildRunArena a) {
        a.setLingerUntil(0L);
        Player builder = Bukkit.getPlayer(a.getBuilder());
        if (builder != null && builder.isOnline()) {
            spectator(builder, a.currentPlot());
        }
        skipCourse(a);
    }

    /** 当前这次尝试超时。 */
    private void handleTimeout(BuildRunArena a) {
        if (a.getPhase() != BuildRunArena.Phase.RUN) {
            return;
        }
        // 展示期没有 deadline、失败收尾期也不该再超时：这两种时间窗都交给 tickCue
        if (a.getPreviewUntil() > 0L || a.getLingerUntil() > 0L) {
            return;
        }
        int seconds = Math.max(10, cfg("run-seconds", 90));
        if (!a.isOthersRun()) {
            Player builder = Bukkit.getPlayer(a.getBuilder());
            broadcast(a, "&c&l超时！&e" + nameOf(a.getBuilder()) + " &7没跑完，跳过这条。");
            builderFailed(a, builder);
            return;
        }
        List<UUID> left = new ArrayList<>(a.getActive());
        a.getActive().clear();
        for (UUID id : left) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                a.getFailed().add(id);
                BuildRunPlot plot = a.currentPlot();
                if (plot != null) {
                    spectator(p, plot);
                }
                msg(p, "&c时间到，没到终点，判失败。");
            }
        }
        broadcast(a, "&c&l超时！&7还有 &e" + left.size() + "&7 人没到终点，算失败。");
        finishCourse(a);
    }

    // ==================================================================
    // 结算
    // ==================================================================

    private void updateLiveBoard(BuildRunArena a) {
        if (a.getPhase() != BuildRunArena.Phase.BUILDING
                && a.getPhase() != BuildRunArena.Phase.RUN) {
            return;
        }
        List<UUID> online = new ArrayList<>(a.getPlayers());
        online.sort(Comparator.comparingInt((UUID id) -> a.getScores().getOrDefault(id, 0)).reversed()
                .thenComparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        for (UUID id : online) {
            lines.add(new LiveSidebar.Line("§e" + nameOf(id), a.getScores().getOrDefault(id, 0)));
        }
        liveSidebar.update("buildrun:" + a.getName(), "&3&l你建我跑 · 分数", online, lines);
    }

    private void endGame(BuildRunArena a) {
        if (a.getPhase() == BuildRunArena.Phase.WAITING || a.getPhase() == BuildRunArena.Phase.ENDING) {
            return;
        }
        liveSidebar.hide("buildrun:" + a.getName());
        a.setPhase(BuildRunArena.Phase.ENDING);
        a.cancelTask();
        a.nextGuard();
        setCollide(a, false);

        List<UUID> ranked = ranked(a);
        broadcast(a, "&3&l========== 你建我跑 结束 ==========");
        if (!ranked.isEmpty()) {
            List<UUID> firsts = new ArrayList<>();
            List<UUID> seconds = new ArrayList<>();
            List<UUID> thirds = new ArrayList<>();
            if (scoreOf(a, ranked.get(0)) > 0) firsts.add(ranked.get(0));
            if (ranked.size() >= 2 && scoreOf(a, ranked.get(1)) > 0) seconds.add(ranked.get(1));
            if (ranked.size() >= 3 && scoreOf(a, ranked.get(2)) > 0) thirds.add(ranked.get(2));
            Top3.broadcast(msg -> broadcast(a, msg), id -> nameOf(id) + " &7(" + scoreOf(a, id) + " 分)",
                    firsts, seconds, thirds);
            if (firsts.isEmpty()) {
                broadcast(a, "&7本局没有人得分，不计名次。");
            }
            Top3.award(data, firsts, seconds, thirds);
            saveData();
        } else {
            broadcast(a, "&7本局没有产生冠军。");
        }
        broadcast(a, "&3&l===================================");

        List<UUID> everyone = new ArrayList<>(a.getPlayers());
        for (UUID id : everyone) {
            playerArena.remove(id);
        }
        // 收尾：先把终点高亮和场内实体清干净（建造阶段召出来的生物/载具/盔甲架/展示框等），再清空本局数据
        clearEndGlow(a);
        clearFieldEntities(a);
        a.getPlayers().clear();
        a.getPlots().clear();
        a.getScores().clear();
        a.clearCourse();
        endHoldSince.clear();

        for (UUID id : everyone) {
            Player p = Bukkit.getPlayer(id);
            HubAPI.clearGame(id);
            if (p != null && p.isOnline()) {
                showOthers(p);
                forgetHidden(p);
                p.setGameMode(GameMode.SURVIVAL);
                p.getInventory().clear();
                p.setAllowFlight(false);
                p.setFlying(false);
                p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
                goHome(p);
            }
        }

        PlotCopier copier = a.getCopier();
        a.setCopier(null);
        if (copier != null) {
            copier.cancel();
            copier.startRestore(() -> {
                a.setPhase(BuildRunArena.Phase.WAITING);
                reportArena(a);
                broadcast(a, "&7场地已清回原样，可以再开一局。");
            });
        } else {
            a.setPhase(BuildRunArena.Phase.WAITING);
            reportArena(a);
        }
    }

    /** 强制结束（OP 命令 / 关服前）。 */
    public void stop(BuildRunArena a) {
        if (a == null) {
            return;
        }
        if (a.getPhase() == BuildRunArena.Phase.WAITING) {
            return;
        }
        endGame(a);
    }

    private List<UUID> ranked(BuildRunArena a) {
        List<UUID> list = new ArrayList<>(a.getScores().keySet());
        list.sort((x, y) -> {
            int sx = scoreOf(a, x);
            int sy = scoreOf(a, y);
            if (sx != sy) {
                return Integer.compare(sy, sx);
            }
            int ix = a.getPlayers().indexOf(x);
            int iy = a.getPlayers().indexOf(y);
            if (ix < 0) {
                ix = Integer.MAX_VALUE;
            }
            if (iy < 0) {
                iy = Integer.MAX_VALUE;
            }
            return Integer.compare(ix, iy);
        });
        return list;
    }

    private int scoreOf(BuildRunArena a, UUID id) {
        return a.getScores().getOrDefault(id, 0);
    }

    private void broadcastScores(BuildRunArena a) {
        StringBuilder sb = new StringBuilder();
        for (UUID id : a.getPlayers()) {
            if (sb.length() > 0) {
                sb.append(" &7· ");
            }
            sb.append("&f").append(nameOf(id)).append(" &e").append(scoreOf(a, id));
        }
        if (sb.length() > 0) {
            broadcast(a, "&7当前积分： " + sb);
        }
    }

    // ==================================================================
    // 终点高亮（只在"其他选手一起跑"那段）
    // ==================================================================

    /**
     * 「其他选手一起跑」时，在终点方块（粉红色羊毛）位置挂一个<b>发光的方块显示实体</b>：
     * 发光轮廓隔墙可见，跑者从起点就能看到终点在哪；这条跑完 / 轮下一条 / 收尾时删掉。
     * <p>
     * 口径（用户确认）：只给<b>非建造者</b>跑的那段挂高亮，建造者自己跑、建造阶段、展示期都不挂。
     * 方块显示没有碰撞、没有 AI、不能被攻击，也不会挡住跑者；只在本条跑酷期间存在。
     * 开关 = {@code config.yml} 的 {@code highlight-end}（默认 true，写 false 就完全不挂）。
     */
    private void updateEndGlow(BuildRunArena a) {
        boolean need = a.isOthersRun()
                && a.getPhase() == BuildRunArena.Phase.RUN
                && a.getPreviewUntil() == 0L
                && a.getLingerUntil() == 0L
                && !a.getActive().isEmpty()
                && highlightEnd();
        BuildRunPlot plot = a.currentPlot();
        Location end = plot == null ? null : plot.getEnd();
        if (!need || end == null || end.getWorld() == null) {
            clearEndGlow(a);
            return;
        }
        BlockDisplay existing = endGlowOf(a);
        if (existing != null
                && existing.getWorld().equals(end.getWorld())
                && existing.getLocation().getBlockX() == end.getBlockX()
                && existing.getLocation().getBlockY() == end.getBlockY()
                && existing.getLocation().getBlockZ() == end.getBlockZ()) {
            return;
        }
        clearEndGlow(a);
        Location at = end.getBlock().getLocation();
        BlockDisplay display = at.getWorld().spawn(at, BlockDisplay.class, d -> {
            // 形状 = 终点那块的材质（粉红色羊毛），这样轮廓就是"终点方块本身"的形状
            d.setBlock(Bukkit.createBlockData(BuildRunPlot.END_MARK));
            d.setGlowing(true);
            d.setGlowColorOverride(Color.fromRGB(0xFF69B4));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setPersistent(false);
            d.setInvulnerable(true);
            d.setSilent(true);
            // 方块模型从实体位置朝 +x/+y/+z 各铺 1 格；缩小 0.998 + 平移 0.001 让它正好藏进
            // 终点那块羊毛里：只露出发光轮廓，不会和那块真方块共面闪烁（发光轮廓不受这个缩放影响）。
            d.setTransformation(new Transformation(
                    new Vector3f(0.001f, 0.001f, 0.001f),
                    new Quaternionf(),
                    new Vector3f(0.998f, 0.998f, 0.998f),
                    new Quaternionf()));
        });
        a.setEndGlow(display.getUniqueId());
    }

    /** 当前挂着的高亮实体（没挂 / 已被删 / 不是方块显示 → null）。 */
    private BlockDisplay endGlowOf(BuildRunArena a) {
        UUID id = a.getEndGlow();
        if (id == null) {
            return null;
        }
        Entity e = Bukkit.getEntity(id);
        return e instanceof BlockDisplay display && display.isValid() ? display : null;
    }

    /** 删掉本条的高亮实体（没挂就什么都不做）。 */
    private void clearEndGlow(BuildRunArena a) {
        UUID id = a.getEndGlow();
        a.setEndGlow(null);
        if (id == null) {
            return;
        }
        Entity e = Bukkit.getEntity(id);
        if (e != null) {
            e.remove();
        }
    }

    /** config.yml 的 {@code highlight-end}（默认开）。 */
    private boolean highlightEnd() {
        return plugin.getConfig().getBoolean("highlight-end", true);
    }

    // ==================================================================
    // 每秒 tick：倒计时 / 超时
    // ==================================================================

    private void tickAll() {
        for (BuildRunArena a : arenas.values()) {
            // 终点高亮：该有的时候在（被清掉/换场也能自己补回来），不该有的时候 1s 内删掉
            updateEndGlow(a);
            // 展示期 / 失败后的"多看几秒"：行动条和收尾都由 tickCue 管，别在这儿刷"剩余 0s"或误判超时
            if (a.getPreviewUntil() > 0L || a.getLingerUntil() > 0L) {
                continue;
            }
            switch (a.getPhase()) {
                case BUILDING -> {
                    long left = a.secondsLeft();
                    for (UUID id : a.getPlayers()) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null && p.isOnline()) {
                            if (a.getBuildDone().contains(id)) {
                                Chat.actionBar(p, "&7已建完 · 旁观 · 剩 &e" + left + "s");
                            } else {
                                Chat.actionBar(p, "&b建造中 · 剩 &e" + left + "s &7· 右键第 1 格道具");
                            }
                        }
                    }
                    // 时间到，或者全员都点了「完成建造」（含有人退出后剩下的都点完）→ 提前开跑
                    if (left <= 0 || allBuildDone(a)) {
                        endBuild(a);
                    }
                }
                case RUN -> {
                    long left = a.secondsLeft();
                    for (UUID id : a.getPlayers()) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null && p.isOnline()) {
                            String who = a.isOthersRun() ? "其他人跑" : "建造者跑";
                            Chat.actionBar(p, "&e" + who + " · 剩 &e" + left + "s");
                        }
                    }
                    if (left <= 0) {
                        handleTimeout(a);
                    }
                }
                default -> {
                }
            }
            updateLiveBoard(a);
        }
        // 顺手清掉落物（1s 一次；落地那一刻由 ItemSpawnEvent 直接拦掉，这里是兜底）
        sweepPlotItems();
    }

    // ==================================================================
    // 建造者开跑前的「看建筑」展示 / 失败后「多看几秒」
    // ==================================================================

    /**
     * 每 2 tick 推进两个"看跑酷"的时间窗（都存时间戳，到点自己收尾）：
     * <ul>
     *   <li><b>展示期</b>（{@code run-preview-seconds}）：轮到建造者时所有人先旁观这条跑酷，
     *       到点才调 {@link #beginBuilderRun} 传送到起点开跑；</li>
     *   <li><b>失败收尾</b>（{@code fail-linger-seconds}）：建造者没通过后留在原地让观众看，
     *       到点才转旁观、跳过这条。</li>
     * </ul>
     * 展示期没有 deadline（{@code secondsLeft()} 恒为 0），所以 {@link #tickAll()} 和
     * {@link #handleTimeout} 都会跳过这两个窗口，超时不会误触发。
     */
    private void tickCue() {
        long now = System.currentTimeMillis();
        for (BuildRunArena a : arenas.values()) {
            if (a.getPhase() != BuildRunArena.Phase.RUN) {
                a.setPreviewUntil(0L);
                a.setLingerUntil(0L);
                continue;
            }
            long previewUntil = a.getPreviewUntil();
            if (previewUntil > 0L) {
                long left = previewUntil - now;
                if (left <= 0L) {
                    a.setPreviewUntil(0L);
                    beginBuilderRun(a);
                    continue;
                }
                String s = String.format("%.1f", left / 1000.0);
                String name = nameOf(a.getBuilder());
                for (UUID id : a.getPlayers()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        Chat.actionBar(p, "&b&l" + name + " 的建筑 &7· &f" + s + "s&f 后开跑");
                    }
                }
            }
            long lingerUntil = a.getLingerUntil();
            if (lingerUntil > 0L) {
                long left = lingerUntil - now;
                if (left <= 0L) {
                    endBuilderLinger(a);
                    continue;
                }
                Player builder = Bukkit.getPlayer(a.getBuilder());
                if (builder != null && builder.isOnline()) {
                    Chat.actionBar(builder, "&c&l没通过 &7· &f" + String.format("%.1f", left / 1000.0) + "s&f 后继续");
                }
            }
        }
    }

    // ==================================================================
    // 羊毛特殊效果 / 终点站满 1 秒
    // ==================================================================

    /** 一条羊毛效果：效果类型 + 等级（0 = I 级）。 */
    private record WoolEffect(PotionEffectType type, int amplifier) {
    }

    /**
     * 每 {@code wool.refresh-ticks} tick 跑一次：
     * <ul>
     *   <li>踩在死亡方块（默认红色羊毛）上：跑者立刻淘汰，建造阶段只提示；</li>
     *   <li>踩在羊毛上就刷新对应药水效果（建造阶段也生效，方便建造者自己试跑）；</li>
     *   <li>跑者站在终点方块（粉红色羊毛）上连续累计满 {@code wool.end-hold-ms} 毫秒 → 判通过。</li>
     * </ul>
     * 离开羊毛/终点方块就丢掉计时或不再刷新，所以效果会自然剩到时间结束、终点要"站稳"。
     */
    private void tickWoolAndEnd() {
        long now = System.currentTimeMillis();
        long holdMs = Math.max(200L, cfg("wool.end-hold-ms", 1000));
        for (BuildRunArena a : arenas.values()) {
            BuildRunArena.Phase phase = a.getPhase();
            if (phase != BuildRunArena.Phase.BUILDING && phase != BuildRunArena.Phase.RUN) {
                continue;
            }
            // 展示期 / 失败收尾：没人在跑，羊毛效果和终点计时都不用刷
            if (a.getPreviewUntil() > 0L || a.getLingerUntil() > 0L) {
                continue;
            }
            for (UUID id : new ArrayList<>(a.getPlayers())) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.isOnline() || p.getGameMode() == GameMode.SPECTATOR) {
                    endHoldSince.remove(id);
                    continue;
                }
                boolean onDeathBlock = isDeathBlockBelow(p);
                if (onDeathBlock && phase == BuildRunArena.Phase.RUN
                        && a.getActive().contains(id)) {
                    // 红色羊毛 = 死亡方块：跑者踩上立刻淘汰（和碰地板走同一条路：判失败、转旁观）
                    onRunnerDeath(a, p);
                    break; // 本条已经结束：角色/场地都可能换了，别再往下遍历
                }
                if (onDeathBlock) {
                    // 建造阶段只提示、不判死（不然拿红色羊毛当装饰就会反复自杀）
                    endHoldSince.remove(id);
                    Chat.actionBar(p, "&4&l死亡方块：开跑后踩到就淘汰");
                    continue;
                }
                applyWoolEffect(p);
                if (phase != BuildRunArena.Phase.RUN || !a.getActive().contains(id)) {
                    endHoldSince.remove(id);
                    continue;
                }
                BuildRunPlot plot = a.currentPlot();
                if (plot == null || !plot.isStandingOnEnd(p.getLocation())) {
                    endHoldSince.remove(id);
                    continue;
                }
                Long since = endHoldSince.get(id);
                if (since == null) {
                    endHoldSince.put(id, now);
                    Chat.actionBar(p, "&e踩到终点了！&7再站稳 &e" + (holdMs / 1000.0) + "s");
                    continue;
                }
                long held = now - since;
                if (held >= holdMs) {
                    endHoldSince.remove(id);
                    onRunnerDone(a, p, true);
                    break; // 本条已经结束：角色/场地都可能换了，别再往下遍历
                }
                Chat.actionBar(p, "&a站稳… &e" + String.format("%.1f", held / 1000.0) + "s");
            }
        }
    }

    /** 脚底那一格是羊毛就给药水效果（每次检查刷新，离开后效果还留 wool.effect-seconds 秒）。 */
    private void applyWoolEffect(Player p) {
        List<WoolEffect> list = woolEffects().get(blockUnderFeet(p));
        if (list == null || list.isEmpty()) {
            return;
        }
        int ticks = Math.max(20, cfg("wool.effect-seconds", 5) * 20);
        for (WoolEffect e : list) {
            p.addPotionEffect(new PotionEffect(e.type(), ticks, e.amplifier(), true, true, true));
        }
    }

    // ==================================================================
    // 死亡方块（默认红色羊毛）
    // ==================================================================

    /** 玩家脚底那一格材质（下探 0.1 格：站在方块上沿时正好取到脚下那块）。 */
    private static Material blockUnderFeet(Player p) {
        return p.getLocation().clone().subtract(0, 0.1, 0).getBlock().getType();
    }

    /** 脚底是死亡方块？（建造阶段也用它，只做提示） */
    private boolean isDeathBlockBelow(Player p) {
        return deathBlocks().contains(blockUnderFeet(p));
    }

    /**
     * 死亡方块表：读 config.yml 的 {@code wool.death-blocks}（方块名，一行一个）；没写这个键 = 默认只有红色羊毛，
     * 写成 {@code []} = 关掉死亡方块。改完重启服务器生效。
     */
    private Set<Material> deathBlocks() {
        if (deathBlocksLoaded) {
            return deathBlocks;
        }
        deathBlocksLoaded = true;
        deathBlocks.clear();
        if (!plugin.getConfig().isSet("wool.death-blocks")) {
            deathBlocks.add(Material.RED_WOOL);
            return deathBlocks;
        }
        for (String key : plugin.getConfig().getStringList("wool.death-blocks")) {
            Material m = matchWool(key);
            if (m == null) {
                plugin.getLogger().warning("wool.death-blocks 里有不认识的方块名：" + key);
                continue;
            }
            deathBlocks.add(m);
        }
        return deathBlocks;
    }

    /**
     * 踩到死亡方块：立刻淘汰（和碰地板/跑出场地走同一条路——判失败、转旁观、计分照常，建造者则跳过这条），
     * 先来一发死亡音效 + 红字标题，让观众看清是哪一步踩死的。
     */
    private void onRunnerDeath(BuildRunArena a, Player p) {
        if (!a.getActive().contains(p.getUniqueId())) {
            return;
        }
        broadcast(a, "&4&l☠ " + p.getName() + " &7踩到&c死亡方块&7！");
        Chat.title(p, "&4&l踩到死亡方块", "&7这条没通过", 2);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1.0f, 1.0f);
        p.getWorld().spawnParticle(Particle.CRIT, p.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.1);
        p.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, p.getLocation(), 1, 0, 0, 0, 0);
        onRunnerDone(a, p, false, "踩到死亡方块了");
    }

    /** 公屏提示一句「死亡方块是哪几个颜色、踩到立刻淘汰」（建造开始 / 每条跑酷开跑各喊一次）。 */
    private void announceDeathBlock(BuildRunArena a) {
        StringBuilder names = new StringBuilder();
        for (Material m : deathBlocks()) {
            names.append(names.length() == 0 ? "" : "&7、")
                    .append("&c").append(WOOL_CN.getOrDefault(m.name(), m.name()));
        }
        if (names.length() == 0) {
            return;
        }
        broadcast(a, "&4&l死亡方块：" + names + " &7踩到就淘汰");
    }

    /** 羊毛效果表：优先读 config.yml 的 wool.effects，没配就用内置默认表。 */
    private Map<Material, List<WoolEffect>> woolEffects() {
        if (woolEffectsLoaded) {
            return woolEffects;
        }
        woolEffectsLoaded = true;
        woolEffects.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("wool.effects");
        if (sec != null && !sec.getKeys(false).isEmpty()) {
            for (String key : sec.getKeys(false)) {
                Material wool = matchWool(key);
                if (wool == null) {
                    plugin.getLogger().warning("wool.effects 里有不认识的方块名：" + key);
                    continue;
                }
                if (deathBlocks().contains(wool)) {
                    // 老配置里红色羊毛还写着 "STRENGTH:0"：它现在是死亡方块，效果忽略
                    plugin.getLogger().info("wool.effects 里的 " + wool + " 是死亡方块，忽略它的药水效果");
                    continue;
                }
                List<WoolEffect> list = parseWoolEffects(sec.getString(key, ""));
                if (!list.isEmpty()) {
                    woolEffects.put(wool, list);
                }
            }
        } else {
            for (Map.Entry<String, String> e : DEFAULT_WOOL_EFFECTS.entrySet()) {
                Material wool = matchWool(e.getKey());
                List<WoolEffect> list = wool == null || deathBlocks().contains(wool)
                        ? List.of() : parseWoolEffects(e.getValue());
                if (!list.isEmpty()) {
                    woolEffects.put(wool, list);
                }
            }
        }
        return woolEffects;
    }

    private Material matchWool(String key) {
        return key == null ? null : Material.matchMaterial(key.trim().toUpperCase(Locale.ROOT));
    }

    /** "SPEED:1, JUMP_BOOST:0" → 效果列表；等级 0 = I 级。 */
    private List<WoolEffect> parseWoolEffects(String raw) {
        List<WoolEffect> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String[] bits = part.trim().split(":");
            String name = bits[0].trim();
            if (name.isEmpty()) {
                continue;
            }
            PotionEffectType type = effectType(name);
            if (type == null) {
                plugin.getLogger().warning("wool.effects 里有不认识的效果：" + name);
                continue;
            }
            int amp = 0;
            if (bits.length > 1) {
                try {
                    amp = Math.max(0, Integer.parseInt(bits[1].trim()));
                } catch (NumberFormatException ignored) {
                    amp = 0;
                }
            }
            out.add(new WoolEffect(type, amp));
        }
        return out;
    }

    private PotionEffectType effectType(String name) {
        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(name.trim().toLowerCase(Locale.ROOT)));
        if (type == null) {
            type = PotionEffectType.getByName(name.trim().toUpperCase(Locale.ROOT));
        }
        return type;
    }

    /**
     * 羊毛效果图鉴（菜单第 23 格点进来）：16 色羊毛一排一格，鼠标悬停就能看到那一格的效果。
     * <p>
     * 子菜单里的羊毛是**纯展示物品**（`plain()`：没有道具 id，点击/拖拽全 cancel）→ 拿不走。
     * 绿色 / 粉红色羊毛是起点 / 终点标志（故意没效果），lore 里写「起点标志 / 终点标志」。
     */
    private void openWoolMenu(Player p) {
        WoolHolder holder = new WoolHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Chat.color("&8羊毛效果图鉴"));
        holder.inv = inv;
        int slot = 0;
        for (Material wool : WOOL_ORDER) {
            inv.setItem(slot++, woolShowcase(wool));
        }
        inv.setItem(WOOL_BACK_SLOT, plain(Material.ARROW, "&7返回跑酷工具菜单", List.of("&7点击回到工具菜单")));
        p.openInventory(inv);
    }

    /** 图鉴子菜单里的一格羊毛：名字是羊毛名，lore 里写效果等级 + 持续时间 + 改的地方。 */
    private ItemStack woolShowcase(Material wool) {
        List<WoolEffect> list = woolEffects().getOrDefault(wool, List.of());
        String cn = WOOL_CN.getOrDefault(wool.name(), wool.name());
        String effect = effectText(list);
        List<String> lore = new ArrayList<>();
        if (deathBlocks().contains(wool)) {
            lore.add("&7效果：&4&l死亡方块&7（跑的时候踩到&c立刻淘汰&7）");
            lore.add("&7建造阶段踩上去只会提示、不会死。");
            lore.add("&8改死亡方块：config.yml 的 wool.death-blocks（改完重启生效）");
            return plain(wool, "&c" + cn, lore);
        }
        if (wool == Material.GREEN_WOOL || wool == Material.PINK_WOOL) {
            boolean start = wool == Material.GREEN_WOOL;
            lore.add("&7效果：" + (start ? "&a起点标志" : "&d终点标志") + "&7（不能自己放、也拆不掉）");
            lore.add("&8起点 / 终点方块，故意没配药水效果");
        } else {
            lore.add("&7效果：" + (effect.isEmpty() ? "&7无效果" : "&e" + effect));
            lore.add("&7站上去立刻获得，离开后还会再留 &e"
                    + Math.max(1, cfg("wool.effect-seconds", 5)) + "s&7。");
        }
        lore.add("&8改效果：config.yml 的 wool 段（改完重启生效）");
        return plain(wool, "&f" + cn, lore);
    }

    /** 图鉴子菜单里点「返回跑酷工具菜单」。 */
    public void backToKit(Player p) {
        p.closeInventory();
        String arenaName = playerArena.get(p.getUniqueId());
        BuildRunArena a = arenaName == null ? null : arenas.get(arenaName);
        if (a != null && a.getPhase() == BuildRunArena.Phase.BUILDING) {
            openKitMenu(p);
        }
    }

    /** 效果列表 → "速度 II+跳跃提升 I"（空表返回空串）。 */
    private static String effectText(List<WoolEffect> list) {
        StringBuilder sb = new StringBuilder();
        for (WoolEffect eff : list) {
            if (sb.length() > 0) {
                sb.append("+");
            }
            sb.append(effectCn(eff.type().getKey().getKey().toUpperCase(Locale.ROOT)));
            sb.append(" ").append(roman(eff.amplifier() + 1));
        }
        return sb.toString();
    }

    private static String effectCn(String effect) {
        return switch (effect) {
            case "SPEED" -> "速度";
            case "SLOWNESS" -> "缓慢";
            case "JUMP_BOOST" -> "跳跃提升";
            case "REGENERATION" -> "生命恢复";
            case "DOLPHINS_GRACE" -> "海豚的恩惠";
            case "LEVITATION" -> "漂浮";
            case "WATER_BREATHING" -> "水下呼吸";
            case "MINING_FATIGUE" -> "挖掘疲劳";
            case "STRENGTH" -> "力量";
            case "BLINDNESS" -> "失明";
            case "INVISIBILITY" -> "隐身";
            case "GLOWING" -> "发光";
            case "HASTE" -> "急迫";
            case "SLOW_FALLING" -> "缓降";
            case "RESISTANCE" -> "抗性提升";
            case "FIRE_RESISTANCE" -> "抗火";
            case "NIGHT_VISION" -> "夜视";
            default -> effect;
        };
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> "Lv" + n;
        };
    }

    // ==================================================================
    // 移动事件：建造时的限制 + 跑步时的判定
    // ==================================================================

    public void handleMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        String arenaName = playerArena.get(p.getUniqueId());
        if (arenaName == null) {
            return;
        }
        BuildRunArena a = arenas.get(arenaName);
        if (a == null) {
            return;
        }
        if (a.getPhase() == BuildRunArena.Phase.BUILDING) {
            // 已点「完成建造」的人已经转旁观模式：放他到处飞（看别人建、绕着场地转），
            // 不再像建造中的玩家那样被 confine 拉回自己场地。
            if (a.getBuildDone().contains(p.getUniqueId()) || p.getGameMode() == GameMode.SPECTATOR) {
                return;
            }
            confine(p, a);
            return;
        }
        if (a.getPhase() != BuildRunArena.Phase.RUN) {
            return;
        }
        UUID id = p.getUniqueId();
        if (!a.getActive().contains(id)) {
            return;
        }
        // 传送就位即开跑（没有"不许动"冻结）：位置不再钉住，判失败从这一刻起算
        // 红色羊毛 = 死亡方块：一踩上去立刻淘汰（碰地板 / 跑出场地 之外的第三个失败原因）
        if (isDeathBlockBelow(p)) {
            onRunnerDeath(a, p);
            return;
        }
        BuildRunPlot plot = a.currentPlot();
        if (plot == null) {
            return;
        }
        Location loc = p.getLocation();
        if (plot.isStandingOnEnd(loc)) {
            // 站在终点方块上：1 秒计时交给 tickWoolAndEnd；这一格不再判"碰到地板"
            return;
        }
        if (plot.isOut(loc, floorTolerance())) {
            onRunnerDone(a, p, false);
        }
    }

    /** 建造阶段把人关在自己场地里（跑出去/掉出去就传回出生点）。 */
    private void confine(Player p, BuildRunArena a) {
        BuildRunPlot plot = a.getPlots().get(p.getUniqueId());
        if (plot == null) {
            return;
        }
        Location loc = p.getLocation();
        int[] b = plot.getBounds();
        boolean out = loc.getBlockX() < b[0] - 1 || loc.getBlockX() > b[3] + 1
                || loc.getBlockZ() < b[2] - 1 || loc.getBlockZ() > b[5] + 1
                || loc.getBlockY() < b[1] - 3 || loc.getBlockY() > b[4] + 10;
        if (out) {
            p.teleport(plot.getSpawn());
            Chat.actionBar(p, "&c别跑出自己场地");
        }
    }

    // ==================================================================
    // 建造阶段道具 / 工具菜单
    // ==================================================================

    private void giveWaitingItems(Player p) {
        p.getInventory().setItem(LEAVE_SLOT, tool(Material.RED_BED, "leave", "&c退出游戏 &7(右键)", List.of("&7离开这个场地")));
        if (p.isOp()) {
            p.getInventory().setItem(START_SLOT, tool(Material.PAPER, "start", "&a&l开始游戏 &7(右键)", List.of("&7人齐后右键开始")));
        }
    }

    private void giveBuildItems(Player p) {
        p.getInventory().clear();
        p.getInventory().setItem(MENU_SLOT, tool(Material.COMPASS, "menu", "&b&l跑酷工具 &7(右键)",
                List.of("&6起点/终点棒&7 · &d羊毛效果&7 · &a完成建造",
                        "&7右键打开工具菜单")));
        p.getInventory().setItem(LEAVE_SLOT, tool(Material.RED_BED, "leave", "&c退出游戏 &7(右键)", List.of("&7离开这个场地")));
        p.getInventory().setHeldItemSlot(MENU_SLOT);
    }

    // ==================================================================
    // 跑者道具：右键隐藏 / 显示其他玩家
    // ==================================================================

    /** 跑者的隐藏道具：右键把其他玩家藏起来（自己看不见他们），再右键恢复。 */
    private ItemStack hideItem(boolean hidden) {
        if (hidden) {
            return tool(Material.ENDER_EYE, HIDE_ITEM, "&a&l显示其他玩家 &7(右键)",
                    List.of("&7再右键一次 = 重新看见其他人", "&7这条跑完也会自动恢复"));
        }
        return tool(Material.ENDER_EYE, HIDE_ITEM, "&7&l隐藏其他玩家 &7(右键)",
                List.of("&7右键后看不到其他玩家（减少干扰）", "&7再右键一次 = 重新显示", "&7这条跑完也会自动恢复"));
    }

    /** 右键切换：藏 → 显示，显示 → 藏。同一次物理右键的第二个包直接丢掉（见 lastHideToggle）。 */
    private void toggleHide(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastHideToggle.get(p.getUniqueId());
        if (last != null && now - last < HIDE_TOGGLE_DEBOUNCE_MS) {
            return;
        }
        lastHideToggle.put(p.getUniqueId(), now);
        if (hiddenFrom.containsKey(p.getUniqueId())) {
            showOthers(p);
            replaceHideItem(p, false);
            Chat.actionBar(p, "&a已显示其他人");
        } else {
            hideOthers(p);
            replaceHideItem(p, true);
            Chat.actionBar(p, "&7已隐藏其他人（再右键恢复）");
        }
    }

    /**
     * 把当前在线的人全部藏起来；记下藏了谁，恢复时只放这些（不动别的插件藏的）。
     * <p>
     * 旁观模式的人跳过：他们本来就对生存玩家不可见（Paper 的 {@code hideEntity}/{@code showEntity}
     * 对"默认不可见"的实体是**反向**的，硬 show 反而会把旁观者塞进你的视野里）。
     */
    private void hideOthers(Player p) {
        Set<UUID> set = hiddenFrom.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashSet<>());
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(p.getUniqueId())) {
                continue;
            }
            if (other.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (set.add(other.getUniqueId())) {
                p.hidePlayer(plugin, other);
            }
        }
    }

    /** 恢复这个玩家看见的所有人（只 show 我们自己藏的）。 */
    private void showOthers(Player p) {
        Set<UUID> set = hiddenFrom.remove(p.getUniqueId());
        if (set == null) {
            return;
        }
        for (UUID id : set) {
            Player other = Bukkit.getPlayer(id);
            if (other != null) {
                p.showPlayer(plugin, other);
            }
        }
    }

    /** 人走了 / 局终：清掉他的隐藏状态，也把他从别人的名单里删掉（重进不会一直看不见）。 */
    private void forgetHidden(Player p) {
        hiddenFrom.remove(p.getUniqueId());
        lastHideToggle.remove(p.getUniqueId());
        UUID id = p.getUniqueId();
        for (Set<UUID> set : hiddenFrom.values()) {
            set.remove(id);
        }
    }

    /** 换掉背包里的隐藏道具图标 / 名字（找不到就补回第 1 格）。 */
    private void replaceHideItem(Player p, boolean hidden) {
        ItemStack fresh = hideItem(hidden);
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            if (HIDE_ITEM.equals(toolId(p.getInventory().getItem(i)))) {
                p.getInventory().setItem(i, fresh);
                return;
            }
        }
        p.getInventory().setItem(HIDE_SLOT, fresh);
    }

    /** 有人上线：正在隐藏的跑者也得看不见他（否则新进来的人会突然出现在跑者视野里）。 */
    public void handleJoin(Player p) {
        if (hiddenFrom.isEmpty()) {
            return;
        }
        for (UUID viewerId : new ArrayList<>(hiddenFrom.keySet())) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline() || viewer.equals(p)) {
                continue;
            }
            Set<UUID> set = hiddenFrom.get(viewerId);
            if (set != null && set.add(p.getUniqueId())) {
                viewer.hidePlayer(plugin, p);
            }
        }
    }

    /** 只用于展示的物品（不带道具 id，不会被当成可交互工具）。 */
    private ItemStack plain(Material material, String name, List<String> lore) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color(name)));
            List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
            for (String l : lore) {
                lines.add(Chat.color(l));
            }
            meta.lore(lines);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack tool(Material material, String id, String name, List<String> lore) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color(name)));
            List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
            for (String l : lore) {
                lines.add(Chat.color(l));
            }
            meta.lore(lines);
            meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, id);
            it.setItemMeta(meta);
        }
        return it;
    }

    private String toolId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    }

    private void openKitMenu(Player p) {
        KitHolder holder = new KitHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Chat.color("&8跑酷工具"));
        holder.inv = inv;
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.displayName(Chat.noItalic(Chat.color(" ")));
            filler.setItemMeta(fm);
        }
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler);
        }
        // 菜单只有三件：起点/终点棒、羊毛效果图鉴、完成建造
        inv.setItem(POINT_SLOT, tool(Material.BLAZE_ROD, "point", "&6&l设置起点 / 终点",
                List.of("&7点一下领取一根 &f烈焰棒&7，然后：",
                        "&a左键方块&7 = 起点（方块变 &a绿色羊毛&7）",
                        "&c右键方块&7 = 终点（方块变 &d粉红色羊毛&7）",
                        "&c地板那层不行&7（先垫高 1 格）")));
        // 菜单中行：点进去看 16 色羊毛的效果（子菜单里鼠标悬停就能看）
        inv.setItem(WOOL_INFO_SLOT, tool(Material.WHITE_WOOL, "woolhelp", "&d&l羊毛效果图鉴",
                List.of("&7点开看 16 色羊毛", "&f鼠标悬停某格&7 = 看它的效果")));
        inv.setItem(DONE_SLOT, tool(Material.EMERALD_BLOCK, "done", "&a&l完成建造",
                List.of("&7起点和终点都设好后点这里", "&7点完你去 &f旁观&7，看别人继续建",
                        "&7所有人都点完 → &e立刻开跑")));
        p.openInventory(inv);
    }

    /** 手上一根烈焰棒管两件事：左键方块 = 起点，右键方块 = 终点。 */
    private ItemStack pointTool() {
        return tool(Material.BLAZE_ROD, "point", "&6&l起点 / 终点棒",
                List.of("&a左键方块&7 = 起点（方块变 &a绿色羊毛&7）",
                        "&c右键方块&7 = 终点（方块变 &d粉红色羊毛&7）",
                        "&7起点要比地板高 1 格（先垫高再点）",
                        "&8手持它时左键只会设点，拆方块请先换手"));
    }

    /** 道具菜单点击。返回是否已处理。 */
    public boolean handleKitClick(Player p, int slot) {
        String arenaName = playerArena.get(p.getUniqueId());
        if (arenaName == null) {
            return false;
        }
        BuildRunArena a = arenas.get(arenaName);
        if (a == null || a.getPhase() != BuildRunArena.Phase.BUILDING) {
            return false;
        }
        BuildRunPlot plot = a.getPlots().get(p.getUniqueId());
        if (plot == null) {
            return false;
        }
        switch (slot) {
            case POINT_SLOT -> {
                giveTool(p, pointTool());
                p.closeInventory();
                msg(p, "&6烈焰棒&7拿到了：&a左键方块设起点&7，&c右键方块设终点&7。");
            }
            case WOOL_INFO_SLOT -> {
                p.closeInventory();
                openWoolMenu(p);
            }
            case DONE_SLOT -> finishBuild(p, a, plot);
            default -> {
            }
        }
        return true;
    }

    /**
     * 菜单「完成建造」：起点 + 终点都设好才算完成 → 该玩家转旁观模式，看别人继续建。
     * <p>
     * 只影响点的人：其他人照旧建。所有人（在册的）都点完 → 立刻进轮流跑，
     * 不再空等 {@code build-seconds} 剩下来的时间。
     */
    private void finishBuild(Player p, BuildRunArena a, BuildRunPlot plot) {
        if (a.getPhase() != BuildRunArena.Phase.BUILDING || plot == null) {
            return;
        }
        UUID id = p.getUniqueId();
        if (a.getBuildDone().contains(id)) {
            msg(p, "&7你已建完，正在旁观。");
            return;
        }
        boolean noStart = plot.getStart() == null;
        boolean noEnd = plot.getEnd() == null;
        if (noStart || noEnd) {
            msg(p, "&c先设好" + (noStart ? " &c起点" : "") + (noEnd ? " &c终点" : "") + " &c再来点。");
            return;
        }
        a.getBuildDone().add(id);
        p.closeInventory();
        spectator(p, plot); // 旁观模式 + 站到自己场地上方
        msg(p, "&a&l建造完成！&7你变旁观，可以随便飞；等别人建完就开跑。");
        broadcast(a, "&e" + p.getName() + " &f建完了（&e" + a.getBuildDone().size()
                + "&7/&e" + a.getPlayers().size() + "&f）→ 旁观。");
        if (allBuildDone(a)) {
            endBuild(a);
        }
    }

    /** 本局在册玩家是否都点过「完成建造」（空局返回 false，别把空场提前结束）。 */
    private boolean allBuildDone(BuildRunArena a) {
        if (a.getPhase() != BuildRunArena.Phase.BUILDING || a.getPlayers().isEmpty()) {
            return false;
        }
        for (UUID id : a.getPlayers()) {
            if (!a.getBuildDone().contains(id)) {
                return false;
            }
        }
        return true;
    }

    /** 把工具放进快捷栏第一个空位（不顶掉菜单指南针），并切到那一格。 */
    private void giveTool(Player p, ItemStack item) {
        int slot = -1;
        for (int i = 1; i <= 7; i++) {
            ItemStack cur = p.getInventory().getItem(i);
            if (cur == null || cur.getType().isAir()) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            slot = 1;
        }
        p.getInventory().setItem(slot, item);
        p.getInventory().setHeldItemSlot(slot);
    }

    /**
     * 手持本插件道具的交互。返回 true = 已消费（调用方取消事件）。
     */
    public boolean handleItemUse(Player p, Action action, Block clicked, ItemStack item) {
        String id = toolId(item);
        if (id == null) {
            return false;
        }
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        String arenaName = playerArena.get(p.getUniqueId());
        BuildRunArena a = arenaName == null ? null : arenas.get(arenaName);

        if ("leave".equals(id)) {
            if (right) {
                leave(p);
            }
            return true;
        }
        if ("start".equals(id)) {
            // 等待室里的开始纸（建场阶段的烈焰棒已改用 "point"）
            if (a != null && a.getPhase() == BuildRunArena.Phase.WAITING && right) {
                startFromPaper(p, a);
            }
            return true;
        }
        if ("point".equals(id)) {
            // 一根烈焰棒管两个点：左键方块 = 起点，右键方块 = 终点
            if (a == null || a.getPhase() != BuildRunArena.Phase.BUILDING) {
                return true;
            }
            BuildRunPlot plot = a.getPlots().get(p.getUniqueId());
            if (plot == null || clicked == null) {
                return true;
            }
            if (left) {
                setStart(p, plot, clicked);
            } else if (right) {
                setEnd(p, plot, clicked);
            }
            return true;
        }
        if (HIDE_ITEM.equals(id)) {
            // 跑者道具：只有正在跑（且没被判定出局）的人能切；其它阶段背包里不会有这件
            if (right && a != null && a.getPhase() == BuildRunArena.Phase.RUN
                    && a.getActive().contains(p.getUniqueId())) {
                toggleHide(p);
            }
            return true;
        }
        if ("menu".equals(id)) {
            if (right && a != null && a.getPhase() == BuildRunArena.Phase.BUILDING) {
                openKitMenu(p);
            }
            return true;
        }
        if ("woolhelp".equals(id)) {
            // 菜单第 23 格 / 旧版说明书道具：右键直接开羊毛图鉴子菜单
            if (right && a != null && a.getPhase() == BuildRunArena.Phase.BUILDING) {
                openWoolMenu(p);
            }
            return true;
        }
        return left || right;
    }

    private void startFromPaper(Player p, BuildRunArena a) {
        if (!p.isOp() && !p.hasPermission("br.admin")) {
            msg(p, "&c只有管理员（OP 或 br.admin）可以开始。");
            return;
        }
        start(p, a.getName());
    }

    private void setStart(Player p, BuildRunPlot plot, Block block) {
        if (!plot.contains(block.getLocation())) {
            msg(p, "&c只能在自己场地里设点。");
            return;
        }
        Location end = plot.getEnd();
        if (end != null && end.getWorld() != null && end.getWorld().equals(block.getWorld())
                && end.getBlockX() == block.getX() && end.getBlockY() == block.getY()
                && end.getBlockZ() == block.getZ()) {
            msg(p, "&c起点和终点不能是同一块。");
            return;
        }
        Location loc = BuildRunPlot.feetOn(block, p.getLocation());
        // 起点禁止设在地板那一层：跑者脚部一落地就低于失败线（一开跑就判失败），先垫高再设
        int tol = floorTolerance();
        if (!plot.isSafeHeight(loc, tol)) {
            msg(p, "&c先垫高：起点要比地板高至少 " + tol + " 格。");
            return;
        }
        plot.setStart(loc);
        plot.markStart(block);
        msg(p, "&a起点设好了 &7（方块变成绿色羊毛）");
        if (plot.getEnd() == null) {
            msg(p, "&7还差终点：&c右键&7同一根烈焰棒点终点方块。");
        } else {
            msg(p, "&a起点终点都齐了，可以跑了！");
        }
    }

    private void setEnd(Player p, BuildRunPlot plot, Block block) {
        if (!plot.contains(block.getLocation())) {
            msg(p, "&c只能在自己场地里设点。");
            return;
        }
        Location start = plot.getStart();
        if (start != null && start.getWorld() != null && start.getWorld().equals(block.getWorld())
                && start.getBlockX() == block.getX() && start.getBlockY() == block.getY() + 1
                && start.getBlockZ() == block.getZ()) {
            msg(p, "&c起点和终点不能是同一块。");
            return;
        }
        Location loc = block.getLocation();
        plot.setEnd(loc);
        plot.markEnd(block);
        msg(p, "&c终点设好了 &7（方块变成粉红色羊毛，站满 1 秒算通过）");
        if (!plot.isSafeHeight(loc, floorTolerance())) {
            msg(p, "&e终点太贴地板，跑者一落地就失败。");
        }
        if (plot.getStart() == null) {
            msg(p, "&7还差起点：&a左键&7同一根烈焰棒点起点方块。");
        } else {
            msg(p, "&a起点终点都齐了，可以跑了！");
        }
    }

    /** 奔跑时的"人"状态：生存、禁飞、满血、空手、传送到起点。 */
    private void setUpRunner(Player p, BuildRunPlot plot) {
        if (p == null || plot == null || plot.getStart() == null) {
            return;
        }
        p.setGameMode(GameMode.SURVIVAL);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.getInventory().clear();
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        // 上一轮残留的"看不见别人"先还原，再发本轮的隐藏道具（右键 = 隐藏 / 再右键 = 显示）
        showOthers(p);
        p.getInventory().setItem(HIDE_SLOT, hideItem(false));
        p.getInventory().setHeldItemSlot(HIDE_SLOT);
        p.setNoDamageTicks(20);
        p.teleport(plot.getStart());
        // 传送前带着的惯性清零，避免刚落地就被上一段的动量带偏；然后直接喊开跑（没有"不许动"）
        p.setVelocity(new Vector(0, 0, 0));
        Chat.actionBar(p, "&a&l开跑！");
    }

    /** 旁观：旁观模式 + 站到该场地出生点上方。 */
    private void spectator(Player p, BuildRunPlot plot) {
        if (p == null) {
            return;
        }
        p.setGameMode(GameMode.SPECTATOR);
        p.getInventory().clear();
        // 这条跑完了：之前右键藏起来的人全部恢复可见（不管通过 / 失败 / 超时都走这里）
        showOthers(p);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
        if (plot != null && plot.getSpawn() != null) {
            p.teleport(plot.getSpawn().clone().add(0, 2, 0));
        }
    }

    // ==================================================================
    // 碰撞箱 / 队伍
    // ==================================================================

    /** on = true：把本局所有人放进"不互相碰撞"的队伍；false：全部移出。 */
    private void setCollide(BuildRunArena a, boolean on) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = sb.getTeam(COLLIDE_TEAM);
        if (team == null) {
            team = sb.registerNewTeam(COLLIDE_TEAM);
        }
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            if (on) {
                team.addEntry(p.getName());
            } else {
                team.removeEntry(p.getName());
            }
        }
        if (!on && team.getEntries().isEmpty()) {
            team.unregister();
        }
    }

    /** 单个玩家移出"不碰撞"队伍（中途退出时别把 NEVER 带到场外）。 */
    private void removeCollideEntry(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = sb.getTeam(COLLIDE_TEAM);
        if (team == null || p == null) {
            return;
        }
        team.removeEntry(p.getName());
        if (team.getEntries().isEmpty()) {
            team.unregister();
        }
    }

    // ==================================================================
    // 配置模式（OP 建场道具）
    // ==================================================================

    public boolean isInSetup(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null && setup.isEditing(p);
    }

    public boolean enterSetup(Player p, String arenaName) {
        BuildRunArena a = arenas.get(arenaName);
        if (a == null) {
            msg(p, "&c场 &e" + arenaName + "&c 不存在。");
            return false;
        }
        if (setup.isEditing(p)) {
            msg(p, "&c先按屏障退出建场模式。");
            return false;
        }
        setup.enter(p, arenaName, setupTools(), setupHandler());
        msg(p, "&a已进入 &e" + arenaName + "&a 的建场模式：");
        msg(p, "&b木棍&7 左键=角1 右键=角2（圈出场地，连地板）");
        msg(p, "&e箭&7 右键=切复制方向");
        msg(p, "&e金锄头&7 右键=出生点   &f纸&7 右键=等待点   &a绿宝石&7 保存   &c屏障&7 退出");
        return true;
    }

    public void exitSetup(Player p) {
        setup.exit(p);
        msg(p, "&7已退出建场模式，背包已恢复。");
    }

    private List<SetupMode.Tool> setupTools() {
        return List.of(
                SetupMode.Tool.both(Material.STICK, "pos", "&b场地范围工具",
                        "&7左键=场地角1&8/&7右键=场地角2（模板，含地板与建造空间）"),
                SetupMode.Tool.right(Material.ARROW, "dir", "&e衍生方向工具",
                        "&7右键=切换方向（+X → -X → +Z → -Z）"),
                SetupMode.Tool.right(Material.GOLDEN_HOE, "spawn", "&e出生点工具",
                        "&7右键=出生点（每块场地里玩家出现的位置）"),
                SetupMode.Tool.right(Material.PAPER, "lobby", "&f等待点工具",
                        "&7右键=等待点（加入后等待的位置，建议在复制区域外）"),
                SetupMode.Tool.right(Material.EMERALD, "save", "&6保存场",
                        "&7右键=保存配置"),
                SetupMode.Tool.right(Material.BARRIER, "exit", "&c退出配置模式",
                        "&7右键=结束配置并恢复背包"));
    }

    private SetupMode.Handler setupHandler() {
        return new SetupMode.Handler() {
            @Override
            public void onTool(Player p, String arenaName, String toolId, Action action, Block clicked) {
                BuildRunArena a = arenas.get(arenaName);
                if (a == null) {
                    exitSetup(p);
                    return;
                }
                switch (toolId) {
                    case "pos" -> {
                        if (action == Action.LEFT_CLICK_BLOCK && clicked != null) {
                            a.setPos1(clicked.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 场地角1：" + shortLoc(clicked.getLocation())
                                    + (a.getPos2() != null ? "" : "（接着右键角2）"));
                        } else if (action == Action.RIGHT_CLICK_BLOCK && clicked != null) {
                            a.setPos2(clicked.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 场地角2：" + shortLoc(clicked.getLocation())
                                    + (a.getPos1() != null ? "（范围已定）" : "（还差角1）"));
                            if (a.getPos1() != null) {
                                msg(p, "&7单块场地 " + sizeText(a) + "，方向 &e"
                                        + a.getDirection().toUpperCase() + "&7，跑酷离底面至少 2 格。");
                            }
                        }
                    }
                    case "dir" -> {
                        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                            String next = nextDirection(a.getDirection());
                            a.setDirection(next);
                            msg(p, "&a方向：&e" + next.toUpperCase() + "&7（每 " + a.step() + " 格一块）");
                        }
                    }
                    case "spawn" -> {
                        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                            a.setSpawn(p.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 出生点：" + shortLoc(p.getLocation()));
                        }
                    }
                    case "lobby" -> {
                        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                            a.setLobby(p.getLocation());
                            msg(p, "&a已设置 &e" + arenaName + "&a 等待点：" + shortLoc(p.getLocation()));
                        }
                    }
                    default -> {
                    }
                }
            }

            @Override
            public void onSave(Player p, String arenaName) {
                BuildRunArena a = arenas.get(arenaName);
                if (a != null) {
                    saveArena(p, a);
                }
            }
        };
    }

    private static String nextDirection(String current) {
        for (int i = 0; i < BuildRunArena.DIRECTIONS.length; i++) {
            if (BuildRunArena.DIRECTIONS[i].equals(current)) {
                return BuildRunArena.DIRECTIONS[(i + 1) % BuildRunArena.DIRECTIONS.length];
            }
        }
        return BuildRunArena.DIRECTIONS[0];
    }

    public boolean handleSetupTool(Player p, Action action, Block clicked) {
        return setup.handleInteract(p, action, null, clicked);
    }

    // ==================================================================
    // 事件辅助（Listener 调用）
    // ==================================================================

    /** 玩家是否在某个场里（等待中 / 建造 / 跑步 / 清理都算）。 */
    public boolean isInSession(UUID id) {
        return playerArena.containsKey(id);
    }

    /** 该玩家此刻能不能动这个方块（建造阶段只允许自己场地内）。 */
    public boolean canEditBlock(Player p, Block block) {
        String arenaName = playerArena.get(p.getUniqueId());
        if (arenaName == null) {
            return true;
        }
        BuildRunArena a = arenas.get(arenaName);
        if (a == null) {
            return true;
        }
        return switch (a.getPhase()) {
            case BUILDING -> {
                BuildRunPlot plot = a.getPlots().get(p.getUniqueId());
                yield plot != null && plot.contains(block.getLocation());
            }
            case WAITING -> true;
            default -> false;
        };
    }

    /**
     * 玩家要拆自己场地里的起点/终点标志（绿色/粉红色羊毛）？是就发提示并返回 true（调用方取消事件）。
     */
    public boolean protectMarkBlock(Player p, Block block) {
        BuildRunArena a = arenas.get(playerArena.get(p.getUniqueId()));
        if (a == null || a.getPhase() != BuildRunArena.Phase.BUILDING) {
            return false;
        }
        BuildRunPlot plot = a.getPlots().get(p.getUniqueId());
        if (plot == null || !plot.isMarkBlock(block)) {
            return false;
        }
        msg(p, "&c标志方块拆不掉；换点就重新设起点/终点。");
        return true;
    }

    /**
     * 玩家要拆自己场地里的屏障（{@link Material#BARRIER}）？是就发提示并返回 true（调用方取消事件）。
     * <p>
     * 用户口径（2026-10-18）：创造模式的建造者**既不许拆、也不许放**屏障。创造模式默认能瞬间挖穿屏障，
     * 而场地里的屏障（模板带的隔墙/隐形墙）一挖就成洞，跑者能直接穿过去；
     * 屏障又没别的手段能弄坏（炸不烂、活塞推不动、烧不着），所以"弄掉屏障"只有拆这一条路，拦这里就够了。
     * 放的那条见 {@link #forbidBarrierPlace}（同一口径，2026-10-18 用户补充："自己也不能放屏障"）。
     */
    public boolean protectBarrierBlock(Player p, Block block) {
        if (block == null || block.getType() != Material.BARRIER) {
            return false;
        }
        if (!isBuildingInOwnPlot(p, block.getLocation())) {
            return false;
        }
        msg(p, "&c屏障不能拆。");
        return true;
    }

    /** 建造阶段在自己场地里放屏障？是就发提示并返回 true（用户口径：自己也不能放屏障）。 */
    public boolean forbidBarrierPlace(Player p, Block block) {
        if (block == null || block.getType() != Material.BARRIER) {
            return false;
        }
        if (!isBuildingInOwnPlot(p, block.getLocation())) {
            return false;
        }
        msg(p, "&c这局不能放屏障。");
        return true;
    }

    /** 建造阶段 + 这个位置就在自己那块复制场地里（屏障拆/放共用的判定）。 */
    private boolean isBuildingInOwnPlot(Player p, Location loc) {
        if (loc == null) {
            return false;
        }
        BuildRunArena a = arenas.get(playerArena.get(p.getUniqueId()));
        if (a == null || a.getPhase() != BuildRunArena.Phase.BUILDING) {
            return false;
        }
        BuildRunPlot plot = a.getPlots().get(p.getUniqueId());
        return plot != null && plot.contains(loc);
    }

    /**
     * 玩家要拆自己场地的地板（复制体最低那一层）？是就发提示并返回 true（调用方取消事件）。
     * <p>
     * 只保护最低这一层：地板下面本来就在场地范围外（{@link #canEditBlock} 挡着），
     * 所以"把地板挖穿掉下去"只可能从这一层下手。
     */
    public boolean protectFloorBlock(Player p, Block block) {
        if (block == null) {
            return false;
        }
        BuildRunArena a = arenas.get(playerArena.get(p.getUniqueId()));
        if (a == null || a.getPhase() != BuildRunArena.Phase.BUILDING) {
            return false;
        }
        BuildRunPlot plot = a.getPlots().get(p.getUniqueId());
        if (plot == null || !plot.isFloor(block.getLocation())) {
            return false;
        }
        msg(p, "&c地板不能挖；想垫高就在上面放方块。");
        return true;
    }

    /**
     * 这个方块是不是「某一局场地」的地板层（不分是谁的场地）。
     * <p>
     * 给活塞 / 爆炸 / 火烧这类**不是玩家拿镐子挖**的破坏用：它们照样能把地板弄出洞，但不走
     * {@link #protectFloorBlock}。调用方只"取消这一下 / 把我的方块从列表里摘掉"，不整场拦事件。
     */
    public boolean isProtectedFloor(Block block) {
        if (block == null) {
            return false;
        }
        for (BuildRunArena a : arenas.values()) {
            if (a.getPhase() == BuildRunArena.Phase.WAITING) {
                continue;
            }
            for (BuildRunPlot plot : a.getPlots().values()) {
                if (plot.isFloor(block.getLocation())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 建造阶段不许自己放绿色/粉红色羊毛（这两种颜色是起点/终点标志）。是就发提示并返回 true。
     */
    public boolean forbidMarkerWool(Player p, Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        if (type != BuildRunPlot.START_MARK && type != BuildRunPlot.END_MARK) {
            return false;
        }
        BuildRunArena a = arenas.get(playerArena.get(p.getUniqueId()));
        if (a == null || a.getPhase() != BuildRunArena.Phase.BUILDING) {
            return false;
        }
        BuildRunPlot plot = a.getPlots().get(p.getUniqueId());
        if (plot == null || !plot.contains(block.getLocation())) {
            return false;
        }
        msg(p, "&c绿色/粉红色羊毛是标志，不能自己放。");
        return true;
    }

    /**
     * 建造阶段、且方块**就在自己那块复制场地里**时才返回 true。
     * <p>
     * 用途只有一个：Listener 在 HIGHEST 上对这种方块显式 {@code setCancelled(false)}。
     * 因为 GuessTheBuild 在 NORMAL 上无条件取消所有非 OP 玩家的方块破坏（不分世界、不分场地），
     * 而同优先级谁先执行不确定，导致非 OP 的建筑者在自己场地里也挖不动。
     */
    public boolean canBuildBlockNow(Player p, Block block) {
        String arenaName = playerArena.get(p.getUniqueId());
        if (arenaName == null) {
            return false;
        }
        BuildRunArena a = arenas.get(arenaName);
        if (a == null || a.getPhase() != BuildRunArena.Phase.BUILDING) {
            return false;
        }
        BuildRunPlot plot = a.getPlots().get(p.getUniqueId());
        return plot != null && plot.contains(block.getLocation());
    }

    /** 本插件道具（用于拦截丢弃 / 菜单点击）。 */
    public boolean isOurItem(ItemStack item) {
        return toolId(item) != null;
    }

    // ==================================================================
    // 防作弊：不许开容器 / 不许丢物品 / 掉落物及时清理 / 不许跟方块交互
    // ==================================================================

    /** 建造 / 跑步两个阶段 = "对局中"（等待、复制、收尾都不算）。 */
    private static boolean isFighting(BuildRunArena a) {
        BuildRunArena.Phase phase = a.getPhase();
        return phase == BuildRunArena.Phase.BUILDING || phase == BuildRunArena.Phase.RUN;
    }

    /**
     * 对局中不许跟方块/实体交互（按钮、门、活板门、栅栏门、拉杆、压力板、箱子、工作台、盔甲架、物品展示框……）。
     * <p>
     * 用户口径（2026-10-18）："不要任何交互，按按钮开门都不行，建造的时候就禁止"。
     * 是就发行动条提示并返回 true（调用方取消/吞掉这次交互）。
     */
    public boolean forbidInteract(Player p) {
        BuildRunArena a = arenas.get(playerArena.get(p.getUniqueId()));
        if (a == null || !isFighting(a)) {
            return false;
        }
        Chat.actionBar(p, "&c这局不能按按钮、开门、开箱子");
        return true;
    }

    /**
     * 对局中右键方块：**只有"手里拿的是方块 + 点到的是普通方块"** 才放行（= 放方块），
     * 其它一律吞掉——按按钮、开门、拉杆、翻活板门、开箱子、踩压力板、用桶/打火石/骨粉/船……
     * <p>
     * 为什么用 {@code useInteractedBlock = DENY} 而不是 {@code setCancelled(true)}：这个事件同时管
     * "交互方块"和"使用手里的东西（放方块）"，整套 cancel 会把放方块一起砍掉（实测字节码：
     * {@code ServerPlayerGameMode.useItemOn} 在 DENY 分支直接 return，不会走到 {@code ItemStack.useOn}）。
     * 点到"有反应的方块"时服务端本来就不会放方块（方块的 use 先吃掉这次点击），所以判定用
     * {@link net.minecraft.world.level.block.BlockType#isInteractable()} 的同款语义：
     * {@code Material#asBlockType()#isInteractable()} = 这个方块类自己实现了 use / useItemOn
     * （见 CraftBlockType#isInteractable 的实现），比自己列一串材质靠谱，也不会漏。
     */
    public boolean forbidWorldInteract(Player p, ItemStack item, Block clicked) {
        // 先判"这次该不该放行"：放行时绝不能发提示，否则每放一个方块都刷一次行动条
        boolean deny = item == null
                || item.getType().isAir()
                || !item.getType().isBlock()     // 手里不是方块（工具/桶/打火石/骨粉/船/盔甲架…）
                || isInteractiveBlock(clicked);  // 点到的是"有反应的方块"（按钮/门/箱子/工作台…）
        return deny && forbidInteract(p);
    }

    /** 这个方块右键有没有反应（按钮 / 门 / 活板门 / 栅栏门 / 拉杆 / 箱子 / 工作台 / 蛋糕 / 花盆…）。 */
    public boolean isInteractiveBlock(Block block) {
        if (block == null) {
            return false;
        }
        org.bukkit.block.BlockType type = block.getType().asBlockType();
        return type != null && type.isInteractable();
    }

    /**
     * 对局中不许打开任何容器（箱子 / 陷阱箱 / 熔炉 / 漏斗 / 发射器 / 潜影盒 / 末影箱……）。
     * <p>
     * 正常右键容器已经被 {@link #forbidWorldInteract} 吞掉了；这个只兜底"没走方块点击"的路径
     * （手里的潜影盒、别的插件替玩家 openInventory）。目的：防止先塞东西、轮到自己跑时再摸出来——背包在
     * 开跑前会被清空，所以"存进容器 / 丢在地上"是唯一的偷带途径。
     */
    public boolean forbidContainerOpen(Player p) {
        BuildRunArena a = arenas.get(playerArena.get(p.getUniqueId()));
        if (a == null || !isFighting(a)) {
            return false;
        }
        msg(p, "&c这局不能开箱子。");
        return true;
    }

    /** 对局中不许丢东西：丢出来就成了"提前存好的物品"（场地里的掉落物还会被立刻清掉）。 */
    public boolean forbidItemDrop(Player p) {
        BuildRunArena a = arenas.get(playerArena.get(p.getUniqueId()));
        if (a == null || !isFighting(a)) {
            return false;
        }
        msg(p, "&c这局不能丢东西。");
        return true;
    }

    /** 这个位置在"正在对局的复制场地"里吗（任意一场、任意一块场地都算）。 */
    public boolean isInsideActivePlot(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        for (BuildRunArena a : arenas.values()) {
            if (a.getPhase() == BuildRunArena.Phase.WAITING) {
                continue;
            }
            for (BuildRunPlot plot : a.getPlots().values()) {
                if (plot.contains(loc)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 1s ticker 顺手清掉落物：对局中（及复制/收尾）的场地里不许留任何掉落物。 */
    private void sweepPlotItems() {
        for (BuildRunArena a : arenas.values()) {
            if (a.getPhase() == BuildRunArena.Phase.WAITING) {
                continue;
            }
            clearPlotItems(a);
        }
    }

    /**
     * 把一局所有复制场地范围内的掉落物删掉。
     * <p>
     * 只按"自己场地"的 AABB 筛：场地世界可能和 GuessTheBuild 共用（{@code world_gtb}），
     * 所以绝不用 {@code world.getEntities()} 一把梭。
     */
    private void clearPlotItems(BuildRunArena a) {
        if (a.getPlots().isEmpty()) {
            return;
        }
        Set<World> worlds = new LinkedHashSet<>();
        for (BuildRunPlot plot : a.getPlots().values()) {
            if (plot.getWorld() != null) {
                worlds.add(plot.getWorld());
            }
        }
        for (World w : worlds) {
            for (Item item : w.getEntitiesByClass(Item.class)) {
                if (item.isDead()) {
                    continue;
                }
                for (BuildRunPlot plot : a.getPlots().values()) {
                    if (plot.contains(item.getLocation())) {
                        item.remove();
                        break;
                    }
                }
            }
        }
    }

    /**
     * 把"场内"的实体全部清掉：一局的开始（复制前）和结束（清回原样前）各来一次。
     * <p>
     * "场内" = 模板范围 + 本局所有复制体，按各自 AABB 精确筛；**只删非玩家实体**
     * （生物/载具/船/盔甲架/物品展示框/掉落物…都算，玩家自己和别的插件的人一律不动）。
     * 场地世界可能和猜猜乐共用（{@code world_gtb}），所以绝不用 {@code world.getEntities()}
     * 一把梭删，必须先判断坐标在不在自己场地的框里。
     * <p>
     * 只扫已加载的区块：场地区域在复制/开局/收尾时都是加载的，够用。
     */
    private void clearFieldEntities(BuildRunArena a) {
        if (a.getPhase() == BuildRunArena.Phase.WAITING) {
            return;
        }
        World w = a.getWorld();
        if (w == null) {
            return;
        }
        List<int[]> regions = new ArrayList<>();
        int[] template = a.templateBounds();
        if (template != null) {
            regions.add(template);
        }
        for (BuildRunPlot plot : a.getPlots().values()) {
            regions.add(plot.getBounds());
        }
        if (regions.isEmpty()) {
            return;
        }
        for (Entity e : w.getEntities()) {
            if (e instanceof Player) {
                continue;
            }
            Location loc = e.getLocation();
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();
            for (int[] b : regions) {
                if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) {
                    e.remove();
                    break;
                }
            }
        }
    }

    public void handleQuitIfInGame(Player p) {
        handleQuit(p);
    }

    // ==================================================================
    // 等待倒计时
    // ==================================================================

    private void updateWaitCountdown(BuildRunArena a) {
        if (a.getPhase() != BuildRunArena.Phase.WAITING) {
            return;
        }
        wait(a).update();
    }

    private WaitCountdown wait(BuildRunArena a) {
        return waits.computeIfAbsent(a.getName(), n -> new WaitCountdown(plugin, new WaitCountdown.Host() {
            @Override
            public Collection<UUID> waitingPlayers() {
                return a.getPlayers();
            }

            @Override
            public int minPlayers() {
                return cfg("min-players", 2);
            }

            @Override
            public int maxPlayers() {
                // 不限人数：永远不算"满员"，只走默认 30s 倒计时（OP 在队列里时仍然停着等手动开始）
                return Integer.MAX_VALUE;
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
                        Chat.actionBar(p, "&6开始倒计时：&e" + secondsLeft + "s");
                    }
                }
            }

            @Override
            public void onMessage(String message) {
                broadcast(a, message);
            }

            @Override
            public void onStart() {
                Player any = a.getPlayers().isEmpty() ? null : Bukkit.getPlayer(a.getPlayers().get(0));
                if (any != null) {
                    start(any, a.getName());
                }
            }
        }));
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private int cfg(String key, int def) {
        return plugin.getConfig().getInt(key, def);
    }

    /**
     * 一行诊断（启动时打进控制台）：config.yml 的真实绝对路径 / 字节数 / 最后修改时间，
     * 以及 build-seconds 是取自文件还是内置默认值。
     * <p>典型用途：面板上"本地 180、线上 120"——如果这行打印的修改时间还是几个小时前，
     * 说明那次"覆盖重复文件"根本没落到这个路径（或落到子目录里去了），别再改代码了。</p>
     */
    private String describeConfigFile() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        StringBuilder sb = new StringBuilder("配置文件：").append(file.getAbsolutePath());
        if (file.isFile()) {
            sb.append("（").append(file.length()).append(" B，最后修改 ")
                    .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(file.lastModified())))
                    .append("）");
        } else {
            sb.append("（文件不存在：用的插件内置默认值，下次保存会生成）");
        }
        sb.append("；build-seconds ")
                .append(plugin.getConfig().isInt("build-seconds") ? "取自 config.yml" : "缺这个键，用内置默认 180");
        return sb.toString();
    }

    private int floorTolerance() {
        return Math.max(0, cfg("floor-tolerance", 1));
    }

    /** 建造者开跑前「所有人（含本人）先旁观这条跑酷」的秒数（0 = 不展示、直接开跑；上限 30s 防手滑）。 */
    private int runPreviewSeconds() {
        return Math.max(0, Math.min(30, cfg("run-preview-seconds", 3)));
    }

    /** 建造者没通过后「留在原地让观众多看几秒」的秒数（0 = 立刻转旁观并跳过；上限 10s 防手滑）。 */
    private long failLingerMs() {
        return Math.max(0L, Math.min(10000L, (long) cfg("fail-linger-seconds", 3) * 1000L));
    }

    private void broadcast(BuildRunArena a, String msg) {
        for (UUID id : a.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                msg(p, msg);
            }
        }
    }

    private void msg(Player p, String msg) {
        Chat.sendTitled(p, msg);
    }

    private void reportArena(BuildRunArena a) {
        boolean playing = a.getPhase() != BuildRunArena.Phase.WAITING;
        String extra = null;
        if (a.getPhase() == BuildRunArena.Phase.RUN && a.getBuilder() != null) {
            extra = "第 " + Math.max(1, a.getTurn()) + "/" + Math.max(1, a.getPlayers().size()) + " 条";
        } else if (a.getPhase() == BuildRunArena.Phase.BUILDING) {
            extra = "建造中 " + a.secondsLeft() + "s";
        }
        HubAPI.setArena(GAME, a.getName(), playing ? GameState.PLAYING : GameState.WAITING,
                a.getPlayers().size(), extra);
        HubAPI.setArenaSpectator(GAME, a.getName(), a.getLobby());
    }

    /** 回主城：优先走 HubCore（带菜单道具），没有主城插件时回场地等待点。 */
    private void goHome(Player p) {
        if (HubAPI.isReady()) {
            HubAPI.teleportToLobby(p);
            return;
        }
        BuildRunArena a = null;
        String name = playerArena.get(p.getUniqueId());
        if (name != null) {
            a = arenas.get(name);
        }
        if (a != null) {
            Location loc = a.getLobby() != null ? a.getLobby() : a.getSpawn();
            if (loc != null) {
                p.teleport(loc);
            }
        }
    }

    private String nameOf(UUID id) {
        Player p = id == null ? null : Bukkit.getPlayer(id);
        return p == null ? shortId(id) : p.getName();
    }

    private static String shortId(UUID id) {
        return id == null ? "?" : id.toString().substring(0, 6);
    }

    private String shortLoc(Location loc) {
        if (loc == null) {
            return "&c未设置";
        }
        return "&f" + loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
    }

    private String shortBounds(int[] b) {
        if (b == null) {
            return "&c未圈选";
        }
        return "&f" + b[0] + "," + b[1] + "," + b[2] + " → " + b[3] + "," + b[4] + "," + b[5];
    }

    private String sizeText(BuildRunArena a) {
        int[] b = a.templateBounds();
        if (b == null) {
            return "未圈选";
        }
        return (b[3] - b[0] + 1) + "×" + (b[4] - b[1] + 1) + "×" + (b[5] - b[2] + 1);
    }

    /** 道具菜单的持有者（用来识别"这个 GUI 是本插件的"）。 */
    static final class KitHolder implements InventoryHolder {
        private Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /** 羊毛效果图鉴子菜单的持有者（点击同样全部拦下 → 展示物品拿不走）。 */
    static final class WoolHolder implements InventoryHolder {
        private Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }
}
