package com.football;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 一个绿茵足球场：范围 + 球门 + 两队出生点 + 运行时比分/阶段。 */
public class FootballArena {

    /** 队伍。goalRed = 红队守的球门（球进红门 = 蓝队得分）。 */
    public enum Team {
        RED("&c红队"), BLUE("&9蓝队");

        private final String display;

        Team(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }

        public Team other() {
            return this == RED ? BLUE : RED;
        }
    }

    public enum State {
        WAITING, RUNNING
    }

    /** 对局内的阶段：KICKOFF=等开球方踢（球冻结在中央）、PLAY=自由比赛、GOAL_PAUSE=进球后的短暂定格。 */
    public enum Phase {
        KICKOFF, PLAY, GOAL_PAUSE
    }

    private String name;
    private Location pos1;
    private Location pos2;
    private Location center;
    private Location lobbyPoint;
    private Location spawnRed;
    private Location spawnBlue;
    private Location goalRedPos1;
    private Location goalRedPos2;
    private Location goalBluePos1;
    private Location goalBluePos2;

    // 运行时
    private State state = State.WAITING;
    private Phase phase = Phase.KICKOFF;
    private final List<UUID> players = new ArrayList<>();   // 场内所有人（等待中=排队，对局中=双方球员）
    private final List<UUID> teamRed = new ArrayList<>();   // 红队成员（等待阶段选了队 / 开赛分配后）
    private final List<UUID> teamBlue = new ArrayList<>();
    private int scoreRed;
    private int scoreBlue;
    private Team kickoffTeam = Team.RED;
    private UUID lastTouch;                                 // 最后触球者
    private Team lastTouchTeam;
    private boolean goldenGoal;
    private long matchEndMs;
    private int matchTotalSeconds;

