package com.skypit;

import org.bukkit.plugin.java.JavaPlugin;

/** 天坑插件主类：配置战斗区域、自动装备、击杀统计、浮空文字前十名。 */
public class SkyPitPlugin extends JavaPlugin {

    /** 单例：SkyPit 是"一个常驻场地"的形态（D26），会话里要摸 manager 时从这里取。 */
    private static SkyPitPlugin instance;

    public static SkyPitPlugin get() {
        return instance;
    }

    private SkyPitManager manager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        manager = new SkyPitManager(this);
        manager.load();

        SkyPitCommand cmd = new SkyPitCommand(this);
        org.bukkit.command.PluginCommand sp = getCommand("skypit");
        if (sp != null) {
            sp.setExecutor(cmd);
            sp.setTabCompleter(cmd);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        // I3：**区域进出钩子**（框架每 tick 算一次"这个玩家落在哪块区域里"）。
        // SkyPit 是 D26 的特殊形态：进区域即入局、离开即离场，会话是"走进来"才建的，
        // 所以这条 bootstrap 不能依赖会话（见 docs/框架不变量.md 的 I3 例外表）。
        com.hub.region.RegionHooks.register("skypit", new com.hub.region.RegionHooks.Listener() {
            @Override
            public void onRegionEnter(org.bukkit.entity.Player player,
                                      com.hub.region.ArenaRegion region) {
                if (!getManager().inPit(player.getUniqueId())) {
                    getManager().enterPit(player);
                }
            }

            @Override
            public void onRegionLeave(org.bukkit.entity.Player player,
                                      com.hub.region.ArenaRegion region) {
                if (getManager().inPit(player.getUniqueId())) {
                    getManager().exitPit(player, false);
                }
            }
        });
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
