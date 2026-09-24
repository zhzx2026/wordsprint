import com.aidemo.wordsprint.UpCh;

import java.util.ArrayList;
import java.util.List;

/**
 * 主机侧：更新通道纯逻辑（UpCh）。
 *
 * 演进史（都是真实事故/真实要求）：
 *   2026-09-22 上午  用户「更新只有两个选项，其他分支怎么分别测试」→ 第 3 档「分支」+ dev 通道坑位。
 *   2026-09-22 下午  用户「直接删除 dev 好了，apk 直接连 github 看分支」→ dev 聚合分支退役，
 *                     每分支测试包挂在预发布 Release `ci` 的资产上（update-<分支id>.json /
 *                     wordsprint-<分支id>.apk）。
 *   2026-09-22 晚  用户「分支都没用，没反应」→ 分支清单不再读 api.github.com（手机网络下经常
 *                     不通/匿名限流，清单永远拉不到 = 整行卡死），改读 ci 根 update.json 的
 *                     `channels` 数组（github.com，与下载同域）。本测试盯的就是这套拼装：
 *                     分支全名 → 短 id（必须与 scripts/branch_id.sh 一致，否则 App 找不到资产）、
 *                     直链拼装、channels 名单解析（主机没有 org.json，手抠要容错）。
 *
 * 跑法：bash scripts/run_tests.sh
 */
public class UpChTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    public static void main(String[] args) {
        // 1) 通道号清洗：只有 stable(0) / 分支(2) 两档；旧 1=dev 与越界一律当 stable
        check(UpCh.sanitize(0) == 0 && UpCh.sanitize(2) == 2, "0/2 原样通过");
        check(UpCh.sanitize(1) == 0, "旧 1=dev 档兜底为 stable（Prefs 层在它之前迁到分支）");
        check(UpCh.sanitize(UpCh.channelForChip(1)) == UpCh.BRANCH, "chips 下标 1 → 通道 2，写入后仍是分支（v6.3/v6.4 回归断言）");
        check(UpCh.sanitize(3) == 0 && UpCh.sanitize(-1) == 0 && UpCh.sanitize(99) == 0, "越界通道号一律当 stable");

        // 1b) chips 下标 → 通道号（v6.3/v6.4 回归点：下标 1 被当通道号存，点「分支」变成 stable）
        check(UpCh.channelForChip(0) == UpCh.STABLE && UpCh.channelForChip(1) == UpCh.BRANCH,
                "两枚 chips：第 0 枚 stable、第 1 枚分支（别把下标当通道号存）");
        check(UpCh.channelForChip(9) == UpCh.STABLE && UpCh.channelForChip(-1) == UpCh.STABLE,
                "越界下标保守当 stable");

        // 2) 坑位 id 清洗：它要拼进 URL / 匹配资产名，路径字符一个都不能留
        check(UpCh.sanitizeSlot("arena01a0c983").equals("arena01a0c983"), "正常坑位 id 原样保留");
        check(UpCh.sanitizeSlot("../etc/passwd").equals("etcpasswd"), "路径分隔与点全部剔除");
        check(UpCh.sanitizeSlot("a b／c").equals("abc"), "空格与全角字符剔除");
        check(UpCh.sanitizeSlot(null).equals("") && UpCh.sanitizeSlot("").equals(""), "null/空串 → 空串");
        check(UpCh.sanitizeSlot("0123456789012345678901234567890123456789012345678123456789").length() == 48,
                "超长 id 截到 48");

        // 3) 分支全名 → 短 id：必须与 scripts/branch_id.sh 同结果（App 与 CI 资产名对得上才有包下）
        check(UpCh.branchId("arena/01a0c983-wordsprint").equals("arena01a0c983"), "arena 全名 → arena+短id");
        check(UpCh.branchId("arena/01a0b2c2-wordsprint").equals("arena01a0b2c2"), "另一条分支同样规则");
        check(UpCh.branchId("staging/foo").equals("staging-foo"), "staging/foo → staging-foo");
        check(UpCh.branchId("main").equals("main") && UpCh.branchId("dev-build").equals("dev-build"), "普通名字原样");
        check(UpCh.branchId(null).equals(""), "null → 空串");
        check(UpCh.branchId("arena/x_y").equals("arenaxy"), "非法字符剔干净（别把 _ 带进资产名）");

        // 4) 直链拼装：预发布 Release ci 的资产 URL（302 到对象存储，App 的 HttpURLConnection 跟随）
        String base = "https://github.com/zhzx2026/wordsprint/releases/download/ci";
        check(UpCh.branchUpdateUrl(base, "arena01a0c983")
                .equals(base + "/update-arena01a0c983.json"), "标准 base → update-<id>.json");
        check(UpCh.branchUpdateUrl(base + "/", "arena01a0c983")
                .equals(base + "/update-arena01a0c983.json"), "带尾斜杠同样拼对");
        check(UpCh.branchUpdateUrl(base, "").equals("") && UpCh.branchUpdateUrl("", "x").equals("")
                && UpCh.branchUpdateUrl(null, "x").equals(""), "没选分支/没配 base → 空串（调用方按未配置处理）");

        // 5) channels 名单解析：publish_ci.sh 写进 ci 根 update.json 的固定形状
        check(UpCh.parseChannels("{\"a\":1}").isEmpty(), "没有 channels 字段 → 空 list");
        check(UpCh.parseChannels("{\"channels\":[]}").isEmpty(), "空数组 → 空 list");
        check(UpCh.parseChannels(null).isEmpty(), "null → 空 list");
        String j = "{\n \"versionCode\": 47,\n \"notes\": \"x\",\n \"channels\":\n  [\"arena01a0b2c2\", \"arena01a0c983\"]\n}";
        List<String> got = UpCh.parseChannels(j);
        check(got.size() == 2 && got.get(0).equals("arena01a0b2c2") && got.get(1).equals("arena01a0c983"),
                "真实形状（多行缩进）→ 两个分支、顺序保持");
        // notes 里出现别的字符串数组也不能干扰（解析从 "channels" 这个 key 之后才开始）
        String tricky = "{\"notes\":\"see [\\\"x\\\"]\",\"channels\":[\"a\"],\"tail\":1}";
        check(UpCh.parseChannels(tricky).size() == 1 && UpCh.parseChannels(tricky).get(0).equals("a"),
                "notes 里的方括号/引号不干扰解析");

        // 7) 名单语义：有效的 [] 表示所有分支都没了；只有请求失败(null)才保留旧 chips
        check(new ArrayList<String>(UpCh.parseChannels("{\"channels\":[\"a\",\"a\"]}")).size() == 2,
                "重复 id 原样保留（后端去重是 ci_sync.py 的事）");
        List<String> previous = UpCh.parseChannels("{\"channels\":[\"deleted\"]}");
        check(UpCh.fetchedOrCached(UpCh.parseChannels("{\"channels\":[]}"), previous).isEmpty(),
                "服务器明确返回空名单 → 必须清空本机旧名单（不能继续显示已删分支）");
        check(UpCh.fetchedOrCached(null, previous).equals(previous),
                "网络失败 → 沿用上次成功拿到的名单");
        check(UpCh.fetchedOrCached(null, null).isEmpty(), "首轮网络失败 → 空名单");

        System.out.println("ALL UPCH TESTS PASS (" + checks + " checks)");
    }
}
