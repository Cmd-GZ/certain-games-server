# TNTBattle（TNT 对战）—— 速查卡

- **一句话职责**：红蓝两队分居两块互不相连的岛屿；右键发射受重力影响的 TNT（延迟爆炸、破坏方块、造成伤害），每 5 秒获得队伍颜色羊毛用于搭桥；一方全员死亡/掉入虚空后另一方获胜。
- **核心规则**：
  - 开局按人数**平分两队**（奇数时红队多一人），随机分配；人数上限由 `max-players` 决定（最高 100），可用 `setmaxplayers <场> <n>` 给单场覆盖。
  - 对局中所有参赛玩家**发光高亮**：红队红光、蓝队蓝光（通过计分板队伍颜色），并附带**无粒子夜视**；死亡转旁听后仍保留夜视，结算时恢复原状态（含头衔）。
  - TNT：冷却结束自动获得 TNT 弹药（默认每次 1 个，可堆叠到 64）；`tnt-ramp` 配置会让每次获得的弹药随时间递增（默认每 60 秒 +1，最多 5 个），每次提升会公屏播报；右键消耗 1 个向准星方向发射。
  - **TNT 只会破坏 `/tntbattle pos1/pos2` 划定的 X/Z 范围**；Y 自动覆盖整个世界高度；区域外（其他小游戏场地）不会被炸到。
  - **玩家会受到 TNT 爆炸伤害**，由插件在爆炸时主动结算：`tnt-damage`（1 = 半颗心，0 = 不掉血）、`tnt-damage-radius`（默认 6 格，爆心最高、边缘 15%），并附带击退；爆炸不产生方块掉落物。
  - 开局**快捷栏最后一格**（`steak.slot`，默认 8）给 64 个牛排；默认不自动补充，`steak.auto-refill: true` 时按 `steak.refill-cooldown-seconds` 补满。
  - 队伍羊毛：默认每秒 1 个，只能放置自己队伍的羊毛，且必须放在区域内。
  - 玩家可以**徒手破坏场地内的羊毛和玻璃类方块**（玻璃、玻璃板、各色玻璃与玻璃板、遮光玻璃）：羊毛正常掉落，**玻璃类不掉落**；其他方块仍然受保护不可破坏。放置时不限队伍颜色，任意羊毛都能放（玻璃不可放置）。
  - 道具**只认右键**（左键不触发）；同一 tick 内的重复交互会被去重，避免一次点出两个蛋。场地内羊毛的破坏会显式放行，不受其他插件（如建筑猜猜乐）的全局非 OP 保护影响。
  - **TNT 发射器固定占用快捷栏第 1 格，队伍羊毛固定占用第 2 格**（这两格锁死，不能拖动/交换）；特殊道具只会放在第 3 格及之后（背包满了才掉在脚下）。**其它道具（牛排、特殊道具、等待室道具）可以在自己的物品栏里随意挪格子**，插件按物品标记识别，不依赖槽位。
  - 特殊道具池：独立冷却（`special-items.cooldown-seconds`），按 `weight` 权重随机获得；`pool` 按配置顺序列出，每项都有 `enabled` 开关（`false` = 不再刷出，条目保留）。支持 `flame`（烈焰弹，直线飞行的原版火球、碰到方块或实体才爆炸）、`blackhole`（黑洞炸弹，发射黑色羊，斜撞墙会沿墙面滑走、正面撞墙或落地才垂直落下、到点吸附）、`decoy`（诱饵炸弹，发射红色羊，斜撞墙会沿墙面滑走、正面撞墙或落地才垂直落下、到点锁视角不爆炸）、`highex`（高爆炸弹，发射闪电苦力怕〔无 AI、本体不爆〕，斜撞墙会沿墙面滑走、正面撞墙或落地才垂直落下、到点爆炸）、`sticky`（粘性炸药，发射粘液块，撞到方块后黏住、到点爆炸）、`bridge_egg`（搭路蛋，发射原版蛋，从玩家腿部高度起、沿蛋的抛物线向下铺自己队伍颜色的羊毛，`width` 控制桥宽）、`angel`（天使之赐，右键直接获得漂浮）。各属性在 config 注释里说明；新行为需要插件支持对应 type。
  - **TNT 爆炸不产生任何掉落物**（包括羊毛）；爆炸后附近掉落的羊毛也会被清理。
  - 建筑高度上限：`build-height-limit`（绝对 Y，-1 = 不限制），或用 `/tntbattle setbuildheight [场] [Y|off]` 设置（每场独立）；玩家只能在这层及以下放置羊毛。
  - 死亡或掉到 `void-y` 以下 → 不再复活，切换旁观（每场可用 `setvoid [场] <Y>` 独立设置）。
  - 一方存活人数归零 → 另一方获胜；双方都归零则平局。
  - 每局结束自动还原区域内被爆炸/放置改变的方块（包括玩家放的羊毛）。
