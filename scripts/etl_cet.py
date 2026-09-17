#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""大学四六级词库 ETL：原始词表 JSON → data/cet_words.tsv.gz → 并入 res/raw/wdb.dat。

词表源头（均为公开 JSON，带音标 + 中文释义）：
  · 四级真题核心词（正序版） 1162 词 —— 有道考神团队，按真题词频筛出的必背核心词
  · 四级英语词汇             3739 词 —— 有道词典，四级大纲 + 中学基础 + 真题高频
  · 六级英语词汇             2078 词 —— 有道词典，四级以上的六级大纲 + 真题高频
两份原始仓库都按 Apache-2.0 分发（mikigo/english-chinese-words 聚合了有道公开词表）。
本仓库只保留**提取后的精简三列数据**（word/phonetic/chinese），不含例句等版权内容。

两种用法：
  python3 scripts/etl_cet.py --fetch      # 取原始数据（本地 raw_cet/ 或联网/gh api）→ 重写 data/cet_words.tsv.gz
  python3 scripts/etl_cet.py              # 把 cet_words.tsv.gz 里的三本书**追加**进 res/raw/wdb.dat（幂等）

追加是「只加不改」：旧字符串池与旧书索引逐字节保留（新串追加在池尾），
所以初中/高中/考纲的词书进度、bookId 全不受影响。
（raw_xlsx 齐全时走 `scripts/etl.py` 全量重生成，那边会 import 本模块的 cet_books() 一并写入，
  两条路径产出的三本 CET 词书完全一致，且都排在词书库最后。）
