
import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * v1.44.0 Root 区文件读写封装。
 * 背景：普通 File API 在 /data、/system 等 root 区无权限（读/写/删/建全部失败），
 * 本类用 su 命令实现同等操作，供各调用方在普通路径失败后回退。
 * 设计：
 *  - 所有方法自带 RootUtil.isRootAvailable() 守卫，无 root 直接返回 false/null，不抛异常
 *  - 写/删/改名成功后自动失效 FileAttrs 缓存
 *  - 引号统一走 q()（单引号转义，与 FileAttrs/RootUtil 一致）
 */
public final class RootIO {

    /** 读文件字节上限（32MB）：编辑器/图片场景足够，哈希走 catProcess 流式不受限 */
    public static final long READ_LIMIT = 32L * 1024 * 1024;

    private RootIO() {}

    private static String q(String p) { return "'" + p.replace("'", "'\\''") + "'"; }

    /** 读整个文件（二进制安全）。非 root / 失败 / 超限返回 null */
    public static byte[] readBytes(File f) {
        if (f == null || !RootUtil.isRootAvailable()) return null;
        return RootUtil.captureOutput("cat " + q(f.getPath()), READ_LIMIT);
    }

    /** 读整个文件为 UTF-8 文本。失败返回 null */
    public static String readText(File f) {
        byte[] b = readBytes(f);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** 覆盖写文件（不存在则创建）。成功后失效属性缓存 */
    public static boolean writeBytes(File f, byte[] data) {
        if (f == null || data == null || !RootUtil.isRootAvailable()) return false;
        boolean ok = RootUtil.runWithInput("cat > " + q(f.getPath()), data);
        if (ok) FileAttrs.invalidate(f);
        return ok;
    }

    /** 覆盖写 UTF-8 文本 */
    public static boolean writeText(File f, String s) {
        return writeBytes(f, s.getBytes(StandardCharsets.UTF_8));
    }

    /** 删除（文件或目录递归，等价 rm -rf） */
    public static boolean delete(File f) {
        if (f == null || !RootUtil.isRootAvailable()) return false;
        boolean ok = RootUtil.run("rm -rf " + q(f.getPath()));
        if (ok) FileAttrs.invalidate(f);
        return ok;
    }

    /** 重命名 / 同区移动（等价 mv -f src newPath） */
    public static boolean rename(File f, String newPath) {
        if (f == null || newPath == null || !RootUtil.isRootAvailable()) return false;
        boolean ok = RootUtil.run("mv -f " + q(f.getPath()) + " " + q(newPath));
        if (ok) FileAttrs.invalidate(f);
        return ok;
    }

    /** 创建目录（含父目录，等价 mkdir -p） */
    public static boolean mkdirs(File dir) {
        if (dir == null || !RootUtil.isRootAvailable()) return false;
        return RootUtil.run("mkdir -p " + q(dir.getPath()));
    }

    /** 复制到目标目录（dst = dstDir/name；保留属性，目录递归，等价 cp -a） */
    public static boolean copyInto(File src, File dstDir) {
        if (src == null || dstDir == null || !RootUtil.isRootAvailable()) return false;
        File dst = new File(dstDir, src.getName());
        return RootUtil.run("cp -a " + q(src.getPath()) + " " + q(dst.getPath()));
    }

    /** 移动到目标目录（dst = dstDir/name） */
    public static boolean moveInto(File src, File dstDir) {
        if (src == null || dstDir == null || !RootUtil.isRootAvailable()) return false;
        File dst = new File(dstDir, src.getName());
        boolean ok = RootUtil.run("mv -f " + q(src.getPath()) + " " + q(dst.getPath()));
        if (ok) FileAttrs.invalidate(src);
        return ok;
    }

    /** 覆盖式复制到指定路径（文件或目录；对比同步用） */
    public static boolean copyOverwrite(File src, File dstFile) {
        if (src == null || dstFile == null || !RootUtil.isRootAvailable()) return false;
        boolean ok = RootUtil.run("cp -a " + q(src.getPath()) + " " + q(dstFile.getPath()));
        if (ok) FileAttrs.invalidate(dstFile);
        return ok;
    }

    /**
     * 流式读（大文件哈希用）：返回 su cat 进程，调用方读 getInputStream()，
     * 读完自行 waitFor + destroy。无 root 或启动失败抛异常（调用方按失败处理）。
     */
    public static Process catProcess(File f) throws Exception {
        if (f == null || !RootUtil.isRootAvailable()) throw new IllegalStateException("no root");
        return RootUtil.catProcess("cat " + q(f.getPath()));
    }
}
