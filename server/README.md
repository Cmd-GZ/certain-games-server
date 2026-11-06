# 服务器本体（供上游协同排查 bug）

这里是**跑起来的那一份服务器**（Paper 26.2 / 33 个插件 / 13 个模板世界），
放在独立分支 `server-data` 上，避免污染代码分支 `restruction` 的体积。

## 怎么用

1. 切到本分支：`git switch server-data`
2. 直接把 `server/` 当作服务器根目录（`server.jar` 就在这一层）
3. 启动（JDK **25** 必需）：

```bash
cd server
java -Xms4G -Xmx6G -jar server.jar nogui
```

4. 关服：控制台输入 `stop`

## 里面有什么 / 没有什么

| 有 | 说明 |
| --- | --- |
| `plugins/` | 33 个插件 jar（当前构建）+ 全部配置与数据 |
| `world/dimensions/minecraft/tmpl_*` | 13 个**模板世界**（房间按需从它们复制） |
| `world/`（overworld / the_end / the_nether） | 主城等持久世界 |
| `world/dimensions/minecraft/world_htf` | HTF 用的持久世界 |
| `config/`、`server.properties`、`ops.json`、`banned-*.json`、`eula.txt` | 服务器配置 |
| `libraries/`、`versions/`、`server.jar` | Paper 本体与依赖（省得再下） |

| 没有（被有意排除） | 原因 |
| --- | --- |
| `cache/`、`logs/`、`crash-reports/` | 运行期垃圾（约 76MB） |
| `world/dimensions/minecraft/room_*` | 测试期房间实例（房间会按需重建，曾占 796MB） |
| `server-pack-20261018-165457.zip` | **247MB，超过 GitHub 单文件 100MB 硬限**；需要的话单独传网盘 |
| `plugins-disabled/`（SkinsRestorer） | 测试机没外网才禁用；有机房网络可自行装回 `plugins/` |
| `world/session.lock` | 世界身份文件，带上会被判定"重复世界" |

## 排查 bug 时的常用命令

```
/hub doctor          # 一体检：插件/契约/目录/菜单/卡顿/被吞异常
/hub create <游戏> <地图> [房间名]
/hub join <房间名>
/hub room maps <游戏>     # 看这个游戏有哪些地图
/hub room gui <游戏>      # 控制台版"选房界面"
```
