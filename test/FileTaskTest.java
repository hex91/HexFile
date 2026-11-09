import com.hex91.file.FileTask;
import com.hex91.file.FileTaskEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v1.77.0 文件任务引擎回归（桌面 JVM，纯 Java 无 android 依赖）。
 *
 * <p>这套测试盯的是「会毁数据」和「会骗用户」的几件事：</p>
 * <ul>
 *   <li>复制到源文件自己所在的目录——旧 FileOps 会先截断再读，直接把文件清空；</li>
 *   <li>取消后残留 {@code .part} 半截文件；</li>
 *   <li>进度永远 0 或永远 100（没有真实分母、或收尾没对齐总量）；</li>
 *   <li>移动走 rename 快路径后百分比停在 0。</li>
 * </ul>
 */
public class FileTaskTest {

    static int pass = 0, fail = 0;
    static final File ROOT = new File("build/tasktest");

    static void ok(boolean v, String msg) {
        if (v) {
            pass++;
            System.out.println("PASS  " + msg);
        } else {
            fail++;
            System.out.println("FAIL  " + msg);
        }
    }

    static void eq(long expect, long got, String msg) {
        ok(expect == got, msg + "（期望 " + expect + "，实际 " + got + "）");
    }

    public static void main(String[] args) throws Exception {
        del(ROOT);
        ROOT.mkdirs();
        FileTaskEngine eng = FileTaskEngine.get();

        // ---------------------------------------------------------- 单文件复制
        {
            File src = dir("single_src");
            File f = file(new File(src, "a.bin"), 300 * 1024);
            File dst = dir("single_dst");
            FileTask t = eng.submit(FileTask.COPY, list(f), dst);
            waitDone(t, 20000);
            File out = new File(dst, "a.bin");
            ok(t.state == FileTask.DONE, "单文件复制：状态 DONE");
            ok(out.isFile() && out.length() == f.length(), "单文件复制：大小一致");
            ok(sameContent(f, out), "单文件复制：内容一致");
            eq(f.length(), t.totalBytes, "单文件复制：totalBytes 为源大小");
            eq(f.length(), t.doneBytes, "单文件复制：doneBytes 收尾对齐总量");
            eq(100, t.percent(), "单文件复制：百分比 100");
            ok(!hasPart(dst), "单文件复制：不残留 .part");
        }

        // ---------------------------------------------------------- 目录递归
        {
            File src = dir("tree_src");
            int n = 0;
            long bytes = 0;
            for (int i = 0; i < 3; i++) {
                File sub = new File(src, "d" + i);
                sub.mkdirs();
                for (int j = 0; j < 4; j++) {
                    File f = file(new File(sub, "f" + i + "_" + j + ".dat"), 10 * 1024 + i * 7 + j);
                    bytes += f.length();
                    n++;
                }
            }
            File dst = dir("tree_dst");
            FileTask t = eng.submit(FileTask.COPY, list(src), dst);
            waitDone(t, 20000);
            ok(t.state == FileTask.DONE, "目录复制：状态 DONE");
            eq(n, t.totalFiles, "目录复制：文件数统计正确");
            eq(bytes, t.totalBytes, "目录复制：字节数统计正确");
            ok(new File(dst, "tree_src/d2/f2_3.dat").isFile(), "目录复制：三级子目录结构正确");
            ok(!hasPart(dst), "目录复制：不残留 .part");
        }

        // ---------------------------------------------------------- 进度单调
        {
            final List<Integer> pcts = Collections.synchronizedList(new ArrayList<Integer>());
            FileTaskEngine.get().setListener(new FileTaskEngine.Listener() {
                public void onTasksChanged() { }
            });
            File src = dir("prog_src");
            List<File> srcs = new ArrayList<File>();
            for (int i = 0; i < 8; i++) srcs.add(file(new File(src, "p" + i), 400 * 1024));
            FileTask t = eng.submit(FileTask.COPY, srcs, dir("prog_dst"));
            long end = System.currentTimeMillis() + 20000;
            int last = -1;
            boolean mono = true;
            while (!t.finished() && System.currentTimeMillis() < end) {
                int p = t.percent();
                if (p < last) mono = false;
                last = p;
                Thread.sleep(5);
            }
            waitDone(t, 5000);
            ok(mono, "进度单调不回退");
            eq(100, t.percent(), "进度收尾到 100");
            pcts.clear();
        }

        // ---------------------------------------------------------- 取消：不留半截
        {
            File src = dir("cancel_src");
            List<File> srcs = new ArrayList<File>();
            for (int i = 0; i < 60; i++) srcs.add(file(new File(src, "c" + i + ".bin"), 2 * 1024 * 1024));
            File dst = dir("cancel_dst");
            FileTask t = eng.submit(FileTask.COPY, srcs, dst);
            long end = System.currentTimeMillis() + 30000;
            boolean cancelled = false;
            while (System.currentTimeMillis() < end) {
                if (t.doneBytes > 1024 * 1024 && t.cancelable()) {
                    eng.cancel(t.id);
                    cancelled = true;
                    break;
                }
                if (t.finished()) break;
                Thread.sleep(2);
            }
            waitDone(t, 20000);
            ok(cancelled, "取消：拷到一半时发出取消");
            ok(t.state == FileTask.CANCELLED, "取消：状态 CANCELLED（实际 " + t.state + "）");
            ok(!hasPart(dst), "取消：正式名之外不残留 .part");
            // 已完成的文件必须是完整长度，不能是半截
            File[] done = dst.listFiles();
            boolean allFull = true;
            if (done != null) {
                for (File f : done) {
                    if (f.isFile() && f.length() % (2 * 1024 * 1024) != 0) allFull = false;
                }
            }
            ok(allFull, "取消：已落盘的文件都是完整长度");
        }

        // ---------------------------------------------------------- 同分区移动（rename 快路径）
        {
            File src = dir("move_src");
            File f = file(new File(src, "m.bin"), 500 * 1024);
            File dst = dir("move_dst");
            FileTask t = eng.submit(FileTask.MOVE, list(f), dst);
            waitDone(t, 20000);
            ok(t.state == FileTask.DONE, "移动：状态 DONE");
            ok(!f.exists(), "移动：源已删除");
            File out = new File(dst, "m.bin");
            ok(out.isFile() && out.length() == 500 * 1024, "移动：目标完整");
            eq(100, t.percent(), "移动：rename 后百分比仍是 100");
        }

        // ---------------------------------------------------------- 复制到自己所在目录（旧实现会清空文件）
        {
            File src = dir("self_src");
            File f = file(new File(src, "self.bin"), 128 * 1024);
            long before = f.length();
            FileTask t = eng.submit(FileTask.COPY, list(f), src);
            waitDone(t, 20000);
            ok(t.state == FileTask.DONE, "同目录复制：当成已完成而不是报错");
            eq(before, f.length(), "同目录复制：源文件未被截断（回归：旧 FileOps 会清空）");
        }

        // ---------------------------------------------------------- 覆盖同名文件
        {
            File src = dir("ow_src");
            File f = file(new File(src, "ow.bin"), 64 * 1024);
            writeGarbage(f, 0x5A);
            File dst = dir("ow_dst");
            File old = new File(dst, "ow.bin");
            file(old, 4096);
            writeGarbage(old, 0x11);
            FileTask t = eng.submit(FileTask.COPY, list(f), dst);
            waitDone(t, 20000);
            ok(t.state == FileTask.DONE, "覆盖：状态 DONE");
            ok(sameContent(f, old), "覆盖：目标内容被新内容替换");
            ok(!hasPart(dst), "覆盖：不残留 .part");
        }

        // ---------------------------------------------------------- 排队中取消
        {
            File src = dir("queue_src");
            List<File> srcs = new ArrayList<File>();
            for (int i = 0; i < 20; i++) srcs.add(file(new File(src, "q" + i), 1024 * 1024));
            File dst = dir("queue_dst");
            FileTask a = eng.submit(FileTask.COPY, srcs, dst);
            FileTask b = eng.submit(FileTask.COPY, srcs, dir("queue_dst2"));
            eng.cancel(b.id);
            waitDone(a, 30000);
            waitDone(b, 10000);
            ok(b.state == FileTask.CANCELLED, "排队取消：没有开始拷贝就记为 CANCELLED");
        }

        eng.clearFinished();
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ 工具

    static void waitDone(FileTask t, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end && !t.finished()) Thread.sleep(10);
    }

