#!/usr/bin/env bash
# 框架自检：不需要启动服务器，纯逻辑断言（I5 状态机 / D22 房间上下文与按房间分桶）。
#
# 为什么是 main() 而不是 JUnit：这台机器 Maven 走离线模式，.server/m2repo 里没有 JUnit 的 jar。
# 用 javac + main() 做等价物，失败非零退出，可以进 CI。
#
# 前置：MiniGameLib 已经编过一次（`tools/build.sh` 或 `mvn package`）。
#       本脚本**直接复用** MiniGameLib/target/classes，不重复编译整个框架
#       （那需要 guava / bungee-chat / adventure 一整套依赖，单独拼 classpath 不划算）。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB="$ROOT/服务器小游戏插件/MiniGameLib"
CLASSES="$LIB/target/classes"
OUT="$ROOT/.server/selfcheck-classes"
REPO="$ROOT/.server/m2repo"

if [ ! -d "$CLASSES/com/hub" ]; then
    echo "MiniGameLib 还没编译（$CLASSES 不存在）。先跑：tools/build.sh" >&2
    exit 2
fi

PAPER="$(ls "$REPO"/io/papermc/paper/paper-api/*/paper-api-*.jar 2>/dev/null | sort | tail -1)"
if [ -z "$PAPER" ]; then
    echo "找不到 paper-api jar（$REPO/io/papermc/paper/paper-api/）" >&2
    exit 2
fi

# 运行时可能被间接引用到的第三方库（离线仓库里都有）
EXTRA="$(ls "$REPO"/net/kyori/adventure-api/*/adventure-api-*.jar \
             "$REPO"/net/kyori/adventure-text-serializer-legacy/*/adventure-text-serializer-legacy-*.jar \
             "$REPO"/net/kyori/adventure-text-minimessage/*/adventure-text-minimessage-*.jar \
             "$REPO"/net/kyori/adventure-key/*/adventure-key-*.jar \
             "$REPO"/net/kyori/examination-api/*/examination-api-*.jar \
             "$REPO"/org/jetbrains/annotations/*/annotations-*.jar \
             "$REPO"/com/google/guava/guava/*/guava-*.jar 2>/dev/null | grep -v sources | grep -v javadoc | tr '\n' ':')"
CP="$CLASSES:$PAPER:$EXTRA"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "== 编译自检 =="
javac -nowarn -encoding UTF-8 -proc:none -cp "$CP" -d "$OUT" \
    $(find "$LIB/src/test/java" -name '*.java') 2>&1 | grep -v "^注:" | head -20
if [ ! -d "$OUT/com/hub/selfcheck" ]; then
    echo "自检编译失败" >&2
    exit 2
fi

echo "== 运行自检 =="
java -cp "$OUT:$CP" com.hub.selfcheck.SelfCheck
rc=$?

# 【I3】事件桥接体检：游戏侧 instanceof 的事件，框架必须真的派发
# （2026-11-07 那批"机制莫名其妙丢了"的根因：游戏写了处理器，框架根本没派发）
echo
echo "== 事件桥接体检 =="
if ! python3 "$(dirname "$0")/check-event-bridge.py"; then
    echo "❌ 有事件游戏在等、框架不派发"
    rc=1
fi
exit $rc
