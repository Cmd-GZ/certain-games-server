package com.skypit;

import com.hub.session.EndReason;

import com.hub.api.HubAPI;
import com.hub.data.PlayerStats;
import com.hub.util.Chat;
import com.hub.util.LiveSidebar;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 天坑核心管理：战斗区域、自动装备、PvP 会话、击杀统计、浮空文字排行榜。
 * 玩法：OP 圈一块战斗区域 → 玩家走进区域自动发放装备并开启 PvP →
 * 击杀数永久记录 → /skypit top 生成浮空文字展示前十名。
 */
public class SkyPitManager {

    /**
     * 框架会话（特殊形态：天坑没有轮次，进区域即入局、离开/死亡即出局）。
     * 注意：本类里已有一个 {@code pitStates}（玩家 → 存档状态），这里**已改名**。
     */
    private SkyPitSession session;

    private SkyPitSession ensureSession() {
        if (session == null || session.state().isFinal()) {
            session = new SkyPitSession();
        }
        return session;
    }


    public static final int KIT_GUI_SIZE = 54;
    public static final int BTN_SAVE = 41;
    public static final int BTN_CLEAR = 42;
    public static final int BTN_CLOSE = 43;

    /** 排行榜显示条数。 */
    public static final int TOP_SIZE = 10;
    /**
     * 排行榜背景色（ARGB）。A=0 全透明、A=255 不透明；
     * 想更透明就把 90 调小（调大更不透明），改完要 /skypit top refresh 才作用于已有实体。
     */
    public static final Color LB_BACKGROUND = Color.fromARGB(90, 20, 20, 35);

    private final SkyPitPlugin plugin;
    private final LiveSidebar liveSidebar;

    // 战斗区域两个对角
    private Location pos1;
    private Location pos2;

    // 入口 / 死亡复活点（均可选）
    private Location entry;
    private Location respawnPoint;

    // 战斗装备（进入天坑自动发放）
    private Kit kit = new Kit();

    // 正在天坑内战斗的玩家（UUID -> 进入前的状态）
    private final Map<UUID, SavedState> pitStates = new LinkedHashMap<>();

    // 击杀数据
    private FileConfiguration data;

    // 浮空文字排行榜
    private final List<LeaderBoard> leaderboards = new ArrayList<>();

    public SkyPitManager(SkyPitPlugin plugin) {
        this.plugin = plugin;
        this.liveSidebar = new LiveSidebar(plugin);
    }

    // ---------- 加载 / 保存 ----------

