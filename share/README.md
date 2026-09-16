# share/ —— 在线战绩页

`index.html` 是战绩图里那个二维码指向的网页：**扫码直接打开，不触发任何文件下载**
（这是需求第 7 条的硬要求）。页面不依赖服务器、不依赖第三方库，数据全在 URL 里，
所以它需要的是一个「会把 .html 当网页发出去」的托管点：

```
https://zhzx2026.github.io/wordsprint/share/index.html?d=<payload>
```

**为什么是 GitHub Pages（2026-09-14 用户反馈「html 打开看到的是源码」后换过来的）**

| 托管方式 | 实测结果 |
|---|---|
| jsDelivr（`cdn.jsdelivr.net/gh/...`） | 官方策略：**HTML 文件一律以 `Content-Type: text/plain` 发出**（防钓鱼），浏览器只会把源码当文本显示 —— 用户看到的正是这个 |
| statically.io / githack | 同样发 `text/plain`；githack 还会先弹一个「External Content Notice」中转页，要人手点一下 |
| GitHub Pages | 官方、`Content-Type: text/html`、查询参数原样保留、没有中转页 ✅ |

**开启方式（只需一次，且必须是仓库所有者点）**：仓库 `Settings` → `Pages` → Source = *Deploy from a branch*
→ Branch = **dev**、目录 = **/ (root)** → Save。

- 为什么不用 Actions 发布：实测 `GITHUB_TOKEN` 没有创建 Pages 站点的权限
  （`Resource not accessible by integration`），所以 CI 里没法代劳；分支方式反而更省事。
- 开完就能用：dev 分支本来就由 `publish_dev.sh` 在每次构建时刷新（含 `share/index.html`
  与 `res/font/wp_word.ttf`），所以页面会跟着每次构建自动更新，**不需要重新发版**。
- 转正后把 Source 切到 `main` 即可（地址不变，之前分享出去的图也不会失效）。
- 若想改用 Actions 发布（`actions/deploy-pages`），先把 Source 改成 *GitHub Actions* 再启用。

## 数据契约（App ↔ 页面，改一边必须改另一边）

| 环节 | App 侧 | 页面侧 |
|---|---|---|
| 组装 | `ShareCard.payload()`：键值行（`n=` 名字、`d=` 日期、`t=` 今日新词、`g=` 目标、`s=` 连续、`b=` 最高连续、`m=` 累计掌握、`k=` 达标天数、`r=` 温习分钟、`h=` 182 天等级串（`f=`/`x=` 收藏与自测 2026-09-16 已从 App 删除，老链接里还带着这两个键，页面忽略）） | `WPI.payload()` 解析回同一批字段 |
| 压缩 | `ZipB64.pack()`：`Deflater(9)` + `DeflaterOutputStream`（**zlib 封装**：2 字节头 + 尾部 adler32） | 手写 `inflateRaw()`；`zlibSafe()` 先按需剥掉 2 字节 zlib 头 |
| 编码 | base64 用 URL 安全字母表（`-` `_`），去掉 `=` 填充 | 两套字母表都认，缺填充也认 |

手机端（微信/系统浏览器）能跑的最小 inflate 是自己写的，所以两端一致性必须锁住：

- `test/share_page_test.js`（node）：从 `index.html` 里抠出真实脚本跑，用 node 的 zlib 造
  「zlib 封装 / 裸 deflate / 存储块 / 固定哈夫曼」四种负载，验证解码 + 坏数据必须报错 + 热力图列数。
- `test/SharePayloadTest.java`（CI 的 `scripts/run_tests.sh`）：验证 `ZipB64.pack/unpack` 往返、
  首字节确实是 `0x78`、负载里没有 `+` `/` `=`、完整在线地址能被编成二维码并解回原样。

改动这里的任何一方后，两条测试都必须过（`bash scripts/run_tests.sh`）。

## 本地预览

```bash
# 随便造一份负载，然后本地起个静态服务（手机同一 WiFi 也能扫）
python3 - <<'PY'
import zlib, base64
raw = "n=我\nd=2026-09-14\nt=57\ng=100\ns=12\nb=30\nm=1234\nk=88\nr=14\nf=9\nx=12\nh=" + "0123401234"*18 + "01"
print("share/index.html?d=" + base64.urlsafe_b64encode(zlib.compress(raw.encode(), 9)).decode().rstrip("="))
PY
```
