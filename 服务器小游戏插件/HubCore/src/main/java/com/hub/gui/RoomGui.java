package com.hub.gui;

import com.hub.room.GameMaps;
import com.hub.room.RoomManager;
import com.hub.room.WorldTemplate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 选房 UI（R3）：9×3 箱子，**第一格永远是"创建新房间"**，其后 8 格放现有房间
 * （没有房间就是空的 —— D10 的语义，不做占位）。
 *
 * <p>两页：
 * <ol>
 *   <li>{@link Mode#ROOMS}：某个游戏的房间列表 + 创建入口</li>
 *   <li>{@link Mode#TEMPLATES}：该游戏可用的模板（点一个 → 建房间）</li>
 * </ol>
 *
 * <p>**控制台也能用**：控制台打不开箱子，所以同样的入口对控制台输出**文本版**
 * （{@link #describe}）。这样 UI 的逻辑在没有客户端的情况下也能验证。
 */
public final class RoomGui {

    /** 一页显示多少个房间（D10：第一格是创建，其余 8 格）。 */
    public static final int ROOM_SLOTS = 8;
    /** 模板一页多少个。 */
    public static final int TEMPLATE_SLOTS = 9;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** 页面的身份标记（用 holder 而不是标题识别，OP 改标题也不会误判）。 */
    public enum Mode {
        /** 房间列表（第一格 = 创建）。 */
        ROOMS,
        /** **地图列表**（点一张图 → 按这张图建房间）。这是"创建房间"的正规入口。 */
        MAPS,
        /** 模板列表（**兜底**：没有 maps.yml 登记的老游戏才走到这里）。 */
        TEMPLATES,
        /** 单个房间的动作页：加入 / 开始 / 停止 / 删除。 */
        ROOM_ACTIONS
    }

    /** 打开中的界面。 */
    public static final class Holder implements InventoryHolder {
        public final String gameKey;
        public final Mode mode;
        /** ROOM_ACTIONS 页对应的房间；其它页为 null。 */
        public final String roomId;
        private Inventory inventory;

        public Holder(String gameKey, Mode mode) {
            this(gameKey, mode, null);
        }

        public Holder(String gameKey, Mode mode, String roomId) {
            this.gameKey = gameKey;
            this.mode = mode;
            this.roomId = roomId;
        }

        public void setInventory(Inventory inv) {
            this.inventory = inv;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** HubCore 实例（用于读 games.yml 的 join/start/stop 映射）。 */
    private static org.bukkit.plugin.Plugin plugin;

    public static void init(org.bukkit.plugin.Plugin pl) {
        plugin = pl;
    }

    private RoomGui() {
    }

    /** 读 plugins/HubCore/games.yml；缺失时返回空配置（调用方按"没配置"处理）。 */
    private static org.bukkit.configuration.file.FileConfiguration games() {
        if (plugin == null) {
            return new org.bukkit.configuration.file.YamlConfiguration();
        }
        java.io.File f = new java.io.File(plugin.getDataFolder(), "games.yml");
        return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
    }

    // ---------- 与主城菜单的接缝 ----------

    /**
     * 把"注册表里的游戏 id"映射成**已登记模板的 gameKey**（大小写不敏感）。
     *
     * <p>返回 null = 这个游戏还没有任何模板 → 调用方保持旧行为（直接进静态场地）。
     * 这样"房间化"可以**按游戏逐个开启**，不用一次性把 24 个游戏全切过去。
     */
    public static String matchTemplateGame(String gameId) {
        if (gameId == null) {
            return null;
        }
        // 模板已经不属于任何游戏（一个模板世界可能服务多个游戏），这里改查**地图表**：
        // 有地图 = 这个游戏具备房间化条件。
        if (!com.hub.room.GameMaps.mapNames(gameId).isEmpty()) {
            return gameId;
        }
        String norm = gameId.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
        for (String game : com.hub.room.GameMaps.games()) {
            String key = game.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
            if (key.equals(norm)) {
                return game;
            }
        }
        return null;
    }

    /** 某个 gameKey 是否有可用模板（有 = 该游戏已具备房间化条件）。 */
    public static boolean hasTemplates(String gameKey) {
        if (gameKey == null) {
            return false;
        }
        return !com.hub.room.GameMaps.mapNames(gameKey).isEmpty();
    }

    // ---------- 文本版（控制台用 / 也是 GUI 的内容来源） ----------

    /**
     * 把某一页的内容描述成若干行（控制台可读、GUI 的 lore 与之一致）。
     */
    public static List<String> describe(String gameKey, Mode mode) {
        return describe(gameKey, mode, null);
    }

    /** 文本版（含 ROOM_ACTIONS 需要 roomId）。 */
    public static List<String> describe(String gameKey, Mode mode, String roomId) {
        List<String> out = new ArrayList<>();
        if (mode == Mode.MAPS) {
            var names = gameKey == null ? java.util.List.<String>of()
                    : com.hub.room.GameMaps.mapNames(gameKey);
            out.add("&b[选地图] &f" + (gameKey == null ? "全部" : gameKey)
                    + " &7共 &f" + names.size() + " &7张（点一张用它建房间）");
            for (String name : names) {
                var map = com.hub.room.GameMaps.get(gameKey, name);
                String worlds = "";
                int regions = 0;
                if (map != null) {
                    java.util.List<String> tpls = new java.util.ArrayList<>();
                    for (var w : map.worlds()) {
                        tpls.add(w.templateKey());
                        if (w.regions() != null) {
                            regions += w.regions().size();
                        }
                    }
                    worlds = String.join("、", tpls);
                }
                out.add("&7- &f" + name + " &7世界=&f" + worlds + " &7区域=&f" + regions + " 块");
            }
            if (names.isEmpty()) {
                out.add("&7（这个游戏还没有登记地图，见 &fplugins/MiniGameLib/maps.yml&7）");
            }
        } else if (mode == Mode.ROOMS) {
            var rooms = RoomManager.rooms().stream()
                    .filter(r -> gameKey == null || gameKey.equals(r.game()))
                    .toList();
            out.add("&b[房间] &f" + (gameKey == null ? "全部" : gameKey)
                    + " &7共 &f" + rooms.size() + " &7个"
                    + " &7（第一格永远是 &f创建新房间&7）");
            for (var r : rooms) {
                String map = r.map() == null || r.map().isBlank() ? "（未登记）" : r.map();
                out.add("&7- &f" + map + " &7状态=&f" + stateText(r)
                        + " &7人=&f" + r.playerCount()
                        + " &7房间=&8" + r.roomId);
            }
            if (rooms.isEmpty()) {
                out.add("&7（还没有房间；点第一格选地图创建）");
            }
        } else if (mode == Mode.ROOM_ACTIONS) {
            var room = RoomManager.room(roomId);
            if (room == null) {
                out.add("&c房间 &f" + roomId + " &c不存在");
                return out;
            }
            out.add("&b[房间动作] &f" + room.roomId + " &7（模板 &f" + room.template.key()
                    + "&7，游戏 &f" + (room.game().isEmpty() ? room.template.key() : room.game()) + "&7）");
            out.add("&7  人=&f" + room.playerCount() + " &7空置=&f" + room.idleSeconds() + "s");
            var cfg = games();
            for (String[] a : new String[][]{
                    {"进入并加入", "join"}, {"开始一局", "start"}, {"停止", "stop"}}) {
                String cmd = cfg.getString("games." + (room.game().isEmpty() ? room.template.key() : room.game())
                        + "." + a[1] + "-command");
                out.add("&7  - &f" + a[0] + "&7："
                        + (cmd == null || cmd.isBlank() ? "&c未配置（" + a[1] + "-command）" : "&f/" + cmd));
            }
            out.add("&7  - &4删除房间&7：会踢人、卸载、删目录、撤销区域注册");
            return out;
        } else {
            var tpls = RoomManager.templates().stream().toList();
            out.add("&b[模板] &f" + (gameKey == null ? "全部" : gameKey)
                    + " &7共 &f" + tpls.size() + " &7个（点一个开始建房间）");
            for (WorldTemplate t : tpls) {
                out.add("&7- &f" + t.key()
                        + " &7目录=&f" + t.folder()
                        + " &7体积=&f" + (t.bytes() / 1024 / 1024) + "MB"
                        + (t.dir().isDirectory() ? "" : " &c(目录缺失)"));
            }
            if (tpls.isEmpty()) {
                out.add("&7（这个游戏没有登记模板，见 &fplugins/MiniGameLib/templates.yml&7）");
            }
        }
        return out;
    }

    // ---------- GUI ----------

    /** 给玩家打开某一页。 */
    public static void open(Player p, String gameKey, Mode mode) {
        open(p, gameKey, mode, null);
    }

    /** 给玩家打开某一页（ROOM_ACTIONS 需要 roomId）。 */
    public static void open(Player p, String gameKey, Mode mode, String roomId) {
        Holder holder = mode == Mode.ROOM_ACTIONS
                ? new Holder(gameKey, mode, roomId)
                : new Holder(gameKey, mode);
        String title = switch (mode) {
            case ROOMS -> "&8房间 · " + (gameKey == null ? "全部" : gameKey);
            case MAPS -> "&8选地图 · " + (gameKey == null ? "全部" : gameKey);
            case TEMPLATES -> "&8选模板（兜底）· " + (gameKey == null ? "全部" : gameKey);
            case ROOM_ACTIONS -> "&8房间 · " + roomId;
        };
        Inventory inv = Bukkit.createInventory(holder, 27, LEGACY.deserialize(title));
        holder.setInventory(inv);

        if (mode == Mode.MAPS) {
            // **选地图**：一张地图可能散在多个世界（派对 3 个），所以这里列的是"地图"而不是"模板世界"
            java.util.List<String> maps = gameKey == null
                    ? java.util.List.of() : com.hub.room.GameMaps.mapNames(gameKey);
            int slot = 0;
            for (String mapName : maps) {
                if (slot >= TEMPLATE_SLOTS * 2) {
                    break;
                }
                inv.setItem(slot++, mapItem(gameKey, mapName));
            }
            inv.setItem(26, actionItem(Material.ARROW, "&7返回房间列表", "&8回到上一页"));
            if (maps.isEmpty()) {
                inv.setItem(13, actionItem(Material.BARRIER, "&c这个游戏还没登记地图",
                        "&7见 plugins/MiniGameLib/maps.yml"));
            }
        } else if (mode == Mode.ROOMS) {
            inv.setItem(0, createButton());
            int slot = 1;
            for (var r : RoomManager.rooms()) {
                if (gameKey != null && !gameKey.equals(r.game())) {
                    continue;
                }
                if (slot > ROOM_SLOTS) {
                    break;
                }
                inv.setItem(slot++, roomItem(r));
            }
        } else if (mode == Mode.ROOM_ACTIONS) {
            if (!p.hasPermission("hub.admin")) {
                // **这一页只给管理员**：玩家看到"开始/停止/删除"完全没有意义（D21 里开局权在主持人手上）
                open(p, gameKey, Mode.ROOMS);
                return;
            }
            var room = RoomManager.room(roomId);
            inv.setItem(0, actionItem(Material.LIME_WOOL, "&a&l进入并加入", "&7传送到房间世界并加入等待队列"));
            boolean canManage = p.hasPermission("hub.admin");
            inv.setItem(2, actionItem(Material.REDSTONE_BLOCK, "&c&l开始一局",
                    canManage ? "&7执行该游戏的 start-command（/hub start 同款）" : "&c需要管理员权限"));
            inv.setItem(4, actionItem(Material.BARRIER, "&e&l停止",
                    canManage ? "&7执行该游戏的 stop-command" : "&c需要管理员权限"));
            inv.setItem(6, actionItem(Material.TNT, "&4&l删除房间",
                    canManage ? "&c会踢人、卸载、删目录、撤销区域注册" : "&c需要管理员权限"));
            inv.setItem(8, actionItem(Material.ARROW, "&7返回房间列表", "&8回到上一页"));
            if (room != null) {
                inv.setItem(13, roomItem(room));
            }
        } else {
            int slot = 0;
            for (WorldTemplate t : RoomManager.templates()) {
                if (gameKey != null && !GameMaps.mapNames(gameKey).isEmpty() && false) {
                    continue;
                }
                if (slot >= TEMPLATE_SLOTS * 2) {
                    break;
                }
                inv.setItem(slot++, templateItem(t));
            }
        }
        p.openInventory(inv);
    }

    /** 处理点击；返回 true 表示这个点击被我们吃掉了。 */
    public static boolean onClick(Player p, Holder holder, int slot) {
        return onClick(p, holder, slot, false);
    }

    /** @param rightClick 右键（管理员用它打开"管理"页） */
    public static boolean onClick(Player p, Holder holder, int slot, boolean rightClick) {
        if (holder.mode == Mode.MAPS) {
            if (slot == 26) {
                open(p, holder.gameKey, Mode.ROOMS);
                return true;
            }
            createFromMap(p, holder.gameKey, slot);
            return true;
        }
        if (holder.mode == Mode.ROOMS) {
            if (slot == 0) {
                // 有地图就**先选地图**（正规路径）；只有没登记地图的老游戏才回退到模板列表
                open(p, holder.gameKey, hasTemplates(holder.gameKey) ? Mode.MAPS : Mode.TEMPLATES);
                return true;
            }
            var rooms = RoomManager.rooms().stream()
                    .filter(r -> holder.gameKey == null || holder.gameKey.equals(r.game()))
                    .toList();
            int idx = slot - 1;
            if (idx >= 0 && idx < rooms.size()) {
                String rid = rooms.get(idx).roomId;
                if (rightClick && p.hasPermission("hub.admin")) {
                    // 右键 = 管理员的管理页（开始/停止/删除）。**普通玩家看不到这一页**
                    open(p, holder.gameKey, Mode.ROOM_ACTIONS, rid);
                } else {
                    // 左键 = 直接进房（玩家点房间就是为了玩，不该再点一层）
                    p.closeInventory();
                    joinRoom(p, rid);
                }
            }
            return true;
        }
        if (holder.mode == Mode.ROOM_ACTIONS) {
            switch (slot) {
                case 0 -> joinRoom(p, holder.roomId);
                case 2 -> {
                    if (!p.hasPermission("hub.admin")) {
                        p.sendMessage(LEGACY.deserialize("&c需要管理员权限。"));
                        return true;
                    }
                    dispatchGameCommand(p, holder.roomId, "start");
                }
                case 4 -> {
                    if (!p.hasPermission("hub.admin")) {
                        p.sendMessage(LEGACY.deserialize("&c需要管理员权限。"));
                        return true;
                    }
                    dispatchGameCommand(p, holder.roomId, "stop");
                }
                case 6 -> {
                    if (!p.hasPermission("hub.admin")) {
                        p.sendMessage(LEGACY.deserialize("&c需要管理员权限。"));
                        return true;
                    }
                    p.closeInventory();
                    boolean ok = RoomManager.delete(holder.roomId);
                    p.sendMessage(LEGACY.deserialize(ok
                            ? "&a已删除房间 &f" + holder.roomId
                            : "&c删除失败（房间不存在）"));
                }
                case 8 -> open(p, holder.gameKey, Mode.ROOMS);
                default -> { }
            }
            return true;
        }
        // 模板页：点一个就建房间
        var tpls = RoomManager.templates().stream()
                .filter(t -> true)
                .toList();
        if (slot >= 0 && slot < tpls.size()) {
            String tplKey = tpls.get(slot).key();
            p.closeInventory();
            p.sendMessage(LEGACY.deserialize("&7正在从模板 &f" + tplKey + " &7创建房间…"));
            RoomManager.createAsync(tplKey, null, room -> {
                if (room == null) {
                    p.sendMessage(LEGACY.deserialize("&c创建房间失败（模板不存在 / 目录缺失 / 达到上限）。"
                            + "详情见 /hub doctor。"));
                } else {
                    p.sendMessage(LEGACY.deserialize("&a已创建房间 &f" + room.roomId
                            + "&7，正在进入…"));
                    joinRoom(p, room.roomId);
                }
            });
        }
        return true;
    }

    /**
     * 按"第 slot 张地图"建房间 —— 与 `/hub create <game> <map>` **同一套解析**：
     * ① `GameMaps.get(game, map)`；② 子小游戏套壳成派对（D15）；③ `createForMap`。
     */
    private static void createFromMap(Player p, String gameKey, int slot) {
        if (gameKey == null) {
            return;
        }
        var names = com.hub.room.GameMaps.mapNames(gameKey);
        if (slot < 0 || slot >= names.size()) {
            return;
        }
        String mapName = names.get(slot);
        var map = com.hub.room.GameMaps.get(gameKey, mapName);
        if (map == null) {
            p.sendMessage(LEGACY.deserialize("&c地图 &f" + mapName + " &c不存在（maps.yml）。"));
            return;
        }
        // 子小游戏的房间拥有者永远是派对（D15）：单独开子游戏 = 打一场"只有一个游戏的派对"
        if (com.hub.room.PartyGames.isSubGame(map.game())) {
            map = com.hub.room.GameMaps.wrapAsParty(map);
        }
        p.closeInventory();
        p.sendMessage(LEGACY.deserialize("&7正在按地图 &f" + mapName + " &7创建房间…"));
        var room = RoomManager.createForMap(map, null);
        if (room == null) {
            p.sendMessage(LEGACY.deserialize("&c创建失败（达到上限 / 世界缺失）。详情见 /hub doctor。"));
            return;
        }
        p.sendMessage(LEGACY.deserialize("&a已创建房间 &f" + room.roomId
                + "&a（地图 &f" + mapName + "&a），正在进入…"));
        joinRoom(p, room.roomId);
    }

    /**
     * 进入房间：传送进房间世界 + **让游戏把人收进等待队列**（走 games.yml 的 join-command）。
     *
     * <p>只传送是不够的：玩家站在房间里但游戏不知道他来了，什么都不会发生。
     * 这一步才是 R3 与 N2 的接缝。
     */
    /** 进入房间 —— 与 `/hub join` **同一套实现**（{@link com.hub.RoomActions#join}）。 */
    private static void joinRoom(Player p, String roomId) {
        if (!com.hub.RoomActions.join(p, roomId)) {
            p.sendMessage(LEGACY.deserialize("&c房间 &f" + roomId + " &c不存在或未加载。"));
        }
    }

    /** 以控制台身份执行某房间对应游戏的 start/stop 命令（与 /hub start|stop 同源）。 */
    private static void dispatchGameCommand(Player p, String roomId, String phase) {
        var room = RoomManager.room(roomId);
        if (room == null) {
            p.sendMessage(LEGACY.deserialize("&c房间不存在。"));
            return;
        }
        String gk = room.game().isEmpty() ? room.template.key() : room.game();
        String key = "games." + gk + "." + phase + "-command";
        String cmd = games().getString(key);
        if (cmd == null || cmd.isBlank()) {
            p.sendMessage(LEGACY.deserialize("&c游戏 &f" + (room.game().isEmpty() ? "-" : room.game())
                    + " &c没有配置 " + phase + "-command（" + key + "）。"));
            return;
        }
        cmd = cmd.replace("{arena}", "").trim();
        p.closeInventory();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        p.sendMessage(LEGACY.deserialize("&a已执行 &f/" + cmd + " &7（房间 &f" + roomId + "&7）"));
    }

    private static ItemStack actionItem(Material mat, String name, String lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(noItalic(name));
        meta.lore(List.of(noItalic(lore)));
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack createButton() {
        ItemStack it = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(noItalic("&a&l创建新房间"));
        meta.lore(List.of(noItalic("&7先选一张地图，再按它复制出新房间"), noItalic("&8点一下开始")));
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack roomItem(RoomManager.Room r) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        // 标题用**地图名**（玩家关心"这是什么图"，不关心房间号）；房间号放 lore 末尾
        String map = r.map() == null || r.map().isBlank() ? "（未登记地图）" : r.map();
        meta.displayName(noItalic("&f" + map));
        List<Component> lore = new ArrayList<>();
        lore.add(noItalic("&7状态：&f" + stateText(r)));
        lore.add(noItalic("&7人数：&f" + r.playerCount()));
        lore.add(noItalic("&7地图：&f" + map));
        lore.add(noItalic("&7房间：&8" + r.roomId));
        lore.add(noItalic(r.idleSeconds() > 0 ? "&7空置：&f" + r.idleSeconds() + "s" : "&a有人在场"));
        lore.add(noItalic("&8左键进入"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** 房间状态的中文（读该房间的游戏上报状态；没上报 = 等待中）。 */
    private static String stateText(RoomManager.Room r) {
        try {
            if (plugin instanceof com.hub.HubCorePlugin hub && hub.getManager() != null) {
                var infos = hub.getManager().arenasInRoom(r.game(), r.roomId);
                for (var info : infos.values()) {
                    if (info == null || info.state() == null) {
                        continue;
                    }
                    return switch (info.state()) {
                        case WAITING -> "&a准备中";
                        case PLAYING -> "&c游戏进行中";
                    };
                }
            }
        } catch (RuntimeException ignored) {
            // 读不到就当"准备中"：UI 不该因为查询失败而崩（R3：详情在 doctor）
        }
        return "&a准备中";
    }

    /** 地图条目：一张地图 → 建一个房间。 */
    private static ItemStack mapItem(String gameKey, String mapName) {
        ItemStack it = new ItemStack(Material.FILLED_MAP);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(noItalic("&b" + mapName));
        List<Component> lore = new ArrayList<>();
        var map = com.hub.room.GameMaps.get(gameKey, mapName);
        if (map != null) {
            List<String> tpls = new ArrayList<>();
            for (var w : map.worlds()) {
                tpls.add(w.templateKey());
            }
            lore.add(noItalic("&7世界：&f" + String.join("、", tpls) + " &7（" + tpls.size() + " 个）"));
            int regions = 0;
            for (var w : map.worlds()) {
                if (w.regions() != null) {
                    regions += w.regions().size();
                }
            }
            lore.add(noItalic("&7区域：&f" + regions + " 块"));
            if (map.entry() != null) {
                lore.add(noItalic("&7入口：&f" + (int) map.entry().x() + ", "
                        + (int) map.entry().y() + ", " + (int) map.entry().z()));
            }
        }
        lore.add(noItalic("&8点击用这张图创建房间"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack templateItem(WorldTemplate t) {
        ItemStack it = new ItemStack(Material.FILLED_MAP);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(noItalic("&b" + t.key()));
        List<Component> lore = new ArrayList<>();
        lore.add(noItalic("&7目录：&f" + t.folder()));
        lore.add(noItalic("&7体积：&f" + (t.bytes() / 1024 / 1024) + "MB"));
        lore.add(noItalic(t.dir().isDirectory() ? "&a目录正常" : "&c目录缺失"));
        lore.add(noItalic("&8点击用这个模板建房间"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static Component noItalic(String legacy) {
        return LEGACY.deserialize(legacy).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }
}
