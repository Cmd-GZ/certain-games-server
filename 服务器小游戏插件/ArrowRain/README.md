# ArrowRain（万箭齐发）—— 速查卡

- **一句话职责**：每回合场地随机位置刷新**橡木半砖掩体**，玩家站到半砖下面躲箭雨；没躲好被箭击中就淘汰转旁观。掩体数量随回合递减（最少 1 个），第 N 回合起开放玩家互殴（默认第 10 回合），最后存活者获胜。
- **核心规则**：
  - 开局随机分配到**固定出生点**，清空背包、生存模式；**对局中玩家之间关闭实体碰撞**（不会互相挤压），结束/离开时恢复。
  - 每回合三个阶段：
    1. **找掩体**（`round.cover-seconds`，默认 3 秒）：清掉上一轮半砖，在场地随机位置重新生成 `covers` 配置数量的半砖（离地 `covers.height`，默认 2 格 = 玩家头顶上方）。动作栏持续显示「箭雨 X 秒后开始」，最后 3 秒屏幕中央每秒报数。
    2. **箭雨**（`round.rain-seconds`，默认 10 秒）：每 `rain.target-interval-ticks`（默认 10 tick）给每个**没有站在掩体正下方**的玩家头顶落一支箭；另有全场随机装饰箭（`rain.ambient-per-second`）。动作栏显示本回合箭雨剩余时间。
    3. **休息**（`round.gap-seconds`，默认 3 秒）→ 下一回合。
  - **掩体保护**：玩家脚部在地面高度附近，且 XZ 与某块半砖同格 → 安全；跳跃/离开该格都会被箭命中。
  - **掩体递减**：第 1 回合 `covers.start-count`（默认 8），每回合减 `covers.decrease-per-round`（默认 1），最低 `covers.min-count`（默认 1）。
  - **PvP**：第 `round.pvp-start-round`（默认 10）回合起开放。伤害处理用 **MONITOR 显式放行**，优先级高于天坑（SkyPit 在 LOWEST 全局取消、HIGHEST 只放行自己场内）；对局开始时会临时把场地世界 PvP 设为 true，结束恢复原值。
  - 玩家被箭击中或 PvP 阵亡 → 淘汰转旁观；只剩 1 人获胜；`game-seconds`（默认 240 秒）时间到仍多人存活 → 剩余玩家并列获胜（都记胜场）。
  - 管理员可以 1 人 `/arrowrain start` 测试；单人局不记胜场、不向街机派对上报名次。
- **命令**（别名 `/ar`、`/arrows`）：
  - 玩家：`/arrowrain join`、`leave`、`list`、`help`
  - 管理（OP 或 `arrowrain.admin`）：
    - `/arrowrain pos1` / `pos2`：站到平台两个对角执行（只取 XZ；pos1 的脚部 Y 记为地面）
    - `/arrowrain setspawn` / `addspawn` / `clearspawns`：固定出生点
    - `/arrowrain setlobby`：等待点
    - `/arrowrain setground [Y]`：地面高度（不写 Y = 当前位置）
    - `/arrowrain setpvpround <回合>`：第几回合开放 PvP（默认 10）
    - `/arrowrain save`、`start`、`stop`、`reload`、`debug`
- **配置**：`server/plugins/ArrowRain/config.yml`
  - 人数：`min-players`、`max-players`
  - 对局：`game-seconds`（240）、`start-delay-seconds`（3）、`end-delay-seconds`（3）
  - 回合：`round.cover-seconds`（3）、`rain-seconds`（10）、`gap-seconds`（3）、`pvp-start-round`（10）
  - 掩体：`covers.start-count`（8）、`min-count`（1）、`decrease-per-round`（1）、`material`（OAK_SLAB）、`height`（2）、`min-distance`（3）
  - 箭雨：`rain.arrow-height`（20）、`target-interval-ticks`（10）、`target-spread`（0.35）、`arrow-speed`（1.2）、`ambient-per-second`（10）
  - 场地：`arena.pos1 / pos2 / lobby / spawns / ground-y`
  - 等待室道具：`wait-items.return` / `wait-items.start`
  - 计分板：`sidebar.enabled` / `sidebar.title`
- **常改文件**：
  | 需求 | 文件 |
  | --- | --- |
  | 回合/掩体/箭雨/PvP/胜负 | `ArrowRain/src/main/java/com/arrowrain/ArrowRainManager.java` |
  | 场地/出生点/地面 | `ArrowRain/src/main/java/com/arrowrain/ArrowRainArena.java` |
  | 命令/权限/tab | `ArrowRain/src/main/java/com/arrowrain/ArrowRainCommand.java` |
  | 事件（箭命中/伤害/PvP/死亡） | `ArrowRain/src/main/java/com/arrowrain/ArrowRainListener.java` |
  | 配置模板 | `ArrowRain/src/main/resources/config.yml` |
- **建场步骤**：
  1. 搭一个**平整、上方开阔**的平台（半砖在头顶，不能有天花板挡住箭）。
  2. 站到两个对角：`/arrowrain pos1`、`/arrowrain pos2`。
  3. 站到出生位置：`/arrowrain addspawn`（可多个）。
  4. `/arrowrain setlobby`；可选 `/arrowrain setground <Y>`、`/arrowrain setpvpround 10`。
  5. `/arrowrain save`，`/arrowrain list` 确认「可开局」。
  6. 至少 2 人 `/arrowrain join` → `/arrowrain start`。
- **注意**：
  - 半砖默认在玩家头顶上方 2 格（`covers.height`），太低会卡头、太高箭可能从侧面绕过；建议 2。
  - 掩体会替换该位置的方块，回合结束/对局结束会**还原原始方块**。
  - 场地需要开放天空：箭从 `rain.arrow-height`（默认 20 格）上方落下。**所有箭都会实际落到平台高度**；命中方块后插在原地（不会中途消失），本轮箭雨结束统一清除，任何玩家都不能捡起。
  - PvP 开始时会把场地世界 PvP 临时设为 true；如果同一世界还有别的游戏，请按世界隔离（参见 AGENTS 的多世界约定）。
  - 已接入 ArcadeParty：`games.arrowrain`（`arrowrain join` / `arrowrain start`）。
- **玩家记录**：命名空间 `arrowrain`，`games.arrowrain.firsts.<uuid>`（胜场），文件 `server/plugins/MiniGameLib/players.yml`。
