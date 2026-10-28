import com.hex91.file.SyntaxHighlight;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 桌面纯 Java 验证：用 SDK 的 android.jar 让 SyntaxHighlight 能加载，
 * 调用 debugPattern 强制编译每种语言的组合正则，并对样例文本跑一遍匹配循环，
 * 确认：(1) 正则能编译（运行时才不会崩溃）；(2) 匹配循环不抛异常、不空匹配死循环。
 */
public class HighlightTest {
    public static void main(String[] a) {
        int fail = 0;
        // 各语言样例片段
        String[] samples = {
            "smali",
            "xml",
            "json",
            "java",
            "python",
            "shell",
            "properties",
            "c",
            "cpp",
            "js",
            "markdown",
            "text"
        };
        String[] code = {
            ".method public foo()V\n  const-string v0, \"hi\"\n  # comment\n  invoke-virtual {v0}, Ljava/lang/Object;->toString()Ljava/lang/String;\n.end method",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n  <!-- note -->\n  <application android:label=\"App\"></application>\n</manifest>",
            "{\n  \"name\": \"MT\",\n  \"ver\": 1.25,\n  \"ok\": true,\n  \"null\": null\n}",
            "public class A {\n  // line\n  /* block */\n  private int x = 0x1F;\n  String s = \"str\";\n  @Override\n  void m() { return; }\n}",
            "def foo():\n  # c\n  x = 12\n  s = \"hi\"\n  if x > 0: print(x)\n",
            "#!/bin/sh\necho \"hi\"\nfor i in 1 2 3; do echo $i; done\n",
            "# comment\nkey1 = value1\nkey2: value2\n",
            "#include <stdio.h>\nint main() {\n  char c = 'a';\n  int n = 10;\n  /* blk */\n  return 0;\n}",
            "#include <iostream>\nint main() {\n  std::vector<int> v;\n  return 0;\n}",
            "function f() {\n  // c\n  let x = `tpl ${v}`;\n  const y = 0x10;\n  if (x) return y;\n}",
            "# Title\n\n> quote line\n\n- item one\n- item two\n\n**bold** and *italic* and `code`\n\n[link](http://x)\n\n---\n",
            "just plain text with no rules at all\nsecond line\n"
        };

        for (int i = 0; i < samples.length; i++) {
            String id = samples[i];
            String src = code[i];
            try {
                String patStr = SyntaxHighlight.debugPattern(id);
                if (patStr == null) { System.out.println("FAIL " + id + ": pattern null"); fail++; continue; }
                if (patStr.isEmpty()) { System.out.println("OK   " + id + "  (无规则，直接返回原文)"); continue; }
                // 复刻 highlightById 的匹配循环，验证不抛异常、无空匹配
                int flags = Pattern.DOTALL | Pattern.MULTILINE;
                Pattern p = Pattern.compile(patStr, flags);
                Matcher m = p.matcher(src);
                int pos = 0, colored = 0, loops = 0;
                while (m.find()) {
                    int s = m.start(), e = m.end();
                    if (s < pos) continue;
                    if (e <= s) { System.out.println("FAIL " + id + ": empty match at " + s); fail++; break; }
                    colored += (e - s);
                    pos = e;
                    if (++loops > src.length() + 5) { System.out.println("FAIL " + id + ": loop runaway"); fail++; break; }
                }
                System.out.println("OK   " + id + "  patternLen=" + patStr.length()
                        + "  colored=" + colored + "/" + src.length());
            } catch (Throwable t) {
                System.out.println("FAIL " + id + ": " + t);
                fail++;
            }
        }

        // MarkdownRender 不在 android 依赖里，单独验证语法清单
        List<SyntaxHighlight.LangInfo> langs = SyntaxHighlight.listLanguages();
        System.out.println("Langs(" + langs.size() + "): " + langs);

        System.out.println(fail == 0 ? "\nALL PASS" : "\n" + fail + " FAILED");
        if (fail > 0) System.exit(1);
    }
}
