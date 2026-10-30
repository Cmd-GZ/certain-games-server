# SkyPit（天坑）—— 速查卡

> AI 先读这一段，能省掉读约 1300 行源码（SkyPit 5 个 Java 文件实测 1305 行）

- **一句话职责**：SkyPit 不建独立世界——它就是主城 `world` 里的一块 3D 长方体区域；玩家走进区域自动「存背包快照 → 发 kit → 开 PvP → 上报 HubCore」，走出 / 死亡重生 / 退出服务器 / 切创造旁观时把进入前的背包、盔甲、副手、游戏模式**原样复原**；击杀数永久记录，浮空文字（TextDisplay）展示前十名。
- **入口 / 主流程**：`SkyPit/src/main/java/com/skypit/SkyPitPlugin.java#onEnable` → `new SkyPitManager(plugin)` → `SkyPitManager#load`（读 `config.yml` + `PlayerStats.load("skypit", …)`）：
  - 进出判定：`SkyPitListener#onMove` → `SkyPitManager#contains`（同世界 + x/y/z 包围盒）→ `enterPit` / `exitPit`；
  - 发装备：`enterPit` 内 `Kit#apply`（装备存在 `config.yml` 的 `kit` 段）；
  - 击杀：`SkyPitListener#onDeath` → `SkyPitManager#handleDeath` → `recordKill`（写 `kills`/`names`）；
  - 复原：`onMove` 走出 → `exitPit`；`onQuit`→`handleQuit`；`onRespawn`→`handleRespawn`；`onGameModeChange`→`handleGameModeChange`；右键回主城道具 → `HubAPI.registerLobbyHandler` → `exitPit(p,false)`；
  - 保存 / 重载：`onDisable` → `manager.onDisable()` + `saveAll()`（内存回写 `config.yml`）；`/skypit reload` → `reload()` = `reloadConfig()` + `load()`。
- **命令**：`/skypit`（别名 `/pit`、`/天坑`）。命令级权限 `skypit.use`（默认 true）；管理子命令要 OP 或 `skypit.admin`（控制台算 admin）：`region pos1|pos2|info`、`set entry|spawn`、`kit`、`save`、`reload`、`top`、`top refresh`、`top remove`、`stats reset [玩家]`、`info`；玩家子命令：`warp`、`leave`、`stats [玩家]`、`help`/`?`（不带参数也打 help）。`stats [玩家]` 与 `stats reset [玩家]` 只认**在线**玩家（`getPlayerExact`），离线会提示"玩家不在线"。
- **配置**：`server/plugins/SkyPit/config.yml`（模板 `SkyPit/src/main/resources/config.yml` 只是默认值，改配置要改线上那份）：
  - `region.world/x1/y1/z1/x2/y2/z2`：两个对角点；未配置时 `world: ''`、坐标全 0（此时 `hasRegion()==false`，谁都不算进坑）。现网 = `world`，x 8.30~46.46 / y 39.84~55 / z -23.65~15.70；
  - `entry.*`（`/skypit set entry`，warp 传送点）、`spawn.*`（`set spawn`，死亡复活点）：未设置时 `world: ''`；**现网这两个都是 `''`＝未设置**（`/skypit warp` 会提示未设置，死亡复活回 `HubAPI.getLobby()`）；
  - `kit.slots`（0-8 快捷栏、0=主手；9-35 物品栏）、`kit.offhand`(36)、`helmet`(37)、`chestplate`(38)、`leggings`(39)、`boots`(40)：由 `/skypit kit` 菜单保存；`slots: {}` = 空配置（进区域不发装备）；
  - `leaderboards`：每项 `world/x/y/z/yaw/pitch/entity`（`entity` = 生成的 TextDisplay UUID）；现网 1 条，位于 `world` 8.75 / 57 / -3.61。榜单显示**前十名**（`SkyPitManager.TOP_SIZE`），背景色 `SkyPitManager.LB_BACKGROUND = Color.fromARGB(90,20,20,35)`（A=90/255 不透明度，比原来的 150 更透明；想更透明改小 A）——文字与背景都在 `applyLeaderboardStyle` 里设置，所以 `/skypit top refresh` 能刷新已有实体的透明度，不必删了重建。
- **玩家记录**：命名空间 `skypit`，结构 `kills.<uuid>`（击杀数）+ `names.<uuid>`（玩家名，排行榜显示用）；唯一文件 `server/plugins/MiniGameLib/players.yml`，代码 `PlayerStats.load("skypit", new File(plugin.getDataFolder(), "data.yml"))`，旧 `server/plugins/SkyPit/data.yml` 首次加载自动迁移（旧文件保留、之后不再读）；清空命令 `/skypit stats reset [玩家]`。详见 `docs/玩家记录.md`。
- **常改文件**：

  | 需求 | 文件 |
  | --- | --- |
  | 进出区域 / PvP / 死亡 / 重生等事件 | `SkyPit/src/main/java/com/skypit/SkyPitListener.java` |
  | 区域判定、装备发放与复原、击杀统计、排行榜 | `SkyPit/src/main/java/com/skypit/SkyPitManager.java` |
  | 子命令 / 权限 / tab 补全 | `SkyPit/src/main/java/com/skypit/SkyPitCommand.java` |
  | 装备存档结构（slots / offhand / 盔甲） | `SkyPit/src/main/java/com/skypit/Kit.java` |
  | 命令声明、别名、`minigame` 标记、权限节点 | `SkyPit/src/main/resources/plugin.yml` |
  | 线上区域 / 入口 / 装备 / 排行榜 | `server/plugins/SkyPit/config.yml`（游戏里用命令改，或手改后 `/skypit reload`） |
