# ArcadeParty（街机派对）—— 速查卡

> AI 先读这一段，别先读源码

- **一句话职责**：把多个迷你小游戏**随机顺序串烧**成一局派对（同一个游戏一局只出现一次），每局小游戏前三名拿 3/2/1 颗星，局间有准备阶段并在右侧计分板显示累积星星，最后按星星排名在颁奖台揭晓前三并放烟花。命令 `/arcade`（别名 `/party`、`/arcadeparty`），显示名 `街机派对`。
- **流程**：
  1. 玩家 `/arcade join` 进等待区；人数够或 OP 强制开始 → 从可用小游戏池随机抽 `games-per-party` 个（不重复）。
  2. **准备阶段**（`transition-seconds`，默认 15 秒）：参赛者会被**加入下一个游戏的等待区（lobby）**并冻结在那里倒计时，右侧计分板显示星星榜，最后 3 秒播 title；超员观战者留在派对等待区。期间各游戏等待室的自动开局、以及各游戏自己的开局倒计时都被临时关闭——**准备阶段一结束就直接开局**，不会再多播 3 秒。
  3. **小游戏局**：把参赛玩家用各游戏的 `join-command` 拉进场，再用 `start-command` 开局；人数超过该游戏 `max-players` 的玩家本局旁观并拿 `stars.spectator-bonus`（默认 1 星）。**兜底**：开局 15 秒后如果场地连续 3 秒回到「等待中」，说明游戏没正常回报结果（或没开起来），派对按无人得分结束本局并继续下一个，不会卡到 600 秒超时。
  4. 小游戏结算时通过 `ArcadeBridge.report(key, 前三名)` 回报，派对发星并**只播报本局前三名**（★）；**累计星星不写聊天栏**，只在过渡阶段的右侧计分板显示，留到颁奖典礼揭晓。
  5. 打完全部小游戏 → **颁奖**：全体传到观众席，前三名隐身站上各自颁奖台（**揭晓前锁住移动，轮到自己的名次才解锁**），按 第三名 → 第二名 → 第一名 依次**只用 title** 现身（名字 + ★N）+ 烟花；三个人都揭晓后，聊天栏才一次性公布「颁奖结果」三行，然后全体回主城。
- **命令**：
  - 玩家：`/arcade join`、`leave`、`list`、`stars [玩家]`
  - OP：`menu`（设置菜单）、`start`、`stop`、`setlobby`、`setaudience`、`setpodium <first|second|third>`、**`addfirework`**（把脚下这格设为烟花发射点，可多次添加）、`clearfireworks`、`fireworks`（查看已配置的点）、`save`、`reload`
- **设置菜单**（`/arcade menu`，管理员；也可以挂在 Hub 菜单里）：
  - 每局小游戏个数、派对最多玩家、过渡准备时间：左键 +1 / 右键 -1（Shift = ±5）
  - 每个小游戏一格：点击启用/禁用；显示房间上限与 join/start 命令
  - 改动立刻写回 `config.yml`
- **配置**：`server/plugins/ArcadeParty/config.yml`
  - `min-players` / `max-players` / `games-per-party` / `transition-seconds` / `round-timeout-seconds`
  - `transition.freeze`（默认 true：过渡阶段锁移动，仍可转头；**改成 false 就允许准备阶段移动**）
  - `podium.end-delay-seconds`（默认 10：颁奖结果公布后停留多少秒展示前三名，再送回大厅；0 = 立刻返回）
  - `fireworks.spots`：固定烟花发射点（用 `/arcade addfirework` 添加，可多个）。**配了固定点后每个点各放一颗**，`spread-ticks`（默认 40）控制依次放完的时间；为空则退回在获奖者脚下随机发射 `count` 颗
  - `stars.first/second/third/spectator-bonus`
  - `lobby` / `podium.audience` / `podium.first|second|third`
  - `fireworks.enabled/count/interval-ticks/flight-ticks/types`
  - `scoreboard.enabled/title/line`
  - `games.<key>`：`display` / `enabled` / `arena-aware` / `max-players` / `join-command` / `start-command`
  - **多场地游戏**：`arena-aware: true` 时命令里写 `{arena}`（如 `dig join {arena}` / `dig start {arena}`）；派对会自动查主城登记的空场（状态为等待中）并替换。**抽到的游戏如果当时没有空场，会从本局清单里去掉并换下一个**，不会把带 `{arena}` 的命令空跑；开局前也会再检查一次。
