package com.hex91.file;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.CRC32;

/** 文件操作工具集：复制/移动/删除/重命名/新建 + 哈希 + 格式化 */
public class FileOps {

    public static boolean copy(File src, File dstDir) {
        return moveOrCopy(src, dstDir, false);
    }

    public static boolean move(File src, File dstDir) {
        return moveOrCopy(src, dstDir, true);
    }

    private static boolean moveOrCopy(File src, File dstDir, boolean isMove) {
        // v1.66.3：区分「拷贝未完成」与「拷贝已完成仅删源失败」——
        // 后者绝不能再走 RootIO.moveInto（会把数据再拷一遍，造成源/目标双份重复）
        boolean copied = false;
        try {
            File dst = new File(dstDir, src.getName());
            if (src.isDirectory()) {
                copyDir(src, dst);
                copied = true;
                if (isMove && !deleteRecursive(src)) {
                    // 拷贝已完成、源删除失败（只读文件系统等）：目标已有完整数据，
                    // 仅残留源 → 尝试 root 删除，不再重复拷贝
                    if (RootUtil.isRootAvailable()) RootIO.delete(src);
                    return true;
                }
            } else {
                dstDir.mkdirs();
                InputStream in = new FileInputStream(src);
                OutputStream out = new FileOutputStream(dst);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                out.close();
                copied = true;
                if (isMove && !src.delete()) {
                    // src.delete() 静默失败：目标已写入，尝试 root 删除源，不重复拷贝
                    if (RootUtil.isRootAvailable()) RootIO.delete(src);
                    return true;
                }
            }
            return true;
        } catch (Exception e) {
            // v1.44.0：root 区回退（/data 等 File API 无权限 → su cp/mv）。
            // 仅当拷贝本身未完成才回退；已完成拷贝的删除失败不回退（防重复）
            if (copied) {
                if (isMove && RootUtil.isRootAvailable()) RootIO.delete(src);
                return true;
            }
            return isMove ? RootIO.moveInto(src, dstDir) : RootIO.copyInto(src, dstDir);
        }
    }

    /**
     * 递归复制目录。
     *
     * <p>v1.97.0：实现搬到 {@link TreeOps#copyDir}，原因是这里原来用
     * {@code k.isDirectory()} 判断递归 —— 该调用会跟随符号链接，于是「复制一个链接目录」
     * 会把目标整棵树复制一份，链接成环时直接 StackOverflow。TreeOps 里链接按原样复刻、
     * 不跟进，并有深度上限。</p>
     */
    private static void copyDir(File src, File dst) throws Exception {
        TreeOps.copyDir(src, dst, TreeOps.SYSTEM, null);
    }

    /**
     * 复制 src 到指定目标路径 dstFile（对比模式同步用）：
     * 自动创建父目录；文件按截断覆盖写；目录递归合并复制（同名文件覆盖、额外文件保留）。
     */
    public static boolean copyOverwrite(File src, File dstFile) {
        try {
            if (src.isDirectory()) {
                copyDir(src, dstFile);
                return true;
            }
            File parent = dstFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            InputStream in = new FileInputStream(src);
            OutputStream out = new FileOutputStream(dstFile);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            return true;
        } catch (Exception e) {
            return RootIO.copyOverwrite(src, dstFile);   // v1.44.0 root 区回退
        }
    }

