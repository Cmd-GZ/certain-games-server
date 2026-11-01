# libs/ —— 让 IDE 也能解析 `com.hub:minigame-lib`

## 为什么需要

`服务器小游戏插件/` 下的插件都 `provided` 依赖 `com.hub:minigame-lib`。
这个 artifact **不在 Maven 中央仓库**，只能由本仓库先 `mvn install` 到本地。

问题：开发环境里默认的 `~/.m2/repository` 是**只读**的，install 会失败，
那里只留下 `*.lastUpdated` 空壳 —— 于是 IDE 一片红：
`Missing artifact com.hub:minigame-lib:jar:1.9.0`。

## 做法

把构建出来的 jar **vendor 进仓库**，做成一个最小 Maven 仓库布局：

```
libs/m2/com/hub/minigame-lib/<版本>/minigame-lib-<版本>.jar
libs/m2/com/hub/minigame-lib/<版本>/minigame-lib-<版本>.pom
```

每个模块的 `pom.xml` 里声明了一个指向它的 file:// 仓库：

```xml
<repositories>
  <repository>
    <id>workspace-libs</id>
    <url>file://${project.basedir}/../libs/m2</url>
  </repository>
</repositories>
```

这样**任何 Maven（包括 IDE）都能解析**，不需要改 IDE 设置、也不依赖 workspace 外的路径。

## 同步规则（重要）

`libs/m2/` 里的副本**必须和 MiniGameLib 的实际版本一致**。
`tools/build.sh` 在构建 MiniGameLib 之后会**自动刷新**这里的副本，
所以正常流程下不用手动管；手工改了版本号又没跑构建时请注意。
