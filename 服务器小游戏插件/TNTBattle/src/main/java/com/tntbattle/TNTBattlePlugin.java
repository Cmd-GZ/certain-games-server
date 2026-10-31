package com.tntbattle;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class TNTBattlePlugin extends JavaPlugin {

    private TNTBattleCore core;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = new TNTBattleCore(this);
        core.load();
        core.publishRegions();   // 声明地盘（I2）

        TNTBattleCommand command = new TNTBattleCommand(this, core);
        PluginCommand tntbattle = getCommand("tntbattle");
        if (tntbattle != null) {
            tntbattle.setExecutor(command);
            tntbattle.setTabCompleter(command);
        }
        // 每场的监听器与音乐录制器由 Core 在加载场地时注册
        HubAPI.registerLobbyHandler(player -> core.leave(player));

        getLogger().info("TNTBattle（TNT 对战）已启用。");
    }

    @Override
    public void onDisable() {
        if (core != null) {
            core.shutdown();
        }
    }

    public TNTBattleCore core() {
        return core;
    }
}
