package com.colorparty;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 色盲派对（ColorParty）主插件。 */
public class ColorPartyPlugin extends JavaPlugin {

    private ColorPartyManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new ColorPartyManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        ColorPartyCommand cmd = new ColorPartyCommand(this);
        PluginCommand cp = getCommand("colorparty");
        if (cp != null) {
            cp.setExecutor(cmd);
            cp.setTabCompleter(cmd);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        // 玩家执行 /hub 或右键回主城道具时，HubCore 回调这里把玩家踢出游戏（防递归）；
        // silent=true：不在场中时别报"你不在任何场中"（进服复原会广播给所有游戏插件，否则一次刷 N 行）
        com.hub.api.HubAPI.registerLobbyHandler(p -> manager.leave(p, true));

        getLogger().info("色盲派对（ColorParty）已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
            manager.saveAll();
        }
    }

    public ColorPartyManager getManager() {
        return manager;
    }
}
