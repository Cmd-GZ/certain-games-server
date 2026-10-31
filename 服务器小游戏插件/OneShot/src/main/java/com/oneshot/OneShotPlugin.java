package com.oneshot;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** One Shot 主插件。 */
public class OneShotPlugin extends JavaPlugin {

    private OneShotManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new OneShotManager(this);
        manager.load();

        OneShotCommand command = new OneShotCommand(this, manager);
        PluginCommand oneshot = getCommand("oneshot");
        if (oneshot != null) {
            oneshot.setExecutor(command);
            oneshot.setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(new OneShotListener(this, manager), this);
        // /hub 或右键回主城道具时，HubCore 回调这里把玩家移出对局
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("One Shot 已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public OneShotManager getManager() {
        return manager;
    }
}
