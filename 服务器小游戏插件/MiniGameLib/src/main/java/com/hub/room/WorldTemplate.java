package com.hub.room;

import java.io.File;
import java.util.List;

/**
 * 一个「模板世界」：设计者编辑好的世界目录，**只读**，永远不会被 createWorld 加载。
 *
 * <p>房间（{@link RoomManager.Room}）是它的一份实例拷贝，玩家真正玩的是房间。
 * 设计见 {@code docs/房间引擎设计.md}。
 *
 * @param key     模板的配置键（templates.yml 里的键）
 * @param gameKey 属于哪个游戏
 * @param folder  模板目录名（必须位于 {@code world/dimensions/minecraft/} 下）
 * @param dir     目录的绝对 File
 * @param rules   gamerule 声明（pvp / difficulty / mob-spawning / keep-inventory …）
 * @param regions 模板内坐标的区域声明（实例化时只替换世界名）
 */
public record WorldTemplate(
        String key,
        String gameKey,
        String folder,
        File dir,
        Rules rules,
        List<Box> regions
) {

    /** 世界规则声明。 */
    public record Rules(boolean pvp, String difficulty, boolean mobSpawning,
                        boolean keepInventory, long fixedTime) {
        public static Rules defaults() {
            return new Rules(false, "NORMAL", false, false, 6000L);
        }
    }

    /** 模板内的一块区域（坐标与世界名无关，实例化时换成房间世界名）。 */
    public record Box(String arenaName, double x1, double y1, double z1,
                      double x2, double y2, double z2) {
    }

    /** 体积估算，用于 doctor 与预算提示。 */
    public long bytes() {
        return dirSize(dir);
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) {
            return 0;
        }
        if (f.isFile()) {
            return f.length();
        }
        File[] kids = f.listFiles();
        if (kids == null) {
            return 0;
        }
        long sum = 0;
        for (File k : kids) {
            sum += dirSize(k);
        }
        return sum;
    }

    public String describe() {
        return key + " (" + gameKey + ") " + folder + " " + (bytes() / 1024 / 1024) + "MB";
    }
}
