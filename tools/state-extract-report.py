#!/usr/bin/env python3
"""乙类游戏的「状态抽取」侦察工具（D22 多房间用）。

背景：有些游戏把「一局的状态」直接摊在 manager 的字段上（键是硬编码的 "main" 或场地名），
同一游戏开第二个房间时两份状态会撞在一起。要改成「每房间一份」，就得先知道：

  ① 哪些字段是**局内状态**（每局都要独立）；
  ② 每个字段被引用多少处；
  ③ 这些引用点里，有多少处**拿得到玩家**（有玩家的地方好改，没玩家的地方要额外传键）。

用法：
    python3 tools/state-extract-report.py <模块目录> <Manager 类名> [字段1 字段2 ...]

不给字段名时，会自动把"可变 + 非 static/final 集合、或 Map/Set/List 且名字像状态"的字段列为候选。
它只读代码、不改任何东西。
"""
import os
import re
import sys

PLAYER_HINT = re.compile(r'\bPlayer\s+\w+|\bUUID\s+\w+|\bgetUniqueId\(\)')


def collect_fields(src):
    """返回 [(字段名, 声明行, 是否 final)]。"""
    out = []
    for m in re.finditer(r'^    private\s+(?:static\s+)?(?:final\s+)?([A-Za-z0-9_<>,\.\[\] ]+?)\s+([a-zA-Z_]\w*)\s*(?:=[^;]*)?;',
                         src, re.M):
        type_, name = m.group(1).strip(), m.group(2)
        if name.isupper():
            continue
        if type_ in ('MemoryFlipPlugin',):
            continue
        out.append((name, m.group(0).strip(), 'final' in m.group(0)))
    return out


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    mod, cls = sys.argv[1].rstrip('/'), sys.argv[2]
    fields = sys.argv[3:]
    path = None
    for base, _d, files in os.walk(mod):
        for f in files:
            if f == f'{cls}.java':
                path = os.path.join(base, f)
    if not path:
        print(f'找不到 {cls}.java')
        return 1
    src = open(path, encoding='utf-8').read()
    lines = src.split('\n')

    if not fields:
        # 候选：Set/Map/List 类型，或名字里带常见局内状态词
        cand = []
        for name, decl, is_final in collect_fields(src):
            if re.search(r'(Set|Map|List)<', decl) or re.search(
                    r'(state|phase|running|ending|task|seconds|started|pending|item)', name, re.I):
                cand.append(name)
        fields = cand

    print(f'{cls}：{len(fields)} 个候选字段\n')
    print(f'{"字段":22s} {"引用":>4s} {"有玩家上下文":>10s}  说明')
    print('-' * 78)
    for name in fields:
        refs = [i for i, l in enumerate(lines)
                if re.search(r'\b' + re.escape(name) + r'\b', l) and 'private' not in l]
        if not refs:
            continue
        with_player = 0
        for i in refs:
            window = '\n'.join(lines[max(0, i - 25):i + 1])
            if PLAYER_HINT.search(window):
                with_player += 1
        flag = '易改' if with_player == len(refs) else ('部分可改' if with_player else '要传键')
        print(f'{name:22s} {len(refs):>4d} {with_player:>10d}  {flag}')
    print('\n提示：')
    print('  · refs 里有 "要传键" 的字段 → 需要在该方法签名上加 roomKey 参数，或改成从 RoomScoped 取。')
    print('  · 一次只切一组字段，改完立刻编译 + 单房间回归（单房间行为必须不变）。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
