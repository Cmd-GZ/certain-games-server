package com.buildbattle.gtb.arena;

import com.buildbattle.gtb.GuessBuildPlugin;
import com.buildbattle.gtb.util.Chat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 场地模块（零依赖）：
 * 1. 由管理员用木棍选两个角点；
 * 2. 保存场地内所有非空气方块为快照（内存 + arena-<名称>.snap 文件）；
 * 3. 每轮开始/结束时在同一 tick 内还原场地（清掉建造物 + 放回快照 + 重铺地板），不会出现"地板消失又长回来"的过程；
 * 4. 支持通过 NPC 更换场地地板方块（草方块/冰/羊毛/水/岩浆等）。
 *
 * 多场地：每个场地有唯一名称，配置写入 arenas.<名称> 段，快照文件为 arena-<名称>.snap。
 */
public class Arena {

    /** 场地体积上限（格），防止误选超大区域导致快照爆炸。 */
    private static final long MAX_VOLUME = 200_000;
    /** 默认地板方块：场地生成、每轮清场、恢复默认时铺设。 */
    private static final Material DEFAULT_FLOOR = Material.OAK_LOG;

    /** 地板商店可选方块（编号 1~8，第 9 项为恢复默认）。 */
    private static final List<Material> FLOORS = List.of(
            Material.GRASS_BLOCK, Material.ICE, Material.WHITE_WOOL,
            Material.WATER, Material.LAVA, Material.SAND, Material.STONE, Material.DIRT);

    private final GuessBuildPlugin plugin;
    private String name;

    /**
     * D22：这个场地实例**钉死**的房间世界（每个房间一份实例）。
     *
     * <p>{@link com.hub.room.RoomBinding} 是"按游戏一个槽"，两个房间同时跑时后一个覆盖前一个 ——
     * 甲房的实例会读到乙房的世界（人被传错房间，或 {@code Location cannot be in a different world}）。
     * 钉死之后读取只认自己的世界。
     */
    private World roomWorld;
    /**
     * 快照文件是否允许写盘。
     *
     * <p>{@code arena-<名称>.snap} 是**每张图一份**的共享文件（内容是世界无关的
     * {@code 坐标 → BlockData}），房间里重选区时如果照写就会把别的房间/定义实例的快照覆盖掉。
     * 房间实例一律 {@code false}（只读共享），写盘只留给定义实例（R2 单一写者）。
     */
    private boolean snapshotWritable = true;

    private World world;
    private Location min;
    private Location max;
    private Location spawn;

    /** 场地保存时的快照（非空气方块）：每轮还原用它，也是"场地原有结构"的判断依据。 */
    private final Map<BlockPos, BlockData> snapshot = new HashMap<>();
    /** 当前地板方块；null = 使用默认（保存时的地板）。 */
    private Material floor = null;
    /** 正在分帧铺地板的任务（换地板时先取消旧的，避免两个任务打架）。 */
    private BukkitTask floorTask = null;
    /** 每 tick 最多处理多少块地板：太少会很久，太多会掉 TPS。500 ≈ 2~3ms/tick。 */
    private static final int FLOOR_BLOCKS_PER_TICK = 500;

