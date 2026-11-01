package com.arrowrain;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 万箭齐发主插件。 */
public final class ArrowRainPlugin extends JavaPlugin {

    private ArrowRainManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new ArrowRainManager(this);
        manager.load();

        ArrowRainCommand command = new ArrowRainCommand(this, manager);
        PluginCommand arrowrain = getCommand("arrowrain");
        if (arrowrain != null) {
            arrowrain.setExecutor(command);
            arrowrain.setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(new ArrowRainListener(this, manager), this);
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("万箭齐发（ArrowRain）已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public ArrowRainManager manager() {
        return manager;
    }
}
