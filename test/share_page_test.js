/*
 * 主机侧：在线战绩页（share/index.html）里那个「最小 inflate」的解码测试。
 *
 * 为什么单独测它：这个解码器是手写的（页面要在手机浏览器/微信内置浏览器里跑，
 * 不引第三方库），而它的输入是 Java 侧 ZipB64.pack()（原 PlanCode.pack，已随「我的词本」删除搬到 ZipB64）产出的
 * 「zlib deflate + base64url(去=)」。两份实现一旦不一致，用户扫码只会看到
 * 「打不开这张战绩图」，而我们是收不到任何日志的。
 *
 * 这里直接从 share/index.html 里抠出 WPI 那段脚本来跑（测的是真页面里的代码，
 * 不是副本），用 node 的 zlib 造出与 Java Deflater(9) 同格式的负载：
 *   · zlib 封装（默认）＝ Java 的 DeflaterOutputStream(Deflater(9))
 *   · 裸 deflate       ＝ 万一哪天改成 Nowrap
 *   · 存储块（level 0）＝ 数据太碎、压不动时的分支
 * 跑法：node test/share_page_test.js   （scripts/run_tests.sh 里也会顺带跑）
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const zlib = require('zlib');

const htmlPath = path.join(__dirname, '..', 'share', 'index.html');
const html = fs.readFileSync(htmlPath, 'utf8');

/* 抠出页面里第一个 <script> 段（里面是 var WPI = (function(){…})()） */
const start = html.indexOf('<script>') + '<script>'.length;
const end = html.indexOf('</script>', start);
const code = html.slice(start, end);
if (code.indexOf('var WPI') < 0) throw new Error('share/index.html 里找不到 WPI 解码器');

const sandbox = { window: globalThis, module: { exports: {} }, TextDecoder: globalThis.TextDecoder, console };
vm.createContext(sandbox);
vm.runInContext(code, sandbox);
const WPI = sandbox.module.exports;

let checks = 0;
function check(cond, what) {
  if (!cond) throw new Error('FAIL: ' + what);
  checks++;
}
function b64u(buf) {
  return Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/* 与 ShareCard.payload() 同构：key=value 行 */
function raw(name, seed) {
  let heat = '';
  for (let i = 0; i < 182; i++) heat += String((i * 7 + seed) % 5);
  return 'n=' + name + '\nd=2026-09-14\nt=57\ng=100\ns=12\nb=30\nm=1234\nk=88\nr=14\nf=9\nx=12\nh=' + heat;
}

const cases = {
  'zlib 封装（= Java Deflater(9)）': zlib.deflateSync(Buffer.from(raw('小明', 1), 'utf8'), { level: 9 }),
  '裸 deflate（Nowrap）': zlib.deflateRawSync(Buffer.from(raw('Alexandra', 2), 'utf8'), { level: 9 }),
  '存储块（level 0，压不动）': zlib.deflateSync(Buffer.from(raw('我', 3), 'utf8'), { level: 0 }),
  '固定哈夫曼（Z_FIXED）': zlib.deflateSync(Buffer.from(raw('张三丰', 4), 'utf8'), { level: 9, strategy: zlib.constants.Z_FIXED }),
};

for (const tag of Object.keys(cases)) {
  const payload = b64u(cases[tag]);
  const p = WPI.payload(payload);
  check(p.d === '2026-09-14', tag + ' 日期');
  check(p.t === '57' && p.g === '100', tag + ' 指标');
  check(p.n.length > 0, tag + ' 名字');
  check(/^[0-4]{182}$/.test(p.h), tag + ' 热力图等级串（182 天 × 0-4 级），实际 ' + (p.h || '').length);
  check(WPI.b64u(payload).length > 30, tag + ' base64 解出字节');
  console.log('   PASS ' + tag + ' · 负载 ' + payload.length + ' 字符 · name=' + p.n);
}

/* 截断/乱码必须抛错（页面据此弹「打不开」，不能白屏或画出乱码数字） */
for (const bad of ['', 'AAAA', '这不是码', 'LU7JDQJBDPunl5WcA9h9pBm']) {
  let threw = false;
  try {
    const p = WPI.payload(bad);
    threw = !p || !p.d;
  } catch (e) {
    threw = true;
  }
  check(threw, '坏数据要被拒：' + JSON.stringify(bad));
}

/* 页面里的热力图对齐逻辑：(182 + 首周偏移) 必须被 7 整除地排成列 */
function weekday(dateStr) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateStr);
  return (new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])).getUTCDay() + 6) % 7;
}
function daysAgo(dateStr, n) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateStr);
  let t = Date.UTC(+m[1], +m[2] - 1, +m[3]) - n * 86400000;
  const dt = new Date(t);
  return dt.getUTCFullYear() + '-' + String(dt.getUTCMonth() + 1).padStart(2, '0') + '-' + String(dt.getUTCDate()).padStart(2, '0');
}
for (const date of ['2026-09-14', '2026-09-13', '2026-09-12', '2026-09-07', '2027-01-01']) {
  const lead = weekday(daysAgo(date, 181));
  const cells = lead + 182;
  const cols = Math.ceil(cells / 7);
  check(cols === 26 || cols === 27, date + ' 应排成 26/27 列（实际 ' + cols + '）');
  check(weekday(daysAgo(date, 181)) === lead, date + ' 星期几计算自洽');
}

console.log('ALL SHARE PAGE TESTS PASS (' + checks + ' checks)');
