#!/usr/bin/env python3
# 生成主机侧 typecheck 用的 R stub（真 R 由 CI 的 aapt2 生成，这里只要能编译过）。
#
# 为什么要有它：沙箱里没有 aapt2，但 ecj 全量编 src/ 需要 R.java。
# 2026-09-23 之前这个文件是手搓在 /tmp 的，沙箱一重启就没了，只能再搓一遍 ——
# 现在脚本化：`python3 scripts/gen_r_stub.py [输出路径]`（默认 /tmp/ac/R.java）。
#
# 注意：id 值与真实 aapt2 不一致也没关系（主机测试不解析资源表）；
# 但名字集合要全 —— 扫描来源：res/values*/*.xml 各标签、layout 里的 @+id/、
# drawable/font/anim/raw/xml/color 目录文件名、Manifest 里的 @mipmap/@drawable/@style 引用。
# 编译后若报 "R.xxx cannot be resolved"，把缺的名字补进对应扫描规则即可。
import glob
import os
import re
import sys

PKG = 'com.aidemo.wordsprint'
out_path = sys.argv[1] if len(sys.argv) > 1 else '/tmp/ac/R.java'
os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))

types = {}


def add(t, names):
    types.setdefault(t, [])
    for n in names:
        if n not in types[t]:
            types[t].append(n)


for f in glob.glob('res/values*/*.xml'):
    s = open(f, encoding='utf-8').read()
    for t, tag in [('string', 'string'), ('color', 'color'), ('dimen', 'dimen'),
                   ('integer', 'integer'), ('bool', 'bool'), ('style', 'style'), ('attr', 'attr')]:
        add(t, re.findall(r'<%s\s+name="([^"]+)"' % tag, s))
    add('array', re.findall(r'<(?:string|integer)-array\s+name="([^"]+)"', s))
    add('id', re.findall(r'<item\s+name="([^"]+)"\s+type="id"', s))

for f in glob.glob('res/layout*/*.xml') + glob.glob('res/xml/*.xml'):
    s = open(f, encoding='utf-8').read()
    add('id', re.findall(r'@\+id/([^"]+)"', s))

add('layout', [os.path.splitext(os.path.basename(f))[0] for f in glob.glob('res/layout*/*.xml')])
add('drawable', [os.path.splitext(os.path.basename(f))[0] for f in glob.glob('res/drawable*/*')])
add('mipmap', [os.path.splitext(os.path.basename(f))[0] for f in glob.glob('res/mipmap*/*')])
add('font', [os.path.splitext(os.path.basename(f))[0] for f in glob.glob('res/font/*')])
add('anim', [os.path.splitext(os.path.basename(f))[0] for f in glob.glob('res/anim/*.xml')])
add('raw', [os.path.splitext(os.path.basename(f))[0] for f in glob.glob('res/raw/*')])
add('xml', [os.path.splitext(os.path.basename(f))[0] for f in glob.glob('res/xml/*.xml')])
add('color', [os.path.splitext(os.path.basename(f))[0] for f in glob.glob('res/color*/*.xml')])

mf = open('AndroidManifest.xml', encoding='utf-8').read()
add('mipmap', re.findall(r'@mipmap/([^"]+)"', mf))
add('drawable', re.findall(r'@drawable/([^"]+)"', mf))
add('style', re.findall(r'@style/([^"]+)"', mf))

# 资源名规则：字母开头，字母/数字/下划线；style 名里的点在 R.style 里换成下划线
# （驼峰、大写都合法 —— 2026-09-23 那版把 wpBg/btnBack 这类全过滤没了，0.5 小时冤案）
def ok(t, n):
    return bool(re.match(r'^[A-Za-z][A-Za-z0-9_.]*$', n)) if t == 'style' \
        else bool(re.match(r'^[A-Za-z][A-Za-z0-9_]*$', n))


out = ['package %s;' % PKG, 'public final class R {']
nid = 0x7f000000
for t in ['anim', 'attr', 'bool', 'color', 'dimen', 'drawable', 'font', 'id',
          'integer', 'layout', 'mipmap', 'raw', 'string', 'style', 'xml', 'array']:
    names = sorted({n.replace('.', '_') for n in types.get(t, []) if ok(t, n)})
    if not names:
        continue
    out.append('  public static final class %s {' % t)
    for n in names:
        nid += 1
        out.append('    public static final int %s = %d;' % (n, nid))
    out.append('  }')
out.append('}')

os.makedirs(os.path.dirname(out_path), exist_ok=True)
open(out_path, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print('R stub →', out_path, '·', nid - 0x7f000000, 'entries')
