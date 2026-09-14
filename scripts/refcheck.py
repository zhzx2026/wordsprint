#!/usr/bin/env python3
"""
无 Gradle / 无 javac 环境下的一道粗筛：把「改完没编译过」最容易踩的坑先扫一遍。

为什么需要它：本仓库的发版依赖 aapt2 + javac + d8（见 build.sh），本地/沙箱不一定有
JDK；改完一大堆 Java 时，一个不存在的方法名、一个拼错的 R.id、少注册的 Activity，
都能等到 CI 才炸。这个脚本用文本分析替代编译器，覆盖这些高频问题：

  1. 资源引用：R.string / R.id / R.layout / R.drawable / ?attr 是否真的存在
  2. 自定义类成员：Xxx.member 是否在 Xxx 里有声明（防手滑改名）
  3. 调用参数个数：Xxx.m(args) 与 Xxx 里同名方法的参数个数是否对得上
  4. 重复声明：同一个类里出现两次同名同参数类型的方法
  5. 括号配平：文件被截断/粘贴漏行
  6. AndroidManifest：Activity 是否注册、引用的类是否存在

它不是编译器，别拿它当 CI 的替代品 —— 只是编辑期间的即时反馈。
用法：python3 scripts/refcheck.py
"""
import os
import re
import sys
import collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'src/com/aidemo/wordsprint')
RES = os.path.join(ROOT, 'res')


def read(p):
    return open(p, encoding='utf-8').read()


def strip_code(t):
    """去掉注释与字符串字面量，括号/逗号计数才不会误判"""
    t = re.sub(r'/\*.*?\*/', ' ', t, flags=re.S)
    t = re.sub(r'//[^\n]*', ' ', t)
    t = re.sub(r'"(?:\\.|[^"\\])*"', '""', t)
    return re.sub(r"'(?:\\.|[^'\\])*'", "''", t)


def balance_problems(texts):
    out = []
    for cls, t in texts.items():
        st, line, p, b, sq = None, 1, 0, 0, 0
        i = 0
        while i < len(t):
            c = t[i]
            if st is None:
                if c == '\n':
                    line += 1
                elif c == '"':
                    st = 'str'
                elif c == "'":
                    st = 'chr'
                elif c == '/' and i + 1 < len(t) and t[i + 1] == '/':
                    st = 'line'
                elif c == '/' and i + 1 < len(t) and t[i + 1] == '*':
                    st = 'blk'
                    i += 1
                elif c == '(':
                    p += 1
                elif c == ')':
                    p -= 1
                elif c == '{':
                    b += 1
                elif c == '}':
                    b -= 1
                elif c == '[':
                    sq += 1
                elif c == ']':
                    sq -= 1
                if min(p, b, sq) < 0:
                    out.append('%s.java:%d 括号配平被打破' % (cls, line))
                    break
            else:
                if c == '\n':
                    line += 1
                    if st == 'line':
                        st = None
                elif st == 'str':
                    if c == '\\':
                        i += 1
                    elif c == '"':
                        st = None
                elif st == 'chr':
                    if c == '\\':
                        i += 1
                    elif c == "'":
                        st = None
                elif st == 'blk' and c == '*' and i + 1 < len(t) and t[i + 1] == '/':
                    st = None
                    i += 1
            i += 1
        if (p, b, sq) != (0, 0, 0):
            out.append('%s.java 结尾括号没配平：()=%d {}=%d []=%d' % (cls, p, b, sq))
    return out


