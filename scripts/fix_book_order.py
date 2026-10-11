#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""维护脚本：直接重排已打包的 res/raw/wdb.dat（不需要 raw_xlsx 源数据）。

用途：etl.py 的 sort_key() 曾把「选择性必修第二册」误判成与「必修第二册」同分
（册次匹配循环没有 break，被后面的 '必修第二' 覆盖），导致高中书目必修/选必交替排列。
本脚本按 etl.py 修好后的规则重排：同一学段内 **先必修(1x) 再选择性必修(2x)**，
其余书目、字符串池、词条索引全部原样保留（pool 段落逐字节不变，文件尺寸不变）。

进度按 bookId（md5(rel)）存取，与书目顺序无关 → 重排不会丢任何已掌握记录。

    python3 scripts/fix_book_order.py          # 就地重写（自动备份到 /tmp）
    python3 scripts/fix_book_order.py --check   # 只看当前顺序，不写文件
"""
import io
import os
import re
import shutil
import struct
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WDB = os.path.join(ROOT, 'res', 'raw', 'wdb.dat')

STAGE_SENIOR = 2


# ---------- 读 ----------
def read_pack(buf):
    f = io.BytesIO(buf)

    def ri():
        v = struct.unpack('<i', f.read(4))[0]
        return v

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


# ---------- 排序 ----------
def volume(title):
    """册次权重：必修 1x，选择性必修 2x（与 etl.py 修好后的 sort_key 一致）。"""
    for pat, v in (('选择性必修第一', 21), ('选择性必修第二', 22), ('选择性必修第三', 23), ('选择性必修第四', 24),
                   ('必修第一', 11), ('必修第二', 12), ('必修第三', 13), ('必修第四', 14)):
        if pat in title:
            return v
    m = re.search(r'高中英语(\d+)', title)
    return int(m.group(1)) if m else 0


def reorder(books):
    """稳定重排：只在高中学段内部按册次权重排，其它学段/出版商的原顺序保持不变。"""
    out = []
    senior = [b for b in books if b['stage'] == STAGE_SENIOR]
    senior.sort(key=lambda b: (volume(b['title']), b['title']))
    it = iter(senior)
    for b in books:                       # 高中册次占用原来的高中槽位，其余原地不动
        out.append(next(it) if b['stage'] == STAGE_SENIOR else b)
    return out


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


def digest(books):
    """与顺序无关的内容指纹：用于断言重排前后一字未改。"""
    return sorted((b['id'], b['pub'], b['title'], b['series'], b['stage'], tuple(b['words'])) for b in books)


def show(books, tag):
    print(f'-- {tag}')
    for i, b in enumerate(books):
        print(f'  {i:2d} | stage={b["stage"]} | {b["pub"]} | {b["title"]} | n={b["n"]}')


def main():
    check_only = '--check' in sys.argv
    old = open(WDB, 'rb').read()
    ver, pool, books = read_pack(old)
    new_books = reorder(books)

    if [b['title'] for b in books] == [b['title'] for b in new_books]:
        show(books, '当前顺序（已符合「先必修再选修」，无需改动）')
        return 0

    show(books, '修改前')
    show(new_books, '修改后')
    if check_only:
        print('（--check：未写文件）')
        return 0

    packed = write_pack(ver, pool, new_books)

    # 自检：重新解析 + 内容指纹一致 + 尺寸一致
    ver2, pool2, books2 = read_pack(packed)
    assert ver2 == ver and pool2 == pool, '字符串池被改动'
    assert digest(books2) == digest(books), '书目内容被改动'
    assert len(packed) == len(old), f'尺寸变化 {len(old)} -> {len(packed)}'

    bak = os.path.join(tempfile.gettempdir(), 'wdb.dat.bak')
    shutil.copyfile(WDB, bak)
    open(WDB, 'wb').write(packed)
    print(f'✅ 已写入 {os.path.relpath(WDB, ROOT)}（{len(packed)} 字节，尺寸不变；备份 {bak}）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
