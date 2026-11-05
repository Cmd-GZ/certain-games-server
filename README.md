# games-server-plugins

Paper 26.2 / Java 25 服务器插件合集：一套自研的主城 + 多小游戏服务端工程，外加两个独立插件。

## 仓库结构

| 路径 | 说明 |
| --- | --- |
| `服务器小游戏插件/` | **BuildBattle 主工程**：`MiniGameLib` 公共库 + `HubCore` 主城 + `GameWorlds` 多世界 + 27 个游戏/工具模块（共 30 个 Maven 模块），含 `docs/` 与 PowerShell 构建/部署脚本 |
| `PlayerTitles/` | 头衔插件，通过 Bukkit Services 暴露 `TitleProvider` |
| `JoinQuitBroadcast/` | 进服/退服播报，编译期依赖 `PlayerTitles` |
| `paper-hold-the-fort/` | Hold the Fort 战线模式（Gradle / Kotlin DSL）。**独立仓库，以 git submodule 引入** |
| `新服务器小游戏插件/` | 发布说明（`安装说明.md`）；同目录的 jar 是构建产物，不入库 |

各子工程的细节以各自 `README.md` 为准；BuildBattle 的 AI 协作入口是
[服务器小游戏插件/AGENTS.md](服务器小游戏插件/AGENTS.md)。

## 环境要求

- **JDK 25**（Paper 26.2 的最低要求）
- **Maven 3.9+**（BuildBattle 也可以只用自带的 `build.ps1`）
- 首次构建需要联网，从 `https://repo.papermc.io` 下载 Paper API

## 构建

### Maven（CI 使用的方式）

```bash
cd 服务器小游戏插件

# MiniGameLib 是其他所有模块的编译依赖，必须先 install
mvn -B -f MiniGameLib/pom.xml install -DskipTests

# 其余模块（含 GameWorlds）逐个打包
for pom in */pom.xml; do
  case "$pom" in MiniGameLib/pom.xml) continue ;; esac
  mvn -B -q -f "$pom" package -DskipTests
done

# 独立插件：PlayerTitles 先 install，JoinQuitBroadcast 再打包
cd ../PlayerTitles && mvn -B -q install -DskipTests
cd ../JoinQuitBroadcast && mvn -B -q package -DskipTests
```

### PowerShell（BuildBattle 原有方式）

```powershell
cd 服务器小游戏插件
.\build.bat              # 全部插件 -> .build\out\
.\build.bat -Deploy      # 同时复制到 server\plugins\
.\build.bat -Only SkyPit  # 只编一个（自动带上 MiniGameLib / HubCore）
```

`build.ps1` 优先使用 `server\jdk` 里的 JDK；新克隆没有 `server\` 时回退到 `JAVA_HOME`
或 PATH 上的 `javac`。编译依赖来自 `%USERPROFILE%\.m2`（跑一次 Maven 构建即可填好）
或 `toolchain\.m2`。

### Hold the Fort

```bash
cd paper-hold-the-fort
./gradlew build
```

## 不入库的内容

- `服务器小游戏插件/server/`：Paper 服务端本体、世界存档、插件运行数据（约 650 MB）
- `服务器小游戏插件/toolchain/`、`.build/`、各模块 `target/`
- `服务器小游戏插件/backups/`：玩家记录快照（含玩家数据）
- 所有 `*.jar` 与 `.claude/`

因此克隆后需要自备 Paper 服务端；源码里的 `src/main/resources/*.yml` 只是模板，
线上生效的配置在 `server/plugins/<插件名>/` 下，不在版本控制内。

## 文档入口

- 仓库级约定（含子仓库分支流程）：[AGENTS.md](AGENTS.md)
- 构建 / 部署 / 已知坑：`服务器小游戏插件/docs/`
- 玩家记录与备份：`服务器小游戏插件/docs/玩家记录.md`
- 接入新小游戏：`服务器小游戏插件/HubCore/docs/游戏插件接入指南.md`

## CI

[.github/workflows/build.yml](.github/workflows/build.yml) 在 push / PR 时用 JDK 25
按依赖顺序编译全部模块。子模块不参与构建，CI 不拉取。

## 子模块

```bash
git clone --recurse-submodules git@github.com:Breeze-dust/games-server-plugins.git
# 已有克隆：
git submodule update --init
```

修改子仓库前先拉上游、再自建分支，不要在 `master` / `main` 上直接改；
完整流程见 [AGENTS.md](AGENTS.md)。
