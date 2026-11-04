package com.buildrun;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 你建我跑（BuildRun）主插件。 */
public class BuildRunPlugin extends JavaPlugin {

    private BuildRunManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new BuildRunManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        BuildRunCommand cmd = new BuildRunCommand(this);
        PluginCommand br = getCommand("buildrun");
        if (br != null) {
            br.setExecutor(cmd);
            br.setTabCompleter(cmd);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        // 玩家执行 /hub 或右键回主城道具时，HubCore 回调这里把玩家踢出游戏（防递归）；
        // silent=true：不在场中时不报"你不在任何场中"
        com.hub.api.HubAPI.registerLobbyHandler(p -> manager.leave(p, true));

        getLogger().info("你建我跑（BuildRun）已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
            manager.saveAll();
        }
    }

    public BuildRunManager getManager() {
        return manager;
    }
}
