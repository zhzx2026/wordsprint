import com.aidemo.wordsprint.UpCh;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 主机侧：更新通道纯逻辑（UpCh）。
 *
 * 演进史（都是真实事故/真实要求）：
 *   2026-09-22 上午  用户「更新只有两个选项，其他分支怎么分别测试」→ 第 3 档「分支」+ dev 通道坑位。
 *   2026-09-22 下午  用户「直接删除 dev 好了，apk 直接连 github 看分支」→ dev 聚合分支退役，
 *                     分支清单直读 GitHub /branches，每分支测试包挂在预发布 Release `ci` 的资产上
 *                     （update-<分支id>.json / wordsprint-<分支id>.json）。本测试盯的就是这套拼装：
 *                     分支全名 → 短 id（必须与 scripts/branch_id.sh 一致，否则 App 找不到资产）、
 *                     直链拼装、JSON 字段抠取（主机没有 org.json）、资产名 → 有包分支集合。
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

        // 5) /branches JSON 抠 name 字段（真实 API 形状：多行、含嵌套 commit 对象）
        String branches = "[\n  {\n    \"name\": \"arena/01a0c983-wordsprint\",\n    \"commit\": {\"sha\": \"abc\"},\n    \"protected\": false\n  },\n" +
                "  {\"name\": \"main\", \"commit\": {\"sha\": \"def\"}},\n  {\"name\": \"staging/exp\", \"commit\": {\"sha\": \"012\"}}\n]";
        List<String> names = UpCh.parseBranchNames(branches);
        check(names.size() == 3 && names.get(0).equals("arena/01a0c983-wordsprint")
                && names.get(1).equals("main") && names.get(2).equals("staging/exp"),
                "/branches 抠出全部分支名、顺序保持");
        check(UpCh.parseBranchNames(null).isEmpty() && UpCh.parseBranchNames("{}").isEmpty(), "空数据不吃亏");

        // 6) Release assets 抠 name + 有包分支集合（update-<id>.json 才算坑位；根 update.json 不算）
        String rel = "{\"name\":\"刷单词 · 测试包通道\",\"prerelease\":true,\"assets\":[\n" +
                "  {\"name\":\"update.json\",\"size\":100},\n  {\"name\":\"update-arena01a0c983.json\",\"size\":120},\n" +
                "  {\"name\":\"wordsprint-arena01a0c983.apk\",\"size\":9},\n  {\"name\":\"update-arena01a0b2c2.json\",\"size\":120}]}";
        List<String> assets = UpCh.parseAssetNames(rel);
        check(assets.size() == 5 && assets.get(0).equals("刷单词 · 测试包通道"),
                "资产名全抠出来（Release 自己的 name 也在，无害）");
        List<String> built = UpCh.builtIds(assets);
        check(built.size() == 2 && built.contains("arena01a0c983") && built.contains("arena01a0b2c2"),
                "有测试包的分支 = update-<id>.json 那几条");
        check(UpCh.builtIds(Arrays.asList("update.json", "wordsprint.apk")).isEmpty(),
                "根 update.json / apk 不冒充坑位");

        // 7) 名单语义：可空、可遍历、去重由调用方保证（这里不偷偷去重 parseBranchNames）
        check(new ArrayList<String>(UpCh.parseBranchNames("[{\"name\":\"a\"},{\"name\":\"a\"}]")).size() == 2,
                "重复名原样保留");

        System.out.println("ALL UPCH TESTS PASS (" + checks + " checks)");
    }
}
