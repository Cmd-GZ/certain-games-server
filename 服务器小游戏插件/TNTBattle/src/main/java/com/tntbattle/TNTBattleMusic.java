package com.tntbattle;

import com.hub.util.Chat;
import org.bukkit.Bukkit;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TNTBattle 背景音乐：把世界里的红石音乐（音符盒）**录一次**，之后按 tick 逐人播放。
 *
 * <p>为什么不用运行时直接放红石机：音符盒是位置音效，远处队伍听不到；红石机一直转还会吃 TPS。
 * 录制后每个玩家用 {@link Player#playSound} 单独播放，音量一致、可循环、可随时停止。
 *
 * <p>录制：{@code /tntbattle music pos1|pos2} 圈出音符盒范围 → {@code music record} →
 * 启动红石机 → {@code music stop}。音符存在 {@code plugins/TNTBattle/music.yml}。
 *
 * <p>触发：对局中任一方存活人数 <= {@code music.trigger-team-alive}（默认 1）时开始循环，
 * 对局结束停止；听众是本局所有玩家（含已淘汰的旁观者）。
 */
public final class TNTBattleMusic {

    /** 单次录制最多保存的音符数，防止忘停把内存/文件撑爆。 */
    private static final int MAX_NOTES = 20000;
    private static final int DEFAULT_TAIL_TICKS = 20;
    private static final double DEFAULT_VOLUME = 1.0;

    private final TNTBattlePlugin plugin;
    private final TNTBattleManager manager;
    private final File file;

    private Location pos1;
    private Location pos2;
    private final List<NoteEntry> notes = new ArrayList<>();
    private final Map<Integer, List<NoteEntry>> byTick = new HashMap<>();
    private int lengthTicks;

    private boolean recording;
    private int recordFirstTick = -1;
    private BukkitTask recordGuard;

    private boolean playing;
    private boolean playLoop;
    private int playTick;
    private BukkitTask playTask;
    private Collection<UUID> playAudience;
    private float playVolume = (float) DEFAULT_VOLUME;
    private boolean warnedEmpty;
    /** 个人开关：默认关闭；等待室右键音符盒道具开启后，才给这个玩家播放。 */
    private final Set<UUID> enabled = new HashSet<>();

    /** 一个音符：(相对 tick, 乐器, 音高编号 0-24)。 */
    public record NoteEntry(int tick, Instrument instrument, int noteId) {
    }

    public TNTBattleMusic(TNTBattlePlugin plugin, TNTBattleManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.file = new File(plugin.getDataFolder(), "music.yml");
    }

    // ---------- 文件读写 ----------

    /** 读取 music.yml（插件启用 / /tntbattle reload 时调用）。 */
    public void load() {
        stopPlayback();
        notes.clear();
        byTick.clear();
        lengthTicks = 0;
        pos1 = null;
        pos2 = null;
        if (!file.isFile()) {
            warnedEmpty = false;
            plugin.getLogger().info("背景音乐：未录制（" + file.getName() + " 不存在）。");
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            pos1 = asLocation(yaml.get("pos1"));
            pos2 = asLocation(yaml.get("pos2"));
            for (String raw : yaml.getStringList("notes")) {
                NoteEntry entry = parseNote(raw);
                if (entry != null) {
                    notes.add(entry);
                }
            }
            notes.sort(Comparator.comparingInt(NoteEntry::tick));
            lengthTicks = Math.max(0, yaml.getInt("length-ticks", 0));
            if (lengthTicks <= 0 && !notes.isEmpty()) {
                lengthTicks = notes.get(notes.size() - 1).tick() + tailTicks();
            }
            lengthTicks = Math.max(notes.isEmpty() ? 0 : 1, lengthTicks);
            rebuildIndex();
            warnedEmpty = notes.isEmpty();
            plugin.getLogger().info("背景音乐：已载入 " + notes.size() + " 个音符，时长 "
                    + lengthTicks + " tick（" + (lengthTicks / 20.0) + " 秒）。");
        } catch (Exception ex) {
            notes.clear();
            byTick.clear();
            lengthTicks = 0;
            plugin.getLogger().warning("读取背景音乐失败：" + ex.getMessage());
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        if (pos1 != null) {
            yaml.set("pos1", pos1);
        }
        if (pos2 != null) {
            yaml.set("pos2", pos2);
        }
        yaml.set("length-ticks", lengthTicks);
        List<String> list = new ArrayList<>(notes.size());
        for (NoteEntry entry : notes) {
            list.add(entry.tick() + ";" + entry.instrument().name() + ";" + entry.noteId());
        }
        yaml.set("notes", list);
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存背景音乐失败：" + ex.getMessage());
        }
    }

    private NoteEntry parseNote(String raw) {
        String[] parts = raw.split(";");
        if (parts.length != 3) {
            return null;
        }
        try {
            int tick = Integer.parseInt(parts[0].trim());
            Instrument instrument = Instrument.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
            int note = Integer.parseInt(parts[2].trim());
            if (tick < 0 || note < 0 || note > 24) {
                return null;
            }
            return new NoteEntry(tick, instrument, note);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void rebuildIndex() {
        byTick.clear();
        for (NoteEntry entry : notes) {
            byTick.computeIfAbsent(entry.tick(), key -> new ArrayList<>()).add(entry);
        }
    }

    // ---------- 录制 ----------

    public void setCorner(int corner, Location location, CommandSender sender) {
        Location target = location.getBlock().getLocation();
        if (corner == 1) {
            if (pos2 != null && pos2.getWorld() != null && !pos2.getWorld().equals(target.getWorld())) {
                Chat.send(sender, "&c两个角必须在同一个世界。");
                return;
            }
            pos1 = target;
        } else {
            if (pos1 != null && pos1.getWorld() != null && !pos1.getWorld().equals(target.getWorld())) {
                Chat.send(sender, "&c两个角必须在同一个世界。");
                return;
            }
            pos2 = target;
        }
        save();
        Chat.send(sender, "&a背景音乐录制范围角 " + corner + " 已设为 &e" + shortLoc(target) + "&a（已保存）。");
        if (regionReady()) {
            Chat.send(sender, "&7范围：" + regionText());
        }
    }

    public boolean startRecording(CommandSender sender) {
        if (recording) {
            Chat.send(sender, "&c正在录制中，先执行 &f/tntbattle music stop&c。");
            return false;
        }
        if (!regionReady()) {
            Chat.send(sender, "&c先站在红石音乐装置的两个对角执行 &f/tntbattle music pos1&c / &fmusic pos2&c。");
            return false;
        }
        stopPlayback();
        notes.clear();
        byTick.clear();
        lengthTicks = 0;
        recording = true;
        recordFirstTick = -1;
        // 只在录制期间注册监听器（框架的录制通道）
        com.hub.event.NoteRecorder.start(plugin, this::onNote);
        int seconds = Math.max(10, plugin.getConfig().getInt("music.max-record-seconds", 600));
        recordGuard = Bukkit.getScheduler().runTaskLater(plugin,
                () -> finishRecording(Bukkit.getConsoleSender(), true), seconds * 20L);
        Chat.send(sender, "&a开始录制：现在启动红石机，录完执行 &f/tntbattle music stop&a。");
        Chat.send(sender, "&7只记录范围 " + regionText() + " 内的音符盒；"
                + seconds + " 秒后会自动停止并保存。");
        return true;
    }

    public void finishRecording(CommandSender sender, boolean auto) {
        if (!recording) {
            Chat.send(sender, "&7当前没有在录制。");
            return;
        }
        recording = false;
        com.hub.event.NoteRecorder.stop();
        if (recordGuard != null) {
            recordGuard.cancel();
            recordGuard = null;
        }
        if (notes.isEmpty()) {
            Chat.send(sender, "&c没有录到任何音符：检查范围是否圈对、红石机是否启动。");
            load(); // 恢复上一次录制，别把已有音乐弄丢
            return;
        }
        notes.sort(Comparator.comparingInt(NoteEntry::tick));
        lengthTicks = Math.max(1, notes.get(notes.size() - 1).tick() + tailTicks());
        rebuildIndex();
        save();
        warnedEmpty = false;
        String prefix = auto ? "&e录制已自动停止：&a" : "&a录制完成：&e";
        Chat.send(sender, prefix + notes.size() + " &a个音符，时长 &e" + lengthTicks
                + " tick（" + String.format(Locale.ROOT, "%.1f", lengthTicks / 20.0) + " 秒）。已保存到 &fmusic.yml&a。");
        Chat.send(sender, "&7记得停掉红石机，否则它会和插件播放重叠。");
    }

    /**
     * 录到一个音符（**由框架的 {@link com.hub.event.NoteRecorder} 在录制期间回调**）。
     *
     * <p>以前这里是常驻的 `@EventHandler`，但它的唯一作用是录制 —— 与"这一局归谁"无关，
     * 所以搬进框架的录制通道：**只有 `music record` 期间才会注册监听器**。
     */
    private void onNote(NotePlayEvent event) {
        if (!recording) {
            return;
        }
        Block block = event.getBlock();
        if (!inRegion(block.getLocation())) {
            return;
        }
        int now = Bukkit.getCurrentTick();
        if (recordFirstTick < 0) {
            recordFirstTick = now;
        }
        int tick = now - recordFirstTick;
        if (tick < 0) {
            return;
        }
        notes.add(new NoteEntry(tick, event.getInstrument(), event.getNote().getId()));
        if (notes.size() >= MAX_NOTES) {
            finishRecording(Bukkit.getConsoleSender(), true);
        }
        }

    private boolean regionReady() {
        return pos1 != null && pos2 != null && pos1.getWorld() != null && pos2.getWorld() != null
                && pos1.getWorld().equals(pos2.getWorld());
    }

    private boolean inRegion(Location location) {
        if (!regionReady() || location.getWorld() == null || !location.getWorld().equals(pos1.getWorld())) {
            return false;
        }
        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
        int maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
        return location.getBlockX() >= minX && location.getBlockX() <= maxX
                && location.getBlockY() >= minY && location.getBlockY() <= maxY
                && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
    }

    // ---------- 播放 ----------

    /** 对局触发：对本局所有玩家循环播放。已经在播就不重启。 */
    public boolean startLoop() {
        if (!plugin.getConfig().getBoolean("music.enabled", true)) {
            return false;
        }
        if (playing) {
            return true;
        }
        if (notes.isEmpty()) {
            if (!warnedEmpty) {
                plugin.getLogger().warning("背景音乐已触发，但 music.yml 里没有音符（先用 /tntbattle music 录制）。");
                warnedEmpty = true;
            }
            return false;
        }
        if (enabled.isEmpty()) {
            return false; // 默认关闭：没有玩家用音符盒开启，就不用起播放任务
        }
        stopPlayback();
        playing = true;
        playLoop = plugin.getConfig().getBoolean("music.loop", true);
        playTick = 0;
        playAudience = null;
        playVolume = volume();
        playTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPlayback, 1L, 1L);
        return true;
    }

    /** 管理员试听：只发给自己，播一遍不循环。 */
    public void playTest(Player player) {
        if (notes.isEmpty()) {
            Chat.send(player, "&c还没有录制背景音乐，先 /tntbattle music record。");
            return;
        }
        stopPlayback();
        playing = true;
        playLoop = false;
        playTick = 0;
        playAudience = List.of(player.getUniqueId());
        playVolume = volume();
        playTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPlayback, 1L, 1L);
        Chat.send(player, "&a试听背景音乐：&e" + notes.size() + " &a个音符，&e"
                + String.format(Locale.ROOT, "%.1f", lengthTicks / 20.0) + " 秒&a（不循环）。");
    }

    private void tickPlayback() {
        if (!playing) {
            return;
        }
        if (lengthTicks <= 0) {
            stopPlayback();
            return;
        }
        Collection<UUID> audience = playAudience != null ? playAudience : manager.gamePlayerIds();
        if (audience.isEmpty()) {
            stopPlayback();
            return;
        }
        List<NoteEntry> due = byTick.get(playTick);
        if (due != null && !due.isEmpty()) {
            for (UUID id : audience) {
                if (!enabled.contains(id)) {
                    continue;
                }
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline()) {
                    continue;
                }
                for (NoteEntry entry : due) {
                    Sound sound = soundOf(entry.instrument());
                    float pitch = (float) Math.pow(2.0, (entry.noteId() - 12) / 12.0);
                    player.playSound(player.getLocation(), sound, SoundCategory.RECORDS, playVolume, pitch);
                }
            }
        }
        playTick++;
        if (playTick >= lengthTicks) {
            if (playLoop) {
                playTick = 0;
            } else {
                stopPlayback();
            }
        }
    }

    private Sound soundOf(Instrument instrument) {
        try {
            Sound sound = instrument.getSound();
            if (sound != null) {
                return sound;
            }
        } catch (RuntimeException ignored) {
            // 自定义头颅等乐器可能没有对应声音，退回竖琴
        }
        return Sound.BLOCK_NOTE_BLOCK_HARP;
    }

    public void stopPlayback() {
        playing = false;
        if (playTask != null) {
            playTask.cancel();
            playTask = null;
        }
        playAudience = null;
    }

    /** 该玩家是否在等待室开启了个人背景音乐（默认关闭）。 */
    public boolean isEnabled(UUID id) {
        return enabled.contains(id);
    }

    /** 切换个人开关；返回 true 表示现在是开启。 */
    public boolean toggleEnabled(UUID id) {
        if (enabled.remove(id)) {
            return false;
        }
        enabled.add(id);
        return true;
    }

    /** 加入/退出时重置为默认（默认关闭，见 music.default-on）。 */
    public void setEnabled(UUID id, boolean on) {
        if (on) {
            enabled.add(id);
        } else {
            enabled.remove(id);
        }
    }

    public void clearEnabled() {
        enabled.clear();
    }

    public void shutdown() {
        stopPlayback();
        if (recording) {
            finishRecording(Bukkit.getConsoleSender(), true);
        }
    }

    // ---------- 管理命令辅助 ----------

    public void clear(CommandSender sender) {
        notes.clear();
        byTick.clear();
        lengthTicks = 0;
        save();
        Chat.send(sender, "&a已清空背景音乐（范围保留）。可以重新 /tntbattle music record。");
    }

    public void info(CommandSender sender) {
        Chat.send(sender, "&c===== TNT 背景音乐 =====");
        Chat.send(sender, "&7enabled=&e" + plugin.getConfig().getBoolean("music.enabled", true)
                + "&7 loop=&e" + plugin.getConfig().getBoolean("music.loop", true)
                + "&7 default-on=&e" + plugin.getConfig().getBoolean("music.default-on", false)
                + "&7 volume=&e" + volume()
                + "&7 触发人数=&e" + triggerAlive());
        Chat.send(sender, "&7音符数=&e" + notes.size()
                + "&7 时长=&e" + lengthTicks + " tick（"
                + String.format(Locale.ROOT, "%.1f", lengthTicks / 20.0) + " 秒）");
        Chat.send(sender, "&7录制范围=&e" + (regionReady() ? regionText() : "&c未设置（music pos1 / pos2）"));
        Chat.send(sender, "&7状态=&e" + (recording ? "录制中" : playing ? "播放中" : "空闲")
                + "&7 文件=&f" + file.getPath());
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isRecording() {
        return recording;
    }

    public int triggerAlive() {
        return Math.max(1, plugin.getConfig().getInt("music.trigger-team-alive", 1));
    }

    private int tailTicks() {
        return Math.max(0, plugin.getConfig().getInt("music.tail-ticks", DEFAULT_TAIL_TICKS));
    }

    private float volume() {
        double configured = plugin.getConfig().getDouble("music.volume", DEFAULT_VOLUME);
        return (float) Math.max(0.05, Math.min(4.0, configured));
    }

    private Location asLocation(Object raw) {
        return raw instanceof Location location ? location : null;
    }

    private String regionText() {
        if (!regionReady()) {
            return "未设置";
        }
        return pos1.getWorld().getName() + " "
                + Math.min(pos1.getBlockX(), pos2.getBlockX()) + ".." + Math.max(pos1.getBlockX(), pos2.getBlockX()) + " / "
                + Math.min(pos1.getBlockY(), pos2.getBlockY()) + ".." + Math.max(pos1.getBlockY(), pos2.getBlockY()) + " / "
                + Math.min(pos1.getBlockZ(), pos2.getBlockZ()) + ".." + Math.max(pos1.getBlockZ(), pos2.getBlockZ());
    }

    private String shortLoc(Location location) {
        return location.getWorld().getName() + " " + location.getBlockX() + " " + location.getBlockY()
                + " " + location.getBlockZ();
    }
}