- **命令**（多场：在指令里写场名；只有一个场时可省略）：
  - 玩家：`/tntbattle join [场]`、`leave`、`list`、`stats [玩家]`、`top [数量]`、`help`
  - `/tntbattle top [数量]`：聊天栏查询用，默认前 10、最多 50；数据来自 `games.tntbattle.firsts.<uuid>`，按胜场降序、同胜场按名字排序。
  - **大厅悬浮排行榜**：`/tntbattle settop` 在当前位置放置 TextDisplay 悬浮文字（自动保存，胜场变化/重载时刷新）；`/tntbattle removetop` 移除。`leaderboard.location` 为空 = 不显示。插件会常驻加载排行榜所在区块，并在世界加载/实体失效时每 10 秒自动补放，重启后也会自动恢复。排行榜/战绩全局只有一份，以主场地（第一个场）为准。
  - 管理（OP 或 `tntbattle.admin`）：`create <场名>`、`remove <场名> confirm`、`pos1|pos2 [场]`、`setspawn [场] red|blue`、`addspawn [场] red|blue`、`clearspawns [场] red|blue`、`setlobby [场]`、`setvoid [场] <Y>`、`setbuildheight [场] [Y|off]`、`setmaxplayers [场] <n>`、`save [场]`、`start|stop [场]`、`settop`、`removetop`、`reload`
  - `list` 逐场显示：世界、状态、玩家数、区域大小、虚空线、建筑上限、人数上限、出生点数量、是否可开局。
  - **多场并发**：每个场常驻一个对局实例，独立等待室 / 出生点 / 虚空线 / 建筑上限 / 方块还原 / 计分板 / 音乐，可放在不同世界并同时开局互不影响；场名 1~24 个字母/数字/下划线/横线。
