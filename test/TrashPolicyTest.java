import com.hex91.file.Trash;
import com.hex91.file.TrashPolicy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.List;

/**
 * v1.82.0 回收站保留策略（桌面 JVM，纯 Java）。
 *
 * <p>回收站清理是「删东西」的功能，测不到位就会误删用户的文件，所以这里把
 * 天数、容量、开关、边界（0 = 不限）和元数据解析全部覆盖。</p>
 */
public class TrashPolicyTest {

    static int pass = 0, fail = 0;
    static final File ROOT = new File("build/trashtest");
    static final long DAY = 24L * 3600 * 1000;
    static long NOW = 1700000000000L;   // 固定时间，避免跑测试时跨天

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
        // ---------------------------------------------------------- 元数据解析
        del(ROOT);
        ROOT.mkdirs();
        File trash = new File(ROOT, "Trash");
        seed(trash, "1000_a", "old.txt", NOW - 40 * DAY, 100);
        seed(trash, "2000_b", "mid.txt", NOW - 10 * DAY, 100);
        seed(trash, "3000_c", "new.txt", NOW - 1 * DAY, 100);

        List<Trash.Entry> list = Trash.listIn(trash);
        eq(3, list.size(), "listIn：读出 3 条");
        eq(NOW - 1 * DAY, list.get(0).time, "listIn：按 id 倒序，新的在前");
        ok("new.txt".equals(list.get(0).name), "listIn：名称正确");
        ok(list.get(0).store.isFile(), "listIn：store 指向真实文件");

        // ---------------------------------------------------------- 天数清理
        TrashPolicy p = new TrashPolicy();
        p.enabled = true;
        p.maxDays = 30;
        p.maxBytes = 0;          // 不限容量，只测天数
        TrashPolicy.Report rep = p.apply(trash, NOW);
        eq(1, rep.removed, "天数：过期 1 条被删");
        eq(100, rep.freed, "天数：释放 100 字节");
        eq(2, Trash.listIn(trash).size(), "天数：剩 2 条");

        // ---------------------------------------------------------- 关闭开关
        p.enabled = false;
        seed(trash, "4000_d", "x.txt", NOW - 999 * DAY, 100);
        TrashPolicy.Report off = p.apply(trash, NOW);
        ok(off.skipped, "开关关闭：标记 skipped");
        eq(0, off.removed, "开关关闭：一条都不删");
        eq(3, Trash.listIn(trash).size(), "开关关闭：条目数不变");

        // ---------------------------------------------------------- 容量清理（最旧优先）
        p.enabled = true;
        p.maxDays = 0;           // 不限天数
        p.maxBytes = 200;        // 总 300 字节 → 删掉最旧的 100 后正好 200，停止
        TrashPolicy.Report cap = p.apply(trash, NOW);
        eq(1, cap.removed, "容量：删 1 条最旧的");
        List<Trash.Entry> left = Trash.listIn(trash);
        eq(2, left.size(), "容量：剩 2 条");
        ok(!hasName(left, "x.txt"), "容量：最旧的 x.txt 被删");
        ok(hasName(left, "new.txt"), "容量：最新的 new.txt 保留");

        // ---------------------------------------------------------- 容量为 0 = 不限
        p.maxBytes = 0;
        TrashPolicy.Report none = p.apply(trash, NOW);
        eq(0, none.removed, "容量 0：不做容量清理");

        // ---------------------------------------------------------- 目录递归大小
        File bigDir = new File(new File(trash, "store"), "9000_dir");
        bigDir.mkdirs();
        file(new File(bigDir, "a.bin"), 30);
        file(new File(bigDir, "sub/b.bin"), 70);
        meta(trash, "9000_dir", "folder", NOW - 2 * DAY, true);
        eq(100, Trash.sizeOf(bigDir), "sizeOf：目录递归求和");

        p.maxBytes = 10;          // 只剩最新的 100 字节目录 → 全删
        TrashPolicy.Report all = p.apply(trash, NOW);
        eq(3, all.removed, "容量极小时：全部清掉");
        eq(0, Trash.listIn(trash).size(), "容量极小时：回收站为空");

        // ---------------------------------------------------------- 策略持久化
        TrashPolicy q = new TrashPolicy();
        q.enabled = false;
        q.maxDays = 7;
        q.maxBytes = 12345678;
        ok(q.save(trash), "保存：写入成功");
        TrashPolicy r = TrashPolicy.load(trash);
        ok(!r.enabled, "读取：enabled=false 保持");
        eq(7, r.maxDays, "读取：maxDays 保持");
        eq(12345678, r.maxBytes, "读取：maxBytes 保持");

        // ---------------------------------------------------------- 损坏的配置不炸
        write(new File(trash, TrashPolicy.CONF_NAME), "enabled=maybe\nmaxDays=abc\nmaxBytes=\n");
        TrashPolicy bad = TrashPolicy.load(trash);
        ok(!bad.enabled, "损坏配置：enabled 回退默认（关闭）");
        eq(TrashPolicy.DEFAULT_DAYS, bad.maxDays, "损坏配置：maxDays 回退默认");
        eq(TrashPolicy.DEFAULT_BYTES, bad.maxBytes, "损坏配置：maxBytes 回退默认");

        del(ROOT);
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ 工具

    static void seed(File trash, String id, String name, long time, int size) throws Exception {
        file(new File(new File(trash, "store"), id), size);
        meta(trash, id, name, time, false);
    }

    static void meta(File trash, String id, String name, long time, boolean isDir) throws Exception {
        File md = new File(trash, "meta");
        md.mkdirs();
        write(new File(md, id + ".txt"),
                name + "\n" + "/sdcard/" + name + "\n" + time + "\n" + isDir + "\n");
    }

    static void file(File f, int size) throws Exception {
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        out.write(new byte[size]);
        out.close();
    }

    static void write(File f, String s) throws Exception {
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        w.write(s);
        w.close();
    }

    static boolean hasName(List<Trash.Entry> list, String name) {
        for (Trash.Entry e : list) if (name.equals(e.name)) return true;
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
