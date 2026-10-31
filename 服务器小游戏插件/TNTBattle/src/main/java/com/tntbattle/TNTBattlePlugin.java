package com.tntbattle;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class TNTBattlePlugin extends JavaPlugin {

    private TNTBattleManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new TNTBattleManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        getServer().getPluginManager().registerEvents(new TNTBattleListener(manager), this);
        // 背景音乐录制：监听音符盒，把红石音乐录成音符序列
        getServer().getPluginManager().registerEvents(manager.music(), this);
        TNTBattleCommand command = new TNTBattleCommand(this, manager);
        PluginCommand tntbattle = getCommand("tntbattle");
        if (tntbattle != null) {
            tntbattle.setExecutor(command);
            tntbattle.setTabCompleter(command);
        }
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("TNTBattle（TNT 对战）已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public TNTBattleManager manager() {
        return manager;
    }
}