- **大厅排行榜配置**：`leaderboard.enabled`（true）、`limit`（10，0 = 全部）、`title`（`&6&lTNT 对战 胜场排行榜`）、`line`（`&e#%rank% &f%player% &7- &c%wins% &7胜`，占位符 `%rank%`/`%player%`/`%wins%`）、`empty`（`&7暂无胜场记录`）、`view-range`（4.0，1.0 ≈ 64 格可见距离）、`location`（用 `/tntbattle settop` 写入，不要手写）。
- **配置**：`server/plugins/TNTBattle/config.yml`
  - 改完在非对局状态 `/tntbattle reload`；设置出生点/区域/等待点会自动保存，reload 也会先保存当前场地。
  - 旧配置缺少的键（TNT/牛排/等待道具）会在启动或 reload 时自动补上；启动日志会打印当前 TNT 配置与每个场。保存场地时会先读回磁盘配置，只写自己的 `arenas.<场名>` 段；手动改的键（如 `debug-damage`）不会被内存旧值覆盖；但**改完仍要 `/tntbattle reload` 或重启才生效**。
  - **旧版单场配置自动迁移**：`config-version < 8` 且没有 `arenas` 段时，把旧 `arena.*` 原样复制成 `arenas.双岛.*`，并把全局 `void-y` / `build-height-limit` 带入该场；旧 `arena` 段保留但不再读取。
  - `min-players`（2）、`max-players`（16，可调最高 100）、`tnt-cooldown-seconds`（5）、`tnt-max-ammo`（10）、`tnt-fuse-ticks`（40）、`tnt-velocity`（1.5）、`tnt-yield`（4.0）、`tnt-damage`（10.0）、`tnt-damage-radius`（6.0）、`fall-damage-multiplier`（0.5）、`wool-cooldown-seconds`（1）、`wool-amount`（1）、`void-y`（-64）、`build-height-limit`（-1）
  - `steak.enabled`、`steak.amount`、`steak.slot`（默认 8）、`steak.auto-refill`、`steak.refill-cooldown-seconds`
  - `tnt-ramp.enabled`（true）、`tnt-ramp.interval-seconds`（60，0 = 关闭）、`tnt-ramp.amount-per-step`（1）、`tnt-ramp.max-amount`（5）
  - `bridge-egg.ground-scan-depth`（8，跳着丢搭路蛋时向下找几格地面）、`bridge-egg.ground-min-gap`（1.0，脚离地多少格才把桥压回地面；设成 100 = 关闭这个修正）
  - `special-items.cooldown-seconds`（30）、`special-items.pool`（按顺序的 type/weight/material/name/lore 及各自属性）
  - `debug-damage`（false）：true = 控制台打印每次爆炸的配置伤害/半径、每个受害者的距离/系数/前后血量，以及玩家受到的每个伤害事件（原因/基础/最终/是否取消/血量），排查伤害异常用
  - 搭路蛋：`max-blocks`（30，最多铺多少格羊毛）、`width`（1-9，桥宽；宽度也计入 max-blocks），桥从玩家腿部高度起、沿蛋的抛物线向下铺。**跳起来丢时**（脚下离地 ≥ `bridge-egg.ground-min-gap` 格，默认 1.0）桥会被压回脚下那层地面（`groundLevelUnder()` 向下最多找 `bridge-egg.ground-scan-depth` 格，默认 8），站着丢不变——修的是「半空丢蛋→桥铺在半空→落地后卡在头里、人被压成趴着」
  - 烈焰弹：`self-launch`（1.0，炸在自己脚边时给自己的竖直初速度，0 = 关闭；1.0 约 6 格、0.8 约 4 格、1.2 约 8 格）、`self-launch-radius`（3.0，判定"炸在自己脚边"的距离）。脚下自爆不扣自己血，这一次落地也免摔伤
  - 飞行道具（粘性炸药 / 黑洞 / 高爆 / 诱饵）撞方块判定用方块真实碰撞箱，活版门 / 半砖 / 楼梯只算实际占用的那半格，不会贴着半空黏住
  - `unlock-seconds`（每项，默认 0）：开局多少秒后这一项才进入随机池；黑洞 / 搭路蛋默认 120（2 分钟后才可能抽到）
  - 每场配置：`arenas.<场名>.world / pos1 / pos2 / red-spawns / blue-spawns / lobby / void-y / build-height-limit / max-players`（`max-players` 为 0 时用全局 `max-players`；场的 `void-y` / `build-height-limit` 缺省回退全局值）
- **玩家记录**：命名空间 `tntbattle`，`games.tntbattle.firsts.<uuid>`（胜场），唯一文件 `server/plugins/MiniGameLib/players.yml`。
- **等待室道具**：`wait-items` 配置段可改“回主城”和“开始游戏”的材质/名字/描述/槽位；默认回主城在 8 格、开始游戏在 4 格（仅管理员）。
- **常改文件**：
  | 需求 | 文件 |
  | --- | --- |
  | TNT 发射/冷却/羊毛/淘汰/胜负 | `TNTBattle/src/main/java/com/tntbattle/TNTBattleManager.java` |
  | 场地注册/多场路由/迁移 | `TNTBattle/src/main/java/com/tntbattle/TNTBattleCore.java` |
  | 单场区域/出生点/等待点/虚空线 | `TNTBattle/src/main/java/com/tntbattle/TNTBattleArena.java` |
  | 事件（右键/爆炸记录/放置/死亡/掉虚空） | `TNTBattle/src/main/java/com/tntbattle/TNTBattleListener.java` |
  | 命令/权限/tab | `TNTBattle/src/main/java/com/tntbattle/TNTBattleCommand.java` |
  | 背景音乐（录制/播放/触发） | `TNTBattle/src/main/java/com/tntbattle/TNTBattleMusic.java` |
