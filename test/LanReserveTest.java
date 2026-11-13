import com.hex91.file.fs.TempFiles;
import com.hex91.file.net.LanProtocol;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * v2.5.0（R-19）：局域网接收的原子占名，以及 TempFiles 的写者计数。
 *
 * <p>原来「列目录 → uniqueName → new FileOutputStream」存在 check-then-create 窗口：
 * 两个同名请求并发时都选中同一个名字，共写一个 {@code .part}。先完成的一方 rename 走，
 * 另一方复制到的是交错数据，还会把对方正在写的文件删掉。</p>
 *
 * <p>用例不需要真实网络：冲突窗口在「选名字」这一步，用多线程对同一目录抢名即可复现。</p>
 */
public class LanReserveTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eqi(long a, long b, String msg) {
        if (a == b) { pass++; System.out.println("PASS  " + msg + " = " + b); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + a + " 实际 " + b); }
    }

    public static void main(String[] args) throws Exception {
        File base = new File(System.getProperty("java.io.tmpdir"),
                "hexfile_reserve_" + System.nanoTime());
        base.mkdirs();
        try {
            t1Sequential(base);
            t2Concurrent(base);
            t3ActiveCount();
            t4SafeName(base);
        } finally {
            deleteTree(base);
        }
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /** 顺序两次同名：第二个必须换号，且占位文件真实存在（0 字节） */
    static void t1Sequential(File base) throws Exception {
        File dir = new File(base, "t1");
        dir.mkdirs();
        File a = LanProtocol.reserve(dir, "a.txt");
        File b = LanProtocol.reserve(dir, "a.txt");
        ok(!a.getName().equals(b.getName()), "同名两次得到不同名字：" + a.getName() + " / " + b.getName());
        ok(a.getName().endsWith(TempFiles.PART), "占位文件带 .part 后缀：" + a.getName());
        ok(a.isFile() && b.isFile(), "两个占位文件都已创建");
        eqi(0, a.length(), "占位文件是 0 字节");

        // 名字里没有扩展名、以及带多个点的，都要能正确插入编号
        File c = LanProtocol.reserve(dir, "README");
        File d = LanProtocol.reserve(dir, "README");
        ok(!c.getName().equals(d.getName()), "无扩展名也能换号：" + d.getName());
        File e = LanProtocol.reserve(dir, "a.tar.gz");
        ok(e.getName().equals("a.tar.gz" + TempFiles.PART), "多扩展名保持原样：" + e.getName());
    }

    /** 并发抢名：4 线程 × 20 次，80 个名字必须互不重复，文件都在 */
    static void t2Concurrent(File base) throws Exception {
        final File dir = new File(base, "t2");
        dir.mkdirs();
        final int threads = 4, per = 20;
        final List<String> names = Collections.synchronizedList(new ArrayList<String>());
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        start.await();
                        for (int k = 0; k < per; k++) {
                            File f = LanProtocol.reserve(dir, "same.bin");
                            names.add(f.getName());
                            // 模拟写入后 rename 走：真实接收成功时会改名成正式文件
                            FileOutputStream o = new FileOutputStream(f);
                            try { o.write(new byte[]{1, 2, 3}); } finally { o.close(); }
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        start.countDown();
        done.await();

        eqi(0, errors.size(), "并发 reserve 没有抛异常");
        eqi((long) threads * per, names.size(), "拿到 " + (threads * per) + " 个名字");
        Set<String> uniq = new HashSet<String>(names);
        eqi(names.size(), uniq.size(), "名字互不重复（关键：不会共写同一个 .part）");
        int missing = 0;
        for (String n : names) if (!new File(dir, n).isFile()) missing++;
        eqi(0, missing, "每个占位文件都在磁盘上");
    }

    /** TempFiles 引用计数：两个写者，先结束的那一个不能把路径注销 */
    static void t3ActiveCount() {
        File f = new File(System.getProperty("java.io.tmpdir"), "hexfile_cnt_" + System.nanoTime() + ".part");
        TempFiles.begin(f);
        TempFiles.begin(f);
        ok(TempFiles.isActive(f), "两个写者登记后是活动状态");
        TempFiles.end(f);
        ok(TempFiles.isActive(f), "只剩一个写者时仍是活动状态（R-19 修复点）");
        eqi(1, TempFiles.snapshot().size(), "快照里仍有一条");
        TempFiles.end(f);
        ok(!TempFiles.isActive(f), "最后一个写者结束后不再是活动状态");
        eqi(0, TempFiles.snapshot().size(), "快照清空");
        TempFiles.end(f);   // 多余的 end 不能抛也不能变负数
        ok(!TempFiles.isActive(f), "多余的 end 无副作用");
    }

    /** 名字里的路径分隔符与非法字符要被清掉，不能借名字跳出接收目录 */
    static void t4SafeName(File base) throws Exception {
        File dir = new File(base, "t4");
        dir.mkdirs();
        File f = LanProtocol.reserve(dir, "../../evil:name?.txt");
        ok(dir.getCanonicalFile().equals(f.getCanonicalFile().getParentFile()),
                "占位文件仍在接收目录内");
        ok(f.getName().indexOf(':') < 0 && f.getName().indexOf('?') < 0, "非法字符被替换：" + f.getName());
    }

    static void deleteTree(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
