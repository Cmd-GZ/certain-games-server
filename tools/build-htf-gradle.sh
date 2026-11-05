#!/usr/bin/env bash
# HTF（paper-hold-the-fort）用 **Gradle 正式构建**（离线）。产物 → .server/deploy/plugins/HoldTheFort.jar
#
# 为什么需要这个脚本（而不是直接 `./gradlew build`）：
#   ① `paper-hold-the-fort/` 里的 gradlew 会去 `~/.gradle/wrapper/dists/...` 解包，而那里**只读**；
#   ② 构建脚本依赖 `xyz.jpenilla.run-paper` 插件（要联网拉插件市场）——离线环境里用不到；
#   ③ Gradle 需要可写的 GRADLE_USER_HOME 与项目目录。
# 做法：把源码**复制**到 `.server/htf-gradle/`（可写），去掉 run-paper / runServer，
#       用已解包的 gradle 发行版 + 可写 GRADLE_USER_HOME（caches 软链到只读缓存）跑 `--offline jar`。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/paper-hold-the-fort"
WORK="$ROOT/.server/htf-gradle"
GH="${GRADLE_USER_HOME:-$ROOT/.server/gradle-home}"
DEPLOY="$ROOT/.server/deploy/plugins"

# ① 找已解包的 gradle 发行版（wrapper 只读也没关系，直接用它解包后的 bin/gradle）
GRADLE_BIN=""
for d in "$HOME"/.gradle/wrapper/dists/gradle-*-bin/*/gradle-*/bin/gradle; do
    [ -x "$d" ] && GRADLE_BIN="$d"
done
if [ -z "$GRADLE_BIN" ]; then
    echo "找不到已解包的 Gradle 发行版（~/.gradle/wrapper/dists/gradle-*-bin/*/gradle-*/bin/gradle）" >&2
    echo "先在能联网的机器上跑一次 ./gradlew，或用 .server/htf-build.sh（javac 方案）" >&2
    exit 2
fi

# ② 可写的 GRADLE_USER_HOME：caches 软链到只读缓存（只读也能命中）
mkdir -p "$GH/caches"
for sub in modules-2 jars-9 build-cache-1 transforms-4; do
    if [ -d "$HOME/.gradle/caches/$sub" ] && [ ! -e "$GH/caches/$sub" ]; then
        ln -sfn "$HOME/.gradle/caches/$sub" "$GH/caches/$sub"
    fi
done

# ③ 复制源码到可写目录，并去掉需要联网的插件/任务
rm -rf "$WORK"
mkdir -p "$WORK"
cp -r "$SRC/src" "$SRC/build.gradle.kts" "$SRC/settings.gradle.kts" "$SRC/gradle.properties" "$WORK"/ 2>/dev/null || true
[ -d "$SRC/libs" ] && cp -r "$SRC/libs" "$WORK"/ 2>/dev/null || true
python3 - "$WORK/build.gradle.kts" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s = s.replace('''plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}''', '''plugins {
    java
}''')
# 去掉开发期一键起服（要下载 Paper）
i = s.find('runServer {')
if i >= 0:
    j = s.find('}', i)
    s = s[:i] + s[j + 1:]
open(p, 'w', encoding='utf-8').write(s)
PYEOF

# ④ 构建
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk}"
echo "== 用 $GRADLE_BIN 构建（offline） =="
GRADLE_USER_HOME="$GH" "$GRADLE_BIN" --offline -q -p "$WORK" jar

JAR="$(ls "$WORK"/build/libs/*.jar 2>/dev/null | grep -v sources | head -1)"
if [ -z "$JAR" ]; then
    echo "构建完成但没有产物（$WORK/build/libs/）" >&2
    exit 2
fi

mkdir -p "$DEPLOY"
cp "$JAR" "$DEPLOY/HoldTheFort.jar"
echo "== 构建完成：$JAR → $DEPLOY/HoldTheFort.jar =="
