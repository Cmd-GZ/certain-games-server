package com.buildbattle.gtb.arena;

import com.buildbattle.gtb.GuessBuildPlugin;
import com.hub.api.HubAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多场地管理器：
 * - 每个场地有唯一名称，配置存于 arenas.<名称>，快照存于 arena-<名称>.snap；
 * - active（生效场地）：游戏实际使用的场地；
 * - editing（编辑目标）：木棍选区保存时写入的场地（/gtb arena edit 指定）。
 * - 兼容迁移旧版单场地配置（arena 段 + arena.snap）。
 */
public class ArenaManager {

    private final GuessBuildPlugin plugin;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    /** 未配置任何场地时的兜底空场地（isReady()=false，保证 getActive() 永不为 null）。 */
    private final Arena empty;

    private String activeName = null;
    private String editingName = null;

    public ArenaManager(GuessBuildPlugin plugin) {
        this.plugin = plugin;
        this.empty = new Arena(plugin, "");
    }

    // ---------- 加载 / 迁移 ----------

    public void loadAll() {
        arenas.clear();
        FileConfiguration config = plugin.getConfig();
        ConfigurationSection cs = config.getConfigurationSection("arenas");
        if ((cs == null || cs.getKeys(false).isEmpty()) && config.contains("arena")) {
            migrateLegacy();
            cs = config.getConfigurationSection("arenas");
        }
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                Arena a = new Arena(plugin, key);
                a.loadFromConfig(cs.getConfigurationSection(key));
                arenas.put(key, a);
            }
        }
        String active = config.getString("active-arena");
        if (active != null && arenas.containsKey(active) && arenas.get(active).isReady()) {
            activeName = active;
        } else {
            activeName = firstReadyName();
        }
        editingName = activeName;

        // 启动时打一行日志说明当前用哪个场地、快照读到了多少格，
        // 场地名与 arena-<名称>.snap 不匹配时能立刻看出来。
        Arena current = getActive();
        if (!current.isReady()) {
            plugin.getLogger().warning("没有可用的场地，请用 /gtb setup 选区保存。");
        } else if (current.getSnapshotSize() == 0) {
            plugin.getLogger().warning("生效场地「" + current.getName() + "」没有快照（缺少 arena-"
                    + current.getName() + ".snap），每轮只会重置默认地板。");
        } else {
            plugin.getLogger().info("生效场地「" + current.getName() + "」：世界 " + current.getWorld().getName()
                    + "，快照 " + current.getSnapshotSize() + " 格，自定地板 "
                    + (current.getFloor() == null ? "默认" : current.getFloor().name()) + "。");
        }
    }

    /** 旧版单场地配置（arena 段 + arena.snap）迁移为命名场地「默认场地」。 */
    private void migrateLegacy() {
        FileConfiguration config = plugin.getConfig();
        ConfigurationSection old = config.getConfigurationSection("arena");
        if (old != null) {
            String wname = old.getString("world", "");
            World w = wname.isEmpty() ? null : com.hub.world.Worlds.resolve(wname);
            List<Integer> p1 = old.getIntegerList("pos1");
            List<Integer> p2 = old.getIntegerList("pos2");
            if (w != null && p1.size() >= 3 && p2.size() >= 3 && volume(p1, p2) >= 16) {
                String name = "默认场地";
                config.set("arenas." + name + ".world", wname);
                config.set("arenas." + name + ".pos1", p1);
                config.set("arenas." + name + ".pos2", p2);
                List<Integer> sp = old.getIntegerList("spawn");
                if (sp.size() >= 3) {
                    config.set("arenas." + name + ".spawn", sp);
                }
                config.set("active-arena", name);
                File oldSnap = new File(plugin.getDataFolder(), "arena.snap");
                File newSnap = new File(plugin.getDataFolder(), "arena-" + name + ".snap");
                if (oldSnap.exists() && !newSnap.exists()) {
                    try {
                        Files.copy(oldSnap.toPath(), newSnap.toPath());
                    } catch (IOException e) {
                        plugin.getLogger().warning("迁移场地快照失败: " + e.getMessage());
                    }
                }
                plugin.getLogger().info("已把旧版单场地配置迁移到场地「" + name + "」。");
            } else {
                plugin.getLogger().info("旧版场地配置为空或无效，已忽略。");
            }
        }
        config.set("arena", null);
        plugin.saveConfig();
    }

    private long volume(List<Integer> p1, List<Integer> p2) {
        long dx = Math.abs(p1.get(0) - p2.get(0)) + 1L;
        long dy = Math.abs(p1.get(1) - p2.get(1)) + 1L;
        long dz = Math.abs(p1.get(2) - p2.get(2)) + 1L;
        return dx * dy * dz;
    }

    // ---------- 查询 ----------

    /** 生效场地（未配置时返回兜底空场地，永不返回 null）。 */
    public Arena getActive() {
        if (activeName != null) {
            Arena a = arenas.get(activeName);
            if (a != null) {
                return a;
            }
        }
        return empty;
    }

    public String getActiveName() {
        return activeName;
    }

    /** 当前编辑目标场地名（木棍选区保存时写入的对象；未设置时回退到生效场地）。 */
    public String getEditingName() {
        return editingName != null ? editingName : activeName;
    }

    public Arena getArena(String name) {
        return arenas.get(name);
    }

    public List<Arena> getAll() {
        return new ArrayList<>(arenas.values());
    }

    public boolean hasArena(String name) {
        return arenas.containsKey(name);
    }

    private String firstReadyName() {
        for (Map.Entry<String, Arena> e : arenas.entrySet()) {
            if (e.getValue().isReady()) {
                return e.getKey();
            }
        }
        return arenas.isEmpty() ? null : arenas.keySet().iterator().next();
    }

    // ---------- 管理操作 ----------

    /** 创建新场地（空，未设置）并设为编辑目标；名称无效或已存在时返回 null。 */
    public String createArena(String name) {
        if (!validName(name) || arenas.containsKey(name)) {
            return null;
        }
        arenas.put(name, new Arena(plugin, name));
        editingName = name;
        // 立即持久化一个空条目，重启后仍可见（world 留空 = 未设置）
        plugin.getConfig().set("arenas." + name + ".world", "");
        plugin.saveConfig();
        return name;
    }

    /** 重命名场地：配置段、快照文件、生效/编辑目标与中央菜单一并同步；对局中禁止由命令层检查。 */
    public boolean renameArena(String oldName, String newName) {
        if (oldName == null || oldName.equals(newName)) {
            return false;
        }
        if (!validName(newName) || arenas.containsKey(newName)) {
            return false;
        }
        Arena a = arenas.get(oldName);
        if (a == null) {
            return false;
        }
        // 配置段迁移（显式拷贝字段）
        ConfigurationSection oldSec = plugin.getConfig().getConfigurationSection("arenas." + oldName);
        if (oldSec != null) {
            ConfigurationSection newSec = plugin.getConfig().createSection("arenas." + newName);
            for (String key : oldSec.getKeys(false)) {
                newSec.set(key, oldSec.get(key));
            }
            plugin.getConfig().set("arenas." + oldName, null);
        }
        // 快照文件重命名
        File oldSnap = new File(plugin.getDataFolder(), "arena-" + oldName + ".snap");
        File newSnap = new File(plugin.getDataFolder(), "arena-" + newName + ".snap");
        if (oldSnap.exists()) {
            try {
                Files.move(oldSnap.toPath(), newSnap.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().warning("重命名场地快照失败：" + e.getMessage());
            }
        }
        arenas.remove(oldName);
        a.setName(newName);
        arenas.put(newName, a);
        if (oldName.equals(activeName)) {
            activeName = newName;
            plugin.getConfig().set("active-arena", newName);
        }
        if (oldName.equals(editingName)) {
            editingName = newName;
        }
        plugin.saveConfig();
        HubAPI.removeArena("猜猜乐", oldName); // 中央菜单移除旧名（新名由 reportArenaToHub 上报）
        return true;
    }

    /** 删除场地（配置段 + 快照文件）；不存在时返回 false。 */
    public boolean removeArena(String name) {
        Arena a = arenas.remove(name);
        if (a == null) {
            return false;
        }
        plugin.getConfig().set("arenas." + name, null);
        if (name.equals(activeName)) {
            activeName = firstReadyName();
            plugin.getConfig().set("active-arena", activeName);
        }
        if (name.equals(editingName)) {
            editingName = activeName;
        }
        File snap = new File(plugin.getDataFolder(), "arena-" + name + ".snap");
        if (snap.exists() && !snap.delete()) {
            plugin.getLogger().warning("删除场地快照文件失败：" + snap.getAbsolutePath());
        }
        plugin.saveConfig();
        plugin.spawnFloorNpc();
        HubAPI.removeArena("猜猜乐", name); // 中央菜单同步移除该场地
        return true;
    }

    /** 切换生效场地（游戏使用的场地）；目标不存在或未设置时返回 false。 */
    public boolean setActive(String name) {
        Arena a = arenas.get(name);
        if (a == null || !a.isReady()) {
            return false;
        }
        activeName = name;
        editingName = name;
        plugin.getConfig().set("active-arena", name);
        plugin.saveConfig();
        // 为新生效场地补齐屏障墙（延迟执行避免卡主线程），并移动地板商店 NPC
        Bukkit.getScheduler().runTask(plugin, a::ensureBarrierWalls);
        plugin.spawnFloorNpc();
        return true;
    }

    /** 设置编辑目标（后续 /gtb setup save 写入该场地）。 */
    public void setEditing(String name) {
        if (arenas.containsKey(name)) {
            editingName = name;
        }
    }

    /** 把木棍选区保存到编辑目标场地；无任何场地时自动创建「默认场地」。返回场地名，失败返回 null。 */
    public String saveSelection(Location pos1, Location pos2) {
        String target = editingName;
        if (target == null || !arenas.containsKey(target)) {
            target = (activeName != null && arenas.containsKey(activeName)) ? activeName : null;
        }
        if (target == null) {
            target = createArena("默认场地");
            if (target == null) {
                return null;
            }
        }
        Arena arena = arenas.get(target);
        if (!arena.setFrom(pos1, pos2)) {
            return null;
        }
        if (activeName == null || !arenas.containsKey(activeName)) {
            activeName = target;
            plugin.getConfig().set("active-arena", target);
            plugin.saveConfig();
        }
        return target;
    }

    /** 场地名校验：1~20 字符，仅中英文/数字/下划线/短横线（避免配置键与文件名问题）。 */
    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > 20) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }
}
