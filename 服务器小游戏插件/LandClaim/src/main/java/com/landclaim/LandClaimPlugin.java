package com.landclaim;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class LandClaimPlugin extends JavaPlugin {

    private LandClaimManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new LandClaimManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        LandClaimCommand command = new LandClaimCommand(this, manager);
        PluginCommand land = getCommand("land");
        if (land != null) {
            land.setExecutor(command);
            land.setTabCompleter(command);
        }
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("LandClaim（划地为王）已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public LandClaimManager manager() {
        return manager;
    }
}