- **常见坑**（均在源码里核实）：
  - `load()` 里必须先把 `leaderboards` 列表 `clear()` 再按配置重建——源码注释写明「重载时先清空，否则同一坐标会重复生成浮空文字实体」，而 `/skypit reload` 走的正是 `load()`；
  - `refreshLeaderboards()` 对**区块未加载**的榜单跳过，实体丢了会重新 `spawnDisplay` 并 `saveAll()`，所以 `entity` UUID 会被回写；`/skypit top remove` 移除的是**全部**榜单；
  - 榜单 TextDisplay 会随区块存盘写进世界文件（Paper 26.2 的 `setPersistent(false)` 对存盘**无效**，见 `docs/已知坑.md` §2）；不把浮窗打进整包的修法是 `server/config/paper-world-defaults.yml` → `chunks.entity-per-chunk-save-limit.text_display: 0`：实体只活在内存，区块存盘时按类型跳过（存量实体下次存盘自动洗掉），实体丢失由 `load()` 延时 40t 的 `refreshLeaderboards` + ChunkLoadEvent 重建并回写新 UUID，功能不受影响；
  - 关服 `saveAll()` 用内存回写 `config.yml`（`region/entry/spawn/kit/leaderboards` 整段重写）：运行中手改线上配置会被关服覆盖，改完记得 `/skypit reload`；
  - PvP 两层，**都只在「天坑所在世界」内生效**（1.0.3 起）：`onDamageGuard`(LOWEST) 在坑所在世界里、双方不都在坑内时取消；`onDamageAllow`(HIGHEST) 仅当**双方都在坑内**才放行（覆盖服务器的 `pvp=false`）。
    **注意：这个守卫是「主城不能互殴」的当前唯一实现**。实测主城世界 `world` 的 gamerule `pvp` 是 `1`（开着的），
    `server.properties` 的 `pvp=false` 只决定新建世界的默认值、管不到已存在的世界。所以删掉它 = 大厅里玩家可以互砍。
    正确做法是把它交给 HubCore（主城世界的所有者）去管，见 `docs/框架不变量.md` I2/I3；在那之前不要动。

    早期版本这两个监听器对**所有世界**生效，等于替其它游戏做决定：`world_duel` / `world_lp` 的 `pvp: true` 会被打掉，那些插件只能反向在 HIGHEST 且 `ignoreCancelled=false` 抢回来（见 `docs/已知坑.md` 幸运之柱那条）。现在天坑只在自己的地盘表态，别处一律 `return`——依据 `docs/框架不变量.md` 的 **I2（区域归属）** 与 **I3（事件裁决唯一）**。改这里之前先读那两条；
  - `kills.<uuid>` 与 `names.<uuid>` 由 `recordKill` 成对写入、`resetKills` 成对清空；缺 `names` 时排行榜显示「未知玩家」；
  - `enterPit` 的前置条件是 `hasRegion()`、`isPvpEligible`（非创造/旁观）且不在会话里——区域没配好时走进也不发装备；
  - **进出区域不播公屏**（2026-10-18 用户要求「天坑不要在公屏显示加入游戏」）：`enterPit` 里原来的 `HubAPI.announceJoin(...)` 已删除，走进区域只给本人 `Chat.send`；击杀播报（`handleDeath` 的 `Chat.broadcast`）保留。别为了"提示一下"再把 announceJoin 加回来。
- **验证方法**：`/skypit info` 看区域 / 入口 / 装备 / 场内人数 / 记录人数 → 走进区域：本人只收到一行「您已进入天坑」（**只有自己看得到；公屏不播报**，2026-10-18 起提示已精简，不再带装备/PvP/击杀榜的说明），背包换成 kit，tab 分区出现「天坑」→ 杀一次：全服播报击杀、`/skypit stats` +1、`/skypit top refresh` 后浮空文字前十名更新 → 走出区域：只提示「您已离开天坑」、背包与游戏模式复原、PvP 关闭 → `/skypit leave` 同效并传回入口（未设 entry 则回主城）。改了玩家记录相关代码再跑 `check-stats.ps1`。
- **未确认 / TODO**：
  - 本机 `server/plugins/MiniGameLib/players.yml` **不存在**（只有 `server/plugins/SkyPit/data.yml`），所以 `games.skypit` 的真实内容、以及迁移是否已发生，未亲眼验证；
  - 任务说明里的「1160 行源码」与实测不符：SkyPit 5 个 Java 文件共 1305 行（含空行）；说明里的「本 README 42 行」也不符，实测 71 行；
  - （已由父会话更正）原文下方「击杀数保存在 `plugins/SkyPit/data.yml`」已改成本文件末尾的 `players.yml` 说法；
  - `entry`/`spawn` 未设置时死亡复活的实际落点取决于 `HubAPI.getLobby()`，未实机跑过；
  - 未在服务端验证权限插件场景下 `skypit.use`（命令级）与 `skypit.admin` 的叠加行为；tab 补全里 `stats` 被重复加入一次（无害，本次未改）。

