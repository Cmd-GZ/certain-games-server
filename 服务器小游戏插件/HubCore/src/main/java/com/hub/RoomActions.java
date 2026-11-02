package com.hub;

import com.hub.room.RoomManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;

/**
 * 房间的两个"高层动作"：**加入** 与 **创建**。
 *
 * <p>存在的理由（D24）：`/hub join`（命令）、房间 GUI 的"进入并加入"、动作页的按钮，
 * 做的是**同一件事**。如果各写一份，迟早出现"命令能用、GUI 不行"这类只有玩家才发现的不一致。
 * 所以收敛到这里 —— **一条实现，多个入口**。
 */
public final class RoomActions {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static Plugin plugin;

    public static void init(Plugin pl) {
        plugin = pl;
    }

    private RoomActions() {
    }

    /** 读 plugins/HubCore/games.yml（join/start/stop 命令映射）。 */
    private static FileConfiguration games() {
        if (plugin == null) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "games.yml"));
    }

    /**
     * 加入房间：传送到房间世界 + 让游戏把人收进队列。
     *
     * @return true = 已加入；false = 房间不存在 / 未加载（调用方负责提示）
     */
    public static boolean join(Player p, String roomId) {
        RoomManager.Room room = RoomManager.room(roomId);
        if (room == null || room.world == null) {
            // R3：失败要可见 —— 以前这里静默 return false，只有发起的玩家看到提示，
            // 控制台完全没线索（排查"为什么某人没进房间"时抓瞎）。
            if (plugin != null) {
                plugin.getLogger().warning("[/hub join] 失败：" + p.getName() + " -> " + roomId
                        + "（房间" + (room == null ? "不存在" : "存在但世界未加载") + "）");
            }
            return false;
        }
        p.closeInventory();
        // 传送到**房间的入口点**（不是世界默认出生点）：多世界房间只有一个入口，
        // 由地图数据决定；游戏侧 init 还可以再按玩家调整具体出生点。
        org.bukkit.Location entry = room.entry() != null ? room.entry() : room.world.getSpawnLocation();
        if (entry != null) {
            p.teleport(entry);
        }
        String gameKey = room.game() != null && !room.game().isBlank()
                ? room.game() : room.template.key();
        if (plugin != null) {
            plugin.getLogger().info("[/hub join] " + p.getName() + " -> " + roomId + "（" + gameKey + "）");
        }
        String join = games().getString("games." + gameKey + ".join-command");
        if (join != null && !join.isBlank()) {
            // {arena} 用房间记住的场地名（一个模板世界多张地图时，这决定进哪块）
            join = join.replace("{arena}", room.arena() == null ? "" : room.arena()).trim();
            // **告诉游戏它在哪个房间**：派对/子游戏据此取场地与委派，不用自己去搜
            //（activeFor 在多房间时返回 null，靠搜是搜不到的）。
            com.hub.room.RoomContext.bind(gameKey, room);
            if (plugin != null) {
                plugin.getLogger().info("[/hub join] " + p.getName() + " 执行 " + join
                        + "（房间上下文已绑定 " + gameKey + " -> " + room.roomId + "）");
            }
            p.performCommand(join);
            p.sendMessage(LEGACY.deserialize("&a已进入房间 &f" + roomId
                    + " &7并加入 &f/" + join + "&7（模板 &f" + room.template.key() + "&7）"));
        } else {
            p.sendMessage(LEGACY.deserialize("&a已进入房间 &f" + roomId
                    + "&7（游戏 &f" + gameKey + "&7 没配 join-command）"));
        }
        return true;
    }
}
