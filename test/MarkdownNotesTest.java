import com.aidemo.wordsprint.MarkdownNotes;

public class MarkdownNotesTest {
    private static int checks;

    private static void has(String html, String expected, String why) {
        checks++;
        if (!html.contains(expected)) throw new AssertionError(why + "; missing: " + expected + "\n" + html);
    }

    private static void lacks(String html, String unexpected, String why) {
        checks++;
        if (html.contains(unexpected)) throw new AssertionError(why + "; found: " + unexpected + "\n" + html);
    }

    public static void main(String[] args) {
        String source = "# 更新说明\n\n"
                + "**修复重点**，*小改善*，`--help`\n\n"
                + "- 第一项\n- 第二项\n\n"
                + "1. 步骤一\n2. 步骤二\n\n"
                + "· 项目风格列表\n\n"
                + "> 备注引用\n\n"
                + "[查看详情](https://example.com/update?a=1&b=2)\n\n"
                + "```text\n<b>保持为普通文本</b>\n```\n\n"
                + "<script>alert(1)</script>\n"
                + "[不安全链接](javascript:alert(1))";
        String html = MarkdownNotes.toHtml(source);

        has(html, "<h1>更新说明</h1>", "一级标题应转换为标题");
        has(html, "<b>修复重点</b>", "粗体应转换");
        has(html, "<i>小改善</i>", "斜体应转换");
        has(html, "<tt>--help</tt>", "行内代码应转换");
        has(html, "<ul>", "无序列表应转换");
        has(html, "<li>第一项</li>", "无序列表内容应保留");
        has(html, "<ol>", "有序列表应转换");
        has(html, "<li>步骤二</li>", "有序列表内容应保留");
        has(html, "<li>项目风格列表</li>", "项目现有的中点列表应转换");
        has(html, "<blockquote>", "引用应转换");
        has(html, "<a href=\"https://example.com/update?a=1&amp;b=2\">查看详情</a>", "安全链接应转换并转义属性");
        has(html, "&lt;b&gt;保持为普通文本&lt;/b&gt;", "代码围栏里的 HTML 必须转义");
        has(html, "&lt;script&gt;alert(1)&lt;/script&gt;", "原始 HTML 必须转义");
        lacks(html, "<script>", "不能输出可执行 HTML 标签");
        lacks(html, "href=\"javascript:", "不能生成 javascript 链接");
        lacks(html, "<b>保持为普通文本</b>", "代码围栏内容不能被当作 HTML");

        checks++;
        if (!MarkdownNotes.toHtml(null).isEmpty()) throw new AssertionError("null 输入应返回空串");
        checks++;
        if (!MarkdownNotes.toHtml("  \n\t ").isEmpty()) throw new AssertionError("空白输入应返回空串");

        System.out.println("ALL MARKDOWN NOTES TESTS PASS (" + checks + " checks)");
    }
}