- **注意**：
  - 每个场的地图由 OP 手工搭；插件不生成地形，只负责出生点、虚空线与方块还原。不同场可以放在不同世界。
  - 场地的区域只取 X/Z；不同场区域允许重叠判定互不影响（按各自 Manager 归属），但仍建议放在各自世界里。
  - 爆炸只记录自己发射的 TNT 破坏的方块；羊毛放置也会记录原始方块，结束后统一恢复。
  - 玩家死亡不掉落物品；旁观者不受伤。
- **背景音乐（1.11.11 起，录一次红石音乐 → 之后插件播放）**：
  - 录制：站在红石音乐装置的两个对角执行 `/tntbattle music pos1` / `/tntbattle music pos2` 圈出**音符盒范围** → `/tntbattle music record` → 启动红石机 → `/tntbattle music stop`。音符按 `tick + 乐器 + 音高` 存到 `plugins/TNTBattle/music.yml`；**录完把红石机停掉**，否则会和插件播放重叠。
  - 播放：对局中**任一方存活人数 <= `music.trigger-team-alive`（默认 1）**时开始循环；淘汰/离场/开局都会检查，`endGame`/`finishEnd`/关服停止。听众是本局所有玩家（含已淘汰旁观），每人用 `playSound` 单独播放，不受装置位置/区块加载影响。
  - 命令：`music pos1|pos2|record|stop|play|info|clear confirm|reload`；`play` 只给自己试听一遍（不循环）。
  - **个人开关（默认关闭）**：等待室物品栏第 1 格（`wait-items.music.slot`，默认 0）是音符盒「背景音乐」；**默认关闭**，右键只开启/关闭**自己**的收听，别人不受影响，lore 显示「当前：已开启/已关闭」。每次进等待室按 `music.default-on` 重置（默认 false），退出服务器后清掉；没有玩家开启时触发也不会启动播放任务。等待室道具可以自己拖到别的格子（右键开关时会在它当前所在格刷新状态），但不能丢弃，也不能 F 换到副手（换手后右键不认）。
  - 配置 `music` 段：`enabled`、`loop`、`default-on`、`volume`、`trigger-team-alive`、`tail-ticks`（最后一个音符后等多少 tick 再循环）、`max-record-seconds`（录制安全上限）。旧配置启动时自动补这些键。
  - 录制细节：只记录**范围内**的音符盒；录制时别在范围里手调音符盒/让别的音乐经过，否则会被一起录进去。播放音高用 `Note#getId()`，乐器对应声音由 `Instrument#getSound()` 决定。
- **验证方法**：
  1. `/tntbattle create a`、`/tntbattle create b`；分别在两个世界执行 `pos1 a|b`、`pos2 a|b`、`setspawn a|b red|blue`、`setlobby a|b`、`setvoid a|b <Y>`、`save a|b`。
  2. 玩家 A `/tntbattle join a`、玩家 B `/tntbattle join b` → `/tntbattle start a`、`/tntbattle start b`：两个场同时开局，红蓝发光、计分板、等待室互不影响；A 场炸方块不影响 B 场，结束后各自还原。
  3. `/tntbattle stop a` 不影响 B 场；`/tntbattle list` 显示两场状态；`/tntbattle remove b confirm` 在有玩家时被拒绝，空场可删除。
  4. 右键发射 TNT，确认 TNT 会飞出去并爆炸、破坏方块、造成伤害；一方全部死亡/掉虚空后另一方获胜，场地恢复。
  5. 在等待室内站到想放排行榜的位置，`/tntbattle settop`，确认出现悬浮文字（无记录时显示「暂无胜场记录」）；打完一局后确认胜场数字自动刷新；`/tntbattle removetop` 可移除。
  6. 重启后所有场与其独立配置保留；只剩旧 `arena.*` 的配置会自动迁移成「双岛」场。
