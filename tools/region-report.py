#!/usr/bin/env python3
"""区域归属表生成器（只读，不改任何东西）

依据 docs/框架不变量.md 的 **I2（区域归属单射）**：区域必须在加载期静态声明。
这个脚本回答一个问题：**按现在的配置，能不能把「谁占了哪块地」声明出来？**

扫描 server/plugins/*/config.yml，识别四种场地表示法：

  1. AABB      pos1 + pos2（都是 Location）
  2. AABB      x1,y1,z1 + x2,y2,z2 + world
  3. 圆        center + radius
  4. Location  单独的 `world,x,y,z` 或 `==: org.bukkit.Location`

然后按「插件 × 世界」汇总、两两做包围盒相交检测。

用法:
    python3 tools/region-report.py [--plugins DIR] [--out FILE]

只读，不修改服务器。默认探测：解压出来的上游服务器 → 本地 server/plugins。
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("需要 PyYAML：pip install pyyaml")

WORLD_KEYS = ("world", "world_key", "worldKey", "level")

# 字符串形式的坐标："world_mini_game,12.50,50.00,301.50[,yaw,pitch]"
# 世界段可以是真实世界名，也可以是房间化后的 `tpl:<模板键>` 令牌
STR_LOC = re.compile(
    r"^\s*(?P<world>(?:tpl:)?[A-Za-z0-9_\-]+)\s*,\s*(?P<x>-?\d+(?:\.\d+)?)\s*,\s*"
    r"(?P<y>-?\d+(?:\.\d+)?)\s*,\s*(?P<z>-?\d+(?:\.\d+)?)\s*(?:,[-\d.]+\s*){0,2}$"
)


def norm_world(v):
    if v is None:
        return None
    s = str(v).strip()
    # `tpl:<模板键>` 要原样保留：它表示"这块场地在模板世界里、开房时复制到房间世界"，
    # 去掉前缀会把它伪装成一个真实世界名（I2 取证就错了）。
    if s.startswith("tpl:"):
        return s
    return s.split(":")[-1] if ":" in s else s


def as_float(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def as_loc(node):
    """node 是不是一个 Location？返回 (world, x, y, z) 或 None"""
    if not isinstance(node, dict):
        return None
    if not all(k in node for k in ("x", "y", "z")):
        return None
    world = next((norm_world(node[k]) for k in WORLD_KEYS if k in node), None)
    if not world:
        return None
    x, y, z = as_float(node["x"]), as_float(node["y"]), as_float(node["z"])
    if None in (x, y, z):
        return None
    return (world, x, y, z)


def as_aabb(node):
    """node 是不是 x1..z2 形式的 AABB？"""
    if not isinstance(node, dict):
        return None
    if not all(k in node for k in ("x1", "y1", "z1", "x2", "y2", "z2")):
        return None
    world = next((norm_world(node[k]) for k in WORLD_KEYS if k in node), None)
    if not world:
        return None
    vals = [as_float(node[k]) for k in ("x1", "y1", "z1", "x2", "y2", "z2")]
    if None in vals:
        return None
    x1, y1, z1, x2, y2, z2 = vals
    return (world, min(x1, x2), min(y1, y2), min(z1, z2),
            max(x1, x2), max(y1, y2), max(z1, z2))


class Region:
    __slots__ = ("plugin", "path", "world", "how", "bb")

    def __init__(self, plugin, path, world, how, bb):
        self.plugin, self.path, self.world, self.how, self.bb = plugin, path, world, how, bb

    @property
    def size(self):
        x1, y1, z1, x2, y2, z2 = self.bb
        return (x2 - x1, y2 - y1, z2 - z1)


def walk(node, path=""):
    yield path, node
    if isinstance(node, dict):
        for k, v in node.items():
            yield from walk(v, f"{path}.{k}" if path else str(k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from walk(v, f"{path}[{i}]")


def scan_plugin(cfg_path):
    """返回 (regions, loose_points)

    regions:      [Region]                     能推出明确边界的场地
    loose_points: {world: [ (x,y,z) ]}         只知道坐标、不知道边界的
    """
    try:
        doc = yaml.safe_load(open(cfg_path, encoding="utf-8", errors="replace"))
    except Exception as ex:
        print(f"  [warn] 解析失败 {cfg_path}: {ex}", file=sys.stderr)
        return [], {}
    if not isinstance(doc, dict):
        return [], {}

    plugin = os.path.basename(os.path.dirname(cfg_path))
    regions, loose = [], {}

    for path, node in walk(doc):
        if not isinstance(node, dict):
            continue

        # 形式 1：pos1 + pos2
        p1, p2 = as_loc(node.get("pos1")), as_loc(node.get("pos2"))
        if p1 and p2 and p1[0] == p2[0]:
            bb = (min(p1[1], p2[1]), min(p1[2], p2[2]), min(p1[3], p2[3]),
                  max(p1[1], p2[1]), max(p1[2], p2[2]), max(p1[3], p2[3]))
            regions.append(Region(plugin, path or "<root>", p1[0], "AABB(pos1/pos2)", bb))
            continue

        # 形式 2：x1..z2
        aabb = as_aabb(node)
        if aabb:
            regions.append(Region(plugin, path or "<root>", aabb[0], "AABB(x1..z2)", aabb[1:]))
            continue

        # 形式 3：center + radius
        c = as_loc(node.get("center"))
        r = as_float(node.get("radius", node.get("arena-radius")))
        if c and r and r > 0:
            bb = (c[1] - r, c[2] - r, c[3] - r, c[1] + r, c[2] + r, c[3] + r)
            regions.append(Region(plugin, path or "<root>", c[0], f"圆(r={r:g})", bb))
            continue

        # 形式 4：散落的 Location
        loc = as_loc(node)
        if loc:
            loose.setdefault(loc[0], []).append(loc[1:])

    # 形式 5：字符串形式的坐标 "world,x,y,z"
    for path, node in walk(doc):
        if not isinstance(node, str):
            continue
        m = STR_LOC.match(node)
        if m:
            loose.setdefault(m["world"], []).append(
                (float(m["x"]), float(m["y"]), float(m["z"])))
    return regions, loose


def overlaps(aw, a, bw, b):
    """a,b 都是 6 元组 (x1,y1,z1,x2,y2,z2)"""
    if aw != bw:
        return False
    return not (a[3] < b[0] or b[3] < a[0]
                or a[4] < b[1] or b[4] < a[1]
                or a[5] < b[2] or b[5] < a[2])


def fmt(bb):
    return (f"{bb[0]:.0f},{bb[1]:.0f},{bb[2]:.0f} ~ "
            f"{bb[3]:.0f},{bb[4]:.0f},{bb[5]:.0f}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--plugins", default=None)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    candidates = [
        args.plugins,
        *sorted(glob.glob(os.path.join(root, ".server/server/small_games/*/plugins"))),
        os.path.join(root, "服务器小游戏插件/server/plugins"),
    ]
    plugins_dir = next((c for c in candidates if c and os.path.isdir(c)), None)
    if not plugins_dir:
        sys.exit("找不到 plugins 目录，用 --plugins 指定")
    plugins_dir = os.path.abspath(plugins_dir)

    all_regions, loose_by_plugin = [], {}
    for cfg in sorted(glob.glob(os.path.join(plugins_dir, "*", "config.yml"))):
        regs, loose = scan_plugin(cfg)
        all_regions += regs
        plugin = os.path.basename(os.path.dirname(cfg))
        if loose:
            loose_by_plugin[plugin] = loose

    out = []
    w = out.append
    w("# 区域归属表")
    w("")
    w("> 由 `tools/region-report.py` 生成（只读，不修改服务器）。")
    w("> 依据 `docs/框架不变量.md` 的 **I2（区域归属单射）**：区域必须在加载期静态声明。")
    w(f"> 扫描目录：`{os.path.relpath(plugins_dir, root)}`")
    w("")

    # ---------- 1 世界租户表
    tenants = {}
    for r in all_regions:
        tenants.setdefault(r.world, set()).add(r.plugin)
    for plugin, loose in loose_by_plugin.items():
        for wld in loose:
            tenants.setdefault(wld, set()).add(plugin)

    w("## 1. 世界租户表（谁在同一个世界里）")
    w("")
    w("| 世界 | 租户数 | 插件 |")
    w("| --- | --- | --- |")
    for wld, users in sorted(tenants.items(), key=lambda kv: (-len(kv[1]), kv[0])):
        w(f"| `{wld}` | {len(users)} | {' / '.join(sorted(users))} |")
    w("")

    # ---------- 2 可声明区域
    w("## 2. 能静态声明的区域")
    w("")
    w("| 插件 | 配置路径 | 世界 | 形状 | 包围盒 (x1,y1,z1 ~ x2,y2,z2) |")
    w("| --- | --- | --- | --- | --- |")
    for r in sorted(all_regions, key=lambda r: (r.plugin, r.path)):
        w(f"| {r.plugin} | `{r.path}` | `{r.world}` | {r.how} | {fmt(r.bb)} |")
    w("")

    # ---------- 3 无法声明的
    w("## 3. 只有坐标、声明不出边界的")
    w("")
    w("| 插件 | 世界 | 散落坐标点数 |")
    w("| --- | --- | --- |")
    for plugin, loose in sorted(loose_by_plugin.items()):
        for wld, pts in sorted(loose.items()):
            if any(r.plugin == plugin and r.world == wld for r in all_regions):
                continue
            w(f"| {plugin} | `{wld}` | {len(pts)} |")
    w("")

    # ---------- 4 重叠
    w("## 4. 同世界区域相交检测")
    w("")
    w("判定：两个**不同插件**的包围盒在 X/Y/Z 三轴上都相交。")
    w("")
    hits = []
    for i in range(len(all_regions)):
        for j in range(i + 1, len(all_regions)):
            a, b = all_regions[i], all_regions[j]
            if a.plugin == b.plugin:
                continue
            if overlaps(a.world, a.bb, b.world, b.bb):
                hits.append((a, b))
    if hits:
        w("| 世界 | 插件 A | 插件 B | A 的范围 | B 的范围 |")
        w("| --- | --- | --- | --- | --- |")
        for a, b in hits:
            w(f"| `{a.world}` | {a.plugin} | {b.plugin} | {fmt(a.bb)} | {fmt(b.bb)} |")
    else:
        w("**（无相交）** —— 说明现有场地在空间上确实分开了，但这只说明「当前没撞」，")
        w("不代表「不会撞」：没有任何机制阻止下一个场地建到别人头上。")
    w("")

    # ---------- 5 结论
    n_declared = len(all_regions)
    n_loose_plugins = len([p for p, l in loose_by_plugin.items()
                           if not any(r.plugin == p for r in all_regions)])
    top = sorted(tenants.items(), key=lambda kv: -len(kv[1]))[:1]
    w("## 5. 结论")
    w("")
    w(f"1. **没有任何插件声明过「这块地归我」**。配置里只有坐标、中心点、半径，")
    w(f"   于是框架无法判定归属 —— I2 现在完全靠人记。")
    w(f"2. 能推出明确边界的场地：**{n_declared} 处**；只能拿到坐标、推不出边界的插件：**{n_loose_plugins} 个**。")
    if top:
        wld, users = top[0]
        w(f"3. 最拥挤的是 `{wld}`，**{len(users)} 个租户**共用。"
          f"对这个世界的任何「整体操作」（gamerule / 区块 / 实体扫描 / 爆炸保护）都会波及全部租户 ——")
        w(f"   这就是 `world_mini_game` 里那些「玩 A 游戏影响 B 游戏」类 bug 的结构性原因。")
    w("4. 要做 I2 的静态校验，第一步是给每个游戏补标准的 `ArenaRegion` 声明：")
    w("   `{ world, shape: AABB|CYLINDER, 尺寸, owner: GameKey }`，把第 3 节那些补齐。")
    w("")

    text = "\n".join(out) + "\n"
    if args.out:
        os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
        open(args.out, "w", encoding="utf-8").write(text)
        print(f"写入 {args.out}")
    else:
        print(text)


if __name__ == "__main__":
    main()
