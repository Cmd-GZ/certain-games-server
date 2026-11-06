#!/usr/bin/env bash
# 把**交付副本**（.server/run/small_games/<hash>）打成一个可以直接上传到面板的包。
#
# 为什么要脚本：直接 `tar` 整个目录会带上 4 类东西，上传后要么开不了服、要么白占空间：
#   ① world/session.lock、各世界 data/paper/metadata.dat 之类"世界身份"文件（Paper 会拒绝加载/判定重复世界）
#   ② cache/、logs/、crash-reports/（几百 MB 的运行期垃圾）
#   ③ plugins-disabled/ 里的 SkinsRestorer（**只在没外网的测试机**上禁用；上游有机房网络，按需恢复）
#   ④ 测试期生成的 room_* 世界（房间会按需从模板重建，不该随包走）
#
# 用法：
#   tools/pack-server.sh                 # → dist/small_games-server-<日期>.tar.gz
#   tools/pack-server.sh /path/out.tar.gz
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$(ls -d "$ROOT"/.server/run/small_games/*/ 2>/dev/null | head -1)"
OUT="${1:-$ROOT/dist/small_games-server-$(date +%Y%m%d-%H%M).tar.gz}"

[ -d "$SRC" ] || { echo "找不到交付副本（.server/run/small_games/*/）" >&2; exit 2; }
mkdir -p "$(dirname "$OUT")"

# ① 服务器在跑就不要打包（会打出半截世界）
if pgrep -f "server\.jar" >/dev/null 2>&1; then
    echo "有服务器进程在跑（server.jar），先关掉再打包" >&2
    exit 2
fi

# ② 清世界身份（session.lock 必须删；metadata.dat 只在"复制成新世界"时才需要删，模板本体要留）
find "$SRC" -name 'session.lock' -delete 2>/dev/null || true

# ③ 打包（排除运行期垃圾 + 测试期房间世界）
cd "$SRC"
tar czf "$OUT" \
    --exclude='./cache' \
    --exclude='./logs/*' \
    --exclude='./crash-reports/*' \
    --exclude='./world/dimensions/minecraft/room_*' \
    --exclude='./.session.lock' \
    --exclude='./plugins/*/arena-*.yml' \
    .

SIZE="$(du -h "$OUT" | cut -f1)"
echo "== 打包完成：$OUT（$SIZE） =="
cat <<'EOF'

上传到 MCSManager 后，实例里这样填：

  实例目录（工作目录/cwd）：  解压出来的服务器根目录（有 server.jar 的那一层）
  启动命令（startCommand）：
      java -Xms4G -Xmx6G -jar server.jar nogui
      # 面板上 Java 不在 PATH 时用绝对路径：/usr/lib/jvm/java-25-openjdk/bin/java
  停止命令（stopCommand）：
      stop
  端口：server.properties 里是 25560（改端口就改这里，MCSM 的端口映射跟着改）

第一次开服前请再确认三件事：
  1) Java 必须是 **25**（Paper 26.2 要求）；
  2) world/session.lock 已随包删除（脚本已处理）；
  3) plugins-disabled/ 里的 SkinsRestorer 只在"没有外网"的机器上禁用 —— 上游有机房网络，想用皮肤就移回 plugins/。
EOF
