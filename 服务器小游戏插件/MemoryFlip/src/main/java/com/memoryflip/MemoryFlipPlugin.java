package com.memoryflip;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 记忆翻牌主插件。 */
public final class MemoryFlipPlugin extends JavaPlugin {

    private MemoryFlipManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new MemoryFlipManager(this);
        manager.load();

        MemoryFlipCommand command = new MemoryFlipCommand(this, manager);
        PluginCommand memory = getCommand("memory");
        if (memory != null) {
            memory.setExecutor(command);
            memory.setTabCompleter(command);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("记忆翻牌（MemoryFlip）已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public MemoryFlipManager getManager() {
        return manager;
    }
}
