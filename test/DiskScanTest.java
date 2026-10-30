package bin.HexFile;
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
