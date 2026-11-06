package com.hub.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * 公共消息工具：所有小游戏插件共用一份实现（原来 6 个插件各抄一份）。
 * <p>
 * 颜色代码统一用 {@code &}；{@code §} 也会被接受（自动换算）。
 * 需要给自己消息加前缀的插件（如建筑猜猜乐），保留一个很薄的本地包装类调用这里。
 */
public final class Chat {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Chat() {
    }

    /** 把 & 或 § 颜色代码转成 Component。 */
    public static Component color(String text) {
        return LEGACY.deserialize(text == null ? "" : text.replace('§', '&'));
    }

    /** 物品名/提示统一用正体：Minecraft 默认把物品显示名渲染成斜体。 */
    public static Component noItalic(Component component) {
        return component == null ? null : component.decoration(TextDecoration.ITALIC, false);
    }

    /** 发消息给玩家/控制台。 */
    public static void send(CommandSender target, String msg) {
        if (target == null) {
            // **控制台驱动**（派对/脚本/定时任务）时也要留痕：否则失败完全静默（R3 失败可见）。
            // 真实教训：`<game> start` 从控制台触发时各种"人数不足/场地未就绪"只发给 null 收件人，
            // 结果排了半天才发现游戏压根没开局（2026-11-05）。
            Bukkit.getLogger().info("[控制台消息] " + strip(msg));
            return;
        }
        target.sendMessage(color(msg));
    }

    /** 广播给全服。 */
    public static void broadcast(String msg) {
        Bukkit.broadcast(color(msg));
    }

    /** 发消息给玩家，并给消息里出现的在线玩家名加上头衔（没装头衔插件时与 send 一致）。 */
    public static void sendTitled(Player player, String msg) {
        if (player != null) {
            player.sendMessage(Titles.decorate(color(msg)));
        } else {
            // 同上：没有收件人时打到控制台，避免"静默失败"（R3）
            Bukkit.getLogger().info("[控制台消息] " + strip(msg));
        }
    }

    /**
     * **把带 `&` 颜色码的字符串转成 Component**（2026-11-07 修：菜单里直接显示 `&e` 的 bug）。
     *
     * <p>坑在哪：`LegacyComponentSerializer.legacySection()` 认的是 `§`，不认 `&`；
     * 直接把 `"&e小游戏菜单"` 丢进去，玩家看到的就是字面的 `&e`。
     * 全仓库统一走这个方法，别再用 `com.hub.util.Chat.legacy("&…")`。
     */
    public static net.kyori.adventure.text.Component legacy(String msg) {
        String colored = org.bukkit.ChatColor.translateAlternateColorCodes('&', msg == null ? "" : msg);
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().deserialize(colored);
    }

    /** 去掉颜色/格式代码，便于写进控制台日志。 */
    private static String strip(String msg) {
        if (msg == null) {
            return "";
        }
        return org.bukkit.ChatColor.stripColor(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
    }

    /** 广播给全服，并给消息里出现的在线玩家名加上头衔（没装头衔插件时与 broadcast 一致）。 */
    public static void broadcastTitled(String msg) {
        Bukkit.broadcast(Titles.decorate(color(msg)));
    }

    /** 屏幕中央大字幕（默认停留 3 秒）。 */
    public static void title(Player player, String title, String subtitle) {
        title(player, title, subtitle, 3);
    }

    /** 屏幕中央大字幕，staySeconds 为停留秒数。 */
    public static void title(Player player, String title, String subtitle, int staySeconds) {
        if (player == null) {
            return;
        }
        player.showTitle(Title.title(
                color(title),
                color(subtitle),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(staySeconds), Duration.ofMillis(500))));
    }

    /** 物品栏上方的动作栏提示。 */
    public static void actionBar(Player player, String msg) {
        if (player != null) {
            player.sendActionBar(color(msg));
        }
    }

    /** Component 转回 § 代码文本（用于告示牌行）。 */
    public static String toLegacy(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component);
    }
}
