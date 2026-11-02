package com.devtools;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 开发期一次性工具。
 *
 * <p>为什么存在：相扑场地被上游画在主城世界里（I2 上不该如此）。过渡方案是把它
 * **原样复制**到一个独立模板世界，之后走正常的"模板 → 房间"流程。
 *
 * <p>**这个插件永不发布**：目录里放了 {@code .build-skip}，`tools/build.sh` 会跳过它。
 * 只在本地开服时手动 build + 拷进 plugins 用一次。
 */
public final class DevToolsPlugin extends JavaPlugin {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("rcopy")) {
            return false;
        }
        if (args.length < 8) {
            sender.sendMessage("用法：/rcopy <源世界> <x1> <y1> <z1> <x2> <y2> <z2> <目标世界> [dx dy dz]");
            return true;
        }
        try {
            World src = Bukkit.getWorld(args[0]);
            World dst = Bukkit.getWorld(args[7]);
            if (src == null || dst == null) {
                sender.sendMessage("世界不存在：" + (src == null ? args[0] : args[7]));
                return true;
            }
            int x1 = Math.min(Integer.parseInt(args[1]), Integer.parseInt(args[4]));
            int y1 = Math.min(Integer.parseInt(args[2]), Integer.parseInt(args[5]));
            int z1 = Math.min(Integer.parseInt(args[3]), Integer.parseInt(args[6]));
            int x2 = Math.max(Integer.parseInt(args[1]), Integer.parseInt(args[4]));
            int y2 = Math.max(Integer.parseInt(args[2]), Integer.parseInt(args[5]));
            int z2 = Math.max(Integer.parseInt(args[3]), Integer.parseInt(args[6]));
            int dx = args.length > 8 ? Integer.parseInt(args[8]) : x1;
            int dy = args.length > 9 ? Integer.parseInt(args[9]) : y1;
            int dz = args.length > 10 ? Integer.parseInt(args[10]) : z1;

            int n = 0;
            for (int x = x1; x <= x2; x++) {
                for (int y = y1; y <= y2; y++) {
                    for (int z = z1; z <= z2; z++) {
                        Block from = src.getBlockAt(x, y, z);
                        BlockData data = from.getBlockData().clone();
                        Location to = new Location(dst, x - x1 + dx, y - y1 + dy, z - z1 + dz);
                        to.getBlock().setBlockData(data, false);
                        n++;
                    }
                }
            }
            sender.sendMessage("已复制 " + n + " 个方块：" + src.getName() + " → " + dst.getName()
                    + "，目标原点 (" + dx + "," + dy + "," + dz + ")");
            getLogger().info("rcopy " + src.getName() + " -> " + dst.getName() + " 方块数=" + n
                    + " 目标原点=(" + dx + "," + dy + "," + dz + ")");
        } catch (NumberFormatException ex) {
            sender.sendMessage("坐标必须是整数。");
        }
        return true;
    }
}
