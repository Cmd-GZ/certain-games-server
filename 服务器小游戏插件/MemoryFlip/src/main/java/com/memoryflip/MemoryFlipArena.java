package com.memoryflip;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 场地：等待点 + 每个玩家一面竖直翻牌墙（牌位/站位/要清理的方块）。 */
public final class MemoryFlipArena {

    public static final class Board {
        final Location stand;
        final List<Location> frames = new ArrayList<>();
        final Set<Location> blocks = new LinkedHashSet<>();

        Board(Location stand) {
            this.stand = stand;
        }
    }

    private final JavaPlugin plugin;
    private Location lobby;
    private final List<Board> boards = new ArrayList<>();

    public MemoryFlipArena(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration cfg = plugin.getConfig();
        lobby = parse(cfg.getString("lobby", ""));
        boards.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("boards");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection b = sec.getConfigurationSection(key);
                if (b == null) {
                    continue;
                }
                Location stand = parse(b.getString("stand", ""));
                if (stand == null) {
                    continue;
                }
                Board board = new Board(stand);
                for (String raw : b.getStringList("frames")) {
                    Location loc = parse(raw);
                    if (loc != null) {
                        board.frames.add(loc);
                    }
                }
                for (String raw : b.getStringList("blocks")) {
                    Location loc = parse(raw);
                    if (loc != null) {
                        board.blocks.add(loc);
                    }
                }
                boards.add(board);
            }
        }
    }

    public void save() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("lobby", encode(lobby));
        cfg.set("boards", null);
        for (int i = 0; i < boards.size(); i++) {
            Board board = boards.get(i);
            String path = "boards." + i;
            cfg.set(path + ".stand", encode(board.stand));
            List<String> frames = new ArrayList<>();
            for (Location loc : board.frames) {
                frames.add(encode(loc));
            }
            cfg.set(path + ".frames", frames);
            List<String> blocks = new ArrayList<>();
            for (Location loc : board.blocks) {
                blocks.add(encode(loc));
            }
            cfg.set(path + ".blocks", blocks);
        }
        plugin.saveConfig();
    }

    public boolean isReady() {
        return lobby != null && !boards.isEmpty();
    }

    public String missing() {
        List<String> missing = new ArrayList<>();
        if (lobby == null) {
            missing.add("lobby");
        }
        if (boards.isEmpty()) {
            missing.add("boards（/memory build <人数>）");
        }
        return missing.isEmpty() ? "无" : String.join("、", missing);
    }

    public Location getLobby() {
        return  com.hub.room.RoomBinding.apply("memoryflip", lobby == null ? null : lobby.clone());
    }

    public void setLobby(Location lobby) {
        this.lobby = lobby == null ? null : lobby.clone();
    }

    public List<Board> getBoards() {
        return boards;
    }

    /** 清掉旧场地：先删展示框，再把记录过的方块还原成空气。 */
    public void clear() {
        for (Board board : boards) {
            for (Location loc : board.frames) {
                for (ItemFrame frame : framesAt(loc)) {
                    frame.remove();
                }
            }
            for (Location loc : board.blocks) {
                Block block = loc.getBlock();
                if (!block.getType().isAir()) {
                    block.setType(Material.AIR, false);
                }
            }
        }
        boards.clear();
    }

    /**
     * 以玩家脚下为基准，向玩家面前 3 格处生成 count 面翻牌墙；
     * 每面墙 cols 宽、rows 高，前面铺一小块地台，站位在墙前 2 格。
     */
    public int build(Player player, int count, int rows, int cols, int spacing,
                     Material wall, Material floor) {
        clear();
        Location base = player.getLocation();
        World world = base.getWorld();
        if (world == null || count < 4) {
            return 0;
        }
        int even = count - (count % 2);
        // 间距取偶数，保证一排墙的中心落在方块中心上，左右镜像一致
        if (spacing % 2 != 0) {
            spacing++;
        }
        int feetY = base.getBlockY();
        int floorY = feetY - 1;
        // 对称中心取玩家最近的一个方块角：偶数宽的墙心落在方块角上，四排墙才能同时
        // 在左右、前后、180° 旋转下严格镜像（取方块中心会出现半格错位）。
        int cx = (int) Math.floor(base.getX() + 0.5);
        int cz = (int) Math.floor(base.getZ() + 0.5);
        int half = cols / 2;

        // 四条边：北、东、南、西；f 从中心指向墙（也就是玩家面朝的方向）
        int[][] sides = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        int basePerRow = even / 4;
        int extraPair = even % 4 == 2 ? 1 : 0;
        int[] sizes = new int[4];
        for (int i = 0; i < 4; i++) {
            // 多出来的 2 面同时加到相对的两排（北/南），保持四排镜像对称
            sizes[i] = basePerRow + ((i == 0 || i == 2) ? extraPair : 0);
        }
        int maxRow = 0;
        for (int size : sizes) {
            maxRow = Math.max(maxRow, size);
        }
        double rowHalf = (maxRow - 1) / 2.0 * spacing + (cols - 1) / 2.0 + 2;
        int distance = (int) Math.max(8, Math.ceil(rowHalf + 4));

        for (int side = 0; side < 4; side++) {
            int fx = sides[side][0];
            int fz = sides[side][1];
            // 切线始终取正方向（南北排沿 +X，东西排沿 +Z）：墙的方块永远覆盖
            // [w-half, w+half)，墙心和站位才落在同一个点上，四排才真正镜像。
            int tx = fx != 0 ? 0 : 1;
            int tz = fx != 0 ? 1 : 0;
            int n = sizes[side];
            // 墙所在的方块坐标：正方向要再退一格，反向一格厚墙才会和对面镜面对称
            int baseX = cx + fx * distance - (fx > 0 ? 1 : 0);
            int baseZ = cz + fz * distance - (fz > 0 ? 1 : 0);
            for (int i = 0; i < n; i++) {
                // (2i-(n-1)) 是奇/偶对称的整数序列，spacing 已保证为偶数 → off 为整数且关于 0 对称
                int off = (2 * i - (n - 1)) * spacing / 2;
                int wallX = baseX + tx * off;
                int wallZ = baseZ + tz * off;
                // 站位：沿墙的法线往场地中心退 1.5 格；沿排方向对准这面墙的几何中心
                double standX = fx != 0 ? wallX + (fx < 0 ? 2.5 : -1.5) : wallX;
                double standZ = fz != 0 ? wallZ + (fz < 0 ? 2.5 : -1.5) : wallZ;
                Board board = new Board(new Location(world, standX, feetY, standZ));
                board.stand.setYaw(yawFacingWall(fx, fz));
                for (int row = 0; row < rows; row++) {
                    for (int col = 0; col < cols; col++) {
                        int cOff = col - half;
                        int x = wallX + tx * cOff;
                        int z = wallZ + tz * cOff;
                        int y = feetY + row;
                        Block wallBlock = world.getBlockAt(x, y, z);
                        wallBlock.setType(wall, false);
                        board.blocks.add(wallBlock.getLocation());
                        Block frameBlock = world.getBlockAt(x - fx, y, z - fz);
                        if (!frameBlock.getType().isAir()) {
                            frameBlock.setType(Material.AIR, false);
                        }
                        Location frameLoc = frameBlock.getLocation();
                        board.frames.add(frameLoc);
                        ItemFrame frame = world.spawn(frameLoc.clone().add(0.5, 0.5, 0.5), ItemFrame.class);
                        frame.setFacingDirection(frameFace(fx, fz));
                        frame.setFixed(true);
                        frame.setInvulnerable(true);
                        frame.setItemDropChance(0f);
                        frame.setItem(new org.bukkit.inventory.ItemStack(Material.AIR));
                    }
                }
                // 地台：朝中心方向铺 4 格深
                for (int depth = 0; depth <= 4; depth++) {
                    for (int col = -half - 1; col <= cols - half; col++) {
                        int x = wallX + tx * col - fx * depth;
                        int z = wallZ + tz * col - fz * depth;
                        Block floorBlock = world.getBlockAt(x, floorY, z);
                        floorBlock.setType(floor, false);
                        board.blocks.add(floorBlock.getLocation());
                        if (depth == 0) {
                            continue;
                        }
                        for (int up = 1; up <= 3; up++) {
                            Block above = world.getBlockAt(x, floorY + up, z);
                            if (!above.getType().isAir()) {
                                above.setType(Material.AIR, false);
                            }
                        }
                    }
                }
                boards.add(board);
            }
        }
        return boards.size();
    }

    private static float yawFacingWall(int fx, int fz) {
        return (float) Math.toDegrees(Math.atan2(-fx, fz));
    }

    private static BlockFace frameFace(int fx, int fz) {
        if (fz > 0) {
            return BlockFace.NORTH;
        }
        if (fz < 0) {
            return BlockFace.SOUTH;
        }
        if (fx > 0) {
            return BlockFace.WEST;
        }
        return BlockFace.EAST;
    }

    /** 按方块坐标找到展示框实体（可能没有）。 */
    public List<ItemFrame> framesAt(Location blockLoc) {
        List<ItemFrame> out = new ArrayList<>();
        World world = blockLoc.getWorld();
        if (world == null) {
            return out;
        }
        Location center = blockLoc.clone().add(0.5, 0.5, 0.5);
        for (Entity entity : world.getNearbyEntities(center, 0.6, 0.6, 0.6)) {
            if (entity instanceof ItemFrame frame && !frame.isDead()) {
                out.add(frame);
            }
        }
        return out;
    }

    private static String encode(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f",
                com.hub.world.Worlds.tokenOf(loc.getWorld()), loc.getX(), loc.getY(), loc.getZ());
    }

    private static Location parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 4) {
            return null;
        }
        World world = com.hub.world.Worlds.resolve(parts[0]);   // R2：支持 tpl:模板键
        if (world == null) {
            return null;
        }
        try {
            return new Location(world,
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
