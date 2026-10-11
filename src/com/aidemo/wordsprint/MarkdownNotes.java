package com.aidemo.wordsprint;

/**
 * 将更新说明里的常用 Markdown 转成安全的 HTML 片段，供 Android Html.fromHtml 显示。
 *
 * 纯 Java：不用额外 Markdown 依赖，因此主机侧也能测试。
 * 支持标题、段落、无序/有序列表、引用、分隔线、粗体、斜体、行内代码、围栏代码和 http(s)/mailto 链接。
 * 原始 HTML 会先按普通文字转义；链接只允许 http(s) 与 mailto，避免远端说明注入任意 HTML/协议。
 */
public final class MarkdownNotes {
    private MarkdownNotes() {}

    public static String toHtml(String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) return "";

        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder html = new StringBuilder(normalized.length() + 128);
        StringBuilder paragraph = new StringBuilder();
        StringBuilder fenced = new StringBuilder();
        String list = "";
        boolean quote = false;
        boolean inFence = false;

        for (String raw : lines) {
            String line = raw.trim();

            if (line.startsWith("```")) {
                flushParagraph(html, paragraph);
                if (!list.isEmpty()) {
                    html.append("</").append(list).append('>');
                    list = "";
                }
                if (quote) {
                    html.append("</blockquote>");
                    quote = false;
                }
                if (inFence) {
                    html.append("<pre><tt>").append(escape(fenced.toString())).append("</tt></pre>");
                    fenced.setLength(0);
                    inFence = false;
                } else {
                    inFence = true;
                }
                continue;
            }
            if (inFence) {
                if (fenced.length() > 0) fenced.append('\n');
                fenced.append(raw);
                continue;
            }

            if (line.isEmpty()) {
                flushParagraph(html, paragraph);
                if (!list.isEmpty()) {
                    html.append("</").append(list).append('>');
                    list = "";
                }
                if (quote) {
                    html.append("</blockquote>");
                    quote = false;
                }
                continue;
            }

            int heading = headingLevel(line);
            if (heading > 0) {
                flushParagraph(html, paragraph);
                if (!list.isEmpty()) {
                    html.append("</").append(list).append('>');
                    list = "";
                }
                if (quote) {
                    html.append("</blockquote>");
                    quote = false;
                }
                html.append("<h").append(heading).append('>')
                        .append(inline(line.substring(heading + 1).trim(), 0))
                        .append("</h").append(heading).append('>');
                continue;
            }

            if (isRule(line)) {
                flushParagraph(html, paragraph);
                if (!list.isEmpty()) {
                    html.append("</").append(list).append('>');
                    list = "";
                }
                if (quote) {
                    html.append("</blockquote>");
                    quote = false;
                }
                html.append("<hr>");
                continue;
            }

            String item = listItem(line);
            String itemType = listItemType(line);
            if (item != null) {
                flushParagraph(html, paragraph);
                if (quote) {
                    html.append("</blockquote>");
                    quote = false;
                }
                if (!itemType.equals(list)) {
                    if (!list.isEmpty()) html.append("</").append(list).append('>');
                    list = itemType;
                    html.append('<').append(list).append('>');
                }
                html.append("<li>").append(inline(item, 0)).append("</li>");
                continue;
            }

            if (line.startsWith(">")) {
                flushParagraph(html, paragraph);
                if (!list.isEmpty()) {
                    html.append("</").append(list).append('>');
                    list = "";
                }
                if (!quote) {
                    html.append("<blockquote>");
                    quote = true;
                }
                String quoted = line.substring(1).trim();
                html.append("<p>").append(inline(quoted, 0)).append("</p>");
                continue;
            }

            if (quote) {
                html.append("</blockquote>");
                quote = false;
            }
            if (!list.isEmpty()) {
                html.append("</").append(list).append('>');
                list = "";
            }
            if (paragraph.length() > 0) paragraph.append(' '); // 普通换行按 Markdown 的软换行处理
            paragraph.append(line);
        }

