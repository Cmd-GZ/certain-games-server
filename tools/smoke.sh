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
#   tools/smoke.sh --fake-player NAME   # 起"假玩家"配合需要真人的流程（见 tools/fakeclient.py）
#   tools/smoke.sh --fake-commands 'a;b' # 假玩家到点依次发的命令（分号分隔，不带前导 /）
#   tools/smoke.sh --fake-delay N       # 假玩家就绪（全部在场）后多久开始发命令（默认 3 秒）
#   tools/smoke.sh --fake-stay N        # 最后一条命令后再挂多久（默认 100 秒）
#   tools/smoke.sh --fake-wait N        # 'Done (' 之后等多久再连假玩家（默认 5 秒）
#   tools/smoke.sh --fake-ready-timeout N # 等"假玩家全部就绪"的最长时间（默认 100 秒）
#   tools/smoke.sh --fake-soft          # 假玩家没全部就绪时**仍然**喂控制台命令（默认跳过）
#
# 为什么要 --fake-player：`/arcade start` 能强制跨过人数门槛，但跨不过"一个人都没有"——
# 街机派对这类"先有人进等待区才开局"的流程，没有客户端就只能人肉进服验。
# 假玩家是协议直连（抬头有说明），只登录、只发命令、只应答心跳。
#
# 为什么要先等 `== ALL READY`：需要"两个 bot 同时在场"的流程（派对开局、满员倒计时）
# 一旦有 bot 没进来，整轮命令都是白喂的。fakeclient 自己会快失败重连、过闸时打
# `== ALL READY (N/N) ==`，本脚本等这一行出现之后再开始喂控制台命令。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="${JAVA:-/usr/lib/jvm/java-25-openjdk/bin/java}"
SERVER=""
DEPLOY=0
CMDS=""
EXPECTS=()
REJECTS=()
TIMEOUT=240
FAKE_NAME=""
FAKE_CMDS=""
FAKE_DELAY=3
FAKE_STAY=100
FAKE_WAIT=5
FAKE_READY_TIMEOUT=100
FAKE_CONNECT_TIMEOUT=180
FAKE_SOFT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --server)   SERVER="$2"; shift 2 ;;
    --deploy)   DEPLOY=1; shift ;;
    --commands) CMDS="$2"; shift 2 ;;
    --expect)   EXPECTS+=("$2"); shift 2 ;;
    --reject)   REJECTS+=("$2"); shift 2 ;;
    --timeout)  TIMEOUT="$2"; shift 2 ;;
    --fake-player)   FAKE_NAME="$2"; shift 2 ;;
    --fake-commands) FAKE_CMDS="$2"; shift 2 ;;
    --fake-delay)    FAKE_DELAY="$2"; shift 2 ;;
    --fake-stay)     FAKE_STAY="$2"; shift 2 ;;
    --fake-wait)     FAKE_WAIT="$2"; shift 2 ;;
    --fake-ready-timeout) FAKE_READY_TIMEOUT="$2"; shift 2 ;;
    --fake-connect-timeout) FAKE_CONNECT_TIMEOUT="$2"; shift 2 ;;
    --fake-soft)     FAKE_SOFT=1; shift ;;
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
  SERVER="${SMOKE_SERVER_DIR:-$(ls -d "$ROOT"/.server/run/small_games/* 2>/dev/null | head -1)}"
fi
if [ -z "$SERVER" ] || [ ! -f "$SERVER/server.jar" ]; then
  echo "找不到服务器目录（需要含 server.jar）。用 --server 指定。" >&2
  exit 1
fi

echo "== 服务器: $SERVER"


