package com.tntrun;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 雪塔大作战（原 TNT Run，插件名/命令仍是 TntRun/tr）主插件。 */
public class TntRunPlugin extends JavaPlugin {

    private TntRunManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new TntRunManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        TntRunCommand cmd = new TntRunCommand(this);
        PluginCommand tr = getCommand("tntrun");
        if (tr != null) {
            tr.setExecutor(cmd);
            tr.setTabCompleter(cmd);
        }
        getServer().getPluginManager().registerEvents(new TntRunListener(this), this);
        // 玩家执行 /hub 或右键回主城道具时，HubCore 回调这里把玩家踢出游戏（防递归）；
        // silent=true：不在场中时别报"你不在任何场中"（进服/hub 复原会广播给所有游戏插件，否则一次刷三行）
        com.hub.api.HubAPI.registerLobbyHandler(p -> manager.leave(p, true));

        getLogger().info("雪塔大作战（TntRun）已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
            manager.saveAll();
        }
    }

    public TntRunManager getManager() {
        return manager;
    }
}
