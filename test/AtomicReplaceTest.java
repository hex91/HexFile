import com.hex91.file.core.AtomicReplace;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * v1.99.1 原子替换（AtomicReplace）。
 *
 * <p>这个类被三处共用：压缩包写回（ZipUtil / ArchiveWrite）、APK 原地覆盖（ApkEditSession）。
 * 它们的失败代价都是「用户的文件没了」，所以用例盯的不是「能替换成功」，
 * 而是<b>替换失败时原文件还在不在</b>、以及成功之后有没有遗留备份。</p>
 *
 * <p>跨挂载点那条只在 Linux 上跑（{@code /dev/shm} 与 {@code java.io.tmpdir} 通常是不同文件系统，
 * {@code renameTo} 必然失败，正好逼出「备份 → 复制 → 清理」这条路径）。Windows 上跳过。</p>
 */
public class AtomicReplaceTest {

    static int pass = 0, fail = 0, skip = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        File base = new File(System.getProperty("java.io.tmpdir"),
                "hexfile_atomic_" + System.nanoTime());
        base.mkdirs();
        try {
            sameVolume(base);
            noTarget(base);
            missingTmp(base);
            messageOnFailure(base);
            crossVolume(base);
        } finally {
            deleteTree(base);
        }
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail + " skip=" + skip);
        if (fail > 0) System.exit(1);
    }

    /** 同一目录：走 rename 原子替换，不该留下 .bakrepl */
    static void sameVolume(File base) throws Exception {
        File dir = mk(base, "same");
        File target = new File(dir, "a.zip");
        File tmp = new File(dir, "a.reb.tmp");
        write(target, "旧内容");
        write(tmp, "新内容");

        ok(AtomicReplace.tryReplace(tmp, target), "同卷替换成功");
        ok("新内容".equals(read(target)), "目标已是新内容");
        ok(!tmp.exists(), "临时文件已消费");
        ok(!bak(target).exists(), "没有遗留备份");
    }

    /** 目标不存在（新建场景）：不应有任何备份逻辑介入 */
    static void noTarget(File base) throws Exception {
        File dir = mk(base, "new");
        File target = new File(dir, "fresh.apk");
        File tmp = new File(dir, "fresh.tmp");
        write(tmp, "内容");

        ok(AtomicReplace.tryReplace(tmp, target), "目标不存在时替换成功");
        ok(target.isFile() && "内容".equals(read(target)), "文件已就位");
    }

    /** 临时文件缺失：必须明确失败，且原文件一个字节都不动 */
    static void missingTmp(File base) throws Exception {
        File dir = mk(base, "missing");
        File target = new File(dir, "keep.zip");
        write(target, "原内容");

        boolean threw = false;
        try {
            AtomicReplace.replace(new File(dir, "nope.tmp"), target);
        } catch (IOException e) {
            threw = true;
        }
        ok(threw, "临时文件缺失时抛 IOException");
        ok(!AtomicReplace.tryReplace(new File(dir, "nope.tmp"), target), "boolean 版返回 false");
        ok("原内容".equals(read(target)), "失败时原文件内容不变");
    }

    /**
     * 跨挂载点：renameTo 必然失败 → 备份 → 复制 → 删备份。
     * 这条路径是 O-19 的核心：老代码在这里删原文件，第二次 rename 再失败就把新文件也删了。
     */
    static void crossVolume(File base) throws Exception {
        File tmpDir = new File("/dev/shm");
        if (!tmpDir.isDirectory()) {
            skip++; System.out.println("SKIP  跨卷用例（没有 /dev/shm）");
            return;
        }
        File target = new File(mk(base, "cross"), "big.zip");
        File tmp = new File(tmpDir, "hexfile_atomic_" + System.nanoTime() + ".tmp");
        try {
            if (sameStore(tmp.getParentFile(), target.getParentFile())) {
                skip++; System.out.println("SKIP  跨卷用例（/dev/shm 与 tmpdir 是同一个文件系统）");
                return;
            }
            byte[] oldData = new byte[300 * 1024];
            for (int i = 0; i < oldData.length; i++) oldData[i] = (byte) (i % 251);
            writeBytes(target, oldData);
            write(tmp, "跨卷新内容");

            ok(AtomicReplace.tryReplace(tmp, target), "跨卷替换成功");
            ok("跨卷新内容".equals(read(target)), "跨卷后目标内容正确");
            ok(!bak(target).exists(), "跨卷后备份已清理");
            ok(!tmp.exists(), "跨卷后临时文件已消费");
        } finally {
            tmp.delete();
        }
    }

    /**
     * v1.99.2（R-06）：失败必须能说清「东西去哪了」。
     *
     * <p>界面上的提示原来只有一句「压缩失败」，而磁盘上多了一个 {@code .bakrepl} ——
     * 用户既不知道它是什么，也不敢删。这里要求失败消息里带上临时文件路径。</p>
     */
    static void messageOnFailure(File base) throws Exception {
        File target = new File(base, "msg.txt");
        write(target, "old");
        File tmp = new File(base, "msg.txt.tmp");      // 故意不创建
        AtomicReplace.Result r = AtomicReplace.replaceWith(tmp, target);
        ok(!r.ok, "临时文件不存在 → 替换失败");
        ok(r.message != null && r.message.length() > 0, "失败消息非空");
        ok(r.message != null && r.message.contains("msg.txt.tmp"),
                "失败消息带上临时文件路径（" + r.message + "）");
        ok(AtomicReplace.lastError != null && AtomicReplace.lastError.equals(r.message),
                "lastError 与返回消息一致（界面可直接拼进提示）");
        ok("old".equals(read(target)), "失败时原文件内容未变");
    }

    // ------------------------------------------------------------------ 工具

    static File bak(File target) {
        return new File(target.getParentFile(), target.getName() + AtomicReplace.BAK_SUFFIX);
    }

    static boolean sameStore(File a, File b) {
        try {
            return Files.getFileStore(a.toPath()).equals(Files.getFileStore(b.toPath()));
        } catch (Exception e) {
            return true;   // 判断不了就当作同卷，跳过用例
        }
    }

    static File mk(File parent, String name) {
        File f = new File(parent, name);
        f.mkdirs();
        return f;
    }

    static void write(File f, String s) throws Exception {
        writeBytes(f, s.getBytes("UTF-8"));
    }

    static void writeBytes(File f, byte[] data) throws Exception {
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(data); } finally { o.close(); }
    }

    static String read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(b, 0, n, "UTF-8");
        } finally {
            in.close();
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
