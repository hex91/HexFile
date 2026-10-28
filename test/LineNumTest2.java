import com.hex91.file.MtTextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v1.27 行号/换行符逻辑桌面测试（纯 Java，与手机端同构）。
 *
 * 覆盖：
 *  1. countBreaks：LF / CRLF / CR / 混合
 *  2. buildVisualMap：视觉行→逻辑行号（模拟 Android Layout 切分：'\n'、'\r'、"\r\n" 硬换行 + 折行）
 *  3. 编号连续性：每个逻辑行有且仅有 1 个编号，无缺号无重号
 *  4. offsetOfLogicalLine ↔ logicalLineOf 互逆（跳转一致性）
 *  5. splitLines 与 String.split("\n",-1) 对 LF 文本一致，且兼容 CR/CRLF
 */
public class LineNumTest2 {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    /** 模拟 Android Layout 的视觉行切分：'\n'、'\r'、"\r\n" 硬换行 + 按 width 折行 */
    static int[] simulateLineStarts(String text, int width) {
        List<Integer> starts = new ArrayList<Integer>();
        starts.add(0);
        int i = 0, len = text.length(), lineLen = 0;
        while (i < len) {
            char c = text.charAt(i);
            if (c == '\n') {
                i++;
                starts.add(i);
                lineLen = 0;
                continue;
            }
            if (c == '\r') {
                i += (i + 1 < len && text.charAt(i + 1) == '\n') ? 2 : 1;
                starts.add(i);
                lineLen = 0;
                continue;
            }
            lineLen++;
            if (lineLen >= width) {
                starts.add(i + 1);
                lineLen = 0;
            }
            i++;
        }
        int[] out = new int[starts.size()];
        for (int k = 0; k < out.length; k++) out[k] = starts.get(k);
        return out;
    }

    /** 断言 map 的编号性质：去重后恰好 1..N 连续，且每个号出现在连续视觉行的首个 */
    static boolean mapWellFormed(int[] map) {
        int n = map.length;
        if (n == 0) return false;
        // 每个逻辑行首个视觉行画号：map[i] > map[i-1]（严格递增 1）或 i==0
        for (int i = 1; i < n; i++) {
            if (map[i] != map[i - 1] && map[i] != map[i - 1] + 1) return false;
        }
        // 去重后 1..N
        int[] uniq = new int[n];
        int u = 0;
        for (int i = 0; i < n; i++) {
            if (i == 0 || map[i] != map[i - 1]) uniq[u++] = map[i];
        }
        for (int i = 0; i < u; i++) if (uniq[i] != i + 1) return false;
        return true;
    }

