package com.hex91.file.fs;

import com.hex91.file.net.*;
import com.hex91.file.core.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * v1.97.0：递归文件树操作（删除 / 复制 / 统计）的唯一实现。
 *
 * <p><b>要解决的问题</b>：{@link File#isDirectory()} 与 {@link File#listFiles()} <b>会跟随符号链接</b>。
 * 所以下面这些地方原来都是「选中一个看起来像快捷方式的条目 → 递归进目标 → 把目标里的东西删掉」：</p>
 * <ul>
 *   <li>{@code FileOps.deleteRecursive} / {@code FileTaskEngine.deleteTree}：删链接目录 = 删目标内容；</li>
 *   <li>{@code RemoteServer} 网页端删除、{@code HfDocumentsProvider} 其它应用经 SAF 删除：同上；</li>
 *   <li>{@code FileOps.copyDir} / {@code FileTaskEngine.copyTree}：复制链接目录 = 把目标整棵树复制一份；</li>
 *   <li>{@code Trash.moveFile} 跨卷回退、{@code MainActivity.dirSize}、{@code DiskScanUtil} 五处统计：
 *       跟进目标，遇到环直接 StackOverflow。</li>
 * </ul>
 *
 * <p>真实场景不是理论：不少 ROM 上 {@code /sdcard} 本身就是一个指向 {@code /storage/...} 的链接，
 * root 下浏览 {@code /} 时删掉它，用户以为删了个「快捷方式」，实际删了整个内部存储。</p>
 *
 * <p><b>可测性</b>：链接的判定与复刻都走 {@link LinkProbe}，默认实现
 * {@link #SYSTEM} 调 {@link LinkUtil}（{@code Os.lstat}）。桌面 JVM 上 {@code Os} 是 stub，
 * {@code lstat} 抛异常后被 LinkUtil 当成「不是链接」——与旧行为一致，不会影响现有用例；
 * 单测里注入桩实现，就能在没有管理员权限的 Windows 上验证「不跟随链接」这条规则。</p>
 */
public final class TreeOps {

    /**
     * 递归深度上限。正常目录不会超过十几层，超过基本就是链接环或恶意构造；
     * 撞到上限按「到此为止」处理，绝不抛 StackOverflow 把整个进程带走。
     */
    public static final int MAX_DEPTH = 64;

    /** 链接探测 + 复刻。见类注释「可测性」。 */
    public interface LinkProbe {
        boolean isLink(File f);

        /**
         * 在 dst 复刻一条与 src 指向同一目标的链接。
         *
         * @return false 表示当前文件系统/API 不支持（例如 FAT32 上的 U 盘、没有权限），
         *         调用方按「跳过」处理，不要把整次复制判失败
         */
        boolean copyLink(File src, File dst);
    }

    /** 真机实现：类型判断走 LinkUtil（lstat），复刻走 Os.symlink（API 21+） */
    public static final LinkProbe SYSTEM = new LinkProbe() {
        public boolean isLink(File f) {
            return LinkUtil.isLink(f);
        }

        public boolean copyLink(File src, File dst) {
            String target = LinkUtil.target(src);
            if (target == null || target.isEmpty()) return false;
            try {
                android.system.Os.symlink(target, dst.getPath());
                return true;
            } catch (Throwable t) {
                return false;   // 目标文件系统不支持链接：由调用方计为跳过
            }
        }
    };

    private TreeOps() {}

    // ------------------------------------------------------------------ 删除

    /**
     * 递归删除。
     *
     * <p>链接只删链本身（与 {@code rm -rf} 不带尾斜杠的行为一致），不进入目标。</p>
     */
    public static boolean delete(File f) {
        return delete(f, SYSTEM);
    }

    public static boolean delete(File f, LinkProbe probe) {
        return delete(f, probe, 0);
    }

    private static boolean delete(File f, LinkProbe probe, int depth) {
        if (f == null || depth > MAX_DEPTH) return false;
        // 关键：必须是 isLink 之前的分支。File.isDirectory() 会跟随链接，
        // 一旦进了下面的 listFiles，删的就是目标的内容。
        if (probe.isLink(f)) return f.delete();
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) delete(k, probe, depth + 1);
        }
        return f.delete();
    }

    /**
     * v2.4.0（R-09）：删除，删不掉再落回收站。
     *
     * <p><b>为什么要有这个入口</b>：{@code MainActivity} 曾自己写了一份
     * {@code deleteRecursiveOrTrash}，用 {@code f.isFile()} 判文件、{@code f.listFiles()}
     * 递归 —— 两处都会<b>跟随软链接</b>。「移动进压缩包」成功后调它删源，
     * 若源目录里有个 {@code backup -> /sdcard/DCIM}，删的是相册内容，链接本身反而留着。
     * 现在所有「删了就没了」的递归路径统一走这里，链接只删链。</p>
     *
     * <p>回收站兜底只在 {@link #delete} 真的删不掉（只读挂载、属主不符）时才发生；
     * {@link Trash#moveToTrash} 自身也做了链接判断（跨卷回退时复刻链接而非跟进），
     * 所以两条分支都不会钻进链接目标。</p>
     */
    public static boolean deleteOrTrash(File f) {
        return deleteOrTrash(f, SYSTEM);
    }

    public static boolean deleteOrTrash(File f, LinkProbe probe) {
        if (f == null) return false;
        if (delete(f, probe)) return true;
        // 已被别的进程删掉：落盘结果符合预期，不必再进回收站
        if (!f.exists()) return true;
        return Trash.moveToTrash(f) != null;
    }

    // ------------------------------------------------------------------ 统计

    /** 递归求大小（不进入链接，链接本身按 0 计） */
    public static long size(File f) {
        return size(f, SYSTEM);
    }

    public static long size(File f, LinkProbe probe) {
        return size(f, probe, 0);
    }

    private static long size(File f, LinkProbe probe, int depth) {
        if (f == null || depth > MAX_DEPTH) return 0;
        // 链接一律不计：既避免同一个目标被重复计算，也断开「a → b → a」这类环
        if (probe.isLink(f)) return 0;
        if (f.isFile()) return f.length();
        File[] kids = f.listFiles();
        if (kids == null) return 0;
        long total = 0;
        for (File k : kids) total += size(k, probe, depth + 1);
        return total;
    }

    // ------------------------------------------------------------------ 复制

    /**
     * 递归复制目录（同名合并、同名文件覆盖）。
     *
     * <p>链接不跟随：按原样复刻成链接；文件系统不支持链接时跳过并计入 {@code skipped}。</p>
     *
     * @param skipped 可空；数组长度 ≥ 1，累加被跳过的链接数
     */
    public static void copyDir(File src, File dst, LinkProbe probe, int[] skipped)
            throws IOException {
        if (src == null || dst == null) throw new IOException("路径为空");
        // v1.99.2（R-02）：入口这一层也得判，之前只在递归里判子项。
        // src 本身是链接时，下面的 dst.mkdirs() + src.listFiles() 全都会跟进到目标 ——
        // 「复制一个链接目录」于是变成「把目标整棵树复制一份」，和 S-1 完全同一个失效模式，
        // 只是因为少了一次判断就从后门漏了回来。
        if (probe.isLink(src)) {
            if (probe.copyLink(src, dst)) return;
            if (skipped != null && skipped.length > 0) {
                skipped[0]++;
                return;
            }
            // 没有「跳过计数」出口就说明调用方要的是「要么成要么失败」，
            // 不能悄悄返回一个空目录，让用户以为复制成功了
            throw new IOException("目标文件系统不支持符号链接: " + src.getPath());
        }
        copyDir(src, dst, probe, skipped, 0);
    }

    private static void copyDir(File src, File dst, LinkProbe probe, int[] skipped, int depth)
            throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("目录层级过深: " + src.getPath());
        if (!dst.isDirectory() && !dst.mkdirs() && !dst.isDirectory())
            throw new IOException("无法创建目录: " + dst);
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File d = new File(dst, k.getName());
            if (probe.isLink(k)) {
                // 不跟进：要么复刻成链接，要么跳过。绝不能把目标整棵树复制过来。
                if (!probe.copyLink(k, d) && skipped != null && skipped.length > 0) skipped[0]++;
                continue;
            }
            if (k.isDirectory()) copyDir(k, d, probe, skipped, depth + 1);
            else copyFile(k, d);
        }
    }

    /** 单文件复制（截断覆盖写） */
    public static void copyFile(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