- **已接入的小游戏**（会通过 `ArcadeBridge` 回报前三名）：`digtobottom`（一掘到底）、`anvilbattle`（铁砧掘战）、`animalslaughter`（动物屠宰）、`snakeparty`（蛇行派对）、`hotpotato`（烫手山芋）、`tntrun`（雪塔大作战）、`colorparty`（色盲派对）、`grouprace`（群友竞速）、`knockback`（击退大战）、`landclaim`（占地风云1）、`jumpfill`（花落水家）、`memoryflip`（记忆翻牌）、`hillking`（高台之王）、`oneshot`（One Shot）。
- **接入一个新小游戏**：
  1. 在它的结算处（算好前三名之后）调用
     `com.hub.api.ArcadeBridge.report("<key>", firsts, seconds, thirds);`
  2. 在 `ArcadeParty/config.yml` 的 `games` 段加一条：`display` / `enabled` / `max-players` / `join-command` / `start-command`（命令不要带前导 `/`）。
     多场地游戏再加 `arena-aware: true`，两条命令都写 `{arena}`。
  3. `/arcade reload` 或重启；菜单里会出现这一格。
- **建场**：
  1. 派对等待区：站好位置 `/arcade setlobby`。
  2. 颁奖：`/arcade setaudience`（观众席）、`/arcade setpodium first|second|third`（前三名站位，可搭台阶）。
  3. `/arcade list` 检查；`/arcade start` 测试。各小游戏自己的场地仍需按各自 README 配好。
- **常见坑**：
  - **小游戏必须在同一台服、且没被别人占用**：派对用命令把玩家拉进小游戏再开局；如果该小游戏正在被别的玩家玩，join/start 会失败。
  - **没回报就会超时**：没接 `ArcadeBridge` 的游戏本局会等到 `round-timeout-seconds` 后按“无人得分”进入下一局，所以未接入的游戏不要启用。
  - **`stop` 不颁奖**：管理员中止是直接回大厅；正常打完才有颁奖与烟花。
  - **派对进行中所有游戏自带的开局倒计时会被关掉**（`StartCountdown` 全局抑制；AnimalSlaughter / SnakeParty 用的是自带倒计时，会读 `StartCountdown.isSuppressed()` 直接开局），避免派对过渡计时之后又等 3 秒。
  - **对局中玩家回主城不会退出派对**：小游戏结算会把人 `HubAPI.teleportToLobby` 传回主城，派对只在 `WAITING` 阶段响应 lobby handler；对局/过渡/颁奖阶段的回城传送会被忽略（要退出请用等待室床或 `/arcade leave`）。
  - **Hub 菜单入口**：先在产品目录的 HubCore `games` 段加 `name: 街机派对 / icon: FIREWORK_ROCKET / color: 'e' / command: arcade join / join-prefix: arcade join`，再用 `/hub menu set <槽位> FIREWORK_ROCKET game:街机派对 &e&l街机派对`；管理员菜单可直接用 `/arcade menu`，要挂到 Hub 菜单就再加一个动作 `cmd arcade menu` 的物品。
- **验证**：
  1. `/arcade list` 看到等待区已配置、4 个小游戏启用；`/arcade menu` 能打开并改数值。
  2. 2 人 `/arcade join` → OP 右键开始纸：进入准备阶段，右侧显示星星榜，15 秒后自动开局。
  3. 一局小游戏结束 → 播报前三名带 ★，星星累加；过渡阶段计分板更新。
  4. 全部打完后进入颁奖：第三名/第二名/第一名依次现身、放烟花，然后回主城。
- **当前限制**：第一版只接入了 4 个会回报前三名的游戏；其它小游戏需要先按上面的“接入一个新小游戏”补一行回报和配置，才能进池。
