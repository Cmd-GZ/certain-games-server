package com.luckypillar;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 幸运之柱主插件：状态机 / 场地 / 监听 / 命令的装配入口。 */
public class LuckyPillarPlugin extends JavaPlugin {

    private LuckyPillarManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig(); // 首次启动铺一份线上 config.yml（源码里的只是模板）
        manager = new LuckyPillarManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        LuckyPillarCommand cmd = new LuckyPillarCommand(this);
        PluginCommand command = getCommand("luckypillar");
        if (command != null) {
            command.setExecutor(cmd);
            command.setTabCompleter(cmd);
        } else {
            getLogger().warning("plugin.yml 里没有 luckypillar 命令，/lp 不可用。");
        }
        getServer().getPluginManager().registerEvents(new LuckyPillarListener(this), this);

        // 登记到 HubCore（插件扫描一般已登记，这里只是兜底，重复登记是幂等的）
        HubAPI.registerGame(getName(), LuckyPillarManager.GAME, "CRYING_OBSIDIAN", "5");
        // 玩家执行 /hub 或右键回主城道具时，HubCore 回调这里把玩家踢出游戏（防递归）；
        // silent=true：不在场中时不报"你不在任何场中"
        HubAPI.registerLobbyHandler(p -> manager.leave(p, true));

        getLogger().info("LuckyPillar 已启用（/lp，别名 /lpz /lucky）。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public LuckyPillarManager getManager() {
        return manager;
    }
}
