package com.hub.event;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.plugin.Plugin;

/**
 * **音符录制通道** —— 只给"OP 建场/录红石音乐"这类工具用，**不是对局裁决**。
 *
 * <p>为什么放在框架里：录制需要监听 `NotePlayEvent`，而它和"这一局归谁"完全无关
 * （录制时可能一个人都不在局内）。如果让游戏自己长期挂着这个监听器，
 * 就会在 I3 的"游戏模块零 `@EventHandler`"上留下一个说不清的例外
 * （真实例子：`TNTBattleMusic` 就是这样一个 497 行的类，其中只有录制那 1/5 需要监听）。
 *
 * <p>用法（录制开始/结束时调用，**只有录制期间才有监听器**）：
 * <pre>
 * NoteRecorder.start(plugin, event -&gt; { … });   // 开始录制
 * NoteRecorder.stop();                          // 结束录制
 * </pre>
 *
 * <p>同一时刻只允许一个录制者（录制本来就是单人工序），重复 {@link #start} 会替换旧的并告警。
 */
public final class NoteRecorder {

    /** 收到一个音符盒被弹响。 */
    public interface Sink {
        void onNote(NotePlayEvent event);
    }

    private static Plugin owner;
    private static Sink sink;
    private static final Bridge BRIDGE = new Bridge();

    private NoteRecorder() {
    }

    /** 开始录制：注册监听器（重复调用会替换）。 */
    public static void start(Plugin plugin, Sink handler) {
        if (plugin == null || handler == null) {
            return;
        }
        if (sink != null) {
            Bukkit.getLogger().warning("[音符录制] 已有录制者在工作，替换为新的（"
                    + plugin.getName() + "）");
            stop();
        }
        owner = plugin;
        sink = handler;
        Bukkit.getPluginManager().registerEvents(BRIDGE, plugin);
        Bukkit.getLogger().info("[音符录制] 开始录制（" + plugin.getName() + "）");
    }

    /** 结束录制：注销监听器（幂等）。 */
    public static void stop() {
        if (sink == null) {
            return;
        }
        sink = null;
        owner = null;
        HandlerList.unregisterAll(BRIDGE);
        Bukkit.getLogger().info("[音符录制] 录制结束，监听器已注销");
    }

    /** 是否正在录制（给调用方做状态判断用）。 */
    public static boolean recording() {
        return sink != null;
    }

    private static final class Bridge implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onNote(NotePlayEvent event) {
            Sink s = sink;
            if (s == null) {
                return;
            }
            try {
                s.onNote(event);
            } catch (RuntimeException ex) {
                com.hub.diagnostics.Failures.recordAndWarn("NoteRecorder.录制", ex);
            }
        }
    }
}
