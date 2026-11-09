import com.hex91.file.ContentGrep;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * v1.81.0 内容搜索（grep）回归（桌面 JVM，纯 Java）。
 *
 * <p>盯的是「用户搜一句话却搜不到 / 搜一个目录就卡死」这类问题：
 * 行号是否准（CRLF、末尾无换行、超长行）、忽略大小写、中文、二进制是否被跳过、
 * 大文件限制、命中上限、片段截断。</p>
 */
public class ContentGrepTest {

    static int pass = 0, fail = 0;
    static final File ROOT = new File("build/greptest");

    static void ok(boolean v, String msg) {
        if (v) {
            pass++;
            System.out.println("PASS  " + msg);
        } else {
            fail++;
            System.out.println("FAIL  " + msg);
        }
    }

    static void eq(long expect, long got, String msg) {
        ok(expect == got, msg + "（期望 " + expect + "，实际 " + got + "）");
    }

    static void eqs(String expect, String got, String msg) {
        ok(expect == null ? got == null : expect.equals(got),
                msg + "（期望 " + expect + "，实际 " + got + "）");
    }

    static ContentGrep.Result grepText(String text, String q, boolean ic) throws Exception {
        InputStream in = new ByteArrayInputStream(text.getBytes("UTF-8"));
        return ContentGrep.grep(in, q, false, ic, 100);
    }

    public static void main(String[] args) throws Exception {
        del(ROOT);
        ROOT.mkdirs();

        // ---------------------------------------------------------- 基本命中 + 行号
        String t1 = "hello world\nsecond line\nthird HELLO\n";
        ContentGrep.Result r = grepText(t1, "hello", true);
        eq(2, r.hits, "忽略大小写：命中 2 行");
        eqs("hello world", r.first().text, "第一处：整行原样");
        eq(1, r.first().line, "第一处：行号 1");
        eq(3, r.matches.get(1).line, "第二处：行号 3");

        r = grepText(t1, "HELLO", false);
        eq(1, r.hits, "区分大小写：只命中 1 行");

        // ---------------------------------------------------------- 中文
        ContentGrep.Result r2 = grepText("第一行\n这是测试内容\n第三行\n", "测试内容", false);
        eq(1, r2.hits, "中文：命中 1 行");
        eqs("这是测试内容", r2.first().text, "中文：不乱码");

        // ---------------------------------------------------------- CRLF 行号
        ContentGrep.Result r3 = grepText("a\r\nb\r\nneedle\r\n", "needle", false);
        eq(3, r3.first().line, "CRLF：行号仍为 3（\\r 不额外计行）");
        eqs("needle", r3.first().text, "CRLF：行尾不带 \\r");

        // ---------------------------------------------------------- 末尾无换行
        ContentGrep.Result r4 = grepText("x\ny\nlast needle", "needle", false);
        eq(1, r4.hits, "末尾无换行：最后一行也能命中");
        eq(3, r4.first().line, "末尾无换行：行号 3");

        // ---------------------------------------------------------- 同一行多次命中只记一次
        ContentGrep.Result r5 = grepText("aa aa aa\n", "aa", false);
        eq(1, r5.hits, "同一行多次出现：只记 1 条命中");

        // ---------------------------------------------------------- 正则
        InputStream rin = new ByteArrayInputStream("id=42\nid=abc\n".getBytes("UTF-8"));
        ContentGrep.Result r6 = ContentGrep.grep(rin, "id=\\d+", true, false, 10);
        eq(1, r6.hits, "正则：只命中数字那行");

        // ---------------------------------------------------------- 命中上限
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 50; i++) many.append("hit ").append(i).append('\n');
        ContentGrep.Result r7 = grepText(many.toString(), "hit", false);
        eq(50, r7.matches.size(), "未达上限：50 条都收下");

        byte[] big = many.toString().getBytes("UTF-8");
        ContentGrep.Result r8 = ContentGrep.grep(new ByteArrayInputStream(big), "hit", false, false, 5);
        eq(5, r8.hits, "上限 5：只收 5 条");
        ok(r8.truncated, "上限 5：标记 truncated");

        // ---------------------------------------------------------- 二进制跳过
        byte[] bin = new byte[]{0x7F, 'E', 'L', 'F', 0, 0, 1, 2, 3};
        ok(ContentGrep.looksBinary(bin, bin.length), "二进制：含 0x00 判定为二进制");
        ContentGrep.Result r9 = ContentGrep.grep(new ByteArrayInputStream(bin), "ELF", false, false, 10);
        ok(r9.binary, "二进制：直接跳过并标记");
        eq(0, r9.hits, "二进制：0 命中");

        byte[] txt = "plain text\n".getBytes("UTF-8");
        ok(!ContentGrep.looksBinary(txt, txt.length), "文本：不误判为二进制");

        // ---------------------------------------------------------- 超长行不炸
        StringBuilder longLine = new StringBuilder("start ");
        for (int i = 0; i < 200000; i++) longLine.append('x');
        longLine.append(" needle end\nnext\n");
        ContentGrep.Result r10 = grepText(longLine.toString(), "needle", false);
        eq(0, r10.hits, "超长行（>8KB）：被截断，不命中后段关键词");
        eq(2, r10.lines, "超长行：行号计数仍正确");

        // ---------------------------------------------------------- 文件大小限制
        File f = new File(ROOT, "big.txt");
        FileOutputStream out = new FileOutputStream(f);
        out.write("needle\n".getBytes("UTF-8"));
        out.close();
        ContentGrep.Result r11 = ContentGrep.grepFile(f, "needle", false, false, 10, 1);
        ok(r11.tooBig, "超过 maxBytes：标记 tooBig");
        eq(0, r11.hits, "超过 maxBytes：不扫描");
        ContentGrep.Result r12 = ContentGrep.grepFile(f, "needle", false, false, 10, 1024);
        eq(1, r12.hits, "未超过 maxBytes：正常命中");

        // ---------------------------------------------------------- 文件真实读取
        File f2 = new File(ROOT, "real.txt");
        out = new FileOutputStream(f2);
        out.write("line1\n关键词在这里\nline3\n".getBytes("UTF-8"));
        out.close();
        ContentGrep.Result r13 = ContentGrep.grepFile(f2, "关键词", false, false, 10, 0);
        eq(1, r13.hits, "真实文件：命中中文关键词");
        eq(2, r13.first().line, "真实文件：行号 2");

        // ---------------------------------------------------------- 片段截断
        String longText = "prefix " + repeat('a', 300) + " needle " + repeat('b', 300);
        String sn = ContentGrep.snippet(longText, "needle", 80);
        ok(sn.length() <= 82, "片段：长度受限（含省略号）");
        ok(sn.contains("needle"), "片段：命中词保留在可见区域");

        eqs("abc", ContentGrep.snippet("  abc  ", null, 100), "片段：去首尾空白");
        eqs("a b", ContentGrep.snippet("a\tb", null, 100), "片段：控制字符转空格");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    static void del(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) del(k);
        }
        f.delete();
    }
}
