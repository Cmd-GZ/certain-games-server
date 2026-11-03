package com.anvilbattle;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 铁砧掘战主插件。 */
public final class AnvilBattlePlugin extends JavaPlugin {

    private AnvilBattleManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new AnvilBattleManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        AnvilBattleCommand command = new AnvilBattleCommand(this, manager);
        PluginCommand anvil = getCommand("anvil");
        if (anvil != null) {
            anvil.setExecutor(command);
            anvil.setTabCompleter(command);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("铁砧掘战（AnvilBattle）已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public AnvilBattleManager getManager() {
        return manager;
    }
}
