#!/usr/bin/env bash
# 本地 MCSManager（面板 + 守护进程）辅助脚本 —— 用来模拟上游的"面板部署"环境。
#
# 为什么要有它：MCSM 的 web(23333) 与 daemon(24444) 都是长驻 node 进程。
# 直接 `node app.js &` 在本仓库的沙箱里**活不过一次命令**（每次 bash 调用是独立进程树），
# 所以必须由**受管后台任务**启动（harness 的 run_in_background）：
#
#   tools/mcsm.sh start-daemon     # ← 用 run_in_background 跑这一行
#   tools/mcsm.sh start-web        # ← 同上，另起一个
#   tools/mcsm.sh status           # 前台即可
#
# 目录（全部在 .server/，已 gitignore）：
#   .server/mcsm/mcsmanager/            解包出来的 MCSM（daemon/ + web/）
#   .server/mcsm/mcsmanager/daemon/data/InstanceConfig/*.json   实例定义（panel 的"实例列表"就是这些）
#   .server/mcsm/instance/              **本仓库的部署实例**（Paper + 31 个插件 + 配置 + 世界）
#   .server/mcsm/token.txt              面板登录 token（脚本里用来调 API）
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MCSM="$ROOT/.server/mcsm"
MC="$MCSM/mcsmanager"
DAEMON_PORT=24444
WEB_PORT=23333
NODE_BIN="${NODE_BIN:-$(command -v node || echo /usr/bin/node)}"

usage() {
    cat <<'EOF'
用法：tools/mcsm.sh <命令>

  start-daemon   启动守护进程（24444）——**必须用受管后台任务跑**
  start-web      启动面板（23333）——同上
  status         看端口、实例数、实例目录是否就绪
  instance       打印部署实例的路径与启动命令
EOF
}

port_up() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && exec 3<&- 3>&-; }

case "${1:-}" in
start-daemon)
    cd "$MC/daemon" || exit 1
    echo "== MCSManager daemon → http://localhost:$DAEMON_PORT =="
    exec "$NODE_BIN" app.js
    ;;
start-web)
    cd "$MC/web" || exit 1
    echo "== MCSManager panel → http://localhost:$WEB_PORT =="
    exec "$NODE_BIN" app.js
    ;;
status)
    echo "== 端口 =="
    port_up "$DAEMON_PORT" && echo "  daemon $DAEMON_PORT  ✅" || echo "  daemon $DAEMON_PORT  ✘（未启动）"
    port_up "$WEB_PORT" && echo "  panel  $WEB_PORT  ✅" || echo "  panel  $WEB_PORT  ✘（未启动）"
    echo "== 实例定义 =="
    if [ -d "$MC/daemon/data/InstanceConfig" ]; then
        for f in "$MC/daemon/data/InstanceConfig"/*.json; do
            [ -f "$f" ] || continue
            python3 - "$f" <<'PY'
import json, sys, os
d = json.load(open(sys.argv[1], encoding='utf-8'))
name = d.get('nickname', '?')
if name.startswith('__MCSM'):
    raise SystemExit   # 面板自带的"终端实例"，不是游戏服务器
cwd = d.get('cwd', '?')
ok = '✅' if os.path.isfile(os.path.join(cwd, 'server.jar')) else '✘ 缺 server.jar'
print(f"  {name:<22} cwd={cwd} {ok}")
PY
        done
    else
        echo "  （没有 InstanceConfig 目录）"
    fi
    echo "== 部署实例 =="
    if [ -f "$MCSM/instance/server.jar" ]; then
        echo "  $MCSM/instance（$(ls "$MCSM/instance/plugins"/*.jar 2>/dev/null | wc -l) 个插件）"
    else
        echo "  ✘ 尚未准备（从 .server/run 复制一份，再 --deploy）"
    fi
    ;;
instance)
    echo "路径：$MCSM/instance"
    echo "启动：cd $MCSM/instance && java -Xms4G -Xmx6G -jar server.jar nogui"
    echo "端口：$(grep -E '^server-port=' "$MCSM/instance/server.properties" 2>/dev/null || echo '未知')"
    ;;
*)
    usage
    exit 2
    ;;
esac
