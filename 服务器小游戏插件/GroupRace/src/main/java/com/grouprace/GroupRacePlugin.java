package com.grouprace;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 群友竞速赛主插件。 */
public class GroupRacePlugin extends JavaPlugin {

    private GroupRaceManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new GroupRaceManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        GroupRaceCommand command = new GroupRaceCommand(this, manager);
        PluginCommand race = getCommand("race");
        if (race != null) {
            race.setExecutor(command);
            race.setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(new GroupRaceListener(manager), this);
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("GroupRace 已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public GroupRaceManager getManager() {
        return manager;
    }
}
