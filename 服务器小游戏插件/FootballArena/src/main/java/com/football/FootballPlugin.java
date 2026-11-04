package com.football;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 绿茵足球主插件。 */
public class FootballPlugin extends JavaPlugin {

    private FootballManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new FootballManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        FootballCommand cmd = new FootballCommand(this);
        PluginCommand fb = getCommand("football");
        if (fb != null) {
            fb.setExecutor(cmd);
            fb.setTabCompleter(cmd);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        // 玩家 /hub 或右键回主城道具时，HubCore 回调这里把玩家踢出游戏（silent=true 防刷屏）
        com.hub.api.HubAPI.registerLobbyHandler(p -> manager.leave(p, true));

        getLogger().info("FootballArena 已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.cleanupBalls(); // 清掉展示实体球（球不落盘，这里兜底防残留）
            manager.saveAll();
        }
    }

    public FootballManager getManager() {
        return manager;
    }
}
