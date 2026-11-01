# FireJumper（跃火者）—— 速查卡

- **一句话职责**：在划定的平台上一轮轮横扫出火焰线，玩家带**跳跃提升 II**跳过线；碰到线立即淘汰并转旁观，最后存活者获胜；时间到仍多人存活则并列获胜。
- **核心规则**：
  - 开局所有玩家被分配到**固定出生点**（`arena.spawns`，`/firejumper setspawn` / `addspawn` 设置；开局打乱顺序分配，人数多于出生点时循环复用），获得**跳跃提升 II**（`jump-boost-level: 2`，无限时长，对局结束移除）。
  - 没有出生点时场地不算配置完整，无法开局（不再随机落点）。
  - 每条线是一面**火焰粒子墙**：线沿一个轴展开，沿垂直方向从场地一侧扫到另一侧；玩家**脚部低于 `ground-y + line-height` 且在线的判定带内**即淘汰。
  - 一轮可以出 1 条或多条线（每条之间间隔可配），轮与轮之间停 `round-gap-min-seconds ~ round-gap-max-seconds` 的随机时间。
  - 线的出现方式写在 `line-types` 列表里，按 `from-round / to-round` 决定第几轮可能出现；默认：
    | id | 轮次 | 条数 | 间隔 | 速度 |
    | --- | --- | --- | --- | --- |
    | normal | 1~9 | 1 | — | 6 格/秒 |
    | triple-slow | 5~9 | 3 | 1.5 秒 | 3 格/秒 |
    | fast | 10+ | 1 | — | 11 格/秒 |
    | quad | 10+ | 4 | 2 秒 | 8 格/秒 |
  - 淘汰：转旁观（SPECTATOR），留在场内观战；对局中免疫其它伤害。
  - 胜利：只剩 1 人；**时间到（默认 180 秒）仍多人存活 → 剩余玩家并列获胜**（都记胜场）。
  - 离开场地 XZ 范围或掉到地面以下 → 直接淘汰（`out-of-bounds-eliminate: true`）。
- **跳跃提升 II 物理测试（本插件节奏依据）**：
  - 按原版跳跃物理逐 tick 模拟（初速 `0.42 + 0.1 × (等级+1)`，每 tick `v = (v - 0.08) × 0.98`，20 tick/秒）：
    - 无加成：滞空 **0.60 秒**
    - 跳跃提升 I：**0.70 秒**
    - **跳跃提升 II：0.80 秒，最高约 2.52 格，脚高于 1.0 格的时间约 0.65 秒**
    - 跳跃提升 III：0.95 秒
  - 结论：**1.5 秒一条线可以完成「起跳→落地→再起跳」**——每次滞空 0.8 秒，落地后有约 0.7 秒按第二次跳；单条线（1 格厚判定带）在 0.65 秒的安全窗口内很容易跳过。默认节奏不需要改；想更松可以调 `line-height` 或加大 `gap-seconds`。
- **命令**（别名 `/fj`）：
  - 玩家：`/firejumper join`、`leave`、`list`、`help`
  - 管理（OP 或 `firejumper.admin`）：
    - `/firejumper pos1` / `pos2`：站到平台两个对角执行（只取 XZ；pos1 的脚部 Y 记为地面高度）
    - `/firejumper setlobby`：等待点
    - `/firejumper setspawn` / `addspawn` / `clearspawns`：固定出生点（setspawn = 清空后只留当前；addspawn = 追加；多点为人数上限准备，开局打乱分配）
    - `/firejumper setground [Y]`：改地面高度（不写 Y = 当前位置）
    - `/firejumper setdirection x+|x-|z+|z-`：线的移动方向（线沿垂直方向展开）
    - `/firejumper setlength <格|auto>`：线的长度（auto = 场地垂直方向总长）
    - `/firejumper setrange <格|auto>`：线的移动距离（auto = 场地移动方向总长；以场地中心居中）
    - `/firejumper setlineheight <格>`：判定高度（默认 1.0）
    - `/firejumper save`、`start`、`stop`、`reload`、`debug`
    - `/firejumper debug`：打印场地与当前火线的实际坐标（排查「看不见线」用）。
  - **单人测试**：管理员可以 `/firejumper join` 后直接 `/firejumper start` 一人开局（自动倒计时仍需 `min-players`）；单人局不记录胜场、不向街机派对上报名次，结算显示「单人测试结束」。