def resources():
    strings = set(re.findall(r'<string name="([^"]+)"', read(os.path.join(RES, 'values/strings.xml'))))
    values = set()                     # values/ 下的 color/dimen/bool/integer/array（R.color.x 等）
    for d in ('values', 'values-night'):
        p = os.path.join(RES, d)
        if not os.path.isdir(p):
            continue
        for f in os.listdir(p):
            if f.endswith('.xml'):
                values |= set(re.findall(r'<(?:color|dimen|bool|integer|integer-array|string-array)\s+name="([^"]+)"',
                                         read(os.path.join(p, f))))
    names = set()
    for d in ('layout', 'drawable', 'xml', 'menu', 'font', 'raw', 'anim', 'color'):
        p = os.path.join(RES, d)
        if os.path.isdir(p):
            for f in os.listdir(p):
                names.add(f.rsplit('.', 1)[0])
    for f in os.listdir(os.path.join(RES, 'layout')):
        if f.endswith('.xml'):
            names |= set(re.findall(r'@\+id/(\w+)', read(os.path.join(RES, 'layout', f))))
    styles = set()
    for f in ('values/styles.xml', 'values/attrs.xml', 'values/skins.xml'):
        p = os.path.join(RES, f)
        if os.path.exists(p):
            t = read(p)
            styles |= set(re.findall(r'<(?:style|attr|declare-styleable) name="([^"]+)"', t))
            styles |= set(re.findall(r'<item name="([^"]+)"', t))
    # style 名里的点会被 R 变成下划线：<style name="Skin.S1"> → R.style.Skin_S1
    for st in list(styles):
        styles.add(st.replace('.', '_'))
    return strings, names, styles, values


def _strip_java_comments(src):
    """按行返回「去掉注释与字符串字面量」的代码（行数不变，报错能对准行号）。"""
    out, i, n = [], 0, len(src)
    in_block, in_line = False, False
    cur = []
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if c == '\n':
            out.append(''.join(cur)); cur = []; in_line = False; i += 1; continue
        if in_block:
            if c == '*' and nxt == '/':
                in_block = False; i += 2; continue
            i += 1; continue
        if in_line:
            i += 1; continue
        if c == '/' and nxt == '*':
            in_block = True; i += 2; continue
        if c == '/' and nxt == '/':
            in_line = True; i += 2; continue
        if c == '"' or c == chr(39):
            quote = c; i += 1
            while i < n and src[i] != quote:
                if src[i] == chr(92):
                    i += 1
                i += 1
            i += 1; cur.append(' "" '); continue
        cur.append(c); i += 1
    out.append(''.join(cur))
    return out


