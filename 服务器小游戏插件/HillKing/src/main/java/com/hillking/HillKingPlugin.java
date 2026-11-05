package com.hillking;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 高台之王主插件。 */
public class HillKingPlugin extends JavaPlugin {

    /** D22：按房间分桶的运行时状态（每房间一份 manager 实例）。 */
    private HillKingRooms rooms;
    /** 默认那一份（没有房间上下文时的老路径）。 */
    private HillKingManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rooms = new HillKingRooms(this);
        manager = rooms.defaultManager();
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）
        manager.logFlow();   // 打印等待流程，配置写错时一眼看得出来

        HillKingCommand command = new HillKingCommand(this, rooms);
        PluginCommand hill = getCommand("hill");
        if (hill != null) {
            hill.setExecutor(command);
            hill.setTabCompleter(command);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        // /hub 或右键回主城道具时，HubCore 回调这里把玩家移出对局
        // 回主城/进服复原：按玩家记录路由到他自己那一份（两房间同时跑时不能落到别人的实例上）
        HubAPI.registerLobbyHandler(player -> rooms.leave(player, true));

        getLogger().info("高台之王（HillKing）已启用。");
    }

    @Override
    public void onDisable() {
        com.hub.region.RegionRegistry.unregisterGame("hillking");   // 撤销地盘声明（I2）
        if (rooms != null) {
            rooms.shutdownAll();
        }
    }

    public HillKingManager getManager() {
        return manager;
    }

    /** D22：房间路由表。 */
    public HillKingRooms rooms() {
        return rooms;
    }
}