"""
import gzip
import hashlib
import io
import json
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WDB = os.path.join(ROOT, 'res', 'raw', 'wdb.dat')
TSV = os.path.join(ROOT, 'data', 'cet_words.tsv.gz')
RAW_DIR = os.path.join(ROOT, 'raw_cet')

# 大学学段：Db.STAGE_COLLEGE（Java 侧同步改成 5；旧包里的 4 = 拓展，别复用）
STAGE_COLLEGE = 5

# (key, 显示标题, series, 原始仓库路径, raw_cet 下的文件名)
LISTS = [
    ('c4core', '四级真题核心词', '', 'books/四级/四级真题核心词（正序版）.json'),
    ('c4', '四级英语词汇', '', 'books/四级/四级英语词汇.json'),
    ('c6', '六级英语词汇', '', 'books/六级/六级英语词汇.json'),
]
REPO = 'mikigo/english-chinese-words'

# ---------- 清洗 ----------

def clean_word(w):
    if w is None:
        return None
    w = str(w).strip().strip('"').replace('\u00a0', ' ')
    w = re.sub(r'[\t\r\n]+', ' ', w).strip()
    if not re.search(r'[A-Za-z]', w):
        return None
    if len(w) > 46 or ' ' in w:
        return None
    return w


def clean_ph(s):
    """音标：只留第一种读法，重音符号归一（' → ˈ、, → ˌ），去掉零宽/斜杠/方括号。

    与课本词书同一套写法：不带斜杠，App 侧显示时自己包 /…/。
    """
    if s is None:
        return ''
    s = str(s).strip().strip('[]/').strip()
    s = (s.replace('\u200b', '').replace('\xa0', ' ')
          .replace('\u2018', "'").replace('\u2019', "'"))
    s = re.split(r'[;；]', s)[0]                       # 多变体：留第一种
    s = re.split(r',\s*-', s)[0]                      # 「əb'sɔ:b, -'zɔ:b」这种替代读法
    # 词首/字母后的 ' 是主重音，, 是次重音（有道原始音标写法）
    s = re.sub(r"(^|[A-Za-zɑæəɜɪʊʌɔɛŋθðʃʒɡ])'", r'\1ˈ', s)
    s = re.sub(r"(^|[A-Za-zɑæəɜɪʊʌɔɛŋθðʃʒɡ]),", r'\1ˌ', s)
    s = s.replace("'", 'ˈ').replace(',', 'ˌ')           # 兜底
    s = re.sub(r'[\[\]（）()&]', '', s)
    s = re.sub(r'\s+', ' ', s).strip().strip('-').strip()
    if not re.search(r'[A-Za-zɑæəɜɪʊʌɔɛŋθðʃʒɡ]', s) or len(s) > 40:
        return ''
    return s


BASE_POS = {'n', 'v', 'vt', 'vi', 'adj', 'adv', 'prep', 'conj', 'pron', 'art', 'num',
            'int', 'aux', 'abbr', 'u', 'c', 'pl'}


def clean_mean(trans):
    """trans 段 → "n. 好处; 善行"（与课本词书同风格；最多 3 段 / 64 字）。"""
    segs, total = [], 0
    for t in trans or []:
        if not isinstance(t, dict):
            continue
        pos = (t.get('pos') or '').strip().rstrip('.')
        cn = (t.get('tranCn') or '').strip()
        if not cn:
            continue
        cn = re.sub(r'\s+', ' ', cn)
        cn = re.sub(r'^(\.|;|；|,|，|、)+', '', cn).strip()
        if not cn:
            continue
        seg = (pos + '. ' + cn) if pos and pos.lower() in BASE_POS else cn
        if seg in segs:
            continue
        segs.append(seg)
        total += len(seg)
        if total >= 64 or len(segs) >= 3:
            break
    m = '; '.join(segs)
    if not m or m in ('无', '-', '—', 'nan', 'None', 'undefined') or re.fullmatch(r'[\W_]+', m):
        return ''
    return m[:80]


def parse_raw(text):
    """行式 JSON（每行一条词）→ [(word, ph, mean)]，按首次出现去重。"""
    out, seen = [], set()
    for line in text.split('\n'):
        line = line.strip()
        if not line:
            continue
        try:
            e = json.loads(line)
        except ValueError:
            continue
        w = clean_word(e.get('headWord') or e.get('word'))
        if not w:
            continue
        try:
            c = e['content']['word']['content']
        except Exception:
            continue
        ph = clean_ph(c.get('usphone') or c.get('phone') or c.get('ukphone'))
        mean = clean_mean(c.get('trans'))
        if not mean:
            continue
        k = w.lower()
        if k in seen:
            continue
        seen.add(k)
        out.append((w, ph, mean))
    return out


# ---------- 取原始数据 ----------

def _raw_path(name):
    return os.path.join(RAW_DIR, name)


def fetch_source(rel, dest):
    """先看本地 raw_cet/，再 gh api（沙箱里唯一通的通道），最后 raw.githubusercontent。"""
    base = os.path.basename(rel)
    local = _raw_path(base)
    if os.path.exists(local):
        return open(local, encoding='utf-8').read()
    os.makedirs(RAW_DIR, exist_ok=True)
    import subprocess
    try:
        r = subprocess.run(['gh', 'api', '-H', 'Accept: application/vnd.github.raw',
                            '/repos/%s/contents/%s' % (REPO, rel)],
                           capture_output=True, timeout=600)
        if r.returncode == 0 and r.stdout:
            open(local, 'wb').write(r.stdout)
            return r.stdout.decode('utf-8')
    except Exception:
        pass
    import urllib.request
    url = 'https://raw.githubusercontent.com/%s/main/%s' % (REPO, rel)
    with urllib.request.urlopen(url, timeout=600) as f:
        data = f.read()
    open(local, 'wb').write(data)
    return data.decode('utf-8')


def build_tsv():
    """三份原始词表 → data/cet_words.tsv.gz（列：list<TAB>word<TAB>phonetic<TAB>chinese）。"""
    lines = []
    for key, title, series, rel in LISTS:
        rows = parse_raw(fetch_source(rel, None))
        if not rows:
            raise SystemExit('!! %s 解析出 0 条：%s' % (title, rel))
        for w, p, m in rows:
            if '\t' in w or '\t' in m:
                continue
            lines.append('%s\t%s\t%s\t%s' % (key, w, p, m))
        print('  %-16s %5d 词' % (title, len(rows)))
    blob = ('\n'.join(lines) + '\n').encode('utf-8')
    with gzip.GzipFile(TSV, 'wb', mtime=0) as f:      # mtime=0 → 同数据同字节，diff 干净
        f.write(blob)
    print('写出 %s（%d 行，%.1f KB，原始 %.1f KB）'
          % (os.path.relpath(TSV, ROOT), len(lines), os.path.getsize(TSV) / 1024, len(blob) / 1024))


# ---------- 读 TSV → 词书 ----------

def read_tsv(path=TSV):
    """→ {key: [(word, ph, mean), ...]}（保序、按 list 内去重）。"""
    out = {}
    with gzip.open(path, 'rt', encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line:
                continue
            parts = line.split('\t')
            if len(parts) != 4:
                raise SystemExit('!! cet_words.tsv 列数不对: %r' % line)
            key, w, p, m = parts
            out.setdefault(key, []).append((w, p, m))
    return out


def cet_books(path=TSV):
    """→ [book dict]（与 etl.py 的 book 结构一致：id/pub/title/series/stage/rows）"""
    if not os.path.exists(path):
        return []
    data = read_tsv(path)
    books = []
    for key, title, series, _rel in LISTS:
        rows = data.get(key)
        if not rows:
            print('   （cet_words.tsv 里没有 %s，跳过）' % key)
            continue
        bid = hashlib.md5(('cet/%s' % key).encode('utf-8')).hexdigest()[:10]
        books.append({'id': bid, 'pub': '大学英语', 'title': title, 'series': series,
                      'stage': STAGE_COLLEGE, 'rows': rows, 'n': len(rows)})
    return books


def verify_books(books):
    for b in books:
        if not (0 < b['n'] <= 1000000):
            raise SystemExit('坏 n: %s' % b['title'])
        for w, p, m in b['rows']:
            if not w or not m:
                raise SystemExit('空词/空释义: %s -> %r' % (b['title'], w))
            if len(p) > 40:
                raise SystemExit('音标过长: %s -> %r' % (b['title'], p))
            if not re.search(r'[A-Za-z]', w):
                raise SystemExit('非法单词: %s -> %r' % (b['title'], w))


# ---------- 追加进 wdb.dat ----------

def read_wdb(path):
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
    pool = [rstr() for _ in range(rint())]
    books = []
    for _ in range(rint()):
        b = {'id': rstr(), 'pub': rstr(), 'title': rstr(), 'series': rstr(),
             'stage': rint(), 'n': rint()}
        b['rows'] = [(rint(), rint(), rint()) for _ in range(b['n'])]
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
            if len(bs) > 60000:
                raise SystemExit('字符串过长（>60000B）：%r' % s[:40])
            f.write(struct.pack('<H', len(bs))); f.write(bs)
        f.write(struct.pack('<i', len(books)))
        for b in books:
            for s in (b['id'], b['pub'], b['title'], b['series']):
                bs = s.encode('utf-8')
                f.write(struct.pack('<H', len(bs))); f.write(bs)
            f.write(struct.pack('<i', b['stage']))
            rows = b.get('rows3') or b['rows']
            f.write(struct.pack('<i', len(rows)))
            for wi, pi, mi in rows:
                f.write(struct.pack('<i', wi)); f.write(struct.pack('<i', pi)); f.write(struct.pack('<i', mi))


def verify_pack(pool, books):
    ids, total = set(), 0
    for b in books:
        if b['id'] in ids:
            raise SystemExit('重复 book id: ' + b['id'])
        ids.add(b['id'])
        for wi, pi, mi in b['rows3']:
            if not (0 <= wi < len(pool) and 0 <= pi < len(pool) and 0 <= mi < len(pool)):
                raise SystemExit('索引越界: %s' % b['title'])
            if not pool[wi] or not pool[mi]:
                raise SystemExit('空词/空释义: %s' % b['title'])
            total += 1
    return total


def append_to_pack():
    ver, pool, books = read_wdb(WDB)
    existing = {b['id'] for b in books}
    fresh = [b for b in cet_books() if b['id'] not in existing]
    if not fresh:
        have = [b for b in books if b['stage'] == STAGE_COLLEGE]
        print('CET 词书已存在（%d 本）：%s' % (len(have), '、'.join(b['title'] for b in have)))
        return 0
    verify_books(fresh)

    idx = {s: i for i, s in enumerate(pool)}
    for b in fresh:                                   # 新串一律追加在池尾 → 旧索引天然有效
        for w, p, m in b['rows']:
            for s in (w, p, m):
                if s not in idx:
                    idx[s] = len(pool)
                    pool.append(s)
    for b in fresh:
        b['rows3'] = [(idx[w], idx[p], idx[m]) for w, p, m in b['rows']]
        b['n'] = len(b['rows3'])
    for b in books:                                   # 老书补齐 rows3（原索引原样）
        b.setdefault('rows3', b['rows'])

    # CET 三本固定排在词书库最后（与 etl.py 全量重生成一致，见 AGENT.md 坑 14 的教训）
    books = [b for b in books if b['stage'] != STAGE_COLLEGE] + fresh
    n_words = verify_pack(pool, books)
    write_wdb(WDB, ver, pool, books)

    ver2, pool2, books2 = read_wdb(WDB)               # 落盘后再解析一遍（模拟 App 冷启动）
    for b in books2:
        b['rows3'] = b['rows']
    assert len(pool2) == len(pool) and len(books2) == len(books)
    assert n_words == verify_pack(pool2, books2)

    print('books=%d words=%d pool=%d size=%.1fKB' % (len(books), n_words, len(pool),
                                                     os.path.getsize(WDB) / 1024))
    for b in books:
        if b['stage'] == STAGE_COLLEGE:
            print('  +大学 %s %s n=%d' % (b['pub'], b['title'], b['n']))
    print('OK')
    return 0


def main():
    if '--fetch' in sys.argv:
        build_tsv()
        return 0
    return append_to_pack()


if __name__ == '__main__':
    sys.exit(main())
