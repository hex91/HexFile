package com.hex91.file;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * v1.81.0 符号链接探测与目标解析。
 *
 * <p>为什么不用 {@code File.getCanonicalPath()}：
 * Java 的 canonical 化在链接指向不存在目标时会抛 IOException，且无法区分
 * 「名字是链接」与「父目录里有链接」；这里直接用 {@code lstat} 拿 inode 类型，
 * 断链、指向目录、指向文件三种情况都能分清。</p>
 *
 * <p>普通目录（/sdcard 的 FUSE）与 root 区（/data）都走同一条路径；
 * 没有权限时 lstat 抛异常，按「不是链接」处理 —— 与旧行为一致，不会让列表崩掉。</p>
 */
public final class LinkUtil {

    private LinkUtil() {}

    /** 不是符号链接（含不存在 / 无权限探测） */
    public static final int NONE = 0;
    /** 指向一个存在的文件 */
    public static final int FILE = 1;
    /** 指向一个存在的目录 */
    public static final int DIR = 2;
    /** 悬空链接：目标不存在 */
    public static final int BROKEN = 3;

    /** 探测类型；失败一律返回 {@link #NONE} */
    public static int kind(File f) {
        if (f == null) return NONE;
        // 列表下拉刷新会反复走同一条路径：缓存（key 含大小/时间）避免重复 lstat
        final String key = f.getPath() + "|" + f.length() + "|" + f.lastModified();
        Integer hit;
        synchronized (KIND_CACHE) { hit = KIND_CACHE.get(key); }
        if (hit != null) return hit;
        int k = probeKind(f);
        synchronized (KIND_CACHE) {
            if (KIND_CACHE.size() > 2048) KIND_CACHE.clear();
            KIND_CACHE.put(key, k);
        }
        return k;
    }

    private static final java.util.LinkedHashMap<String, Integer> KIND_CACHE =
            new java.util.LinkedHashMap<String, Integer>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Integer> e) {
                    return size() > 2048;
                }
            };

    private static int probeKind(File f) {
        try {
            android.system.StructStat st = android.system.Os.lstat(f.getPath());
            if ((st.st_mode & android.system.OsConstants.S_IFMT)
                    != android.system.OsConstants.S_IFLNK) {
                return NONE;
            }
        } catch (Throwable t) {
            return NONE;   // 不存在 / EACCES / SELinux 拒绝
        }
        // 确实是链接，再判断目标状态（这些调用会跟随链接）
        if (f.isDirectory()) return DIR;
        if (f.exists()) return FILE;
        return BROKEN;
    }

    public static boolean isLink(File f) {
        return kind(f) != NONE;
    }

    /**
     * v1.99.1：遍历目录时用这个，而不是自己写 {@code for (c : listFiles()) if (isLink(c)) continue}。
     *
     * <p>「不跟进链接」是全局规则，不是每个调用点的自由选择：{@code File.isDirectory()} 与
     * {@code listFiles()} 都会跟随链接，跟进一次就是重复计数，链接成环还会递归爆栈。
     * 之前 6 个递归入口各写一遍判断，谁漏写一次就会重新出现「磁盘占用翻倍 / 删链删光目标」。</p>
     *
     * <p>需要区分「链接本身也计入条目数」的场景（磁盘分析的分母、Top N 的条数），
     * 仍应自行 {@link #isLink} 判断 —— 那是计数策略，不是遍历策略。</p>
     *
     * @return 已剔除符号链接的子项；目录读不到（无权限 / 已删除）返回 {@code null}，
     *         与 {@link File#listFiles()} 的约定一致
     */
    public static File[] listNoFollow(File dir) {
        if (dir == null) return null;
        File[] all = dir.listFiles();
        if (all == null) return null;
        // 一次遍历完成判断与收集（isLink 内部有 lstat + 缓存，但每次调用仍要拼 key，别做两遍）
        File[] buf = new File[all.length];
        int n = 0;
        for (File f : all) if (!isLink(f)) buf[n++] = f;
        if (n == all.length) return all;      // 常见路径：没有链接，不额外分配
        File[] out = new File[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    /** 读取链接目标原文；失败返回 null */
    public static String target(File f) {
        if (f == null) return null;
        try {
            return android.system.Os.readlink(f.getPath());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把 readlink 的结果解析成绝对路径（纯 Java，可桌面单测）。
     *
     * @param parent 链接所在目录的绝对路径
     * @param target readlink 原文（可能是相对路径）
     * @return 规范化绝对路径；target 为空时返回 null
     */
    public static String resolve(String parent, String target) {
        if (target == null || target.isEmpty()) return null;
        if (target.startsWith("/")) return normalize(target);
        String base = (parent == null || parent.isEmpty()) ? "/" : parent;
        return normalize(base + "/" + target);
    }

    /** 消掉 "." / ".." / 重复斜杠；结果始终以 "/" 开头，根目录返回 "/" */
    public static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        Deque<String> st = new ArrayDeque<String>();
        for (String p : path.split("/")) {
            if (p.isEmpty() || ".".equals(p)) continue;
            if ("..".equals(p)) {
                if (!st.isEmpty()) st.removeLast();
            } else {
                st.addLast(p);
            }
        }
        if (st.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String p : st) sb.append('/').append(p);
        return sb.toString();
    }

    /**
     * 列表里展示用的短目标：优先显示相对链接原文（更短、更贴近用户建立链接时写的内容），
     * 绝对目标则原样返回。
     */
    public static String displayTarget(File link) {
        String raw = target(link);
        if (raw == null) return null;
        return raw;
    }
}
