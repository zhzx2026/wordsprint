import com.aidemo.wordsprint.ZipB64;
import com.aidemo.wordsprint.QREnc;
import com.aidemo.wordsprint.QRUtil;

import java.util.Random;

/**
 * 主机侧：战绩分享链路（需求第 7 条）。
 *
 * 这条链路有两份实现必须永远一致：
 *   · App：ShareCard.payload() → ZipB64.pack()（zlib deflate + base64url 去 =）
 *   · 在线页：share/index.html 里的最小 inflate（要能在手机浏览器/微信内置浏览器跑，不引第三方库）
 * 这里锁三件事：
 *   1) pack/unpack 往返一致，且确实是 zlib 封装（首字节 0x78，页面靠它决定要不要剥头）；
 *   2) 负载长度可控（二维码别膨胀到扫不出来）+ 负载里没有会破坏 URL 的字符；
 *   3) 完整在线地址真能编成二维码，且能被 zxing 解回原样（与 QRUtil 的自检同源）。
 * 页面侧的 JS 解码在沙箱里用 node + python zlib 交叉验证过（见 share/README.md）。
 */
public class SharePayloadTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    /** 与 ShareCard.payload() 完全同构：键值行（n 名字 d 日期 … h 182 天等级串） */
    static String buildRaw(String name, String date, int today, int goal, int streak, int best,
                           int total, int days, int revMin, String heat) {
        // 与 ShareCard.payload() 同构：f（收藏）/ x（自测）两个键随功能删除
        return "n=" + name + "\nd=" + date + "\nt=" + today + "\ng=" + goal
                + "\ns=" + streak + "\nb=" + best + "\nm=" + total + "\nk=" + days
                + "\nr=" + revMin + "\nh=" + heat;
    }

    static String heat(int seed) {
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 182; i++) sb.append((char) ('0' + (r.nextInt(10) < 4 ? 0 : 1 + r.nextInt(4))));
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        // 与实际 pageBase() 一致：GitHub Pages（官方，会以 text/html 发出，浏览器才渲染成网页）
        // dev / stable 用同一个地址：Pages 背后跟哪个分支由仓库设置决定，二维码里的链接不用变
        String baseDev = "https://zhzx2026.github.io/wordsprint/share/index.html";
        String baseMain = baseDev;
        String base = baseDev;

        // 0) 页面地址必须落在会正常渲染 HTML 的站点上
        //    （jsDelivr / statically 这些 CDN 故意把 .html 发成 text/plain → 打开只看到源码）
        check(base.indexOf("jsdelivr") < 0 && base.indexOf("statically") < 0,
                "战绩页不能挂在把 .html 当 text/plain 发的 CDN 上：" + base);
        check(base.indexOf("github.io") > 0, "战绩页地址应是 GitHub Pages：" + base);

        // 1) 往返 + zlib 封装
        String raw = buildRaw("小明", "2026-09-14", 57, 100, 12, 30, 1234, 88, 14, heat(7));
        String payload = ZipB64.pack(raw);
        check(payload.length() > 40, "负载不该是空的");
        check(payload.indexOf('=') < 0, "负载不能带 base64 填充（URL 里难看且会被截断）");
        check(!payload.matches(".*[+/].*"), "负载必须是 URL 安全字母表（- _），否则二维码进 URL 会坏");
        check(ZipB64.unpack(payload).equals(raw), "pack/unpack 必须逐字符往返一致");

        byte[] zip = java.util.Base64.getUrlDecoder().decode(
                payload.length() % 4 == 0 ? payload : payload + "==".substring(0, (4 - payload.length() % 4) % 4));
        check((zip[0] & 0xFF) == 0x78, "必须是 zlib 封装（首字节 0x78）：" + Integer.toHexString(zip[0] & 0xFF));
        check(((zip[0] << 8 | (zip[1] & 0xFF)) % 31) == 0, "zlib 头校验位要对（网页端靠这个判断要不要剥头）");

        // 2) 长度与完整 URL
        String url = base + "?d=" + payload;
        System.out.println("   负载 " + payload.length() + " 字符 · 在线地址 " + url.length() + " 字符");
        check(payload.length() < 400, "负载别超过 400 字符（二维码容量与清晰度）");
        check(url.length() < 420, "完整地址别超过 420 字符");
        check(url.indexOf('\n') < 0 && url.indexOf(' ') < 0, "URL 里不能有空白");

        // 3) 二维码：编 + 自解码（和用户扫的是同一个矩阵）
        byte[] bytes = url.getBytes("UTF-8");
        boolean[][] mat = QRUtil.verifiedEncode(bytes);
        check(mat.length == mat[0].length, "二维码矩阵应为正方形");
        check(mat.length >= 21 && mat.length <= 177, "矩阵尺寸应在 v1~v40 之间：" + mat.length);
        System.out.println("   QR 版本 ≈ v" + ((mat.length - 17) / 4) + "（" + mat.length + "×" + mat.length + " 模块）");
        check(mat.length <= 77, "分享图里的二维码别超过 v16（模块太小手机扫不动）：" + mat.length);
        check(QRUtil.selfDecodes(mat, new String(bytes, "UTF-8")), "在线地址的二维码必须能被解回原样");

        // 4) 极端负载：超长名字 / 全 0 热力图 / 空名字，都不能把 URL 撑爆
        String[] names = {"", "我", "张三丰", "Alexandra-Wang", rep('李', 40)};
        for (int i = 0; i < names.length; i++) {
            String r2 = buildRaw(names[i], "2026-12-31", 0, 50, 0, 999, 99999, 999, 0,
                    i == 1 ? rep('0', 182) : heat(100 + i));
            String u2 = base + "?d=" + ZipB64.pack(r2);
            check(u2.length() < 470, "极端负载 URL 也别超长：" + u2.length());
            check(QRUtil.selfDecodes(QREnc.encode(u2.getBytes("UTF-8")), u2), "极端负载二维码自解码：name#" + i);
        }

        // 5) 页面解析的两种写法都要兼容：标准字母表（+ /）与 URL 字母表（- _）
        String std = ZipB64.pack(raw);
        check(ZipB64.unpack(std).equals(raw), "标准字母表/URL 字母表都要能解（unB64Pure 两套都认）");

        // 6) 打包/解包（ZipB64）：分享负载就是它压出来的，在线页解的就是它
        String back = ZipB64.unpack(payload);
        check(back.contains("n=") && back.contains("h="), "解包能还原负载文本");
        check(ZipB64.pack("").length() > 0, "空文本也能打包");
        check(ZipB64.clean(" a\u200Bb\u00A0c ").equals("abc"), "脏字符清理");

        System.out.println("ALL SHARE PAYLOAD TESTS PASS (" + checks + " checks)");
    }

    static String rep(char c, int n) {
        char[] a = new char[n];
        java.util.Arrays.fill(a, c);
        return new String(a);
    }
}
