#!/usr/bin/env python3
"""对着编译错误自动补 import（I3 搬迁的收尾工具）。

用法：
    python3 tools/fix-imports.py <模块目录> [最多轮数]

它反复：编译 → 抓 `找不到符号: 类 Xxx` / `符号: 变量 Xxx` → 在**整个仓库**里找一个
`import ...Xxx;` → 插进报错的 Session 文件 → 再编译。最多 5 轮。

它只补 import，**不改逻辑**。如果是"找不到符号: 方法 xxx"（原 Listener 的私有 helper），
它补不了 —— 那种要从 `git show HEAD:<被删文件>` 手工搬（踩过：LandClaim 的 isGameItem）。
"""
import re
import subprocess
import sys
import os

MVN = '/home/user/MyProject/certain-games-server/.server/tools/apache-maven-3.9.9/bin/mvn'
REPO = '/home/user/MyProject/certain-games-server/.server/m2repo'
ENV = dict(os.environ, JAVA_HOME='/usr/lib/jvm/java-25-openjdk')


def import_index(root):
    """symbol -> import 行（在整个仓库里找）。"""
    idx = {}
    for base, _d, files in os.walk(root):
        if '/.server' in base or '/target' in base:
            continue
        for f in files:
            if not f.endswith('.java'):
                continue
            p = os.path.join(base, f)
            try:
                s = open(p, encoding='utf-8').read()
            except OSError:
                continue
            for line in s.split('\n'):
                line = line.strip()
                if line.startswith('import ') and line.endswith(';'):
                    sym = line[:-1].split('.')[-1]
                    if sym and sym not in idx:
                        idx[sym] = line
    return idx


def compile_mod(mod):
    r = subprocess.run([MVN, '-q', '-o', f'-Dmaven.repo.local={REPO}', 'clean', 'package', '-DskipTests'],
                       cwd=mod, capture_output=True, text=True, env=ENV)
    return r.returncode, (r.stdout or '') + (r.stderr or '')


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    mod = sys.argv[1].rstrip('/')
    rounds = int(sys.argv[2]) if len(sys.argv) > 2 else 5
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    idx = import_index(root)

    for i in range(rounds):
        code, out = compile_mod(mod)
        if code == 0:
            print(f'✅ 第 {i} 轮后编译通过')
            return 0
        # 只处理"类/变量"缺失，方法缺失交给人工
        missing = set()
        for m in re.finditer(r'符号:\s*(?:类|变量)\s*([A-Za-z_][A-Za-z0-9_]*)', out):
            missing.add(m.group(1))
        files = set(re.findall(r'(/[^\s:]*\.java):\[\d+', out))
        if not missing or not files:
            print('还有非 import 类错误，需要人工处理：')
            for line in out.split('\n'):
                if '符号' in line or 'ERROR' in line and 'java' in line:
                    print('  ', line.strip())
            return 1
        added = 0
        for f in files:
            s = open(f, encoding='utf-8').read()
            for sym in sorted(missing):
                imp = idx.get(sym)
                if not imp or imp in s:
                    continue
                s = s.replace('import com.hub.session.GameSession;',
                              'import com.hub.session.GameSession;\n' + imp, 1)
                added += 1
            open(f, 'w', encoding='utf-8').write(s)
        print(f'第 {i + 1} 轮：补了 {added} 个 import（{", ".join(sorted(missing))}）')
        if added == 0:
            print('没得补了，交给人工')
            return 1
    return 1


if __name__ == '__main__':
    sys.exit(main())