---

# SkyPit 天坑插件

Paper 1.21+ 小游戏插件：OP 圈一块**战斗区域**，玩家走进区域**自动发放装备并开启 PvP**，
击杀数**永久记录**，OP 可用指令生成**浮空文字排行榜**展示击杀前十名。
装备配置方式与 DuelArena（单挑 PvP）完全一致：**打开菜单窗口摆放物品**。

- 主类：`com.skypit.SkyPitPlugin`（depend: MiniGameLib, HubCore）
- 编译产物：`.build/SkyPit.jar`（用仓库根目录的 `build.ps1 -Deploy` 部署到 `server/plugins/`）

## 玩家命令

| 命令 | 说明 |
|------|------|
| `/skypit warp` | 传送到天坑入口（主城菜单点击图标也执行此命令） |
| `/skypit leave` | 离开天坑（归还装备并传送回入口/主城） |
| `/skypit stats [玩家]` | 查看自己的（或指定玩家的）击杀数 |

## OP 命令

| 命令 | 说明 |
|------|------|
| `/skypit region pos1` / `pos2` | 站在战斗区域两个对角设置范围 |
| `/skypit region info` | 查看当前区域 |
| `/skypit set entry` | 把当前位置设为天坑入口（warp 传送点） |
| `/skypit set spawn` | 把当前位置设为天坑内死亡后的复活点 |
| `/skypit kit` | 打开装备配置菜单（进入天坑自动发放，全体玩家共用） |
| `/skypit save` | 保存配置 |
| `/skypit reload` | 重载配置 |
| `/skypit top` | 在脚下生成击杀榜**浮空文字**（前十名） |
| `/skypit top refresh` | 手动刷新全部浮空排行榜 |
| `/skypit top remove` | 移除全部浮空排行榜 |
| `/skypit stats reset [玩家]` | 清空全部（或指定玩家的）击杀记录 |
| `/skypit info` | 查看天坑配置状态 |

别名：`/pit`、`/天坑`。玩家命令无需权限，管理命令仅 OP 可用。

## 装备配置菜单（与 DuelArena 相同）

`/skypit kit` 打开 54 格菜单：

- 第 1~4 行（0-35 格）：快捷栏 + 物品栏，第 1 格为主手；
- 36 副手，37 头盔，38 胸甲，39 护腿，40 靴子；
- 41 保存，42 清空，43 关闭。

点击保存后写入 `config.yml` 的 `kit` 段，玩家进入天坑时自动发放。

## 玩法与规则

- 玩家走进战斗区域 → 自动发放装备、回满血/饥饿、清除药水效果、PvP 开启，tab 显示「已加入·天坑」（**只有本人收到提示，不在公屏播报加入**）；
- 走出区域 / 退出服务器 / 切换创造/旁观 → 自动归还进入前的背包并关闭 PvP；
- 在天坑内被击杀 → 不掉落物品、不清等级，击杀者 +1 击杀数；死者复活到复活点（未设置则入口/主城），归还装备后可再入坑；
- 主城等区域禁止玩家互伤；天坑内仅双方都在区域内才允许 PvP（兼容 DuelArena 的对战不受影响）；
- 击杀数保存在 `plugins/MiniGameLib/players.yml` 的 `games.skypit` 段（旧 `plugins/SkyPit/data.yml` 首次启动自动迁移，旧文件保留不再读取），重启不丢失。

## 与主城（HubCore）的接入

- `plugin.yml` 已带 `minigame-game: true` 与 `minigame.name: 天坑` 标记，重载/重启后 tab 自动出现「天坑」分区；
- 玩家进/出区域时自动调用 `HubAPI.setPlaying / clearGame`；背包归还靠 `PlayerQuitEvent`、走出区域、
  以及 `HubAPI.registerLobbyHandler(...)`（右键「回主城」道具时）；
- 天坑没有 `join` 命令（"走进区域"就是加入方式），因此**没有**在 HubCore 中央菜单里挂入口；
  如果需要菜单入口，可以先给 SkyPit 加一个 `/skypit join`（传送到 entry），再在 HubCore
  `config.yml` 的 `games` 段加一条命令为 `skypit join` 的条目。

## 部署步骤

1. 把 `SkyPit.jar` 放入 `server/plugins/`（已部署）；
2. 重启服务器（或先 `reload confirm`）；
3. OP 依次执行：
   `/skypit region pos1` → `/skypit region pos2` → `/skypit set entry` →
   `/skypit set spawn` → `/skypit kit` 配置装备并保存 → `/skypit save`；
4. 走到想展示榜单的位置执行 `/skypit top`。
