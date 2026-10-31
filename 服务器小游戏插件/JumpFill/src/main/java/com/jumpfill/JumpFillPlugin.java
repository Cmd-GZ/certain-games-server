package com.jumpfill;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class JumpFillPlugin extends JavaPlugin {

    private JumpFillManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new JumpFillManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        getServer().getPluginManager().registerEvents(new JumpFillListener(this, manager), this);
        JumpFillCommand command = new JumpFillCommand(this, manager);
        PluginCommand jump = getCommand("jump");
        if (jump != null) {
            jump.setExecutor(command);
            jump.setTabCompleter(command);
        }
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("JumpFill（跳水填格）已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public JumpFillManager manager() {
        return manager;
    }
}
