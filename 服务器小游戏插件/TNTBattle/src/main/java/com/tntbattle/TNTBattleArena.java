package com.tntbattle;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 一个 TNT 对战场地：区域范围、每队多个出生点、等待点、虚空/建筑高度。 */
public final class TNTBattleArena {

    private final TNTBattlePlugin plugin;
    private final String name;

    /**
     * D22：这个场地实例**钉死**的房间世界（每个房间一份实例）。
     *
     * <p>{@link com.hub.room.RoomBinding} 是"按游戏一个槽"，两个房间同时跑时后一个覆盖前一个 ——
     * 甲房的实例会读到乙房的世界（人被传错房间，或 {@code Location cannot be in a different world}）。
     * 钉死之后读取只认自己的世界。
     */
    private World roomWorld;

    public void bindWorld(World world) {
        this.roomWorld = world;
    }

    public World boundWorld() {
        return roomWorld;
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
        return com.hub.room.RoomBinding.apply("tntbattle", loc);
    }

    /**
     * **把解析出来的坐标重新钉回自己的房间世界**（{@code load()} 用）。
     *
     * <p>{@code tpl:} 的解析看"当前房间上下文"，而 {@code /hub join} 会让 HubCore 给子游戏发一次
     * {@code reload}；如果那次上下文指向**别的**房间，解析结果就会落到别人的世界里。
     */
    private Location pin(Location loc) {
        if (loc == null) {
            return null;
        }
        if (roomWorld == null || roomWorld.equals(loc.getWorld())) {
            return loc;
        }
        return new Location(roomWorld, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    private String worldName = "world";
    private Location pos1;
    private Location pos2;

    /** 场地边界角 1（供区域声明用，{@code docs/框架不变量.md} I2）。 */
    public Location getPos1() {
        return apply(pos1 == null ? null : pos1.clone());
    }

    /** 场地边界角 2。 */
    public Location getPos2() {
        return apply(pos2 == null ? null : pos2.clone());
    }
    private Location lobby;
    private final List<Location> redSpawns = new ArrayList<>();
    private final List<Location> blueSpawns = new ArrayList<>();
    private int voidY = -64;
    private int buildHeightLimit = -1;
    /** 0 = 用全局 max-players。 */
    private int maxPlayers;

    public TNTBattleArena(TNTBattlePlugin plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    public String name() {
        return name;
    }

    private ConfigurationSection section() {
        return plugin.getConfig().getConfigurationSection("arenas." + name);
    }

    public void load() {
        ConfigurationSection sec = section();
        if (sec == null) {
            return;
        }
        FileConfiguration cfg = plugin.getConfig();
        worldName = sec.getString("world", cfg.getString("arena.world", "world"));
        // D22：解析出来的坐标**钉回本房间的世界**（`tpl:` 解析看上下文，可能指到别人的房间）
        pos1 = pin(parse(sec.getString("pos1", "")));
        pos2 = pin(parse(sec.getString("pos2", "")));
        lobby = pin(parse(sec.getString("lobby", "")));
        redSpawns.clear();
        blueSpawns.clear();
        for (String raw : sec.getStringList("red-spawns")) {
            Location loc = pin(parse(raw));
            if (loc != null) {
                redSpawns.add(loc);
            }
        }
        for (String raw : sec.getStringList("blue-spawns")) {
            Location loc = pin(parse(raw));
            if (loc != null) {
                blueSpawns.add(loc);
            }
        }
        // 兼容旧版单出生点配置
        if (redSpawns.isEmpty()) {
            Location legacy = pin(parse(sec.getString("red-spawn", "")));
            if (legacy != null) {
                redSpawns.add(legacy);
            }
        }
        if (blueSpawns.isEmpty()) {
            Location legacy = pin(parse(sec.getString("blue-spawn", "")));
            if (legacy != null) {
                blueSpawns.add(legacy);
            }
        }
        voidY = sec.getInt("void-y", cfg.getInt("void-y", -64));
        buildHeightLimit = sec.getInt("build-height-limit", cfg.getInt("build-height-limit", -1));
        maxPlayers = Math.max(0, sec.getInt("max-players", 0));
        if (pos1 != null && pos1.getWorld() != null) {
            worldName = com.hub.world.Worlds.tokenOf(pos1.getWorld());
        } else if (!redSpawns.isEmpty() && redSpawns.get(0).getWorld() != null) {
            worldName = com.hub.world.Worlds.tokenOf(redSpawns.get(0).getWorld());
        }
    }

    public void save() {
        // 先读回磁盘配置，只改本场 section，避免多场之间互相覆盖
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        String path = "arenas." + name;
        // R2：只写 `tpl:` 令牌；**读不出来就整项跳过**，绝不把磁盘上已有的坐标擦成 ''
        //（真实事故：TNTBattle 配置里出现 `pos1: room_tb3_1,334.00,...`；反向事故是把已配好的键写成空串）。
        String token = encodeWorld();
        if (token != null) {
            cfg.set(path + ".world", token);
        }
        write(cfg, path + ".pos1", pos1);
        write(cfg, path + ".pos2", pos2);
        write(cfg, path + ".lobby", lobby);
        cfg.set(path + ".red-spawn", null);
        cfg.set(path + ".blue-spawn", null);
        List<String> red = encodeAll(redSpawns);
        if (!red.isEmpty()) {
            cfg.set(path + ".red-spawns", red);
        }
        List<String> blue = encodeAll(blueSpawns);
        if (!blue.isEmpty()) {
            cfg.set(path + ".blue-spawns", blue);
        }
        cfg.set(path + ".void-y", voidY);
        cfg.set(path + ".build-height-limit", buildHeightLimit);
        cfg.set(path + ".max-players", maxPlayers);
        plugin.saveConfig();
    }

    /** 本场世界 → 配置令牌；读不出来（无世界 / 房间实例反查不到模板）返回 null。 */
    private String encodeWorld() {
        World world = world();
        if (world == null) {
            return null;
        }
        return encodeWorldName(world);
    }

    private static String encodeWorldName(World world) {
        String token = com.hub.world.Worlds.tokenOf(world);
        if (token == null || token.isBlank() || token.startsWith("room_")) {
            return null;
        }
        return token;
    }

    private List<String> encodeAll(List<Location> locations) {
        List<String> out = new ArrayList<>();
        for (Location location : locations) {
            String token = encode(location);
            if (token != null) {
                out.add(token);
            }
        }
        return out;
    }

    /** 写一个坐标；**写不了就什么都不做**（保留磁盘上的原值）。 */
    private static void write(FileConfiguration cfg, String path, Location loc) {
        String token = encode(loc);
        if (token != null) {
            cfg.set(path, token);
        }
    }

    /**
     * 坐标 → 存档 token；**写不了时返回 null**（调用方跳过）。
     *
     * <p>房间化之后场地跑在 `room_xxx` 里，把临时房间名写进配置（R2 违规）会让删房后配置永久失效。
     * {@link com.hub.world.Worlds#tokenOf} 会把房间实例还原成 `tpl:<模板键>`；反查不到模板就拒绝写。
     */
    public static String encode(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        String token = encodeWorldName(location.getWorld());
        if (token == null) {
            return null;
        }
        return String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.2f,%.2f",
                token, location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    public void setCorner(int index, Location location) {
        if (index == 1) {
            pos1 = location.getBlock().getLocation();
        } else {
            pos2 = location.getBlock().getLocation();
        }
        worldName = com.hub.world.Worlds.tokenOf(location.getWorld());
    }

    public void setSpawn(Team team, Location location) {
        spawns(team).clear();
        addSpawn(team, location);
    }

    public void addSpawn(Team team, Location location) {
        Location loc = location.clone();
        loc.setX(loc.getBlockX() + 0.5);
        loc.setZ(loc.getBlockZ() + 0.5);
        spawns(team).add(loc);
        worldName = com.hub.world.Worlds.tokenOf(loc.getWorld());
    }

    public void clearSpawns(Team team) {
        spawns(team).clear();
    }

    public void setLobby(Location location) {
        lobby = location.clone();
    }

    public void setVoidY(int y) {
        voidY = y;
    }

    public int voidY() {
        return voidY;
    }

    public void setBuildHeightLimit(int y) {
        buildHeightLimit = y;
    }

    /** -1 = 不限制。 */
    public int buildHeightLimit() {
        return buildHeightLimit;
    }

    public void setMaxPlayers(int value) {
        maxPlayers = Math.max(0, value);
    }

    /** 0 = 用全局 max-players。 */
    public int maxPlayers() {
        return maxPlayers;
    }

    public boolean regionConfigured() {
        Location a = getPos1();
        Location b = getPos2();
        return a != null && b != null
                && a.getWorld() != null && b.getWorld() != null
                && a.getWorld().equals(b.getWorld());
    }

    public boolean ready() {
        Location corner = getPos1();
        if (!regionConfigured() || corner == null || corner.getWorld() == null
                || lobby() == null || redSpawns.isEmpty() || blueSpawns.isEmpty()) {
            return false;
        }
        World world = corner.getWorld();
        for (Location spawn : redSpawns) {
            Location bound = apply(spawn);
            if (bound == null || bound.getWorld() == null || !world.equals(bound.getWorld())) {
                return false;
            }
        }
        for (Location spawn : blueSpawns) {
            Location bound = apply(spawn);
            if (bound == null || bound.getWorld() == null || !world.equals(bound.getWorld())) {
                return false;
            }
        }
        return true;
    }

    public boolean contains(Location location) {
        Location a = getPos1();
        Location b = getPos2();
        if (a == null || b == null || a.getWorld() == null
                || location == null || location.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().equals(a.getWorld()) || !a.getWorld().equals(b.getWorld())) {
            return false;
        }
        int minX = Math.min(a.getBlockX(), b.getBlockX());
        int maxX = Math.max(a.getBlockX(), b.getBlockX());
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        int maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        // pos1/pos2 只决定 X/Z 对角线；Y 覆盖整个世界高度（从世界最低到最高）
        int minY = location.getWorld().getMinHeight();
        int maxY = location.getWorld().getMaxHeight() - 1;
        return location.getBlockX() >= minX && location.getBlockX() <= maxX
                && location.getBlockY() >= minY && location.getBlockY() <= maxY
                && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
    }

    /**
     * 场地所在世界：**必须与 {@link #getPos1()} / {@link #lobby()} / {@link #spawn} 同源**，
     * 否则会出现"世界是 A 房、坐标是 B 房"的组合（{@code different world} 就是这么来的）。
     */
    public World world() {
        if (roomWorld != null) {
            return roomWorld;
        }
        if (regionConfigured()) {
            Location resolved = apply(pos1);
            return resolved == null ? null : resolved.getWorld();
        }
        if (!redSpawns.isEmpty()) {
            Location resolved = apply(redSpawns.get(0));
            return resolved == null ? null : resolved.getWorld();
        }
        return com.hub.world.Worlds.resolve(worldName);
    }

    public String worldName() {
        World world = world();
        return world == null ? worldName : world.getName();
    }

    public Location spawn(Team team, int index) {
        List<Location> list = spawns(team);
        if (list.isEmpty()) {
            return null;
        }
        return apply(list.get(Math.floorMod(index, list.size())).clone());
    }

    /** 原始出生点表（**可变**：setSpawn/addSpawn/clearSpawns 改的就是它）；要落地坐标请用 {@link #spawn}。 */
    public List<Location> spawns(Team team) {
        return team == Team.RED ? redSpawns : blueSpawns;
    }

    public Location lobby() {
        return apply(lobby == null ? null : lobby.clone());
    }

    public Location pos1() {
        return apply(pos1 == null ? null : pos1.clone());
    }

    public Location pos2() {
        return apply(pos2 == null ? null : pos2.clone());
    }

    public String sizeText() {
        if (!regionConfigured()) {
            return "未设置";
        }
        int dx = Math.abs(pos1.getBlockX() - pos2.getBlockX()) + 1;
        int dz = Math.abs(pos1.getBlockZ() - pos2.getBlockZ()) + 1;
        int dy = pos1.getWorld().getMaxHeight() - pos1.getWorld().getMinHeight();
        return dx + "x" + dy + "x" + dz + "（Y 覆盖整个世界高度）";
    }

    /** 淘汰后的旁观位置：优先等待点，否则第一个红队出生点上方。 */
    public Location spectatorLocation() {
        if (lobby != null) {
            return apply(lobby.clone());
        }
        if (!redSpawns.isEmpty()) {
            return apply(redSpawns.get(0).clone().add(0, 8, 0));
        }
        return null;
    }

    /**
     * 坐标 → 配置字符串（排行榜等**全局**坐标用它）；读不出世界时返回空串。
     *
     * <p>这里保留旧语义（返回 "" 而不是 null），因为调用方是"整数倍于字符串"的写配置；
     * 场地坐标一律走 {@link #encode}（读不出来就不写）。
     */
    public static String format(Location location) {
        if (location == null || location.getWorld() == null) {
            return "";
        }
        // **不能写 getWorld().getName()**：房间化之后场地跑在 room_xxx 里，
        // 把临时房间名写进配置（R2 违规）会让删房后配置永久失效
        //（真实事故：TNTBattle 配置里出现 `pos1: room_tb3_1,334.00,...`）。
        // Worlds.tokenOf 会把房间实例还原成 `tpl:<模板键>`。
        return String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.2f,%.2f",
                com.hub.world.Worlds.tokenOf(location.getWorld()),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    public static Location parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 4) {
            return null;
        }
        // 房间化（R2/R1.5）：配置里的世界段可能是 `tpl:<模板键>` 令牌，
        // 只有存活房间时才解析得出来。用 Bukkit.getWorld 会直接返回 null -> 场地永远"未配置"。
        World world = com.hub.world.Worlds.resolve(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = parts.length > 4 ? Float.parseFloat(parts[4]) : 0f;
            float pitch = parts.length > 5 ? Float.parseFloat(parts[5]) : 0f;
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public enum Team {
        RED("红队", "c", org.bukkit.Material.RED_WOOL),
        BLUE("蓝队", "9", org.bukkit.Material.BLUE_WOOL);

        private final String display;
        private final String tabColor;
        private final org.bukkit.Material wool;

        Team(String display, String tabColor, org.bukkit.Material wool) {
            this.display = display;
            this.tabColor = tabColor;
            this.wool = wool;
        }

        public String display() {
            return display;
        }

        public String tabColor() {
            return tabColor;
        }

        public org.bukkit.Material wool() {
            return wool;
        }

        public Team other() {
            return this == RED ? BLUE : RED;
        }
    }
}
