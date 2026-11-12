import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.TreeOps;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * v1.97.0：递归树操作的软链接保护（S-1）+ 压缩包写回的安全替换（O-19）。
 *
 * <p>为什么要有这个用例：这两个问题的共同点是「真机才能复现、而且复现即数据丢失」——
 * 软链接得在 ext4 上建（Windows 桌面要管理员权限，Android 的 /sdcard 是 FUSE 不支持链接），
 * 压缩包写回的失败分支要跨挂载点才触发。等到真机上才发现，代价是用户已经丢过一次数据。</p>
 *
 * <p>所以 {@link TreeOps} 把「是不是链接」「怎么复刻链接」抽成了 {@code LinkProbe}：
 * 真机用 {@code Os.lstat/symlink}，这里用桩 —— 文件名以 {@code ln-} 开头的当链接，
 * 复刻时写一个内容为 "-> 目标" 的小文件。判断规则本身（不跟进、只删链、跳过统计）
 * 与真机完全同一条代码路径。</p>
 */
public class TreeOpsTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eqi(long expect, long got, String msg) {
        if (expect == got) { pass++; System.out.println("PASS  " + msg + " = " + got); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got); }
    }

    // ------------------------------------------------------------------ 桩

    /** 名字以 ln- 开头的文件视为软链接 */
    static final TreeOps.LinkProbe STUB = new TreeOps.LinkProbe() {
        public boolean isLink(File f) {
            return f != null && f.getName().startsWith("ln-");
        }

        public boolean copyLink(File src, File dst) {
            try {
                write(dst, "-> stub");
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    };

    /** 声称是链接、但复刻不了（FAT32 / 无权限） */
    static final TreeOps.LinkProbe STUB_NO_COPY = new TreeOps.LinkProbe() {
        public boolean isLink(File f) {
            return f != null && f.getName().startsWith("ln-");
        }

        public boolean copyLink(File src, File dst) {
            return false;
        }
    };

    /** 系统实现：桌面 JVM 上 Os.lstat 是 stub，一律判为「不是链接」，用来验证普通目录不受影响 */
    static final TreeOps.LinkProbe NONE = TreeOps.SYSTEM;

    // ------------------------------------------------------------------ 入口

    public static void main(String[] args) throws Exception {
        File base = new File(System.getProperty("java.io.tmpdir"),
                "hexfile_treeops_" + System.nanoTime());
        base.mkdirs();
        try {
            t1DeleteLinkKeepsTarget(base);
            t2DeleteNormalStillRecursive(base);
            t3SizeSkipsLink(base);
            t4CopyDoesNotFollow(base);
            t5CopyLinkUnsupported(base);
            t6DepthGuard(base);
            t7CopyLinkSource(base);
            // v1.99.1：临时文件替换的用例已迁到 AtomicReplaceTest（三处共用一份实现）
        } finally {
            deleteTree(base);
        }
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ 用例

    /**
     * 核心用例：删「指向目录的链接」只能删链本身。
     * 这条不成立时的真实后果——root 下删 /sdcard 一个链接，把整个内部存储删空。
     */
    static void t1DeleteLinkKeepsTarget(File base) throws Exception {
        File root = mk(base, "t1");
        File target = mk(root, "target");
        write(new File(target, "a.txt"), "重要数据");

        File holder = mk(root, "holder");
        write(new File(holder, "ln-target"), "stub");
        write(new File(holder, "real.txt"), "x");

        boolean okDel = TreeOps.delete(holder, STUB);
        ok(okDel, "删除含链接的目录返回成功");
        ok(!holder.exists(), "链接所在目录已删除");
        ok(new File(target, "a.txt").isFile(), "链接目标里的文件仍在（关键：没被跟进删除）");
        ok(target.isDirectory(), "链接目标目录仍在");
    }

    /** 普通目录的递归删除不能因为这次改动而失效 */
    static void t2DeleteNormalStillRecursive(File base) throws Exception {
        File root = mk(base, "t2");
        File d = mk(root, "dir");
        mk(d, "sub");
        write(new File(d, "f1.txt"), "1");
        write(new File(d, "sub/f2.txt"), "2");

        ok(TreeOps.delete(d, STUB), "递归删除普通目录成功");
        ok(!d.exists(), "普通目录连同子目录一并删除");
        ok(!new File(d, "sub/f2.txt").exists(), "深层文件也删掉了");
    }

    /** 统计不跟进链接：否则同一批文件被重复计数，链接成环时直接爆栈 */
    static void t3SizeSkipsLink(File base) throws Exception {
        File root = mk(base, "t3");
        File target = mk(root, "target");
        write(new File(target, "big.bin"), repeat('a', 4000));

        File holder = mk(root, "holder");
        write(new File(holder, "real.txt"), "12345");     // 5 字节
        write(new File(holder, "ln-target"), "stub");     // 6 字节，按链接不该计入

        eqi(5L, TreeOps.size(holder, STUB), "统计大小只算真实文件，链接按 0 计");
        // 反面证据：把链接当普通文件时会更小/更大都会不同，这里明确对比两个探针的差值
        long naive = TreeOps.size(holder, NONE);
        ok(naive == 5L + "stub".length(), "对照：不认链接时会把桩文件一起算进去（" + naive + "）");
    }

    /** 复制不跟进链接：目标整棵树不该被复制一份 */
    static void t4CopyDoesNotFollow(File base) throws Exception {
        File root = mk(base, "t4");
        File target = mk(root, "target");
        write(new File(target, "secret.txt"), "不该被复制");
        mk(target, "deep");

        File src = mk(root, "src");
        write(new File(src, "real.txt"), "r");
        write(new File(src, "ln-target"), "stub");        // 模拟指向 target 的链接

        File dst = mk(root, "dst");
        TreeOps.copyDir(src, dst, STUB, null);

        ok(new File(dst, "real.txt").isFile(), "普通文件已复制");
        ok(new File(dst, "ln-target").isFile(), "链接按链接复刻（桩写成小文件）");
        ok(!new File(dst, "secret.txt").exists(), "链接目标的内容没有被复制过来");
        ok(!new File(dst, "deep").exists(), "链接目标的子目录没有被复制过来");
    }

    /** 文件系统不支持链接（FAT32）：跳过并计数，不能退化成「跟进复制」 */
    static void t5CopyLinkUnsupported(File base) throws Exception {
        File root = mk(base, "t5");
        File src = mk(root, "src");
        write(new File(src, "real.txt"), "r");
        write(new File(src, "ln-x"), "stub");

        File dst = mk(root, "dst");
        int[] skipped = new int[1];
        TreeOps.copyDir(src, dst, STUB_NO_COPY, skipped);

        ok(new File(dst, "real.txt").isFile(), "普通文件照常复制");
        ok(!new File(dst, "ln-x").exists(), "复刻失败的链接被跳过");
        eqi(1, skipped[0], "跳过数被记录（供上层提示）");
    }

    /** 深度上限：70 层嵌套不该把栈打爆 */
    static void t6DepthGuard(File base) throws Exception {
        File root = mk(base, "t6");
        File cur = root;
        for (int i = 0; i < 70; i++) {
            cur = new File(cur, "d");
            if (!cur.mkdirs()) break;
        }
        write(new File(cur, "deep.txt"), "x");

        long s;
        try {
            s = TreeOps.size(root, STUB);
            ok(true, "深层目录统计没有 StackOverflow（结果 " + s + "）");
        } catch (Throwable t) {
            ok(false, "深层目录统计抛了 " + t.getClass().getSimpleName());
        }
        // 超过上限的那部分按 0 计，属预期行为；这里只要求「不崩、不无限递归」
        ok(TreeOps.MAX_DEPTH > 0, "深度上限已定义 = " + TreeOps.MAX_DEPTH);
    }

    /**
     * v1.99.2（R-02）：<b>顶层</b>就是链接时同样不能跟进。
     *
     * <p>之前只在递归里判子项，入口直接 {@code dst.mkdirs() + src.listFiles()} ——
     * 于是「复制一个看起来像目录的链接」会把链接目标的整棵树复制一份。
     * 真机上这种「看起来像目录的链接」很常见（不少 ROM 的 /sdcard 本身就是链接）。</p>
     */
    static void t7CopyLinkSource(File base) throws Exception {
        File root = mk(base, "t7");
        File target = mk(root, "target");
        write(new File(target, "secret.txt"), "不该被复制");
        mk(target, "deep");

        // 名字 ln- 开头 → 桩视为链接；这里让它「目标内容」与 target 同构
        File srcLink = mk(root, "ln-src");
        write(new File(srcLink, "secret.txt"), "不该被复制");
        mk(srcLink, "deep");

        File dst = new File(root, "dst");
        TreeOps.copyDir(srcLink, dst, STUB, null);
        ok(dst.exists(), "顶层链接在目标位置被复刻成链接");
        ok(!new File(dst, "secret.txt").exists(), "链接指向的内容没有被复制过来");
        ok(!new File(dst, "deep").exists(), "链接指向的子目录没有被复制过来");

        // 文件系统不支持链接：有跳过出口 → 跳过并计数，不留空目录
        File dst2 = new File(root, "dst2");
        int[] skipped = new int[1];
        TreeOps.copyDir(srcLink, dst2, STUB_NO_COPY, skipped);
        ok(!dst2.exists(), "复刻不了时不留下空目录骗人");
        eqi(1, skipped[0], "跳过数被记录");

        // 没有跳过出口 → 必须明确失败，而不是悄悄返回一个空目录
        boolean threw = false;
        try {
            TreeOps.copyDir(srcLink, new File(root, "dst3"), STUB_NO_COPY, null);
        } catch (IOException e) {
            threw = true;
        }
        ok(threw, "没有跳过出口时抛异常而不是静默成功");
    }

    // ------------------------------------------------------------------ 工具

    static File mk(File parent, String name) {
        File f = new File(parent, name);
        f.mkdirs();
        return f;
    }

    static void write(File f, String s) throws IOException {
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes("UTF-8"));
        } finally {
            o.close();
        }
    }

    static String read(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            return new String(b, 0, Math.max(0, n), "UTF-8");
        } finally {
            in.close();
        }
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
