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
 * <p>迁移已经完成：manager 里**不再有这些字段**，所有引用都走"当前房间那一份"（见
 * {@code MemoryFlipManager#game()}）。
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

    /** 本房间的等待倒计时（D22：每房间一个实例，否则两个房间的倒计时会互相取消）。 */
    com.hub.util.WaitCountdown countdown;
    /** 本房间的阶段（WAITING / RUNNING）。 */
    MemoryFlipManager.State state = MemoryFlipManager.State.WAITING;
    /** 本房间的对局会话。 */
    MemoryFlipSession session;
    /** endGame 传下来的收场文案（每房间一份，避免两个房间互相覆盖）。 */
    String pendingEndMessage;

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
