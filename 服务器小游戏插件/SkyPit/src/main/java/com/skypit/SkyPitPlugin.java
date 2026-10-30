package com.skypit;

import org.bukkit.plugin.java.JavaPlugin;

/** 天坑插件主类：配置战斗区域、自动装备、击杀统计、浮空文字前十名。 */
public class SkyPitPlugin extends JavaPlugin {

    private SkyPitManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new SkyPitManager(this);
        manager.load();

        SkyPitCommand cmd = new SkyPitCommand(this);
        org.bukkit.command.PluginCommand sp = getCommand("skypit");
        if (sp != null) {
            sp.setExecutor(cmd);
            sp.setTabCompleter(cmd);
        }
        getServer().getPluginManager().registerEvents(new SkyPitListener(this), this);
        // 玩家执行 /hub 或右键回主城道具时，主城插件回调这里归还天坑装备（防递归）
        com.hub.api.HubAPI.registerLobbyHandler(p -> manager.exitPit(p, false));

        getLogger().info("SkyPit 天坑插件已启用，/skypit help 查看帮助。");
    }

    @Override
    public void onDisable() {
        // 撤销地盘声明，避免 /reload 后留下幽灵区域（I2）
        com.hub.region.RegionRegistry.unregisterGame("skypit");
        if (manager != null) {
            manager.onDisable(); // 归还所有在场玩家背包
            manager.saveAll();
        }
    }

    public SkyPitManager getManager() {
        return manager;
    }
}
