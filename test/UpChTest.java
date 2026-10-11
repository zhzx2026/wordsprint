import com.aidemo.wordsprint.UpCh;

/**
 * 主机侧：更新源纯逻辑（UpCh）。
 *
 * 演进史（都是真实事故/真实要求）：
 *   2026-09-22 上午  用户「更新只有两个选项，其他分支怎么分别测试」→ 第 3 档「分支」+ dev 通道坑位。
 *   2026-09-22 下午  用户「直接删除 dev 好了，apk 直接连 github 看分支」→ dev 聚合分支退役，
 *                     每分支测试包挂在预发布 Release `ci` 的资产上（update-<分支id>.json /
 *                     wordsprint-<分支id>.apk）。
 *   2026-09-22 晚  用户「分支都没用，没反应」→ 分支清单不再读 api.github.com（手机网络下经常
 *                     不通/匿名限流，清单永远拉不到 = 整行卡死），改读 ci 根 update.json 的
 *                     `channels` 数组（github.com，与下载同域）。
 *   2026-10-11     用户「如果是 sbs 就只更新 arena takeover 的更新包，不要有选择；相同的 main
 *                     就只有 main，不要有选择的地方」→ **选择整个删掉**：chips 下标换算
 *                     （channelForChip）、channels 名单解析（parseChannels）、失败沿用旧名单
 *                     （fetchedOrCached）连同设置页那两排控件一起退役；源改由包的构建标识定死
 *                     （ownSlot）。本测试盯的是新的那套：
 *                       · 分支全名 → 短 id（必须与 scripts/branch_id.sh 一致，否则 App 找不到资产）
 *                       · 构建标识 → 本包自己的坑位 id（sbs 包只认造它的那条分支）
 *                       · 坑位直链拼装
 *
 * 跑法：bash scripts/run_tests.sh
 */
public class UpChTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    public static void main(String[] args) {
        // 1) 通道号清洗：只有 stable(0) / 分支(2) 两档；旧 1=dev 与越界一律当 stable
        check(UpCh.sanitize(0) == 0 && UpCh.sanitize(2) == 2, "0/2 原样通过");
        check(UpCh.sanitize(1) == 0, "旧 1=dev 档兜底为 stable（早先选过它的存量值不再当分支）");
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
        check(UpCh.branchId("arena/takeover-wordsprint").equals("arenatakeover"),
                "本轮的 arena/takeover-wordsprint → arenatakeover（= CI 坑位 update-arenatakeover.json）");
        check(UpCh.branchId("staging/foo").equals("staging-foo"), "staging/foo → staging-foo");
        check(UpCh.branchId("main").equals("main") && UpCh.branchId("dev-build").equals("dev-build"), "普通名字原样");
        check(UpCh.branchId(null).equals(""), "null → 空串");
        check(UpCh.branchId("arena/x_y").equals("arenaxy"), "非法字符剔干净（别把 _ 带进资产名）");

        // 4) 本包自己的坑位 id（「没有选择」的落点）：从构建标识第一段取，形如分支id·短sha[·sbs]
        check(UpCh.ownSlot("arenatakeover·1a2b3c4").equals("arenatakeover"), "测试包：分支id 在构建标识第一段");
        check(UpCh.ownSlot("arenatakeover·1a2b3c4·sbs").equals("arenatakeover"), "双装包（·sbs 尾巴）同样认第一段");
        check(UpCh.ownSlot("arena01a0c983·10c270e").equals("arena01a0c983"), "arena 分支同规则");
        check(UpCh.ownSlot("main").equals("main"), "没有分隔符就是它自己（本机手编的包）");
        check(UpCh.ownSlot("").equals("") && UpCh.ownSlot(null).equals(""),
                "没有构建标识 → 空串，调用方按「该源没有测试包」处理，绝不猜一个坑位出来");
        check(UpCh.ownSlot("../x·sha").equals("x"), "构建标识里的路径字符同样要剔干净");

        // 5) 直链拼装：预发布 Release ci 的资产 URL（302 到对象存储，App 的 HttpURLConnection 跟随）
        String base = "https://github.com/zhzx2026/wordsprint/releases/download/ci";
        check(UpCh.branchUpdateUrl(base, "arena01a0c983")
                .equals(base + "/update-arena01a0c983.json"), "标准 base → update-<id>.json");
        check(UpCh.branchUpdateUrl(base + "/", "arena01a0c983")
                .equals(base + "/update-arena01a0c983.json"), "带尾斜杠同样拼对");
        check(UpCh.branchUpdateUrl(base, "").equals("") && UpCh.branchUpdateUrl("", "x").equals("")
                && UpCh.branchUpdateUrl(null, "x").equals(""), "没分支 id / 没配 base → 空串（调用方按未配置处理）");

        // 6) 「sbs 包 → 自己的坑位」端到端：构建标识 → 坑位 id → 直链
        String stamp = "arenatakeover·1a2b3c4·sbs";
        check(UpCh.branchUpdateUrl(base, UpCh.ownSlot(stamp))
                .equals(base + "/update-arenatakeover.json"), "双装包自己找自己那条分支的 update.json");

        System.out.println("ALL UPCH TESTS PASS (" + checks + " checks)");
    }
}