- **配置**：`server/plugins/FireJumper/config.yml`
  - 人数：`min-players`、`max-players`
  - 对局：`game-seconds`（180）、`start-delay-seconds`（3）、`jump-boost-level`（2）、`out-of-bounds-eliminate`（true）、`end-delay-seconds`（3）
  - 轮次：`round-gap-min-seconds`（1.5）、`round-gap-max-seconds`（3.0）
  - 线：`line-types` 列表（`id/enabled/from-round/to-round/lines/gap-seconds/speed`）
  - 场地：`arena.direction / line-length / travel-range / line-height / ground-y / pos1 / pos2 / lobby / spawns`
  - 等待室道具：`wait-items.return` / `wait-items.start`
  - 计分板：`sidebar.enabled` / `sidebar.title`
  - 粒子：`particles.type / rows / row-gap / spacing / interval-ticks / count`；排查：`debug-lines`
- **常改文件**：
  | 需求 | 文件 |
  | --- | --- |
  | 线的移动/碰撞/轮次/胜负 | `FireJumper/src/main/java/com/firejumper/FireJumperManager.java` |
  | 场地区域/方向/长度/范围 | `FireJumper/src/main/java/com/firejumper/FireJumperArena.java` |
  | 命令/权限/tab | `FireJumper/src/main/java/com/firejumper/FireJumperCommand.java` |
  | 事件（道具/免伤/出界/退出） | `FireJumper/src/main/java/com/firejumper/FireJumperListener.java` |
  | 配置模板 | `FireJumper/src/main/resources/config.yml` |
- **建场步骤**：
  1. 搭好平台（建议平整），站到平台一角执行 `/firejumper pos1`，站到对角执行 `/firejumper pos2`。
  2. `/firejumper setlobby` 设等待点；站到每个出生位置执行 `/firejumper addspawn`（或 `setspawn` 只留一个）。
  3. 可选：`/firejumper setdirection z+`（默认从左到右是 x+）、`setlength`、`setrange`、`setlineheight`。
  4. `/firejumper save`，`/firejumper list` 确认「可开局」。
  5. 至少 2 人 `/firejumper join` → `/firejumper start`（或有 OP 在队列时手动开始）。
- **注意**：
  - `ground-y` 是**玩家站立时的脚部 Y**；`/firejumper addspawn` 会用它记录地面，pos1 也会记录，所以正常按指令建场即可。地形抬高/降低后重新 pos1 或 setground。
  - **看不到火线**：`/firejumper debug` 看「地面 Y」是否等于你站立的脚部 Y；打开 `debug-lines: true` 后每次出线会在控制台打印起点/终点/判定高度。
  - 火线粒子**逐玩家发送**（不受 32 格视距限制）；默认只有 **1 层**（`particles.rows: 1`），每 3 tick 发一次（`particles.interval-ticks`）。
  - **拖尾长短**：原版粒子的存活时间由客户端固定，服务端 API 不能直接改；可视拖尾 ≈ 线速度 × 粒子寿命。要更短就调大 `particles.spacing` / `particles.interval-ticks`，或把 `particles.type` 换成寿命更短的 `ELECTRIC_SPARK` / `CRIT` / `FIREWORK`。
  - 粒子参数：`particles.type`（FLAME / SMALL_FLAME / SOUL_FIRE_FLAME / ELECTRIC_SPARK / CRIT / ENCHANTED_HIT / FIREWORK / END_ROD / SMOKE / LAVA）、`rows`、`row-gap`、`spacing`、`interval-ticks`、`count`。
  - `line-length` / `travel-range` 为 0 时自动用场地尺寸；自定义范围以场地中心居中。
  - 烟花/火焰粒子只用于显示，判定完全由插件计算，不受客户端粒子设置影响。
  - 已接入 ArcadeParty：`games.firejumper`（`fj join` / `fj start`），结算通过 `ArcadeBridge.report("firejumper", …)` 回报。
- **玩家记录**：命名空间 `firejumper`，`games.firejumper.firsts.<uuid>`（胜场），文件 `server/plugins/MiniGameLib/players.yml`。
