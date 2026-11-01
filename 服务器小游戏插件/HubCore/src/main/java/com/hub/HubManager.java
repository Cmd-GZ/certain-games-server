package com.hub;

import com.hub.api.ArenaStatus;
import com.hub.api.GameState;
import com.hub.api.HubAPI;
import com.hub.api.HubService;
import com.hub.game.GameInfo;
import com.hub.menu.MenuLayout;
import com.hub.util.Chat;
import com.hub.util.Titles;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 大厅、菜单、游戏注册、tab 管理。同时也是公共库 HubAPI 的服务实现（HubService）。 */
public class HubManager implements HubService {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final HubCorePlugin plugin;
    /** 玩家 -> (游戏, 状态)。没有记录的玩家视为在大厅。 */
    private final Map<UUID, PlayerGame> gameMap = new LinkedHashMap<>();
    /** 玩家 -> tab 名字颜色覆盖（legacy 色号，如 "c"/"9"），小游戏插件按队伍染色用。 */
    private final Map<UUID, String> tabNameColor = new LinkedHashMap<>();

    public HubManager(HubCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** 运行时登记一个游戏（一般不需要：启动时会自动扫描带接入标记的插件）。 */
    @Override
    public void registerGame(String id, String displayName, String icon, String color) {
        char c = (color == null || color.isEmpty()) ? 0 : color.charAt(0);
        plugin.getRegistry().register(new GameInfo(id, displayName, icon, c));
    }

    /** 一名玩家当前所处的游戏与状态。 */
    public record PlayerGame(String game, GameState state) {
    }

    // ---------- 大厅 ----------

    public Location getLobby() {
        String world = plugin.getConfig().getString("lobby.world", "world");
        var w = Bukkit.getWorld(world);
        if (w == null) {
            return null;
        }
        double x = plugin.getConfig().getDouble("lobby.x", 0.5);
        double y = plugin.getConfig().getDouble("lobby.y", 64);
        double z = plugin.getConfig().getDouble("lobby.z", 0.5);
        float yaw = (float) plugin.getConfig().getDouble("lobby.yaw", 0);
        float pitch = (float) plugin.getConfig().getDouble("lobby.pitch", 0);
        return new Location(w, x, y, z, yaw, pitch);
    }

    /** 把当前位置设为大厅。 */
    public void setLobby(Player p) {
        Location loc = p.getLocation();
        plugin.getConfig().set("lobby.world", loc.getWorld().getName());
        plugin.getConfig().set("lobby.x", loc.getX());
        plugin.getConfig().set("lobby.y", loc.getY());
        plugin.getConfig().set("lobby.z", loc.getZ());
        plugin.getConfig().set("lobby.yaw", loc.getYaw());
        plugin.getConfig().set("lobby.pitch", loc.getPitch());
        plugin.saveConfig();
    }

    /** 传送大厅并给菜单道具。传回主城即视为回到大厅，同时清除游戏状态（保证与游戏分区分开）。 */
    public void teleportToLobby(Player p) {
        spectating.remove(p.getUniqueId()); // 回大厅 = 结束观战
        HubAPI.notifyLobbyHandlers(p); // 先让各游戏插件把玩家踢出自身游戏状态（有防递归保护）
        // **兜底**：不管各游戏有没有在 leave() 里摘会话，回主城必须把玩家从归属链上摘干净。
        // 真实 bug：LuckPillar 之类只接了 session.join 没接 session.leave，
        // 玩家用床退出后会话仍挂着他 → 之后点任何别的游戏都被 I1 拒绝
        // （表现为"你正在 luckypillar 的等待中"，重进服务器也无效，只能重启服务器）。
        com.hub.session.PlayerSessions.quitToLobby(p);
        clearGame(p.getUniqueId());
        Location lobby = getLobby();
        if (lobby != null) {
            p.teleport(lobby);
        }
        giveMenuItem(p);
        p.setGameMode(GameMode.SURVIVAL); // 一律回生存：防止淘汰/对局遗留的观战或创造模式在重连后"无敌/穿墙"（断线回主城的兜底）
        p.setHealth(20);
        p.setFoodLevel(20);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
    }

    public Material getMenuItemMaterial() {
        try {
            return Material.valueOf(plugin.getConfig().getString("menu-item", "COMPASS").toUpperCase());
        } catch (IllegalArgumentException ex) {
            return Material.COMPASS;
        }
    }

    /** 给玩家菜单道具（不重复给）。带 PDC 标记，避免和别的指南针（比如杀手的追踪指南针）混淆。 */
    public void giveMenuItem(Player p) {
        p.getInventory().clear();
        p.getInventory().setHelmet(null);
        p.getInventory().setChestplate(null);
        p.getInventory().setLeggings(null);
        p.getInventory().setBoots(null);
        Material mat = getMenuItemMaterial();
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // 名字固定由这里写死（不读配置）：菜单道具每次发放都重刷，OP 改名不会生效也不会留旧名字。
            meta.displayName(Chat.noItalic(LEGACY.deserialize("&e小游戏菜单 &7(右键打开)")));
            meta.getPersistentDataContainer().set(MENU_ITEM_KEY, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        // 菜单道具固定放在配置的格子（默认 4）；那一格被游戏入口占用时自动顺延到空槽，
        // 以免把 OP 摆的菜单物品顶掉，也免得玩家永远拿不到菜单道具。
        p.getInventory().setItem(menuItemSlot(p.getInventory()), item);
    }

    /**
     * 菜单道具该放哪一格：配置的槽位（默认 4）空着就固定放那儿；
     * 那一格被 OP 摆的菜单物品占住时就近找空槽，免得顶掉菜单入口或玩家拿不到指南针。
     */
    private int menuItemSlot(org.bukkit.inventory.PlayerInventory inv) {
        int preferred = plugin.getMenuLayout().menuItemSlot();
        if (isEmpty(inv.getItem(preferred))) {
            return preferred;
        }
        for (int d = 1; d < 9; d++) {
            int right = preferred + d;
            if (right < 9 && isEmpty(inv.getItem(right))) {
                return right;
            }
            int left = preferred - d;
            if (left >= 0 && isEmpty(inv.getItem(left))) {
                return left;
            }
        }
        return preferred;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    /** 判断物品是否是主城菜单道具（带标记）。 */
    public boolean isMenuItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(MENU_ITEM_KEY, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    // ---------- 回主城道具 ----------

    // 注意：命名空间保留 "gamehub" —— 这个 key 已经写进了各插件 kit 存档里的物品数据
    // （例如 DuelArena/SkyPit 的 config.yml 有 "gamehub:hub-item"），改名会让旧道具失效。
    private static final org.bukkit.NamespacedKey HUB_ITEM_KEY = new org.bukkit.NamespacedKey("gamehub", "hub-item");
    /** 主城菜单道具的标记（只有主城发出来的那个才会开菜单）。 */
    private static final org.bukkit.NamespacedKey MENU_ITEM_KEY = new org.bukkit.NamespacedKey("gamehub", "menu-item");

    /** 给玩家"回主城"道具：右键 = 退出所有游戏并回主城（放空槽，背包满则放主手）。 */
    public void giveHubItem(Player p) {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(LEGACY.deserialize("&a回主城 &7(右键使用)")));
            List<Component> lore = new ArrayList<>();
            lore.add(LEGACY.deserialize("&7右键退出所有游戏并返回主城"));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(HUB_ITEM_KEY, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        var left = p.getInventory().addItem(item);
        if (!left.isEmpty()) {
            p.getInventory().setItemInMainHand(item);
        }
        p.sendMessage(LEGACY.deserialize("&a已获得回主城道具（右键使用）。"));
    }

    /** 判断物品是否为"回主城"道具（带 PDC 标记，防玩家做同名物品误触发）。 */
    public boolean isHubItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(HUB_ITEM_KEY, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    // ---------- 观战 ----------

    /** 正在观战的玩家 -> 被观战的游戏显示名。 */
    private final Map<UUID, String> spectating = new LinkedHashMap<>();
    /** 游戏 -> 场地 -> 观战落点（由各游戏通过 HubAPI.setArenaSpectator 上报）；场地名为空串 = 整个游戏共用。 */
    private final Map<String, Map<String, Location>> spectatorSpots = new LinkedHashMap<>();

    /** 可观战的一局：游戏显示名 + 场地名（不分场地的单场游戏场地名为空串）。 */
    public record SpectateTarget(String game, String arena, String extra) {
    }

    @Override
    public void setArenaSpectator(String game, String arena, Location location) {
        String canon = plugin.getRegistry().canonicalName(game);
        String key = arena == null ? "" : arena;
        Map<String, Location> spots = spectatorSpots.computeIfAbsent(canon, k -> new LinkedHashMap<>());
        if (location == null || location.getWorld() == null) {
            spots.remove(key);
            return;
        }
        spots.put(key, location.clone());
    }

    public boolean isSpectating(UUID id) {
        return spectating.containsKey(id);
    }

    /** 右键末影之眼：列出所有正在进行的一局（有多场地就每个场地一条），点哪个进哪个。 */
    public void openSpectateMenu(Player p) {
        List<SpectateTarget> targets = runningTargets();
        if (targets.isEmpty()) {
            p.sendMessage(LEGACY.deserialize("&7现在没有正在进行的小游戏。"));
            return;
        }
        int rows = Math.min(6, (targets.size() + 8) / 9);
        SpectateHolder holder = new SpectateHolder();
        Inventory inv = Bukkit.createInventory(holder, rows * 9, LEGACY.deserialize("&8观战：选择对局"));
        holder.setInventory(inv);
        int slot = 0;
        for (SpectateTarget target : targets) {
            ItemStack icon = new ItemStack(gameIcon(target.game()));
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                String title = target.arena().isEmpty()
                        ? "&b" + target.game()
                        : "&b" + target.game() + " &7· &f" + target.arena();
                meta.displayName(Chat.noItalic(LEGACY.deserialize(title)));
                List<Component> lore = new ArrayList<>();
                if (target.extra() != null && !target.extra().isEmpty()) {
                    lore.add(LEGACY.deserialize("&7" + target.extra()));
                }
                lore.add(LEGACY.deserialize("&a点击进入观战"));
                meta.lore(lore);
                icon.setItemMeta(meta);
            }
            holder.bind(slot, target);
            inv.setItem(slot++, icon);
        }
        p.openInventory(inv);
    }

    /**
     * 进入观战：优先用该场地上报的观战落点（`HubAPI.setArenaSpectator`）；
     * 没上报就当老办法——落到该局一位存活玩家头顶 3 格。
     */
    public void spectateGame(Player p, String game, String arena) {
        String canon = plugin.getRegistry().canonicalName(game);
        if (isSpectating(p.getUniqueId())) {
            p.sendMessage(LEGACY.deserialize("&7你已经在观战了，右键 &b末影之眼 &7可以退出。"));
            return;
        }
        if (!isInLobby(p.getUniqueId())) {
            p.sendMessage(LEGACY.deserialize("&c你还在游戏里，先退出当前对局才能观战。"));
            return;
        }
        Location spot = spectatorSpot(canon, arena);
        Player follow = spot == null ? findRunningPlayer(canon) : null;
        if (spot == null && follow == null) {
            p.sendMessage(LEGACY.deserialize("&c" + canon
                    + (arena == null || arena.isEmpty() ? "" : "｜" + arena) + " 现在没有正在进行的对局。"));
            return;
        }
        p.closeInventory();
        spectating.put(p.getUniqueId(), canon);
        if (spot != null) {
            p.teleport(spot);
        } else {
            p.teleport(follow.getLocation().clone().add(0.0, 3.0, 0.0));
        }
        p.setFallDistance(0f);
        p.setGameMode(GameMode.SPECTATOR);
        // 旁观模式用不了道具（快捷栏都不显示），退出靠聊天栏的可点按钮或 /hub
        p.getInventory().clear();
        String label = canon + (arena == null || arena.isEmpty() ? "" : "｜" + arena);
        p.sendMessage(LEGACY.deserialize("&b&l观战中：&f" + label
                + "&7 —— 点下面的按钮，或输入 &e/hub&7 返回大厅。"));
        p.sendMessage(exitButton());
        Chat.actionBar(p, "&7观战中：&b" + label + " &7｜ 输入 &e/hub &7或点聊天栏【退出观战】");
    }

    /** 聊天栏里那行可点的「退出观战」按钮。 */
    private Component exitButton() {
        return LEGACY.deserialize("&b&l[ 点击这里退出观战 ]")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/hub"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        LEGACY.deserialize("&7点击返回大厅")));
    }

    /** 兼容旧调用（不指定场地）。 */
    public void spectateGame(Player p, String game) {
        spectateGame(p, game, "");
    }

    public void spectateGame(Player p, SpectateTarget target) {
        spectateGame(p, target.game(), target.arena());
    }

    /** 场地观战落点：先找该场地，再找整个游戏共用的那个；都没有返回 null。 */
    private Location spectatorSpot(String canonGame, String arena) {
        Map<String, Location> spots = spectatorSpots.get(canonGame);
        if (spots == null) {
            return null;
        }
        Location loc = arena == null || arena.isEmpty() ? null : spots.get(arena);
        if (loc == null) {
            loc = spots.get("");
        }
        return loc == null ? null : loc.clone();
    }

    /** 右键末影之眼退出观战：回大厅并重新发道具。 */
    public void exitSpectate(Player p) {
        spectating.remove(p.getUniqueId());
        teleportToLobby(p);
        p.sendMessage(LEGACY.deserialize("&7已退出观战，回到大厅。"));
    }

    /** 每秒检查观战：对局结束就自动送回大厅，顺便在经验条上方提示退出方法。 */
    public void tickSpectate() {
        if (spectating.isEmpty()) {
            return;
        }
        for (UUID id : new ArrayList<>(spectating.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                spectating.remove(id);
                continue;
            }
            String game = spectating.get(id);
            if (!isGameRunning(game)) {
                spectating.remove(id);
                if (p.getGameMode() == GameMode.SPECTATOR) {
                    teleportToLobby(p);
                }
                p.sendMessage(LEGACY.deserialize("&7你观战的对局已结束，已送回大厅。"));
                continue;
            }
            if (p.getGameMode() != GameMode.SPECTATOR) {
                // 被别的插件改了游戏模式：观战没法继续了，直接送回大厅，别把人晾在游戏世界里
                spectating.remove(id);
                teleportToLobby(p);
                p.sendMessage(LEGACY.deserialize("&7观战已结束（游戏模式被改动），已送回大厅。"));
                continue;
            }
            Chat.actionBar(p, "&7观战中：&b" + game + " &7｜ 输入 &e/hub &7退出（聊天栏有按钮）");
        }
    }

    /** 该游戏当前有没有正在进行的对局（菜单红色羊毛的判定口径；没有场地信息的单场游戏看玩家状态）。 */
    public boolean isGameRunning(String game) {
        for (ArenaInfo info : arenasOf(game).values()) {
            if (info != null && info.state() == GameState.PLAYING) {
                return true;
            }
        }
        return hasPlayingPlayer(game);
    }

    /** 所有正在进行的一局（末影之眼列表用；多场地游戏每个场地一条）。 */
    public List<SpectateTarget> runningTargets() {
        List<SpectateTarget> out = new ArrayList<>();
        for (GameInfo info : plugin.getRegistry().getGames()) {
            String game = info.displayName();
            boolean listed = false;
            for (Map.Entry<String, ArenaInfo> e : arenasOf(game).entrySet()) {
                ArenaInfo arena = e.getValue();
                if (arena != null && arena.state() == GameState.PLAYING) {
                    out.add(new SpectateTarget(game, e.getKey(), arena.extra()));
                    listed = true;
                }
            }
            // 没有上报场地的单场游戏（天坑 / 粒子枪战 等）：只要有人在对局里就列一条
            if (!listed && hasPlayingPlayer(game)) {
                out.add(new SpectateTarget(game, "", null));
            }
        }
        return out;
    }

    /** 该游戏有没有处于「已加入对局」状态的在线玩家。 */
    private boolean hasPlayingPlayer(String game) {
        for (Map.Entry<UUID, PlayerGame> e : gameMap.entrySet()) {
            PlayerGame pg = e.getValue();
            if (pg == null || pg.state() != GameState.PLAYING) {
                continue;
            }
            if (!game.equals(plugin.getRegistry().canonicalName(pg.game()))) {
                continue;
            }
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline()) {
                return true;
            }
        }
        return false;
    }

    /** 该游戏正在对局中的一位存活玩家（观战落点）。 */
    private Player findRunningPlayer(String game) {
        for (Map.Entry<UUID, PlayerGame> e : gameMap.entrySet()) {
            PlayerGame pg = e.getValue();
            if (pg == null || pg.state() != GameState.PLAYING) {
                continue;
            }
            if (!game.equals(plugin.getRegistry().canonicalName(pg.game()))) {
                continue;
            }
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline() && !p.isDead()) {
                return p;
            }
        }
        return null;
    }

    /** 游戏图标材质（注册表里存的是字符串，非法值退回末影之眼）。 */
    private Material gameIcon(String game) {
        GameInfo info = plugin.getRegistry().get(game);
        Material mat = info == null ? null : Material.matchMaterial(info.icon());
        return mat == null ? Material.ENDER_EYE : mat;
    }

    /** 观战列表 GUI 的 holder：只记「槽位 -> 目标」，点击时按它分发。 */
    public static final class SpectateHolder implements org.bukkit.inventory.InventoryHolder {
        private final Map<Integer, SpectateTarget> targets = new LinkedHashMap<>();
        private Inventory inventory;

        void bind(int slot, SpectateTarget target) {
            targets.put(slot, target);
        }

        /** 该槽位对应的目标；不是观战图标返回 null。 */
        public SpectateTarget targetAt(int slot) {
            return targets.get(slot);
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ---------- 菜单 ----------

    /** 游戏 -> 场地名 -> 场地信息（由各游戏插件通过 HubAPI.setArena 实时上报）。 */
    private final Map<String, Map<String, ArenaInfo>> arenaInfos = new LinkedHashMap<>();

    /** 某游戏一个场地的最新状态。 */
    public record ArenaInfo(GameState state, int players, String extra) {
    }

    private MenuLayout layout() {
        return plugin.getMenuLayout();
    }

    /** 菜单槽位动作：game/best=加入该游戏人最多的等待中场地；arena=加入指定场地；open=打开场地子菜单；cmd=执行命令。 */
    public record MenuAction(String type, String game, String arena, String command) {
    }

    // ---------- 场地状态（游戏插件上报） ----------

    /** 游戏插件上报场地状态（WAITING=等待中可加入；PLAYING=游戏中）。 */
    public void setArena(String game, String arena, GameState state, int players, String extra) {
        if (game == null || arena == null || state == null) {
            return;
        }
        String canon = plugin.getRegistry().canonicalName(game);
        arenaInfos.computeIfAbsent(canon, k -> new LinkedHashMap<>()).put(arena, new ArenaInfo(state, players, extra));
    }

    /** 游戏插件删除场地时移除其状态。 */
    public void removeArena(String game, String arena) {
        if (game == null || arena == null) {
            return;
        }
        String canon = plugin.getRegistry().canonicalName(game);
        Map<String, ArenaInfo> m = arenaInfos.get(canon);
        if (m != null) {
            m.remove(arena);
        }
    }

    /** 玩家加入某游戏：全服公屏播报（游戏名用注册表里的显示名与颜色）。 */
    @Override
    public void announceJoin(String gameIdOrName, String playerName) {
        announceJoin(gameIdOrName, playerName, null);
    }

    /**
     * 带场地名的加入播报：{@code [游戏][场地] 玩家 加入了游戏}。
     * <p>多场地的游戏（单挑）传场地名，玩家才知道加的是哪个场；场地名为空时与两参数版本完全一致。
     */
    @Override
    public void announceJoin(String gameIdOrName, String playerName, String arenaName) {
        GameInfo info = plugin.getRegistry().get(gameIdOrName);
        String name = info == null ? String.valueOf(gameIdOrName) : info.displayName();
        char color = info != null && info.hasColor() ? info.color() : '6';
        String arena = arenaName == null ? "" : arenaName.trim();
        String head = "&8[&" + color + name + "&8]";
        if (!arena.isEmpty()) {
            head += "&8[&e" + arena + "&8]"; // 场地名统一金色，与 kit 里其它场名提示一致
        }
        Component msg = Titles.decorate(LEGACY.deserialize(head + " &e" + playerName + " &7加入了游戏"));
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(msg);
        }
    }

    /**
     * 某游戏的场地表（无则空）。
     * <p>
     * 游戏名先归一化成注册表显示名：场地状态是按显示名（{@code setArena} 里 canonicalName）存的，
     * 而调用方可能传 OP 手写的动作名/别名（如 {@code 单挑PvP}、{@code 决斗}）。
     * 不归一化会返回空表 → 场地子菜单一个物品都没有（表现就是"看不到羊毛"）。
     */
    public Map<String, ArenaInfo> arenasOf(String game) {
        Map<String, ArenaInfo> m = arenaInfos.get(plugin.getRegistry().canonicalName(game));
        return m == null ? Map.of() : m;
    }

    /** HubAPI 查询入口：返回某游戏全部场地的只读快照（街机派对用来挑空场）。 */
    @Override
    public List<ArenaStatus> arenas(String game) {
        List<ArenaStatus> out = new ArrayList<>();
        String canon = plugin.getRegistry().canonicalName(game);
        Map<String, Location> spots = spectatorSpots.getOrDefault(canon, Map.of());
        for (Map.Entry<String, ArenaInfo> e : arenasOf(game).entrySet()) {
            Location spot = spots.get(e.getKey());
            if (spot == null) {
                spot = spots.get("");
            }
            out.add(new ArenaStatus(e.getKey(), e.getValue().state(), e.getValue().players(), spot));
        }
        return out;
    }

    private ArenaInfo arenaInfo(String game, String arena) {
        return arenaInfos.getOrDefault(plugin.getRegistry().canonicalName(game), Map.of()).get(arena);
    }

    /**
     * 打开菜单：主菜单内容由 OP 用 /hub menu edit 摆放（config.yml 的 menu 段）。
     * 玩家点游戏图标时，若该游戏有多个场地就进场地子菜单，只有一个就直接加入。
     */
    public void openMenu(Player p) {
        plugin.getMenuEditor().openMenu(p, MenuLayout.Place.MAIN, null, false);
    }

    /** 打开某游戏的场地子菜单（场地羊毛由实时状态渲染）。 */
    public void openArenaMenu(Player p, String game) {
        if (game == null || game.isEmpty()) {
            return;
        }
        plugin.getMenuEditor().openMenu(p, MenuLayout.Place.SUB, game, false);
    }

    /**
     * 菜单点击（按槽位 + 动作处理，杜绝不同游戏同名场地串游戏）。
     *
     * @param place MAIN=主菜单，SUB=某游戏的场地子菜单
     * @param game  子菜单所属游戏；主菜单传 null
     */
    public void handleMenuClick(Player p, MenuLayout.Place place, String game, int slot) {
        MenuLayout layout = layout();
        // 游戏名先归一化成显示名：场地状态是按显示名存的，动作/子菜单键里可能是 alias 或少了空格
        // （如 单挑PvP / 决斗 -> 单挑 PvP），不归一化会查不到场地而误报"正在游戏中"。
        game = plugin.getRegistry().canonicalName(game);
        if (place == MenuLayout.Place.SUB) {
            MenuLayout.SubItem item = layout.subItem(game, String.valueOf(slot));
            String action = item == null ? null : item.action();
            if (action != null && action.startsWith("arena:")) {
                String[] parts = action.substring("arena:".length()).split("\\|", 2);
                if (parts.length == 2) {
                    // 动作里存的游戏名可能是旧写法（alias/少空格），一并归一化
                    joinArena(p, plugin.getRegistry().canonicalName(parts[0]), parts[1]);
                    return;
                }
            }
            // 子菜单不一定是"某游戏的场地列表"：也允许 game/open/cmd/none 动作（迷你小游戏合集用）
            if (action != null) {
                String[] parsed = MenuLayout.parseAction(action);
                if (parsed != null) {
                    switch (parsed[0]) {
                        case "game" -> {
                            clickGame(p, parsed[1]);
                            return;
                        }
                        case "open" -> {
                            p.closeInventory();
                            openArenaMenu(p, parsed[1]);
                            return;
                        }
                        case "cmd" -> {
                            if (parsed[3] != null && !parsed[3].isEmpty()) {
                                p.closeInventory();
                                Bukkit.dispatchCommand(p, parsed[3]);
                            }
                            return;
                        }
                        case "none" -> {
                            return;
                        }
                        default -> {
                        }
                    }
                }
            }
            joinArena(p, game, arenaOfSlot(game, slot));
            return;
        }
        MenuLayout.Entry entry = layout.entry(MenuLayout.Place.MAIN, null, slot);
        if (entry == null || entry.action() == null) {
            return;
        }
        if (entry.opOnly() && !p.isOp()) {
            return;
        }
        String[] parsed = MenuLayout.parseAction(entry.action());
        if (parsed == null) {
            return;
        }
        switch (parsed[0]) {
            case "game" -> clickGame(p, parsed[1]);
            case "spectate" -> {
                p.closeInventory();
                openSpectateMenu(p);
            }
            case "open" -> {
                p.closeInventory();
                openArenaMenu(p, parsed[1]);
            }
            case "arena" -> {
                String arena = parsed[2];
                if (arena == null || arena.isEmpty()) {
                    p.sendMessage(LEGACY.deserialize("&c这个槽位没写场地名，请 OP 用 /hub menu edit 或 /hub menu action 修正。"));
                    return;
                }
                p.closeInventory();
                joinArena(p, plugin.getRegistry().canonicalName(parsed[1]), arena);
            }
            case "cmd" -> {
                if (parsed[3] != null && !parsed[3].isEmpty()) {
                    p.closeInventory();
                    Bukkit.dispatchCommand(p, parsed[3]);
                }
            }
            default -> {
                // none：纯装饰
            }
        }
    }

    /**
     * 这个游戏是否已经"房间化"（登记了模板）。
     *
     * <p>返回已登记模板的 gameKey；null = 还没模板，调用方保持旧的静态场地行为。
     * 菜单里是**显示名**（"建筑猜猜乐"），模板登记的是 gameKey（"gtb"），
     * 所以先让注册表把显示名解析成 id，再按 id 匹配。
     */
    private String matchRoomGame(String game) {
        com.hub.gui.RoomGui.init(plugin);
        var regInfo = plugin.getRegistry().get(game);
        String gameKey = regInfo == null ? null : regInfo.id();
        return com.hub.gui.RoomGui.matchTemplateGame(gameKey);
    }

    /** 点游戏图标：只有 1 个场地才直接进，多个（或还没有场地信息）都打开场地子菜单。 */
    private void clickGame(Player p, String game) {
        // R3：房间化分流要放在**这一层**，不能只在 joinArena 里做 ——
        // 因为"只有 1 个场地"的游戏会走到下面的 bestJoinCommand + dispatchCommand，
        // 那条路径**根本不经过 joinArena**（曾经因此让房间流程在最常见的入口上失效）。
        String matched = matchRoomGame(game);
        if (matched != null) {
            p.closeInventory();
            com.hub.gui.RoomGui.open(p, matched, com.hub.gui.RoomGui.Mode.ROOMS);
            return;
        }
        Map<String, ArenaInfo> arenas = arenasOf(game);
        if (arenas.size() != 1) {
            p.closeInventory();
            openArenaMenu(p, game);
            return;
        }
        String cmd = bestJoinCommand(game);
        if (cmd == null) {
            // 没有等待中的场地，但这一局正在打 → 点图标直接进入观战
            if (isGameRunning(game)) {
                p.closeInventory();
                // 只有 1 个场地时 arena 就是它；多场地走到这里是"没有进行中的场地"，用空串兜底
                String only = arenas.size() == 1 ? arenas.keySet().iterator().next() : "";
                spectateGame(p, game, only);
                return;
            }
            p.sendMessage(LEGACY.deserialize("&c该模式当前没有等待中的场地。"));
            return;
        }
        p.closeInventory();
        Bukkit.dispatchCommand(p, cmd);
    }

    /** 加入指定场地（状态不是等待中就只提示）。 */
    private void joinArena(Player p, String game, String arena) {
        // R3：这个游戏已经登记了模板 -> 走**房间流程**（选房 UI），不再直接进静态场地。
        // 没有模板的游戏保持旧行为 —— 这样房间化可以按游戏逐个开启，不用一次性全切。
        // 菜单里是**显示名**（"建筑猜猜乐"），模板登记的是 gameKey（"gtb"）——
        // 先让注册表把显示名解析成 id，再按 id 找模板（大小写不敏感）。
        String matched = matchRoomGame(game);
        if (matched != null) {
            p.closeInventory();
            com.hub.gui.RoomGui.open(p, matched, com.hub.gui.RoomGui.Mode.ROOMS);
            return;
        }
        if (arena == null || arena.isEmpty()) {
            p.sendMessage(LEGACY.deserialize("&c这个槽位还没绑定场地，请找 OP 用 /hub menu edit 设置。"));
            return;
        }
        ArenaInfo info = arenaInfo(game, arena);
        if (info == null || info.state() != GameState.WAITING) {
            // 正在打的对局：点红色羊毛 = 进去观战（有上报落点就落到落点）
            if (info != null && info.state() == GameState.PLAYING) {
                p.closeInventory();
                spectateGame(p, game, arena);
                return;
            }
            String extra = info == null ? null : info.extra();
            p.sendMessage(LEGACY.deserialize("&c场地 &e" + arena + "&c 正在游戏中"
                    + (extra == null || extra.isEmpty() ? "。" : "（" + extra + "）。")));
            return;
        }
        String cmd = joinCommand(game, arena);
        if (cmd == null) {
            p.sendMessage(LEGACY.deserialize("&c该游戏没有配置 join-prefix，OP 可用 /hub menu action 改成 cmd: 动作。"));
            return;
        }
        p.closeInventory();
        Bukkit.dispatchCommand(p, cmd);
    }

    /** 子菜单槽位对应的场地名（按 config 里 arena:<游戏>|<场地> 反查；失败再按场地列表顺序兜底）。 */
    private String arenaOfSlot(String game, int slot) {
        for (Map.Entry<String, MenuLayout.SubItem> e : layout().subItems(game).entrySet()) {
            MenuLayout.SubItem item = e.getValue();
            String action = item == null ? null : item.action();
            if (action != null && action.startsWith("arena:")) {
                String[] parts = action.substring("arena:".length()).split("\\|", 2);
                if (parts.length == 2 && slotMatches(game, e.getKey(), parts[1], slot)) {
                    return parts[1];
                }
            }
        }
        int i = 0;
        for (String arena : arenasOf(game).keySet()) {
            if (i == slot) {
                return arena;
            }
            i++;
        }
        return null;
    }

    /** 该场地条目是不是占着这个槽位（键是数字就直接比，否则按渲染时的顺序推）。 */
    private boolean slotMatches(String game, String key, String arena, int slot) {
        try {
            return Integer.parseInt(key.trim()) == slot;
        } catch (NumberFormatException ignored) {
            // 键是场地名：槽位是渲染时按场地顺序分配的
        }
        int i = 0;
        for (String a : arenasOf(game).keySet()) {
            if (i == slot) {
                return a.equals(arena);
            }
            i++;
        }
        return false;
    }

    /** 该游戏人最多的等待中场地；没有任何场地信息时回退配置的静态 command（如 gtb join）。 */
    private String bestJoinCommand(String game) {
        Map<String, ArenaInfo> arenas = arenaInfos.get(game);
        if (arenas != null && !arenas.isEmpty()) {
            String best = null;
            int bestPlayers = -1;
            for (Map.Entry<String, ArenaInfo> e : arenas.entrySet()) {
                if (e.getValue().state() == GameState.WAITING && e.getValue().players() > bestPlayers) {
                    best = e.getKey();
                    bestPlayers = e.getValue().players();
                }
            }
            if (best != null) {
                return joinCommand(game, best);
            }
        }
        return staticCommand(game);
    }

    /** 加入指定场地的命令：join-prefix + 场地名（如 "duel join test1"）。 */
    private String joinCommand(String game, String arena) {
        String prefix = joinPrefix(game);
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }
        return (arena == null || arena.isEmpty()) ? prefix : prefix + " " + arena;
    }

    /** 配置 games 段的 join-prefix（加入场地用命令前缀，如 duel join / hp join / tr join / gtb join）。 */
    private String joinPrefix(String game) {
        for (Map<?, ?> raw : plugin.getConfig().getMapList("games")) {
            Object nameObj = raw.get("name");
            String name = nameObj == null ? "游戏" : String.valueOf(nameObj);
            if (name.equals(game)) {
                Object c = raw.get("join-prefix");
                return c == null ? null : String.valueOf(c);
            }
        }
        return null;
    }

    /** 配置 games 段的静态 command（直接执行，如 gtb join）。 */
    private String staticCommand(String game) {
        for (Map<?, ?> raw : plugin.getConfig().getMapList("games")) {
            Object nameObj = raw.get("name");
            String name = nameObj == null ? "游戏" : String.valueOf(nameObj);
            if (name.equals(game)) {
                Object c = raw.get("command");
                return c == null ? null : String.valueOf(c);
            }
        }
        return null;
    }

    // ---------- 游戏状态（tab） ----------

    /** 玩家进入某游戏等待室（等待中）。兼容旧接口：以前 setGame 表示进入游戏，现在默认视为“等待中”。 */
    public void setGame(UUID player, String gameName) {
        setGameState(player, gameName, GameState.WAITING);
    }

    /** 游戏正式开始，玩家已加入对局。 */
    public void setPlaying(UUID player, String gameName) {
        setGameState(player, gameName, GameState.PLAYING);
    }

    /** 设置玩家在某游戏中的状态。 */
    public void setGameState(UUID player, String gameName, GameState state) {
        if (gameName == null || state == null) {
            return;
        }
        gameMap.put(player, new PlayerGame(plugin.getRegistry().canonicalName(gameName), state));
    }

    public void clearGame(UUID player) {
        gameMap.remove(player);
        tabNameColor.remove(player);
        spectating.remove(player);
    }

    /** 小游戏插件覆盖玩家 tab 名字颜色（如绿茵足球红/蓝队）；null/空串恢复默认。 */
    @Override
    public void setTabNameColor(UUID player, String colorCode) {
        if (player == null) {
            return;
        }
        if (colorCode == null || colorCode.isEmpty()) {
            tabNameColor.remove(player);
        } else {
            tabNameColor.put(player, colorCode);
        }
    }

    public String getGame(UUID player) {
        PlayerGame pg = gameMap.get(player);
        return pg == null ? null : pg.game();
    }

    public GameState getState(UUID player) {
        PlayerGame pg = gameMap.get(player);
        return pg == null ? null : pg.state();
    }

    /** 玩家是否在大厅（未加入任何游戏）。 */
    public boolean isInLobby(UUID id) {
        return gameMap.get(id) == null;
    }

    /** 每秒刷新 tab：头部按游戏分区，玩家名前缀标注等待中 / 已加入。 */
    public void refreshTab() {
        // 按游戏与状态收集在线玩家
        List<Player> lobby = new ArrayList<>();
        Map<String, List<Player>> waiting = new LinkedHashMap<>();
        Map<String, List<Player>> playing = new LinkedHashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerGame pg = gameMap.get(p.getUniqueId());
            if (pg == null) {
                lobby.add(p);
            } else if (pg.state() == GameState.WAITING) {
                waiting.computeIfAbsent(pg.game(), k -> new ArrayList<>()).add(p);
            } else {
                playing.computeIfAbsent(pg.game(), k -> new ArrayList<>()).add(p);
            }
        }

        // 分区顺序：已登记游戏（配置 + 自动发现）在前，未登记的兜底游戏在后
        List<String> keys = new ArrayList<>();
        for (GameInfo g : plugin.getRegistry().getGames()) {
            keys.add(g.displayName());
        }
        for (String k : waiting.keySet()) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }
        for (String k : playing.keySet()) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }

