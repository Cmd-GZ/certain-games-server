#!/usr/bin/env python3
"""给每个游戏的运行时配置补「场地别名」——同一游戏开多个房间时分开场地用。

背景（D22 后半的**数据侧**解法）：游戏的 `Map<场地名, Arena/Session>` 都按**场地名**索引。
同一张地图开第二个房间时，框架会用 `<场名>-2` 当场地名（`RoomManager.arenaAliasFor`），
所以游戏配置里必须有 `<场名>-2 … -8` 这几份**内容和原条目一模一样**的别名条目
（需要快照的游戏再复制一份 `arena-<场名>-N.snap`）。

用法：
    python3 tools/arena-alias.py <某个 plugins 目录> [--max 8] [--dry-run]

它只做两件事：① 在 `arenas:` / `maps:` 段里复制条目；② 复制对应 `.snap`。
**不动任何玩法参数**（坐标、规则原样照抄），重复运行是幂等的。
"""
import argparse
import os
import re
import shutil
import sys

SECTIONS = ('arenas:', 'maps:', 'list:')


def split_entries(lines, section_idx):
    """返回 [(key, start, end)]：从 section 行之后解析出同级子键块。"""
    i = section_idx + 1
    while i < len(lines) and not lines[i].strip():
        i += 1
    if i >= len(lines):
        return [], None
    m = re.match(r'^(\s+)([^\s:#][^:]*):\s*$', lines[i])
    if not m:
        m = re.match(r'^(\s+)([^\s:#][^:]*):\s*\S', lines[i])
    if not m:
        return [], None
    indent = len(m.group(1))
    entries = []
    j = i
    while j < len(lines):
        line = lines[j]
        if line.strip():
            cur = len(line) - len(line.lstrip())
            if cur < indent or (cur == indent and not re.match(r'^\s+[^\s:#][^:]*:', line)):
                break
            if cur == indent and re.match(r'^\s+[^\s:#][^:]*:', line):
                key = line.strip().rstrip(':').strip()
                entries.append([key, j, j + 1])
                if len(entries) > 1:
                    entries[-2][2] = j
        j += 1
    if entries:
        entries[-1][2] = j
        # 末尾的空行不算条目内容
        for e in entries:
            while e[2] > e[1] + 1 and not lines[e[2] - 1].strip():
                e[2] -= 1
    return entries, indent


def process(cfg_path, base_dir, max_alias, dry):
    with open(cfg_path, encoding='utf-8') as f:
        lines = f.read().split('\n')
    changed = 0
    snaps = 0
    for name in SECTIONS:
        try:
            idx = next(i for i, l in enumerate(lines) if l.strip() == name)
        except StopIteration:
            continue
        entries, indent = split_entries(lines, idx)
        if not entries:
            continue
        # 从后往前插，避免行号漂移
        for key, start, end in reversed(entries):
            if re.search(r'-\d+$', key):
                continue           # 已经是别名
            block = lines[start:end]
            if f'{key}-2' in '\n'.join(lines):
                continue           # 已经补过
            pad = ' ' * indent
            add = []
            for n in range(2, max_alias + 1):
                newkey = f'{key}-{n}'
                body = [pad + newkey + ':'] + block[1:]
                add.extend(body)
                src = os.path.join(base_dir, f'arena-{key}.snap')
                if os.path.exists(src) and not dry:
                    shutil.copyfile(src, os.path.join(base_dir, f'arena-{key}-{n}.snap'))
                    snaps += 1
            lines[end:end] = add
            changed += 1
    if changed and not dry:
        with open(cfg_path, 'w', encoding='utf-8') as f:
            f.write('\n'.join(lines))
    return changed, snaps


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('plugins_dir')
    ap.add_argument('--max', type=int, default=8, help='补到 <场名>-N（默认 8，对应每游戏 8 房间）')
    ap.add_argument('--dry-run', action='store_true')
    a = ap.parse_args()
    total = 0
    for game in sorted(os.listdir(a.plugins_dir)):
        d = os.path.join(a.plugins_dir, game)
        cfg = os.path.join(d, 'config.yml')
        if not os.path.isfile(cfg):
            continue
        changed, snaps = process(cfg, d, a.max, a.dry_run)
        if changed:
            print(f'{game:16s} 补了 {changed} 组别名，复制 {snaps} 个快照')
            total += changed
    print(f'共 {total} 组{"（dry-run，未写盘）" if a.dry_run else ""}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