    public static void main(String[] args) {
        // ---------- 1. countBreaks ----------
        check("countBreaks LF", MtTextUtils.countBreaks("a\nb\nc", 0, 5) == 2);
        check("countBreaks CRLF", MtTextUtils.countBreaks("a\r\nb\r\nc", 0, 7) == 2);
        check("countBreaks CR", MtTextUtils.countBreaks("a\rb\rc", 0, 5) == 2);
        check("countBreaks 混合", MtTextUtils.countBreaks("a\r\nb\rc\nd", 0, 8) == 3);
        check("countBreaks 区间", MtTextUtils.countBreaks("a\nb\nc", 2, 5) == 1);
        check("countBreaks 空", MtTextUtils.countBreaks("", 0, 0) == 0);

        // ---------- 2. buildVisualMap 编号性质 ----------
        String[] cases = {
                "a\nb\nc",
                "a\r\nb\r\nc",
                "a\rb\rc",                       // 纯 CR（旧实现漏号的场景）
                "a\r\nb\rc\nd",                   // 混合
                "aaaa\nbbbb",                     // 折行
                "aaaaa\r\nbb\r\nccccc\ndd",       // 混合+折行
                "x",                              // 单行无换行
                "\n\n\n",                         // 连续空行
                "a\rb",                           // CR 结尾
                "a\r\n",                          // CRLF 结尾
                "a\n",                            // LF 结尾
                "",                               // 空文本
        };
        for (int w = 2; w <= 8; w += 2) {
            for (String t : cases) {
                int[] starts = simulateLineStarts(t, w);
                int[] map = MtTextUtils.buildVisualMap(t, starts);
                check("map[" + w + "] " + esc(t), mapWellFormed(map));
            }
        }

        // ---------- 3. 旧 bug 回归：纯 CR 文本每个逻辑行都要有编号 ----------
        {
            String t = "a\rb\rc\rd";
            int[] starts = simulateLineStarts(t, 80);
            int[] map = MtTextUtils.buildVisualMap(t, starts);
            check("纯CR四行编号", map.length == 4 && map[0] == 1 && map[1] == 2
                    && map[2] == 3 && map[3] == 4);
        }

        // ---------- 4. 折行续行不新增编号 ----------
        {
            String t = "aaaaaaaaaa"; // width 3 → 4 个视觉行，1 个逻辑行
            int[] starts = simulateLineStarts(t, 3);
            int[] map = MtTextUtils.buildVisualMap(t, starts);
            check("折行同号", map.length == 4 && map[0] == 1 && map[1] == 1
                    && map[2] == 1 && map[3] == 1);
        }

        // ---------- 5. offsetOfLogicalLine ↔ logicalLineOf 互逆 ----------
        {
            String[] ts = {
                    "a\nb\nc\nd",
                    "a\r\nb\r\nc",
                    "a\rb\rc\rd\re",
                    "l1\r\nl2\rl3\nl4\r\nl5",
                    "x",
                    "",
                    "\n\n\n",
                    "aa\r\nbb\rcc\ndd\ree",
            };
            boolean allOk = true;
            for (String t : ts) {
                int total = MtTextUtils.countBreaks(t, 0, t.length()) + 1;
                for (int ln = 1; ln <= total; ln++) {
                    int off = MtTextUtils.offsetOfLogicalLine(t, ln);
                    int back = MtTextUtils.logicalLineOf(t, off);
                    if (back != ln) { allOk = false; System.out.println("  mismatch: " + esc(t)
                            + " line=" + ln + " off=" + off + " back=" + back); }
                }
            }
            check("跳转互逆", allOk);
        }

        // ---------- 6. splitLines ----------
        {
            String lf = "a\nbb\n\nccc";
            check("splitLines 对齐 split", Arrays.equals(
                    MtTextUtils.splitLines(lf), lf.split("\n", -1)));
            check("splitLines CRLF", Arrays.equals(
                    MtTextUtils.splitLines("a\r\nbb\r\nccc"), new String[]{"a", "bb", "ccc"}));
            check("splitLines CR", Arrays.equals(
                    MtTextUtils.splitLines("a\rbb\rccc"), new String[]{"a", "bb", "ccc"}));
            check("splitLines 尾部空行", Arrays.equals(
                    MtTextUtils.splitLines("a\n"), new String[]{"a", ""}));
            check("splitLines CRLF 尾部", Arrays.equals(
                    MtTextUtils.splitLines("a\r\n"), new String[]{"a", ""}));
            check("splitLines 空串", Arrays.equals(
                    MtTextUtils.splitLines(""), new String[]{""}));
        }

        // ---------- 7. 用户场景：混合二进制-ish 文本，编号连续 ----------
        {
            StringBuilder sb = new StringBuilder();
            sb.append("BIN\u0001\u0002\r\n");      // CRLF
            sb.append("head\r");                     // 单独 CR
            sb.append("\r\n");                       // CRLF
            sb.append("文本行\n");                   // LF
            sb.append("tail\r\n");                   // CRLF
            String t = sb.toString();
            int total = MtTextUtils.countBreaks(t, 0, t.length()) + 1;
            int[] starts = simulateLineStarts(t, 80);
            int[] map = MtTextUtils.buildVisualMap(t, starts);
            // BIN.. / head / (空) / 文本行 / tail / (尾部空) = 6 个逻辑行
            check("用户场景编号连续", mapWellFormed(map) && total == 6);
            // 跳转到每个逻辑行，回读行号一致
            boolean ok = true;
            for (int ln = 1; ln <= total; ln++) {
                if (MtTextUtils.logicalLineOf(t,
                        MtTextUtils.offsetOfLogicalLine(t, ln)) != ln) ok = false;
            }
            check("用户场景跳转一致", ok);
        }

        System.out.println();
        System.out.println("RESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static String esc(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }
}
