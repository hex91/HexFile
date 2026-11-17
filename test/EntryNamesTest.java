import com.hex91.file.core.EntryNames;

/**
 * v2.13.1（F-11）：归档条目名映射用例。
 *
 * <p>重命名一个目录时，靠的是「把 {@code a} 及其子项改到 {@code x/y} 下」这一条字符串
 * 映射。它错了不会崩，只会悄悄改坏半个包 —— 所以这里把三类边界都钉住：</p>
 * <ol>
 *   <li>前缀必须按 <b>路径段</b> 匹配：{@code ab/1.txt} 不是 {@code a} 的子项，
 *       用 {@code startsWith(from)} 就会把它一起搬走；</li>
 *   <li>目录条目本身（带结尾 {@code /}）也要跟着改，否则原目录会留下一个空壳；</li>
 *   <li>用户输入要能容错（反斜杠、首尾斜杠、连续斜杠、{@code ./} 段），但规范化之后
 *       仍必须过 {@code safe()} —— 规范化不是安全校验。</li>
 * </ol>
 */
public class EntryNamesTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eq(String want, String got, String msg) {
        boolean v = want == null ? got == null : want.equals(got);
        ok(v, msg + (v ? "" : "（期望 " + want + "，实际 " + got + "）"));
    }

    public static void main(String[] args) {
        // ---- safe：写入侧穿越防护
        ok(EntryNames.safe("a/b.txt"), "safe 放行普通条目 a/b.txt");
        ok(EntryNames.safe("a\\b.txt"), "safe 认反斜杠当分隔符（不误判为绝对路径）");
        ok(!EntryNames.safe(null), "safe 拒绝 null");
        ok(!EntryNames.safe(""), "safe 拒绝空串");
        ok(!EntryNames.safe("/etc/passwd"), "safe 拒绝绝对路径");
        ok(!EntryNames.safe("C:/windows"), "safe 拒绝盘符");
        ok(!EntryNames.safe("a/../../b"), "safe 拒绝 .. 段");
        ok(!EntryNames.safe("a/b\0c"), "safe 拒绝 NUL");

        // ---- renamed：目录整体搬迁，但只搬自家子项
        eq("x/y", EntryNames.renamed("a", "a", "x/y"), "renamed 目录条目本身 → x/y");
        eq("x/y/", EntryNames.renamed("a/", "a", "x/y"), "renamed 带斜杠的目录条目 → x/y/");
        eq("x/y/1.txt", EntryNames.renamed("a/1.txt", "a", "x/y"), "renamed 子项 → x/y/1.txt");
        eq("x/y/deep/2.bin", EntryNames.renamed("a/deep/2.bin", "a", "x/y"),
                "renamed 深层子项 → x/y/deep/2.bin");
        eq("ab/1.txt", EntryNames.renamed("ab/1.txt", "a", "x/y"),
                "renamed 不碰同前缀的兄弟目录 ab/（按路径段匹配）");
        eq("a2", EntryNames.renamed("a2", "a", "x/y"), "renamed 不碰同前缀文件 a2");
        eq("b.txt", EntryNames.renamed("a.txt", "a.txt", "b.txt"), "renamed 单文件就是改名字");
        eq("sub/b.txt", EntryNames.renamed("a.txt", "a.txt", "sub/b.txt"),
                "renamed 单文件可以移到子目录");
        eq("a/1.txt", EntryNames.renamed("a/1.txt", "a/", "a"),
                "renamed 源名带斜杠、目标同名 → 原样（不误判成改名）");
        eq("x/1.txt", EntryNames.renamed("a/1.txt", "a/", "x/"),
                "renamed 源与目标都带斜杠时同样生效");
        eq("z/1.txt", EntryNames.renamed("z/1.txt", "a", "b"), "renamed 无关条目原样返回");
        eq("a/1.txt", EntryNames.renamed("a/1.txt", "", "b"), "renamed 空源名不做任何事");

        // ---- dir / parentOf / lastSeg
        eq("a/", EntryNames.dir("a"), "dir 补结尾斜杠");
        eq("a/b/", EntryNames.dir("a/b/"), "dir 已有斜杠不重复补");
        eq("a/b/", EntryNames.dir("/a/b"), "dir 去掉开头斜杠");
        eq("", EntryNames.dir(""), "dir 空串表示归档根");
        eq("", EntryNames.dir(null), "dir null → 空串");
        eq("a/b/", EntryNames.parentOf("a/b/c.txt"), "parentOf 深层 → a/b/");
        eq("", EntryNames.parentOf("c.txt"), "parentOf 顶层 → 空串");
        eq("", EntryNames.parentOf("a/"), "parentOf 目录条目 → 空串（父目录）");
        eq("c.txt", EntryNames.lastSeg("a/b/c.txt"), "lastSeg 深层 → c.txt");
        eq("b", EntryNames.lastSeg("a/b/"), "lastSeg 目录条目 → b");

        // ---- clean：用户输入容错
        eq("a/b/c", EntryNames.clean(" a\\b//c/ "), "clean 反斜杠 + 连续斜杠 + 首尾空白");
        eq("a", EntryNames.clean("/a/"), "clean 去首尾斜杠");
        eq("a/b", EntryNames.clean("./a/./b"), "clean 去掉 . 段");
        eq("", EntryNames.clean("  /  "), "clean 只剩斜杠 → 空串");
        ok(!EntryNames.safe(EntryNames.clean("../../etc")),
                "clean 之后仍要过 safe（规范化不是安全校验）");

        System.out.println("EntryNamesTest: " + pass + " PASS / " + fail + " FAIL");
        if (fail > 0) System.exit(1);
    }
}
