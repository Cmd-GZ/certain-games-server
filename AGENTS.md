# AGENTS.md — games-server-plugins 仓库级约定

本文件管整个 monorepo。各子工程自己的规则见其目录下的 `README.md` / `AGENTS.md`
（BuildBattle 的入口是 [服务器小游戏插件/AGENTS.md](服务器小游戏插件/AGENTS.md)）。

---

## 0. 当前状态：**正在做框架重构（2026-10-31 起）** ⚠️

上游（不懂代码、只是向 AI 许愿出了这套服务器）把重构交给了我们。
**干活前必读这一节**，否则会重复已经踩过的坑。

### 0.1 分支与交付

| 项 | 值 |
| --- | --- |
| 工作分支 | **`restruction`**（不是 `main`） |
| 交付方式 | 我方与上游**并行开发**，做完再合并；不需要保持线上不停服 |
| 验收人 | **代码/逻辑层 = 用户本人**；功能层等交付后由上游拍板 |
| 推送注意 | 默认 `ssh` 会被 `/etc/ssh/ssh_config.d/` 的权限问题打死，用<br>`GIT_SSH_COMMAND="ssh -F /dev/null -o StrictHostKeyChecking=no" git push` |

### 0.2 重构的验收基线：`docs/框架不变量.md`

**这份文档是唯一的验收标准**，任何改动都要能对上它。要点：

| # | 不变量 | 一句话 |
| --- | --- | --- |
| I1 | 玩家归属是一条链 | 不能同时属于两个**并列**对局；允许嵌套（派对 ⊃ 当前小游戏） |
| I2 | 区域归属单射 | 一块区域（整个世界或世界内一块）同时只归一个对局；没认领的是公共区 |
| I3 | 事件裁决唯一 | 一条事件只有一个"说了算的人"；**游戏模块里不许出现 `@EventHandler`** |
| I4 | 生命周期唯一出口 | 对局只能由 `end(reason)` 结束，并向子对局递归 |
| I5 | 状态转移封闭 | 状态只能按写死的转移表变化，非法转移抛异常 |
| R1 | 单一事实源 | 一个游戏的定义只存在一处（`GameKey`），菜单/观战/tab 全部派生 |
| R2 | 单一写者 | 每份持久化数据只有一个写入者；**禁止 `onDisable` 整份回写配置** |
| R3 | 失败可见 | 不许 `catch (Exception ignored)`；被吞的异常进 `Failures`，`/hub doctor` 能查 |
| R4 | 契约一致 | `pom.xml` 版本 == `plugin.yml` 版本，`tools/version-check.py` 强制 |

进度表在该文档**第 6 节**，每次改代码都要同步更新——**没写进进度表的改动等于没做**。

### 0.3 文档地图（按需只读一份）

| 想知道什么 | 读这个 |
| --- | --- |
| 重构的规矩与进度 | [服务器小游戏插件/docs/框架不变量.md](服务器小游戏插件/docs/框架不变量.md) |
| 会话模型（GameSession / 归属链）设计 | [服务器小游戏插件/docs/会话模型草案.md](服务器小游戏插件/docs/会话模型草案.md) |
| **玩家到底看到什么**（指南针→菜单→场地→游戏→观战→回主城） | [服务器小游戏插件/docs/玩家可见流程.md](服务器小游戏插件/docs/玩家可见流程.md) |
| 房间流程（等待/30s倒计时/OP 暂停，对齐上游） | [服务器小游戏插件/docs/房间流程.md](服务器小游戏插件/docs/房间流程.md) |
| 派对/房间的**编排属性** `flow`（单局包装成派对 PARTY_OF_ONE） | [服务器小游戏插件/docs/派对编排属性.md](服务器小游戏插件/docs/派对编排属性.md) |
| 「模板世界 / 游玩世界」方案评估（含实测数据） | [服务器小游戏插件/docs/模板世界方案评估.md](服务器小游戏插件/docs/模板世界方案评估.md) |
| 哪块世界归谁（I2 取证） | [服务器小游戏插件/docs/区域归属表.md](服务器小游戏插件/docs/区域归属表.md) |
| 世界命名前缀与 `/gw create` 规则（计划） | [服务器小游戏插件/docs/世界命名与前缀.md](服务器小游戏插件/docs/世界命名与前缀.md) |
| 每一步改了什么、验了没 | [服务器小游戏插件/docs/改动记录.md](服务器小游戏插件/docs/改动记录.md) |
| 接一个新小游戏（含声明地盘） | [服务器小游戏插件/HubCore/docs/游戏插件接入指南.md](服务器小游戏插件/HubCore/docs/游戏插件接入指南.md) |
| 历史踩过的坑（上传/面板/事件顺序） | [服务器小游戏插件/docs/已知坑.md](服务器小游戏插件/docs/已知坑.md) |

