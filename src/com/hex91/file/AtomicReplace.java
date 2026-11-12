package com.hex91.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * v1.99.1：用临时文件覆盖原文件的<b>唯一</b>实现。
 *
 * <p>这条路径有三个调用方，各自的失败代价都是「用户的东西没了」，所以只能有一份代码：</p>
 * <ul>
 *   <li>{@code ZipUtil} / {@code ArchiveWrite}：改压缩包后写回原包（v1.97.0 的 O-19）；</li>
 *   <li>{@code ApkEditSession}：重打包 / 重签名后原地覆盖 APK（S-1 的配套）。</li>
 * </ul>
 *
 * <p><b>为什么不能「先删原文件再改名」</b>：{@code rename} 在跨挂载点、目标被占用时会失败。
 * 原实现是 {@code if (!tmp.renameTo(zip)) { zip.delete(); tmp.renameTo(zip); }} ，
 * 第二次改名再失败就把写好的新文件也删了 —— 用户手里「原包 + 新包一起消失」。
 * 这里把顺序反过来：<b>先把原文件挪成备份，复制成功才删备份；任何一步失败都还原</b>。</p>
 *
 * <p><b>为什么不直接 copy 覆盖</b>：{@code FileOutputStream} 打开即截断，写到一半失败
 * （空间不足、进程被杀）原文件已经毁了 —— 所以必须先有一份完整备份，而不是边写边赌。</p>
 */
public final class AtomicReplace {

    private AtomicReplace() {}

    /** 失败时原文件挪成的备份后缀；看到它说明上一次替换失败、原内容在里面 */
    public static final String BAK_SUFFIX = ".bakrepl";

    /**
     * 用 {@code tmp} 覆盖 {@code target}，成功返回 true。
     *
     * <p>失败不抛异常，供「失败 = 返回 false + 界面提示」的调用方使用。</p>
     */
    public static boolean tryReplace(File tmp, File target) {
        try {
            replace(tmp, target);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 用 {@code tmp} 覆盖 {@code target}。
     *
     * <p>成功返回后 {@code tmp} 已被消费（删除）；失败时 {@code tmp} 保留，
     * 与备份一起供人工恢复 —— 宁可留垃圾，也不删掉可能是唯一完好副本的文件。</p>
     *
     * @throws IOException 替换失败；抛出时 {@code target} 已尽量还原为原内容，
     *                     若连还原都失败，异常信息里会给出备份路径
     */
    public static void replace(File tmp, File target) throws IOException {
        if (tmp == null || target == null) throw new IOException("替换参数为空");
        if (!tmp.isFile()) throw new IOException("临时文件不存在: " + tmp);
        File parent = target.getParentFile();
        if (parent == null) throw new IOException("目标没有父目录: " + target);

        // 1) 同一挂载点：rename 原子替换，没有中间态，也不需要备份
        if (tmp.renameTo(target)) return;

        File bak = new File(parent, target.getName() + BAK_SUFFIX);
        if (bak.exists() && !bak.delete()) {
            // 旧备份清不掉（被占用）：不硬来，原文件一个字节都不动
            throw new IOException("旧备份无法清理: " + bak.getName());
        }
        boolean hadOld = target.exists();
        if (hadOld && !target.renameTo(bak)) {
            // rename 让不开（目标被别的进程打开 / 跨挂载点）→ 先复制出完整备份，再删原文件。
            // 复制失败会抛异常，此时原文件仍完好 —— 这是这条路径唯一能接受的失败方式。
            copy(target, bak);
            if (!target.delete()) {
                bak.delete();
                throw new IOException("原文件无法挪开: " + target.getName());
            }
        }

        // 2) 原文件让位后先再试一次 rename：同一目录内这一步几乎总能成功，
        //    比整份复制快一个数量级（几十 MB 的压缩包差距是毫秒 vs 秒）
        if (tmp.renameTo(target)) {
            bak.delete();
            return;
        }

        // 3) 跨挂载点：只能复制。失败时删掉半成品并把备份改回原名
        try {
            copy(tmp, target);
        } catch (IOException e) {
            if (target.exists()) target.delete();
            if (hadOld && bak.exists() && !bak.renameTo(target)) {
                throw new IOException("替换失败且还原失败，原内容保留在 " + bak.getPath());
            }
            throw e;
        }
        bak.delete();
        // 可能被 mmap 锁住删不掉：不影响「target 已是新内容」这个结果
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
    }

    /** 流式复制（64KB 缓冲）；不完整就抛，绝不留下「看起来成功」的短文件 */
    private static void copy(File src, File dst) throws IOException {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }
}
