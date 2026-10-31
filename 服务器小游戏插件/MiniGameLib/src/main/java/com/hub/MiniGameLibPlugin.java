package com.hub;

import com.hub.data.PlayerStats;
import com.hub.region.RegionRegistry;
import com.hub.util.SetupMode;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MiniGameLib —— 小游戏公共库。
 * <p>
 * 本身不注册命令、不碰游戏逻辑，只向小游戏插件提供公共能力：
 * <ul>
 *   <li>{@link com.hub.api.HubAPI}：与主城插件（HubCore）对接的静态 API 与数据契约；</li>
 *   <li>{@link com.hub.region.RegionRegistry}：区域（地盘）声明 + 加载期冲突检测（I2）；</li>
 *   <li>{@link com.hub.diagnostics.Failures}：被吞异常登记处，供 {@code /hub doctor} 查询（R3）；</li>
 *   <li>{@link com.hub.util.Chat}：颜色/标题/动作栏消息工具；</li>
 *   <li>{@link com.hub.util.SetupMode}：OP 建场模式（备份背包 + 带标记的配置工具）；</li>
 *   <li>{@link com.hub.util.WaitCountdown}：等待室自动开局倒计时。</li>
 * </ul>
 * 加载顺序：本插件 ← HubCore ← 各小游戏插件（靠 plugin.yml 的 depend 保证）。
 */
public class MiniGameLibPlugin extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getLogger().info("MiniGameLib 已启用（公共库：HubAPI / RegionRegistry / Failures / Chat / SetupMode / WaitCountdown / PlayerStats）。");
        // 唯一的玩家记录文件（各小游戏共用一个）：启动日志就是“线上有没有带对文件”的证据
        getLogger().info("玩家记录 " + PlayerStats.file().getPath() + " -> " + PlayerStats.summary());

        // 区域声明汇总必须等到「所有插件都 onEnable 完」才打印，否则会漏掉后加载的游戏。
        // ServerLoadEvent 正好在那个时点触发（晚于全部插件 enable、早于玩家进服）。
        getServer().getPluginManager().registerEvents(this, this);
    }

    /**
     * 全部插件启用完毕：打印一次区域归属表 + 冲突检测结果。
     *
     * <p>用 MONITOR 优先级，确保不干扰任何其它插件。
     * 输出同时是冒烟台的断言目标（见 {@code tools/smoke.sh --expect '\\[区域\\]'}）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        // 再延后一个 tick：个别插件会在 onEnable 末尾用 runTask 补注册
        Bukkit.getScheduler().runTaskLater(this, RegionRegistry::printSummaryOnce, 1L);
    }

    @Override
    public void onDisable() {
        // 关服兜底：把还停在建场模式里的 OP 背包还原回去
        SetupMode.exitAllRegistered();
        RegionRegistry.clear();
        com.hub.session.Sessions.clear();
        com.hub.session.PlayerSessions.clear();
    }
}