    /**
     * 递归删除。
     *
     * <p>v1.97.0：软链接只删链本身。原来的 {@code if (f.isDirectory())} 会跟随链接 ——
     * root 下选中一个指向普通目录的链接（不少 ROM 上 {@code /sdcard} 就是链接）删除时，
     * 会把目标里的内容全部删光，最后只删掉链，用户完全无法理解发生了什么。
     * 递归逻辑（含深度上限）现在统一在 {@link TreeOps#delete}。</p>
     */
    /**
     * 用 tmp 替换 target（压缩包写回、原地重打包用）。
     *
     * <p>v1.97.0：原来三处实现都是同一段危险代码：</p>
     * <pre>
     * if (!tmp.renameTo(zip)) {
     *     if (zip.delete() &amp;&amp; tmp.renameTo(zip)) return true;   // 原包已删
     *     tmp.delete();                                        // 新包也删
     *     return false;                                        // → 两个都没了
     * }
     * </pre>
     * <p>rename 失败（跨挂载点、目标被占用）时它先删原文件，第二次 rename 再失败就把新文件也删了，
     * 用户手里的压缩包连同刚写好的内容一起消失 —— 这是最不该出现的失败姿态。</p>
     *
     * <p>这里改成 {@code ApkEditSession.safeReplace} 的思路：<b>任何一步失败都不动原文件</b>，
     * 必须挪开时先改名成 {@code .bakrepl} 备份，复制成功才删备份；失败则还原。</p>
     *
     * @return 成功替换返回 true；失败时保证 target 仍是原来的内容（或作为 {@code .bakrepl} 完整存在）
     */
    public static boolean replaceFile(File tmp, File target) {
        if (tmp == null || target == null || !tmp.isFile()) return false;
        // 同一挂载点：rename 直接原子替换，既快又没有中间态
        if (tmp.renameTo(target)) return true;
        File parent = target.getParentFile();
        if (parent == null) return false;
        File bak = new File(parent, target.getName() + ".bakrepl");
        if (bak.exists() && !bak.delete()) return false;
        boolean hadOld = target.exists();
        if (hadOld && !target.renameTo(bak)) {
            // 原文件挪不开（被别的进程打开 / 权限）：原样保留，tmp 也留着供人工恢复
            return false;
        }
        if (tmp.renameTo(target)) {          // 原文件让位后空间/名称冲突消失，rename 可能成功
            bak.delete();
            return true;
        }
        if (copyFileTo(tmp, target)) {
            bak.delete();
            tmp.delete();
            return true;
        }
        // 复制失败：清掉可能写了一半的目标，再把备份改回来
        if (target.exists()) target.delete();
        if (hadOld) bak.renameTo(target);
        return false;
    }

    /** 流式复制单个文件（失败返回 false，不抛） */
    private static boolean copyFileTo(File src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
    }

    public static boolean deleteRecursive(File f) {
        boolean ok = TreeOps.delete(f);
        // v1.44.0：普通删除失败且文件仍在（root 区/系统文件）→ su rm -rf
        // 注意：RootIO.delete 是 `rm -rf <路径>`（不带尾斜杠），对链接同样只删链接
        if (!ok && f != null && f.exists() && RootUtil.isRootAvailable()) return RootIO.delete(f);
        return ok;
    }

    public static boolean rename(File f, String newName) {
        boolean ok = f.renameTo(new File(f.getParentFile(), newName));
        // v1.44.0：root 区回退
        if (!ok && RootUtil.isRootAvailable())
            return RootIO.rename(f, new File(f.getParentFile(), newName).getPath());
        return ok;
    }

    public static File newFolder(File dir, String name) {
        File f = new File(dir, name);
        // v1.44.0：root 区回退（mkdirs 失败且目录未建出 → su mkdir -p）
        if (!f.exists() && !f.mkdirs() && RootUtil.isRootAvailable()) RootIO.mkdirs(f);
        return f;
    }

    public static String formatSize(long len) {
        if (len < 1024) return len + " B";
        if (len < 1048576) return String.format(Locale.US, "%.1f KB", len / 1024.0);
        if (len < 1073741824) return String.format(Locale.US, "%.1f MB", len / 1048576.0);
        return String.format(Locale.US, "%.2f GB", len / 1073741824.0);
    }

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    public static String formatTime(long ms) {
        return FMT.format(new Date(ms));
    }

    /** 计算文件 MD5 / SHA-256 / CRC32 */
    public static String hash(File f, String alg) {
        try {
            if ("CRC32".equals(alg)) {
                CRC32 crc = new CRC32();
                InputStream in = new FileInputStream(f);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
                in.close();
                return String.format(Locale.US, "%08X", crc.getValue());
            }
            MessageDigest md = MessageDigest.getInstance(alg);
            InputStream in = new FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest())
                sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "错误: " + e.getMessage();
        }
    }
}
