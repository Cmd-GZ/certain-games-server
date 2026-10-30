package com.hotpotato;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 烫手山芋主插件。 */
public class HotPotatoPlugin extends JavaPlugin {

    private HotPotatoManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new HotPotatoManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        HotPotatoCommand cmd = new HotPotatoCommand(this);
        PluginCommand hp = getCommand("hotpotato");
        if (hp != null) {
            hp.setExecutor(cmd);
            hp.setTabCompleter(cmd);
        }
        getServer().getPluginManager().registerEvents(new HotPotatoListener(this), this);
        // 玩家执行 /hub 或右键回主城道具时，HubCore 回调这里把玩家踢出游戏（防递归）；
        // silent=true：不在场中时别报"你不在任何场中"（进服/hub 复原会广播给所有游戏插件，否则一次刷三行）
        com.hub.api.HubAPI.registerLobbyHandler(p -> manager.leave(p, true));

        getLogger().info("HotPotato 已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.restoreAllWorldPvp(); // 对局中关服时把临时开启的 PvP 还原
            manager.saveAll();
        }
    }

    public HotPotatoManager getManager() {
        return manager;
    }
}
