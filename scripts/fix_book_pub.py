#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""维护脚本：修正 res/raw/wdb.dat 里初中/高中词书的出版社标签（体检 P2-15）。

背景：词库里初中学段 5 本、高中学段 7 本的 pub 都写「人教版 PEP」，
列表行右上角的小标签跟着显示「人教版 PEP」——PEP 是 People's Education Press
（人民教育出版社）的英文缩写，标签上那串英文对用户没有意义，应该就叫「人教版」。
小学的 8 本是「人教版（PEP）· 三年级起点」体系，保留「人教版 PEP」以区分一年级起点版本。

改什么：
  · stage=1（初中）和 stage=2（高中）的书：pub「人教版 PEP」→「人教版」
不改什么：
  · bookId 一个不动（进度按 bookId 存，改了会丢已掌握记录）
  · 词条（words 三元组）逐字节一致（改前改后有指纹断言）
  · 小学/词表/大学学段不动

用法：
    python3 scripts/fix_book_pub.py          # 就地重写（自动备份到 /tmp）
    python3 scripts/fix_book_pub.py --check   # 只看差异，不写文件
"""
import io
import os
import shutil
import struct
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WDB = os.path.join(ROOT, 'res', 'raw', 'wdb.dat')

OLD_PUB = '人教版 PEP'
NEW_PUB = '人教版'
STAGES = {1, 2}   # 初中 / 高中


# ---------- 读（与 fix_book_order.py / Pack.java 同一套 WDB1 格式） ----------
def read_pack(buf):
    f = io.BytesIO(buf)

    def ri():
        return struct.unpack('<i', f.read(4))[0]

    def rs():
        n = struct.unpack('<H', f.read(2))[0]
        return f.read(n).decode('utf-8')

    if f.read(4) != b'WDB1':
        raise SystemExit('!! 不是 WDB1 文件：' + WDB)
    ver = rs()
    pool = [rs() for _ in range(ri())]
    books = []
    for _ in range(ri()):
        b = {'id': rs(), 'pub': rs(), 'title': rs(), 'series': rs(), 'stage': ri(), 'n': ri()}
        b['words'] = [(ri(), ri(), ri()) for _ in range(b['n'])]
        books.append(b)
    if f.read():
        raise SystemExit('!! 文件尾部有多余字节')
    return ver, pool, books


# ---------- 写 ----------
def write_pack(ver, pool, books):
    data = io.BytesIO()

    def put_int(v):
        data.write(struct.pack('<i', v))

    def put_str(s):
        bs = s.encode('utf-8')
        data.write(struct.pack('<H', len(bs)))
        data.write(bs)

    put_str(ver)
    put_int(len(pool))
    for s in pool:
        put_str(s)
    put_int(len(books))
    for b in books:
        put_str(b['id']); put_str(b['pub']); put_str(b['title']); put_str(b['series'])
        put_int(b['stage']); put_int(len(b['words']))
        for (w, p, m) in b['words']:
            put_int(w); put_int(p); put_int(m)
    return b'WDB1' + data.getvalue()


def words_digest(books):
    """词条指纹（书序无关）：pub 改前改后必须一模一样。"""
    return sorted((b['id'], b['stage'], b['title'], b['series'], tuple(b['words'])) for b in books)


def main():
    check_only = '--check' in sys.argv
    old = open(WDB, 'rb').read()
    ver, pool, books = read_pack(old)

    changed = []
    for b in books:
        if b['stage'] in STAGES and b['pub'] == OLD_PUB:
            changed.append(b['title'])
            b['pub'] = NEW_PUB

    if not changed:
        print('当前数据已符合（初中/高中 = 「人教版」），无需改动')
        return 0

    print('待改 %d 本（stage=1/2，pub %r → %r）：' % (len(changed), OLD_PUB, NEW_PUB))
    for t in changed:
        print('  ·', t)
    if check_only:
        print('（--check：未写文件）')
        return 0

    packed = write_pack(ver, pool, books)

    # 自检：重新解析，词条/池子/书目 id 一字不差，只有 pub 变了
    ver2, pool2, books2 = read_pack(packed)
    assert ver2 == ver and pool2 == pool, '字符串池被改动'
    assert words_digest(books2) == words_digest(read_pack(old)[2]), '书目内容（除 pub）被改动'
    for b_old, b_new in zip(read_pack(old)[2], books2):
        assert b_old['id'] == b_new['id'], 'bookId 被改动（进度会丢）'
        if b_new['stage'] in STAGES:
            assert b_new['pub'] == NEW_PUB, '目标学段没改到'
        else:
            assert b_new['pub'] == b_old['pub'], '非目标学段的 pub 被误改'

    bak = os.path.join(tempfile.gettempdir(), 'wdb.dat.pub.bak')
    shutil.copyfile(WDB, bak)
    open(WDB, 'wb').write(packed)
    print('✅ 已写入 %s（%d → %d 字节；备份 %s）'
          % (os.path.relpath(WDB, ROOT), len(old), len(packed), bak))
    return 0


if __name__ == '__main__':
    sys.exit(main())
