#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 PDF「3500词汇高频使用词汇（E-Z）」整理成独立词书并并入 wdb.dat。

词条源：data/high_frequency_ez.tsv，每行 `word<TAB>phonetic<TAB>pos<TAB>chinese`。
TSV 是可维护的正式源数据；ETL 不依赖维护机上的 PDF/PyMuPDF。
词性（第三列）由 ETL 拼到释义前面（`n. 生态`），与词库里其他词书的呈现方式一致
（用户 2026-10-11：「把 E-Z 的词性加上」）。

用法：
  python3 scripts/etl_high_frequency_ez.py          # 追加词书 / 词条有更新时原地同步
  python3 scripts/etl_high_frequency_ez.py --check  # 只校验 TSV 与已打包词书

此词书放在 stage 3（高考 3500 学段），紧跟已有 stage 3 词表、大学词书之前。
新增独立 book id，不改其他词书的 id、顺序或词条索引。
"""
import hashlib
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WDB = os.path.join(ROOT, 'res', 'raw', 'wdb.dat')
TSV = os.path.join(ROOT, 'data', 'high_frequency_ez.tsv')

# 两个 A 开头的固定搭配来自 E-Z PDF 的词条/短语栏，不是补入 A-D 词表。
SOURCE_PHRASE_EXCEPTIONS = {'a large quantity of', 'at random'}
EXPECTED_WORDS = 628
BOOK_ID = hashlib.md5(b'wordsprint/high-frequency-ez').hexdigest()[:10]
PUB = '高频词汇'
TITLE = '3500词汇高频使用词汇（E–Z）'
SERIES = ''
STAGE = 3
WORD_RE = re.compile(r"^[A-Za-z][A-Za-z'’\-]*(?:\s+[A-Za-z][A-Za-z'’\-]*)*$")
# 词性标注（第三列）：单词性 / 多词性用 / 连接；固定搭配没有单一词性时用 phr.
POS_TAGS = ('n.', 'v.', 'adj.', 'adv.', 'prep.', 'conj.', 'pron.', 'art.', 'num.', 'phr.')


def has_pos(meaning):
    """释义是否以词性标注开头（`n. 生态` / `v./n. 缓解`）。"""
    head, sep, rest = meaning.partition(' ')
    return bool(sep) and bool(rest.strip()) and all(tag in POS_TAGS for tag in head.split('/'))


def read_tsv(path=TSV):
    rows = []
    seen = set()
    with open(path, encoding='utf-8') as f:
        for lineno, line in enumerate(f, 1):
            line = line.rstrip('\r\n')
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            cells = [cell.strip() for cell in line.split('\t')]
            if len(cells) != 4:
                raise SystemExit('%s:%d：应为 word<TAB>phonetic<TAB>pos<TAB>chinese' % (path, lineno))
            word, phonetic, pos, chinese = cells
            for tag in pos.split('/'):
                if tag not in POS_TAGS:
                    raise SystemExit('%s:%d：词性只认 %s（用 / 连接多词性），得到 %r'
                                     % (path, lineno, ' '.join(POS_TAGS), pos))
            key = word.lower()
            if key in seen:
                raise SystemExit('%s:%d：重复词条 %r' % (path, lineno, word))
            seen.add(key)
            if not WORD_RE.fullmatch(word):
                raise SystemExit('%s:%d：单词/短语格式不正确：%r' % (path, lineno, word))
            if not chinese:
                raise SystemExit('%s:%d：缺少中文释义：%r' % (path, lineno, word))
            if len(phonetic) > 40:
                raise SystemExit('%s:%d：音标过长：%r' % (path, lineno, word))
            if not pos:
                raise SystemExit('%s:%d：缺少词性：%r' % (path, lineno, word))
            # 主词头按 E-Z 收录。少量 A 开头条目只是 PDF 中出现的固定搭配。
            if word[0].lower() in 'abcd' and key not in SOURCE_PHRASE_EXCEPTIONS:
                raise SystemExit('%s:%d：发现不属于 E-Z 的词头：%r' % (path, lineno, word))
            # 词性拼进释义 —— wdb.dat 每条只有「词 / 音标 / 释义」三个字段，
            # 与词库里其他词书（如高考 3500「n. 能力，能耐; 才能」）保持同一种形态。
            rows.append((word, phonetic, pos + ' ' + chinese))
    if len(rows) != EXPECTED_WORDS:
        raise SystemExit('词条数异常：预期 %d，实际 %d；核对 TSV / 更新测试常量' %
                         (EXPECTED_WORDS, len(rows)))
    return rows


def make_book():
    return {'id': BOOK_ID, 'pub': PUB, 'title': TITLE, 'series': SERIES,
            'stage': STAGE, 'rows': read_tsv()}


def _read_exact(f, n):
    data = f.read(n)
    if len(data) != n:
        raise SystemExit('wdb.dat 截断：需要 %d 字节、实际 %d 字节' % (n, len(data)))
    return data


def read_wdb(path=WDB):
    with open(path, 'rb') as f:
        if _read_exact(f, 4) != b'WDB1':
            raise SystemExit('不是 WDB1 词库包')

        def rstr():
            n = struct.unpack('<H', _read_exact(f, 2))[0]
            return _read_exact(f, n).decode('utf-8')

        def rint():
            return struct.unpack('<i', _read_exact(f, 4))[0]

        ver = rstr()
        pool = [rstr() for _ in range(rint())]
        books = []
        for _ in range(rint()):
            b = {'id': rstr(), 'pub': rstr(), 'title': rstr(), 'series': rstr(),
                 'stage': rint()}
            n = rint()
            b['rows'] = [(rint(), rint(), rint()) for __ in range(n)]
            books.append(b)
        if f.read(1):
            raise SystemExit('wdb.dat 尾部有无法解析的多余数据')
    return ver, pool, books


def write_wdb(path, ver, pool, books):
    def wstr(f, s):
        data = s.encode('utf-8')
        if len(data) > 60000:
            raise SystemExit('字符串过长（>60000 字节）：%r' % s[:40])
        f.write(struct.pack('<H', len(data)))
        f.write(data)

    with open(path, 'wb') as f:
        f.write(b'WDB1')
        wstr(f, ver)
        f.write(struct.pack('<i', len(pool)))
        for s in pool:
            wstr(f, s)
        f.write(struct.pack('<i', len(books)))
        for b in books:
            for s in (b['id'], b['pub'], b['title'], b['series']):
                wstr(f, s)
            f.write(struct.pack('<i', b['stage']))
            f.write(struct.pack('<i', len(b['rows'])))
            for wi, pi, mi in b['rows']:
                f.write(struct.pack('<iii', wi, pi, mi))


def verify(pool, books):
    ids = set()
    total = 0
    for b in books:
        if b['id'] in ids:
            raise SystemExit('重复 book id：' + b['id'])
        ids.add(b['id'])
        if not b['rows']:
            raise SystemExit('空词书：' + b['title'])
        words = set()
        for wi, pi, mi in b['rows']:
            if not (0 <= wi < len(pool) and 0 <= pi < len(pool) and 0 <= mi < len(pool)):
                raise SystemExit('字符串池索引越界：' + b['title'])
            word, phonetic, meaning = pool[wi], pool[pi], pool[mi]
            if not word or not meaning:
                raise SystemExit('空词或空释义：%s -> %r' % (b['title'], word))
            # 老词库可能包含带省略号的句型（如 “have ... class”）；旧书只做字母存在性校验。
            if not re.search(r'[A-Za-z]', word):
                raise SystemExit('非法词条：%s -> %r' % (b['title'], word))
            if len(phonetic) > 40:
                raise SystemExit('音标过长：%s -> %r' % (b['title'], word))
            key = word.lower()
            if key in words:
                raise SystemExit('书内重复词条：%s -> %r' % (b['title'], word))
            words.add(key)
            total += 1
    return total


def _book_rows_as_text(b, pool):
    return [(pool[wi], pool[pi], pool[mi]) for wi, pi, mi in b['rows']]


def check_pack():
    expected = make_book()
    ver, pool, books = read_wdb()
    matches = [b for b in books if b['id'] == BOOK_ID]
    if len(matches) != 1:
        raise SystemExit('wdb.dat 中应有且仅有 1 本 E-Z 词书，实际 %d 本' % len(matches))
    actual = matches[0]
    if (actual['pub'], actual['title'], actual['series'], actual['stage']) != \
            (expected['pub'], expected['title'], expected['series'], expected['stage']):
        raise SystemExit('E-Z 词书的书名/出版社/学段元数据与 ETL 源不一致')
    if _book_rows_as_text(actual, pool) != expected['rows']:
        raise SystemExit('wdb.dat 中的 E-Z 词条与 data/high_frequency_ez.tsv 不一致')
    for word, _ph, mean in _book_rows_as_text(actual, pool):
        if not has_pos(mean):
            raise SystemExit('E-Z 词条缺少词性标注：%s -> %r' % (word, mean))
    total = verify(pool, books)
    ez_count = len(actual['rows'])
    # 四级/六级书(stage 5)排在 E-Z 之后；stage3 顺序与全量 ETL 一致。
    ez_index = books.index(actual)
    if any(b['stage'] == 5 for b in books[:ez_index]):
        raise SystemExit('E-Z 词书应排在大学词书之前')
    print('E-Z 词书校验通过：%d 词；全库 %d 本 / %d 词（包 %s）' %
          (ez_count, len(books), total, ver))
    return 0


def _sync_existing(actual, pool, books, ver, new):
    """词书已在包里：内容一致 = 无事；只有释义变了（如本轮补词性）= 原地同步。

    词条数、词条顺序、音标全不变，只把每条指向的释义串换掉 —— 用户进度是按
    书内下标存的（ProgressCode / mastered 位图），所以原地同步不会错位。
    词条增删 / 顺序变化一律拒绝，请走 scripts/etl.py 全量重建（那种情况进度本来也要重来）。
    """
    if (actual['pub'], actual['title'], actual['series'], actual['stage']) != \
            (new['pub'], new['title'], new['series'], new['stage']):
        raise SystemExit('wdb.dat 里的 E-Z 词书元数据与 ETL 源不一致，未自动覆盖。')
    before = _book_rows_as_text(actual, pool)
    if before == new['rows']:
        print('E-Z 词书已存在且与源 TSV 一致（%d 词），无需重复添加。' % len(new['rows']))
        return 0
    if [r[:2] for r in before] != [r[:2] for r in new['rows']]:
        raise SystemExit('词条/音标（或顺序）与 TSV 不一致：原地同步只支持「释义变化」；'
                         '词条增删请走 scripts/etl.py 全量重建。')

    indices = {s: i for i, s in enumerate(pool)}
    changed = 0
    for j, (wi, pi, mi) in enumerate(actual['rows']):
        word, phonetic, meaning = new['rows'][j]
        if pool[mi] == meaning:
            continue
        if meaning not in indices:                 # 只增不改：老串留在池里，其他词书索引不受影响
            indices[meaning] = len(pool)
            pool.append(meaning)
        actual['rows'][j] = (wi, pi, indices[meaning])
        changed += 1
    if changed == 0:
        print('E-Z 词书无需更新。')
        return 0
    verify(pool, books)
    write_wdb(WDB, ver, pool, books)
    # 落盘重读，模拟 App 冷启动
    ver2, pool2, books2 = read_wdb()
    assert ver2 == ver and len(pool2) == len(pool) and len(books2) == len(books)
    check_pack()
    print('已原地同步 E-Z 词书：%d/%d 条释义更新（词条数、顺序、音标不变）。'
          % (changed, len(new['rows'])))
    return 0

def append_to_pack():
    new = make_book()
    ver, pool, books = read_wdb()
    existing = [b for b in books if b['id'] == BOOK_ID]
    if existing:
        if len(existing) != 1:
            raise SystemExit('wdb.dat 里出现了 %d 本同 ID 的 E-Z 词书' % len(existing))
        return _sync_existing(existing[0], pool, books, ver, new)

    indices = {s: i for i, s in enumerate(pool)}
    indexed = []
    for word, phonetic, meaning in new['rows']:
        ix = []
        for s in (word, phonetic, meaning):
            if s not in indices:
                indices[s] = len(pool)
                pool.append(s)
            ix.append(indices[s])
        indexed.append(tuple(ix))
    new['rows'] = indexed

    # stage 0-3 在前、大学 stage 5 在后；新书放在大学书之前。
    insert_at = next((i for i, b in enumerate(books) if b['stage'] > STAGE), len(books))
    books.insert(insert_at, new)
    total = verify(pool, books)
    write_wdb(WDB, ver, pool, books)

    # 落盘重读，模拟 App 冷启动；确认新词书和所有旧索引都仍能解析。
    ver2, pool2, books2 = read_wdb()
    assert ver2 == ver and len(pool2) == len(pool) and len(books2) == len(books)
    assert total == verify(pool2, books2)
    check_pack()
    print('已追加：%s / %d 词。' % (TITLE, len(new['rows'])))
    return 0


def main():
    if '--check' in sys.argv:
        return check_pack()
    return append_to_pack()


if __name__ == '__main__':
    sys.exit(main())
