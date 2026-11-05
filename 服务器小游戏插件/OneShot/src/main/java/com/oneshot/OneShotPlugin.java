package com.oneshot;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** One Shot 主插件。 */
public class OneShotPlugin extends JavaPlugin {

    /** D22：按房间分桶的运行时状态（每房间一份 manager 实例）。 */
    private OneShotRooms rooms;
    /** 默认那一份（没有房间上下文时的老路径）。 */
    private OneShotManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rooms = new OneShotRooms(this);
        manager = rooms.defaultManager();
        manager.load();

        OneShotCommand command = new OneShotCommand(this, rooms);
        PluginCommand oneshot = getCommand("oneshot");
        if (oneshot != null) {
            oneshot.setExecutor(command);
            oneshot.setTabCompleter(command);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        // /hub 或右键回主城道具时，HubCore 回调这里把玩家移出对局
        // D22：按玩家记录路由到他自己那一份（两房间同时跑时不能落到别人的实例上）
        HubAPI.registerLobbyHandler(player -> rooms.leave(player, true));

        getLogger().info("One Shot 已启用。");
    }

    @Override
    public void onDisable() {
        if (rooms != null) {
            rooms.shutdownAll();
        }
    }

    public OneShotManager getManager() {
        return manager;
    }

    /** D22：房间路由表。 */
    public OneShotRooms rooms() {
        return rooms;
    }
}
