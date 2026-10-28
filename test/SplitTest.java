package bin.HexFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * FileSplit 桌面测试：
 *  1. 随机数据分割→合并 字节级一致（非整除边界）
 *  2. 恰好整除的分割（末卷 = 整卷）
 *  3. 小于单卷 → 报错不分割
 *  4. 缺卷 → 合并报错
 *  5. 传入中间分卷也能定位起点合并
 *  6. 目标重名 → .merged 后缀，不覆盖
 */
public class SplitTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        File root = new File("test/_split_t");
        deleteR(root);
        root.mkdirs();

        // ---------- 1) 随机数据 round-trip（1025KB，卷 100KB → 非整除） ----------
        File src = new File(root, "video.mp4");
        byte[] data = new byte[1024 * 1024 + 1025];
        new Random(42).nextBytes(data);
        write(src, data);

        List<File> parts = FileSplit.split(src, root, 100 * 1024);
        check("分割卷数 = 11（实际 " + parts.size() + "）", parts.size() == 11);
        check("首卷名 video.mp4.001", parts.get(0).getName().equals("video.mp4.001"));
        check("末卷名 video.mp4.011", parts.get(10).getName().equals("video.mp4.011"));
        boolean namesOk = true;
        for (int i = 0; i < parts.size(); i++)
            if (!parts.get(i).getName().equals("video.mp4." + String.format("%03d", i + 1))) namesOk = false;
        check("全部序号连续命名", namesOk);

        FileSplit.MergeResult mr = FileSplit.merge(parts.get(0), new File(root, "out1"));
        check("合并输出名 video.mp4", mr.output.getName().equals("video.mp4"));
        check("合并卷数 = 11", mr.parts == 11);
        check("合并字节一致", Arrays.equals(read(mr.output), data));
        check("isSplitPart 识别分卷", FileSplit.isSplitPart(parts.get(0)) && !FileSplit.isSplitPart(src));

        // ---------- 2) 恰好整除（100KB 卷 × 5 = 500KB） ----------
        File src2 = new File(root, "exact.bin");
        byte[] data2 = new byte[500 * 1024];
        new Random(7).nextBytes(data2);
        write(src2, data2);
        List<File> p2 = FileSplit.split(src2, root, 100 * 1024);
        check("整除分割卷数 = 5（实际 " + p2.size() + "）", p2.size() == 5);
        check("整除末卷大小 = 100KB", p2.get(4).length() == 100 * 1024);
        FileSplit.MergeResult mr2 = FileSplit.merge(p2.get(2), new File(root, "out2"));  // 传中间卷
        check("中间卷合并字节一致", Arrays.equals(read(mr2.output), data2));

        // ---------- 3) 小于单卷 → 报错 ----------
        File small = new File(root, "small.txt");
        write(small, "hi".getBytes());
        boolean threw = false;
        try { FileSplit.split(small, root, 100 * 1024); }
        catch (Exception e) { threw = true; }
        check("小于单卷分割抛错", threw);

        // ---------- 4) 缺卷 → 合并报错 ----------
        File dir4 = new File(root, "gap");
        dir4.mkdirs();
        byte[] data4 = new byte[250 * 1024];
        new Random(9).nextBytes(data4);
        File src4 = new File(dir4, "gap.bin");
        write(src4, data4);
        List<File> p4 = FileSplit.split(src4, dir4, 100 * 1024);
        boolean del4 = p4.get(1).delete();  // 删除 .002
        check("删除中间卷成功", del4);
        boolean threw4 = false;
        try { FileSplit.merge(p4.get(0), dir4); }
        catch (Exception e) { threw4 = true; }
        check("缺卷合并抛错", threw4);

        // 缺起始卷
        boolean del1 = new File(dir4, "gap.bin.001").delete();
        check("删除起始卷成功", del1);
        boolean threw5 = false;
        try { FileSplit.merge(p4.get(2), dir4); }  // 从 .003 找不到 .001
        catch (Exception e) { threw5 = true; }
        check("缺起始卷合并抛错", threw5);

        // ---------- 4b) 尾卷缺失（启发式发现不了的）→ 靠 .parts 清单精确检测 ----------
        File dir4b = new File(root, "tail");
        dir4b.mkdirs();
        byte[] data4b = new byte[450 * 1024];
        new Random(13).nextBytes(data4b);
        File src4b = new File(dir4b, "tail.bin");
        write(src4b, data4b);
        List<File> p4b = FileSplit.split(src4b, dir4b, 100 * 1024);  // 5 卷
        check("存在 .parts 清单", new File(dir4b, "tail.bin.parts").isFile());
        boolean del4b = p4b.get(3).delete();  // 删除 .004（尾部连续前缀仍在，启发式无法发现）
        check("删除尾区卷成功", del4b);
        boolean threw4b = false;
        try { FileSplit.merge(p4b.get(0), dir4b); }
        catch (Exception e) { threw4b = true; }
        check("尾卷缺失按清单报错", threw4b);

        // 清单齐全时合并正常
        List<File> p4c = FileSplit.split(src4b, dir4b, 100 * 1024);  // 重新分割补齐
        FileSplit.MergeResult mr4c = FileSplit.merge(p4c.get(0), dir4b);
        check("补齐后合并字节一致", Arrays.equals(read(mr4c.output), data4b));

        // ---------- 5) 合并重名 → .merged 不覆盖 ----------
        File dir5 = new File(root, "dup");
        dir5.mkdirs();
        byte[] data5 = new byte[300 * 1024];
        new Random(11).nextBytes(data5);
        File src5 = new File(dir5, "dup.bin");
        write(src5, data5);
        List<File> p5 = FileSplit.split(src5, dir5, 100 * 1024);
        write(new File(dir5, "dup.bin"), "SENTINEL".getBytes());  // 占住原名
        FileSplit.MergeResult mr5 = FileSplit.merge(p5.get(0), dir5);
        check("重名输出 .merged", mr5.output.getName().equals("dup.bin.merged"));
        check(".merged 内容完整", Arrays.equals(read(mr5.output), data5));
        check("原同名文件未被覆盖", new File(dir5, "dup.bin").length() == 8);

        deleteR(root);
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) passed++; else failed++;
    }

    static void write(File f, byte[] b) throws Exception {
        OutputStream os = new FileOutputStream(f);
        os.write(b); os.close();
    }

    static byte[] read(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0;
        while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
        in.close();
        return b;
    }

    static void deleteR(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteR(c);
        f.delete();
    }
}