def main():
    texts = {}
    for f in sorted(os.listdir(SRC)):
        if f.endswith('.java'):
            texts[f[:-5]] = read(os.path.join(SRC, f))

    problems = balance_problems(texts)

    strings, names, styles, values = resources()
    for cls, t in texts.items():
        for m in re.finditer(r'(?<![\w.])R\.(string|id|layout|drawable|style|attr|color|raw|array|font|anim)\.(\w+)', t):
            kind, name = m.group(1), m.group(2)
            if kind == 'string':
                ok = name in strings
            elif kind in ('style', 'attr'):
                ok = name in styles or name in names
            elif kind == 'color':
                ok = name in values or name in names
            else:
                ok = name in names or name in values
            if not ok:
                problems.append('%s.java:%d  R.%s.%s 不存在' % (cls, t[:m.start()].count('\n') + 1, kind, name))
    for d in ('layout', 'drawable', 'values', 'values-night'):
        p = os.path.join(RES, d)
        if not os.path.isdir(p):
            continue
        for f in sorted(os.listdir(p)):
            if not f.endswith('.xml'):
                continue
            t = read(os.path.join(p, f))
            for m in re.finditer(r'"@(?:\+)?(string|drawable|color|style|id|attr|layout|font|raw)/([A-Za-z0-9_.]+)"', t):
                kind, name = m.group(1), m.group(2)
                if kind == 'string':
                    ok = name in strings
                elif kind == 'color':
                    ok = name in values or name in names
                else:
                    ok = name in names or name in styles or name in values
                if not ok:
                    problems.append('res/%s/%s  @%s/%s 不存在' % (d, f, kind, name))
            for m in re.finditer(r'\?attr/(\w+)', t):
                if m.group(1) not in styles and m.group(1) != 'wpXxx':   # 注释里的示意名
                    problems.append('res/%s/%s  ?attr/%s 不存在' % (d, f, m.group(1)))

    decls = collections.defaultdict(lambda: collections.defaultdict(set))
    for cls, raw in texts.items():
        t = strip_code(raw)
        for m in re.finditer(r'(?<!\bnew\s)^    (?:(?:public|private|protected|static|final|synchronized|abstract|native|@\w+)\s+)*'
                             r'([A-Za-z_][\w<>\[\], .]*?)\s+(\w+)\s*\(([^;{}()]*?)\)\s*(?:throws [\w,. ]+)?\s*[{;]', t, re.M):
            decls[cls][m.group(2)].add(count_params(m.group(3)))

    for cls, raw in texts.items():
        t = strip_code(raw)
        seen = collections.defaultdict(list)
        for m in re.finditer(r'(?<!\bnew\s)^    (?:(?:public|private|protected|static|final|synchronized|abstract|native|@\w+)\s+)*'
                             r'([A-Za-z_][\w<>\[\], .]*?)\s+(\w+)\s*\(([^;{}()]*?)\)\s*(?:throws [\w,. ]+)?\s*[{;]', t, re.M):
            seen[(m.group(2), param_key(m.group(3)))].append(raw[:m.start()].count('\n') + 1)
        for k, lines in seen.items():
            if len(lines) > 1:
                problems.append('%s.java 重复声明 %s(%s) 行 %s' % (cls, k[0], k[1], lines))
        for m in re.finditer(r'(?<![\w.])([A-Z]\w*)\.(\w+)\s*\(', t):
            c, mem = m.group(1), m.group(2)
            if mem in ('this', 'class', 'super') or c not in decls or mem not in decls[c]:
                continue
            start = m.end() - 1
            depth, i = 0, start
            while i < len(t):
                if t[i] == '(':
                    depth += 1
                elif t[i] == ')':
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            n = count_params(t[start + 1:i])
            if n not in decls[c][mem]:
                problems.append('%s.java:%d  %s.%s(%d 参) 与声明 %s 不符'
                                % (cls, raw[:m.start()].count('\n') + 1, c, mem, n, sorted(decls[c][mem])))

    # 「字段声明了却从没赋值」：漏写 findViewById 就会在运行时 NPE（StudyActivity.tvPos 就是这么挂的）
    for cls, raw in texts.items():
        t = strip_code(raw)
        for m in re.finditer(r'^    (?:private|protected|public)\s+(?:static\s+)?(?!final\b)([A-Za-z_][\w.<>\[\], ]*?)\s+([\w, ]+);$',
                             raw, re.M):
            decl = m.group(0)
            if '=' in decl:
                continue
            for name in [x.strip().split()[-1] for x in m.group(2).split(',') if x.strip()]:   # 跳过 volatile/static 这类漏进来的修饰词
                if not re.search(r'(?<![\w])' + re.escape(name) + r'\s*=[^=]', strip_code(raw)):   # 允许 Xxx.name = … 这种带限定名的赋值
                    problems.append('%s.java:%d  字段 %s 声明了却从未赋值（漏了 findViewById/初始化？）'
                                    % (cls, raw[:m.start()].count('\n') + 1, name))

    # 「findViewById 的 id 不在这个类加载的布局里」：跨布局同名会骗过「id 是否存在」的全局检查
    for cls, raw in texts.items():
        layouts = set(re.findall(r'R\.layout\.(\w+)', raw))
        if not layouts:
            continue
        ok_ids = set()
        for lay in layouts:
            lp = os.path.join(RES, 'layout', lay + '.xml')
            if os.path.exists(lp):
                ok_ids |= set(re.findall(r'@\+?id/(\w+)', read(lp)))
        for m in re.finditer(r'findViewById\(R\.id\.(\w+)\)', raw):
            if m.group(1) not in ok_ids:
                problems.append('%s.java:%d  findViewById(R.id.%s)：不在本文件用到的布局里（%s）'
                                % (cls, raw[:m.start()].count('\n') + 1, m.group(1),
                                   ','.join(sorted(layouts))))

    # 「主机侧单测的源码不能碰 Android/Prefs」（CI 里这 13 个文件被 cp 到 test/src 单编，
    #  一旦它们 import/引用 Prefs 或 Context，javac 直接 cannot find symbol → CI 红）
    PURE = ('Engine', 'QREnc', 'QRUtil', 'Transfer', 'ProgressCode', 'Pack', 'PlanCode',
            'Plan', 'Diary', 'Scale', 'WrongBook', 'ShareGeom', 'Ges')
    for name in PURE:
        body = texts.get(name)
        if not body:
            continue
        for i, line in enumerate(_strip_java_comments(body), 1):
            m = re.search(r'(?<!\w)(Prefs|Context|Activity|Uri|View)\b', line)
            if m and 'import' not in line:
                problems.append('%s.java:%d  主机侧单测源码（run_tests.sh 会单编它）不能引用 %s：%s'
                                % (name, i, m.group(1), line.strip()[:50]))

    # 「手势又写死了」：刷词页必须走 Ges 映射分发（用户 2026-09-14 明确要求「手势由用户自己定」）
    st = texts.get('StudyActivity', '')
    if st:
        if 'fire(Ges.' not in st:
            problems.append('StudyActivity.java 没走 Ges 的手势分发（手势必须可被用户自定义，别写死方向→动作）')
        for m in re.finditer(r'(?:toggleFav\(\)|answer\((?:true|false)\));[^\n]*//\s*(?:上滑|下滑|左滑|右滑)', st):
            problems.append('StudyActivity.java:%d  出现「方向写死」的注释/调用：%s'
                            % (st[:m.start()].count('\n') + 1, m.group(0).strip()[:40]))

    # 「整页收口被塞进每次点击都会跑的路径」：Ui.finishSetup = 整棵树缩字号，放进 refresh/onClick
    # 这类重复路径就会越点越大（2026-09-14 用户报的「字体每次点击都变大一下」）。新增行才用 Fonts.scaleTree。
    REPEAT = re.compile(r'^(refresh|render|bind\w*|update\w*|getView|getItemViewType|onClick|onTap|onItemClick|'
                        r'apply\w*|reload|notify\w*|run|siz\w*Highlight|start\w*|show\w*|fill|updateHud)$')
    # 只认「声明」形状：4 空格缩进 + 修饰符 + 返回类型 + 名字(...) {，避免把 refresh(); 这种调用当方法名
    METHOD_DECL = re.compile(r'^\s{4}(?:@Override\s+)?(?:(?:public|private|protected|static|final|synchronized|abstract)\s+)*'
                             r'[\w<>\[\],.]+\s+(\w+)\s*\([^;{}]*\)\s*(?:throws [\w,. ]+)?\{\s*$')
    for cls, raw in texts.items():
        lines = raw.split('\n')
        for i, l in enumerate(lines):
            if 'Ui.finishSetup' not in l:
                continue
            owner = '?'
            for j in range(i, -1, -1):
                m = METHOD_DECL.match(lines[j])
                if m:
                    owner = m.group(1)
                    break
            if REPEAT.match(owner):
                problems.append('%s.java:%d  Ui.finishSetup 在 %s() 里 —— 这个方法每次点击都会跑，'
                                '整页收口只该在 onCreate 末尾调一次（新增行请用 Fonts.scaleTree）'
                                % (cls, i + 1, owner))

    # 「findViewById 的强制转型和布局里的控件类型对不上」：运行起来才炸的 ClassCastException
    SUPER = {'LinearLayout': {'ViewGroup', 'View'}, 'FrameLayout': {'ViewGroup', 'View'},
             'RelativeLayout': {'ViewGroup', 'View'}, 'ScrollView': {'ViewGroup', 'View'},
             'HorizontalScrollView': {'ViewGroup', 'View'}, 'TextView': {'View'},
             'ImageView': {'View'}, 'ProgressBar': {'View'}, 'Switch': {'View'},
             'View': set(), 'EditText': {'View'}, 'Button': {'View'}, 'Space': {'View'}}
    for cls, raw in texts.items():
        layouts = set(re.findall(r'R\.layout\.(\w+)', raw))
        if not layouts:
            continue
        idtag = {}
        for lay in layouts:
            lp = os.path.join(RES, 'layout', lay + '.xml')
            if not os.path.exists(lp):
                continue
            t = read(lp)
            for m in re.finditer(r'<([\w.]+)[^>]*@\+?id/(\w+)', t):
                tag = m.group(1).split('.')[-1]
                if tag not in ('item',):                      # <item> 之类不是控件
                    idtag.setdefault(m.group(2), set()).add(tag)
        for m in re.finditer(r'\((\w+(?:\.\w+)*)\)\s*findViewById\(R\.id\.(\w+)\)', raw):
            cast, vid = m.group(1).split('.')[-1], m.group(2)
            if cast in ('View', 'Object') or vid not in idtag:
                continue
            ok = cast in idtag[vid] or any(cast in SUPER.get(t, set()) for t in idtag[vid])
            if not ok:
                problems.append('%s.java:%d  (%s) findViewById(R.id.%s) 与布局里的 <%s> 不匹配'
                                % (cls, raw[:m.start()].count('\n') + 1, m.group(1), vid,
                                   '/'.join(sorted(idtag[vid]))))

    # 「匿名类里的 this」：new Xxx() { ... this ... } 里的 this 是匿名类自己，
    # 传给要 Context/Activity/View 的方法就编译不过（CI 抓过一次，规则写回脚本里）
    risky = ('this,', 'this)', 'this.', 'this ')
    for cls, raw in texts.items():
        t = strip_code(raw)
        for m in re.finditer(r'\bnew\s+[\w.<>\[\], ]*?\s*\(\s*[^()]*?\)\s*\{', t):
            depth, i = 1, m.end()
            while i < len(t) and depth > 0:
                if t[i] == '{':
                    depth += 1
                elif t[i] == '}':
                    depth -= 1
                i += 1
            body = t[m.end():i]
            base = m.start()
            for cm in re.finditer(r'(?<![\w.])this(?![\w])', body):
                # 这份代码里唯一合法的匿名类 this：把 Runnable 自己丢回 Handler（postDelayed(this, …)）
                if re.match(r'this\s*,\s*\d', body[cm.start():cm.start() + 14]):
                    continue
                ctx = body[max(0, cm.start() - 40):cm.start() + 8].replace('\n', ' ')
                problems.append('%s.java:%d  匿名类里的 this（%s…）：要写 外层类.this 或 getContext()'
                                % (cls, raw[:base + cm.start()].count('\n') + 1, ctx.strip()))

    # 「String 当数组用」：字符串是 length()，数组才是 .length —— 这类手滑 CI 才炸，先拦下来
    for cls, raw in texts.items():
        t = strip_code(raw)
        strings = set(re.findall(r'\bString\s+(\w+)\s*[=;,\)]', t))
        strings |= set(re.findall(r'\bString\]\s*(\w+)', t))
        for name in sorted(strings):
            for m in re.finditer(r'(?<![\w.])' + re.escape(name) + r'\.length\b(?!\s*\()', t):
                problems.append('%s.java:%d  %s 是 String，应该用 %s.length()'
                                % (cls, raw[:m.start()].count('\n') + 1, name, name))

    man = os.path.join(ROOT, 'AndroidManifest.xml')
    if os.path.exists(man):
        t = read(man)
        registered = set(m.group(1) for m in re.finditer(r'android:name="\.(\w+)"', t))
        for name in sorted(registered):
            if name + '.java' not in [c + '.java' for c in texts]:
                problems.append('AndroidManifest 引用的 %s 类不存在' % name)
        for cls, raw in texts.items():
            if re.search(r'class \w+ extends (Activity|android\.app\.Activity)', raw) and cls not in registered:
                problems.append('%s 是 Activity 但 Manifest 没注册' % cls)

    print('\n'.join(sorted(set(problems))) if problems else 'OK：资源 / 成员 / 参数 / 重复声明 / 括号 / Manifest 全部通过')
    return 1 if problems else 0


def count_params(s):
    if not s.strip():
        return 0
    depth, n = 0, 1
    for ch in s:
        if ch in '(<[{':
            depth += 1
        elif ch in ')>]}':
            depth -= 1
        elif ch == ',' and depth == 0:
            n += 1
    return n


def param_key(s):
    return ','.join(re.sub(r'\s+', ' ', p).strip().rsplit(' ', 1)[0].replace('final ', '')
                    for p in s.split(',') if p.strip())


if __name__ == '__main__':
    sys.exit(main())
