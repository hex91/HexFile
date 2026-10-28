import com.hex91.file.MarkdownRender;

public class MarkdownTest {
    public static void main(String[] a) {
        String md = "# 标题一\n\n普通段落 **粗体** 和 *斜体* 还有 `代码`。\n\n"
                + "> 这是引用\n\n"
                + "- 列表一\n- 列表二\n\n"
                + "1. 有序一\n2. 有序二\n\n"
                + "```\n代码块\n多行\n```\n\n"
                + "[链接](http://example.com)\n\n---\n";
        String html = MarkdownRender.render(md);
        int checks = 0, ok = 0;
        String[] must = {"<h1>标题一</h1>", "<strong>粗体</strong>", "<em>斜体</em>",
                "<code>代码</code>", "<blockquote>这是引用</blockquote>",
                "<ul><li>列表一</li><li>列表二</li></ul>",
                "<ol><li>有序一</li><li>有序二</li></ol>",
                "<pre><code>代码块\n多行\n</code></pre>",
                "<a href=\"http://example.com\">链接</a>", "<hr>"};
        for (String m : must) {
            checks++;
            boolean f = html.contains(m);
            if (f) ok++;
            System.out.println((f ? "OK  " : "MISS") + "  " + m);
        }
        System.out.println("\n" + ok + "/" + checks + (ok == checks ? "  ALL PASS" : "  FAILED"));
        if (ok != checks) System.exit(1);
    }
}
