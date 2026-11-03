#!/usr/bin/env python3
"""检查前端引用的 DOM id 是否都真存在于 index.html。

为什么要单独查：`$('foo')` 拿到 null 之后再 `.textContent = ...` 会抛 TypeError，
但只在那个分支被走到时才抛 —— 一个藏在「扫描失败」分支里的错 id，正常路径永远测不出来。
反过来，写了 `$('foo').closest('.bar').querySelector('.baz') || null;` 这种挂在不存在节点上的
链，则完全静默：不抛错、不报错、也不做事，读代码的人却会以为它在起某种作用。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HTML = os.path.join(ROOT, 'src/main/resources/static/index.html')
JS_DIR = os.path.join(ROOT, 'src/main/resources/static/js')


def ids_of(path):
    return set(re.findall(r'id="([^"]+)"', io.open(path, encoding='utf-8').read()))


def refs_of(js_dir):
    out = {}
    for name in sorted(os.listdir(js_dir)):
        if not name.endswith('.js'):
            continue
        src = io.open(os.path.join(js_dir, name), encoding='utf-8').read()
        for m in re.finditer(r"\$\('([a-z0-9_-]+)'\)", src):
            out.setdefault(m.group(1), set()).add(name)
    return out


def check(html, js_dir, label):
    ids = ids_of(html)
    missing = {k: v for k, v in refs_of(js_dir).items() if k not in ids}
    for name, files in sorted(missing.items()):
        print('FAIL  %s: %s 引用了不存在的 id "%s"' % (label, ', '.join(sorted(files)), name))
    return not missing


ok = check(HTML, JS_DIR, '前端')
if ok:
    print('ok  前端引用的 DOM id 全部存在')
sys.exit(0 if ok else 1)