        StringBuilder hb = new StringBuilder();
        hb.append("&6&l========== 小游戏服 ==========\n");
        if (!lobby.isEmpty()) {
            hb.append("&7&l■ 大厅 &7(").append(lobby.size()).append(")\n");
            hb.append("&7").append(lobby.stream().map(Player::getName).collect(Collectors.joining("&7, "))).append("\n");
        }
        // 默认始终显示所有已登记游戏分区（每个游戏一个分区），空分区也显示（0）
        boolean showEmpty = plugin.getConfig().getBoolean("tab.show-empty-games", true);
        for (String game : keys) {
            List<Player> ws = waiting.get(game);
            List<Player> ps = playing.get(game);
            int count = (ws == null ? 0 : ws.size()) + (ps == null ? 0 : ps.size());
            if (count == 0 && !showEmpty) {
                continue;
            }
            char color = colorFor(game);
            hb.append("&").append(color).append("&l■ ").append(game).append(" &7(").append(count).append(")\n");
            if (ws != null && !ws.isEmpty()) {
                hb.append("  &e[等待中] ").append(ws.stream().map(p -> nameIn(p, 'e')).collect(Collectors.joining("&7, "))).append("\n");
            }
            if (ps != null && !ps.isEmpty()) {
                hb.append("  &a[已加入] ").append(ps.stream().map(p -> nameIn(p, 'a')).collect(Collectors.joining("&7, "))).append("\n");
            }
        }
        Component header = LEGACY.deserialize(hb.toString());

        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerGame pg = gameMap.get(p.getUniqueId());
            String name = p.getName();
            if (pg == null) {
                p.playerListName(LEGACY.deserialize("&7" + name));
            } else if (pg.state() == GameState.WAITING) {
                p.playerListName(LEGACY.deserialize("&e[等待中·" + pg.game() + "] " + coloredName(p)));
            } else {
                p.playerListName(LEGACY.deserialize("&a[已加入·" + pg.game() + "] " + coloredName(p)));
            }
            p.sendPlayerListHeaderAndFooter(header, Component.empty());
        }
    }

    /** 分区名单里的名字：有 tab 颜色覆盖用覆盖色，否则用分区默认色。 */
    private String nameIn(Player p, char def) {
        String c = tabNameColor.get(p.getUniqueId());
        return "&" + (c == null ? String.valueOf(def) : c) + p.getName();
    }

    /** 玩家列表名的名字段：有覆盖时切到覆盖色，无覆盖返回原名（继承前缀色）。 */
    private String coloredName(Player p) {
        String c = tabNameColor.get(p.getUniqueId());
        return c == null ? p.getName() : "&" + c + p.getName();
    }

    /** 游戏分区标题颜色：注册表颜色 > 兼容旧版硬编码 > 默认青色。 */
    private char colorFor(String game) {        GameInfo info = plugin.getRegistry().get(game);
        if (info != null && info.hasColor()) {
            return info.color();
        }
        switch (game) {
            case "猜猜乐":
            case "建筑猜猜乐":
                return '6';
            case "决斗":
            case "单挑 PvP":
                return 'c';
            case "烫手山芋":
                return 'e';
            case "TNT Run":
                return 'a';
            case "雪塔大作战":
                return 'b';
            default:
                return 'b';
        }
    }

    /** 菜单条目（已废弃：菜单现在按槽位构建，见 openMenu/handleMenuClick）。 */
}
