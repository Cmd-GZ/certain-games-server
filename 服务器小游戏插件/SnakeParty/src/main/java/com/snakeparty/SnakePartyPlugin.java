package com.snakeparty;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 蛇行派对主插件。 */
public final class SnakePartyPlugin extends JavaPlugin {

    private SnakePartyManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new SnakePartyManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        SnakePartyCommand command = new SnakePartyCommand(this, manager);
        PluginCommand snake = getCommand("snake");
        if (snake != null) {
            snake.setExecutor(command);
            snake.setTabCompleter(command);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("蛇行派对（SnakeParty）已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public SnakePartyManager getManager() {
        return manager;
    }
}
