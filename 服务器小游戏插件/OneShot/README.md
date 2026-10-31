# OneShot（One Shot）—— 速查卡

> AI 先读这一段，别先读源码

- **一句话职责**：每人一把木剑、一把弓、**一支箭**；弓箭射中玩家**直接秒杀**；击杀任意玩家（剑砍、弓箭、或把对手打下台摔死）**补回一支箭**（上限一支）。最后存活者获胜，按淘汰顺序记录最后三人为前三名。命令 `/oneshot`（别名 `/os`、`/one`），显示名 `One Shot`。
- **规则**：
  - 开局至少 `min-players`（默认 2），最多 `max-players`（16）；等待倒计时用 `WaitCountdown`，OP 在队列时暂停自动开局。
  - 开局公共库 `StartCountdown` 播 3 秒 title 倒计时（街机派对里自动跳过）。
  - 开局给存活玩家**永久夜视**（`night-vision`，默认开；**淘汰后旁观保留**，整局结束时统一移除）。
  - 每人：**木剑（无限耐久）+ 弓（无限耐久）+ 1 支箭**。第一次射箭会消耗掉箭；**任何击杀都补回 1 支**（已有 1 支则不变）。
  - **一箭秒杀**：玩家射出的箭命中玩家 → 直接击杀（不按原版伤害算）；箭不会被对局内玩家捡回。
  - **摔死/掉虚空算人头**：离开台面掉到台面以下 `arena.fall-kill-below`（默认 1.5 格）、或掉进世界底部虚空，即判定死亡；如果 `kill-credit-seconds`（默认 10 秒）内最后打他的人还活着，人头算给他并补箭。
  - **最后 60 秒发光**：剩余 `glow-at-seconds`（默认 60，0 = 关闭）时，所有存活玩家自动发光，方便互相找到。
  - 对局默认最长 `game-seconds`（600 秒）；时间到仍有多人存活则共同获胜。
  - 对局在**场地实际所在世界 + 参赛者所在世界**临时开启 PvP（手动 pos1/pos2 场地也生效，结束/关服还原）；会显式解除天坑（SkyPit）对非天坑玩家的 PvP 取消。被淘汰或掉虚空后会强制重生并切成旁观。
  - 场内玩家无伤（除玩家 PvP 外所有伤害取消）、不能破坏/放置、不能丢东西、不能饿。
  - **前三名**：最后存活者第一；按淘汰顺序倒序补第二、第三（同归于尽时按最后淘汰顺序取三档）。分别 +3/+2/+1，写入 `games.oneshot`。
- **命令**：`/oneshot`（别名 `/os`、`/one`）
  - 玩家：`join`、`leave`、`list`、`stats [玩家]`、`help`
  - OP：**`build [半径] [台高]`**（站地面一键建圆形场地 + 自动生成出生点 + 保存）、**`pos1` / `pos2`**（手动选场地两个角）、`setlobby`、`addspawn`、`clearspawns`、`genspawns [数量]`、`save`、`start`、`stop`、`reload`、`stats reset`
- **配置**：`server/plugins/OneShot/config.yml`
  - `min-players` / `max-players` / `game-seconds` / `kill-credit-seconds` / `glow-at-seconds`（默认 60）/ `night-vision`（默认开）
  - `items.sword|bow|arrow` 的材质、名字、lore
  - `arena.radius`（默认 20）/ `arena.height`（台面抬高，默认 3）/ `arena.depth`（底座厚度）/ `platform-material` / `base-material` / `fall-kill-below` / `pos1` / `pos2` / `lobby` / `spawns`（设置 pos1/pos2 后优先用手动区域）
  - `sidebar.enabled/title`：右侧存活榜
- **建场步骤（OP，两种方式，二选一）**：
  - **自动建场（原功能不变）**：站到地面 `/oneshot build 20 3` → 头顶上方 3 格生成圆形石台、自动生成出生点并保存，人送到台面。会自动清掉之前手动划的 pos1/pos2。
  - **手动建场（自己搭场地）**：自己搭好平台后，站到场地**两个对角**分别执行 `/oneshot pos1`、`/oneshot pos2`；**只取 XZ 范围，Y 轴自动扫描矩形内最低/最高的非空气方块**（多层立体地图也能整块选中）。低于区域最低点、或走出 XZ 范围即判定摔死。然后 `/oneshot genspawns 16`（或 `/oneshot addspawn`）生成出生点。
  - 两种方式最后都要：场外 `/oneshot setlobby` → `/oneshot list` 显示已配置 → `/oneshot start` 测试。
  - 建议建在**独立世界**（本服可用 GameWorlds 的 `/gw create world_os`）：对局期间会临时打开该世界的 PvP。
  - 掉出场地即“摔死”（人头算最后打你的人），所以不需要真的摔 20 格。
- **街机派对**：key `oneshot`，命令 `oneshot join` / `oneshot start`，`max-players: 16`，非多场地。
- **HubCore**：带 `minigame-game: true`，自动登记到 tab/播报；菜单入口在主城配置里加。
- **验证**：
  1. `/oneshot build 20 3` → 圆形石台、出生点数量正确；`/oneshot list` 已配置。
  2. 2 人 `/oneshot join` → `/oneshot start` → 两人各拿到木剑、弓、1 支箭。
  3. A 射中 B → B 立刻死亡（一箭秒杀），A 的箭补回 1 支；B 变成旁观。
  4. A 把 B 打下台 → B 摔死，人头算给 A，A 补箭。
  5. 最后 1 人存活 → 结束并播报前三名（+3/+2/+1）；`/oneshot stats` 能查到。
  6. 派对里抽到 One Shot → 正常加入、直接开局、回报名次。
