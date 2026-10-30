package com.digdown;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 一掘到底主插件。 */
public class DigToBottomPlugin extends JavaPlugin {

    private DigToBottomManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new DigToBottomManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        DigToBottomCommand command = new DigToBottomCommand(this);
        PluginCommand dig = getCommand("dig");
        if (dig != null) {
            dig.setExecutor(command);
            dig.setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(new DigToBottomListener(this), this);
        // /hub 或回主城道具：静默把玩家踢出本游戏（不在场中时不刷提示）
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("一掘到底（DigToBottom）已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public DigToBottomManager getManager() {
        return manager;
    }
}
