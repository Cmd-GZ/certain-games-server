package com.devtools;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;

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
 * <h2>命令</h2>
 * <pre>
 * /rcopy &lt;源世界&gt; &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt; &lt;目标世界&gt; [dx dy dz] [no-air]
 *     盒子复制到目标世界；默认偏移 = (x1,y1,z1) 即**绝对坐标不变**。
 *     默认**跳过空气**（目标是空世界时结果一致，快很多）；no-air = 空气也写（用于覆盖清理）。
 * /rcstat                     队列与当前进度
 * /rprobe &lt;世界&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;   探针：该点方块 + 脚下最近实体地面（验证"玩家站得住"）
 * /rscan &lt;世界&gt; &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt;
 *     世界内统计非空气方块（分批，与 rcopy 共用队列；结果打到控制台 SCAN-RESULT）
 * </pre>
 */
public final class DevToolsPlugin extends JavaPlugin {

    /** 每 tick 处理的方块数。2 万 ≈ 数百微秒的 setBlockData，体感无卡顿。 */
    private static final int BLOCKS_PER_TICK = 20000;

    /** 逐方块作业：{@link #step(int)} 返回**实际处理**的方块数；返回 0 表示做完。 */
    private interface Cursor {
        int step(int budget);

        /** 已"落地"的数量（复制 = 写入的方块数；扫描 = 非空气数）。 */
        long done();

        /** 顺手跳过的数量（复制 = 空气）。 */
        long skipped();
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
        getLogger().info("DevTools 已启用（rcopy 分批：每 tick " + BLOCKS_PER_TICK + " 方块）");
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
                    + " 用时=" + ms + "ms 剩余队列=" + queue.size());
        } else if (System.currentTimeMillis() - job.lastLog >= 5000L) {
            job.lastLog = System.currentTimeMillis();
            getLogger().info("[rcopy] 进度 job#" + job.id + " 落地=" + job.cursor.done()
                    + " 跳过=" + job.cursor.skipped() + "（队列剩余 " + queue.size() + "）");
        }
    }

    /** 盒子复制：保持绝对坐标（偏移默认 = 盒子最小角），默认跳过空气。 */
    private static final class BoxCursor implements Cursor {
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
        private final boolean silent;
        private int x;
        private int y;
        private int z;
        private long copied;
        private long skipped;
        private boolean finished;

        BoxCursor(World src, World dst, int x1, int y1, int z1, int x2, int y2, int z2,
                  int dx, int dy, int dz, boolean skipAir, boolean silent) {
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
            this.silent = silent;
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
                            finished = true;
                            break;
                        }
                    }
                }
            }
            if (finished && !silent) {
                Bukkit.getLogger().info("RCOPY-STAT " + dst.getName()
                        + " 复制=" + copied + " 跳过空气=" + skipped);
            }
            return n;
        }

        @Override
        public long done() {
            return copied;
        }

        @Override
        public long skipped() {
            return skipped;
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
                    if (b.getState() instanceof org.bukkit.block.TileState) {
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
                    : "队列 " + queue.size() + " 个；当前 job#" + cur.id + " " + cur.desc);
            return true;
        }
        if (name.equals("rprobe")) {
            return rprobe(sender, args);
        }
        if (name.equals("rscan")) {
            return rscan(sender, args);
        }
        return false;
    }

    private boolean rcopy(CommandSender sender, String[] args) {
        if (args.length < 8) {
            sender.sendMessage("用法：/rcopy <源世界> <x1> <y1> <z1> <x2> <y2> <z2> <目标世界> [dx dy dz] [no-air]");
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
            int dx = args.length > 8 && !args[8].equalsIgnoreCase("no-air")
                    ? Integer.parseInt(args[8]) : x1;
            int dy = args.length > 9 && !args[9].equalsIgnoreCase("no-air")
                    ? Integer.parseInt(args[9]) : y1;
            int dz = args.length > 10 && !args[10].equalsIgnoreCase("no-air")
                    ? Integer.parseInt(args[10]) : z1;
            boolean skipAir = true;
            for (String a : args) {
                if (a.equalsIgnoreCase("no-air")) {
                    skipAir = false;
                }
            }
            long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
            String desc = src.getName() + "→" + dst.getName()
                    + " 盒(" + x1 + "," + y1 + "," + z1 + ")-(" + x2 + "," + y2 + "," + z2 + ")"
                    + " 偏移=" + (dx - x1) + "," + (dy - y1) + "," + (dz - z1)
                    + " 体积=" + volume + (skipAir ? " 跳过空气" : " 含空气");
            Job job = submit(desc, new BoxCursor(src, dst, x1, y1, z1, x2, y2, z2,
                    dx, dy, dz, skipAir, false));
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
}