        if (inFence) html.append("<pre><tt>").append(escape(fenced.toString())).append("</tt></pre>");
        flushParagraph(html, paragraph);
        if (!list.isEmpty()) html.append("</").append(list).append('>');
        if (quote) html.append("</blockquote>");
        return html.toString();
    }

    private static void flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.length() == 0) return;
        html.append("<p>").append(inline(paragraph.toString(), 0)).append("</p>");
        paragraph.setLength(0);
    }

    private static int headingLevel(String line) {
        int n = 0;
        while (n < line.length() && n < 6 && line.charAt(n) == '#') n++;
        return n > 0 && n < line.length() && Character.isWhitespace(line.charAt(n)) ? n : 0;
    }

    private static boolean isRule(String line) {
        String s = line.replace(" ", "").replace("\t", "");
        if (s.length() < 3) return false;
        char c = s.charAt(0);
        if (c != '-' && c != '*' && c != '_') return false;
        for (int i = 1; i < s.length(); i++) if (s.charAt(i) != c) return false;
        return true;
    }

    private static String listItemType(String line) {
        if (line.matches("^(?:[-*+]|·)\\s+.*$")) return "ul";
        if (line.matches("^\\d+[.)]\\s+.*$")) return "ol";
        return "";
    }

    private static String listItem(String line) {
        if (line.matches("^(?:[-*+]|·)\\s+.*$")) return line.replaceFirst("^(?:[-*+]|·)\\s+", "");
        if (line.matches("^\\d+[.)]\\s+.*$")) return line.replaceFirst("^\\d+[.)]\\s+", "");
        return null;
    }

    /** Inline parser works on raw text and escapes every literal character before emitting HTML. */
    private static String inline(String s, int depth) {
        if (depth > 8) return escape(s);
        StringBuilder out = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length();) {
            if (s.charAt(i) == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    out.append("<tt>").append(escape(s.substring(i + 1, end))).append("</tt>");
                    i = end + 1;
                    continue;
                }
            }
            if (starts(s, i, "**") || starts(s, i, "__")) {
                String mark = s.substring(i, i + 2);
                int end = s.indexOf(mark, i + 2);
                if (end > i + 2) {
                    out.append("<b>").append(inline(s.substring(i + 2, end), depth + 1)).append("</b>");
                    i = end + 2;
                    continue;
                }
            }
            if (starts(s, i, "~~")) {
                int end = s.indexOf("~~", i + 2);
                if (end > i + 2) {
                    out.append("<strike>").append(inline(s.substring(i + 2, end), depth + 1)).append("</strike>");
                    i = end + 2;
                    continue;
                }
            }
            if (s.charAt(i) == '*' && !starts(s, i, "**")) {
                int end = s.indexOf('*', i + 1);
                if (end > i + 1) {
                    out.append("<i>").append(inline(s.substring(i + 1, end), depth + 1)).append("</i>");
                    i = end + 1;
                    continue;
                }
            }
            if (s.charAt(i) == '[') {
                int closeLabel = s.indexOf(']', i + 1);
                if (closeLabel > i + 1 && closeLabel + 1 < s.length() && s.charAt(closeLabel + 1) == '(') {
                    int closeUrl = s.indexOf(')', closeLabel + 2);
                    if (closeUrl > closeLabel + 2) {
                        String url = s.substring(closeLabel + 2, closeUrl).trim();
                        if (safeUrl(url)) {
                            out.append("<a href=\"").append(escape(url)).append("\">")
                                    .append(inline(s.substring(i + 1, closeLabel), depth + 1)).append("</a>");
                            i = closeUrl + 1;
                            continue;
                        }
                    }
                }
            }
            out.append(escapeChar(s.charAt(i++)));
        }
        return out.toString();
    }

    private static boolean starts(String s, int at, String token) {
        return at + token.length() <= s.length() && s.regionMatches(at, token, 0, token.length());
    }

    private static boolean safeUrl(String url) {
        return url.matches("(?i)^https?://[^\\s<>\\\"']+$")
                || url.matches("(?i)^mailto:[^\\s<>\\\"']+$");
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) out.append(escapeChar(s.charAt(i)));
        return out.toString();
    }

    private static String escapeChar(char c) {
        switch (c) {
            case '&': return "&amp;";
            case '<': return "&lt;";
            case '>': return "&gt;";
            case '\"': return "&quot;";
            case '\'': return "&#39;";
            default: return String.valueOf(c);
        }
    }
}
