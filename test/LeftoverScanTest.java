import com.hex91.file.fs.LeftoverScan;
import com.hex91.file.fs.TempFiles;

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
            t4RebTmpAndFunctionalData(base);   // v2.4.0（R-11）
            t5ActiveWriting(base);            // v2.4.0（R-08）
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

    /**
     * v2.4.0（R-11）：压缩包写回留下的 {@code .reb<ts>.tmp} 必须能扫出来；
     * 同时把功能数据明确排除 —— {@code .parts}（断点续传清单与分片）、
     * {@code .merged}（合并输出）都是要用的东西，列出来只会诱导用户删掉。
     */
    static void t4RebTmpAndFunctionalData(File base) throws Exception {
        File root = new File(base, "t4");
        write(new File(root, "a.zip.reb1699999999999.tmp"), "tmp");
        write(new File(root, "notes.tmp"), "名字里有 tmp 但不是我们写的");
        write(new File(root, "a.merged"), "合并输出，不是临时文件");
        File parts = new File(root, "a.parts");
        parts.mkdirs();
        write(new File(parts, "000001.part"), "分片");
        write(new File(parts, "000002.part"), "分片");

        List<LeftoverScan.Hit> hits = LeftoverScan.scan(root);
        eqi(1, hits.size(), "只扫出 .reb<ts>.tmp 这一个");
        ok("a.zip".equals(hits.get(0).owner), "owner 剥掉 .reb<ts>.tmp 得到 a.zip（"
                + hits.get(0).owner + "）");
        ok(!LeftoverScan.isLeftover(new File(root, "notes.tmp")), "普通 .tmp 不算遗留");
        ok(!LeftoverScan.isLeftover(new File(root, "a.merged")), ".merged 不算遗留");
        ok(!LeftoverScan.isLeftover(new File(root, "a.parts")), ".parts 目录名不算遗留");
    }

    /**
     * v2.4.0（R-08）：正在写的临时文件不能被列出来、更不能被删。
     *
     * <p>真实后果：1 GB 复制进行中打开「设置 → 遗留文件」，列表里出现了那个
     * {@code .part}；用户确认删除后，写线程仍持有已 unlink 的句柄，
     * rename 必然失败，拷贝在几百 MB 处报错。</p>
     */
    static void t5ActiveWriting(File base) throws Exception {
        File root = new File(base, "t5");
        File part = new File(root, "big.bin.part");
        write(part, "正在写");

        TempFiles.begin(part);
        try {
            eqi(0, LeftoverScan.scan(root).size(), "写入中：扫描结果不含该 .part");
            eqi(0, LeftoverScan.scan(root, TempFiles.snapshot()).size(),
                    "写入中：按活动快照扫描同样不含");
            ok(!LeftoverScan.delete(part), "写入中：delete 返回 false");
            ok(part.isFile(), "写入中：文件仍在（关键：任务没被打断）");
        } finally {
            TempFiles.end(part);
        }

        eqi(1, LeftoverScan.scan(root).size(), "写完之后才算遗留文件");
        ok(LeftoverScan.delete(part), "写完之后可以正常删");
        ok(!part.exists(), "写完之后删除生效");
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
