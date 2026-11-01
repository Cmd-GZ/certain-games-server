#!/usr/bin/env bash
# 起一个本地测试服（前台运行），供人工进游戏验证。
#
# 为什么不用 `java -jar` 直接跑：需要能优雅关服（走控制台 stop，让插件把内存状态落盘）。
# 直接杀进程会让存档和 6 个"关服回写配置"的插件处于半写状态。
#
# 用法:
#   tools/serve.sh                  # 起服（自动找服务器目录）
#   tools/serve.sh --deploy         # 先把 .server/deploy/plugins/*.jar 覆盖进去再起
#   tools/serve.sh --fresh          # 从 .server/server/ 重建一份干净工作副本再起
#   tools/serve.sh --server DIR     # 指定服务器目录
#   tools/serve.sh --memory 4G      # 改堆大小（默认 2G）
#
# 关服:
#   前台按一次 Ctrl-C（脚本会转成控制台 stop 并等它自己退），或另开终端：
#       echo stop > .server/.serve.fifo
#
# 环境变量:
#   JAVA  默认 /usr/lib/jvm/java-25-openjdk/bin/java（Paper 26.2 最低 JDK 25）
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="${JAVA:-/usr/lib/jvm/java-25-openjdk/bin/java}"
MEM="2G"
SERVER=""
DEPLOY=0
FRESH=0

while [ $# -gt 0 ]; do
  case "$1" in
    --server) SERVER="$2"; shift 2 ;;
    --deploy) DEPLOY=1; shift ;;
    --fresh)  FRESH=1; shift ;;
    --memory) MEM="$2"; shift 2 ;;
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
    *) echo "未知参数 $1" >&2; exit 2 ;;
  esac
done

if [ ! -x "$JAVA" ]; then
  echo "找不到 JDK 25：$JAVA（用 JAVA=... 指定）" >&2
  exit 1
fi

# ---------- --fresh：从原样副本重建工作服 ----------
if [ "$FRESH" = 1 ]; then
  PRISTINE="$(ls -d "$ROOT"/.server/server/small_games/* 2>/dev/null | head -1)"
  if [ -z "$PRISTINE" ] || [ ! -f "$PRISTINE/server.jar" ]; then
    echo "--fresh 需要 .server/server/ 里有原样副本（含 server.jar），找不到" >&2
    exit 1
  fi
  echo "== --fresh：从 $(basename "$PRISTINE") 重建工作副本"
  rm -rf "$ROOT/.server/run"
  mkdir -p "$ROOT/.server/run/small_games"
  cp -a "$PRISTINE" "$ROOT/.server/run/small_games/"
  SERVER="$(ls -d "$ROOT"/.server/run/small_games/* | head -1)"
  # 上游包里缺 world/level.dat，开服会刷 "No existing world data" ERROR。
  # 从服务器自带的旧全量包补回来（见 docs/区域归属表.md 抬头那份体检报告）。
  if [ ! -f "$SERVER/world/level.dat" ] && [ -f "$SERVER/server-pack-20261018-165457.zip" ]; then
    echo "== 补 world/level.dat"
    unzip -o -q "$SERVER/server-pack-20261018-165457.zip" \
      'world/level.dat' 'world/level.dat_old' -d "$SERVER" || true
  fi
fi

if [ -z "$SERVER" ]; then
  SERVER="$(ls -d "$ROOT"/.server/run/small_games/* 2>/dev/null | head -1)"
fi
if [ -z "$SERVER" ] || [ ! -f "$SERVER/server.jar" ]; then
  echo "找不到服务器目录（需要含 server.jar）。用 --server 指定，或先解压上游包。" >&2
  exit 1
fi


# 部署守卫：服务器正在跑时**绝不能**覆盖 plugins/*.jar。
# 真实事故：JVM 已把旧 jar 加载进内存，文件被换掉后新代码引用到旧 jar 没有的类，
# 表现为 NoClassDefFoundError（像代码 bug，其实是运行时换了 jar）。
server_is_running() {
  local dir="$1" lock
  lock=$(ls "$dir"/world/session.lock 2>/dev/null | head -1)
  if [ -n "$lock" ] && [ -f "$lock" ] && ! flock -n "$lock" true 2>/dev/null; then
    return 0
  fi
  if pgrep -f "server.jar nogui" >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

if [ "$DEPLOY" = 1 ]; then
  src="$ROOT/.server/deploy/plugins"
  if [ ! -d "$src" ]; then
    echo "--deploy 但 $src 不存在，先跑 tools/build.sh --deploy" >&2
    exit 1
  fi
  n=0
  if server_is_running "$SERVER"; then
    echo "❌ 服务器正在运行（session.lock 被占用），不能覆盖 jar。" >&2
    echo "   先关服再部署 —— 运行时换 jar 会导致 NoClassDefFoundError。" >&2
    exit 1
  fi
  for j in "$src"/*.jar; do cp "$j" "$SERVER/plugins/"; n=$((n+1)); done
  echo "== 已部署 $n 个 jar 到 $SERVER/plugins"
fi

FIFO="$ROOT/.server/.serve.fifo"
rm -f "$FIFO"
mkfifo "$FIFO" || { echo "建不出 fifo：$FIFO" >&2; exit 1; }

echo "== 服务器: $SERVER"
echo "== Java  : $("$JAVA" -version 2>&1 | head -1)"
echo "== 内存  : -Xms$MEM -Xmx$MEM"
echo "== 日志  : $SERVER/logs/latest.log"
echo "== 关服  : echo stop > $FIFO   或按一次 Ctrl-C"
echo

cd "$SERVER" || exit 1
"$JAVA" -Xms"$MEM" -Xmx"$MEM" -jar server.jar nogui < "$FIFO" &
JOB=$!
exec 3> "$FIFO"

stopping=0
on_int() {
  if [ "$stopping" = 0 ]; then
    stopping=1
    echo
    echo "== 收到中断：发送控制台 stop，等服务器自己退出（请勿再按 Ctrl-C）"
    echo "stop" >&3 2>/dev/null || true
  fi
}
trap on_int INT TERM

# 中断会让 wait 提前返回，所以要循环到子进程真的消失
while true; do
  wait "$JOB"; rc=$?
  kill -0 "$JOB" 2>/dev/null || break
done

exec 3>&-
rm -f "$FIFO"
echo "== 服务器已退出（exit=$rc）"
exit "$rc"
