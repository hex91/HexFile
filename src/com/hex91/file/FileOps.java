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

    private static void copyDir(File src, File dst) throws Exception {
        dst.mkdirs();
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File d = new File(dst, k.getName());
            if (k.isDirectory()) copyDir(k, d);
            else {
                InputStream in = new FileInputStream(k);
                OutputStream out = new FileOutputStream(d);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                out.close();
            }
        }
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

    public static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        boolean ok = f.delete();
        // v1.44.0：普通删除失败且文件仍在（root 区/系统文件）→ su rm -rf
        if (!ok && f.exists() && RootUtil.isRootAvailable()) return RootIO.delete(f);
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
