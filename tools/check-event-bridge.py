#!/usr/bin/env python3
"""**事件桥接体检**：游戏会话里 `instanceof XxxEvent` 处理的每一种事件，框架必须真的派发。

为什么需要：
    重构把各游戏的监听器搬进了 `GameSession#onGameEvent`（I3：游戏侧 0 个 @EventHandler），
    但"搬进去"只是第一步 —— 事件还得由框架（`RegionEvents` / `SessionEvents`）**派发**过来。
    2026-11-07 体检发现：游戏侧处理 51 种事件，框架只桥接 31 种，**26 种永远收不到**
    （捡箭、切换副手、潜行/疾跑、死亡后的游戏模式切换、盔甲架交互、聊天猜词……），
    玩家侧就是"机制莫名其妙丢了"。

用法：
    python3 tools/check-event-bridge.py [--verbose]
    退出码 0 = 全部有派发；1 = 有游戏写在等一个永远不会来的事件。
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLUG = os.path.join(ROOT, "服务器小游戏插件")
FRAMEWORK = [
    os.path.join(PLUG, "MiniGameLib/src/main/java/com/hub/session/RegionEvents.java"),
    os.path.join(PLUG, "MiniGameLib/src/main/java/com/hub/session/SessionEvents.java"),
]

# 这些事件是"子类型 / 异步包装"，框架通过其父类型或专用方法已经派发，不算漏
KNOWN_SUBTYPES = {
    "EntityDamageByEntityEvent",   # RegionEvents#onDamage 收到的是同一个实例
}


def handled_events():
    """游戏会话里 instanceof 过的所有事件类名 → 涉及的游戏模块。"""
    out = {}
    for path in glob.glob(os.path.join(PLUG, "*/src/main/java/**/*Session.java"), recursive=True):
        game = os.path.relpath(path, PLUG).split(os.sep)[0]
        with open(path, encoding="utf-8") as fh:
            src = fh.read()
        for m in re.finditer(r"instanceof\s+([A-Z][A-Za-z0-9]*Event)\b", src):
            out.setdefault(m.group(1), set()).add(game)
        # 还有些游戏在 Manager 里用 instanceof 处理（通过 session 转发）
    return out


def bridged_events():
    """框架 @EventHandler 方法参数里出现过的事件类名。"""
    out = set()
    for path in FRAMEWORK:
        with open(path, encoding="utf-8") as fh:
            src = fh.read()
        for m in re.finditer(r"@EventHandler[^\n]*\n\s*public void \w+\(\s*([A-Za-z0-9_.]+)\s+\w+", src):
            out.add(m.group(1).split(".")[-1])
    return out


def main():
    verbose = "--verbose" in sys.argv
    handled = handled_events()
    bridged = bridged_events()
    missing = {k: v for k, v in handled.items()
               if k not in bridged and k not in KNOWN_SUBTYPES}

    print(f"游戏会话处理的事件：{len(handled)} 种")
    print(f"框架桥接的事件：    {len(bridged)} 种")
    if missing:
        print(f"\n❌ 以下 {len(missing)} 种事件**游戏写了但框架不会派发**：")
        for name in sorted(missing):
            print(f"  - {name:<40} 涉及：{', '.join(sorted(missing[name]))}")
        print("\n修法：在 RegionEvents 里加一个 @EventHandler，用 bridgePlayer/bridgeLocation 转发。")
        return 1
    print("\n✅ 全部有派发（游戏侧写的事件都能收到）")
    if verbose:
        for name in sorted(handled):
            print(f"  {name:<40} {', '.join(sorted(handled[name]))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