### 0.4 Linux 工具链（`tools/`）

上游的 `build.ps1` / `verify.ps1` / `pack-upload.ps1` 是 **Windows 专用**，Linux 服务器跑不了。
重构期用这一套，**两者并存**，细节见 [tools/README.md](tools/README.md)：

```bash
tools/fetch-maven.sh          # 装 Maven 到 .server/tools/（不污染系统）
tools/build.sh --deploy       # 全量构建 → .server/deploy/plugins/
tools/serve.sh --deploy       # 部署进服务器 → 启动（前台，Ctrl-C 或 echo stop > .server/.serve.fifo）
tools/smoke.sh --deploy       # 冒烟台：真起服 → 断言日志 → 干净关服
tools/version-check.py        # pom/plugin.yml 版本一致性（R4），不一致 exit 1
tools/maven-settings.xml      # 可选的 Maven settings：把本地仓库指向 .server/m2repo
# 服务器小游戏插件/libs/m2/   # vendor 的 com.hub:minigame-lib（IDE 靠它解析，build.sh 自动同步）
# 已合入但上游还没做完的模块：在模块目录放一个 .build-skip（写原因）即可跳过构建。
# 当前没有（FireJumper 上游补齐场地后已恢复正常构建）
tools/region-report.py        # 生成《区域归属表》（只读）
tools/fakeclient.py           # "假玩家"：协议直连登录 + 按时间表发命令（需要真人在场的流程用）
tools/smoke-arcade-party.txt  # 派对冒烟的命令脚本（配合 --fake-player 用）
# /gw 的别名是 [gworlds, world]；**没有** /gameworld（不存在）。子命令见 docs/交接.md §2.9
```

### 0.5 十条硬规则（不遵守会浪费一整轮）

1. **`build.sh` 必须 clean。** 增量构建会复用 `target/classes` 留下半成品 jar
   （类里带 ECJ 的 `Unresolved compilation problems`，插件一启用就 `java.lang.Error`）。
   已经踩过：构建"成功"但只注册了 1 个区域。
2. **`smoke.sh` 的 ERROR 正则是 ` (ERROR|SEVERE)]:`**，不是 `\\[ERROR\\]`。
   Paper 的格式是 `[06:09:08 ERROR]:`。旧写法永远匹配不到，等于断言形同虚设。
3. **`cp` 复制世界目录必须用 `--reflink=auto`，绝不能用 `always`。**
   开发机是 btrfs（能白拿 0 拷贝），**生产是 ext4**（`always` 会直接失败）。
4. **复制世界后必须删掉世界身份**，否则 Paper 拒绝加载并报
   `is a duplicate of another world ... duplicated Paper world metadata`：
   ```
   <房间>/data/paper/metadata.dat
   <房间>/session.lock
   <房间>/level.dat_old
   ```
5. **验证"场地是否正常加载"之前，先把 run 副本的配置从 `.server/server/...`（pristine）还原。**
   插件在关服时会把配置写回去，多跑几轮冒烟后 run 副本会和原始状态漂移，
   此时"区域少了几块"是**配置漂移**而不是代码回归 —— 今天已因此误判过一次。
   ```bash
   P=.server/server/small_games/*/plugins; S=.server/run/small_games/*/plugins
   for d in $P/*/; do n=$(basename "$d"); for f in "$d"*.yml "$d"*.snap; do [ -f "$f" ] && cp "$f" "$S/$n/"; done; done
   ```
6. **用户说"检查/看看"时，只检查、不改。** 结论先给出，等点头再动手。
   真实教训：用户问"`/gameworld` 是 `/gw` 的别名吗"，我查完顺手把别名加进了 `plugin.yml`、
   还改写了 `usage`，过程中**把原有的 `/world` 别名删掉了**（差点让运维习惯失效）。
   已回退。**范围锁定不是只针对跨插件——"检查"和"修改"也是两件事。**