    public FootballArena(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Location getPos1() {
        return pos1;
    }

    public void setPos1(Location pos1) {
        this.pos1 = pos1;
    }

    public Location getPos2() {
        return pos2;
    }

    public void setPos2(Location pos2) {
        this.pos2 = pos2;
    }

    public Location getCenter() {
        return center;
    }

    public void setCenter(Location center) {
        this.center = center;
    }

    public Location getLobbyPoint() {
        return lobbyPoint;
    }

    public void setLobbyPoint(Location lobbyPoint) {
        this.lobbyPoint = lobbyPoint;
    }

    public Location getSpawnRed() {
        return spawnRed;
    }

    public void setSpawnRed(Location spawnRed) {
        this.spawnRed = spawnRed;
    }

    public Location getSpawnBlue() {
        return spawnBlue;
    }

    public void setSpawnBlue(Location spawnBlue) {
        this.spawnBlue = spawnBlue;
    }

    public Location getGoalRedPos1() {
        return goalRedPos1;
    }

    public void setGoalRedPos1(Location goalRedPos1) {
        this.goalRedPos1 = goalRedPos1;
    }

    public Location getGoalRedPos2() {
        return goalRedPos2;
    }

    public void setGoalRedPos2(Location goalRedPos2) {
        this.goalRedPos2 = goalRedPos2;
    }

    public Location getGoalBluePos1() {
        return goalBluePos1;
    }

    public void setGoalBluePos1(Location goalBluePos1) {
        this.goalBluePos1 = goalBluePos1;
    }

    public Location getGoalBluePos2() {
        return goalBluePos2;
    }

    public void setGoalBluePos2(Location goalBluePos2) {
        this.goalBluePos2 = goalBluePos2;
    }

    /** 场地算"已配置"的条件：范围、开球点、两队出生点、两个球门都齐（等待点可省）。 */
    public boolean isReady() {
        return missingPoints().isEmpty();
    }

    /** 还缺哪些点（给 /fb list 与 join 的提示用）。 */
    public List<String> missingPoints() {
        List<String> missing = new ArrayList<>();
        if (pos1 == null) missing.add("范围角1");
        if (pos2 == null) missing.add("范围角2");
        if (center == null) missing.add("开球点");
        if (spawnRed == null) missing.add("红队出生点");
        if (spawnBlue == null) missing.add("蓝队出生点");
        if (goalRedPos1 == null) missing.add("红门角1");
        if (goalRedPos2 == null) missing.add("红门角2");
        if (goalBluePos1 == null) missing.add("蓝门角1");
        if (goalBluePos2 == null) missing.add("蓝门角2");
        return missing;
    }

    /** 精确坐标（double）判断是否在 pos1/pos2 围成的长方体内，用于球与人。 */
    public boolean contains(Location loc) {
        return inBox(loc, pos1, pos2);
    }

    /** 点在哪个队的球门里（RED=红队守的门）。都不在返回 null。 */
    public Team goalAt(Location loc) {
        if (inBox(loc, goalRedPos1, goalRedPos2)) {
            return Team.RED;
        }
        if (inBox(loc, goalBluePos1, goalBluePos2)) {
            return Team.BLUE;
        }
        return null;
    }

    private static boolean inBox(Location loc, Location a, Location b) {
        if (loc == null || a == null || b == null || a.getWorld() == null
                || !a.getWorld().equals(loc.getWorld())) {
            return false;
        }
        return loc.getX() >= Math.min(a.getX(), b.getX()) && loc.getX() <= Math.max(a.getX(), b.getX())
                && loc.getY() >= Math.min(a.getY(), b.getY()) && loc.getY() <= Math.max(a.getY(), b.getY())
                && loc.getZ() >= Math.min(a.getZ(), b.getZ()) && loc.getZ() <= Math.max(a.getZ(), b.getZ());
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    public List<UUID> getPlayers() {
        return players;
    }

    public List<UUID> getTeamRed() {
        return teamRed;
    }

    public List<UUID> getTeamBlue() {
        return teamBlue;
    }

    public List<UUID> teamOf(Team team) {
        return team == Team.RED ? teamRed : teamBlue;
    }

    public Team teamOf(UUID id) {
        if (teamRed.contains(id)) {
            return Team.RED;
        }
        if (teamBlue.contains(id)) {
            return Team.BLUE;
        }
        return null;
    }

    /** 加入某队（先从另一队移除）。 */
    public void assignTeam(UUID id, Team team) {
        teamRed.remove(id);
        teamBlue.remove(id);
        teamOf(team).add(id);
    }

    public void removeFromTeams(UUID id) {
        teamRed.remove(id);
        teamBlue.remove(id);
    }

    public int getScoreRed() {
        return scoreRed;
    }

    public int getScoreBlue() {
        return scoreBlue;
    }

    public void addScore(Team team) {
        if (team == Team.RED) {
            scoreRed++;
        } else {
            scoreBlue++;
        }
    }

    public Team leadingTeam() {
        if (scoreRed > scoreBlue) {
            return Team.RED;
        }
        if (scoreBlue > scoreRed) {
            return Team.BLUE;
        }
        return null;
    }

    public Team getKickoffTeam() {
        return kickoffTeam;
    }

    public void setKickoffTeam(Team kickoffTeam) {
        this.kickoffTeam = kickoffTeam;
    }

    public UUID getLastTouch() {
        return lastTouch;
    }

    public Team getLastTouchTeam() {
        return lastTouchTeam;
    }

    public void setLastTouch(UUID id, Team team) {
        this.lastTouch = id;
        this.lastTouchTeam = team;
    }

    public boolean isGoldenGoal() {
        return goldenGoal;
    }

    public void setGoldenGoal(boolean goldenGoal) {
        this.goldenGoal = goldenGoal;
    }

    public long getMatchEndMs() {
        return matchEndMs;
    }

    public void setMatchEndMs(long matchEndMs) {
        this.matchEndMs = matchEndMs;
    }

    public int getMatchTotalSeconds() {
        return matchTotalSeconds;
    }

    public void setMatchTotalSeconds(int matchTotalSeconds) {
        this.matchTotalSeconds = matchTotalSeconds;
    }

    /** 重置对局运行时数据（开赛与结算时调用）。 */
    public void resetMatch() {
        phase = Phase.KICKOFF;
        teamRed.clear();
        teamBlue.clear();
        scoreRed = 0;
        scoreBlue = 0;
        lastTouch = null;
        lastTouchTeam = null;
        goldenGoal = false;
        matchEndMs = 0L;
        matchTotalSeconds = 0;
    }

    /** 保存（Location 直接序列化，与其它游戏一致）。 */
    public void save(ConfigurationSection sec) {
        com.hub.world.Worlds.writeLocation(sec, "pos1", pos1);
        com.hub.world.Worlds.writeLocation(sec, "pos2", pos2);
        com.hub.world.Worlds.writeLocation(sec, "center", center);
        com.hub.world.Worlds.writeLocation(sec, "lobby", lobbyPoint);
        sec.set("spawn-red", spawnRed);
        sec.set("spawn-blue", spawnBlue);
        sec.set("goal-red-1", goalRedPos1);
        sec.set("goal-red-2", goalRedPos2);
        sec.set("goal-blue-1", goalBluePos1);
        sec.set("goal-blue-2", goalBluePos2);
    }

    public static FootballArena load(String name, ConfigurationSection sec) {
        FootballArena a = new FootballArena(name);
        a.pos1 = com.hub.world.Worlds.readLocation(sec, "pos1");
        a.pos2 = com.hub.world.Worlds.readLocation(sec, "pos2");
        a.center = com.hub.world.Worlds.readLocation(sec, "center");
        a.lobbyPoint = com.hub.world.Worlds.readLocation(sec, "lobby");
        a.spawnRed = (Location) sec.get("spawn-red");
        a.spawnBlue = (Location) sec.get("spawn-blue");
        a.goalRedPos1 = (Location) sec.get("goal-red-1");
        a.goalRedPos2 = (Location) sec.get("goal-red-2");
        a.goalBluePos1 = (Location) sec.get("goal-blue-1");
        a.goalBluePos2 = (Location) sec.get("goal-blue-2");
        return a;
    }
}
