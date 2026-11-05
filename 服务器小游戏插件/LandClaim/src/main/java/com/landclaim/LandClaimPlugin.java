package com.landclaim;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class LandClaimPlugin extends JavaPlugin {

    /** D22：按房间分桶的运行时状态（每房间一份 manager 实例）。 */
    private LandClaimRooms rooms;
    /** 默认那一份（没有房间上下文时的老路径）。 */
    private LandClaimManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rooms = new LandClaimRooms(this);
        manager = rooms.defaultManager();
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        LandClaimCommand command = new LandClaimCommand(this, rooms);
        PluginCommand land = getCommand("land");
        if (land != null) {
            land.setExecutor(command);
            land.setTabCompleter(command);
        }
        // 回主城/进服复原：按玩家记录路由到他自己那一份（两房间同时跑时不能落到别人的实例上）
        HubAPI.registerLobbyHandler(player -> rooms.leave(player, true));   // D22

        getLogger().info("LandClaim（划地为王）已启用。");
    }

    @Override
    public void onDisable() {
        if (rooms != null) {
            rooms.shutdownAll();
        }
    }

    public LandClaimManager manager() {
        return manager;
    }

    /** D22：房间路由表。 */
    public LandClaimRooms rooms() {
        return rooms;
    }
}
