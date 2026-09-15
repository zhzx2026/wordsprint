import com.aidemo.wordsprint.Profiles;

/**
 * 主机侧：多用户档案的「隔离算术」。
 *
 * 背景：用户 2026-09-15 问「多个用户档案真的隔开了吗」。查下来真有两处没隔开：
 *   ① 切档案/删档案时的顺序反了 —— 先把 activeId 换成新档案、再调 DiaryStore.forget()，
 *      而 forget() 内部会 save()，于是上一个人的日记（热力图/目标/连续天数）被写进新档案的槽里；
 *   ② 删「遗留档案」（升级前那份数据，id=0，键是**无前缀**的）时只按 "u0_" 前缀挑键 → 一条没删，
 *      而且这份数据还能被 orphanLegacy() 再次捡回来给新档案。
 * 这里把「谁的数据归谁」写成断言：{@link Profiles#ownedBy} 是删档案唯一依据。
 *
 * 跑法：bash scripts/run_tests.sh
 */
public class ProfilesTest {

    static int checks = 0;
    static void check(boolean c, String what) { if (!c) throw new RuntimeException("FAIL: " + what); checks++; }

    public static void main(String[] args) {
        // ---------------- 列表本身：编码 / 解析 ----------------
        Profiles ps = new Profiles();
        check(ps.isEmpty() && ps.nextId().equals("1"), "空列表：下一个 id 从 1 开始");
        Profiles.P a = ps.add("小明", true);
        check(a.id.equals("0"), "第一个档案 + 本机有老数据 → 占遗留命名空间");
        Profiles.P b = ps.add("小红", true);
        check(b.id.equals("1"), "已经有人占了遗留位 → 第二个档案排 1");
        check(ps.nextId().equals("2"), "下一个 id = 最大 + 1");
        check(ps.active("9") == ps.list.get(0), "id 不存在时退回第一个档案");
        check(ps.byId("1").name.equals("小红"), "byId 按 id 找");
        check(ps.indexOf("1") == 1, "indexOf");
        check(ps.remove("0") && ps.list.size() == 1, "两个档案时能删掉遗留那个");
        check(!ps.remove("1"), "只剩一个档案时不许删");
        check(!ps.remove("42"), "删不存在的档案返回 false");
        Profiles.P c1 = ps.add("小刚", false);
        check(c1.id.equals("2"), "老档案删掉后 id 不回收（避免和历史数据撞名）");

        check(Profiles.clean("  阿  明  ").equals("阿  明"), "名字首尾空格清掉，中间保留");
        check(Profiles.clean("含\t制表符\n换行").indexOf('\t') < 0, "名字里的制表符换成空格（否则编码串行）");
        check(Profiles.clean("").equals("我"), "空名字兜底");
        check(Profiles.clean(null).equals("我"), "null 名字兜底");
        check(Profiles.clean("12345678901234567890").length() == 12, "名字截到 12 字");

        Profiles enc = new Profiles();
        enc.add("甲", true);
        enc.add("乙\t丙", false);
        String text = enc.encode();
        check(text.startsWith("v1\n"), "编码带版本头");
        Profiles back = Profiles.decode(text);
        check(back.list.size() == 2 && back.list.get(0).id.equals("0") && back.list.get(1).name.equals("乙 丙"),
                "编码→解析 往返不丢");
        check(Profiles.decode(null).isEmpty(), "空文本 → 空列表");
        check(Profiles.decode("垃圾\n1\t只有名字\n2\t好\t1234\t多出来的列").list.size() == 2,
                "坏行跳过、多列容错");
        Profiles dup = Profiles.decode("v1\n1\t甲\t1\n1\t乙\t2\n");
        check(dup.list.size() == 1 && dup.list.get(0).name.equals("甲"), "重复 id 只留第一条");
        check(back.byId("0").created <= System.currentTimeMillis(), "创建时间读得回来");

        // ---------------- 键归属：删档案按它挑，绝不能连坐 ----------------
        // 非遗留档案：u<id>_ 前缀就是它的
        check(Profiles.ownedBy("1", "u1_b_PEP3A_p"), "档案 1 的书进度归它");
        check(Profiles.ownedBy("1", "u1_diary_v1"), "档案 1 的日记归它");
        check(Profiles.ownedBy("1", "u1_d_20260915"), "档案 1 的每日计数归它");
        check(Profiles.ownedBy("1", "u1_随便新加的键"), "带 u1_ 前缀的新键也算它的（将来加字段不会漏删）");
        check(!Profiles.ownedBy("1", "b_PEP3A_p"), "无前缀的老键不是档案 1 的");
        check(!Profiles.ownedBy("1", "u11_b_x_p"), "u11_ 不能被 u1_ 前缀吞掉");
        check(!Profiles.ownedBy("1", "u2_b_x_p"), "档案 2 的数据不能算在档案 1 头上");
        check(!Profiles.ownedBy("2", "u1_b_x_p"), "反过来也一样");

        // 遗留档案（id=0）：无前缀的学习数据键归它，全局设置绝不归它
        String[] mine = {"b_PEP3A_p", "b_PEP3A_wc", "d_20260915", "s_speak", "s_sound",
                "diary_v1", "fav_v1", "g_goal", "g_ges", "g_size", "g_heat_span"};
        for (String k : mine) check(Profiles.ownedBy("0", k), "遗留命名空间里的学习数据键：" + k);
        String[] globals = {"p_profiles", "p_active", "g_night", "g_skin", "g_font", "g_scale",
                "g_goal_mode", "u_url", "u_ch", "u_auto", "u_last", "u_seen", "u1_b_PEP3A_p"};
        for (String k : globals) check(!Profiles.ownedBy("0", k), "全局设置 / 别人的数据不能跟着遗留档案一起删：" + k);
        check(!Profiles.ownedBy("0", null) && !Profiles.ownedBy(null, "b_x_p"), "null 参数不炸");

        // 学习数据键清单本身
        check(Profiles.isProfileKey("b_x_p") && Profiles.isProfileKey("d_1") && Profiles.isProfileKey("s_sound"),
                "isProfileKey：b_ / d_ / s_ 家族");
        check(Profiles.isProfileKey("diary_v1") && Profiles.isProfileKey("fav_v1"), "isProfileKey：日记与收藏");
        check(!Profiles.isProfileKey("g_skin") && !Profiles.isProfileKey("p_profiles"), "isProfileKey：全局键不算学习数据");
        check(!Profiles.isProfileKey("") && !Profiles.isProfileKey(null), "isProfileKey：空值不炸");

        System.out.println("ALL PROFILES TESTS PASS (" + checks + " checks) —— 档案隔离：数据归属、删号清理、全局设置不动");
    }
}
