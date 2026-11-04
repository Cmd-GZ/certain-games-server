package com.memoryflip;

import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * **一局记忆翻牌的全部运行时状态**（D22 多房间）。
 *
 * <p>为什么要有这个类：以前这些字段直接摊在 {@link MemoryFlipManager} 上，
 * 于是"一个 manager = 一局"，同一游戏开第二个房间时两份状态会互相覆盖
 * （键是硬编码的 {@code "main"}）。
 *
 * <p>现在每房间一份 {@code MemoryGame}，由 {@link MemoryFlipManager} 用
 * {@code RoomScoped<MemoryGame>} 分桶；**配置**（场地坐标）仍按场地名查，两者不混。
 *
 * <p>迁移步骤（本轮只建类，不动 manager 的行为）：
 * <ol>
 *   <li>manager 里加 {@code RoomScoped<MemoryGame> games = new RoomScoped<>("memoryflip")}</li>
 *   <li>每个方法开头取 {@code MemoryGame g = game(...)}，把 {@code waiting} 等改成 {@code g.waiting}</li>
 *   <li>删掉 manager 上的同名字段</li>
 * </ol>
 */
final class MemoryGame {

    /** 房间键（房间 id；没有房间时就是场地名）。 */
    final String key;
    /** 场地名（取坐标用）。 */
    final String arenaName;

    final Set<UUID> waiting = new LinkedHashSet<>();
    final Set<UUID> playing = new LinkedHashSet<>();
    /** 每个玩家的翻牌进度。 */
    final Map<UUID, PlayState> states = new HashMap<>();
    /** 加入时的 tick 与同 tick 内的随机名次（D21：同 tick 的人随机决定主持人）。 */
    final Map<UUID, Long> joinTick = new HashMap<>();
    final Map<UUID, Long> joinSeed = new HashMap<>();

    BukkitTask gameTask;
    int secondsLeft;
    long startedAt;
    ItemStack questionItem;
    ItemStack checkItem;

    MemoryGame(String key, String arenaName) {
        this.key = key;
        this.arenaName = arenaName;
    }
}
