package com.hex91.file.utils;

import com.hex91.file.fs.FileTask;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.99.2（R-07）：未完成任务的小账本。
 *
 * <p><b>要解决的问题</b>：复制 4GB 文件时被系统清掉进程（或者用户手滑强退），
 * 下次打开应用什么都没有 —— 目标目录里只剩几个 {@code .part}，用户不知道发生过什么，
 * 也不知道当时想把这些文件拷去哪儿，只能凭记忆从头再选一遍。</p>
 *
 * <p><b>做法</b>：任务一进队列就把「源列表 + 目标目录 + 类型」写进应用私有目录下的小文件，
 * 任务结束时重写；全部结束就删掉这个文件。启动时文件非空，就说明上次有任务没跑完。</p>
 *
 * <p><b>为什么不用数据库</b>：这里最多几条记录、路径可能很长，一段纯文本最省事，
 * 也最容易在出问题时用 root 看一眼。</p>
 *
 * <p>v1.99.3：存储容器改为 {@code SharedPreferences} 的 {@code running_task_json}
 * （见 {@link TaskStore}）——启动时读一次就够，不必碰文件系统；老版本留在
 * {@code files/task_journal} 的文件由 TaskStore 一次性迁移，升级用户不会丢提示。
 * 本类仍然只做「字符串 ↔ 条目列表」，不引用 {@code android.*}，可桌面单测。</p>
 */
public final class TaskJournal {

    /** 文件头：格式变了就直接当成「没有账本」，不要误读上一版的内容 */
    private static final String MAGIC = "hexfile-journal-1";

    /** v1.99.2 及以前用的文件名；v1.99.3 起只用于迁移旧账本 */
    public static final String FILE_NAME = "task_journal";

    private TaskJournal() {}

    /** 一条未完成的任务 */
    public static final class Entry {
        public final int kind;
        public final File dstDir;
        public final List<File> srcs;

        public Entry(int kind, File dstDir, List<File> srcs) {
            this.kind = kind;
            this.dstDir = dstDir;
            this.srcs = srcs == null ? new ArrayList<File>() : new ArrayList<File>(srcs);
        }
    }

    /**
     * v1.99.3：账本容器。引擎只认这个接口 —— 生产用 {@link TaskStore}（SharedPreferences），
     * 桌面单测注入内存实现，两边跑的是同一段序列化代码。
     */
    public interface Store {
        /** 覆盖写入；空列表表示「没有未完成任务」 */
        void save(List<Entry> es);
    }

    /** 读回未完成任务；文件不存在、为空、格式不认识都返回空列表（绝不抛）。 */
    public static List<Entry> load(File f) {
        if (f == null || !f.isFile()) return new ArrayList<Entry>();
        String text = null;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (!first) sb.append('\n');
                sb.append(line);
                first = false;
            }
            text = sb.toString();
        } catch (Throwable t) {
            // 账本坏了不该拦住应用启动：宁可丢掉「上次没做完」的提示
            return new ArrayList<Entry>();
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
        return decode(text);
    }

    /**
     * v1.99.3：序列化成一段文本（存 SharedPreferences 用）。空列表返回 {@code null} ——
     * 调用方据此 {@code remove} 掉那个 key，而不是留一个空串在偏好里。
     */
    public static String encode(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(MAGIC).append('\n');
        int n = 0;
        for (Entry e : entries) {
            if (e == null || e.dstDir == null || e.srcs.isEmpty()) continue;
            sb.append('T').append('\t').append(e.kind).append('\t')
                    .append(escape(e.dstDir.getPath())).append('\n');
            for (File s : e.srcs) {
                if (s != null) sb.append('S').append('\t').append(escape(s.getPath())).append('\n');
            }
            n++;
        }
        return n == 0 ? null : sb.toString();
    }

    /**
     * v1.99.3：反序列化。{@code null}、空串、格式不认识、单行被截断都返回空列表 ——
     * 故意容忍：偏好里的内容可能是被系统降级、或者上个版本的半截写入。
     */
    public static List<Entry> decode(String text) {
        List<Entry> out = new ArrayList<Entry>();
        if (text == null || text.length() == 0) return out;
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !MAGIC.equals(lines[0])) return out;
        try {
            int kind = -1;
            File dst = null;
            List<File> srcs = new ArrayList<File>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.startsWith("T\t")) {
                    // 新任务开始：上一段收尾
                    flush(out, kind, dst, srcs);
                    String[] p = line.split("\t", -1);
                    kind = p.length > 1 ? parseInt(p[1]) : -1;
                    dst = p.length > 2 ? new File(unescape(p[2])) : null;
                    srcs = new ArrayList<File>();
                } else if (line.startsWith("S\t")) {
                    srcs.add(new File(unescape(line.substring(2))));
                }
            }
            flush(out, kind, dst, srcs);
        } catch (Throwable t) {
            return new ArrayList<Entry>();
        }
        return out;
    }

    /** 写入（覆盖）。{@code entries} 为空则删除文件 —— 没有未完成任务就不该留痕 */
    public static boolean save(File f, List<Entry> entries) {
        if (f == null) return false;
        String text = encode(entries);
        if (text == null) {
            if (f.exists()) f.delete();
            return true;
        }
        Writer w = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(text);
            w.write('\n');
            w.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignored) {}
        }
    }

    private static void flush(List<Entry> out, int kind, File dst, List<File> srcs) {
        if (dst != null && !srcs.isEmpty()) out.add(new Entry(kind, dst, srcs));
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return FileTask.COPY;
        }
    }

    /** 路径里可能有制表符/换行（极罕见），转义后再写；反斜杠先转，避免二次解释 */
    static String escape(String p) {
        return p.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    static String unescape(String p) {
        StringBuilder sb = new StringBuilder(p.length());
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '\\' && i + 1 < p.length()) {
                char n = p.charAt(++i);
                if (n == 't') sb.append('\t');
                else if (n == 'n') sb.append('\n');
                else sb.append(n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
