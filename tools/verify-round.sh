#!/usr/bin/env bash
# 单局全流程验证：**建房 → 玩家进房 → 断言 [RUNNING] → 断言收场/销毁房间 → 断言 ERROR 0**
#
# 为什么需要它：`regress-a.sh` 只验到"建房/进房/会话/收房 + ERROR 0"，观测窗口 70 秒，
# 而很多游戏的**开局门槛**（最少人数、30 秒倒计时、派对编排）在那个窗口里到不了，
# 于是 `session.start()` 这类"只有真开局才走"的路径一直没被验证过。
#
# 用法：
#   tools/verify-round.sh <gameKey> <地图名> [玩家数=2] [最长等待秒=240]
# 例：
#   tools/verify-round.sh anvilbattle 铁砧掘战 2 180
#   tools/verify-round.sh murdermystery 圣诞工厂 4 240 "mm start 圣诞工厂"
#
# 第 5 个参数 = 控制台开局命令。D21 规定"队列里有 OP 就暂停倒计时"，而假玩家都是 OP，
# 所以**不在派对 games 里的游戏**必须由脚本手动开局（例如 `mm start <场地>`）。
#
# 环境：默认用隔离副本 .server/run_alt（端口 25561），不会动交付副本。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GAME="${1:?用法: tools/verify-round.sh <gameKey> <地图名> [玩家数] [最长等待秒]}"
MAP="${2:?缺少地图名}"
PLAYERS="${3:-2}"
MAXWAIT="${4:-240}"
STARTCMD="${5:-}"

# 房间标签必须小写（大写会生成非法房间 id）
LABEL="vr${GAME//[^a-z0-9]/}$(date +%H%M%S)"
ROOM="room_${LABEL}"
LOG=".server/verify-${GAME}.log"
CMDS=".server/verify-${GAME}-cmds.txt"
TMPL="Alpha,Beta,Gamma,Delta"

# 玩家数上限 4（fakeclient 只带 4 个名字）
[ "$PLAYERS" -gt 4 ] && PLAYERS=4

# ⓿ 先校验"地图名"是否存在（踩过两次：把显示名当地图名，/hub create 静默失败、报错只发给玩家）
VALID="$(python3 - "$GAME" <<'PYEOF'
import sys, yaml
game = sys.argv[1]
maps = yaml.safe_load(open('服务器小游戏插件/MiniGameLib/src/main/resources/maps.yml', encoding='utf-8'))['maps']
print(','.join(maps.get(game, {}).keys()))
PYEOF
)"
if [ -z "$VALID" ]; then
    echo "✘ maps.yml 里没有游戏 $GAME（可用：/hub room templates）" >&2
    exit 2
fi
if ! echo ",$VALID," | grep -q ",$MAP,"; then
    echo "✘ 地图名不对：$GAME 的地图是 [$VALID]，你给的是「$MAP」" >&2
    echo "  （提示：显示名 ≠ 地图名；地图名以 maps.yml 为准）" >&2
    exit 2
fi

echo "== 验证 $GAME（地图 $MAP，$PLAYERS 人，最长等 ${MAXWAIT}s，房间 $ROOM） =="

# ① 测试副本从交付副本还原（规则 5）
P=$(ls -d .server/run/small_games/*/plugins)
A=.server/run_alt/plugins
for d in "$P"/*/; do
    g=$(basename "$d")
    [ "$g" = "ArcadeParty" ] && continue
    mkdir -p "$A/$g"
    for f in "$d"*.yml "$d"*.snap; do [ -f "$f" ] && cp "$f" "$A/$g/"; done
done

# ①.5 派对池子收窄到本游戏（房间归 arcadeparty 的游戏必须这样才开得到它；独立游戏不受影响）
APARTY=".server/run_alt/plugins/ArcadeParty/config.yml"
if [ -f "$APARTY" ]; then
    python3 - "$APARTY" "$GAME" <<'PYEOF'
import re, sys
path, game = sys.argv[1], sys.argv[2]
s = open(path, encoding='utf-8').read()
s = re.sub(r'(?m)^flow-game:.*$', 'flow-game: ' + game, s)
open(path, 'w', encoding='utf-8').write(s)
PYEOF
fi

# ② 控制台命令：建房 + 反复打 doctor（doctor 会打印 [对局] 段，用来观测状态）
{
    echo "hub create $GAME $MAP $LABEL"
    # 等玩家进房：bot 在 t+18s 才发 join，控制台命令是逐条喂的，所以这里先空转 30 次 doctor
    for _ in $(seq 1 30); do echo "hub doctor"; done
    if [ -n "$STARTCMD" ]; then
        echo "$STARTCMD"                                       # 手动开局（OP 在队列时 D21 会暂停倒计时）
        for _ in $(seq 1 5); do echo "hub doctor"; done
        echo "$STARTCMD"                                       # 再补一次（第一次可能场地还没就绪）
    fi
    for _ in $(seq 1 $((MAXWAIT / 2))); do echo "hub doctor"; done
} > "$CMDS"

# ③ 每个 bot 在固定时刻进房（;; 分段 = 按 bot 分配）
FAKE=""
for i in $(seq 1 "$PLAYERS"); do
    FAKE="${FAKE}18:hub join ${ROOM};;"
done
FAKE="${FAKE%;;}"

rm -rf ".server/run_alt/world/dimensions/minecraft/${ROOM}_"* 2>/dev/null

SMOKE_SERVER_DIR="$ROOT/.server/run_alt" SMOKE_PORT=25561 SMOKE_LOG="$ROOT/$LOG" \
    ./tools/smoke.sh --deploy --commands "$CMDS" \
    --fake-player "$TMPL" --fake-commands "$FAKE" \
    --fake-delay 1 --fake-stay "$MAXWAIT" >/dev/null 2>&1

CLEANF=".server/verify-${GAME}-clean.log"
sed 's/\x1b\[[0-9;]*m//g' "$LOG" > "$CLEANF"
has()   { grep -aqE "$1" "$CLEANF"; }
count() { grep -acE "$1" "$CLEANF"; }

# ④ 断言
ok=1
say() { printf '  %s %s\n' "$1" "$2"; }

if has "已创建房间 ${ROOM}"; then
    say "✔" "建房成功（$ROOM）"
else
    say "✘" "建房失败：日志里没有「已创建房间 $ROOM」"; ok=0
fi

if has "${GAME}/[^ ]+ \[RUNNING\]"; then
    say "✔" "会话进入 RUNNING：$(grep -aoE "${GAME}/[^ ]+ \[RUNNING\] [0-9]+ 人" "$CLEANF" | head -1)"
else
    say "✘" "**没有观测到 ${GAME} 的 [RUNNING]**（游戏没真正开局，或会话没被推进）"; ok=0
fi

if has "对局结束，销毁房间 ${ROOM}|收场 reason="; then
    say "✔" "收场/销毁房间：$(grep -aE "对局结束，销毁房间 ${ROOM}|收场 reason=" "$CLEANF" | head -1 | sed 's/.*INFO]: *//')"
else
    say "✘" "没有观测到收场或销毁房间"; ok=0
fi

ERRS=$(count " (ERROR|SEVERE)\]:")
if [ "$ERRS" -eq 0 ]; then
    say "✔" "ERROR 行 0"
else
    say "✘" "ERROR 行 $ERRS（日志：$LOG）"; ok=0
fi

echo
if [ "$ok" -eq 1 ]; then
    echo "== 通过 ✅（日志：$LOG）=="
    exit 0
else
    echo "== 未通过 ❌（日志：$LOG）=="
    exit 1
fi
