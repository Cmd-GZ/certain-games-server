#!/usr/bin/env bash
# 在 Linux 上全量构建 + 部署候选产物，并做一次"能不能开起来"的冒烟。
#
# 和上游 build.ps1 的关系：build.ps1 是 Windows 专用（找 server\jdk\bin\javac.exe），
# 这个脚本是它在 Linux 上的等价物：Maven + JDK 25，产物落在各模块 target/。
#
# 用法:
#   tools/build.sh              # 只构建
#   tools/build.sh --deploy     # 构建后把 jar 复制到 .server/smoke/plugins/
#   tools/build.sh --smoke      # 构建 + 部署 + 起服冒烟（需要 --server 指向服务器目录）
#
# 环境变量:
#   JAVA_HOME  默认 /usr/lib/jvm/java-25-openjdk
#   MVN        默认 <repo>/.server/tools/apache-maven-*/bin/mvn
#   M2_REPO    默认 <repo>/.server/m2repo
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk}"

MVN="${MVN:-$(ls -d "$ROOT"/.server/tools/apache-maven-*/bin/mvn 2>/dev/null | head -1)}"
M2_REPO="${M2_REPO:-$ROOT/.server/m2repo}"
if [ -z "$MVN" ] || [ ! -x "$MVN" ]; then
  echo "找不到 maven。先跑：tools/fetch-maven.sh" >&2
  exit 1
fi

DEPLOY=0; SMOKE=0
for a in "$@"; do
  case "$a" in
    --deploy) DEPLOY=1 ;;
    --smoke)  DEPLOY=1; SMOKE=1 ;;
    *) echo "未知参数 $a" >&2; exit 2 ;;
  esac
done

COMMON=(-B -q --no-transfer-progress -Dmaven.repo.local="$M2_REPO")
cd "$ROOT/服务器小游戏插件" || exit 1

echo "== JDK: $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
echo "== Maven: $MVN"

# MiniGameLib 必须先 install：其它模块都依赖 com.hub:minigame-lib
echo "== [1/3] MiniGameLib install"
if ! "$MVN" "${COMMON[@]}" -f MiniGameLib/pom.xml install -DskipTests > /tmp/mg-build.log 2>&1; then
  echo "MiniGameLib 构建失败，日志："; tail -40 /tmp/mg-build.log; exit 1
fi

echo "== [2/3] 其余 25 个模块 package"
fail=0
for pom in */pom.xml; do
  case "$pom" in MiniGameLib/pom.xml) continue ;; esac
  if ! "$MVN" "${COMMON[@]}" -f "$pom" package -DskipTests >> /tmp/mg-build.log 2>&1; then
    echo "  失败: $pom"; fail=1
  fi
done
if [ "$fail" = 1 ]; then echo "有模块构建失败，日志：/tmp/mg-build.log"; exit 1; fi

echo "== [3/3] 独立插件"
( cd "$ROOT/PlayerTitles" && "$MVN" "${COMMON[@]}" install -DskipTests >> /tmp/mg-build.log 2>&1 ) \
  || { echo "PlayerTitles 失败"; tail -30 /tmp/mg-build.log; exit 1; }
( cd "$ROOT/JoinQuitBroadcast" && "$MVN" "${COMMON[@]}" package -DskipTests >> /tmp/mg-build.log 2>&1 ) \
  || { echo "JoinQuitBroadcast 失败"; tail -30 /tmp/mg-build.log; exit 1; }

jars=$(find "$ROOT/服务器小游戏插件" "$ROOT/PlayerTitles" "$ROOT/JoinQuitBroadcast" \
        -name '*.jar' -path '*/target/*' | wc -l)
echo "== 构建 OK，产出 $jars 个 jar"

if [ "$DEPLOY" = 1 ]; then
  DEST="$ROOT/.server/deploy/plugins"
  mkdir -p "$DEST"
  n=0
  for j in $(find "$ROOT/服务器小游戏插件" "$ROOT/PlayerTitles" "$ROOT/JoinQuitBroadcast" \
              -name '*.jar' -path '*/target/*'); do
    cp "$j" "$DEST/"; n=$((n+1))
  done
  echo "== 已复制 $n 个 jar 到 $DEST"
fi

if [ "$SMOKE" = 1 ]; then
  exec "$ROOT/tools/smoke.sh"
fi
