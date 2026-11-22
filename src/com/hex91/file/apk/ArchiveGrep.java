package com.hex91.file.apk;

import com.hex91.file.fs.ContentGrep;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 包内内容搜索（v2.112.0，F-24）：在压缩包里按 <b>内容</b> 找字符串，而不只是文件名。
 *
 * <p>对标 MT 的「哪个 .so / 哪个 xml 里有这个字符串」。零件都是现成的：
 * {@link ContentGrep} 负责按行扫描与二进制判定，{@link ArchiveUtil.Archive#extractEntry}
 * 负责把单个条目落成临时文件 —— 这里只做「选哪些条目、怎么防止被一个 zip bomb 打满」。</p>
 *
 * <h3>为什么不 readAllBytes</h3>
 * 7z / tar 的后端只有流式接口，直接读进内存等于「按条目大小分配数组」；
 * 一个声明 4MB、实际 4GB 的 bomb 就能把进程干掉。统一走 {@code extractEntry} 落临时文件，
 * 再用文件长度复核 —— 磁盘不够时抛 IOException，是可恢复的失败，不是 OOM。
 *
 * <h3>三道闸</h3>
 * <ol>
 *   <li>扩展名白名单：只碰真正的文本，二进制连解压都不做；</li>
 *   <li>单条上限：声明大小超 {@link #MAX_TEXT_BYTES} 直接跳过；解压后实际大小超
 *       {@link #HARD_ENTRY_BYTES} 视为可疑（头部撒谎），删掉并计数；</li>
 *   <li>总预算 {@link #MAX_TOTAL_BYTES}：到顶就停，结果里标 {@code truncated}。</li>
 * </ol>
 *
 * <p>纯 Java（不依赖 Android），桌面可造真实 zip 单测。</p>
 */
public final class ArchiveGrep {

    /** 命中条数上限（与名称搜索同口径） */
    public static final int MAX_HITS = 500;
    /** 参与内容扫描的单条上限：4MB。更大的文件多半是资源或数据，不是用户要找的文本 */
    public static final long MAX_TEXT_BYTES = 4L * 1024 * 1024;
    /** 解压后实际大小的硬上限：64MB。声明与真实不符（zip bomb）时在这里拦住 */
    public static final long HARD_ENTRY_BYTES = 64L * 1024 * 1024;
    /** 一次搜索累计解压上限：512MB */
    public static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    /**
     * 只对这些扩展名做内容扫描。
     *
     * <p>不做「先解压再猜是不是文本」：那等于把包里所有 .so / .png 都解一遍，
     * 一个 200MB 的包就是几百次无谓的磁盘写。白名单漏掉的格式，用户仍能用名称搜索定位。</p>
     */
    private static final String[] TEXT_EXT = {
            ".txt", ".xml", ".json", ".java", ".smali", ".kt", ".js", ".css",
            ".html", ".htm", ".properties", ".md", ".yml", ".yaml", ".ini", ".cfg",
            ".csv", ".log", ".sh", ".gradle", ".pro", ".ts", ".sql", ".srt", ".ass"
    };

    private ArchiveGrep() {}

    /** 取消信号（每个条目边界问一次） */
    public interface Cancel {
        boolean cancelled();
    }

    /** 进度回调（在调用线程触发，UI 自己转主线程） */
    public interface Progress {
        void onEntry(int done, int total, String name);
    }

    /** 一条内容命中 */
    public static final class Hit {
        public final ArchiveUtil.Entry entry;
        /** 行号，从 1 开始 */
        public final int line;
        /** 该行内容（已截断到 ContentGrep 的单行上限） */
        public final String text;

        Hit(ArchiveUtil.Entry entry, int line, String text) {
            this.entry = entry;
            this.line = line;
            this.text = text;
        }
    }

    /** 搜索结果与统计（统计值用于如实告诉用户「跳过了什么」） */
    public static final class Result {
        public final List<Hit> hits = new ArrayList<Hit>();
        /** 实际读过内容的条目数 */
        public int scanned;
        /** 因扩展名不在白名单而未解压 */
        public int skippedType;
        /** 因体积超限（声明或实际）而未扫描 */
        public int skippedBig;
        /** 累计解压字节 */
        public long bytes;
        /** 命中达上限 / 总预算用尽，结果不完整 */
        public boolean truncated;
        public boolean cancelled;
        /** ASCII 诊断（单条失败不中断整轮，只记最后一条）；null = 无错 */
        public String error;
    }

    /**
     * 扫描 {@code all} 里的文本条目。
     *
     * @param all    归档条目表（{@link ArchiveUtil.Archive#listAll()}）
     * @param tmpBase 临时根目录（调用方给缓存目录），方法结束会删掉自己建的那一层
     */
    public static Result search(ArchiveUtil.Archive a, List<ArchiveUtil.Entry> all,
                                String query, boolean ignoreCase, File tmpBase,
                                Cancel cancel, Progress prog) {
        Result r = new Result();
        if (a == null || all == null || query == null || query.isEmpty()) return r;

        List<ArchiveUtil.Entry> cand = new ArrayList<ArchiveUtil.Entry>();
        for (int i = 0; i < all.size(); i++) {
            ArchiveUtil.Entry e = all.get(i);
            if (e == null || e.dir || e.full == null) continue;
            if (!isTextName(e.name)) {
                r.skippedType++;
                continue;
            }
            if (e.size > MAX_TEXT_BYTES) {
                r.skippedBig++;
                continue;
            }
            cand.add(e);
        }

        File work = new File(tmpBase, "g" + System.nanoTime());
        int total = cand.size();
        try {
            for (int i = 0; i < total; i++) {
                if (cancel != null && cancel.cancelled()) {
                    r.cancelled = true;
                    break;
                }
                if (r.hits.size() >= MAX_HITS) {
                    r.truncated = true;
                    break;
                }
                if (r.bytes >= MAX_TOTAL_BYTES) {
                    r.truncated = true;
                    break;
                }
                ArchiveUtil.Entry e = cand.get(i);
                File dir = new File(work, "i" + i);
                try {
                    a.extractEntry(e.full, dir);
                    File f = locate(dir, e);
                    if (f == null) continue;
                    // 头部声明可以撒谎：解压后用真实长度再判一次
                    if (f.length() > HARD_ENTRY_BYTES) {
                        r.skippedBig++;
                        continue;
                    }
                    r.bytes += f.length();
                    r.scanned++;
                    ContentGrep.Result g = ContentGrep.grepFile(f, query, false, ignoreCase,
                            MAX_HITS - r.hits.size(), MAX_TEXT_BYTES);
                    for (int k = 0; k < g.matches.size(); k++) {
                        ContentGrep.Match m = g.matches.get(k);
                        r.hits.add(new Hit(e, m.line, m.text));
                    }
                    if (g.truncated) r.truncated = true;
                } catch (Throwable t) {
                    // 单条解压失败（加密、损坏、密码错）不该让整轮白跑
                    r.error = "ARCHIVE GREP " + e.full + ": " + t.getClass().getSimpleName();
                } finally {
                    deleteTree(dir);
                }
                if (prog != null) prog.onEntry(i + 1, total, e.full);
            }
        } finally {
            deleteTree(work);
        }
        return r;
    }

    /** 扩展名是否在内容扫描白名单里 */
    public static boolean isTextName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < TEXT_EXT.length; i++) {
            if (n.endsWith(TEXT_EXT[i])) return true;
        }
        return false;
    }

    /**
     * 找到刚解压出来的那个文件。
     *
     * <p>各后端对 {@code extractEntry} 的目标路径规约不完全一致（有的保留完整的
     * {@code e.full} 相对路径，有的只落短名），所以先按完整路径试，找不到再在
     * 该条目的临时目录里取第一个普通文件 —— 每次只解一个条目，目录里不会有歧义。</p>
     */
    private static File locate(File dir, ArchiveUtil.Entry e) {
        File direct = new File(dir, e.full);
        if (direct.isFile()) return direct;
        return firstFile(dir, 0);
    }

    private static File firstFile(File dir, int depth) {
        if (dir == null || depth > 16) return null;
        File[] cs = dir.listFiles();
        if (cs == null) return null;
        for (int i = 0; i < cs.length; i++) {
            if (cs[i].isFile()) return cs[i];
        }
        for (int i = 0; i < cs.length; i++) {
            if (cs[i].isDirectory()) {
                File f = firstFile(cs[i], depth + 1);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) {
            for (int i = 0; i < cs.length; i++) deleteTree(cs[i]);
        }
        f.delete();
    }
}
