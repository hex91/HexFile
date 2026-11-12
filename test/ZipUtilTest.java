import com.hex91.file.fs.TreeOps;
import com.hex91.file.fs.ZipUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * v1.99.2（R-01）：压缩「添加目录」不能跟进软链接。
 *
 * <p>这个 bug 的形状和 S-1 一样，但后果是体积/耗时爆炸而不是丢数据：压缩一个指向
 * 几百兆目录的链接，会把目标整棵树打进 zip；链接成环时递归直接 StackOverflow。</p>
 *
 * <p>桌面没有 lstat，所以 {@link ZipUtil} 的入口接受 {@link TreeOps.LinkProbe}：
 * 桩里「名字以 ln- 开头的算链接」，与真机走的是同一条遍历代码。</p>
 */
public class ZipUtilTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eqi(long a, long b, String msg) {
        if (a == b) { pass++; System.out.println("PASS  " + msg + " = " + b); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + a + " 实际 " + b); }
    }

    /** 名字 ln- 开头的视为软链接（真机用 Os.lstat，规则同一条代码路径） */
    static final TreeOps.LinkProbe STUB = new TreeOps.LinkProbe() {
        public boolean isLink(File f) {
            return f != null && f.getName().startsWith("ln-");
        }

        public boolean copyLink(File src, File dst) {
            return false;      // zip 里存不了链接，走「跳过」分支
        }
    };

    public static void main(String[] args) throws Exception {
        File base = new File(System.getProperty("java.io.tmpdir"),
                "hexfile_zip_" + System.nanoTime());
        base.mkdirs();
        try {
            t1NormalTree(base);
            t2LinkNotFollowed(base);
            t3AllLinksFails(base);
            t4DepthGuard(base);
        } finally {
            deleteTree(base);
        }
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /** 普通目录照旧：结构完整、条目名带父目录 */
    static void t1NormalTree(File base) throws Exception {
        File src = mk(base, "t1");
        write(new File(src, "a.txt"), "aaa");
        mk(src, "sub");
        write(new File(src, "sub/b.txt"), "bbb");

        File zip = new File(base, "t1.zip");
        boolean okZip = ZipUtil.createZip(zip, Arrays.asList(src));
        ok(okZip, "普通目录压缩成功");
        Set<String> names = names(zip);
        ok(names.contains("t1/"), "含目录条目（" + names + "）");
        ok(names.contains("t1/a.txt"), "含根级文件");
        ok(names.contains("t1/sub/b.txt"), "含子目录文件");
        // 目录条目本身也写入（t1/ 与 t1/sub/），共 4 条
        eqi(4, names.size(), "条目数正确");
    }

    /** 核心用例：目录里的链接不能被跟进，链接目标的内容一个字节都不该进包 */
    static void t2LinkNotFollowed(File base) throws Exception {
        File target = mk(base, "bigTarget");
        write(new File(target, "huge.bin"), repeat('x', 20000));
        mk(target, "deep");

        File src = mk(base, "t2");
        write(new File(src, "real.txt"), "r");
        // 模拟「指向 target 的链接」：真机上这里是一条 symlink，桌面用名字 + 桩
        File link = mk(src, "ln-bigTarget");
        write(new File(link, "huge.bin"), repeat('x', 20000));

        File zip = new File(base, "t2.zip");
        ok(ZipUtil.createZip(zip, Arrays.asList(src), STUB), "含链接的目录压缩成功");
        Set<String> names = names(zip);
        ok(names.contains("t2/real.txt"), "普通文件照常入包");
        ok(!names.contains("t2/ln-bigTarget/"), "链接目录条目本身被跳过");
        ok(!names.contains("t2/ln-bigTarget/huge.bin"), "链接目标的内容没有被打进包（关键）");
        ok(!names.contains("t2/ln-bigTarget/deep"), "链接目标的子目录没有被打进包");
    }

    /** 选中项全是链接：不能报告成功却给出一个空 zip */
    static void t3AllLinksFails(File base) throws Exception {
        File src = mk(base, "t3");
        File link = mk(src, "ln-only");
        write(new File(link, "x.txt"), "x");

        File zip = new File(base, "t3.zip");
        ok(!ZipUtil.createZip(zip, Arrays.asList(link), STUB), "全是链接时返回失败");
        ok(!zip.exists(), "失败时不留下空 zip");
    }

    /** 深度上限：70 层嵌套不能把栈打爆 */
    static void t4DepthGuard(File base) throws Exception {
        File root = mk(base, "t4");
        File cur = root;
        for (int i = 0; i < 70; i++) {
            cur = new File(cur, "d");
            if (!cur.mkdirs()) break;
        }
        write(new File(cur, "deep.txt"), "x");
        File zip = new File(base, "t4.zip");
        try {
            boolean r = ZipUtil.createZip(zip, Arrays.asList(root));
            ok(true, "深层目录压缩没有 StackOverflow（返回 " + r + "）");
        } catch (Throwable t) {
            ok(false, "深层目录压缩抛了 " + t.getClass().getSimpleName());
        }
        ok(TreeOps.MAX_DEPTH > 0, "深度上限已定义 = " + TreeOps.MAX_DEPTH);
    }

    // ------------------------------------------------------------------ 工具

    static Set<String> names(File zip) throws Exception {
        Set<String> out = new HashSet<String>();
        ZipFile zf = new ZipFile(zip);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) out.add(en.nextElement().getName());
        } finally {
            zf.close();
        }
        return out;
    }

    static File mk(File parent, String name) {
        File f = new File(parent, name);
        f.mkdirs();
        return f;
    }

    static void write(File f, String s) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(s.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    static void deleteTree(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