# ---------------------------------------------------------------------------
# 部署前的守卫：**服务器正在跑的时候绝不能覆盖 plugins/*.jar**
#
# 真实事故：服务器开着，我跑了 build.sh --deploy 覆盖了 run 目录里的 jar。
# JVM 已经把旧 jar 加载进内存，文件被换掉之后新代码引用到旧 jar 里没有的类，
# 于是玩家点菜单就炸：
#   NoClassDefFoundError: com/hub/session/PlayerSessions
#   at com.hub.session.GameSession.join(...)
# 这种错看起来像代码 bug，其实是"运行时换了 jar"，极难排查。
#
# 判据：Paper 启动时会对 world/session.lock 加锁；`flock -n` 试一下就知道有没有人在用。
# ---------------------------------------------------------------------------
server_is_running() {
  local dir="$1"
  local lock
  lock=$(ls "$dir"/world/session.lock 2>/dev/null | head -1)
  if [ -n "$lock" ] && [ -f "$lock" ]; then
    if ! flock -n "$lock" true 2>/dev/null; then
      return 0     # 锁不住 = 有人在用
    fi
  fi
  # 兜底：有 java 进程在做这件事。**指定了 SMOKE_SERVER_DIR（隔离副本）时跳过** ——
  # 否则别人在另一份副本上跑着的服务器会让这次部署被误判为"目标正在运行"。
  if [ -z "${SMOKE_SERVER_DIR:-}" ] && pgrep -f "server.jar nogui" >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

if [ "$DEPLOY" = 1 ]; then
  if server_is_running "$SERVER"; then
    echo "❌ 这台服务器看起来正在运行（world/session.lock 被占用）。" >&2
    echo "   覆盖 plugins/*.jar 会让运行中的 JVM 引用到不存在的类（NoClassDefFoundError）。" >&2
    echo "   先关服再跑；只想构建不部署就用 tools/build.sh（不带 --deploy）。" >&2
    exit 1
  fi
  src="$ROOT/.server/deploy/plugins"
  if [ ! -d "$src" ]; then echo "--deploy 但 $src 不存在，先跑 tools/build.sh --deploy" >&2; exit 1; fi
  n=0
  for j in "$src"/*.jar; do cp "$j" "$SERVER/plugins/"; n=$((n+1)); done
  echo "== 已部署 $n 个 jar"
fi

LOG="${SMOKE_LOG:-$ROOT/.server/smoke.log}"
FAKE_LOG="$ROOT/.server/fakeclient.log"
FIFO="$ROOT/.server/.smoke.fifo"
rm -f "$LOG" "$FAKE_LOG" "$FIFO"
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

fake_ready=0
FAKE_JOB=""
if [ "$done_seen" = 1 ]; then
  # 稍微等一下再让假玩家连：刚 Done 时服务器还在收尾（15 个世界存盘 + 模板扫描），
  # 这期间登录会被拖慢。不过**不用等很久**——fakeclient 自己会快失败重连，
  # 早连反而能早一点抢到服务器空闲的那一刻。
  sleep "$FAKE_WAIT"
  if [ -n "$FAKE_NAME" ]; then
    echo "== 假玩家 $FAKE_NAME 命令: $FAKE_CMDS（就绪后 ${FAKE_DELAY}s 起算，挂 ${FAKE_STAY}s）"
    python3 "$ROOT/tools/fakeclient.py" --port "${SMOKE_PORT:-25560}" --name "$FAKE_NAME" \
        --commands "$FAKE_CMDS" --delay "$FAKE_DELAY" --stay "$FAKE_STAY" \
        --connect-timeout "$FAKE_CONNECT_TIMEOUT" > "$FAKE_LOG" 2>&1 &
    FAKE_JOB=$!

    # 等所有假玩家都进游戏阶段（fakeclient 过闸会打 '== ALL READY (N/N) =='）。
    # 需要"两人同时在场"的场景，等不到就别喂命令了，直接判失败。
    for _ in $(seq 1 "$FAKE_READY_TIMEOUT"); do
      if grep -q '== ALL READY' "$FAKE_LOG" 2>/dev/null; then fake_ready=1; break; fi
      kill -0 "$FAKE_JOB" 2>/dev/null || break
      sleep 1
    done
    if [ "$fake_ready" = 1 ]; then
      echo "   ✅ 假玩家就绪：$(grep -o '== ALL READY ([0-9]*/[0-9]*) ==' "$FAKE_LOG" | head -1)"
    else
      echo "   ❌ ${FAKE_READY_TIMEOUT}s 内假玩家没有全部就绪（见 $FAKE_LOG）"
      rc=1
    fi
  fi

  feed_cmds=1
  if [ -n "$FAKE_NAME" ] && [ "$fake_ready" = 0 ] && [ "$FAKE_SOFT" = 0 ]; then
    feed_cmds=0
  fi
  if [ -n "$CMDS" ] && [ "$feed_cmds" = 1 ]; then
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
  elif [ -n "$CMDS" ]; then
    echo "== 跳过喂命令：假玩家没全部就绪（确定要照喂就加 --fake-soft）"
  fi

  sleep 2
  echo "stop" >&3
  for _ in $(seq 1 180); do kill -0 "$JOB" 2>/dev/null || break; sleep 1; done
  if [ -n "${FAKE_JOB:-}" ]; then
    kill -0 "$FAKE_JOB" 2>/dev/null && kill "$FAKE_JOB" 2>/dev/null
    wait "$FAKE_JOB" 2>/dev/null
  fi
fi
exec 3>&-
rm -f "$FIFO"
kill -0 "$JOB" 2>/dev/null && kill "$JOB" 2>/dev/null
wait "$JOB" 2>/dev/null

echo "== 断言"
if [ "$done_seen" = 1 ]; then
  n_plugins=$(grep -c 'Loading server plugin' "$LOG" || true)
  echo "   插件加载: $n_plugins 个"
  # 注意：Paper 的日志格式是 "[hh:mm:ss ERROR]:"，不是 "[ERROR]" ——
  # 早期版本这里写成 \[ERROR\] 导致"有错也报 0"，等于断言形同虚设。
  bad=$(grep -cE ' (ERROR|SEVERE)\]:' "$LOG" || true)
  echo "   ERROR/SEVERE: $bad 行"
  if [ "$bad" != 0 ]; then
    grep -E ' (ERROR|SEVERE)\]:' "$LOG" | head -10 | sed 's/^/     /'
    rc=1
  fi
fi

if [ -n "$FAKE_NAME" ]; then
  # 假玩家摘要：就绪几个、发了几条命令、服务端到底收到了几条玩家命令
  n_ready=$(grep -c '\[ready\]' "$FAKE_LOG" 2>/dev/null || true)
  n_sent=$(grep -c '发送命令' "$FAKE_LOG" 2>/dev/null || true)
  n_pcmd=$(grep -c 'issued server command' "$LOG" 2>/dev/null || true)
  echo "   假玩家就绪: ${n_ready:-0} 个（$FAKE_NAME）$( [ "$fake_ready" = 1 ] && echo '，全部就绪 ✅' || echo '，没凑齐 ❌')"
  echo "   假玩家发出命令: ${n_sent:-0} 条；服务端记录玩家命令: ${n_pcmd:-0} 行"
  if [ "${n_ready:-0}" = 0 ]; then rc=1; fi
  echo "   假玩家日志: $FAKE_LOG"
  tail -12 "$FAKE_LOG" 2>/dev/null | sed 's/^/     /'
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
