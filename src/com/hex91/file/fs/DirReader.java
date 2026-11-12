package com.hex91.file.fs;

import com.hex91.file.*;
import com.hex91.file.core.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v1.96.0：读目录（列取 / root 回退 / 排序 / 过滤 / 统计）——纯同步逻辑，由调用方决定线程。
 *
 * <p>为什么从 {@code FileAdapter.setDir} 搬出来：那段代码原来是「UI 线程里直接
 * {@code listFiles()} + {@code Arrays.sort} + 逐项 {@code lstat}」，几千个文件的下载目录
 * 或者要起 {@code su ls} 的受保护目录会在主线程停几十毫秒到几秒（ANR 边缘）。
 * 现在它只返回一份结果，{@link MainActivity} 用 {@link Bg#io} 在后台调用，
 * 回到主线程 {@code FileAdapter.setResult} 一次性换掉列表。</p>
 *
 * <p>刻意不碰 Android API：这样能在桌面 JVM 上直接跑单测（{@code test/DirReaderTest}），
 * 排序、隐藏文件、返回上级项、计数这些容易写错的规则有护栏。</p>
 */
public final class DirReader {

    private DirReader() {}

    /** 读取结果：条目（可能含顶部 ".."）+ 目录/文件计数（不含 ".."） */
    public static final class Result {
        public final List<FileItem> items;
        public final int dirs;
        public final int files;
        /** v1.97.0：非 null 表示这次读取失败（items 为空），调用方必须收尾而不是一直等。 */
        public final String error;

        Result(List<FileItem> items, int dirs, int files) {
            this(items, dirs, files, null);
        }

        Result(List<FileItem> items, int dirs, int files, String error) {
            this.items = items;
            this.dirs = dirs;
            this.files = files;
            this.error = error;
        }

        int count() { return items.size(); }
    }

    /**
     * v1.97.0：读目录的「绝不抛异常」版本。
     *
     * <p>为什么需要它：{@code Bg.io} 只把异常写进日志，任务体一旦抛出，
     * 回主线程的那一步 {@code Bg.ui(...)} 根本不会执行 —— 列表的 loading 标志永远为 true，
     * 而读目录期间点击/长按是被拦住的，于是整个窗格从此点不动，只能杀进程重启。
     * 这类「不自愈」的失败姿态不该由调用方逐处 try/catch 兜。</p>
     *
     * <p>现在读目录要么返回正常结果、要么返回带 {@code error} 的空结果，调用方一律收尾。</p>
     */
    public static Result readSafe(File dir, int sortMode, boolean showHidden, File rootDir) {
        try {
            return read(dir, sortMode, showHidden, rootDir);
        } catch (Throwable t) {
            return failed(t);
        }
    }

    /** 单测用：显式注入特权状态（不探测 su） */
    public static Result readSafe(File dir, int sortMode, boolean showHidden, File rootDir,
                                  boolean privileged) {
        try {
            return read(dir, sortMode, showHidden, rootDir, privileged);
        } catch (Throwable t) {
            return failed(t);
        }
    }

    private static Result failed(Throwable t) {
        String m = t == null ? "未知错误"
                : (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        return new Result(new ArrayList<FileItem>(), 0, 0, m);
    }

    /**
     * 读目录（自动判断有无特权 shell）。
     *
     * @param sortMode   {@link FileAdapter#SORT_NAME} / SORT_SIZE / SORT_TIME
     * @param showHidden 是否显示以 . 开头的条目
     * @param rootDir    应用根目录；等于它时不插 ".."（null = 只要上级可读就插）
     */
    public static Result read(File dir, int sortMode, boolean showHidden, File rootDir) {
        // 只有 listFiles 为 null（被 SELinux 拒绝）时才值得去探测 root：探测本身要起进程
        boolean privileged = RootUtil.isRootAvailable();
        return read(dir, sortMode, showHidden, rootDir, privileged);
    }

    /** 可注入特权状态的版本（单测用：桌面 JVM 没有 su，也不该去探测） */
    public static Result read(File dir, int sortMode, boolean showHidden, File rootDir,
                              boolean privileged) {
        if (dir == null) return new Result(new ArrayList<FileItem>(), 0, 0);

        List<FileItem> items = new ArrayList<FileItem>();
        int dirs = 0, files = 0;

        File[] list = dir.listFiles();
        // v1.42.0：listFiles 被 SELinux 拒绝（/data 等）且已 root → su ls 列取回退
        Set<String> rootDirNames = null;
        if (list == null && privileged) {
            List<String> lines = RootUtil.suList(dir.getPath());
            if (lines != null) {
                ArrayList<File> fs = new ArrayList<File>();
                rootDirNames = new HashSet<String>();
                for (String line : lines) {
                    if (line.length() < 2) continue;
                    boolean d = line.endsWith("/");
                    String n = d ? line.substring(0, line.length() - 1) : line;
                    if (n.equals(".") || n.equals("..") || n.isEmpty()) continue;
                    fs.add(new File(dir, n));
                    if (d) rootDirNames.add(n);
                }
                list = fs.toArray(new File[0]);
            }
        }

        if (list != null) {
            final Comparator<File> byName = new Comparator<File>() {
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            };
            // v1.96.0：排序键先取一遍再比 —— 原来 comparator 里直接 a.length()/lastModified()/
            // isDirectory()，每次比较都 stat 两个文件，n log n 次系统调用（几千项时非常明显）
            final java.util.Map<File, long[]> keys = new java.util.HashMap<File, long[]>();
            for (File f : list) {
                boolean d = isDirCached(f, rootDirNames);
                keys.put(f, new long[]{d ? 1 : 0, f.length(), f.lastModified()});
            }
            Arrays.sort(list, new Comparator<File>() {
                public int compare(File a, File b) {
                    long[] ka = keys.get(a), kb = keys.get(b);
                    boolean ad = ka != null && ka[0] != 0;
                    boolean bd = kb != null && kb[0] != 0;
                    if (ad != bd) return ad ? -1 : 1;
                    int r;
                    if (sortMode == FileAdapter.SORT_SIZE) {
                        r = Long.compare(ka == null ? 0 : ka[1], kb == null ? 0 : kb[1]);
                        if (r == 0) r = byName.compare(a, b);
                    } else if (sortMode == FileAdapter.SORT_TIME) {
                        r = Long.compare(kb == null ? 0 : kb[2], ka == null ? 0 : ka[2]);
                        if (r == 0) r = byName.compare(a, b);
                    } else {
                        r = byName.compare(a, b);
                    }
                    return r;
                }
            });
            for (File f : list) {
                if (!showHidden && f.getName().startsWith(".")) continue;
                FileItem it = new FileItem(f);
                if (rootDirNames != null && rootDirNames.contains(f.getName()))
                    it.forceDir = true;
                fillLink(it);      // v1.81.0：符号链接标记（列表显示 → 目标，点击仍跟随）
                items.add(it);
                long[] k = keys.get(f);
                if ((k != null && k[0] != 0) || it.isDir()) dirs++;
                else files++;
            }
        }

        // v1.40.0：非顶层目录 → 顶部插 .. 返回项（file 指向上级，点击即返回）
        // v1.42.0：root 设备上级目录即便不可读（/data）也允许返回
        File up = dir.getParentFile();
        boolean atRoot = rootDir != null
                && dir.getAbsolutePath().equals(rootDir.getAbsolutePath());
        boolean upReadable = up != null && (up.canRead() || privileged);
        if (upReadable && !atRoot) items.add(0, FileItem.up(up));

        return new Result(items, dirs, files);
    }

    private static boolean isDirCached(File f, Set<String> rootDirNames) {
        if (rootDirNames != null && rootDirNames.contains(f.getName())) return true;
        return f.isDirectory();
    }

    /**
     * v1.81.0：填充符号链接信息。
     * <p>lstat 一次判类型（有缓存），确认是链接再 readlink。悬空链接 {@code f.exists()}
     * 为 false，单独标记，列表显示「(失效)」。</p>
     */
    private static void fillLink(FileItem it) {
        if (it.file == null || it.upEntry) return;
        int k = LinkUtil.kind(it.file);
        if (k == LinkUtil.NONE) return;
        String t = LinkUtil.target(it.file);
        it.linkTarget = t == null ? "" : t;
        it.linkBroken = (k == LinkUtil.BROKEN);
        // 指向目录 → 按目录处理：部分 FUSE 实现 isDirectory() 不跟随链接，
        // 这里用 lstat 的结论兜底，保证点进去能当目录打开
        if (k == LinkUtil.DIR) it.forceDir = true;
    }
}
