package com.hub.gui;

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
        /** 房间列表。 */
        ROOMS,
        /** 模板列表（点一个建房间）。 */
        TEMPLATES
    }

    /** 打开中的界面。 */
    public static final class Holder implements InventoryHolder {
        public final String gameKey;
        public final Mode mode;
        private Inventory inventory;

        public Holder(String gameKey, Mode mode) {
            this.gameKey = gameKey;
            this.mode = mode;
        }

        public void setInventory(Inventory inv) {
            this.inventory = inv;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private RoomGui() {
    }

    // ---------- 文本版（控制台用 / 也是 GUI 的内容来源） ----------

    /**
     * 把某一页的内容描述成若干行（控制台可读、GUI 的 lore 与之一致）。
     */
    public static List<String> describe(String gameKey, Mode mode) {
        List<String> out = new ArrayList<>();
        if (mode == Mode.ROOMS) {
            var rooms = RoomManager.rooms().stream()
                    .filter(r -> gameKey == null || r.template.gameKey().equals(gameKey))
                    .toList();
            out.add("&b[房间] &f" + (gameKey == null ? "全部" : gameKey)
                    + " &7共 &f" + rooms.size() + " &7个"
                    + " &7（第一格永远是 &f创建新房间&7）");
            for (var r : rooms) {
                out.add("&7- &f" + r.roomId + " &7← 模板 &f" + r.template.key()
                        + " &7人=&f" + r.playerCount() + " &7空置=&f" + r.idleSeconds() + "s");
            }
            if (rooms.isEmpty()) {
                out.add("&7（还没有房间；点第一格从模板创建）");
            }
        } else {
            var tpls = RoomManager.templates().stream()
                    .filter(t -> gameKey == null || t.gameKey().equals(gameKey))
                    .toList();
            out.add("&b[模板] &f" + (gameKey == null ? "全部" : gameKey)
                    + " &7共 &f" + tpls.size() + " &7个（点一个开始建房间）");
            for (WorldTemplate t : tpls) {
                out.add("&7- &f" + t.key() + " &7游戏=&f" + t.gameKey()
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
        Holder holder = new Holder(gameKey, mode);
        String title = mode == Mode.ROOMS
                ? "&8房间 · " + (gameKey == null ? "全部" : gameKey)
                : "&8选地图 · " + (gameKey == null ? "全部" : gameKey);
        Inventory inv = Bukkit.createInventory(holder, 27, LEGACY.deserialize(title));
        holder.setInventory(inv);

        if (mode == Mode.ROOMS) {
            inv.setItem(0, createButton());
            int slot = 1;
            for (var r : RoomManager.rooms()) {
                if (gameKey != null && !r.template.gameKey().equals(gameKey)) {
                    continue;
                }
                if (slot > ROOM_SLOTS) {
                    break;
                }
                inv.setItem(slot++, roomItem(r));
            }
        } else {
            int slot = 0;
            for (WorldTemplate t : RoomManager.templates()) {
                if (gameKey != null && !t.gameKey().equals(gameKey)) {
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
        if (holder.mode == Mode.ROOMS) {
            if (slot == 0) {
                open(p, holder.gameKey, Mode.TEMPLATES);
                return true;
            }
            var rooms = RoomManager.rooms().stream()
                    .filter(r -> holder.gameKey == null || r.template.gameKey().equals(holder.gameKey))
                    .toList();
            int idx = slot - 1;
            if (idx >= 0 && idx < rooms.size()) {
                joinRoom(p, rooms.get(idx).roomId);
            }
            return true;
        }
        // 模板页：点一个就建房间
        var tpls = RoomManager.templates().stream()
                .filter(t -> holder.gameKey == null || t.gameKey().equals(holder.gameKey))
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

    /** 进入房间：先传送进房间世界，再让游戏自己把人收进等待队列（走 games.yml 的 join-command）。 */
    private static void joinRoom(Player p, String roomId) {
        var room = RoomManager.room(roomId);
        if (room == null || room.world == null) {
            p.sendMessage(LEGACY.deserialize("&c房间 &f" + roomId + " &c不存在或未加载。"));
            return;
        }
        p.closeInventory();
        p.teleport(room.world.getSpawnLocation());
        p.sendMessage(LEGACY.deserialize("&a已进入房间 &f" + roomId
                + " &7（模板 &f" + room.template.key() + "&7）"));
    }

    private static ItemStack createButton() {
        ItemStack it = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(noItalic("&a&l创建新房间"));
        meta.lore(List.of(noItalic("&7选一个模板，从模板复制一份新世界"), noItalic("&8点一下开始")));
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack roomItem(RoomManager.Room r) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(noItalic("&f" + r.roomId));
        meta.lore(List.of(
                noItalic("&7模板：&f" + r.template.key()),
                noItalic("&7人数：&f" + r.playerCount()),
                noItalic("&7空置：&f" + r.idleSeconds() + "s"),
                noItalic("&8点击进入")));
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack templateItem(WorldTemplate t) {
        ItemStack it = new ItemStack(Material.FILLED_MAP);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(noItalic("&b" + t.key()));
        List<Component> lore = new ArrayList<>();
        lore.add(noItalic("&7游戏：&f" + t.gameKey()));
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
