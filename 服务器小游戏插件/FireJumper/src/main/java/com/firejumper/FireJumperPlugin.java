package com.firejumper;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 跃火者主插件。 */
public final class FireJumperPlugin extends JavaPlugin {

    private FireJumperManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new FireJumperManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        FireJumperCommand command = new FireJumperCommand(this, manager);
        PluginCommand firejumper = getCommand("firejumper");
        if (firejumper != null) {
            firejumper.setExecutor(command);
            firejumper.setTabCompleter(command);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        // /hub 或右键回主城道具时，HubCore 回调这里把玩家移出对局
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("跃火者（FireJumper）已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public FireJumperManager manager() {
        return manager;
    }
}
