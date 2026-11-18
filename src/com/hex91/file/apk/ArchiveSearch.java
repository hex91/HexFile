package com.hex91.file.apk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 包内搜索的匹配与排序（v2.16.0，F-21）。
 *
 * <p>纯 JDK，不碰任何归档后端：输入是 {@link ArchiveUtil#open} 已经读好的条目表，
 * 输出是排好序的命中列表。这样匹配规则可以在桌面单独测，不必造十个真实压缩包。</p>
 *
 * <h3>为什么自己写通配而不是用正则</h3>
 * 用户输入里 {@code . ( ) [ ] + } 全都可能出现在文件名里；把这些转成正则要先做一次
 * 完整转义，漏一个字符就是「搜不到」或「正则语法错误」。直接用双指针通配匹配，
 * 只需要处理 {@code * ?} 两个字符，其余一律按字面量比较，没有转义这一步也就没有这一步的坑。
 */
public final class ArchiveSearch {

    /** 结果上限：再多也没人一屏一屏翻，超过就截断并如实告知总数 */
    public static final int MAX_HITS = 500;

    private ArchiveSearch() {}

    /** 搜索结果 */
    public static final class Result {
        /** 排好序的命中项（最多 max 条） */
        public final List<ArchiveUtil.Entry> items;
        /** 截断前的命中总数 */
        public final int total;
        /** 是否被上限截断 */
        public final boolean truncated;

        Result(List<ArchiveUtil.Entry> items, int total, boolean truncated) {
            this.items = items;
            this.total = total;
            this.truncated = truncated;
        }
    }

    /**
     * 规范化查询串：去首尾空白、连续空白压成一个空格、反斜杠转斜杠。
     *
     * <p>反斜杠那一项是为了从别处粘来的 Windows 路径：用户复制 {@code res\layout\a.xml}
     * 时不会想到要改成斜杠，而归档里的路径永远是斜杠。</p>
     */
    public static String normalize(String q) {
        if (q == null) return "";
        String s = q.replace('\\', '/').trim();
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // 含全角空格：中文输入法下很容易打出来，按空白对待才符合直觉
            if (c == ' ' || c == '\t' || c == '\u3000') {
                space = true;
                continue;
            }
            if (space && sb.length() > 0) sb.append(' ');
            space = false;
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 一个条目路径是否命中查询。
     *
     * <p>规则：空格分隔的多个词必须**全部**命中（AND）；词内 {@code *} 匹配任意串、
     * {@code ?} 匹配单个字符；其余按字面量、忽略大小写。</p>
     */
    public static boolean matches(String path, String query) {
        String q = normalize(query);
        if (q.isEmpty() || path == null || path.isEmpty()) return false;
        String hay = path.toLowerCase(Locale.ROOT);
        String[] words = q.toLowerCase(Locale.ROOT).split(" ");
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) continue;
            // 前后补 * 就是子串语义；词里自带的 * ? 照常生效
            if (!wild(hay, "*" + w + "*")) return false;
        }
        return true;
    }

    /** 双指针通配匹配（支持 * ?，{@code s} 与 {@code p} 必须都已转小写） */
    public static boolean wild(String s, String p) {
        int si = 0, pi = 0, star = -1, mark = 0;
        while (si < s.length()) {
            if (pi < p.length() && (p.charAt(pi) == '?' || p.charAt(pi) == s.charAt(si))) {
                si++;
                pi++;
            } else if (pi < p.length() && p.charAt(pi) == '*') {
                star = pi++;                       // 记住「星号位置」，失败时只回退到这里
                mark = si;
            } else if (star >= 0) {
                pi = star + 1;                     // 让星号多吞一个字符再试
                si = ++mark;
            } else {
                return false;
            }
        }
        while (pi < p.length() && p.charAt(pi) == '*') pi++;
        return pi == p.length();
    }

    /**
     * 过滤 + 排序 + 截断。
     *
     * <p>排序依次看：短名完全等于查询 &gt; 短名命中 &gt; 路径更短 &gt; 字典序。
     * 「短名优先」是因为用户输入 {@code AndroidManifest.xml} 时，想要的是根目录那一个，
     * 而不是某个 {@code res/raw/AndroidManifest.xml}；「路径更短」则让浅层的命中排在
     * 深层的同名文件前面。</p>
     */
    public static Result filter(List<ArchiveUtil.Entry> all, String query, int max) {
        if (all == null || all.isEmpty()) return new Result(new ArrayList<ArchiveUtil.Entry>(), 0, false);
        String q = normalize(query);
        if (q.isEmpty()) return new Result(new ArrayList<ArchiveUtil.Entry>(), 0, false);
        final String lower = q.toLowerCase(Locale.ROOT);
        final int limit = max <= 0 ? MAX_HITS : max;

        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            ArchiveUtil.Entry e = all.get(i);
            if (e == null || e.full == null) continue;
            if (!matches(e.full, q)) continue;
            String leaf = e.name == null ? "" : e.name.toLowerCase(Locale.ROOT);
            boolean nameHit = matches(leaf, q);
            hits.add(new Hit(e, leaf.equals(lower) || (leaf + "/").equals(lower), nameHit, i));
        }
        if (hits.isEmpty()) return new Result(new ArrayList<ArchiveUtil.Entry>(), 0, false);

        Collections.sort(hits, new Comparator<Hit>() {
            public int compare(Hit a, Hit b) {
                if (a.exact != b.exact) return a.exact ? -1 : 1;
                if (a.nameHit != b.nameHit) return a.nameHit ? -1 : 1;
                int la = a.e.full.length(), lb = b.e.full.length();
                if (la != lb) return la < lb ? -1 : 1;
                int c = a.e.full.compareToIgnoreCase(b.e.full);
                if (c != 0) return c;
                return a.idx < b.idx ? -1 : (a.idx == b.idx ? 0 : 1);   // 稳定：同分按原顺序
            }
        });

        int total = hits.size();
        boolean truncated = total > limit;
        List<ArchiveUtil.Entry> out = new ArrayList<>(Math.min(total, limit));
        for (int i = 0; i < total && i < limit; i++) out.add(hits.get(i).e);
        return new Result(out, total, truncated);
    }

    /** 排序用的中间行：把判断结果先算好，避免比较器里重复做字符串扫描 */
    private static final class Hit {
        final ArchiveUtil.Entry e;
        /** 短名与查询完全相同（含目录名的尾斜杠） */
        final boolean exact;
        /** 短名自己就命中全部词 */
        final boolean nameHit;
        final int idx;

        Hit(ArchiveUtil.Entry e, boolean exact, boolean nameHit, int idx) {
            this.e = e;
            this.exact = exact;
            this.nameHit = nameHit;
            this.idx = idx;
        }
    }
}
