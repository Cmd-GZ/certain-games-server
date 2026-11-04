package com.knockback;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 击退大战主插件。 */
public class KnockbackPlugin extends JavaPlugin {

    /** D22：按房间分桶的运行时状态（每房间一份 manager 实例）。 */
    private KnockbackRooms rooms;
    /** 默认那一份（没有房间上下文时的老路径）。 */
    private KnockbackManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rooms = new KnockbackRooms(this);
        manager = rooms.defaultManager();
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        KnockbackCommand command = new KnockbackCommand(this, rooms);
        PluginCommand kb = getCommand("knockback");
        if (kb != null) {
            kb.setExecutor(command);
            kb.setTabCompleter(command);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        // 玩家执行 /hub 或右键回主城道具时，HubCore 回调这里把玩家移出对局
        HubAPI.registerLobbyHandler(player -> rooms.leave(player, true));

        getLogger().info("KnockbackBattle 已启用。");
    }

    @Override
    public void onDisable() {
        if (rooms != null) {
            rooms.shutdownAll();
        }
    }

    public KnockbackManager getManager() {
        return manager;
    }

    /** D22：房间路由表。 */
    public KnockbackRooms rooms() {
        return rooms;
    }
}
