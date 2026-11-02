package com.devtools;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 开发期一次性工具（世界重排迁移用）。
 *
 * <p><b>这个插件永不发布</b>：目录里放了 {@code .build-skip}，{@code tools/build.sh} 会跳过它。
 * 只在本地开服时手动 build + 拷进 plugins 用。
 *
 * <h2>为什么 rcopy 必须分批</h2>
 * 最初版本在**单 tick 内逐方块**复制。几十万方块的盒子（谋杀之谜的四张图、TNT 的废墟）
 * 会卡服几十秒 → 超时踢人。现在改成**单一队列 + 单一 tick 任务**：
 * 每 tick 处理 {@value #BLOCKS_PER_TICK} 个方块，多个 {@code /rcopy} 串行执行
 * （并行开多个 taskTimer 反而互相抢主线程）。
 *
 * <h2>为什么要单独复制"方块实体"（2026-11-03 补）</h2>
 * {@code setBlockData} 只写"方块"（id + 朝向等属性），**不会**带上方块实体 NBT ——
 * 告示牌文字、头颅皮肤、箱子/潜影盒内容、旗帜花纹、唱片机唱片全都会丢
 * （实测：MM 四图 1582 处方块实体里，357 个头颅皮肤、132 块有字的告示牌、3 个有内容的容器全丢）。
 *
 * <p>实现走 Bukkit 的快照 API：{@code BlockState#copy(Location)} + {@code update(true, false)}。
 * 字节码确认（{@code javap -c org.bukkit.craftbukkit.block.CraftBlockEntityState}）：
 * {@code update} 会把快照的整份 NBT 通过 {@code applyTo} 写回目标方块实体，因此**不挑类型**
 * （告示牌/头颅/容器/旗帜/唱片机/熔炉/酿造台/篝火/蜜蜂巢…… 全部覆盖），不用逐类 switch。
 *
 * <h2>命令</h2>
 * <pre>
 * /rcopy &lt;源世界&gt; &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt; &lt;目标世界&gt; [dx dy dz] [no-air|no-tiles]
 *     盒子复制到目标世界；默认偏移 = (x1,y1,z1) 即**绝对坐标不变**。
 *     默认**跳过空气**（目标是空世界时结果一致，快得多）；no-air = 空气也写（用于覆盖清理）。
 *     默认**连方块实体一起复制**；no-tiles = 只复制方块（旧行为）。
 * /rcstat                     队列与当前进度
 * /rprobe &lt;世界&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;   探针：该点方块 + 脚下最近实体地面（验证"玩家站得住"）
 * /rscan &lt;世界&gt; &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt;
 *     世界内统计非空气方块（分批，与 rcopy 共用队列；结果打到控制台 SCAN-RESULT）
 * /rtiles &lt;世界&gt; &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt; [样本数]
 *     统计方块实体（按类型），并抽样打印告示牌文字 / 容器内容 / 头颅档案 / 旗帜花纹 / 唱片机唱片
 * /rworld load|unload &lt;世界名&gt;
 *     直接加载/卸载服务器目录里**已经存在**的世界目录（不经过 GameWorlds 登记，
 *     用于把备份世界临时挂上来当 rcopy 的源）；unload 一律 save=false（源只读）
 * </pre>
 */
public final class DevToolsPlugin extends JavaPlugin {

    /** 每 tick 处理的方块数。2 万 ≈ 数百微秒的 setBlockData，体感无卡顿。 */
    private static final int BLOCKS_PER_TICK = 20000;

    /** 每 tick 处理的方块实体上限（写回 NBT 比 setBlockData 贵，200 个约数毫秒）。 */
    private static final int TILES_PER_TICK = 200;

    /** 逐方块作业：{@link #step(int)} 返回**实际处理**的方块数；返回 0 表示做完。 */
    private interface Cursor {
        int step(int budget);

        /** 已"落地"的数量（复制 = 写入的方块数；扫描 = 非空气数）。 */
        long done();

        /** 顺手跳过的数量（复制 = 空气）。 */
        long skipped();

        /** 复制到的方块实体数（其它作业恒为 0）。 */
        default long tiles() {
            return 0L;
        }
    }

    /** 队列里的一个作业（统计由游标回填，方便打完一行汇总日志）。 */
    private static final class Job {
        final int id;
        final String desc;
        final Cursor cursor;
        final long started = System.currentTimeMillis();
        long lastLog = started;

        Job(int id, String desc, Cursor cursor) {
            this.id = id;
            this.desc = desc;
            this.cursor = cursor;
        }
    }

    private final Deque<Job> queue = new ArrayDeque<>();
    private BukkitTask task;
    private int seq;

    @Override
    public void onEnable() {
        getLogger().info("DevTools 已启用（rcopy 分批：每 tick " + BLOCKS_PER_TICK + " 方块 / "
                + TILES_PER_TICK + " 方块实体）");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ---------------------------------------------------------------- 队列

    private Job submit(String desc, Cursor cursor) {
        Job job = new Job(++seq, desc, cursor);
        queue.addLast(job);
        getLogger().info("[rcopy] 入队 job#" + job.id + " " + desc);
        if (task == null || task.isCancelled()) {
            task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        }
        return job;
    }

    private void tick() {
        Job job = queue.peekFirst();
        if (job == null) {
            if (task != null) {
                task.cancel();
                task = null;
            }
            getLogger().info("RCOPY-QUEUE-EMPTY");
            return;
        }
        int left = BLOCKS_PER_TICK;
        boolean finished = false;
        try {
            while (left > 0) {
                int used = job.cursor.step(left);
                if (used <= 0) {
                    finished = true;
                    break;
                }
                left -= used;
            }
        } catch (Throwable ex) {
            getLogger().severe("[rcopy] job#" + job.id + " 失败：" + ex);
            ex.printStackTrace();
            finished = true;
        }
        if (finished) {
            queue.pollFirst();
            long ms = System.currentTimeMillis() - job.started;
            getLogger().info("[rcopy] RCOPY-DONE job#" + job.id + " " + job.desc
                    + " 落地=" + job.cursor.done() + " 跳过=" + job.cursor.skipped()
                    + " 方块实体=" + job.cursor.tiles()
                    + " 用时=" + ms + "ms 剩余队列=" + queue.size());
        } else if (System.currentTimeMillis() - job.lastLog >= 5000L) {
            job.lastLog = System.currentTimeMillis();
            getLogger().info("[rcopy] 进度 job#" + job.id + " 落地=" + job.cursor.done()
                    + " 跳过=" + job.cursor.skipped() + " 方块实体=" + job.cursor.tiles()
                    + "（队列剩余 " + queue.size() + "）");
        }
    }

    /** 盒子复制：保持绝对坐标（偏移默认 = 盒子最小角），默认跳过空气、连方块实体一起复制。 */
    private static final class BoxCursor implements Cursor {

        /**
         * 一个方块实体的"工作量"折算成多少个方块。
         *
         * <p>只是为了复用同一个 tick 预算（{@value #BLOCKS_PER_TICK}）：写回一份 NBT 大致相当于
         * 上百次 {@code setBlockData}，所以按 100 折算 —— 结果就是每 tick 最多
         * {@value #TILES_PER_TICK} 个方块实体。
         */
        private static final int TILE_COST = 100;

        private final World src;
        private final World dst;
        private final int x1;
        private final int y1;
        private final int z1;
        private final int x2;
        private final int y2;
        private final int z2;
        private final int dx;
        private final int dy;
        private final int dz;
        private final boolean skipAir;
        private final boolean copyTiles;
        private final boolean silent;
        private int x;
        private int y;
        private int z;
        private boolean blockDone;
        private long copied;
        private long skipped;
        /** 相位：0 = 方块，1 = 方块实体，2 = 收尾。 */
        private int phase;
        private int cx;
        private int cz;
        private boolean tileDone;
        private long tiles;
        private long tileFailed;
        private final Map<String, Integer> tileKinds = new LinkedHashMap<>();
        private boolean statLogged;

        BoxCursor(World src, World dst, int x1, int y1, int z1, int x2, int y2, int z2,
                  int dx, int dy, int dz, boolean skipAir, boolean copyTiles, boolean silent) {
            this.src = src;
            this.dst = dst;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.x2 = x2;
            this.y2 = y2;
            this.z2 = z2;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.skipAir = skipAir;
            this.copyTiles = copyTiles;
            this.silent = silent;
            this.x = x1;
            this.y = y1;
            this.z = z1;
            this.cx = x1 >> 4;
            this.cz = z1 >> 4;
        }

        @Override
        public int step(int budget) {
            if (phase == 0) {
                int n = stepBlocks(budget);
                if (!blockDone) {
                    return n;
                }
                // 方块铺完了才能写方块实体：目标方块必须先存在，否则 BlockState 拿不到方块实体
                phase = copyTiles ? 1 : 2;
                return Math.max(n, 1);
            }
            if (phase == 1) {
                int maxTiles = Math.max(1, Math.min(budget / TILE_COST, TILES_PER_TICK));
                long before = tiles;
                stepTiles(maxTiles);
                if (tileDone) {
                    phase = 2;
                }
                return (int) Math.max(tiles - before, 1) * TILE_COST;
            }
            if (!statLogged) {
                statLogged = true;
                logStat();
            }
            return 0;
        }

        private int stepBlocks(int budget) {
            if (blockDone) {
                return 0;
            }
            int n = 0;
            while (n < budget) {
                Block from = src.getBlockAt(x, y, z);
                if (skipAir && from.isEmpty()) {
                    skipped++;
                } else {
                    BlockData data = from.getBlockData().clone();
                    dst.getBlockAt(x - x1 + dx, y - y1 + dy, z - z1 + dz)
                            .setBlockData(data, false);
                    copied++;
                }
                n++;
                // 游标推进：z 最快，然后 y，最后 x
                if (++z > z2) {
                    z = z1;
                    if (++y > y2) {
                        y = y1;
                        if (++x > x2) {
                            blockDone = true;
                            break;
                        }
                    }
                }
            }
            return n;
        }

        /**
         * 逐**区块**把源世界盒子内的方块实体搬过去。
         *
         * <p>为什么按区块枚举而不是逐方块 {@code getState()}：盒子动辄上百万方块，
         * 每块都造一个 BlockState 会慢一个数量级；{@code Chunk#getTileEntities} 只返回
         * 真正的方块实体（一堆地图里就几十到几百个）。
         *
         * <p>枚举前先 {@code getChunkAt} 强制加载源区块 —— 否则区块已经被卸载的话
         * 会**静默**少复制（这是最恶心的失败方式，宁可慢也不静默）。
         */
        private void stepTiles(int maxTiles) {
            int handled = 0;
            while (handled < maxTiles && !tileDone) {
                for (int[] pos : tilePositions(src, cx, cz)) {
                    int bx = pos[0];
                    int by = pos[1];
                    int bz = pos[2];
                    if (bx < x1 || bx > x2 || by < y1 || by > y2 || bz < z1 || bz > z2) {
                        continue;
                    }
                    // 用 getState() 再取一次：待提升（pending）的方块实体只有被访问过才带数据
                    BlockState st = src.getBlockAt(bx, by, bz).getState();
                    if (!(st instanceof TileState)) {
                        continue;
                    }
                    handled++;
                    copyOne(st, new Location(dst, bx - x1 + dx, by - y1 + dy, bz - z1 + dz));
                }
                if (++cz > (z2 >> 4)) {
                    cz = z1 >> 4;
                    if (++cx > (x2 >> 4)) {
                        tileDone = true;
                    }
                }
            }
        }

        private void copyOne(BlockState from, Location to) {
            String kind = from.getType().getKey().toString();
            try {
                BlockState copy = from.copy(to);
                if (copy != null && copy.update(true, false)) {
                    tiles++;
                    tileKinds.merge(kind, 1, Integer::sum);
                    return;
                }
                tileFailed++;
                if (tileFailed <= 5) {
                    Bukkit.getLogger().warning("[rcopy] 方块实体写回失败（update 返回 false）："
                            + kind + " " + from.getX() + "," + from.getY() + "," + from.getZ()
                            + " → " + to.getWorld().getName() + " "
                            + to.getBlockX() + "," + to.getBlockY() + "," + to.getBlockZ());
                }
            } catch (Throwable ex) {
                tileFailed++;
                if (tileFailed <= 5) {
                    Bukkit.getLogger().warning("[rcopy] 方块实体复制异常：" + kind + " "
                            + from.getX() + "," + from.getY() + "," + from.getZ() + " → "
                            + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            }
        }

        private void logStat() {
            if (silent) {
                return;
            }
            StringBuilder sb = new StringBuilder("RCOPY-STAT ").append(dst.getName())
                    .append(" 复制=").append(copied)
                    .append(" 跳过空气=").append(skipped)
                    .append(" 方块实体=").append(tiles);
            if (tileFailed > 0) {
                sb.append(" 方块实体失败=").append(tileFailed);
            }
            if (!tileKinds.isEmpty()) {
                sb.append(" [");
                int i = 0;
                for (Map.Entry<String, Integer> e : tileKinds.entrySet()) {
                    if (i++ > 0) {
                        sb.append(", ");
                    }
                    sb.append(e.getKey().replace("minecraft:", "")).append('=').append(e.getValue());
                }
                sb.append(']');
            }
            Bukkit.getLogger().info(sb.toString());
        }

        @Override
        public long done() {
            return copied;
        }

        @Override
        public long skipped() {
            return skipped;
        }

        @Override
        public long tiles() {
            return tiles;
        }
    }

    /** 世界内统计非空气方块（分批）。 */
    private static final class ScanCursor implements Cursor {
        private final World w;
        private final int x1;
        private final int y1;
        private final int z1;
        private final int x2;
        private final int y2;
        private final int z2;
        private final long volume;
        private int x;
        private int y;
        private int z;
        private long solid;
        private int ymin = Integer.MAX_VALUE;
        private int ymax = Integer.MIN_VALUE;
        private int tiles;
        private boolean finished;

        ScanCursor(World w, int x1, int y1, int z1, int x2, int y2, int z2) {
            this.w = w;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.x2 = x2;
            this.y2 = y2;
            this.z2 = z2;
            this.volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
            this.x = x1;
            this.y = y1;
            this.z = z1;
        }

        @Override
        public int step(int budget) {
            if (finished) {
                return 0;
            }
            int n = 0;
            while (n < budget) {
                Block b = w.getBlockAt(x, y, z);
                if (!b.isEmpty()) {
                    solid++;
                    if (y < ymin) {
                        ymin = y;
                    }
                    if (y > ymax) {
                        ymax = y;
                    }
                    if (b.getState() instanceof TileState) {
                        tiles++;
                    }
                }
                n++;
                if (++z > z2) {
                    z = z1;
                    if (++y > y2) {
                        y = y1;
                        if (++x > x2) {
                            finished = true;
                            break;
                        }
                    }
                }
            }
            if (finished) {
                Bukkit.getLogger().info("SCAN-RESULT " + w.getName() + " 非空气=" + solid
                        + " 体积=" + volume + " y=[" + ymin + ".." + ymax + "]"
                        + " 方块实体=" + tiles);
            }
            return n;
        }

        @Override
        public long done() {
            return solid;
        }

        @Override
        public long skipped() {
            return 0L;
        }
    }

    // ---------------------------------------------------------------- 命令

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();
        if (name.equals("rcopy")) {
            return rcopy(sender, args);
        }
        if (name.equals("rstat")) {
            Job cur = queue.peekFirst();
            sender.sendMessage(cur == null
                    ? "队列空"
                    : "队列 " + queue.size() + " 个；当前 job#" + cur.id + " " + cur.desc
                    + "（已落地 " + cur.cursor.done() + " 方块 / "
                    + cur.cursor.tiles() + " 方块实体）");
            return true;
        }
        if (name.equals("rprobe")) {
            return rprobe(sender, args);
        }
        if (name.equals("rscan")) {
            return rscan(sender, args);
        }
        if (name.equals("rtiles")) {
            return rtiles(sender, args);
        }
        if (name.equals("rworld")) {
            return rworld(sender, args);
        }
        return false;
    }

    private boolean rcopy(CommandSender sender, String[] args) {
        if (args.length < 8) {
            sender.sendMessage("用法：/rcopy <源世界> <x1> <y1> <z1> <x2> <y2> <z2> <目标世界>"
                    + " [dx dy dz] [no-air] [no-tiles]");
            return true;
        }
        try {
            World src = Bukkit.getWorld(args[0]);
            World dst = Bukkit.getWorld(args[7]);
            if (src == null || dst == null) {
                sender.sendMessage("世界不存在：" + (src == null ? args[0] : args[7])
                        + "（源世界要先 /rworld load 或用 /gw load 加载）");
                return true;
            }
            int x1 = Math.min(Integer.parseInt(args[1]), Integer.parseInt(args[4]));
            int y1 = Math.min(Integer.parseInt(args[2]), Integer.parseInt(args[5]));
            int z1 = Math.min(Integer.parseInt(args[3]), Integer.parseInt(args[6]));
            int x2 = Math.max(Integer.parseInt(args[1]), Integer.parseInt(args[4]));
            int y2 = Math.max(Integer.parseInt(args[2]), Integer.parseInt(args[5]));
            int z2 = Math.max(Integer.parseInt(args[3]), Integer.parseInt(args[6]));
            int dx = args.length > 8 && !args[8].equalsIgnoreCase("no-air")
                    && !args[8].equalsIgnoreCase("no-tiles")
                    ? Integer.parseInt(args[8]) : x1;
            int dy = args.length > 9 && !args[9].equalsIgnoreCase("no-air")
                    && !args[9].equalsIgnoreCase("no-tiles")
                    ? Integer.parseInt(args[9]) : y1;
            int dz = args.length > 10 && !args[10].equalsIgnoreCase("no-air")
                    && !args[10].equalsIgnoreCase("no-tiles")
                    ? Integer.parseInt(args[10]) : z1;
            boolean skipAir = true;
            boolean copyTiles = true;
            for (String a : args) {
                if (a.equalsIgnoreCase("no-air")) {
                    skipAir = false;
                }
                if (a.equalsIgnoreCase("no-tiles")) {
                    copyTiles = false;
                }
            }
            long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
            String desc = src.getName() + "→" + dst.getName()
                    + " 盒(" + x1 + "," + y1 + "," + z1 + ")-(" + x2 + "," + y2 + "," + z2 + ")"
                    + " 偏移=" + (dx - x1) + "," + (dy - y1) + "," + (dz - z1)
                    + " 体积=" + volume + (skipAir ? " 跳过空气" : " 含空气")
                    + (copyTiles ? " 方块实体" : " 不含方块实体");
            Job job = submit(desc, new BoxCursor(src, dst, x1, y1, z1, x2, y2, z2,
                    dx, dy, dz, skipAir, copyTiles, false));
            sender.sendMessage("已入队 #" + job.id + " " + desc
                    + "（预计 " + (volume / BLOCKS_PER_TICK + 1) + " tick）");
            return true;
        } catch (NumberFormatException ex) {
            sender.sendMessage("坐标必须是整数。");
            return true;
        }
    }

    private boolean rprobe(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("用法：/rprobe <世界> <x> <y> <z>");
            return true;
        }
        World w = Bukkit.getWorld(args[0]);
        if (w == null) {
            sender.sendMessage("世界不存在：" + args[0]);
            return true;
        }
        double px = Double.parseDouble(args[1]);
        double py = Double.parseDouble(args[2]);
        double pz = Double.parseDouble(args[3]);
        int bx = (int) Math.floor(px);
        int by = (int) Math.floor(py);
        int bz = (int) Math.floor(pz);
        Block at = w.getBlockAt(bx, by, bz);
        Block feet = w.getBlockAt(bx, by - 1, bz);
        int floorY = Integer.MIN_VALUE;
        for (int y = by - 1; y >= w.getMinHeight(); y--) {
            Block b = w.getBlockAt(bx, y, bz);
            if (!b.isEmpty() && !b.isLiquid()) {
                floorY = y;
                break;
            }
        }
        String line = "PROBE " + w.getName() + " " + px + "," + py + "," + pz
                + " | 该点=" + at.getType()
                + " 脚下=" + feet.getType()
                + " 最近实体地面=" + (floorY == Integer.MIN_VALUE ? "无" : "y=" + floorY)
                + " 落差=" + (floorY == Integer.MIN_VALUE ? "inf" : String.valueOf(by - 1 - floorY));
        sender.sendMessage(line);
        Bukkit.getLogger().info(line);
        return true;
    }

    private boolean rscan(CommandSender sender, String[] args) {
        if (args.length < 7) {
            sender.sendMessage("用法：/rscan <世界> <x1> <y1> <z1> <x2> <y2> <z2>");
            return true;
        }
        World w = Bukkit.getWorld(args[0]);
        if (w == null) {
            sender.sendMessage("世界不存在：" + args[0]);
            return true;
        }
        int x1 = Math.min(Integer.parseInt(args[1]), Integer.parseInt(args[4]));
        int y1 = Math.min(Integer.parseInt(args[2]), Integer.parseInt(args[5]));
        int z1 = Math.min(Integer.parseInt(args[3]), Integer.parseInt(args[6]));
        int x2 = Math.max(Integer.parseInt(args[1]), Integer.parseInt(args[4]));
        int y2 = Math.max(Integer.parseInt(args[2]), Integer.parseInt(args[5]));
        int z2 = Math.max(Integer.parseInt(args[3]), Integer.parseInt(args[6]));
        long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        Job job = submit("scan " + w.getName() + " 盒(" + x1 + "," + y1 + "," + z1 + ")-("
                + x2 + "," + y2 + "," + z2 + ") 体积=" + volume,
                new ScanCursor(w, x1, y1, z1, x2, y2, z2));
        sender.sendMessage("已入队 #" + job.id + " scan " + w.getName());
        return true;
    }

    /**
     * 方块实体体检：按类型计数 + 抽样打印"真内容"。
     *
     * <p>用途：验证 rcopy 到底有没有把告示牌文字/头颅皮肤/箱子内容带过去
     * （只看 .mca 里"有没有方块实体"是不够的 —— 空告示牌、空箱子也是方块实体）。
     */
    private boolean rtiles(CommandSender sender, String[] args) {
        if (args.length < 7) {
            sender.sendMessage("用法：/rtiles <世界> <x1> <y1> <z1> <x2> <y2> <z2> [样本数]");
            return true;
        }
        World w = Bukkit.getWorld(args[0]);
        if (w == null) {
            sender.sendMessage("世界不存在：" + args[0]);
            return true;
        }
        int x1 = Math.min(Integer.parseInt(args[1]), Integer.parseInt(args[4]));
        int y1 = Math.min(Integer.parseInt(args[2]), Integer.parseInt(args[5]));
        int z1 = Math.min(Integer.parseInt(args[3]), Integer.parseInt(args[6]));
        int x2 = Math.max(Integer.parseInt(args[1]), Integer.parseInt(args[4]));
        int y2 = Math.max(Integer.parseInt(args[2]), Integer.parseInt(args[5]));
        int z2 = Math.max(Integer.parseInt(args[3]), Integer.parseInt(args[6]));
        int samples = args.length > 7 ? Math.max(0, Integer.parseInt(args[7])) : 3;

        Map<String, Integer> kinds = new TreeMap<>();
        Map<String, List<String>> examples = new TreeMap<>();
        int total = 0;
        int withContent = 0;
        for (int cx = x1 >> 4; cx <= (x2 >> 4); cx++) {
            for (int cz = z1 >> 4; cz <= (z2 >> 4); cz++) {
                for (int[] pos : tilePositions(w, cx, cz)) {
                    int bx = pos[0];
                    int by = pos[1];
                    int bz = pos[2];
                    if (bx < x1 || bx > x2 || by < y1 || by > y2 || bz < z1 || bz > z2) {
                        continue;
                    }
                    BlockState st = w.getBlockAt(bx, by, bz).getState();
                    if (!(st instanceof TileState)) {
                        continue;
                    }
                    total++;
                    String kind = st.getType().getKey().toString().replace("minecraft:", "");
                    kinds.merge(kind, 1, Integer::sum);
                    String desc = describe(st);
                    if (desc != null) {
                        withContent++;
                        List<String> list = examples.computeIfAbsent(kind, k -> new ArrayList<>());
                        if (list.size() < samples) {
                            list.add("@" + bx + "," + by + "," + bz + " " + desc);
                        }
                    }
                }
            }
        }
        String head = "TILES-RESULT " + w.getName() + " 盒(" + x1 + "," + y1 + "," + z1 + ")-("
                + x2 + "," + y2 + "," + z2 + ") 方块实体=" + total + " 有内容=" + withContent
                + " " + kinds;
        sender.sendMessage(head);
        Bukkit.getLogger().info(head);
        for (Map.Entry<String, List<String>> e : examples.entrySet()) {
            for (String ex : e.getValue()) {
                String line = "TILES-SAMPLE " + e.getKey() + " " + ex;
                sender.sendMessage(line);
                Bukkit.getLogger().info(line);
            }
        }
        return true;
    }

    /**
     * 枚举一个源区块里的**方块实体位置**（局部坐标 → 世界坐标，返回 int[3] 列表）。
     *
     * <h2>为什么不能直接用 Bukkit 的 {@code Chunk#getTileEntities()}</h2>
     * 实测（2026-11-03，Paper 26.2）：**刚加载的区块**里方块实体还在
     * {@code ChunkAccess.pendingBlockEntities}（待提升）里，没被提升进 {@code blockEntities}；
     * {@code getTileEntities()} 只读后者，于是搬迁时"MM 四图 1582 处方块实体只复制到 9 个"。
     * 更恶心的是它**不报错**——数量少一点而已，完全是静默失败。
     *
     * <p>所以这里走 NMS 的 {@code ChunkAccess#getBlockEntitiesPos()}（字节码确认：
     * 它返回 {@code pendingBlockEntities ∪ blockEntities} 的并集），拿到位置后再用 Bukkit 的
     * {@code Block#getState()} 取值——那一步会把 pending 的实体正常提升（走 Mojang 自己的
     * {@code LevelChunk#getBlockEntity}，不会重复注册 ticker）。
     *
     * <p>反射失败就退回 {@code getTileEntities()}（少复制总比崩了好，但要打 WARN 留痕）。
     */
    private static List<int[]> tilePositions(World world, int cx, int cz) {
        List<int[]> out = new ArrayList<>();
        if (Nms.broken) {
            return fallbackPositions(world, cx, cz, out);
        }
        try {
            Object level = Nms.handle(world);
            Object chunk = Nms.chunk(level, cx, cz);
            Object raw = Nms.blockEntitiesPos(chunk);
            if (!(raw instanceof java.util.Set<?> set)) {
                return out;
            }
            for (Object pos : set) {
                out.add(Nms.xyz(pos));
            }
            return out;
        } catch (Throwable t) {
            Nms.broken = true;
            Bukkit.getLogger().warning("[rcopy] 反射取方块实体位置失败（退回调 getTileEntities）：" + t);
            return fallbackPositions(world, cx, cz, new ArrayList<>());
        }
    }

    private static List<int[]> fallbackPositions(World world, int cx, int cz, List<int[]> out) {
        for (BlockState st : world.getChunkAt(cx << 4, cz << 4).getTileEntities(false)) {
            out.add(new int[]{st.getX(), st.getY(), st.getZ()});
        }
        return out;
    }

    /** 反射缓存（只为拿 "这个区块有哪些方块实体位置"，一个方法一个 Method）。 */
    private static final class Nms {
        private static Method getHandle;
        private static Method getChunk;
        private static Method blockEntitiesPos;
        private static Method posGetX;
        private static Method posGetY;
        private static Method posGetZ;
        private static boolean broken;

        static Object handle(World world) throws ReflectiveOperationException {
            if (getHandle == null) {
                getHandle = world.getClass().getMethod("getHandle");
            }
            return getHandle.invoke(world);
        }

        static Object chunk(Object level, int cx, int cz) throws ReflectiveOperationException {
            if (getChunk == null) {
                getChunk = level.getClass().getMethod("getChunk", int.class, int.class);
            }
            return getChunk.invoke(level, cx, cz);
        }

        static Object blockEntitiesPos(Object chunk) throws ReflectiveOperationException {
            if (chunk == null) {
                return null;
            }
            if (blockEntitiesPos == null) {
                blockEntitiesPos = chunk.getClass().getMethod("getBlockEntitiesPos");
            }
            return blockEntitiesPos.invoke(chunk);
        }

        static int[] xyz(Object pos) throws ReflectiveOperationException {
            if (posGetX == null) {
                posGetX = pos.getClass().getMethod("getX");
                posGetY = pos.getClass().getMethod("getY");
                posGetZ = pos.getClass().getMethod("getZ");
            }
            return new int[]{((Number) posGetX.invoke(pos)).intValue(),
                    ((Number) posGetY.invoke(pos)).intValue(),
                    ((Number) posGetZ.invoke(pos)).intValue()};
        }
    }

    /** 抽一条"能看出内容在不在"的描述；没有内容返回 null。 */
    private static String describe(BlockState st) {
        try {
            if (st instanceof Sign sign) {
                String front = joinLines(sign.getSide(Side.FRONT).getLines());
                String back = joinLines(sign.getSide(Side.BACK).getLines());
                if (front.isEmpty() && back.isEmpty()) {
                    return null;
                }
                return "告示牌 front=[" + front + "]" + (back.isEmpty() ? "" : " back=[" + back + "]");
            }
            if (st instanceof Container c) {
                ItemStack[] contents = c.getSnapshotInventory().getContents();
                int used = 0;
                ItemStack first = null;
                for (ItemStack it : contents) {
                    if (it != null && it.getType().isItem() && it.getAmount() > 0) {
                        used++;
                        if (first == null) {
                            first = it;
                        }
                    }
                }
                if (used == 0) {
                    return null;
                }
                return "容器 槽位=" + used + "/" + contents.length + " 首个="
                        + (first == null ? "-" : first.getType() + "x" + first.getAmount());
            }
            if (st instanceof Jukebox jb) {
                if (!jb.hasRecord()) {
                    return null;
                }
                ItemStack rec = jb.getRecord();
                return "唱片机 " + (rec == null ? "-" : rec.getType().toString());
            }
            if (st instanceof Banner banner) {
                if (banner.numberOfPatterns() == 0) {
                    return null;
                }
                return "旗帜 底色=" + banner.getBaseColor() + " 花纹=" + banner.numberOfPatterns();
            }
            if (st instanceof Skull skull) {
                com.destroystokyo.paper.profile.PlayerProfile prof = skull.getPlayerProfile();
                if (prof == null) {
                    return null;
                }
                boolean hasTex = prof.getProperties() != null && !prof.getProperties().isEmpty();
                if ((prof.getName() == null || prof.getName().isEmpty()) && prof.getId() == null
                        && !hasTex) {
                    return null;
                }
                return "头颅 profile=" + (prof.getName() == null ? "" : prof.getName())
                        + "/" + (prof.getId() == null ? "-" : prof.getId())
                        + " 皮肤属性=" + (hasTex ? "有" : "无");
            }
            if (st instanceof org.bukkit.block.CreatureSpawner sp) {
                if (sp.getSpawnedType() == null || sp.getSpawnedType() == org.bukkit.entity.EntityType.UNKNOWN) {
                    return null;
                }
                return "刷怪笼 " + sp.getSpawnedType();
            }
        } catch (RuntimeException ex) {
            return "（读内容异常：" + ex.getClass().getSimpleName() + "）";
        }
        return null;
    }

    private static String joinLines(String[] lines) {
        if (lines == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 加载/卸载服务器目录里已经存在的世界目录。
     *
     * <p>为什么不走 {@code /gw load}：那个要求世界先在 GameWorlds 的 config.yml 里登记过。
     * 迁库时"源世界"是备份目录临时拷回来的，**不该**污染配置（临时登记完还得再删，
     * 删漏了就永远留在世界列表里 —— 这正是本次迁移要避免的事）。
     */
    private boolean rworld(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("用法：/rworld load|unload <世界名>");
            sender.sendMessage("      已加载的世界：" + worldsList());
            return true;
        }
        String action = args[0].toLowerCase();
        if (action.equals("list")) {
            sender.sendMessage("已加载：" + worldsList());
            return true;
        }
        String name = args[1];
        if (action.equals("unload")) {
            World w = Bukkit.getWorld(name);
            if (w == null) {
                sender.sendMessage("世界未加载：" + name);
                return true;
            }
            if (!Bukkit.getWorlds().isEmpty() && Bukkit.getWorlds().get(0).equals(w)) {
                sender.sendMessage("拒绝卸载主世界：" + name);
                return true;
            }
            // save=false：源世界是只读的，绝不能把关服时的状态写回去
            boolean ok = Bukkit.unloadWorld(w, false);
            String line = "RWORLD unload " + name + " → " + (ok ? "OK" : "失败");
            sender.sendMessage(line);
            Bukkit.getLogger().info(line);
            return true;
        }
        if (!action.equals("load")) {
            sender.sendMessage("用法：/rworld load|unload <世界名>");
            return true;
        }
        World loaded = Bukkit.getWorld(name);
        if (loaded != null) {
            sender.sendMessage("世界已经是加载状态：" + name);
            return true;
        }
        File dim = dimensionDir(name);
        if (dim == null || !dim.isDirectory()) {
            sender.sendMessage("找不到世界目录（不会自动生成）：" + (dim == null ? "?" : dim.getPath()));
            return true;
        }
        // 放到下一个 tick 建世界：一是不在命令外框（Commands#executeCommandInContext）里做重活，
        // 二是开服后**头 1~2 秒**喂进来的控制台命令本来就会撞上 Paper 的启动窗口
        // （报 `getLevel() is null` 的 NPE，实测 5/5，和控制台发什么命令无关 ——
        // smoke/serve 里等 10 秒再发命令也没事）。
        sender.sendMessage("已提交加载请求：" + name + "（下一 tick 执行）");
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                World w = Bukkit.createWorld(new WorldCreator(name)
                        .environment(World.Environment.NORMAL)
                        .type(WorldType.NORMAL)
                        .generateStructures(false));
                String line = "RWORLD load " + name + " → "
                        + (w == null ? "失败" : "OK（" + w.getWorldFolder().getPath() + "）");
                Bukkit.getLogger().info(line);
            } catch (RuntimeException ex) {
                Bukkit.getLogger().warning("[rworld] 加载 " + name + " 异常：" + ex);
            }
        });
        return true;
    }

    private static String worldsList() {
        StringBuilder sb = new StringBuilder();
        for (World w : Bukkit.getWorlds()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(w.getName());
        }
        return sb.toString();
    }

    /**
     * 世界名 → 目录。要兼容两种布局（本服是前者）：
     * <ul>
     *   <li>Paper Moonrise：每个世界是一个维度目录 {@code <level>/dimensions/minecraft/<名字>}，
     *       连主世界都是 —— 实测 {@code getWorldFolder()} 返回的是
     *       {@code .../world/dimensions/minecraft/overworld}，不是 {@code .../world}；</li>
     *   <li>经典 Bukkit：世界目录直接挂在服务器根下 {@code <server>/<名字>}。</li>
     * </ul>
     */
    private static File dimensionDir(String name) {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return null;
        }
        File main = worlds.get(0).getWorldFolder();
        List<File> candidates = new ArrayList<>();
        if (main != null) {
            File parent = main.getParentFile();
            if (parent != null) {
                candidates.add(new File(parent, name));                       // Moonrise 维度目录
            }
            candidates.add(new File(new File(main, "dimensions" + File.separator
                    + "minecraft"), name));                                   // 若 getWorldFolder 给的是 level
        }
        File container = Bukkit.getWorldContainer();
        if (container != null) {
            candidates.add(new File(container, name));                        // 经典布局
        }
        for (File f : candidates) {
            if (f.isDirectory()) {
                return f;
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }
}
