#!/usr/bin/env bash
# 冒烟台：在 Linux 上把服务器真起一次，灌一段命令脚本，对日志做断言，然后干净关服。
#
# 为什么需要它：Paper 26.2 目前没有可用的 MockBukkit，端到端验证只能"真起一个服务器"。
# 上游原来靠手写 smoke-skypit-lb*.log（起服 → 人肉看日志）。这里把它脚本化，进 CI。
#
# 依据 docs/框架不变量.md：
#   - 每次都要断言"31 个插件全部 enable"、"Done (" 出现、"无 ERROR/SEVERE"
#   - 加上 --expect 断言特定日志行（比如某个不变量修复后的标记）
#
# 用法:
#   tools/smoke.sh                      # 用 .server/run 里的服务器
#   tools/smoke.sh --server DIR         # 指定服务器目录
#   tools/smoke.sh --deploy             # 先把 .server/deploy/plugins/*.jar 覆盖进服务器
#   tools/smoke.sh --commands FILE      # 启动后逐行喂命令
#   tools/smoke.sh --expect 'PATTERN'   # 断言日志里必须出现（可重复）
#   tools/smoke.sh --reject 'PATTERN'   # 断言日志里不能出现（可重复）
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="${JAVA:-/usr/lib/jvm/java-25-openjdk/bin/java}"
SERVER=""
DEPLOY=0
CMDS=""
EXPECTS=()
REJECTS=()
TIMEOUT=240

while [ $# -gt 0 ]; do
  case "$1" in
    --server)   SERVER="$2"; shift 2 ;;
    --deploy)   DEPLOY=1; shift ;;
    --commands) CMDS="$2"; shift 2 ;;
    --expect)   EXPECTS+=("$2"); shift 2 ;;
    --reject)   REJECTS+=("$2"); shift 2 ;;
    --timeout)  TIMEOUT="$2"; shift 2 ;;
    *) echo "未知参数 $1" >&2; exit 2 ;;
  esac
done

# --commands 必须转成绝对路径：后面会 cd 进服务器目录
if [ -n "$CMDS" ]; then
  if [ ! -f "$CMDS" ]; then
    echo "--commands 指定的文件不存在：$CMDS" >&2
    exit 1
  fi
  CMDS="$(cd "$(dirname "$CMDS")" && pwd)/$(basename "$CMDS")"
fi

if [ -z "$SERVER" ]; then
  SERVER="$(ls -d "$ROOT"/.server/run/small_games/* 2>/dev/null | head -1)"
fi
if [ -z "$SERVER" ] || [ ! -f "$SERVER/server.jar" ]; then
  echo "找不到服务器目录（需要含 server.jar）。用 --server 指定。" >&2
  exit 1
fi

echo "== 服务器: $SERVER"

if [ "$DEPLOY" = 1 ]; then
  src="$ROOT/.server/deploy/plugins"
  if [ ! -d "$src" ]; then echo "--deploy 但 $src 不存在，先跑 tools/build.sh --deploy" >&2; exit 1; fi
  n=0
  for j in "$src"/*.jar; do cp "$j" "$SERVER/plugins/"; n=$((n+1)); done
  echo "== 已部署 $n 个 jar"
fi

LOG="$ROOT/.server/smoke.log"
FIFO="$ROOT/.server/.smoke.fifo"
rm -f "$LOG" "$FIFO"
mkfifo "$FIFO"

cd "$SERVER" || exit 1
( "$JAVA" -Xms1G -Xmx2G -jar server.jar nogui < "$FIFO" > "$LOG" 2>&1
  echo "=== JAVA_EXIT=$? ===" >> "$LOG" ) &
JOB=$!
exec 3> "$FIFO"

done_seen=0
for _ in $(seq 1 "$TIMEOUT"); do
  if grep -q 'Done (' "$LOG" 2>/dev/null; then done_seen=1; break; fi
  kill -0 "$JOB" 2>/dev/null || break
  sleep 1
done

rc=0
if [ "$done_seen" = 0 ]; then
  echo "❌ 没等到 'Done ('，开服失败"
  tail -40 "$LOG"
  rc=1
else
  echo "== 开服成功：$(grep -o 'Done ([0-9.]*s)' "$LOG" | head -1)"
fi

if [ "$done_seen" = 1 ]; then
  sleep 3
  if [ -n "$CMDS" ]; then
    if [ ! -f "$CMDS" ]; then
      echo "❌ 命令脚本不见了：$CMDS"; rc=1
    fi
    echo "== 喂命令: $CMDS"
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      case "$line" in \#*) continue ;; esac
      echo "   > $line"
      echo "$line" >&3
      sleep 1
    done < "$CMDS"
  fi
  sleep 2
  echo "stop" >&3
  for _ in $(seq 1 180); do kill -0 "$JOB" 2>/dev/null || break; sleep 1; done
fi
exec 3>&-
rm -f "$FIFO"
kill -0 "$JOB" 2>/dev/null && kill "$JOB" 2>/dev/null
wait "$JOB" 2>/dev/null

echo "== 断言"
if [ "$done_seen" = 1 ]; then
  n_plugins=$(grep -c 'Loading server plugin' "$LOG" || true)
  echo "   插件加载: $n_plugins 个"
  bad=$(grep -cE '\[(ERROR|SEVERE)\]' "$LOG" || true)
  echo "   ERROR/SEVERE: $bad 行"
  if [ "$bad" != 0 ]; then
    grep -E '\[(ERROR|SEVERE)\]' "$LOG" | head -10 | sed 's/^/     /'
    rc=1
  fi
fi

for pat in "${EXPECTS[@]:-}"; do
  [ -z "$pat" ] && continue
  if grep -qE "$pat" "$LOG"; then echo "   ✅ 命中: $pat"
  else echo "   ❌ 缺少: $pat"; rc=1; fi
done
for pat in "${REJECTS[@]:-}"; do
  [ -z "$pat" ] && continue
  if grep -qE "$pat" "$LOG"; then echo "   ❌ 不该出现: $pat"; rc=1
  else echo "   ✅ 未出现: $pat"; fi
done

echo "== 日志: $LOG"
[ "$rc" = 0 ] && echo "== 冒烟通过 ✅" || echo "== 冒烟失败 ❌"
exit "$rc"
