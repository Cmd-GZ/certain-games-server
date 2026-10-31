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
| 「模板世界 / 游玩世界」方案评估（含实测数据） | [服务器小游戏插件/docs/模板世界方案评估.md](服务器小游戏插件/docs/模板世界方案评估.md) |
| 哪块世界归谁（I2 取证） | [服务器小游戏插件/docs/区域归属表.md](服务器小游戏插件/docs/区域归属表.md) |
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
tools/region-report.py        # 生成《区域归属表》（只读）
```

### 0.5 五条硬规则（不遵守会浪费一整轮）

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
5. **机制不确定就去读字节码**（`javap -c`），别凭 javadoc 印象下结论。
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
| **已知主线程阻塞** | 启动阶段实测有 **4435ms** 与 **1040ms** 两次（上游插件 onEnable 里做重活）。`/hub doctor` 的 `[卡顿]` 段可持续观察 |
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
| D12 | **就绪池分层，不做大池** | 每模板默认就绪 **1** 个、热门可配 2–3、**全服就绪总数上限 4–8**，按最近使用频率补货，队列空则退回现做现建。理由：每个就绪槽 = 一个常驻已加载世界（约 20–50MB 堆），而房间创建只要 33–71ms，大池的收益递减、成本线性 |
| D13 | **扩大常驻世界前必须先调 `-Xmx`** | 当前是 `-Xms1G -Xmx2G`，实测开服后 421–824MB（20–40%）。物理内存 32GB 用不上，堆被 `-Xmx` 卡死。建议改 `-Xms4G -Xmx6G`。`/hub doctor` 的 `[内存]` 段到 80% 就是警戒线 |
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