    public void load() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateLiveBoard, 40L, 40L);
        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection r = cfg.getConfigurationSection("region");
        if (r != null) {
            String w = r.getString("world", "");
            if (!w.isEmpty() && com.hub.world.Worlds.resolve(w) != null) {
                World world = com.hub.world.Worlds.resolve(w);
                pos1 = new Location(world, r.getDouble("x1"), r.getDouble("y1"), r.getDouble("z1"));
                pos2 = new Location(world, r.getDouble("x2"), r.getDouble("y2"), r.getDouble("z2"));
            }
        }
        entry = readLoc(cfg.getConfigurationSection("entry"));
        respawnPoint = readLoc(cfg.getConfigurationSection("spawn"));
        ConfigurationSection ks = cfg.getConfigurationSection("kit");
        if (ks != null) {
            kit = Kit.load(ks);
        }

        // 玩家记录（唯一文件 plugins/MiniGameLib/players.yml，命名空间 skypit）
        data = PlayerStats.load("skypit", new File(plugin.getDataFolder(), "data.yml"));

        publishRegion(); // 声明地盘（I2）—— 必须在读完全部配置之后
        leaderboards.clear(); // 重载时先清空，否则同一坐标会重复生成浮空文字实体
        for (Map<?, ?> raw : cfg.getMapList("leaderboards")) {
            Location loc = readLocMap(raw);
            if (loc == null) {
                continue;
            }
            String idStr = raw.get("entity") == null ? null : String.valueOf(raw.get("entity"));
            UUID id = null;
            if (idStr != null && !idStr.isEmpty()) {
                try {
                    id = UUID.fromString(idStr);
                } catch (IllegalArgumentException ignored) {
                }
            }
            leaderboards.add(new LeaderBoard(loc, id));
        }

        // 延迟刷新排行榜（等世界与区块就绪）
        Bukkit.getScheduler().runTaskLater(plugin, this::refreshLeaderboards, 40L);

        plugin.getLogger().info("天坑插件已加载：区域" + (hasRegion() ? "已配置" : "未配置")
                + "，装备" + (kit.isEmpty() ? "未配置" : "已配置")
                + "，击杀记录 " + totalPlayers() + " 人，排行榜 " + leaderboards.size() + " 个。");
    }

    public void onDisable() {
        for (UUID id : new ArrayList<>(pitStates.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                exitPit(p, false);
            } else {
                pitStates.remove(id);
            }
        }
    }

    public void saveAll() {
        // R2 单一写者（docs/框架不变量.md）：先把磁盘上的当前值读进内存，再只重写自己负责的段。
        // 否则关服时（onDisable -> saveAll -> saveConfig）会用内存里的旧值整份覆盖 config.yml：
        // 线上手改了别的键，传上去又被旧值写回去，表现为"改了配置没生效"（和 BuildRun 同一个坑）。
        plugin.reloadConfig();

        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection r = com.hub.world.Worlds.getOrCreate(cfg, "region");
        if (pos1 != null) {
            r.set("world", com.hub.world.Worlds.tokenOf(pos1.getWorld()));   // R2：房间实例 → tpl:模板键
            r.set("x1", pos1.getX());
            r.set("y1", pos1.getY());
            r.set("z1", pos1.getZ());
            r.set("x2", pos2 == null ? pos1.getX() : pos2.getX());
            r.set("y2", pos2 == null ? pos1.getY() : pos2.getY());
            r.set("z2", pos2 == null ? pos1.getZ() : pos2.getZ());
        }
        writeLoc(cfg.createSection("entry"), entry);
        writeLoc(cfg.createSection("spawn"), respawnPoint);
        kit.save(cfg.createSection("kit"));
        List<Map<String, Object>> lb = new ArrayList<>();
        for (LeaderBoard b : leaderboards) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", com.hub.world.Worlds.tokenOf(b.loc.getWorld()));
            m.put("x", b.loc.getX());
            m.put("y", b.loc.getY());
            m.put("z", b.loc.getZ());
            m.put("yaw", (double) b.loc.getYaw());
            m.put("pitch", (double) b.loc.getPitch());
            m.put("entity", b.entityId == null ? "" : b.entityId.toString());
            lb.add(m);
        }
        cfg.set("leaderboards", lb);
        plugin.saveConfig();
        saveData();
        publishRegion(); // 区域可能刚被改过，重新声明
    }

    private void saveData() {
        try {
            PlayerStats.save("skypit", data);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存击杀数据失败: " + ex.getMessage());
        }
    }

    public void reload() {
        plugin.reloadConfig();
        load();
    }

    // ---------- 区域 / 点位 ----------

    public boolean hasRegion() {
        return pos1 != null && pos2 != null && pos1.getWorld() != null && pos1.getWorld().equals(pos2.getWorld());
    }

    public boolean hasKitConfigured() {
        return !kit.isEmpty();
    }

    /**
     * 把自己的地盘声明给框架（{@code docs/框架不变量.md} 的 I2）。
     *
     * <p>天坑是「主城世界里的一个 AABB」，所以声明粒度就是区域，不是世界 ——
     * 这样主城的公共区仍然归 HubCore 管，天坑这块才归 SkyPit 管。
     *
     * <p>调用时机：{@link #load()} 之后、{@link #saveAll()} 之后（改完区域要重新声明），
     * 以及 {@code /skypit} 的 reload。区域没配好时自动撤销旧声明。
     */
    public void publishRegion() {
        try {
            com.hub.region.RegionRegistry.unregisterGame("skypit");
            if (!hasRegion()) {
                return;
            }
            com.hub.region.RegionRegistry.register(
                    com.hub.region.ArenaRegion.box("skypit", "", pos1, pos2));
        } catch (RuntimeException ex) {
            com.hub.diagnostics.Failures.recordAndWarn("SkyPit.声明区域", ex);
        }
    }

    /**
     * 这个世界是不是「天坑所在的世界」。
     * <p>
     * 用途：天坑只在自己的世界里对 PvP 表态，别的地方（world_duel / world_lp / world_mm …）
     * 一律不插手。依据 {@code server/docs/框架不变量.md} 的 I2（区域归属）
     * 与 I3（事件裁决唯一）：天坑的地盘是「这个世界的这块 AABB」，不是整台服务器。
     *
     * @param world 待判定的世界，可为 null
     * @return 区域已配置且世界一致时为 true；未配置区域时永远 false（不做任何拦截）
     */
    public boolean isPitWorld(org.bukkit.World world) {
        return hasRegion() && world != null && world.equals(pos1.getWorld());
    }

    public boolean contains(Location loc) {
        if (!hasRegion() || loc == null || loc.getWorld() == null) {
            return false;
        }
        if (!loc.getWorld().equals(pos1.getWorld())) {
            return false;
        }
        double x1 = Math.min(pos1.getX(), pos2.getX()), x2 = Math.max(pos1.getX(), pos2.getX());
        double y1 = Math.min(pos1.getY(), pos2.getY()), y2 = Math.max(pos1.getY(), pos2.getY());
        double z1 = Math.min(pos1.getZ(), pos2.getZ()), z2 = Math.max(pos1.getZ(), pos2.getZ());
        return loc.getX() >= x1 && loc.getX() <= x2
                && loc.getY() >= y1 && loc.getY() <= y2
                && loc.getZ() >= z1 && loc.getZ() <= z2;
    }

    public void setPos1(Player p) {
        pos1 = p.getLocation().clone();
        Chat.send(p, "&a已设置天坑区域角1：&f" + shortLoc(pos1)
                + (pos2 != null ? "（区域已定，/skypit save 保存）" : "（接着用 /skypit region pos2 定对角）"));
    }

    public void setPos2(Player p) {
        pos2 = p.getLocation().clone();
        Chat.send(p, "&a已设置天坑区域角2：&f" + shortLoc(pos2)
                + (pos1 != null ? "（区域已定，/skypit save 保存）" : "（还差角1）"));
    }

    public void setEntry(Player p) {
        entry = p.getLocation().clone();
        Chat.send(p, "&a已设置天坑入口：&f" + shortLoc(entry) + "（/skypit warp 传送至此）");
    }

    public void setRespawnPoint(Player p) {
        respawnPoint = p.getLocation().clone();
        Chat.send(p, "&a已设置天坑死亡复活点：&f" + shortLoc(respawnPoint));
    }

    public String regionInfo() {
        if (!hasRegion()) {
            return "&c未配置战斗区域（OP 用 /skypit region pos1 / pos2 站在对角设置）";
        }
        return "&7战斗区域：&f" + shortLoc(pos1) + " &7-> &f" + shortLoc(pos2)
                + " &7（" + (int) (Math.abs(pos2.getX() - pos1.getX()) + 1) + "x"
                + (int) (Math.abs(pos2.getY() - pos1.getY()) + 1) + "x"
                + (int) (Math.abs(pos2.getZ() - pos1.getZ()) + 1) + "）";
    }

    public Location getEntry() {
        return entry;
    }

    public void warp(Player p) {
        if (entry == null) {
            Chat.send(p, "&c尚未设置天坑入口（OP 用 /skypit set entry 站在入口处设置）。");
            return;
        }
        p.teleport(entry);
        Chat.send(p, "&a你已传送至天坑入口，走进战斗区域即可自动装备参战！");
    }

    private String shortLoc(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
    }

    private Location readLoc(ConfigurationSection sec) {
        if (sec == null) {
            return null;
        }
        String w = sec.getString("world", "");
        if (w.isEmpty() || com.hub.world.Worlds.resolve(w) == null) {
            return null;
        }
        return new Location(com.hub.world.Worlds.resolve(w), sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"),
                (float) sec.getDouble("yaw"), (float) sec.getDouble("pitch"));
    }

    private Location readLocMap(Map<?, ?> m) {
        Object wObj = m.get("world");
        if (wObj == null || Bukkit.getWorld(String.valueOf(wObj)) == null) {
            return null;
        }
        Object xObj = m.get("x"), yObj = m.get("y"), zObj = m.get("z");
        Object yawObj = m.get("yaw"), pitchObj = m.get("pitch");
        if (xObj == null || yObj == null || zObj == null) {
            return null;
        }
        double yaw = yawObj == null ? 0 : Double.parseDouble(String.valueOf(yawObj));
        double pitch = pitchObj == null ? 0 : Double.parseDouble(String.valueOf(pitchObj));
        return new Location(Bukkit.getWorld(String.valueOf(wObj)),
                Double.parseDouble(String.valueOf(xObj)),
                Double.parseDouble(String.valueOf(yObj)),
                Double.parseDouble(String.valueOf(zObj)),
                (float) yaw, (float) pitch);
    }

    private void writeLoc(ConfigurationSection sec, Location loc) {
        if (loc == null) {
            sec.set("world", "");
            sec.set("x", 0.0);
            sec.set("y", 0.0);
            sec.set("z", 0.0);
            sec.set("yaw", 0.0);
            sec.set("pitch", 0.0);
            return;
        }
        sec.set("world", loc.getWorld().getName());
        sec.set("x", loc.getX());
        sec.set("y", loc.getY());
        sec.set("z", loc.getZ());
        sec.set("yaw", (double) loc.getYaw());
        sec.set("pitch", (double) loc.getPitch());
    }

    // ---------- 天坑会话（进/出/死亡/重生/退出） ----------

    /** 进入前的玩家状态快照。 */
    private static class SavedState {
        final ItemStack[] contents; // 36 格物品栏
        final ItemStack[] armor;    // 4 件盔甲
        final ItemStack offhand;
        final GameMode mode;

        SavedState(Player p) {
            PlayerInventory inv = p.getInventory();
            ItemStack[] all = inv.getContents();
            if (all.length == 41) { // Paper 1.21：contents 含盔甲与副手
                contents = Arrays.copyOfRange(all, 0, 36);
                armor = Arrays.copyOfRange(all, 36, 40);
                offhand = all[40];
            } else {
                contents = all.clone();
                armor = inv.getArmorContents().clone();
                offhand = inv.getItemInOffHand();
            }
            mode = p.getGameMode();
        }

        void apply(Player p) {
            PlayerInventory inv = p.getInventory();
            inv.clear();
            for (int i = 0; i < contents.length; i++) {
                if (contents[i] != null) {
                    inv.setItem(i, contents[i].clone());
                }
            }
            inv.setArmorContents(armor);
            inv.setItemInOffHand(offhand == null ? null : offhand.clone());
            p.setGameMode(mode);
        }
    }

    public boolean inPit(UUID id) {
        return pitStates.containsKey(id);
    }

    public int pitCount() {
        return pitStates.size();
    }

    /** 是否参与天坑战斗（创造/旁观不参与，方便 OP 建设）。 */
    public boolean isPvpEligible(Player p) {
        GameMode m = p.getGameMode();
        return m != GameMode.CREATIVE && m != GameMode.SPECTATOR;
    }

    /**
     * 玩家进入区域：保存状态、发放装备、开启 PvP、上报 HubCore。
     * <p>**不做全服播报**（用户要求：天坑不在公屏刷"加入了游戏"）——走进区域只给自己发提示，
     * 进出坑很频繁，全服播报会刷屏；击杀播报仍在 {@code handleDeath} 里。
     */
    public void enterPit(Player p) {
        UUID id = p.getUniqueId();
        if (pitStates.containsKey(id) || !isPvpEligible(p) || !hasRegion()) {
            return;
        }
        pitStates.put(id, new SavedState(p));
        // 框架会话：进区域即入局（没有等待阶段，第一个进来就直接 RUNNING）
        SkyPitSession gs = ensureSession();
        if (gs.join(p)) {
            if (gs.state() == com.hub.session.SessionState.WAITING) {
                gs.start();
            }
        } else {
            plugin.getLogger().info("[SkyPit] " + p.getName() + " 已在别的对局里，天坑这次不计入会话");
        }
        if (!kit.isEmpty()) {
            kit.apply(p);
        }
        p.setHealth(20);
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        HubAPI.setPlaying(id, "天坑");
        HubAPI.setArenaSpectator("天坑", "", getEntry());
        Chat.send(p, "&a您已进入天坑");
    }

    /** 玩家离开区域：归还状态、关闭 PvP 上报。teleport=true 时传送回入口/主城。 */
    public void exitPit(Player p, boolean teleport) {
        SavedState st = pitStates.remove(p.getUniqueId());
        if (st == null) {
            return;
        }
        if (session != null && !session.state().isFinal()) {
            session.leave(p);   // 最后一个人走掉时框架会自动 end(ABORT)
        }
        st.apply(p);
        HubAPI.clearGame(p.getUniqueId());
        if (teleport) {
            Location target = entry != null ? entry : HubAPI.getLobby();
            if (target != null) {
                p.teleport(target);
            }
        }
        Chat.send(p, "&7您已离开天坑");
    }

    /** 只发给当前在天坑里的玩家，大厅和其他世界看不到局内播报。 */
    private void broadcastPit(String message) {
        for (UUID id : new ArrayList<>(pitStates.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Chat.sendTitled(p, message);
            }
        }
    }

    /** 死亡：只结算击杀，背包由重生时归还（不掉落）。 */
    public void handleDeath(Player victim) {
        if (!pitStates.containsKey(victim.getUniqueId())) {
            return;
        }
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())
                && pitStates.containsKey(killer.getUniqueId())) {
            int kills = recordKill(killer);
            broadcastPit("&e" + victim.getName() + " &7在天坑中被 &c" + killer.getName()
                    + " &7击杀！&e" + killer.getName() + "&7 累计击杀 &e" + kills + "&7 人");
        } else {
            broadcastPit("&e" + victim.getName() + " &7在天坑中阵亡了。");
        }
        refreshLeaderboards();
    }

    /** 重生：归还装备并传送到复活点（未设置则入口，再未设置则主城）。 */
    public void handleRespawn(Player p, Location respawnLoc) {
        if (!pitStates.containsKey(p.getUniqueId())) {
            return;
        }
        SavedState st = pitStates.remove(p.getUniqueId());
        st.apply(p);
        HubAPI.clearGame(p.getUniqueId());
        Location target = respawnPoint != null ? respawnPoint : (entry != null ? entry : HubAPI.getLobby());
        if (target != null) {
            respawnLoc.setWorld(target.getWorld());
            respawnLoc.setX(target.getX());
            respawnLoc.setY(target.getY());
            respawnLoc.setZ(target.getZ());
            respawnLoc.setYaw(target.getYaw());
            respawnLoc.setPitch(target.getPitch());
        }
        Chat.send(p, "&7你已复活，装备已归还。再次进入天坑即可继续战斗！");
        // 复活点若仍在区域内，直接重新入坑（属于恢复状态）
        if (hasRegion() && contains(respawnLoc) && isPvpEligible(p)) {
            enterPit(p);
        }
    }

    /** 退出服务器：归还背包（避免把天坑装备写进玩家存档）。 */
    public void handleQuit(Player p) {
        SavedState st = pitStates.remove(p.getUniqueId());
        if (st == null) {
            return;
        }
        st.apply(p);
        HubAPI.clearGame(p.getUniqueId());
    }

    /** 重新登录：若还站在区域内，自动再次入坑（恢复状态）。 */
    public void handleJoin(Player p) {
        if (contains(p.getLocation()) && isPvpEligible(p)) {
            enterPit(p);
        }
    }

    /** 切到创造/旁观：强制离场。 */
    public void handleGameModeChange(Player p, GameMode newMode) {
        if (inPit(p.getUniqueId()) && (newMode == GameMode.CREATIVE || newMode == GameMode.SPECTATOR)) {
            exitPit(p, false);
        }
    }

    // ---------- 击杀统计 ----------

    private void updateLiveBoard() {
        if (pitStates.isEmpty()) {
            liveSidebar.hide("skypit");
            return;
        }
        List<UUID> online = new ArrayList<>();
        for (UUID id : pitStates.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                online.add(id);
            }
        }
        online.sort(Comparator.comparingInt(this::getKills).reversed()
                .thenComparing(id -> Bukkit.getOfflinePlayer(id).getName(), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        List<LiveSidebar.Line> lines = new ArrayList<>();
        for (UUID id : online) {
            String name = Bukkit.getPlayer(id) != null ? Bukkit.getPlayer(id).getName() : "?";
            lines.add(new LiveSidebar.Line("§e" + name, getKills(id)));
        }
        liveSidebar.update("skypit", "&5&l天坑 · 击杀", online, lines);
    }

    public int recordKill(Player killer) {
        UUID id = killer.getUniqueId();
        int n = data.getInt("kills." + id, 0) + 1;
        data.set("kills." + id, n);
        data.set("names." + id, killer.getName());
        saveData();
        return n;
    }

    public int getKills(UUID id) {
        return data.getInt("kills." + id, 0);
    }

    public String nameOf(UUID id) {
        return data.getString("names." + id, "未知玩家");
    }

    /** 击杀前 N 名：{玩家名, 击杀数}，按击杀数降序。 */
    public List<String[]> topKills(int n) {
        ConfigurationSection sec = data.getConfigurationSection("kills");
        List<String[]> out = new ArrayList<>();
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            out.add(new String[]{nameOf(id), String.valueOf(sec.getInt(key))});
        }
        out.sort((x, y) -> Integer.compare(Integer.parseInt(y[1]), Integer.parseInt(x[1])));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    public int totalPlayers() {
        ConfigurationSection sec = data.getConfigurationSection("kills");
        return sec == null ? 0 : sec.getKeys(false).size();
    }

    public void resetKills() {
        data.set("kills", null);
        data.set("names", null);
        saveData();
        refreshLeaderboards();
    }

    public void resetKills(UUID id) {
        data.set("kills." + id, null);
        data.set("names." + id, null);
        saveData();
        refreshLeaderboards();
    }

    // ---------- 装备配置菜单（与 DuelArena 相同） ----------

    public void openKitEditor(Player op) {
        Inventory inv = Bukkit.createInventory(null, KIT_GUI_SIZE, Chat.color("&0天坑装备配置"));
        for (int i = 0; i < Kit.SLOTS; i++) {
            if (kit.getSlot(i) != null) {
                inv.setItem(i, kit.getSlot(i).clone());
            }
        }
        if (kit.getOffhand() != null) {
            inv.setItem(36, kit.getOffhand().clone());
        }
        inv.setItem(37, kit.getArmor(0));
        inv.setItem(38, kit.getArmor(1));
        inv.setItem(39, kit.getArmor(2));
        inv.setItem(40, kit.getArmor(3));
        inv.setItem(BTN_SAVE, btn(Material.EMERALD, "&a保存此装备配置", "&7点击保存给进入天坑的玩家"));
        inv.setItem(BTN_CLEAR, btn(Material.REDSTONE_BLOCK, "&c清空配置", "&7清空所有物品"));
        inv.setItem(BTN_CLOSE, btn(Material.BARRIER, "&7关闭窗口", "&7不保存直接关闭"));
        inv.setItem(44, btn(Material.ORANGE_STAINED_GLASS_PANE, "&6装备与副手位置说明", "&7（装饰格，不会保存）"));
        inv.setItem(45, btn(Material.ORANGE_STAINED_GLASS_PANE, "&6副手 = 本行第1格（36）", "&7装备：37头盔 38胸甲 39护腿 40靴子"));
        inv.setItem(46, btn(Material.YELLOW_STAINED_GLASS_PANE, "&e装备：37头盔 38胸甲 39护腿 40靴子", ""));
        inv.setItem(47, btn(Material.CYAN_STAINED_GLASS_PANE, "&b第1行 = 快捷栏（第1格=主手）", ""));
        inv.setItem(48, btn(Material.CYAN_STAINED_GLASS_PANE, "&b第2-4行 = 物品栏 27格", ""));
        inv.setItem(49, btn(Material.GRAY_STAINED_GLASS_PANE, "&7按钮：41保存 42清空 43关闭", ""));
        inv.setItem(50, btn(Material.LIME_STAINED_GLASS_PANE, "&a进入天坑的玩家共用此配置", ""));
        inv.setItem(51, btn(Material.GRAY_STAINED_GLASS_PANE, "", ""));
        inv.setItem(52, btn(Material.GRAY_STAINED_GLASS_PANE, "", ""));
        inv.setItem(53, btn(Material.GRAY_STAINED_GLASS_PANE, "", ""));
        op.openInventory(inv);
    }

    private ItemStack btn(Material m, String name, String lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Chat.noItalic(Chat.color(name)));
            meta.lore(List.of(Chat.color(lore)));
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 配置窗口点击：按钮操作（其余槽位自由摆放）。 */
    public void handleKitClick(Player p, Inventory inv, int slot) {
        if (slot > BTN_CLOSE) {
            return; // 装饰标签格
        }
        if (slot == BTN_SAVE) {
            saveKitFromGui(p, inv);
            return;
        }
        if (slot == BTN_CLEAR) {
            for (int i = 0; i < 41; i++) {
                inv.setItem(i, null);
            }
            Chat.send(p, "&a已清空窗口，可重新摆放物品。");
            return;
        }
        if (slot == BTN_CLOSE) {
            p.closeInventory();
            Chat.send(p, "&7已关闭（未保存）。");
        }
    }

    private void saveKitFromGui(Player p, Inventory inv) {
        Kit k = new Kit();
        for (int i = 0; i < Kit.SLOTS; i++) {
            k.setSlot(i, inv.getItem(i));
        }
        k.setOffhand(inv.getItem(36));
        k.setArmor(0, inv.getItem(37));
        k.setArmor(1, inv.getItem(38));
        k.setArmor(2, inv.getItem(39));
        k.setArmor(3, inv.getItem(40));
        kit = k;
        saveAll();
        p.closeInventory();
        Chat.send(p, "&a已保存天坑装备配置（" + (kit.isEmpty()
                ? "空配置：进入天坑不发放装备"
                : "含装备/物品：进入天坑自动发放") + "）。");
    }

    // ---------- 浮空文字排行榜 ----------

    /** 单个排行榜：位置 + 当前实体 UUID。 */
    private static class LeaderBoard {
        final Location loc;
        UUID entityId;

        LeaderBoard(Location loc, UUID entityId) {
            this.loc = loc;
            this.entityId = entityId;
        }
    }

    /** OP 在脚下生成浮空文字排行榜。 */
    public void placeLeaderboard(Player op) {
        Location loc = op.getLocation().clone();
        LeaderBoard lb = new LeaderBoard(loc, null);
        leaderboards.add(lb);
        lb.entityId = spawnDisplay(loc);
        saveAll();
        refreshLeaderboards();
        Chat.send(op, "&a已在 &f" + shortLoc(loc) + "&a 放置天坑击杀榜（前十名浮空文字），当前共 "
                + leaderboards.size() + " 个。");
    }

    /** 移除全部浮空排行榜。 */
    public void removeLeaderboards(Player op) {
        for (LeaderBoard lb : leaderboards) {
            Entity e = lb.entityId == null ? null : plugin.getServer().getEntity(lb.entityId);
            if (e != null) {
                e.remove();
            }
        }
        leaderboards.clear();
        saveAll();
        Chat.send(op, "&a已移除全部天坑击杀榜浮空文字。");
    }

    /** 刷新所有排行榜文字（区块未加载则跳过，防止生成重复实体）。 */
    public void refreshLeaderboards() {
        boolean changed = false;
        for (LeaderBoard lb : leaderboards) {
            if (!lb.loc.isChunkLoaded()) {
                continue;
            }
            Entity e = lb.entityId == null ? null : plugin.getServer().getEntity(lb.entityId);
            if (e == null) {
                lb.entityId = spawnDisplay(lb.loc);
                changed = true;
            } else if (e instanceof TextDisplay td) {
                // 已有实体也要重设背景，否则改了背景色刷新不生效
                applyLeaderboardStyle(td);
            }
        }
        if (changed) {
            saveAll();
        }
    }

    /** 排行榜所在区块加载时刷新（重启后实体文字可能为旧内容）。 */
    public void refreshLeaderboardsInChunk(int cx, int cz) {
        for (LeaderBoard lb : leaderboards) {
            if ((lb.loc.getBlockX() >> 4) == cx && (lb.loc.getBlockZ() >> 4) == cz) {
                refreshLeaderboards();
                return;
            }
        }
    }

    private UUID spawnDisplay(Location loc) {
        World w = loc.getWorld();
        if (w == null) {
            return null;
        }
        if (!loc.isChunkLoaded()) {
            loc.getChunk().load();
        }
        TextDisplay td = w.spawn(loc, TextDisplay.class);
        applyLeaderboardStyle(td);
        td.setBillboard(Display.Billboard.CENTER);
        td.setSeeThrough(true);
        td.setShadowed(true);
        td.setAlignment(TextDisplay.TextAlignment.CENTER);
        td.setLineWidth(600);
        td.setViewRange(80);
        td.setPersistent(true);
        td.setInvulnerable(true);
        td.setGravity(false);
        td.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f(1.8f, 1.8f, 1.8f), new Quaternionf()));
        return td.getUniqueId();
    }

    /** 文字 + 背景色（新生成和刷新已有实体共用，保证背景透明度一处生效）。 */
    private void applyLeaderboardStyle(TextDisplay td) {
        td.text(Chat.color(buildLeaderboardText()));
        td.setDefaultBackground(false);
        td.setBackgroundColor(LB_BACKGROUND);
    }

    private String buildLeaderboardText() {
        StringBuilder sb = new StringBuilder("§6§l—— 天坑击杀榜 ——\n");
        List<String[]> top = topKills(TOP_SIZE);
        if (top.isEmpty()) {
            sb.append("§8暂无击杀记录\n§7走进天坑战斗，赢取击杀！");
        } else {
            for (int i = 0; i < top.size(); i++) {
                String[] row = top.get(i);
                int rank = i + 1;
                String prefix = switch (i) {
                    case 0 -> "§e1§f.";
                    case 1, 2 -> "§7 " + rank + "§f.";
                    default -> (rank >= 10 ? "§7" : "§7 ") + rank + "§f.";
                };
                sb.append(prefix).append(" §f").append(row[0]).append(" §c").append(row[1]).append("§f 杀\n");
            }
        }
        return sb.toString().trim();
    }
}
