#!/usr/bin/env bash
# 两房各打完整一局：**两个派对房间同时开同一个子游戏，各自跑到结算**。
#
# 这是 D22 最深的一层验证：不只是"两个会话存在"，而是
#   ① 两房同时进 RUNNING；② 两房各自 `收场 reason=ROUND_END` 各一次；③ 全程 ERROR 0。
#
# 用法：tools/verify-two-rooms.sh <gameKey> [玩家数每房=2] [最长等待秒=300]
# 例：  tools/verify-two-rooms.sh anvilbattle 2 300
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GAME="${1:?用法: tools/verify-two-rooms.sh <gameKey> [每房人数] [最长等待秒]}"
PER="${2:-2}"
MAXWAIT="${3:-300}"

STAMP="$(date +%H%M%S)"
LABEL_A="vta${STAMP}"
LABEL_B="vtb${STAMP}"
ROOM_A="room_${LABEL_A}"
ROOM_B="room_${LABEL_B}"
LOG=".server/verify-two-${GAME}.log"
CMDS=".server/verify-two-${GAME}-cmds.txt"

echo "== 两房完整局：$GAME（每房 $PER 人，最长等 ${MAXWAIT}s） =="
echo "   房间：$ROOM_A / $ROOM_B"

# ① 测试副本从交付副本还原（ArcadeParty 例外：它的 enabled/display 是测试态）
P=$(ls -d .server/run/small_games/*/plugins)
A=.server/run_alt/plugins
for d in "$P"/*/; do
    g=$(basename "$d")
    [ "$g" = "ArcadeParty" ] && continue
    mkdir -p "$A/$g"
    for f in "$d"*.yml "$d"*.snap; do [ -f "$f" ] && cp "$f" "$A/$g/"; done
done

# ② 派对池子收窄到本游戏（两房都只会挑它）
python3 - "$A/ArcadeParty/config.yml" "$GAME" <<'PYEOF'
import re, sys
path, game = sys.argv[1], sys.argv[2]
s = open(path, encoding='utf-8').read()
s = re.sub(r'(?m)^flow-game:.*$', 'flow-game: ' + game, s)
open(path, 'w', encoding='utf-8').write(s)
PYEOF

# ③ 控制台：先建 A，再建 B，之后一直打 doctor 观测
{
    echo "hub create arcadeparty 迷你小游戏派对 $LABEL_A"
    for _ in $(seq 1 12); do echo "hub doctor"; done
    echo "hub create arcadeparty 迷你小游戏派对 $LABEL_B"
    for _ in $(seq 1 $((MAXWAIT / 2))); do echo "hub doctor"; done
} > "$CMDS"

# ④ 假玩家：前 PER 个进 A，后面 PER 个进 B
NAMES=(Alpha Beta Gamma Delta)
TOTAL=$((PER * 2))
[ "$TOTAL" -gt 4 ] && { echo "每房人数最多 2（总共 4 个假玩家）" >&2; exit 2; }
FAKE=""
for i in $(seq 0 $((PER - 1))); do FAKE="${FAKE}20:hub join ${ROOM_A};;"; done
for i in $(seq 0 $((PER - 1))); do FAKE="${FAKE}20:hub join ${ROOM_B};;"; done
FAKE="${FAKE%;;}"
PLAYERS="$(IFS=,; echo "${NAMES[*]:0:$TOTAL}")"

rm -rf ".server/run_alt/world/dimensions/minecraft/${ROOM_A}_"* ".server/run_alt/world/dimensions/minecraft/${ROOM_B}_"* 2>/dev/null

SMOKE_SERVER_DIR="$ROOT/.server/run_alt" SMOKE_PORT=25561 SMOKE_LOG="$ROOT/$LOG" \
    ./tools/smoke.sh --deploy --commands "$CMDS" \
    --fake-player "$PLAYERS" --fake-commands "$FAKE" \
    --fake-delay 1 --fake-stay "$MAXWAIT" >/dev/null 2>&1

CLEANF=".server/verify-two-${GAME}-clean.log"
sed 's/\x1b\[[0-9;]*m//g' "$LOG" > "$CLEANF"
has()   { grep -aqE "$1" "$CLEANF"; }
count() { grep -acE "$1" "$CLEANF"; }

ok=1
say() { printf '  %s %s\n' "$1" "$2"; }

if has "已创建房间 ${ROOM_A}" && has "已创建房间 ${ROOM_B}"; then
    say "✔" "两个房间都建好了"
else
    say "✘" "建房失败（看日志里「已创建房间」）"; ok=0
fi

RUN_ROOMS=$(grep -aoE "${GAME}/[^ ]+ \[RUNNING\]" "$CLEANF" | sort -u | wc -l)
if [ "$RUN_ROOMS" -ge 2 ]; then
    say "✔" "两房都进 RUNNING（不同会话 $RUN_ROOMS 个：$(grep -aoE "${GAME}/[^ ]+ \[RUNNING\]" "$CLEANF" | sort -u | tr '\n' ' '))"
else
    say "✘" "只有 $RUN_ROOMS 个会话进过 RUNNING（期望 2）"; ok=0
fi

ENDS=$(count "收场 reason=ROUND_END")
if [ "$ENDS" -ge 2 ]; then
    say "✔" "两场都正常结算（收场 ROUND_END × $ENDS）"
else
    say "✘" "收场 ROUND_END 只出现 $ENDS 次（期望 2；窗口可能不够，或有一场没结算）"; ok=0
fi

ERRS=$(count " (ERROR|SEVERE)\]:")
if [ "$ERRS" -eq 0 ]; then
    say "✔" "ERROR 行 0"
else
    say "✘" "ERROR 行 $ERRS"; ok=0
fi

echo
if [ "$ok" -eq 1 ]; then
    echo "== 通过 ✅（日志：$LOG）=="
    exit 0
else
    echo "== 未通过 ❌（日志：$LOG）=="
    exit 1
fi
