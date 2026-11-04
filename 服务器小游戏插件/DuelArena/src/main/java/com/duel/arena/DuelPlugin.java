package com.duel.arena;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/** 单挑 PvP 主插件类。 */
public class DuelPlugin extends JavaPlugin {

    private DuelManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new DuelManager(this);
        manager.load();
        manager.publishRegions();   // 声明地盘（I2）

        DuelCommand cmd = new DuelCommand(this);
        org.bukkit.command.PluginCommand duel = getCommand("duel");
        if (duel != null) {
            duel.setExecutor(cmd);
            duel.setTabCompleter(cmd);
        }
        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子
        // 世界加载完成后刷新所有加入告示牌内容
        org.bukkit.Bukkit.getScheduler().runTaskLater(this, manager::refreshJoinSigns, 20L);
        // 玩家执行 /hub 或右键回主城道具时，HubCore 回调这里把玩家踢出游戏（防递归）
        com.hub.api.HubAPI.registerLobbyHandler(p -> manager.leaveArena(p.getUniqueId()));

        getLogger().info("DuelArena 已启用，/duel help 查看帮助。");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            // 战斗中玩家恢复
            for (String name : manager.getArenas().keySet()) {
                DuelArena a = manager.getArena(name);
                if (a != null) {
                    manager.restoreFightBlocks(name); // 还原对战中玩家放置的方块
                    for (UUID id : new UUID[]{a.getFighter1(), a.getFighter2()}) {
                        if (id == null) {
                            continue;
                        }
                        org.bukkit.entity.Player p = Bukkit.getPlayer(id);
                        if (p != null && p.isOnline()) {
                            p.getInventory().clear();
                            p.setGameMode(GameMode.SURVIVAL);
                            p.setHealth(20);
                            p.setFoodLevel(20);
                            p.setInvulnerable(false);
                        }
                    }
                    a.setFighter1(null);
                    a.setFighter2(null);
                    a.setState(DuelArena.State.WAITING);
                }
            }
            manager.saveAll();
        }
    }

    public DuelManager getManager() {
        return manager;
    }
}
