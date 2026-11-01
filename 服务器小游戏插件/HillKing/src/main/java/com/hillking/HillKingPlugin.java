package com.hillking;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 高台之王主插件。 */
public class HillKingPlugin extends JavaPlugin {

    private HillKingManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new HillKingManager(this);
        manager.load();
        manager.logFlow();   // 打印等待流程，配置写错时一眼看得出来

        HillKingCommand command = new HillKingCommand(this, manager);
        PluginCommand hill = getCommand("hill");
        if (hill != null) {
            hill.setExecutor(command);
            hill.setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(new HillKingListener(manager), this);
        // /hub 或右键回主城道具时，HubCore 回调这里把玩家移出对局
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("高台之王（HillKing）已启用。");
    }

    @Override
    public void onDisable() {
        com.hub.region.RegionRegistry.unregisterGame("hillking");   // 撤销地盘声明（I2）
        if (manager != null) {
            manager.shutdown();
        }
    }

    public HillKingManager getManager() {
        return manager;
    }
}
