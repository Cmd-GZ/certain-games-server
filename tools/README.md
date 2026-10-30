# tools/ —— 重构期用的脚本

> 全部是 **Linux 可用**的脚本。上游原有的 `build.ps1` / `verify.ps1` / `pack-upload.ps1` 都是
> Windows / PowerShell 专用（会去找 `server\jdk\bin\javac.exe`），在 Linux 服务器上跑不了，
> 所以重构期用这一套顶上。**不替代**上游脚本，两边并存。

| 脚本 | 干什么 | 会不会改东西 |
| --- | --- | --- |
| `fetch-maven.sh` | 把 Maven 装到 `.server/tools/`（不污染系统） | 只往 `.server/tools/` 写 |
| `build.sh` | Maven + JDK 25 全量构建 26 个模块 + PlayerTitles + JoinQuitBroadcast | 只写各模块 `target/`；`--deploy` 额外写 `.server/deploy/` |
| `serve.sh` | **人工测试服**：前台起服，Ctrl-C 或 `stop` 优雅关服 | 写 `.server/.serve.fifo`；`--deploy` 覆盖 `plugins/*.jar`；`--fresh` 重建工作副本 |
| `smoke.sh` | **冒烟台**：真起一个 Paper 服务器 → 灌命令 → 断言日志 → 干净关服 | 只写 `.server/smoke.log`；`--deploy` 会覆盖服务器 `plugins/*.jar` |
| `version-check.py` | 检查/修正 `pom.xml` 版本与 `plugin.yml` 版本一致（R4） | `--fix` 时改 pom |
| `region-report.py` | 生成《区域归属表》（I2 的取证） | **只读**，只写报告 md |

## 典型流程

```bash
# 第一次（装工具链）
tools/fetch-maven.sh

# 日常：改完代码 → 构建 → 冒烟
tools/build.sh --deploy
tools/smoke.sh --deploy

# 进游戏人工验证
tools/serve.sh --deploy                # 起服，然后客户端连 <本机IP>:25560
#   关服：Ctrl-C 一次，或另开终端 echo stop > .server/.serve.fifo

# 发版前
tools/version-check.py                 # 不一致就 exit 1
tools/region-report.py --out 服务器小游戏插件/docs/区域归属表.md
tools/smoke.sh --deploy --expect 'SkyPit v1\.0\.3'
```

### 人工测试服的三个注意点

| 注意 | 说明 |
| --- | --- |
| **必须用 `stop` 关服，别直接杀进程** | 存档和 6 个"关服回写配置"的插件（`MurderMystery / SkyPit / TntRun / DuelArena / HotPotato / BuildRun`）需要走完关服流程；`serve.sh` 的 Ctrl-C 已经帮你转成控制台 `stop` |
| `--fresh` 才干净 | `.server/run/` 是工作副本，反复测试会修改世界数据。要回到"刚解压"的状态用 `--fresh`（它会顺便补上包里缺的 `world/level.dat`） |
| 连不上先查两件事 | ① 端口是否 `LISTEN`（`ss -lnt \| grep 25560`）；② 服务器是别的机器就用 `192.168.x.x:25560`，`online-mode=false` 不用正版 |

## `smoke.sh` 在验什么

对应 `docs/框架不变量.md` 的验收方式（T4 层）。它固定做这几条：

1. 服务器能起到 `Done (`（起不来 = 直接失败）；
2. 统计 `Loading server plugin` 的条数（期望 31）；
3. `[ERROR]` / `[SEVERE]` 必须为 0；
4. 额外的 `--expect '正则'` / `--reject '正则'` 断言；
5. 干净关服（`JAVA_EXIT=0`）。

`--commands FILE` 可以灌一段控制台命令（一行一条，`#` 开头是注释），用来验"命令链路"。
相对路径会在参数解析阶段转成绝对路径（脚本中途会 `cd` 进服务器目录）；文件不存在直接报错。
仓库里有个例子 `.server/smoke-commands.txt`（跑 `hub doctor`）。
**注意**：需要客户端的交互（真的拿两个人对打验 PvP）这里做不到 —— Paper 26.2 没有可用的
MockBukkit，也没有测试客户端。这部分只能靠上游进游戏实测，或者后续做"假玩家"驱动。

## 已知限制

- `smoke.sh` 用的是**整份服务器副本**（`.server/run/...`），起服会写世界数据。
  要绝对干净就每次从 `.server/server/` 重新 `cp -a` 一份。
- `.server/` 整个目录在 `.gitignore` 里，脚本产物不会进版本库。
- `region-report.py` 需要 PyYAML。解析规则在脚本头部有说明（三种场地表示法 + 字符串坐标）。
