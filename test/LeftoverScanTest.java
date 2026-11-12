import com.hex91.file.fs.LeftoverScan;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * v1.99.2（R-06）：遗留文件（{@code .bakrepl} / {@code .replacebak} / {@code .part}）扫描。
 *
 * <p>这些文件必须保留（可能是唯一完好的副本），但要能被找出来、并且只在用户明确
 * 点击时才删除。用例守住两点：<b>认得出</b>、<b>绝不误删正经文件</b>。</p>
 */
public class LeftoverScanTest {

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
                "hexfile_leftover_" + System.nanoTime());
        base.mkdirs();
        try {
            t1Scan(base);
            t2Owner(base);
            t3DeleteGuard(base);
        } finally {
            deleteTree(base);
        }
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /** 三种后缀都要认出来，普通文件不能混进来 */
    static void t1Scan(File base) throws Exception {
        File root = new File(base, "t1");
        new File(root, "sub").mkdirs();
        write(new File(root, "a.zip.bakrepl"), "old");
        write(new File(root, "b.apk.replacebak"), "old");
        write(new File(root, "c.bin.part"), "half");
        write(new File(root, "sub/d.zip.bakrepl"), "old");
        write(new File(root, "normal.txt"), "keep");
        write(new File(root, "part.txt"), "keep");       // 名字里带 part 但不是 .part 结尾

        List<LeftoverScan.Hit> hits = LeftoverScan.scan(root);
        eqi(4, hits.size(), "扫出 4 个遗留文件");
        long total = 0;
        for (LeftoverScan.Hit h : hits) total += h.size;
        eqi(3 + 3 + 4 + 3, total, "大小合计（供界面展示占用）");

        eqi(0, LeftoverScan.scan(new File(base, "nope")).size(), "目录不存在返回空");
        eqi(0, LeftoverScan.scan(null).size(), "null 返回空");
    }

    /** 展示用：能说清「这是哪个文件的备份」 */
    static void t2Owner(File base) throws Exception {
        File root = new File(base, "t2");
        write(new File(root, "installer.apk.bakrepl"), "x");
        write(new File(root, "movie.mp4.part"), "x");
        List<LeftoverScan.Hit> hits = LeftoverScan.scan(root);
        eqi(2, hits.size(), "两个遗留文件");
        boolean apk = false, mp4 = false;
        for (LeftoverScan.Hit h : hits) {
            if ("installer.apk".equals(h.owner)) apk = true;
            if ("movie.mp4".equals(h.owner)) mp4 = true;
        }
        ok(apk, "从 installer.apk.bakrepl 认出原文件 installer.apk");
        ok(mp4, "从 movie.mp4.part 认出原文件 movie.mp4");
        ok(LeftoverScan.isLeftover(new File(root, "x.bakrepl")), "isLeftover 认备份");
        ok(!LeftoverScan.isLeftover(new File(root, "x.txt")), "isLeftover 不认普通文件");
        ok(!LeftoverScan.isLeftover(null), "isLeftover(null) = false");
    }

    /** 删除入口的双保险：传入正经文件时必须拒绝 */
    static void t3DeleteGuard(File base) throws Exception {
        File root = new File(base, "t3");
        File keep = new File(root, "keep.txt");
        write(keep, "不能删");
        File part = new File(root, "x.part");
        write(part, "half");

        ok(!LeftoverScan.delete(keep), "对普通文件调用 delete 被拒绝");
        ok(keep.isFile(), "普通文件还在（关键：不误删）");
        ok(LeftoverScan.delete(part), "遗留文件可以删除");
        ok(!part.exists(), "遗留文件已删除");
        ok(LeftoverScan.delete(new File(root, "gone.part")), "删除不存在的遗留文件视为成功");
    }

    static void write(File f, String s) throws Exception {
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(s.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    static void deleteTree(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
