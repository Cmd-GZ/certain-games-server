package com.hub.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 活动会话登记处（供 {@code /hub doctor} 与冒烟台查询）。
 *
 * <p>只记账，不参与生命周期决策 —— 真正的状态在各自的 {@link GameSession} 里。
 * 已结束的会话会在 {@link #purge()} 时清掉，避免长期运行后无限增长。
 */
public final class Sessions {

    private static final List<GameSession> ALL = new CopyOnWriteArrayList<>();

    private Sessions() {
    }

    static void track(GameSession session) {
        if (session != null) {
            ALL.add(session);
        }
    }

    /** 还没结束的会话。 */
    /**
     * 还没结束、**并且确实有人**的会话。
     *
     * <p>为什么要求"有人"：会话对象在构造时就自动登记（这样忘不了），
     * 但"为了尝试加入而临时建出来、结果被 I1 拒绝"的空会话也会被登记进来。
     * 那种会话没有玩家、也不会有人再进来，若照样列进 doctor，就会显示成
     * `某场地 [WAITING] 0 人`，看起来像"场地被占了"，其实谁也没进去。
     *
     * <p>空会话本身不销毁（销毁要跑收场逻辑，反而危险），只是**不再当作活动曝光**。
     */
    public static List<GameSession> active() {
        List<GameSession> out = new ArrayList<>();
        for (GameSession s : ALL) {
            if (s.isActive() && !s.players().isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 全部（含已结束）。 */
    public static List<GameSession> all() {
        return new ArrayList<>(ALL);
    }

    public static int activeCount() {
        return active().size();
    }

    /** 清掉已结束的记录（框架在每次 end 之后或定时调）。 */
    public static void purge() {
        ALL.removeIf(s -> !s.isActive());
    }

    /** 清空（关服/重载用）。 */
    public static void clear() {
        ALL.clear();
    }

    /** 按 gameKey 统计活动会话数。 */
    public static Map<String, Integer> activeByGame() {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (GameSession s : active()) {
            out.merge(s.gameKey(), 1, Integer::sum);
        }
        return out;
    }
}
