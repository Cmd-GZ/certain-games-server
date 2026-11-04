package com.grouprace;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 群友竞速赛主插件。 */
public class GroupRacePlugin extends JavaPlugin {

    /** D22：按房间分桶的运行时状态（每房间一份 manager 实例）。 */
    private GroupRaceRooms rooms;
    /** 默认那一份（没有房间上下文时的老路径）。 */
    private GroupRaceManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rooms = new GroupRaceRooms(this);
        manager = rooms.defaultManager();
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        GroupRaceCommand command = new GroupRaceCommand(this, rooms);
        PluginCommand race = getCommand("race");
        if (race != null) {
            race.setExecutor(command);
            race.setTabCompleter(command);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        HubAPI.registerLobbyHandler(player -> rooms.leave(player, true));

        getLogger().info("GroupRace 已启用。");
    }

    @Override
    public void onDisable() {
        if (rooms != null) {
            rooms.shutdownAll();
        }
    }

    public GroupRaceManager getManager() {
        return manager;
    }

    /** D22：房间路由表。 */
    public GroupRaceRooms rooms() {
        return rooms;
    }
}
