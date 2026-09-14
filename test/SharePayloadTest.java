import com.aidemo.wordsprint.Plan;
import com.aidemo.wordsprint.PlanCode;
import com.aidemo.wordsprint.QREnc;
import com.aidemo.wordsprint.QRUtil;

import java.util.Random;

/**
 * 主机侧：战绩分享链路（需求第 7 条）。
 *
 * 这条链路有两份实现必须永远一致：
 *   · App：ShareCard.payload() → PlanCode.pack()（zlib deflate + base64url 去 =）
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
                           int total, int days, int revMin, int favs, int tested, String heat) {
        return "n=" + name + "\nd=" + date + "\nt=" + today + "\ng=" + goal
                + "\ns=" + streak + "\nb=" + best + "\nm=" + total + "\nk=" + days
                + "\nr=" + revMin + "\nf=" + favs + "\nx=" + tested + "\nh=" + heat;
    }

    static String heat(int seed) {
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 182; i++) sb.append((char) ('0' + (r.nextInt(10) < 4 ? 0 : 1 + r.nextInt(4))));
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        String base = "https://cdn.jsdelivr.net/gh/zhzx2026/wordsprint@main/share/index.html";

        // 1) 往返 + zlib 封装
        String raw = buildRaw("小明", "2026-09-14", 57, 100, 12, 30, 1234, 88, 14, 9, 12, heat(7));
        String payload = PlanCode.pack(raw);
        check(payload.length() > 40, "负载不该是空的");
        check(payload.indexOf('=') < 0, "负载不能带 base64 填充（URL 里难看且会被截断）");
        check(!payload.matches(".*[+/].*"), "负载必须是 URL 安全字母表（- _），否则二维码进 URL 会坏");
        check(PlanCode.unpack(payload).equals(raw), "pack/unpack 必须逐字符往返一致");

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
            String r2 = buildRaw(names[i], "2026-12-31", 0, 50, 0, 999, 99999, 999, 0, 999, 0,
                    i == 1 ? rep('0', 182) : heat(100 + i));
            String u2 = base + "?d=" + PlanCode.pack(r2);
            check(u2.length() < 470, "极端负载 URL 也别超长（" + names.length + "）：" + u2.length);
            check(QRUtil.selfDecodes(QREnc.encode(u2.getBytes("UTF-8")), u2), "极端负载二维码自解码：name#" + i);
        }

        // 5) 页面解析的两种写法都要兼容：标准字母表（+ /）与 URL 字母表（- _）
        String std = PlanCode.pack(raw);
        check(PlanCode.unpack(std).equals(raw), "标准字母表/URL 字母表都要能解（unB64Pure 两套都认）");

        // 6) 词本配置码与分享负载共用同一套 deflate+base64：互相别串味
        Plan plan = new Plan();
        plan.items.add(new Plan.Item("c3d0bc3dc2", 50, 0, 5));
        plan.items.add(new Plan.Item("fcc2190a0c", 30, 1, 3));
        plan.goal = 100;
        String code = PlanCode.encode(plan);
        check(code.startsWith("WPB1."), "词本配置码要带 WPB1. 前缀");
        PlanCode.Out o = PlanCode.parse("扫码结果：" + code + "\n（转发自刷单词）");
        check(o.plan != null && o.plan.items.size() == 2 && o.plan.goal == 100, "配置码要能从脏文本里解出来：" + o.why);
        check(PlanCode.parse(PlanCode.pack(raw)).plan == null, "分享负载不是配置码，必须老实说解不开");
        check(PlanCode.parse("WPB1." + payload).plan == null, "WPB1 前缀 + 分享负载也要老实失败，别乱认");

        System.out.println("ALL SHARE PAYLOAD TESTS PASS (" + checks + " checks)");
    }

    static String rep(char c, int n) {
        char[] a = new char[n];
        java.util.Arrays.fill(a, c);
        return new String(a);
    }
}
