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
 * @param folder  模板目录名（必须位于 {@code world/dimensions/minecraft/} 下）
 * @param dir     目录的绝对 File
 */
public record WorldTemplate(
        String key,
        String folder,
        File dir
) {


    /** 世界规则声明。 */
    public record Rules(boolean pvp, String difficulty, boolean mobSpawning,
                        boolean keepInventory, long fixedTime) {
        public static Rules defaults() {
            return new Rules(false, "NORMAL", false, false, 6000L);
        }
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
        return key + " " + folder + " " + (bytes() / 1024 / 1024) + "MB";
    }
}
