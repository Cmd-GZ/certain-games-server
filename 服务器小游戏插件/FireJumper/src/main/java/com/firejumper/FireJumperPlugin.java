package com.firejumper;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 跃火者主插件。 */
public final class FireJumperPlugin extends JavaPlugin {

    /** D22：按房间分桶的运行时状态（每房间一份 manager 实例）。 */
    private FireJumperRooms rooms;
    /** 默认那一份（没有房间上下文时的老路径）。 */
    private FireJumperManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rooms = new FireJumperRooms(this);
        manager = rooms.defaultManager();
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        FireJumperCommand command = new FireJumperCommand(this, rooms);
        PluginCommand firejumper = getCommand("firejumper");
        if (firejumper != null) {
            firejumper.setExecutor(command);
            firejumper.setTabCompleter(command);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        // /hub 或右键回主城道具时，HubCore 回调这里把玩家移出对局
        // 回主城/进服复原：按玩家记录路由到他自己那一份（两房间同时跑时不能落到别人的实例上）
        HubAPI.registerLobbyHandler(player -> rooms.leave(player, true));

        getLogger().info("跃火者（FireJumper）已启用。");
    }

    @Override
    public void onDisable() {
        if (rooms != null) {
            rooms.shutdownAll();
        }
    }

    public FireJumperManager manager() {
        return manager;
    }

    /** D22：房间路由表。 */
    public FireJumperRooms rooms() {
        return rooms;
    }
}
