package com.arcade;

import com.hub.api.HubAPI;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 街机派对主插件。 */
public final class ArcadePartyPlugin extends JavaPlugin {

    private ArcadePartyManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new ArcadePartyManager(this);
        manager.load();

        // 登记"子小游戏"范围（唯一事实源就是本插件的 games: 配置）。
        // 框架据此判断：某个游戏被单独打开时要不要**套壳成派对**（PARTY_OF_ONE）。
        var gamesSec = getConfig().getConfigurationSection("games");
        com.hub.room.PartyGames.register(gamesSec == null ? java.util.List.of() : gamesSec.getKeys(false));
        getLogger().info("子小游戏范围已登记：" + com.hub.room.PartyGames.size() + " 个"
                + "（" + String.join(", ", com.hub.room.PartyGames.all()) + "）");

        ArcadeMenu menu = new ArcadeMenu(this, manager);
        ArcadePartyCommand command = new ArcadePartyCommand(this, manager, menu);
        PluginCommand arcade = getCommand("arcade");
        if (arcade != null) {
            arcade.setExecutor(command);
            arcade.setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(new ArcadePartyListener(manager), this);
        // 只有派对“等待中”时，玩家回主城才退出队列。
        // 对局中/过渡/颁奖阶段，小游戏结算会调用 HubAPI.teleportToLobby 把玩家送回主城，
        // 那只是回大厅的传送，不能当成“玩家主动离开”，否则一局结束人就被移光了。
        HubAPI.registerLobbyHandler(player -> {
            if (manager.state() == ArcadePartyManager.State.WAITING) {
                manager.leave(player, true);
            }
        });

        getLogger().info("街机派对（ArcadeParty）已启用。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public ArcadePartyManager getManager() {
        return manager;
    }
}
