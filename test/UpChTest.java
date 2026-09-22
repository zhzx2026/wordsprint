import com.aidemo.wordsprint.UpCh;

import java.util.ArrayList;
import java.util.List;

/**
 * 主机侧：更新源第 3 档「分支」通道的纯逻辑（UpCh）。
 *
 * 背景（用户 2026-09-22）：更新源原来只有 stable / dev 两档，dev 根地址永远是「最近一次构建」，
 * 多条会话并行时互相覆盖 —— 用户问「其他分支怎么分别测试」。修法是第 3 档「分支」：
 * App 里直接列出并选择 dev 通道上的坑位（channels/&lt;分支id&gt;/）。坑位 id 要拼进下载 URL、
 * 坑位名单要从一个手抠解析器里出来，这两处脏数据都会直接变成「更新失败」或「下错包」，
 * 所以在这里钉死。
 *
 * 跑法：bash scripts/run_tests.sh
 */
public class UpChTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    public static void main(String[] args) {
        // 1) 通道号清洗：0/1/2 原样，越界一律当 stable
        check(UpCh.sanitize(0) == 0 && UpCh.sanitize(1) == 1 && UpCh.sanitize(2) == 2, "0/1/2 原样通过");
        check(UpCh.sanitize(3) == 0 && UpCh.sanitize(-1) == 0 && UpCh.sanitize(99) == 0, "越界通道号一律当 stable");

        // 2) 坑位 id 清洗：它要拼进下载 URL，路径字符一个都不能留
        check(UpCh.sanitizeSlot("arena01a0c983").equals("arena01a0c983"), "正常坑位 id 原样保留");
        check(UpCh.sanitizeSlot("../etc/passwd").equals("etcpasswd"), "路径分隔与点全部剔除（拼 URL 逃不出 channels/）");
        check(UpCh.sanitizeSlot("a b／c").equals("abc"), "空格与全角字符剔除");
        check(UpCh.sanitizeSlot(null).equals("") && UpCh.sanitizeSlot("").equals(""), "null/空串 → 空串");
        check(UpCh.sanitizeSlot("0123456789012345678901234567890123456789012345678123456789").length() == 48,
                "超长 id 截到 48");

        // 3) 坑位直链拼装：内置 dev 源两种写法都要能换算
        String dev = "https://raw.githubusercontent.com/zhzx2026/wordsprint/dev/update.json";
        check(UpCh.slotUrl(dev, "arena01a0c983")
                        .equals("https://raw.githubusercontent.com/zhzx2026/wordsprint/dev/channels/arena01a0c983/update.json"),
                "标准 dev 源 → 坑位直链");
        check(UpCh.slotUrl("https://raw.githubusercontent.com/zhzx2026/wordsprint/dev/", "arena01a0c983")
                        .equals("https://raw.githubusercontent.com/zhzx2026/wordsprint/dev/channels/arena01a0c983/update.json"),
                "带尾斜杠的 dev 源 → 同一条直链");
        check(UpCh.slotUrl(dev, "").equals("") && UpCh.slotUrl("", "x").equals("")
                && UpCh.slotUrl(null, "x").equals(""), "没选坑位/没配源 → 空串（调用方按未配置处理）");

        // 4) channels 名单解析：publish_dev.sh 写进 dev 根 update.json 的固定形状
        check(UpCh.parseChannels("{\"a\":1}").isEmpty(), "没有 channels 字段 → 空 list");
        check(UpCh.parseChannels("{\"channels\":[]}").isEmpty(), "空数组 → 空 list");
        check(UpCh.parseChannels(null).isEmpty(), "null → 空 list");
        String j = "{\n \"versionCode\": 43,\n \"notes\": \"x\",\n \"channels\":\n  [\"arena01a0b2c2\", \"arena01a0c46d\"]\n}";
        List<String> got = UpCh.parseChannels(j);
        check(got.size() == 2 && got.get(0).equals("arena01a0b2c2") && got.get(1).equals("arena01a0c46d"),
                "真实形状（多行缩进）→ 两个坑位、顺序保持");
        // notes 里出现别的字符串数组也不能干扰（解析从 "channels" 这个 key 之后才开始）
        String tricky = "{\"notes\":\"see [\\\"x\\\"]\",\"channels\":[\"a\"],\"tail\":1}";
        check(UpCh.parseChannels(tricky).size() == 1 && UpCh.parseChannels(tricky).get(0).equals("a"),
                "notes 里的方括号/引号不干扰解析");

        // 5) 名单是 List 语义：可空、可遍历（界面直接拿去渲染 chips）
        check(new ArrayList<String>(UpCh.parseChannels("{\"channels\":[\"a\",\"a\"]}")).size() == 2,
                "重复 id 原样保留（后端去重是 publish_dev.sh 的事）");

        System.out.println("ALL UPCH TESTS PASS (" + checks + " checks)");
    }
}