    public Arena(GuessBuildPlugin plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /** 改名（场地重命名时由管理器调用；快照文件由管理器负责迁移）。 */
    public void setName(String name) {
        this.name = name;
    }

    // ---------- D22：房间世界绑定 ----------

    /** 把场地实例钉到"这一局实际跑的世界"（房间实例用；定义实例不绑）。 */
    public void bindWorld(World world) {
        this.roomWorld = world;
    }

    /** 钉住的世界；没钉返回 null。 */
    public World boundWorld() {
        return roomWorld;
    }

    /** 房间实例 = 只读共享快照文件，不写盘（别把别人的 {@code arena-<名称>.snap} 写坏）。 */
    public void setSnapshotWritable(boolean writable) {
        this.snapshotWritable = writable;
    }

    public boolean snapshotWritable() {
        return snapshotWritable;
    }

    /**
     * 读坐标时的世界解析：**钉死了就只认自己的房间世界**，没钉才退回按上下文的换绑。
     *
     * <p>钉死之后**不能**再回落 {@link com.hub.room.RoomBinding}：它先看"派对委派"、再看
     * "该游戏唯一的房间"，两者都是**按游戏一个槽**；两房间同时跑时会给出另一个房间的世界，
     * 于是同一份代码里"世界"和"坐标"对不上（抛 {@code Location cannot be in a different world}）。
     */
    private Location apply(Location loc) {
        if (loc == null) {
            return null;
        }
        if (roomWorld != null) {
            if (roomWorld.equals(loc.getWorld())) {
                return loc;
            }
            return new Location(roomWorld, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        }
        return com.hub.room.RoomBinding.apply("gtb", loc);
    }

    /**
     * **把解析出来的坐标重新钉回自己的房间世界**（{@link #loadFromConfig} 用）。
     *
     * <p>{@code tpl:} 的解析看"当前房间上下文"：多世界房间（派对图 = 迷你小游戏世界 + 各游戏自己的模板）里
     * 它解析到的是**第一个世界**，不一定是这张图所在的那个实例。
     */
    private Location pin(Location loc) {
        if (loc == null || roomWorld == null || roomWorld.equals(loc.getWorld())) {
            return loc;
        }
        return new Location(roomWorld, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    // ---------- 配置读写 ----------

    /** 从配置段加载场地（world/pos1/pos2/spawn + 快照文件）。 */
    public void loadFromConfig(ConfigurationSection cs) {
        snapshot.clear();
        floor = null;
        if (cs == null) {
            world = null;
            min = max = spawn = null;
            return;
        }
        String worldName = cs.getString("world");
        // R2：world 段可能是 tpl: 令牌
        world = (worldName == null || worldName.isEmpty()) ? null : com.hub.world.Worlds.resolve(worldName);
        if (world == null && roomWorld == null) {
            min = max = spawn = null;
            return;
        }
        // D22：解析结果**钉回本房间世界**（`tpl:` 解析看上下文，多世界房间里可能指到别人的世界）
        min = pin(readLocation(cs, "pos1"));
        max = pin(readLocation(cs, "pos2"));
        spawn = pin(readLocation(cs, "spawn"));
        File snap = new File(plugin.getDataFolder(), "arena-" + name + ".snap");
        if (snap.exists()) {
            loadSnapshotFile(snap);
        }
        // 启动时把地板铺成默认方块（橡木原木）
        floor = null;
        applyFloor(DEFAULT_FLOOR);
    }

    private Location readLocation(ConfigurationSection cs, String key) {
        // D22：坐标的世界用**本实例的世界**（房间实例 = 钉死的房间世界；定义实例 = 解析出来的模板世界）
        World base = roomWorld != null ? roomWorld : world;
        List<Integer> ints = cs.getIntegerList(key);
        if (ints.size() >= 3) {
            return new Location(base, ints.get(0), ints.get(1), ints.get(2));
        }
        List<String> list = cs.getStringList(key);
        if (list == null || list.size() < 3) {
            return null;
        }
        try {
            return new Location(base,
                    Double.parseDouble(list.get(0)),
                    Double.parseDouble(list.get(1)),
                    Double.parseDouble(list.get(2)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 由选区命令调用：校验并保存场地，同时写入配置文件和快照文件。 */
    public boolean setFrom(Location a, Location b) {
        if (a.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            return false;
        }
        World w = a.getWorld();
        int x1 = Math.min(a.getBlockX(), b.getBlockX());
        int x2 = Math.max(a.getBlockX(), b.getBlockX());
        int y1 = Math.min(a.getBlockY(), b.getBlockY());
        int y2 = Math.max(a.getBlockY(), b.getBlockY());
        int z1 = Math.min(a.getBlockZ(), b.getBlockZ());
        int z2 = Math.max(a.getBlockZ(), b.getBlockZ());

        long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (volume < 16 || volume > MAX_VOLUME) {
            return false;
        }

        this.world = w;
        this.min = new Location(w, x1, y1, z1);
        this.max = new Location(w, x2, y2, z2);
        this.spawn = new Location(w, (x1 + x2) / 2.0 + 0.5, y1 + 1, (z1 + z2) / 2.0 + 0.5);
        this.floor = null;

        captureSnapshot();
        persistCoordinates();
        return true;
    }

    /**
     * 把坐标写回配置 + 快照文件。
     *
     * <p><b>R2 单一写者</b>：房间实例（{@link #snapshotWritable} = false）**什么都不写** ——
     * 配置段与 {@code arena-<名称>.snap} 都归定义实例，房间只是跑在它的一份拷贝上。
     *
     * <p><b>只写 {@code tpl:} 令牌</b>：房间世界里选区时 {@code Worlds.tokenOf} 会还原成
     * {@code tpl:<模板键>}；反查不到模板（房间已销毁 / 手建的 room_ 世界）就**整项跳过**，
     * 绝不把 {@code room_xxx_1} 写进磁盘（R2 违规，删房后该场地永久"未就绪"）。
     */
    private void persistCoordinates() {
        if (!snapshotWritable) {
            plugin.getLogger().info("[GuessTheBuild] 房间实例的场地「" + name
                    + "」只在本局生效：不写配置、不写快照文件（R2 单一写者）。");
            return;
        }
        // 先读回磁盘，只改本场这一段，避免把别的进程/实例刚写的键覆盖掉
        plugin.reloadConfig();
        String prefix = "arenas." + name + ".";
        String token = (world == null) ? null : com.hub.world.Worlds.tokenOf(world);
        if (token == null || token.isBlank() || token.startsWith("room_")) {
            plugin.getLogger().warning("[GuessTheBuild] 跳过写场地「" + name + "」的世界段：世界 "
                    + (world == null ? "（空）" : world.getName()) + " 反查不到模板（保留磁盘原值）。");
        } else {
            plugin.getConfig().set(prefix + "world", token);
            plugin.getConfig().set(prefix + "pos1", List.of(min.getBlockX(), min.getBlockY(), min.getBlockZ()));
            plugin.getConfig().set(prefix + "pos2", List.of(max.getBlockX(), max.getBlockY(), max.getBlockZ()));
            plugin.getConfig().set(prefix + "spawn",
                    List.of(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ()));
            plugin.getConfig().set(prefix + "floor", null);
        }
        plugin.saveConfig();

        try {
            saveSnapshotFile();
        } catch (IOException e) {
            plugin.getLogger().warning("保存场地快照失败: " + e.getMessage());
        }
    }

    // ---------- 快照 ----------

    private void captureSnapshot() {
        snapshot.clear();
        if (min == null || max == null || min.getWorld() == null) {
            return;
        }
        World w = min.getWorld();
        for (int x = min.getBlockX(); x <= max.getBlockX(); x++) {
            for (int y = min.getBlockY(); y <= max.getBlockY(); y++) {
                for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++) {
                    BlockData data = w.getBlockAt(x, y, z).getBlockData();
                    if (!data.getMaterial().isAir()) {
                        snapshot.put(new BlockPos(x, y, z), data);
                    }
                }
            }
        }
    }

    private void saveSnapshotFile() throws IOException {
        if (!snapshotWritable) {
            return;   // 房间实例：快照文件是共享只读的
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<BlockPos, BlockData> e : snapshot.entrySet()) {
            BlockPos p = e.getKey();
            sb.append(p.x()).append(',').append(p.y()).append(',').append(p.z())
                    .append(';').append(e.getValue().getAsString()).append('\n');
        }
        File file = new File(plugin.getDataFolder(), "arena-" + name + ".snap");
        Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
    }

    private void loadSnapshotFile(File file) {
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int semi = line.indexOf(';');
                if (semi <= 0) {
                    continue;
                }
                String[] xyz = line.substring(0, semi).split(",");
                if (xyz.length != 3) {
                    continue;
                }
                BlockPos pos = new BlockPos(
                        Integer.parseInt(xyz[0].trim()),
                        Integer.parseInt(xyz[1].trim()),
                        Integer.parseInt(xyz[2].trim()));
                BlockData data = Bukkit.createBlockData(line.substring(semi + 1));
                snapshot.put(pos, data);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("读取场地快照失败: " + e.getMessage());
        }
    }

    // ---------- 还原 ----------

    /**
     * 一次性还原场地：清掉本轮建造的方块并把快照放回去，最后重铺地板。
     * <p>
     * 为了不出现"地板先消失再慢慢长回来"的过程，这里**同一 tick 内同步做完**：
     * 只对"需要变动的方块"写数据（本来就还是空气、快照也没它的位置直接跳过），
     * 所以真实写入量只有本轮建造的那几百格，代价可控；完成后再回调 onDone（可为 null）。
     */
    public void restore(Runnable onDone) {
        if (!isReady()) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }

        Location lo = getMin();
        Location hi = getMax();
        World w = lo.getWorld();
        int buildHeight = plugin.getConfig().getInt("game.max-build-height", 40);
        int clearTop = hi.getBlockY() + Math.max(4, buildHeight);
        BlockData air = Material.AIR.createBlockData();
        int changed = 0;
        for (int x = lo.getBlockX(); x <= hi.getBlockX(); x++) {
            for (int z = lo.getBlockZ(); z <= hi.getBlockZ(); z++) {
                for (int y = lo.getBlockY(); y <= clearTop; y++) {
                    BlockData target = snapshot.get(new BlockPos(x, y, z));
                    if (target == null) {
                        if (w.getBlockAt(x, y, z).getType().isAir()) {
                            continue; // 本该是空气且现在就是空气 → 跳过（绝大多数格子走这里）
                        }
                        target = air; // 建造者新放的方块 → 清掉
                    }
                    w.getBlockAt(x, y, z).setBlockData(target, false);
                    changed++;
                }
            }
        }
        // 每轮清空结束后地板重置为默认橡木原木
        floor = null;
        applyFloor(DEFAULT_FLOOR);
        if (changed > 0) {
            plugin.getLogger().fine("场地「" + name + "」已还原（重写 " + changed + " 格）。");
        }
        if (onDone != null) {
            onDone.run();
        }
    }

    // ---------- 查询 ----------

    public boolean isReady() {
        Location lo = getMin();
        Location hi = getMax();
        return getWorld() != null && lo != null && hi != null && getSpawn() != null
                && volume() >= 16;
    }

    public long volume() {
        Location lo = getMin();
        Location hi = getMax();
        if (lo == null || hi == null) {
            return 0;
        }
        return (long) (hi.getBlockX() - lo.getBlockX() + 1)
                * (long) (hi.getBlockY() - lo.getBlockY() + 1)
                * (long) (hi.getBlockZ() - lo.getBlockZ() + 1);
    }

    /**
     * 场地世界：**必须与坐标 getter 同源**，否则会出现"世界是 A 房、坐标是 B 房"
     * （{@code Location cannot be in a different world} 就是这么来的）。
     *
     * <p>钉死了房间世界就只认它；没钉（定义实例）才退回按上下文的换绑。
     */
    public World getWorld() {
        if (roomWorld != null) {
            return roomWorld;
        }
        Location resolved = apply(min);
        return resolved != null ? resolved.getWorld() : world;
    }

    /** 快照里的方块数量（保存场地时记录；0 表示没有快照文件）。 */
    public int getSnapshotSize() {
        return snapshot.size();
    }

    /** 场地保存时该位置的原始方块（快照）；该位置原本为空气时返回 null。 */
    public BlockData getSnapshotAt(int x, int y, int z) {
        return snapshot.get(new BlockPos(x, y, z));
    }

    /** 场地边界角 1（世界 = {@link #getWorld()}，与坐标同源）。 */
    public Location getMin() {
        return apply(min);
    }

    /** 场地边界角 2。 */
    public Location getMax() {
        return apply(max);
    }

    /** 等待/观战出生点。 */
    public Location getSpawn() {
        return apply(spawn);
    }

    /** 建造者开始位置：场地水平中心、地板上方 1 格（用于开局与越界拉回，不再使用大厅）。 */
    /** 评价阶段观众出生点：场地中心上方（高于地板 4 格，避免卡进建筑）。 */
    public Location getEvalSpawn() {
        return getBuildStart().add(0, 3, 0);
    }

    public Location getBuildStart() {
        Location lo = getMin();
        Location hi = getMax();
        return new Location(getWorld(),
                (lo.getX() + hi.getX()) / 2.0 + 0.5,
                hi.getY() + 1.0,
                (lo.getZ() + hi.getZ()) / 2.0 + 0.5);
    }

    /**
     * 启动时补齐场地四周屏障墙（原地图脚本只建到 y-50，
     * 但建造高度上限是 -21，上方开口，玩家会飞出去）。
     * 墙体位于选区外一圈（不会被清场抽干），高度从地板上方 1 格到建造上限 +2 格。
     */
    public void ensureBarrierWalls() {
        if (!isReady()) {
            return;
        }
        Location lo = getMin();
        Location hi = getMax();
        World w = getWorld();
        int buildHeight = plugin.getConfig().getInt("game.max-build-height", 40);
        int floorY = lo.getBlockY();
        int top = floorY + buildHeight + 2;
        int x1 = lo.getBlockX() - 1;
        int x2 = hi.getBlockX() + 1;
        int z1 = lo.getBlockZ() - 1;
        int z2 = hi.getBlockZ() + 1;
        BlockData wall = Material.BARRIER.createBlockData();
        for (int y = floorY + 1; y <= top; y++) {
            for (int z = z1; z <= z2; z++) {
                w.getBlockAt(x1, y, z).setBlockData(wall, false);
                w.getBlockAt(x2, y, z).setBlockData(wall, false);
            }
            for (int x = x1; x <= x2; x++) {
                w.getBlockAt(x, y, z1).setBlockData(wall, false);
                w.getBlockAt(x, y, z2).setBlockData(wall, false);
            }
        }
    }

    public double getCenterX() {
        Location lo = getMin();
        Location hi = getMax();
        return lo == null || hi == null ? 0 : (lo.getX() + hi.getX()) / 2.0;
    }

    public double getCenterZ() {
        Location lo = getMin();
        Location hi = getMax();
        return lo == null || hi == null ? 0 : (lo.getZ() + hi.getZ()) / 2.0;
    }

    /** 场地在水平方向上的最大半径（用于计算观战点距离）。 */
    public double getRadiusXZ() {
        Location lo = getMin();
        Location hi = getMax();
        return lo == null || hi == null ? 0
                : Math.max(hi.getX() - lo.getX(), hi.getZ() - lo.getZ()) / 2.0;
    }

    /** 判断位置是否在场地水平范围内（可带余量）。 */
    public boolean containsXZ(Location loc, int margin) {
        Location lo = getMin();
        Location hi = getMax();
        if (lo == null || hi == null || loc == null) {
            return false;
        }
        return loc.getX() >= lo.getX() - margin && loc.getX() <= hi.getX() + margin
                && loc.getZ() >= lo.getZ() - margin && loc.getZ() <= hi.getZ() + margin;
    }

    // ---------- 地板商店 ----------

    /** 打开地板商店 GUI（每格点击即切换）。 */
    public void openFloorMenu(Player p) {
        if (!isReady()) {
            Chat.send(p, "&c场地尚未设置，无法更换地板。");
            return;
        }
        Inventory inv = Bukkit.createInventory(null, 9, Chat.color("&6&l地板商店"));
        inv.setItem(0, floorItem(Material.GRASS_BLOCK, "草方块", "点击将场地地板换成草方块"));
        inv.setItem(1, floorItem(Material.ICE, "冰", "点击将场地地板换成冰"));
        inv.setItem(2, floorItem(Material.WHITE_WOOL, "白色羊毛", "点击将场地地板换成白色羊毛"));
        inv.setItem(3, floorItem(Material.WATER_BUCKET, "水", "点击将场地地板换成水（源块）"));
        inv.setItem(4, floorItem(Material.LAVA_BUCKET, "岩浆", "点击将场地地板换成岩浆（源块）"));
        inv.setItem(5, floorItem(Material.SAND, "沙子", "点击将场地地板换成沙子"));
        inv.setItem(6, floorItem(Material.STONE, "石头", "点击将场地地板换成石头"));
        inv.setItem(7, floorItem(Material.DIRT, "泥土", "点击将场地地板换成泥土"));
        inv.setItem(8, floorItem(Material.BARRIER, "恢复默认", "恢复默认地板（橡木原木）"));
        p.openInventory(inv);
    }

    private ItemStack floorItem(Material m, String name, String lore) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.noItalic(Chat.color("&f" + name)));
        meta.lore(List.of(Chat.color("&7" + lore)));
        item.setItemMeta(meta);
        return item;
    }

    /** 处理 /gtb floor <编号|名称> 或菜单点击。 */
    public boolean applyFloorSelection(Player p, String input) {
        if (!isReady()) {
            Chat.send(p, "&c场地尚未设置，无法更换地板。");
            return true;
        }
        String s = input.trim();
        int index = -1;
        try {
            index = Integer.parseInt(s) - 1;
        } catch (NumberFormatException ignored) {
            // 尝试按中文名匹配
            for (int i = 0; i < FLOORS.size(); i++) {
                if (floorName(FLOORS.get(i)).equals(s)) {
                    index = i;
                    break;
                }
            }
        }
        if (index == 8) {
            resetFloor();
            Chat.send(p, "&a已恢复默认地板（橡木原木）。");
            return true;
        }
        if (index < 0 || index >= FLOORS.size()) {
            Chat.send(p, "&c无效选项，请点击地板商店里的选项或输入 &e/gtb floor 1~9&c。");
            return false;
        }
        Material m = FLOORS.get(index);
        setFloor(m);
        Chat.send(p, "&a地板已更换为 &e" + floorName(m) + "&a。");
        return true;
    }

    /** 建造者选择地板：立即生效于当前轮，每轮结束后重置为默认地板（橡木原木）。 */
    public void setFloor(Material m) {
        if (!isReady() || m == null) {
            return;
        }
        floor = m;
        applyFloor(m);
    }

    /** 恢复默认地板（橡木原木）。 */
    public void resetFloor() {
        if (!isReady()) {
            return;
        }
        floor = null;
        applyFloor(DEFAULT_FLOOR);
    }

    /**
     * 把目标方块铺到世界地板层（不触发物理更新）。
     *
     * <p>⚠️ **必须分帧**：整块地板一次性铺（比如 100×100 = 1 万次 {@code setBlockData}）
     * 会在主线程上同步加载一堆区块，实测卡 **4.1 秒**（{@code /hub doctor} 的 {@code [卡顿]} 段抓到过
     * {@code Arena#applyFloor ← Arena#loadFromConfig}）。
     *
     * <p>另外加了快速路径：**已经是目标方块就跳过**，不再写。绝大多数情况下地板本来就是对的，
     * 所以实际开销接近零（只读不写）。
     */
    private void applyFloor(Material m) {
        if (!isReady() || m == null) {
            return;
        }
        if (floorTask != null) {
            floorTask.cancel();     // 换地板：先停掉上一轮
            floorTask = null;
        }
        final BlockData data = m.createBlockData();
        final Location lo = getMin();
        final Location hi = getMax();
        final World w = getWorld();
        final int y = lo.getBlockY();
        final int x0 = lo.getBlockX();
        final int x1 = hi.getBlockX();
        final int z0 = lo.getBlockZ();
        final int z1 = hi.getBlockZ();
        final int[] cursor = {x0, z0};
        floorTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int done = 0;
            while (done < FLOOR_BLOCKS_PER_TICK) {
                int x = cursor[0];
                int z = cursor[1];
                if (x > x1) {
                    if (floorTask != null) {
                        floorTask.cancel();
                        floorTask = null;
                    }
                    return;
                }
                org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                if (b.getType() != m) {
                    b.setBlockData(data, false);   // 只在不一样时才写
                }
                done++;
                if (++cursor[1] > z1) {
                    cursor[1] = z0;
                    cursor[0]++;
                }
            }
        }, 1L, 1L);
    }


    public Material getFloor() {
        return floor;
    }

    private String floorName(Material m) {
        return switch (m) {
            case GRASS_BLOCK -> "草方块";
            case ICE -> "冰";
            case WHITE_WOOL -> "白色羊毛";
            case WATER -> "水";
            case LAVA -> "岩浆";
            case SAND -> "沙子";
            case STONE -> "石头";
            case DIRT -> "泥土";
            default -> m.name();
        };
    }
}