7. **动玩家能看见的东西之前，先读 `docs/玩家可见流程.md`。**
   真实教训：重构了几天，差点不知道"游戏是通过**主城背包里的指南针**打开的"。
   玩家可见的还有：等待室道具、tab 分区、观战（末影之眼/旁观模式/聊天栏退出按钮）、
   头衔、公屏加入播报 —— 这些**不在游戏插件里**，很容易在重构中被漏掉。
8. **服务器正在跑时，绝对不要 `--deploy`。** `smoke.sh --deploy` / `serve.sh --deploy` 会覆盖
   run 目录里的 `plugins/*.jar`；JVM 已经把旧 jar 加载进内存，换文件之后新代码会引用到
   **旧 jar 里不存在的类**，表现为：
   ```
   NoClassDefFoundError: com/hub/session/PlayerSessions
       at com.hub.session.GameSession.join(...)
   ```
   看起来像代码 bug，实际上是"运行时换 jar"。**已给两个脚本加了 `session.lock` 守卫**，
   但仍要记住：`build.sh --deploy` 只写 `.server/deploy/`（安全），
   真正落地到 run 目录的是 smoke/serve。
9. **接生命周期必须成对**（`session.join` ↔ `session.leave`），而且**冒烟要测"退出后再进另一个游戏"**。
   真实事故：20 多个游戏只接了 `join` 没接 `leave` → 玩家用床退出后归属链上还挂着他 →
   点任何别的游戏都被 I1 拒绝，提示"你正在 X 的等待中"，**重进服务器无效、只能重启**。
   我的测试全是"进一个游戏就结束"，所以完全没发现。框架层已加兜底
   （`teleportToLobby` 强制 `PlayerSessions.quitToLobby`），但各游戏仍要自己摘干净。
10. **机制不确定就去读字节码**（`javap -c`），别凭 javadoc 印象下结论。
   真实教训：我曾误判 `createSection` 会保留旧键 → 编出一个不存在的"删场地复活"bug。

### 0.6 环境事实（省得每次重新测）

| 项 | 值 |
| --- | --- |
| 上游服务器包 | `.server/small_games.zip`（已解压到 `.server/server/`；`.server/` 全部 gitignore） |
| 可直接开服的副本 | `.server/run/small_games/b064ede…/` |
| JDK | `/usr/lib/jvm/java-25-openjdk`（Paper 26.2 最低 25） |
| Paper | 26.2-126，31 个插件，端口 25560，`online-mode=false` |
| 世界布局 | Paper Moonrise 布局：**`world/dimensions/minecraft/<名字>/`**，每个"世界"是一个维度目录 |
| 开发机文件系统 | btrfs（支持 reflink） |
| **生产机文件系统** | **ext4**（无 reflink），内存 32GB |
| 复制/加载成本 | 16MB 世界复制 50–115ms（冷）/ 7–12ms（页缓存热）；15 个世界启动时同一秒内加载完 → **IO 不是瓶颈** |
| 房间创建耗时（实测） | 异步复制 **9–10ms** + 主线程加载 **24–61ms**（复制已移出主线程） |
| **主线程阻塞（已闭环）** | 插件级 3 个热点**已修**：`MurderMystery GoldScan`（同步 `getChunkAt`）、`TNTBattle loadLeaderboard`（`location.getChunk()`）、`GuessTheBuild Arena#applyFloor`（整块地板一次性重铺，实测 4.1s）。卡顿 4183+969ms → 归因仅剩 **3595ms 的 `SavedDataStorage#saveAndJoin`**，即 **Paper 启动时给 15 个世界存盘**（`Saving chunks for level ...`），框架自身、与世界数量成正比，插件层修不了。`/hub doctor` 的 `[卡顿]` 段看可疑帧 |
| **上游回退过的功能** | 「密室杀手开局打乱全员皮肤」——上游加了又回退（本快照里没有）。它是 `Unsafe#park` 型阻塞（逐个解析 `PlayerProfile`）。**要重做必须异步批量取档案，不能在主线程逐个解析** |
| 假玩家登录延迟 | `tools/fakeclient.py` 从连上到登录成功实测 **2–20 秒**波动 → **别写\"第 N 秒发命令\"的时间表**，要挑对时序不敏感的判据（例：会话数量 `1 → 0 → 1`） |
| 服务端堆 | `-Xms1G -Xmx2G`（**物理 32GB 用不上**）；开服后实测 421–824MB（20–40%）。见 D13 |

