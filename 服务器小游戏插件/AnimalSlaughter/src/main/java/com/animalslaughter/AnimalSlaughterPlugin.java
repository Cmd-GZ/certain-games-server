package com.animalslaughter;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 动物屠宰主插件。 */
public final class AnimalSlaughterPlugin extends JavaPlugin {

    private AnimalSlaughterManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new AnimalSlaughterManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        AnimalSlaughterCommand command = new AnimalSlaughterCommand(this, manager);
        PluginCommand animal = getCommand("animal");
        if (animal != null) {
            animal.setExecutor(command);
            animal.setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(new AnimalSlaughterListener(manager), this);
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("动物屠宰（AnimalSlaughter）已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public AnimalSlaughterManager getManager() {
        return manager;
    }
}
