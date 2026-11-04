package com.arcade;

import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * **一场派对**的全部状态（D22 多派对，第 1 阶段）。
 *
 * <p>为什么要有这个类：以前这些字段直接摊在 {@link ArcadePartyManager} 上 ——
 * 于是"一个 manager = 一场派对"，同一地图开第二个房间时两份状态会互相覆盖。
 *
 * <p>第 1 阶段只做**状态搬家**：manager 持有 {@code RoomScoped<Party>}，但当前仍只有一个
 * {@code Party} 实例，所以**单派对行为完全不变**（这是本阶段的验收标准）。
 * 第 2 阶段才会让 {@code party()} 按房间取。
 */
final class Party {

    /** 派对阶段（WAITING/TRANSITION/ROUND/PODIUM）。 */
    ArcadePartyManager.State state = ArcadePartyManager.State.WAITING;

    final Set<UUID> waiting = new LinkedHashSet<>();
    final Set<UUID> playing = new LinkedHashSet<>();
    final Map<UUID, Integer> stars = new HashMap<>();

    final List<String> selected = new ArrayList<>();
    int roundIndex;
    int transitionLeft;
    String currentGameKey = "";
    /** 过渡阶段选好的场地名（arena-aware 游戏），开局时优先复用。 */
    String currentArena;

    final Set<UUID> roundParticipants = new LinkedHashSet<>();
    final Set<UUID> transitionParticipants = new LinkedHashSet<>();
    final Set<UUID> transitionSpectators = new LinkedHashSet<>();
    boolean roundResolved;

    /** 收场时给玩家看的文案（end(reason) 不带文案，所以暂存在这）。 */
    String endMessage;
    /** 本场是否走过领奖台（冒烟台断言用：PARTY_OF_ONE 永远是 false）。 */
    boolean podiumShown;

    BukkitTask transitionTask;
    BukkitTask timeoutTask;
    BukkitTask podiumTask;
    BukkitTask endTask;
    BukkitTask guardTask;
    int guardTicks;
    int guardIdleTicks;
    final List<BukkitTask> fireworkTasks = new ArrayList<>();

    final Map<UUID, String> sidebarEntries = new HashMap<>();
    /** 颁奖时逐个揭晓的名次行，全部揭晓后一起发到聊天栏。 */
    final List<String> podiumLines = new ArrayList<>();
    /** 颁奖台上还没被揭晓的玩家：在揭晓前锁住移动。 */
    final Set<UUID> podiumFrozen = new LinkedHashSet<>();

    /** 第二名的站位（颁奖用）；属于这一场派对的场地状态。 */
    org.bukkit.Location secondSpot;
}
