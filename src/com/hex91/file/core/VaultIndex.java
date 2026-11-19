package com.hex91.file.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 隐私空间索引（v2.18.0，F-22）。纯 JDK。
 *
 * <p>索引是<b>唯一的目录信息</b>：文件名、大小、原始时间都在这里，密文文件名只是随机 id。
 * 因此它有两条硬要求：</p>
 * <ol>
 *   <li><b>能被解出明文名字</b>——索引整体走 {@link VaultCrypto#seal} 加密，光看索引文件
 *       看不到任何文件名（连「有几个文件」都只能靠长度猜）；</li>
 *   <li><b>坏一行不能带走整张表</b>——文本行式存储，解析时逐行校验（id 必须是 32 位小写十六进制，
 *       大小/时间必须是非负数），坏行跳过并在 {@link #decode} 的返回值里计数，
 *       交给上层提示「有 N 条记录读不出来」，而不是整个隐私空间打不开。</li>
 * </ol>
 *
 * <p>格式（UTF-8，制表符分隔，{@code \ \t \n \r} 四类转义）：</p>
 * <pre>
 * HFVIDX1
 * &lt;id 32hex&gt;\t&lt;size&gt;\t&lt;mtime&gt;\t&lt;addedAt&gt;\t&lt;name&gt;
 * </pre>
 */
public final class VaultIndex {

    private static final String MAGIC = "HFVIDX1";

    /** 一条记录：id + 原始文件名 + 原始大小/时间 + 入库时间 */
    public static final class Entry {
        public String id;
        public String name;
        public long size;
        public long mtime;
        public long addedAt;

        public Entry() {}

        public Entry(String id, String name, long size, long mtime, long addedAt) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.mtime = mtime;
            this.addedAt = addedAt;
        }

        public Entry copy() {
            return new Entry(id, name, size, mtime, addedAt);
        }
    }

    /** 解析结果：entries 是能用的部分，bad 是被跳过的行数 */
    public static final class Read {
        public final List<Entry> entries;
        public final int bad;

        Read(List<Entry> entries, int bad) {
            this.entries = entries;
            this.bad = bad;
        }
    }

    private VaultIndex() {}

    /** 生成 32 位小写十六进制 id（16 字节随机） */
    public static String newId() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }

    /**
     * id 的严格校验：必须正好 32 位小写十六进制。
     *
     * <p>这一条是安全边界而不是格式洁癖：id 会被直接拼成 {@code b/<id>.bin} 的文件名，
     * 放行 {@code ../} 或大写混排会让「索引里的一条记录」变成任意路径的读写。</p>
     */
    public static boolean isId(String s) {
        if (s == null || s.length() != 32) return false;
        for (int i = 0; i < 32; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) return false;
        }
        return true;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        if (!isId(s)) throw new VaultCrypto.Fail(VaultCrypto.Code.ID_FORMAT);
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static byte[] encode(List<Entry> list) {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n');
        if (list != null) {
            for (Entry e : list) {
                if (e == null || !isId(e.id)) continue;
                sb.append(e.id).append('\t')
                        .append(Math.max(0, e.size)).append('\t')
                        .append(Math.max(0, e.mtime)).append('\t')
                        .append(Math.max(0, e.addedAt)).append('\t')
                        .append(esc(e.name)).append('\n');
            }
        }
        try {
            return sb.toString().getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 解析索引。魔数不符直接抛 {@link IllegalArgumentException}（那说明拿错了文件），
     * 单行坏掉只跳过并计数（那说明只是某条记录坏了）。
     */
    public static Read decode(byte[] raw) {
        if (raw == null) throw new VaultCrypto.Fail(VaultCrypto.Code.INDEX_EMPTY);
        String text;
        try {
            text = new String(raw, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        int nl = text.indexOf('\n');
        String head = nl < 0 ? text : text.substring(0, nl).trim();
        if (!MAGIC.equals(head)) throw new VaultCrypto.Fail(VaultCrypto.Code.INDEX_MAGIC);

        List<Entry> out = new ArrayList<Entry>();
        int bad = 0;
        int i = nl < 0 ? text.length() : nl + 1;
        while (i < text.length()) {
            int j = text.indexOf('\n', i);
            if (j < 0) j = text.length();
            String line = text.substring(i, j);
            i = j + 1;
            if (line.isEmpty()) continue;
            String[] f = split5(line);
            if (f == null) { bad++; continue; }
            if (!isId(f[0])) { bad++; continue; }
            long size, mtime, added;
            try {
                size = Long.parseLong(f[1]);
                mtime = Long.parseLong(f[2]);
                added = Long.parseLong(f[3]);
            } catch (NumberFormatException e) {
                bad++;
                continue;
            }
            if (size < 0 || mtime < 0 || added < 0) { bad++; continue; }
            out.add(new Entry(f[0], unesc(f[4]), size, mtime, added));
        }
        return new Read(out, bad);
    }

    /** 按前四个制表符切：名字里允许出现空格等任何字符，只有转义过的才安全 */
    private static String[] split5(String line) {
        String[] f = new String[5];
        int from = 0;
        for (int k = 0; k < 4; k++) {
            int t = line.indexOf('\t', from);
            if (t < 0) return null;
            f[k] = line.substring(from, t);
            from = t + 1;
        }
        f[4] = line.substring(from);
        return f;
    }

    /** 转义：反斜杠必须最先处理，否则「名字里本来就有一个反斜杠 + t」会被二次解释 */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\t': sb.append("\\t"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unesc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            if (n == '\\') sb.append('\\');
            else if (n == 't') sb.append('\t');
            else if (n == 'n') sb.append('\n');
            else if (n == 'r') sb.append('\r');
            else { sb.append('\\').append(n); }   // 未知转义原样保留，不吞字符
        }
        return sb.toString();
    }
}