### 0.7 已定决策（用户拍板，别再问一遍）

| # | 决策 | 备注 |
| --- | --- | --- |
| D1 | **主城不模板化** | 主城是唯一的"持久游玩世界"；理由：玩家会留痕迹（建筑/告示牌）、`world/players/` 是玩家存档不能跟着模板复制。等有新需求再重新考虑 |
| D2 | **房间上限：每游戏 8 个（暂时取舍，不是 UI 限制）** | 9×3 箱子有 27 格，理论上能放 1+26；8 只是当前先按 8 走。⚠️ 我曾误说"9×3 只能放 1+8"，已纠正 |
| D9 | **全服房间数不限制**（`max-rooms: 0`） | 与 D2 同源：先把功能跑通，配额等有真实压力再加 |
| D10 | 选房 UI 语义 | **第一格永远**是"创建新房间"；其后 8 格**有房间才显示**，没房间就是空的（不是占位） |
| D3 | **模板瘦身暂时不做** | 8 房间 × 16MB ≈ 128MB，磁盘无压力。等真有压力再做"发布期裁剪"（见 `docs/模板世界方案评估.md` §8） |
| D4 | **房间世界放 tmpfs 是可选优化，暂不做** | ext4 上复制 50–115ms 已够快；tmpfs 主要解决"泄漏"和"删除慢"。等 R4（回收/监控）再评估 |
| D5 | 单局小游戏 = 统一**协议**（session + 房间），不统一**流程** | 单局不需要等待室/领奖台；派对是 `COMPOSITE` 串多个子 session |
| D6 | `end()` 重复调用 → **警告忽略**（幂等） | 不抛异常，避免"时间到"和"最后一人退出"同时触发时报错给玩家 |
| D7 | `attach` 冲突 → 框架内**抛异常**，但 `session.join()` 捕获后记 `Failures` + 提示 + 返回 false | 程序错误要留响，玩家体验不能崩 |
| D8 | 我的工作**只推到 `restruction`**；上游是我们 fork 出来的那个仓库 | 见下方"仓库关系" |
| D14 | **模板"热度"由插件自动统计，不让人肉配置** | 按实际创建次数/最近使用时间自动决定给哪个模板留就绪位；维护者只需（可选）设上限 |
| D12 | **就绪池分层，不做大池** | 每模板默认就绪 **1** 个、热门可配 2–3、**全服就绪总数上限 4–8**，按最近使用频率补货，队列空则退回现做现建。理由：每个就绪槽 = 一个常驻已加载世界（约 20–50MB 堆），而房间创建只要 33–71ms，大池的收益递减、成本线性 |
| D13 | **扩大常驻世界前必须先调 `-Xmx`** | 当前是 `-Xms1G -Xmx2G`，实测开服后 421–824MB（20–40%）。物理内存 32GB 用不上，堆被 `-Xmx` 卡死。建议改 `-Xms4G -Xmx6G`。`/hub doctor` 的 `[内存]` 段到 80% 就是警戒线 |
| D17 | **派对是"房间的编排属性"，不是嵌套层数** | 判断只需一条：**父 session 是否必须比子活得久**。`SINGLE` 与 `PARTY_OF_ONE`（单局包装成派对）共用同一条代码路径 —— 一个房间、一个 session，差别只在 UI/流程；**只有 `PARTY_MULTI` 才用 `COMPOSITE` 父子**。`GameSession` 的 `parent/children/adopt` 保留但单局不启用（零开销、子树为空）。详见 `docs/会话模型草案.md` 末尾补充节。**第一期已落地（2026-11-01，ArcadeParty 1.4.0）**：`flow: party|single` 编排属性，`single` = PARTY_OF_ONE = 一个 session、无 children、跳过领奖台；`PARTY_MULTI` 留给第二期（等被包装的游戏先各自接入 session）。见 `docs/派对编排属性.md` |
| D15 | **派对类游戏同样要房间化**（不是"可以不接"） | 房间化的两个收益：① 同时跑多局；② **把单局小游戏包装成"只有一个游戏、没有领奖流程"的派对，让流程标准化**。所以 `ArcadeParty` 那一类不能跳过 |
| D16 | **HTF 依赖 MiniGameLib，`HubBridge` 退役** | 用户交给我判断。定：① 玩法一行不改；② 其余与其它 23 个游戏保持一致（`provided` 依赖 MiniGameLib + `depend: [MiniGameLib, HubCore]`）。`HubBridge` 是"让 HTF 适配上游"留下的技术债（反射式兼容层），一致性优先于它的独立运行能力 |
| **D18** | **验收标准是"上游的 features/游戏机制"，不是上游的代码结构** | 我们的任务是**重构**上游，不是同步它的实现：同一个功能完全可以用我们的架构重写。合并上游时的正确做法是——**先把它的提交读成需求清单**（"该修什么/该有什么玩法"），再在 `restruction` 的架构里实现；**不要照搬它的代码形状**（它不是我们这一版的约束）。HTF 同理：**只要保证游戏机制与 `design.md` 一致**即可 |
| **D19** | **世界三段命名：`tmpl_` 模板 / `room_` 房间实例 / 无前缀 持久世界** | `/gw create <名字>` 造**模板**（自动补 `tmpl_`，**不自动加载**）；`/gw create --persist <名字>` 造**持久世界**（不补前缀、开服自动加载，主城这类）。`room_` 是引擎独占（会自动回收），**手建一律拒绝**（`--persist` 也拒绝）。**持久世界不用 `room_`**：`room_` 意味着"可被回收"，而主城恰恰是唯一的永久世界（D1）—— 命名不能暗示它可以删。历史世界（`world_gtm` 等）**全部重命名为 `tmpl_`**（它们就是未来的模板）。计划与排序（N1 命令规则 → N2 R3 UI → N3 批量重命名）见 `docs/世界命名与前缀.md` |
| **D20** | **房间流程暂按上游 features 走** | 加入房间等待直到人数 **≥2** → **30 秒**倒计时开局；**队列里有 OP 则暂停自动倒计时、等 OP 手动开**（无论人数）。用户**对这套流程不满意**，但要先与上游协商后再改 → 本版照做、不擅自改。实现就是 `MiniGameLib/com/hub/util/WaitCountdown`（**24 个游戏全部已用它**），详见 `docs/房间流程.md`。⚠️ 房间化后它的实例键要从"场地名"改成"房间 id"，否则两个房间的倒计时会互相取消 |
| **D21** | **目标房间流程（上游已同意，可实施）** | 倒计时 **300s**；**最早加入者 = 主持人**，开始权限归主持人；**超时未开始则踢掉主持人**；**主持人退出则重置倒计时**；**OP 加入 = 接管主持 + 不再倒计时 + 不限制最小人数**。**上游已同意（2026-11-01）**。**框架层已实现（`WaitCountdown` 四个可选钩子，默认关闭）**，HillKing 已试点（`flow: host`）。**四条行为已用 3 个假玩家实测通过**（最早加入者为主持人 / 主持人能开局 / 超时踢主持人且不自动开局 / 主持人退出重置倒计时）。详见 `docs/房间流程.md` §5–§6 |
| **D22** | **所有小游戏一律按"多份状态"建模；房间化后键是【房间 id】** | ① 不要按"单场地/多场地"分两套写法——上游经常加地图，分两套就得重写。② **地图差异由房间初始化消化**：每张模板复制成一个房间，游戏不必再关心"一个世界里切哪块区域"。③ 但**同一游戏可同时开多个房间**（D2 上限 8），所以插件仍要持有**多份**对局状态——只是键从"场地名"变成"**房间 id**"。会话层统一 `Map<键, Session>`；绑定房间之前先用场地名占位。样板：TntRun（原生多图）、MemoryFlip（单图但已用 Map）|
| **D23** | **玩家已在 A 局时加入 B 局 → 一律拒绝** | 用户定了这一条（普通玩家走 GUI，正常不会遇到；遇到就拒绝并提示"你正在另一局游戏里，请先退出"）。实现要点：**会话挂接必须在把玩家写进本游戏队列之前**，失败就直接 `return`，否则会留下"游戏队列里有他、归属链上没他"的**半加入状态**（doctor 查不出来）。另：玩家点别的游戏是**正常操作**，`GameSession.join` 不再把它记进 `Failures`（那会刷满 doctor、把真异常淹掉），只记一行 INFO |
| **D24** | **`/hub` 是高层语义命令，`/gw` 是它下面的原子实现** | 用户定的分层：`/hub join <room>` 与 `/hub create <game> <map>` 面向玩家/运维；`/gw create room` / `/gw tp` 是原子操作。`/hub` 的职责是 **① 校验能不能做 → ② 以框架身份执行对应的 `/gw` 原子操作（**不让玩家需要 `/gw` 的权限**）→ ③ 初始化玩家状态**（传送 + 跑该游戏的 join-command）。命令尽量原子化；用 GUI 的玩家对此无感。⚠️ **map ↔ 模板世界不是 1:1**：一个模板世界可能装多张地图，所以 `/hub create <game> <map>` 要先 `map → 模板世界` 解析，再 `/gw create room <模板>`，并记下**选的是哪张 map** 作为房间初始化的一部分 |
| **D25** | **房间与对局 1:1；对局结束房间就销毁** | 一个房间只装一局，**不需要"复用"**。用户特别指出理由：**破坏性游戏**（TNTBattle 这种会改地形/放 TNT 的）如果房间被复用，上一局炸烂的地形会带到下一局 —— 只能靠"每局一个新房间"保证干净。配套：房间的"空闲回收"退化成兜底（正常路径是**对局结束 → 删房间**）|
| D11 | **不做"房间复用池"**（含"缓存已加载 World"的变体） | 已加载世界无法便宜还原 → 池只能"预热"不能"复用"；而创建只要异步复制 9–10ms + 加载 24–61ms，玩家感知不到。若将来真要优化，做**"备用 1 个"的懒预热**（每游戏 1 槽、后台补），不做大池。⚠️ **"池 + 拷贝"不成立**：已加载世界无法克隆、其目录也不能安全复制（session.lock/未刷盘），而"产物目录拷贝"省不下时间（页缓存已在做）。详见评估文档 §8.4.1 |

