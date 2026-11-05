# AGENTS.md —— AI 项目入口（BuildBattle）

> 用户的开场话术通常是：**读 `docs/开发须知.md`，并遵守它第 8 节的约定；本次只做一件事：`<需求>`**。
> 本文件是浓缩版入口。**先读这里**，需要细节再按 §3 路由表只读那一个文件，
> 不要整份重读文档、不要为了"了解上下文"通读插件源码。
>
> ⚠️ **2026-10-31 起本工程进入框架重构，验收基线是 `docs/框架不变量.md`。**
> 任何改动都必须能对上那 5 条不变量 + 4 条硬规则；进度表在它第 6 节，**没写进进度表的改动等于没做**。
> 重构分支是 **`restruction`**，跑在 **Linux** 上（`build.ps1` 是 Windows 专用，用不了了，见 §4）。

## 1. 六条铁律（每次会话都生效）

1. **先要证据再改代码**：需要日志/配置/文件大小时直接问用户；不许连续猜超过两轮；
   **机制不确定就去读服务器 jar 的字节码（`javap -c`），别凭记忆或 javadoc 印象**。
   真实教训：曾凭 javadoc 印象误判 `ConfigurationSection#createSection` 会保留旧键，
   编出一个不存在的"删场地复活"bug；读字节码才发现它是 `new MemorySection` + `Map.put` 替换。
   找不到原因就加一行临时诊断日志。
2. **范围锁定**：只改用户指定的文件/插件，不顺手动别的插件或 MiniGameLib/HubCore；要扩大范围先问。
3. **有歧义先问**：游戏规则类歧义（误伤算谁死、谁算赢、平局怎么算）先问清楚再动手；
   架构类歧义先写成 `docs/` 草案给用户审，审过再动手。
4. **改完必须**：构建 → 冒烟 → 报告「传哪几个 jar + 各自字节大小 + 在游戏里怎么验证」。
   Linux 上用 `tools/build.sh --deploy` + `tools/smoke.sh --deploy`（见 §4）。
5. **回答尽量短**：细节写进文件（插件 README / docs），不要重复贴用户已知的代码；
   要改大文件前先说明"只需读哪几段"，不要整份重读。
6. **一轮只做一件事**，做完收尾；用户要做第二件事时提醒他新开会话（同一 bug 的改→验证→微调除外）。
   **不许把构建留在半成品状态**——宁可少做一点，也要保证每轮结束时 `tools/build.sh` 是绿的。

## 2. 项目是什么（30 秒）

Paper 26.2 / Java 25，端口 25560，`online-mode=false`，一个主城 + 一堆小游戏。

**30 个 Maven 模块 = 框架 3 个（MiniGameLib / HubCore / GameWorlds）+ 游戏/工具 27 个**：

```
MiniGameLib（公共库）        HubCore（主城：菜单/tab/游戏注册表）
    ↑                             ↑
    └────── 27 个游戏/工具模块 ────┘       GameWorlds（多世界托管）
```

- 依赖方向：**游戏 + HubCore → MiniGameLib（编译期 provided）**；
  运行时所有游戏 `depend: [MiniGameLib, HubCore]`、`softdepend: [GameWorlds]`（后者纯粹为了加载顺序）。
- **改了 MiniGameLib ⇒ 全部 jar 都要一起重传**（它是所有人的编译依赖）。
- `HoldTheFort` 是 **git submodule**（`paper-hold-the-fort`，Gradle 项目）：
  `tools/build.sh` 不管它，本机用 `.server/htf-build.sh` 手工 javac 出 jar；正式发版要有 Gradle 的环境跑 `./gradlew build`。
- `OneShot` **已在上游合并时补全源码**（是普通 Maven 模块，D22 已房间化 1.8.0），不再是"只有 jar"。
- HubCore 登记 **27 个游戏 key**；整服插件数 **33 个**（含 DevTools / GameWorlds / PlayerTitles / Quark 等非游戏插件）。
- 玩家记录（战绩/击杀/第一名/玩家名）**只有一个文件**：`server/plugins/MiniGameLib/players.yml`。

### 重构期已新增的公共能力（都在 MiniGameLib）

| 包 | 干什么 | 对应不变量 |
| --- | --- | --- |
| `com.hub.region` | `ArenaRegion` / `RegionRegistry` / `Regions`：声明"这块地归我" + 加载期冲突检测 | I2 |
| `com.hub.session` | `EndReason` / `SessionState` / `GameSession` / `PlayerSessions`：对局生命周期 + 玩家归属链 | I1 / I4 / I5 |
| `com.hub.diagnostics` | `Failures`：被吞异常登记处，`/hub doctor` 能查 | R3 |

## 3. 路由表：要做的事 → 只读这些

