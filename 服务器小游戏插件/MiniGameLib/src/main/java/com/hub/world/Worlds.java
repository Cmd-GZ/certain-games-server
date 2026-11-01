package com.hub.world;

import com.hub.room.RoomManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 世界解析器 —— {@code docs/框架不变量.md} 里「模板世界 / 游玩世界」（R2 坐标解耦）的核心。
 *
 * <h2>它解决什么问题</h2>
 * 房间化之后，一个场地的世界名是**动态的**（`room_hillking_1`、`room_hillking_2`…），
 * 而所有游戏配置里存的都是 `world_mini_game,627.5,53.0,307.5` 这种**写死世界名**的坐标。
 * 如果配置跟着房间名走，房间一删配置就失效。
 *
 * <h2>约定</h2>
 * 坐标字符串的第一段叫「世界 token」，两种写法：
 *
 * <pre>
 *   world_mini_game,627.5,53.0,307.5    ← 固定世界（旧配置，行为完全不变）
 *   tpl:hillking_天空竞技场,620.0,40.0,300.0  ← 模板作用域：解析成"该模板当前房间的世界"
 * </pre>
 *
 * <p>{@link #resolve(String)} 负责 token → {@link World}；
 * {@link #encode(Location)} 负责反向：房间世界 → 写回 {@code tpl:模板名}（**不写房间名**），
 * 这样「在房间里 /set 场地」也不会把配置写坏。
 *
 * <p>⚠️ 没有存活房间时 {@code tpl:} 解析为 {@code null}——调用方必须当作"场地未就绪"处理，
 * 而不是崩掉或退化到别的世界。
 */
public final class Worlds {

    /** 模板作用域前缀。 */
    public static final String TEMPLATE_PREFIX = "tpl:";

    private Worlds() {
    }

    /** 是不是模板作用域的 token。 */
    public static boolean isTemplateToken(String token) {
        return token != null && token.trim().startsWith(TEMPLATE_PREFIX);
    }

    /** 取 token 里的模板键（非模板 token 返回 null）。 */
    public static String templateKey(String token) {
        if (!isTemplateToken(token)) {
            return null;
        }
        String key = token.trim().substring(TEMPLATE_PREFIX.length()).trim();
        return key.isEmpty() ? null : key;
    }

    /**
     * token → 世界。
     *
     * @return 解析失败（世界没加载 / 模板没有存活房间 / token 为空）返回 {@code null}
     */
    public static World resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String t = token.trim();
        if (t.startsWith(TEMPLATE_PREFIX)) {
            String key = templateKey(t);
            return key == null ? null : RoomManager.worldOfTemplate(key);
        }
        return Bukkit.getWorld(t);
    }

    /**
     * 世界 → 存档用的 token。
     *
     * <p>如果这个世界是某个房间实例，写回 {@code tpl:<模板键>}（这样配置不会因为房间被删而失效）；
     * 否则写世界名（旧行为）。
     */
    public static String tokenOf(World world) {
        if (world == null) {
            return "";
        }
        String templateKey = RoomManager.templateKeyOfRoomWorld(world.getName());
        return templateKey == null ? world.getName() : TEMPLATE_PREFIX + templateKey;
    }

    /** 把一个坐标编码成 {@code token,x,y,z,yaw,pitch}。 */
    public static String encode(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return "";
        }
        return String.format(java.util.Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.1f,%.1f",
                tokenOf(loc.getWorld()), loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch());
    }

    /**
     * 解析 {@code token,x,y,z[,yaw,pitch]}。
     *
     * @return 世界解析不出来时返回 {@code null}（调用方按"未配置"处理）
     */
    public static Location parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 4) {
            return null;
        }
        World world = resolve(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            double x = Double.parseDouble(parts[1].trim());
            double y = Double.parseDouble(parts[2].trim());
            double z = Double.parseDouble(parts[3].trim());
            float yaw = parts.length > 4 ? Float.parseFloat(parts[4].trim()) : 0.0f;
            float pitch = parts.length > 5 ? Float.parseFloat(parts[5].trim()) : 0.0f;
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---------- 配置里的 Location 读写（R2） ----------
    //
    // 很多游戏的场地是直接用 Bukkit 原生 `Location` 序列化存的，YAML 里长这样：
    //   pos1:
    //     ==: org.bukkit.Location
    //     world_key: minecraft:world_mm     ← Paper 26.2；老版本是 world: xxx
    //     x: 1.0  y: 64.0  z: 2.0
    //
    // 一旦把 world_key 换成 tpl:<模板键>，Bukkit 的 Location.deserialize 找不到世界会**抛异常并刷 ERROR**
    // （不是返回 null）。所以这里自己读写，绕开它。

    /** 是否是 "可能是 Location 的配置键"（用子键判断，避免触发反序列化）。 */
    private static String rawWorldToken(ConfigurationSection sec, String key) {
        if (sec == null) {
            return null;
        }
        String token = sec.getString(key + ".world_key");
        if (token == null) {
            token = sec.getString(key + ".world");
        }
        return token;
    }

    /**
     * 读一个坐标；支持 {@code tpl:} token。
     *
     * @return 读不出来 / 模板没有存活房间 → {@code null}（上层按"未配置"处理）
     */
    public static Location readLocation(ConfigurationSection sec, String key) {
        if (sec == null) {
            return null;
        }
        // 注意：**不能**用 sec.isConfigurationSection(key) 作为前置条件 ——
        // 坐标在 YAML 里是带 `==: org.bukkit.Location` 的序列化 Map，
        // 不同 Bukkit 版本/加载方式下不一定被当成 section，加了会误判成"没配置"。
        String token = rawWorldToken(sec, key);
        if (token != null && isTemplateToken(token)) {
            World world = resolve(token);
            if (world == null) {
                return null;   // 模板还没有存活房间
            }
            return new Location(world,
                    sec.getDouble(key + ".x"), sec.getDouble(key + ".y"), sec.getDouble(key + ".z"),
                    (float) sec.getDouble(key + ".yaw"), (float) sec.getDouble(key + ".pitch"));
        }
        // 普通世界名：交给 Bukkit 正常反序列化
        Object raw = sec.get(key);
        return raw instanceof Location loc ? loc : null;
    }

    /**
     * 写一个坐标。
     *
     * <p>房间实例世界会写成 {@code tpl:<模板键>}；**{@code loc == null} 时什么都不做**
     * —— 绝不能把磁盘上已有的坐标擦掉（"读不出来"必须表现为"不动它"）。
     */
    public static void writeLocation(ConfigurationSection sec, String key, Location loc) {
        if (sec == null || loc == null) {
            return;
        }
        sec.set(key, loc);
        if (loc.getWorld() == null) {
            return;
        }
        String token = tokenOf(loc.getWorld());
        if (token.equals(loc.getWorld().getName())) {
            return;   // 普通世界，照 Bukkit 默认写法即可
        }
        // 房间实例：把世界字段换成 token（哪个字段存在就改哪个）
        if (sec.isSet(key + ".world_key")) {
            sec.set(key + ".world_key", token);
        } else {
            sec.set(key + ".world", token);
        }
    }

    // ---------- 配置段安全写入（R2 单一写者） ----------

    /**
     * 取一个配置段；**不存在才创建**。
     *
     * <p>⚠️ 直接用 {@code ConfigurationSection#createSection} 是**危险的**：
     * 它是"新建 + 覆盖"，不是"获取或创建"。用它保存场地时，会把磁盘上已有的键整段清掉
     * —— 实测把 4 个场地的坐标写成了 `{}`（`MurderMystery` 就这么丢过一次数据）。
     *
     * <p>凡是"保存时遍历内存里的场地，逐个写回配置"的地方，都应该走这个方法。
     */
    public static ConfigurationSection getOrCreate(ConfigurationSection parent, String path) {
        ConfigurationSection sec = parent.getConfigurationSection(path);
        return sec != null ? sec : parent.createSection(path);
    }

    /** 同上，但入参是插件的 config 根。 */
    public static ConfigurationSection getOrCreate(FileConfiguration cfg, String path) {
        return getOrCreate((ConfigurationSection) cfg, path);
    }
}
