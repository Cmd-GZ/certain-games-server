#!/usr/bin/env bash
# **"对局中进房 = 观战"** 的断言脚本。
#
# 规则（2026-11-07 用户口径）：房间正在对局时，任何人 `/hub join` 都不该进入对局，
# 而是进入**观战模式**（框架层统一处理，不再依赖各游戏自己判断）。
#
# 用法：tools/verify-spectate.sh <gameKey> <地图名> [每局人数=2] [最长等待秒=200]
# 例：  tools/verify-spectate.sh tntbattle 双岛 2 200
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GAME="${1:?用法: tools/verify-spectate.sh <gameKey> <地图名> [每局人数] [最长等待秒]}"
MAP="${2:?缺少地图名}"
N="${3:-2}"
MAXWAIT="${4:-200}"
STAMP="$(date +%H%M%S)"
LABEL="vs${STAMP}"
ROOM="room_${LABEL}"
LOG=".server/verify-spectate-${GAME}.log"
CMDS=".server/verify-spectate-${GAME}-cmds.txt"

echo "== 对局中进房 → 观战：$GAME / $MAP（房内 $N 人，最多等 ${MAXWAIT}s） =="

# 测试副本从交付副本还原配置（ArcadeParty 例外）
P=$(ls -d .server/run/small_games/*/plugins)
A=.server/run_alt/plugins
for d in "$P"/*/; do
    g=$(basename "$d")
    [ "$g" = "ArcadeParty" ] && continue
    mkdir -p "$A/$g"
    for f in "$d"*.yml "$d"*.snap; do [ -f "$f" ] && cp "$f" "$A/$g/"; done
done

# 控制台：建房 → 等开局 → 开局 → 继续观测
{
    echo "hub create $GAME $MAP $LABEL"
    for _ in $(seq 1 60); do echo "hub doctor"; done
    echo "hub start $ROOM"
    for _ in $(seq 1 $((MAXWAIT / 2))); do echo "hub doctor"; done
} > "$CMDS"

# 假玩家：前 N 个正常进房（第 N+1 个在开局后进 → 应被判为观战）
NAMES=(Alpha Beta Gamma Delta)
TOTAL=$((N + 1))
[ "$TOTAL" -gt 4 ] && { echo "最多 3 人进房 + 1 个观战（共 4 个假玩家）" >&2; exit 2; }
FAKE=""
for i in $(seq 1 "$N"); do FAKE="${FAKE}25:hub join ${ROOM};;"; done
FAKE="${FAKE}110:hub join ${ROOM}"
PLAYERS="$(IFS=,; echo "${NAMES[*]:0:$TOTAL}")"

SMOKE_SERVER_DIR="$ROOT/.server/run_alt" SMOKE_PORT=25561 SMOKE_LOG="$ROOT/$LOG" \
    ./tools/smoke.sh --deploy --commands "$CMDS" \
    --fake-player "$PLAYERS" --fake-commands "$FAKE" \
    --fake-delay 1 --fake-stay $((MAXWAIT + 20)) >/dev/null 2>&1

CLEAN=".server/verify-spectate-${GAME}-clean.log"
sed 's/\x1b\[[0-9;]*m//g' "$LOG" > "$CLEAN"
has()   { grep -aqE "$1" "$CLEAN"; }
count() { grep -acE "$1" "$CLEAN"; }

ok=1
say() { printf '  %s %s\n' "$1" "$2"; }

if has "已创建房间 ${ROOM}"; then say "✔" "建房成功（$ROOM）"; else say "✘" "建房失败"; ok=0; fi

RUN=$(grep -aoE "${GAME}/${ROOM} \[RUNNING\]" "$CLEAN" | wc -l)
if [ "$RUN" -gt 0 ]; then say "✔" "对局进入 RUNNING（$RUN 次采样）"; else say "✘" "对局没有进入 RUNNING（脚本没测到开局）"; ok=0; fi

if has "（对局进行中，改为观战）"; then
    say "✔" "对局中进房被改为观战"
else
    say "✘" "对局中进房**没有**被改为观战（玩家会被塞进对局）"; ok=0
fi

ERRS=$(count " (ERROR|SEVERE)\]:")
if [ "$ERRS" -eq 0 ]; then say "✔" "ERROR 行 0"; else say "✘" "ERROR 行 $ERRS"; ok=0; fi

echo
if [ "$ok" -eq 1 ]; then
    echo "== 通过 ✅（日志：$LOG）=="
    exit 0
else
    echo "== 未通过 ❌（日志：$LOG）=="
    exit 1
fi
