package com.hex91.file;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 文本行级对比（v1.17.0）：
 *  - LCS 动态规划（先裁剪公共前缀/后缀，中间段 DP；超大规模退化为整段替换）
 *  - 输出扁平 op 列表：EQ / DEL / ADD，带两侧行号，UI 直接渲染
 *  - 大小保护：单侧超 20000 行或 4MB 直接拒绝（防 OOM/卡死）
 */
public final class TextDiffUtil {

    public static final int EQ = 0, DEL = 1, ADD = 2;

    public static final int MAX_LINES = 20000;
    public static final long MAX_BYTES = 4L * 1024 * 1024;

    /** 一行 diff 结果 */
    public static class Line {
        public int type;
        public String text;
        public int aNo;   // A 侧行号（1 起；ADD 行为 0）
        public int bNo;   // B 侧行号（1 起；DEL 行为 0）
        Line(int type, String text, int aNo, int bNo) {
            this.type = type; this.text = text; this.aNo = aNo; this.bNo = bNo;
        }
    }

    public static class Result {
        public List<Line> lines = new ArrayList<Line>();
        public int nAdd, nDel;
        public int totalA, totalB;
        public boolean tooBig = false;     // 超限未比对
        public String encoding = "UTF-8";
        public String error = null;
    }

    private TextDiffUtil() {}

    /** 按行读入（保留空行；兼容 \n 与 \r\n） */
    public static List<String> readLines(File f, String enc) throws IOException {
        List<String> out = new ArrayList<String>();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), enc), 65536);
        String line;
        while ((line = br.readLine()) != null) out.add(line);
        br.close();
        return out;
    }

    /** 探测文本编码（复用 TextEditorActivity 的思路：UTF-8 严格 → GBK） */
    public static String detectEncoding(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            int n = (int) Math.min(8192L, f.length());
            byte[] head = new byte[Math.max(n, 0)];
            if (n > 0) in.read(head);
            if (head.length >= 2) {
                int b0 = head[0] & 0xFF, b1 = head[1] & 0xFF;
                if (b0 == 0xFF && b1 == 0xFE) return "UTF-16LE";
                if (b0 == 0xFE && b1 == 0xFF) return "UTF-16BE";
            }
            try {
                java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(head));
                return "UTF-8";
            } catch (java.nio.charset.CharacterCodingException e) {
                return "GBK";
            }
        } catch (Exception e) {
            return "UTF-8";
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    /** 对比两个文本文件（耗时操作，放后台线程） */
    public static Result diffFiles(File a, File b) {
        Result r = new Result();
        if (a.length() > MAX_BYTES || b.length() > MAX_BYTES) {
            r.tooBig = true;
            r.error = "文件超过 " + (MAX_BYTES / 1048576) + "MB，不做逐行对比";
            return r;
        }
        try {
            String encA = detectEncoding(a), encB = detectEncoding(b);
            // 编码取并集偏好：任一是 UTF-16 就用各自的，混用按 UTF-8 读 GBK 会乱码——
            // 行对比按文本进行，两侧编码不同时统一按各自编码解码即可。
            List<String> la = readLines(a, encA);
            List<String> lb = readLines(b, encB);
            r.encoding = encA.equals(encB) ? encA : encA + "/" + encB;
            if (la.size() > MAX_LINES || lb.size() > MAX_LINES) {
                r.tooBig = true;
                r.error = "行数超过 " + MAX_LINES + "，不做逐行对比";
                return r;
            }
            r.totalA = la.size();
            r.totalB = lb.size();
            diff(la, lb, r);
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return r;
    }

    /** 行列表 diff → 扁平 op 列表 */
    public static void diff(List<String> a, List<String> b, Result r) {
        // 1) 裁剪公共前缀
        int pre = 0;
        int minLen = Math.min(a.size(), b.size());
        while (pre < minLen && eq(a.get(pre), b.get(pre))) pre++;
        // 2) 裁剪公共后缀
        int suf = 0;
        while (suf < minLen - pre
                && eq(a.get(a.size() - 1 - suf), b.get(b.size() - 1 - suf))) suf++;
        // 3) 前缀
        for (int i = 0; i < pre; i++)
            r.lines.add(new Line(EQ, a.get(i), i + 1, i + 1));
        // 4) 中间段
        List<String> ma = a.subList(pre, a.size() - suf);
        List<String> mb = b.subList(pre, b.size() - suf);
        diffMiddle(ma, mb, pre, r);
        // 5) 后缀
        for (int i = 0; i < suf; i++)
            r.lines.add(new Line(EQ, a.get(a.size() - suf + i),
                    a.size() - suf + i + 1, b.size() - suf + i + 1));
    }

    private static boolean eq(String x, String y) {
        return x == null ? y == null : x.equals(y);
    }

    /** 中间段：小规模 DP LCS，大规模退化为「全删全增」 */
    private static void diffMiddle(List<String> a, List<String> b, int offA, Result r) {
        int n = a.size(), m = b.size();
        if (n == 0 && m == 0) return;
        // DP 规模上限：n*m ≤ 400 万（约 2000×2000），int[][] 16MB 可接受
        if ((long) n * m > 4000000L) {
            for (int i = 0; i < n; i++)
                r.lines.add(new Line(DEL, a.get(i), offA + i + 1, 0));
            for (int j = 0; j < m; j++)
                r.lines.add(new Line(ADD, b.get(j), 0, offA + j + 1));
            r.nDel += n; r.nAdd += m;
            return;
        }
        // LCS 长度表
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                dp[i][j] = eq(a.get(i), b.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
        // 回溯生成 op（i 前进 = A 行，j 前进 = B 行）
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (eq(a.get(i), b.get(j))) {
                r.lines.add(new Line(EQ, a.get(i), offA + i + 1, offA + j + 1));
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                r.lines.add(new Line(DEL, a.get(i), offA + i + 1, 0));
                r.nDel++;
                i++;
            } else {
                r.lines.add(new Line(ADD, b.get(j), 0, offA + j + 1));
                r.nAdd++;
                j++;
            }
        }
        while (i < n) {
            r.lines.add(new Line(DEL, a.get(i), offA + i + 1, 0));
            r.nDel++; i++;
        }
        while (j < m) {
            r.lines.add(new Line(ADD, b.get(j), 0, offA + j + 1));
            r.nAdd++; j++;
        }
    }

    /** 生成可复制/分享的文本报告（unified 风格） */
    public static String report(Result r, String nameA, String nameB) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(nameA).append('\n');
        sb.append("+++ ").append(nameB).append('\n');
        if (r.tooBig || r.error != null) {
            sb.append("!! ").append(r.error).append('\n');
            return sb.toString();
        }
        sb.append("@@ 共 ").append(r.totalA).append(" 行 → ").append(r.totalB)
          .append(" 行，新增 ").append(r.nAdd).append("，删除 ").append(r.nDel)
          .append(" @@\n");
        for (Line l : r.lines) {
            if (l.type == EQ) continue;
            sb.append(l.type == ADD ? "+ " : "- ");
            if (l.type == ADD) sb.append(l.bNo).append('\t');
            else sb.append(l.aNo).append('\t');
            sb.append(l.text).append('\n');
        }
        return sb.toString();
    }
}