| 要做的事 | 只读这个 | 大概成本 |
| --- | --- | --- |
| **重构的规矩、进度、每条不变量的落地程度** | `docs/框架不变量.md`（尤其第 6 节进度表） | ~2k token |
| 会话模型怎么用（GameSession / 归属链） | `docs/会话模型草案.md` | ~1.5k token |
| 世界模板化 / 房间化方案 | `docs/模板世界方案评估.md` | ~2k token |
| 哪块世界归谁、有没有重叠 | `docs/区域归属表.md`（`tools/region-report.py` 生成） | ~1k token |
| 刚才改了什么、验没验 | `docs/改动记录.md`（**append-only，最新在最上面**，看最近 2–3 条即可） | — |
| 接一个新小游戏（含"声明地盘"第 4 步） | `HubCore/docs/游戏插件接入指南.md` | ~1.5k token |
| 改某个小游戏的玩法/事件 | `<插件>/README.md` 顶部的「速查卡」 | ~400 token |
| "本地好、上传就坏"、某个游戏不对劲 | `docs/已知坑.md`（症状→根因→怎么确认） | ~1k token |
| 玩家记录 / 战绩 / 备份 | `docs/玩家记录.md` | ~1k token |
| 主城菜单/物品/场地羊毛颜色 | `HubCore/README.md` 速查卡 + `server/plugins/HubCore/config.yml` 的 `menu` 段 | — |
| 世界规则（pvp/难度/刷怪） | `server/plugins/GameWorlds/config.yml`（真实生效的那份） | — |

## 4. 命令

### Linux（当前环境，重构期主力）

```bash
cd /home/user/MyProject/certain-games-server
tools/fetch-maven.sh          # 首次：装 Maven 到 .server/tools/
tools/build.sh --deploy       # 全量构建（clean）→ .server/deploy/plugins/
tools/serve.sh --deploy       # 部署进服务器 → 前台启动
                              #   关服：echo stop > .server/.serve.fifo  或按一次 Ctrl-C
tools/smoke.sh --deploy --commands 文件 --expect '正则' --reject '正则'
tools/version-check.py        # pom/plugin.yml 版本一致性（不一致 exit 1，可进 CI）
tools/region-report.py --out 服务器小游戏插件/docs/区域归属表.md
```

进服务器：`<本机IP>:25560`，OP 名单见 `server/ops.json`。

### Windows（上游原有，重构期保留但不是主力）

```powershell
D:\BuildBattle\build.bat                      # 编译全部插件（产物 .build\out\）
D:\BuildBattle\build.bat -Only SkyPit         # 只编一个（自动带上 MiniGameLib/HubCore）
D:\BuildBattle\build.bat -Deploy              # 编译并复制到 server\plugins\
powershell -ExecutionPolicy Bypass -File verify.ps1        # 上传前预检
D:\BuildBattle\pack-upload.bat                # 打包上传
```

## 5. 硬性约定（违反了就会"本地行、上传不行"）

1. **`.ps1` 必须是纯 ASCII**（PS 5.1 无 BOM 时按 ANSI 读，中文注释直接语法错误）；中文写进 `.md`。
2. **YAML 的 `键: 值` 冒号后必须有空格**：`detective-stone-seconds:90` 会让整份配置加载失败（表现为"场地全没了"）。
3. `gamehub:` 命名空间**不要改名**（写在各插件 kit 存档的物品数据里）。
4. 插件数据目录是 `server/plugins/<插件名>/`；源码里的 `src/main/resources/*.yml` 只是模板，
   **改配置要连线上那份一起改**。
5. **`onDisable` 不许再用内存整份回写配置**（R2）。已修：`MurderMystery / TntRun / HotPotato / DuelArena / SkyPit`
   的 `saveAll()` 开头都加了 `plugin.reloadConfig()`——语义是"磁盘上别的键保持现状，只写自己负责的段"。
   新写的保存逻辑照这个模式来；`BuildRun` 早就是这个写法，可以当范本。
6. **同优先级（HIGHEST）事件拦截必须限定自己的区域/世界**（I2/I3）。
   真实事故：天坑的 PvP 守卫曾对**所有世界**无条件取消互伤，打掉了 `world_duel` / `world_lp` 的 `pvp: true`，
   那些游戏只能反向在 HIGHEST 且 `ignoreCancelled=false` 抢回来。
   **重构目标：游戏模块里不出现 `@EventHandler`**（全部走框架的作用域裁决）。
7. **上传面板铁律**：先停面板服 → 再覆盖 → 再启动；不能只传 `plugins/*.jar`，
   `config/` 与各插件的 `config.yml / data.yml / words.yml / arena-*.snap` 都要在。

## 6. 汇报格式（用户要的收尾）

```
改了什么：<文件列表，一句话一个>
编译：tools/build.sh --deploy 成功（或 build.ps1 -Deploy）
冒烟：tools/smoke.sh → Done (xx.xs) / 33 插件 / ERROR 0 行
要传的 jar：<逐字节大小>
游戏里怎么验证：<具体命令/操作 + 期望现象>
写进文档：<更新了哪个 README / docs + 框架不变量第 6 节进度表是否同步>
下一步：<建议是否新开会话>
```
