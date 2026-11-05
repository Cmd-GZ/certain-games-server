package com.hub.selfcheck;

import com.hub.room.GameDelegation;
import com.hub.room.RoomContext;
import com.hub.room.RoomScoped;
import com.hub.session.SessionState;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 框架自检（**不需要启动服务器**）。
 *
 * <p>为什么不用 JUnit：这台机器上 Maven 是离线模式，`.server/m2repo` 里没有 JUnit 的 jar，
 * 也拉不下来。用 `main()` + 断言做个等价物，由 `tools/selfcheck.sh` 编译并运行，
 * 失败就非零退出，可以进 CI。
 *
 * <p>覆盖的是"纯逻辑、不碰 Bukkit 运行时"的部分：
 * <ul>
 *   <li><b>I5</b> 状态转移表（`SessionState.canTransitionTo`）——合法/非法各若干条</li>
 *   <li><b>D22</b> 房间上下文（压栈/出栈/嵌套/线程隔离）</li>
 *   <li><b>D22</b> `RoomScoped` 的分桶语义（同键同一份、不同键互不影响）</li>
 *   <li><b>D22</b> `GameDelegation` 的<b>按房间多槽</b>（两场派对各自委派互不覆盖）</li>
 * </ul>
 */
public final class SelfCheck {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private SelfCheck() {
    }