### 0.8 仓库关系（很容易搞错）

| remote | 地址 | 用途 |
| --- | --- | --- |
| `origin` | `git@github.com:Cmd-GZ/certain-games-server.git` | **我们自己的 fork**，日常推这里 |
| `upstream` | `https://github.com/Breeze-dust/games-server-plugins.git` | **真正的上游**（Breeze-dust），只 fetch 不合入别人的分支 |

- 合并上游：`git fetch upstream && git merge upstream/main`（2026-10-31 已合过 3 个提交，
  其中 `65f6881` 补上了 OneShot 完整源码 + TNTBattle 1.12.1 多场地）。
- 子模块 `paper-hold-the-fort` 的 checkout 在 `/home/user/MyProject/paper-hold-the-fort`，
  **当前是只读文件系统**，fetch/写都做不了；要用它得先请用户放开权限。

---

## 1. 子仓库（git submodule）改动规矩

`paper-hold-the-fort/` 是独立仓库，以 submodule 引入；以后新增的 submodule 同样适用。

1. **不要在 `master` / `main` 上直接改。** 任何修改都先自建分支。
2. **每次修改前先拉取上游**，确保基于最新提交：
   ```bash
   cd paper-hold-the-fort
   git fetch origin
   git switch master
   git pull --ff-only
   ```
3. **在自建分支上提交和推送**：
   ```bash
   git switch -c work/<主题>
   # ...修改...
   git add -A && git commit -m "feat: <主题>"
   git push -u origin work/<主题>
   ```
4. **主仓库只记录子仓库的 commit 指针。** 子仓库的改动必须先推到它自己的远端，
   再回主仓库提交 `paper-hold-the-fort` 的指针变化；不要只提交主仓库、
   让子仓库的改动停留在本地未推送状态。
5. 主仓库的主分支上不要直接改子仓库里的文件——那样既不在子仓库的分支流程里，
   也很容易在别人拉取时丢失。

> 注意：工作区里的 `paper-hold-the-fort/` 是**空目录**（子模块未 init）。
> 真正的 checkout 在 `/home/user/MyProject/paper-hold-the-fort`（工作区外，写入需额外授权），
> 它的 `master` 比主仓库指针新。改它之前先按上面的规矩拉取 + 建 `restruction` 分支。
