package com.quark;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 粒子枪战主插件。 */
public class QuarkPlugin extends JavaPlugin {

    private QuarkManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new QuarkManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        QuarkCommand command = new QuarkCommand(this, manager);
        PluginCommand quark = getCommand("quark");
        if (quark != null) {
            quark.setExecutor(command);
            quark.setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(new QuarkListener(manager), this);
        // 玩家执行 /hub 或右键回主城道具时，HubCore 回调这里把玩家移出对局
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("Quark 已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public QuarkManager getManager() {
        return manager;
    }
}
