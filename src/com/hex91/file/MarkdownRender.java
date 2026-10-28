package com.hex91.file;

/**
 * 轻量 Markdown → HTML 渲染器（v1.26），用于编辑器内「预览 Markdown」功能。
 *
 * 支持子集：标题(#~######)、分隔线(---/***)、引用(>)、无序列表(- * +)、有序列表(1.)、
 * 段落、代码块(``` 围栏)、行内代码(`)、粗体(**)、斜体(*)、链接([t](u))。
 * 不追求完整 CommonMark，够用即可；输出供 WebView 直接 loadData。
 */
public final class MarkdownRender {

    private MarkdownRender() {
    }

    private static final String CSS =
            "body{font-family:sans-serif;line-height:1.6;padding:14px;color:#222;font-size:15px}"
            + "code{background:#f3f3f3;padding:1px 5px;border-radius:3px;font-family:monospace;font-size:13px}"
            + "h1,h2,h3,h4,h5,h6{line-height:1.3}"
            + "h1{border-bottom:1px solid #eee;padding-bottom:4px}"
            + "blockquote{margin:8px 0;padding:6px 12px;background:#f7f7f7;border-left:4px solid #ccc;color:#555}"
            + "pre{background:#f3f3f3;padding:10px;border-radius:4px;overflow:auto}"
            + "pre code{background:none;padding:0}"
            + "hr{border:none;border-top:1px solid #ddd;margin:14px 0}"
            + "ul,ol{margin:6px 0;padding-left:22px}a{color:#1565C0}";

    public static String render(String md) {
        if (md == null) md = "";
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta charset='utf-8'><style>")
                .append(CSS).append("</style></head><body>");

        String[] lines = md.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            // 代码块围栏 ```
            if (line.trim().startsWith("```")) {
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append("\n");
                    i++;
                }
                i++; // 跳过结尾 ```
                html.append("<pre><code>").append(escape(code.toString())).append("</code></pre>");
                continue;
            }

            // 标题
            int h = 0;
            while (h < 6 && h < line.length() && line.charAt(h) == '#') { h++; }
            if (h > 0 && (h == line.length() || line.charAt(h) == ' ')) {
                html.append("<h").append(h).append(">")
                        .append(inline(line.substring(h).trim()))
                        .append("</h").append(h).append(">");
                i++;
                continue;
            }

            // 分隔线
            String t = line.trim();
            if (t.matches("^(-{3,}|\\*{3,}|_{3,})$")) {
                html.append("<hr>");
                i++;
                continue;
            }

            // 引用（连续 > 行合并）
            if (t.startsWith(">")) {
                StringBuilder q = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    q.append(lines[i].trim().substring(1).trim()).append("\n");
                    i++;
                }
                html.append("<blockquote>").append(inline(q.toString().trim())).append("</blockquote>");
                continue;
            }

            // 无序列表
            if (t.matches("^[-*+]\\s+.*")) {
                html.append("<ul>");
                while (i < lines.length && lines[i].trim().matches("^[-*+]\\s+.*")) {
                    String item = lines[i].trim().replaceFirst("^[-*+]\\s+", "");
                    html.append("<li>").append(inline(item)).append("</li>");
                    i++;
                }
                html.append("</ul>");
                continue;
            }

            // 有序列表
            if (t.matches("^\\d+\\.\\s+.*")) {
                html.append("<ol>");
                while (i < lines.length && lines[i].trim().matches("^\\d+\\.\\s+.*")) {
                    String item = lines[i].trim().replaceFirst("^\\d+\\.\\s+", "");
                    html.append("<li>").append(inline(item)).append("</li>");
                    i++;
                }
                html.append("</ol>");
                continue;
            }

            // 空行
            if (t.isEmpty()) { i++; continue; }

            // 段落（合并连续非空非特殊行）
            StringBuilder para = new StringBuilder();
            while (i < lines.length && !lines[i].trim().isEmpty()
                    && !lines[i].trim().startsWith("#")
                    && !lines[i].trim().startsWith(">")
                    && !lines[i].trim().startsWith("```")
                    && !lines[i].trim().matches("^[-*+]\\s+.*")
                    && !lines[i].trim().matches("^\\d+\\.\\s+.*")
                    && !lines[i].trim().matches("^(-{3,}|\\*{3,}|_{3,})$")) {
                if (para.length() > 0) para.append(" ");
                para.append(lines[i].trim());
                i++;
            }
            html.append("<p>").append(inline(para.toString())).append("</p>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    /** 行内格式：先转义，再处理 code/bold/italic/link */
    private static String inline(String s) {
        s = escape(s);
        s = s.replaceAll("`([^`]+)`", "<code>$1</code>");
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        s = s.replaceAll("\\*([^*]+)\\*", "<em>$1</em>");
        s = s.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
        return s;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
