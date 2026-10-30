#!/usr/bin/env python3
"""版本一致性检查 / 修正（对应 docs/框架不变量.md 的 R4：契约一致）

唯一权威 = `src/main/resources/plugin.yml` 的 `version:`（因为它是玩家在 `/plugins` 里看到、
也是线上 jar 里真正生效的那个）。本脚本让 `pom.xml` 的版本与它对齐。

现状：26 个模块里 22 个不一致；ArcadeParty 更是 pom 1.3.1 / plugin.yml 1.3.3 / 线上 1.3.5 三处各不同。

用法:
    python3 tools/version-check.py            # 只检查，不一致就 exit 1（CI 用）
    python3 tools/version-check.py --fix      # 把 pom 版本改成 plugin.yml 的版本
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

# 参与检查的工程根（相对仓库根）
PROJECT_ROOTS = ["服务器小游戏插件", "PlayerTitles", "JoinQuitBroadcast"]

PROJECT_VER = re.compile(
    r"(<artifactId>[^<]+</artifactId>\s*\n\s*)<version>([^<]+)</version>", re.M)
PLUGIN_VER = re.compile(r"^version:\s*['\"]?([^'\"\n#]+)", re.M)
DEP = re.compile(
    r"(<groupId>com\.hub</groupId>\s*\n\s*<artifactId>minigame-lib</artifactId>\s*\n\s*"
    r"<version>)([^<]+)(</version>)", re.M)


def read(path):
    return open(path, encoding="utf-8").read()


def write(path, text):
    open(path, "w", encoding="utf-8").write(text)


def collect():
    """返回 [(module_dir, pom, plugin_yml, pom_ver, yml_ver)]"""
    rows = []
    for r in PROJECT_ROOTS:
        base = os.path.join(ROOT, r)
        if not os.path.isdir(base):
            continue
        for pom in sorted(glob.glob(os.path.join(base, "*", "pom.xml"))
                          + glob.glob(os.path.join(base, "pom.xml"))):
            d = os.path.dirname(pom)
            yml = os.path.join(d, "src/main/resources/plugin.yml")
            if not os.path.isfile(yml):
                continue
            pom_ver = PROJECT_VER.search(read(pom))
            yml_ver = PLUGIN_VER.search(read(yml))
            rows.append((d, pom, yml,
                         pom_ver.group(2).strip() if pom_ver else None,
                         yml_ver.group(1).strip() if yml_ver else None))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fix", action="store_true")
    args = ap.parse_args()

    rows = collect()
    if not rows:
        sys.exit("没找到任何模块，检查路径")

    bad = [r for r in rows if r[3] != r[4]]
    print(f"检查了 {len(rows)} 个模块，版本不一致 {len(bad)} 个")
    for d, pom, yml, pv, yv in bad:
        rel = os.path.relpath(d, ROOT)
        print(f"  {rel:<40} pom={pv:<14} plugin.yml={yv}")

    if not bad:
        print("全部一致 ✅")
        return 0

    if not args.fix:
        print("\n加 --fix 以把 pom 版本改成 plugin.yml 的版本")
        return 1

    # 1) MiniGameLib 先改，记下新版本用于同步依赖
    lib_ver = None
    for d, pom, yml, pv, yv in rows:
        if os.path.basename(d) == "MiniGameLib":
            lib_ver = yv
    if not lib_ver:
        sys.exit("找不到 MiniGameLib，无法确定 minigame-lib 依赖版本")

    changed = 0
    for d, pom, yml, pv, yv in bad:
        if pv == yv:
            continue
        text = read(pom)
        new, n = PROJECT_VER.subn(lambda m: f"{m.group(1)}<version>{yv}</version>", text, count=1)
        if n != 1:
            print(f"  [skip] {pom} 匹配不到项目版本")
            continue
        write(pom, new)
        changed += 1

    # 2) 所有 pom 里的 minigame-lib 依赖版本统一
    dep_changed = 0
    for pom in glob.glob(os.path.join(ROOT, "**", "pom.xml"), recursive=True):
        if "/.server/" in pom:
            continue
        text = read(pom)
        new, n = DEP.subn(lambda m: f"{m.group(1)}{lib_ver}{m.group(3)}", text)
        if n and new != text:
            write(pom, new)
            dep_changed += 1

    print(f"\n已改 {changed} 个 pom 的项目版本，{dep_changed} 个 pom 的 minigame-lib 依赖版本 -> {lib_ver}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
