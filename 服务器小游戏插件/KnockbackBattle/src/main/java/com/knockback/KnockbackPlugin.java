package com.knockback;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 击退大战主插件。 */
public class KnockbackPlugin extends JavaPlugin {

    private KnockbackManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new KnockbackManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        KnockbackCommand command = new KnockbackCommand(this, manager);
        PluginCommand kb = getCommand("knockback");
        if (kb != null) {
            kb.setExecutor(command);
            kb.setTabCompleter(command);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        // 玩家执行 /hub 或右键回主城道具时，HubCore 回调这里把玩家移出对局
        HubAPI.registerLobbyHandler(player -> manager.leave(player, true));

        getLogger().info("KnockbackBattle 已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public KnockbackManager getManager() {
        return manager;
    }
}
