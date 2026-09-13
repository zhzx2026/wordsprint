#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ETL: raw xlsx -> compact binary pack (res/raw/wdb.dat)
Format: 'WDB1' | i32 nStrings | nStrings * (u16 len + utf8) | i32 nBooks |
        nBooks * (str id, str pub, str title, str series, i32 stage, i32 nWords,
                  nWords * (i32 wordIdx, i32 phIdx, i32 meanIdx))
"""
import openpyxl, re, os, json, hashlib, struct, io

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, 'raw_xlsx')
paths = json.load(open(os.path.join(ROOT, 'path_index.json')))

# ===== 保留：人教版小学（PEP 三年级起点 3-6 年级）+ 初中 5 册 + 高中新课标 7 册 + 高考大纲 3500 =====
# 小学册没有 raw_xlsx 时由 scripts/etl_primary.py 从 data/primary_words.tsv 追加，
# 两条路径产出的元数据保持一致（pub=人教版 PEP / title=三年级上册… / series=三年级起点 / stage=0）。
KEEP_PRIMARY = ('三年级', '四年级', '五年级', '六年级')
KEEP_JUNIOR = ('初中英语七年级上', '初中英语七年级下', '初中英语八年级上', '初中英语八年级下', '初中英语九年级全')
KEEP_SENIOR = ('必修第一册', '必修第二册', '必修第三册',
               '选择性必修第一册', '选择性必修第二册', '选择性必修第三册', '选择性必修第四册')
CUTOFF_3500 = '2.高考/高考英语大纲词汇表.xlsx'
def _keep(rel):
    if rel == CUTOFF_3500:
        return True
    if rel.startswith('1.全国各大教材版本中小学同步/人教版/'):
        fn = rel.rsplit('/', 1)[-1][:-5]
        if any(k in fn for k in KEEP_JUNIOR): return True
        if '高中英语' in fn and any(k in fn for k in KEEP_SENIOR): return True
        # 小学：人教版 3-6 年级。"一年级起点"是另一个系列（人教一起），不收
        if '小学英语' in fn and '一年级起点' not in fn and any(k in fn for k in KEEP_PRIMARY): return True
    return False
KEEP_IDX = [(i, p) for i, p in enumerate(paths) if _keep(p)]

PUB_ZH = {
 '人教版':'人教版 PEP','仁爱版':'仁爱版','冀教版':'冀教版','剑桥版':'剑桥版','北京版':'北京版',
 '北师大':'北师大版','外研版':'外研版','广东版':'粤人版','广州版':'广州版','教科版':'教科版',
 '沪教版':'沪教版','湘少版':'湘少版','牛津版':'牛津版','科普版':'科普版','译林版':'译林版',
 '闽教版':'闽教版','陕西版':'陕旅版','鲁教版':'鲁教版','鲁科版':'鲁科版',
}
EXTRA_PUB = {'2.中考':'升学考试','2.高考':'升学考试','3.四级':'大学英语','4.六级':'大学英语',
             '5.考研':'研究生英语','8.新概念英语':'新概念英语'}
PUB_ORDER = list(PUB_ZH.values()) + ['新概念英语', '升学考试', '大学英语', '研究生英语']
CN = {'一':1,'二':2,'三':3,'四':4,'五':5,'六':6,'七':7,'八':8,'九':9}

def sort_key(title):
    stage, g, term, series = 3, 99, 0, 0
    if '一年级起点' in title: series = 1
    if '三年级起点' in title: series = 2
    m = re.search(r'([一二三四五六七八九])年级', title)
    if m:
        g = CN.get(m.group(1), 99)
        if '必修' not in title and '选择性' not in title and '高中' not in title:
            stage = 0 if g <= 6 else 1
    if '初中' in title: stage = 1
    if '高中' in title or '必修' in title or '选修' in title: stage = 2
    if re.search(r'(上册|年级上|上)$', title) or '上' in title[-1:]: term = 0
    if '下册' in title or title.endswith('下'): term = 1
    if '全册' in title: term = 2
    mh = re.search(r'高中英语(\d+)', title)
    extra = int(mh.group(1)) if mh else 0
    for pat, v in (('选择性必修第四',41),('选择性必修第三',31),('选择性必修第二',21),('选择性必修第一',11),
                   ('必修第四',14),('必修第三',13),('必修第二',12),('必修第一',11)):
        if pat in title: extra = v
    return (stage, series, g, term, extra)

def clean_word(w):
    w = str(w).strip().strip('"').replace('\u00a0', ' ')
    w = re.sub(r'[\t\r\n]+', ' ', w).strip()
    if not re.search(r'[A-Za-z]', w): return None
    if len(w) > 46: return None
    if re.match(r'^(Unit|Lesson|Module|Revision|Review|Words in|词汇|单词)\b', w, re.I): return None
    if w.count(' ') > 5: return None
    return w

def clean_mean(s):
    if s is None: return ''
    s = str(s).strip().replace('\u00a0', ' ')
    s = re.sub(r'\s*\n\s*', '\n', s)
    out, total = [], 0
    for p in s.split('\n'):
        for seg in re.split(r'[;；]', p):
            seg = seg.strip()
            if not seg: continue
            out.append(seg); total += len(seg)
            if total >= 34 or len(out) >= 2: break
        if total >= 34 or len(out) >= 2: break
    m = '; '.join(out)
    m = re.sub(r'^[.,;、\s]+', '', m)
    if m in ('无', '-', '—', '', 'nan', 'None', 'undefined') or re.fullmatch(r'[\W_]+', m):
        return ''
    return m[:80]

def clean_ph(s):
    if s is None: return ''
    s = str(s).strip().strip('[]').strip()
    s = s.split(';')[0].strip()
    return s if (s and re.search(r'[^\s\[\]()（）]', s) and len(s) <= 40) else ''

def parse_xlsx(fp):
    wb = openpyxl.load_workbook(fp, read_only=True)
    ws = wb[wb.sheetnames[0]]
    rows = []
    for i, r in enumerate(ws.iter_rows(values_only=True)):
        if i == 0 and r and str(r[0]).strip() in ('单词', 'word', 'Word'):
            continue
        if not r or r[0] is None: continue
        cells = [c for c in r if c is not None and str(c).strip() != '']
        if len(cells) < 2: continue
        w = clean_word(cells[0])
        if not w: continue
        c1, c2, c3 = (str(cells[1]) if len(cells) > 1 else ''), (str(cells[2]) if len(cells) > 2 else ''), (str(cells[3]) if len(cells) > 3 else '')
        if len(cells) >= 4 and (re.match(r'^\[', c1) or re.match(r'^\[', c2)):
            ph = clean_ph(c1) or clean_ph(c2); mean = clean_mean(c3)
        elif len(cells) >= 3 and (re.match(r'^\[', c1) or re.match(r'^/[^/]+/$', c1)):
            ph = clean_ph(c1); mean = clean_mean(c2)
        else:
            ph = ''; mean = clean_mean(c1)
        if not mean: continue
        rows.append((w, ph, mean))
    wb.close()
    seen = {}
    for w, p, m in rows:
        k = w.lower()
        if k not in seen: seen[k] = (w, p, m)
    return list(seen.values())

books = []
skipped = []
for i, rel in KEEP_IDX:
    fp = os.path.join(RAW, f'{i:04d}.xlsx')
    fn = os.path.basename(rel)[:-5]
    parts = rel.split('/')
    pub_folder, cat0 = parts[1], parts[0]
    ws = parse_xlsx(fp)
    if len(ws) < 8:
        skipped.append((fn, len(ws))); continue
    if cat0.startswith('1.'):
        pub = PUB_ZH.get(pub_folder, pub_folder + '版')
        title = fn
        for pref in (pub_folder, pub):
            if title.startswith(pref): title = title[len(pref):]
        if title.startswith('英语'): title = title[2:]
        series = '三年级起点' if '三年级起点' in fn else ('一年级起点' if '一年级起点' in fn else '')
        title = title.replace('三年级起点', '').replace('一年级起点', '').strip()
        import re as _re
        title = _re.sub(r'^(初中|高中)英语', r'\1', title)
        if title.startswith('小学英语'): title = title[2:]     # 显示为"三年级上册"，学段看分组头
        stage = sort_key(fn)[0]
        if stage == 0 and not series: series = '三年级起点'    # 人教版小学英语默认即三年级起点
    elif rel == CUTOFF_3500:
        pub = '大纲词表'
        title = '高考英语 3500 词'; series = ''
        stage = 3
    else:
        pub = EXTRA_PUB.get(cat0, '扩展词书')
        title = fn; series = ''
        stage = 4 if cat0.startswith('8.') else 3
    bid = hashlib.md5(rel.encode()).hexdigest()[:10]
    books.append({'id': bid, 'pub': pub, 'title': title or fn, 'series': series,
                  'stage': stage, 'sortk': sort_key(fn), 'words': ws})

def pub_rank(pub):
    return PUB_ORDER.index(pub) if pub in PUB_ORDER else 99
books.sort(key=lambda b: (pub_rank(b['pub']), b['pub'], b['sortk'], b['title']))

# two-pass string pool
pool = {}
def intern(s):
    if s not in pool: pool[s] = len(pool)
for b in books:
    for (w, p, m) in b['words']:
        intern(w); intern(p); intern(m)

data = io.BytesIO()
def put_int(v): data.write(struct.pack('<i', v))
def put_str(s):
    bs = s.encode('utf-8')
    if len(bs) > 60000: bs = bs[:60000].decode('utf-8', 'ignore').encode('utf-8')
    data.write(struct.pack('<H', len(bs))); data.write(bs)

put_str('v1')
put_int(len(pool))
for s in sorted(pool, key=pool.get): put_str(s)
put_int(len(books))
for b in books:
    put_str(b['id']); put_str(b['pub']); put_str(b['title']); put_str(b['series']); put_int(b['stage'])
    put_int(len(b['words']))
    for (w, p, m) in b['words']:
        put_int(pool[w]); put_int(pool[p]); put_int(pool[m])

packed = b'WDB1' + data.getvalue()
os.makedirs(os.path.join(ROOT, 'res/raw'), exist_ok=True)
open(os.path.join(ROOT, 'res/raw/wdb.dat'), 'wb').write(packed)

total = sum(len(b['words']) for b in books)
print(f'books={len(books)} words={total} pool={len(pool)} packed={len(packed)/1e6:.2f}MB skipped={len(skipped)}')
for s in skipped[:15]: print('  skip', s)
pubs = {}
for b in books: pubs[b['pub']] = pubs.get(b['pub'], 0) + 1
print(json.dumps(pubs, ensure_ascii=False))
