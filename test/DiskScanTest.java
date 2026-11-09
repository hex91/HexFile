import com.hex91.file.DiskScanUtil;
import java.io.*;
import java.util.List;
public class DiskScanTest {
    static int n = 0;
    static void ok(String w) { n++; System.out.println("  ok: " + w); }
    public static void main(String[] a) throws Exception {
        File tmp = new File("out/disktest"); tmp.mkdirs(); clean(tmp);
        // 构造：a1/a2 重复(2MB 相同)，b 独立(2MB 不同)，sub/small 小文件
        byte[] content = "DUPCONTENT-DUPCONTENT-".getBytes(); // 22B*重复
        new File(tmp, "sub").mkdirs();
        write(new File(tmp, "a1.bin"), content, 90000);   // ~1.98MB
        write(new File(tmp, "a2.bin"), content, 90000);
        byte[] other = new byte[22];
        new java.util.Random(1).nextBytes(other);
        write(new File(tmp, "b.bin"), other, 90000);
        write(new File(new File(tmp, "sub"), "small.bin"), "tiny".getBytes(), 1);
        // 1. scanSize
        long total = DiskScanUtil.scanSize(tmp, null);
        ok("scanSize=" + total + " (expect " + (3*22*90000+4) + ")");
        if (total != 3L*22*90000 + 4) throw new AssertionError("size mismatch");
        // 2. findDuplicates（min 1MB）
        List<DiskScanUtil.DupGroup> g = DiskScanUtil.findDuplicates(tmp, 1024*1024, null);
        if (g.size() != 1) throw new AssertionError("expect 1 dup group, got " + g.size());
        DiskScanUtil.DupGroup grp = g.get(0);
        ok("dup group: " + grp.files.size() + " files, size=" + grp.size);
        if (grp.files.size() != 2) throw new AssertionError("expect a1+a2");
        // 3. hashFull
        String h1 = DiskScanUtil.hashFull(new File(tmp, "a1.bin"), "MD5");
        String h2 = DiskScanUtil.hashFull(new File(tmp, "a2.bin"), "MD5");
        String h3 = DiskScanUtil.hashFull(new File(tmp, "b.bin"), "MD5");
        ok("md5 same=" + h1.equals(h2) + " diff=" + !h1.equals(h3));
        if (!h1.equals(h2) || h1.equals(h3)) throw new AssertionError("hash");
        if (h1.length() != 32) throw new AssertionError("md5 len");
        String s = DiskScanUtil.hashFull(new File(tmp, "a1.bin"), "SHA-256");
        if (s.length() != 64) throw new AssertionError("sha256 len");
        ok("sha256 len=64");

        // 4. analyze（v1.73.0）：单遍得到总大小/子目录占用/大文件 TopN + 真实进度分母
        final int[] calls = new int[]{0};
        final int[] lastTotal = new int[]{-1};
        final long[] lastBytes = new long[]{-1};
        DiskScanUtil.Result r = DiskScanUtil.analyze(tmp, 20, new DiskScanUtil.ScanProgress() {
            public void onProgress(int done, int total, long bytes) {
                calls[0]++; lastTotal[0] = total; lastBytes[0] = bytes;
            }
            public boolean isCancelled() { return false; }
        });
        if (r == null) throw new AssertionError("analyze 返回 null");
        if (r.total != total) throw new AssertionError("analyze total=" + r.total + " 应为 " + total);
        if (r.fileCount != 4) throw new AssertionError("fileCount=" + r.fileCount);
        if (r.dirCount != 1) throw new AssertionError("dirCount=" + r.dirCount);
        if (r.subDirs.size() != 1) throw new AssertionError("subDirs=" + r.subDirs.size());
        if (r.subDirs.get(0).size != 4) throw new AssertionError("sub size=" + r.subDirs.get(0).size);
        if (r.subDirs.get(0).fileCount != 1) throw new AssertionError("sub files");
        if (r.top.size() != 4) throw new AssertionError("top size=" + r.top.size());
        for (int i = 1; i < r.top.size(); i++) {
            if (r.top.get(i - 1).size < r.top.get(i).size) throw new AssertionError("top 未按降序");
        }
        ok("analyze total=" + r.total + " files=" + r.fileCount + " dirs=" + r.dirCount
                + " top=" + r.top.size() + " 进度回调=" + calls[0]);
        // 进度分母必须等于真实文件数，进度条才不会「虚」
        if (lastTotal[0] != 4) throw new AssertionError("进度分母=" + lastTotal[0] + " 应为 4");
        ok("进度分母=" + lastTotal[0]);

        // 5. topN 截断
        DiskScanUtil.Result r2 = DiskScanUtil.analyze(tmp, 2, null);
        if (r2.top.size() != 2) throw new AssertionError("topN=2 得到 " + r2.top.size());
        ok("topN=2 截断正确");

        // 6. 取消：应在阶段 1 立刻返回 null
        DiskScanUtil.Result r3 = DiskScanUtil.analyze(tmp, 20, new DiskScanUtil.ScanProgress() {
            public void onProgress(int done, int total, long bytes) {}
            public boolean isCancelled() { return true; }
        });
        if (r3 != null) throw new AssertionError("取消后应返回 null");
        ok("取消返回 null");

        System.out.println("DISK SCAN PASS (" + n + ")");
        clean(tmp);
    }
    static void write(File f, byte[] pattern, int repeats) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        for (int i = 0; i < repeats; i++) fo.write(pattern);
        fo.close();
    }
    static void clean(File d) {
        File[] cs = d.listFiles();
        if (cs == null) return;
        for (File c : cs) { if (c.isDirectory()) clean(c); c.delete(); }
    }
}
