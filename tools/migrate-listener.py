#!/usr/bin/env python3
"""把某个游戏模块的 Listener 类**整个搬迁**到它的 Session（I3 收敛工具）。

用法：
    python3 tools/migrate-listener.py <模块目录> [--dry]

它做四件事：
  1. 把 listener 里每个 `@EventHandler` 方法体搬进 `<X>Session#onGameEvent(Event)`，
     用 `if (event instanceof <原事件类> e) { ...原函数体（event 改成 e）... return; }` 包裹；
  2. 把 listener 的**实例字段**（除 plugin）一起搬到 session；
  3. 把 `plugin.` 改写成 `manager.plugin().`（manager 上没有 plugin() 的话会提示你补一个）；
  4. 删掉 listener 文件与它的 `registerEvents(...)` 注册行。

**它是"机械搬运"，不保证语义正确** —— 跑完必须：
    mvn -f <模块>/pom.xml clean package   →  冒烟 regress-a.sh  →  人眼过一遍 diff
编译不过就 `git checkout` 该模块，别手工修补切坏的代码（踩过）。
"""
import re
import sys
import os


HOOK_TYPES = {
    'PlayerInteractEvent', 'BlockBreakEvent', 'BlockPlaceEvent', 'PlayerMoveEvent',
    'EntityDamageEvent', 'EntityDamageByEntityEvent', 'PlayerQuitEvent',
    'PlayerDeathEvent', 'PlayerRespawnEvent',
}


def method_spans(src):
    """返回 [(start, end, name, eventClass)]，end 是方法右括号之后。"""
    out = []
    pat = re.compile(r'[ \t]*@EventHandler(?:\([^)]*\))?[^\n]*\n'
                     r'(?:[ \t]*@[A-Za-z][^\n]*\n)*'
                     r'[ \t]*public void (\w+)\(([A-Za-z]+) \w+\) \{')
    pos = 0
    while True:
        m = pat.search(src, pos)
        if not m:
            return out
        i = src.index('{', m.end() - 1)
        depth = 0
        j = i
        while True:
            if src[j] == '{':
                depth += 1
            elif src[j] == '}':
                depth -= 1
            j += 1
            if depth == 0:
                break
        end = j
        while end < len(src) and src[end] == '\n':
            end += 1
        out.append((m.start(), end, m.group(1), m.group(2), i + 1, j - 1))
        pos = end


def fields(src):
    """listener 的实例字段声明（跳过 plugin / 静态 / final 注入）。"""
    out = []
    for m in re.finditer(r'^    private (final )?([A-Za-z<>\[\], .]+) (\w+)( =[^;]+)?;',
                         src, re.M):
        name = m.group(3)
        if name == 'plugin':
            continue
        out.append(m.group(0).strip())
    return out


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    mod = sys.argv[1].rstrip('/')
    dry = '--dry' in sys.argv

    listeners = []
    for root, _dirs, files in os.walk(os.path.join(mod, 'src', 'main', 'java')):
        for f in files:
            if f.endswith('Listener.java'):
                p = os.path.join(root, f)
                s = open(p, encoding='utf-8').read()
                if '@EventHandler' in s:
                    listeners.append((p, s))
    if not listeners:
        print('没有需要搬的 Listener')
        return 1

    # 只认"真的 extends GameSession"的那个文件 —— 别被 XxxSession 这种同名工具类骗了
    # （踩过：GuessTheBuild 的 WandSession 只是建场工具的会话，不是对局会话）
    sessions = []
    for root, _dirs, files in os.walk(os.path.join(mod, 'src', 'main', 'java')):
        for f in files:
            if not f.endswith('.java'):
                continue
            p = os.path.join(root, f)
            body = open(p, encoding='utf-8').read()
            if 'extends GameSession' in body:
                sessions.append(p)
    if not sessions:
        print('该模块没有 extends GameSession 的类，先手动建一个')
        return 1
    if len(sessions) > 1:
        print('有多个 GameSession 子类，请手动指定：', sessions)
        return 1
    session = sessions[0]

    blocks = []
    moved_fields = []
    for path, src in listeners:
        moved_fields += fields(src)
        for (_start, _end, _name, ev, body_start, body_end) in method_spans(src):
            body = src[body_start:body_end]
            # 一律用 e —— 用 event 会和 onGameEvent 的形参同名（踩过）
            arg = 'e'
            body = re.sub(r'\bevent\b', 'e', body)
            body = body.replace('plugin.', 'manager.plugin().')
            blocks.append(f'        if (event instanceof {ev} {arg}) {{{body}            return;\n        }}')
        # 删掉 listener 文件与注册行
        print(('将删除' if dry else '删除'), path)
        if not dry:
            os.remove(path)
    # 删注册行
    for root, _dirs, files in os.walk(os.path.join(mod, 'src', 'main', 'java')):
        for f in files:
            p = os.path.join(root, f)
            s = open(p, encoding='utf-8').read()
            if 'registerEvents' in s and 'Listener' in s:
                s2 = re.sub(r'[ \t]*getServer\(\)\.getPluginManager\(\)\.registerEvents\(new \w+Listener\([^)]*\), this\);\n',
                            '        // I3：本插件已无 Listener —— 事件由框架按归属派发到 Session 的钩子\n', s)
                if s2 != s and not dry:
                    open(p, 'w', encoding='utf-8').write(s2)
                    print('去掉注册行', p)

    ss = open(session, encoding='utf-8').read()
    imp = [i for i in ['import org.bukkit.event.Event;', 'import org.bukkit.event.entity.EntityDamageEvent;',
                       'import org.bukkit.event.block.BlockBreakEvent;', 'import org.bukkit.event.block.BlockPlaceEvent;',
                       'import org.bukkit.event.player.PlayerInteractEvent;',
                       'import org.bukkit.event.player.PlayerMoveEvent;'] if i not in ss]
    if imp:
        ss = ss.replace('import com.hub.session.GameSession;',
                        'import com.hub.session.GameSession;\n' + '\n'.join(imp), 1)
    add = '\n    // ---------- I3：由 listener 机械搬迁过来的处理器（按事件类型分派） ----------\n\n'
    if moved_fields:
        add += '\n'.join('    ' + f for f in moved_fields) + '\n\n'
    add += '    @Override\n    protected void onGameEvent(org.bukkit.event.Event event) {\n'
    add += '\n'.join(blocks) + '\n    }\n'
    ss = ss.rstrip()
    assert ss.endswith('}')
    ss = ss[:-1] + add + '}\n'
    if not dry:
        open(session, 'w', encoding='utf-8').write(ss)
    print('搬迁完成：', session, '（', len(blocks), '个处理器）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
