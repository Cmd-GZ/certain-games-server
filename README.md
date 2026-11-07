# server-data —— 服务器本体（只有服务器，没有源码）

本分支的**根目录就是服务器根**：`server.jar` 就在这里。

- 代码在另一条分支 **`restruction`**（`服务器小游戏插件/` 等）。
- 起服（JDK **25** 必需）：

```bash
java -Xms4G -Xmx6G -jar server.jar nogui
```

- 关服：控制台输入 `stop`

## 里面有什么

| 有 | 说明 |
| --- | --- |
| `plugins/` | 33 个插件 jar + 全部配置与数据 |
| `world/dimensions/minecraft/tmpl_*` | 13 个**模板世界**（房间按需复制） |
| `world/`（overworld / the_end / the_nether）+ `world_htf` | 主城等持久世界、HTF 战场 |
| `libraries/`、`versions/`、`server.jar` | Paper 本体与依赖 |
| `config/`、`server.properties`、`ops.json`、`eula.txt` | 服务器配置 |

## 不含（有意排除）

`cache/`、`logs/`、`crash-reports/`、测试期 `room_*` 世界、`session.lock`、`plugins-disabled/`、
`server-pack-20261018-165457.zip`（247MB，超 GitHub 单文件 100MB 硬限，需要时单独传）。


## 提交策略（什么进 git、什么不进）

**判据：跑一次服之后会不会被服务器自己改写，以及"别人 clone 下来还能不能复现"。**

| 类别 | 入库？ | 例子 |
| --- | --- | --- |
| 插件 jar | ✅ | `plugins/*.jar`（33 个）|
| 插件配置 / 数据 | ✅ | `plugins/*/config.yml`、`plugins/*/players.yml`、`plugins/MiniGameLib/{maps,templates}.yml` |
| 模板世界 `tmpl_*` | ✅ | `world/dimensions/minecraft/tmpl_*/**`（房间从它们复制，本身不会被跑改）|
| 服务端本体与依赖 | ✅ | `server.jar`、`libraries/`、`versions/` |
| 服务器配置 | ✅ | `server.properties`、`ops.json`、`eula.txt`、`bukkit.yml`、`spigot.yml` |
| 主城 / 下界 / 末地 / `world_htf` | ✅（**有改动请提交，但别把"只因为跑过一次"的差异一起提交**）| `world/dimensions/minecraft/overworld/**` |
| 玩家个人数据 | ❌ | `world/players/**`（每人进一次就变）|
| 运行时缓存 | ❌ | `usercache.json`、`.paper/`、`plugins/spark/tmp/` |
| 世界锁 | ❌ | `**/session.lock`、`*.lock`（带上会"重复世界"起不了服）|
| 日志 / 崩溃 / 房间世界 | ❌ | `logs/`、`crash-reports/`、`room_*` |
| 迁移备份 | ❌ | `*.bak`、`*.bak-migration` |

> `server.properties` 每次开服都会改一个时间戳注释行，属于正常噪声；
> 真正的配置项改动（端口/motd 等）**应该**提交。

## 常用命令

```
/hub doctor                       一体检（插件/契约/目录/菜单/卡顿/被吞异常）
/hub create <游戏> <地图> [房间名]
/hub join <房间名>
/hub room maps <游戏>              看这个游戏有哪些地图
```
