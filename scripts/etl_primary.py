#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「人教版 PEP 小学（三年级起点）词汇」并进 res/raw/wdb.dat —— 追加式合并，不依赖 raw_xlsx。

背景：
- wdb.dat 是 etl.py 从 raw_xlsx 全量生成的（raw_xlsx 只在维护机本地，不入 git）。
- 新增小学词汇时手头没有全量 xlsx，所以用这个脚本**追加**：
  旧 pool 字符串与旧书索引一律不动（新串追加在 pool 尾部 → 旧下标天然有效），
  新书写在文件末尾，写完做一次与 test/PackTest.java 同标准的解析校验。

数据源：data/primary_words.tsv，每行 `grade<TAB>semester<TAB>word<TAB>phonetic<TAB>chinese`
（768 词，3-6 年级共 8 册；phonetic 不含斜杠，App 显示时自己包 /…/）。

用法：python3 scripts/etl_primary.py
幂等：已存在（按 book id 判定）的小册子不会再加第二遍；可放心重跑。
顺序：合并后按 (stage, 原文件顺序) 输出，小学(stage 0) 排在初中/高中/考纲之前，
      与 etl.py 全量重生成后的展示顺序一致。
注意：raw_xlsx 齐全时仍应以 `python3 scripts/etl.py` 全量重生成为准（_keep 已放行小学册）。
"""
import hashlib
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WDB = os.path.join(ROOT, 'res', 'raw', 'wdb.dat')
TSV = os.path.join(ROOT, 'data', 'primary_words.tsv')

GRADE_CN = {3: '三', 4: '四', 5: '五', 6: '六'}
SEM_CN = {1: '上', 2: '下'}


def read_wdb(path):
    """完整解析（与 Pack.read 同格式）。返回 (ver, pool, books)；book 含原始 int 三元组。"""
    f = open(path, 'rb')
    magic = f.read(4)
    if magic != b'WDB1':
        raise SystemExit('不是 WDB1 包：' + repr(magic))

    def rstr():
        n = (f.read(1)[0]) | (f.read(1)[0] << 8)
        return f.read(n).decode('utf-8')

    def rint():
        return struct.unpack('<i', f.read(4))[0]

    ver = rstr()
    pn = rint()
    pool = [rstr() for _ in range(pn)]
    bn = rint()
    books = []
    for _ in range(bn):
        b = {'id': rstr(), 'pub': rstr(), 'title': rstr(), 'series': rstr(),
             'stage': rint(), 'n': rint()}
        rows = []
        for _ in range(b['n']):
            rows.append((rint(), rint(), rint()))   # (wI, pI, mI) 交错三元组
        b['rows'] = rows
        books.append(b)
    f.close()
    return ver, pool, books


def write_wdb(path, ver, pool, books):
    with open(path, 'wb') as f:
        f.write(b'WDB1')
        for s in [ver]:
            bs = s.encode('utf-8')
            f.write(struct.pack('<H', len(bs))); f.write(bs)
        f.write(struct.pack('<i', len(pool)))
        for s in pool:
            bs = s.encode('utf-8')
            f.write(struct.pack('<H', len(bs))); f.write(bs)
        f.write(struct.pack('<i', len(books)))
        for b in books:
            for s in (b['id'], b['pub'], b['title'], b['series']):
                bs = s.encode('utf-8')
                f.write(struct.pack('<H', len(bs))); f.write(bs)
            f.write(struct.pack('<i', b['stage']))
            f.write(struct.pack('<i', len(b['rows'])))
            for wi, pi, mi in b['rows']:
                f.write(struct.pack('<i', wi)); f.write(struct.pack('<i', pi)); f.write(struct.pack('<i', mi))


def verify(pool, books):
    """与 test/PackTest.java 同标准的断言，写完立即自检。"""
    ids = set()
    total = 0
    for b in books:
        if b['id'] in ids:
            raise SystemExit('重复 book id: ' + b['id'])
        ids.add(b['id'])
        if not (0 < b['n'] <= 1000000):
            raise SystemExit('坏 n: %s' % b['title'])
        for wi, pi, mi in b['rows']:
            if not (0 <= wi < len(pool) and 0 <= pi < len(pool) and 0 <= mi < len(pool)):
                raise SystemExit('索引越界: %s' % b['title'])
            w, p, m = pool[wi], pool[pi], pool[mi]
            if not w or not m:
                raise SystemExit('空词/空释义: %s -> %r' % (b['title'], w))
            if len(p) > 40:
                raise SystemExit('音标过长: %s -> %r' % (b['title'], p))
            if not any('a' <= c <= 'z' or 'A' <= c <= 'Z' for c in w):
                raise SystemExit('非法单词: %s -> %r' % (b['title'], w))
            total += 1
    return total


def build_primary_books(pool):
    """data/primary_words.tsv -> 8 本小学词书（book id 用固定派生值，重跑不漂移）。"""
    books = {}
    order = []
    with open(TSV, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line.strip():
                continue
            g, s, w, p, m = (x.strip() for x in line.split('\t'))
            g, s = int(g), int(s)
            title = '%s年级%s册' % (GRADE_CN[g], SEM_CN[s])
            if title not in books:
                # 派生 id：与 etl.py 的 md5(rel)[:10] 同为 10 位十六进制，保证稳定
                bid = hashlib.md5(('pep-primary/%s' % title).encode('utf-8')).hexdigest()[:10]
                books[title] = {'id': bid, 'pub': '人教版 PEP', 'title': title,
                                'series': '三年级起点', 'stage': 0, 'rows': []}
                order.append(title)
            books[title]['rows'].append((w, p, m))
    out = []
    for t in order:
        b = books[t]
        # 书内去重（同词不同释义/音标时保留第一次出现，与 etl.py 行为一致）
        seen = set()
        rows = []
        for w, p, m in b['rows']:
            if w.lower() in seen:
                continue
            seen.add(w.lower())
            rows.append((w, p, m))
        b['rows'] = rows
        b['n'] = len(rows)
        out.append(b)
    return out


def main():
    ver, pool, books = read_wdb(WDB)
    existing = {b['id'] for b in books}

    new = [b for b in build_primary_books(pool) if b['id'] not in existing]
    if not new:
        print('小学词书已全部存在（%d 本），无需合并。' % sum(1 for b in books if b['stage'] == 0))
        return

    # 新串追加到 pool 尾部 —— 旧书索引不受影响
    idx = {s: i for i, s in enumerate(pool)}
    for b in new:
        for w, p, m in b['rows']:
            for s in (w, p, m):
                if s not in idx:
                    idx[s] = len(pool)
                    pool.append(s)
    for b in new:
        for i, (w, p, m) in enumerate(b['rows']):
            b['rows'][i] = (idx[w], idx[p], idx[m])

    # 追加新书，再按 (stage, 原顺序) 输出：小学在前，其余相对顺序不变
    for b in new:
        books.append(b)
    books = [b for _, b in sorted(enumerate(books), key=lambda t: (t[1]['stage'], t[0]))]

    n_words = verify(pool, books)
    write_wdb(WDB, ver, pool, books)

    # 落盘后再解析一遍（模拟 App 冷启动）
    ver2, pool2, books2 = read_wdb(WDB)
    assert len(pool2) == len(pool) and len(books2) == len(books)
    assert n_words == verify(pool2, books2)

    print('books=%d words=%d pool=%d size=%.1fKB' % (len(books), n_words, len(pool),
                                                      os.path.getsize(WDB) / 1024))
    for b in books:
        if b['stage'] == 0:
            print('  +stage0 %s %s n=%d' % (b['pub'], b['title'] + ' ' + b['series'], b['n']))
    print('OK')


if __name__ == '__main__':
    sys.exit(main())