    static List<File> list(File f) {
        List<File> l = new ArrayList<File>();
        l.add(f);
        return l;
    }

    static File dir(String name) {
        File d = new File(ROOT, name);
        d.mkdirs();
        return d;
    }

    static File file(File f, int size) throws Exception {
        f.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        byte[] buf = new byte[8192];
        int left = size;
        int seed = (int) (f.length() + f.getName().hashCode());
        while (left > 0) {
            int n = Math.min(buf.length, left);
            for (int i = 0; i < n; i++) buf[i] = (byte) (seed + i);
            out.write(buf, 0, n);
            left -= n;
        }
        out.close();
        return f;
    }

    static void writeGarbage(File f, int v) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        byte[] buf = new byte[(int) f.length()];
        for (int i = 0; i < buf.length; i++) buf[i] = (byte) v;
        out.write(buf);
        out.close();
    }

    static boolean sameContent(File a, File b) throws Exception {
        if (a.length() != b.length()) return false;
        FileInputStream ia = new FileInputStream(a);
        FileInputStream ib = new FileInputStream(b);
        byte[] ba = new byte[8192], bb = new byte[8192];
        int na, nb;
        while (true) {
            na = ia.read(ba);
            nb = ib.read(bb);
            if (na != nb) { ia.close(); ib.close(); return false; }
            if (na <= 0) break;
            for (int i = 0; i < na; i++) if (ba[i] != bb[i]) { ia.close(); ib.close(); return false; }
        }
        ia.close();
        ib.close();
        return true;
    }

    static boolean hasPart(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File k : kids) {
            if (k.isDirectory()) {
                if (hasPart(k)) return true;
            } else if (k.getName().endsWith(".part")) {
                return true;
            }
        }
        return false;
    }

    static void del(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) del(k);
        }
        f.delete();
    }
}