    public static void main(String[] args) {
        checkTransitionTable();
        checkRoomContext();
        checkRoomScoped();
        checkDelegationSlots();
        checkContract();

        System.out.println();
        System.out.println("自检结果：" + passed + " 条通过，" + failures.size() + " 条失败");
        for (String f : failures) {
            System.out.println("  ✘ " + f);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ---------- I5：状态转移表 ----------

    private static void checkTransitionTable() {
        // 合法的五级链路
        ok("CREATED→WAITING", SessionState.canTransitionTo(SessionState.CREATED, SessionState.WAITING));
        ok("WAITING→RUNNING", SessionState.canTransitionTo(SessionState.WAITING, SessionState.RUNNING));
        ok("RUNNING→ENDING", SessionState.canTransitionTo(SessionState.RUNNING, SessionState.ENDING));
        ok("ENDING→ENDED", SessionState.canTransitionTo(SessionState.ENDING, SessionState.ENDED));

        // 非法：跳级
        bad("CREATED→RUNNING", SessionState.canTransitionTo(SessionState.CREATED, SessionState.RUNNING));
        bad("WAITING→ENDING", SessionState.canTransitionTo(SessionState.WAITING, SessionState.ENDING));
        bad("RUNNING→WAITING", SessionState.canTransitionTo(SessionState.RUNNING, SessionState.WAITING));

        // 终态不可变
        for (SessionState to : SessionState.values()) {
            bad("ENDED→" + to, SessionState.canTransitionTo(SessionState.ENDED, to));
        }
        // null 安全
        bad("null→WAITING", SessionState.canTransitionTo(null, SessionState.WAITING));

        // isFinal / isClosed 的语义
        ok("ENDED.isFinal", SessionState.ENDED.isFinal());
        bad("RUNNING.isFinal", SessionState.RUNNING.isFinal());
        ok("ENDING.isClosed", SessionState.ENDING.isClosed());
        ok("ENDED.isClosed", SessionState.ENDED.isClosed());
        bad("WAITING.isClosed", SessionState.WAITING.isClosed());
    }

    // ---------- D22：房间上下文 ----------

    private static void checkRoomContext() {
        ok("初始无上下文", RoomContext.currentRoomId() == null);

        RoomContext.pushRoom("room_a");
        ok("push 后可读", "room_a".equals(RoomContext.currentRoomId()));

        RoomContext.pushRoom("room_b");
        ok("嵌套 push 取最内层", "room_b".equals(RoomContext.currentRoomId()));

        RoomContext.popRoom();
        ok("pop 一层回到外层", "room_a".equals(RoomContext.currentRoomId()));

        RoomContext.popRoom();
        ok("pop 完回到 null", RoomContext.currentRoomId() == null);

        // 多 pop 不该炸（防御性）
        try {
            RoomContext.popRoom();
            ok("多余 pop 不抛异常", true);
        } catch (RuntimeException ex) {
            ok("多余 pop 不抛异常", false);
        }

        // 线程隔离：别的线程不该看到主线程压的上下文
        RoomContext.pushRoom("room_main");
        final String[] other = new String[1];
        Thread t = new Thread(() -> other[0] = RoomContext.currentRoomId(), "selfcheck-thread");
        t.start();
        try {
            t.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        ok("线程隔离（子线程看不到主线程上下文）", other[0] == null);
        RoomContext.popRoom();
    }

    // ---------- D22：RoomScoped ----------

    private static void checkRoomScoped() {
        RoomScoped<StringBuilder> scoped = new RoomScoped<>("selfcheck");

        StringBuilder a1 = scoped.ofKey("room_a", k -> new StringBuilder());
        StringBuilder a2 = scoped.ofKey("room_a", k -> new StringBuilder());
        StringBuilder b1 = scoped.ofKey("room_b", k -> new StringBuilder());
        ok("同键返回同一份状态", a1 == a2);
        ok("不同键是不同状态", a1 != b1);
        ok("size 计两份", scoped.size() == 2);

        a1.append("x");
        ok("A 房间的写入不影响 B", b1.length() == 0);

        scoped.remove("room_a");
        ok("移除后 size=1", scoped.size() == 1);
        ok("移除后不再命中", scoped.peek("room_a") == null);

        // 键规则：**按玩家记录到的房间**优先，没有记录就用场地名。
        // 注意 keyOf(UUID, …) 看的是"这个玩家登记在哪个房间"（join 时由框架登记），
        // 不是线程上下文 —— 所以这里只能验兜底分支（未登记的玩家 → 场地名）。
        UUID id = UUID.randomUUID();
        ok("未登记房间的玩家 → 用场地名兜底", "地图A".equals(scoped.keyOf(id, "地图A")));
    }

    // ---------- D22：GameDelegation 按房间多槽 ----------

    private static void checkDelegationSlots() {
        Object worldA = world("world_a", "room_pd1_1");
        Object worldB = world("world_b", "room_pd2_1");

        // 两场派对各自在自己房间的上下文里委派
        RoomContext.pushRoom("room_pd1");
        GameDelegation.begin("game_x", (org.bukkit.World) worldA, "地图A", List.of(), null);
        RoomContext.popRoom();

        RoomContext.pushRoom("room_pd2");
        GameDelegation.begin("game_x", (org.bukkit.World) worldB, "地图A", List.of(), null);
        RoomContext.popRoom();

        // 各自读到自己那份（**不会互相覆盖**，这就是本次多槽改造的目的）
        RoomContext.pushRoom("room_pd1");
        ok("room_pd1 读到自己的世界", GameDelegation.worldFor("game_x") == worldA);
        RoomContext.popRoom();

        RoomContext.pushRoom("room_pd2");
        ok("room_pd2 读到自己的世界", GameDelegation.worldFor("game_x") == worldB);
        RoomContext.popRoom();

        // 没上下文的旧路径：退回"最后一次委派"
        ok("无上下文时退回最后一次委派", GameDelegation.worldFor("game_x") == worldB);

        // 撤销只影响本房间
        GameDelegation.end("game_x", "room_pd1");
        RoomContext.pushRoom("room_pd1");
        ok("撤销 room_pd1 后该房间不再有委派（退回兜底）", GameDelegation.worldFor("game_x") == worldB);
        RoomContext.popRoom();

        RoomContext.pushRoom("room_pd2");
        ok("room_pd2 的委派不受影响", GameDelegation.worldFor("game_x") == worldB);
        RoomContext.popRoom();

        GameDelegation.end("game_x", "room_pd2");
        GameDelegation.end();
    }

    /** 造一个不用真实现的 World 桩（只用来比较引用）。 */
    private static Object world(String name, String ignored) {
        return Proxy.newProxyInstance(
                SelfCheck.class.getClassLoader(),
                new Class<?>[]{org.bukkit.World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "toString" -> "World(" + name + ")";
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                    default -> null;
                });
    }

    // ---------- R4：契约版本 ----------

    private static void checkContract() {
        ok("版本一致（当前版本）", com.hub.api.Contract.matches(com.hub.api.Contract.VERSION));
        bad("版本不一致（旧版本）", com.hub.api.Contract.matches(com.hub.api.Contract.VERSION - 1));
        bad("未声明（-1）不算一致", com.hub.api.Contract.matches(-1));
        ok("describe(一致)", "一致".equals(com.hub.api.Contract.describe(com.hub.api.Contract.VERSION)));
        ok("describe(未声明)", com.hub.api.Contract.describe(-1).contains("未声明"));
        ok("describe(不一致) 带期望值",
                com.hub.api.Contract.describe(com.hub.api.Contract.VERSION + 7).contains("不一致"));
    }

    // ---------- 断言 ----------

    private static void ok(String what, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ✔ " + what);
        } else {
            failures.add(what);
            System.out.println("  ✘ " + what);
        }
    }

    private static void bad(String what, boolean conditionThatMustBeFalse) {
        ok(what + "（应当不允许）", !conditionThatMustBeFalse);
    }
}
