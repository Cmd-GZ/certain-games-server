package com.snakeparty;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 蛇行派对主插件。 */
public final class SnakePartyPlugin extends JavaPlugin {

    /** D22：按房间分桶的运行时状态（每房间一份 manager 实例）。 */
    private SnakePartyRooms rooms;
    /** 默认那一份（没有房间上下文时的老路径）。 */
    private SnakePartyManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rooms = new SnakePartyRooms(this);
        manager = rooms.defaultManager();
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        SnakePartyCommand command = new SnakePartyCommand(this, rooms);
        PluginCommand snake = getCommand("snake");
        if (snake != null) {
            snake.setExecutor(command);
            snake.setTabCompleter(command);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        // 回主城/进服复原：按玩家记录路由到他自己那一份（两房间同时跑时不能落到别人的实例上）
        HubAPI.registerLobbyHandler(player -> rooms.leave(player, true));

        getLogger().info("蛇行派对（SnakeParty）已启用。");
    }

    @Override
    public void onDisable() {
        if (rooms != null) {
            rooms.shutdownAll();
        }
    }

    public SnakePartyManager getManager() {
        return manager;
    }

    /** D22：房间路由表。 */
    public SnakePartyRooms rooms() {
        return rooms;
    }
}
