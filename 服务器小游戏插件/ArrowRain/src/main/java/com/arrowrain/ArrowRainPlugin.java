package com.arrowrain;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 万箭齐发主插件。 */
public final class ArrowRainPlugin extends JavaPlugin {

    /** D22：按房间分桶的运行时状态（每房间一份 manager 实例）。 */
    private ArrowRainRooms rooms;
    /** 默认那一份（没有房间上下文时的老路径）。 */
    private ArrowRainManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rooms = new ArrowRainRooms(this);
        manager = rooms.defaultManager();
        manager.load();

        ArrowRainCommand command = new ArrowRainCommand(this, rooms);
        PluginCommand arrowrain = getCommand("arrowrain");
        if (arrowrain != null) {
            arrowrain.setExecutor(command);
            arrowrain.setTabCompleter(command);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        // D22：按玩家记录路由到他自己那一份（两房间同时跑时不能落到别人的实例上）
        HubAPI.registerLobbyHandler(player -> rooms.leave(player, true));

        getLogger().info("万箭齐发（ArrowRain）已启用。");
    }

    @Override
    public void onDisable() {
        if (rooms != null) {
            rooms.shutdownAll();
        }
    }

    public ArrowRainManager manager() {
        return manager;
    }

    /** D22：房间路由表。 */
    public ArrowRainRooms rooms() {
        return rooms;
    }
}
