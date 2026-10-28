
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Random;

/**
 * StringExtract 桌面测试（v1.38.0）：
 *  1. ASCII 串提取 + 偏移正确；短于 minLen 剔除
 *  2. UTF-16LE 串提取 + 偏移正确；双 \0 分隔的两条串不粘连
 *  3. 字符串跨越 64KB 缓冲边界不漏（ASCII 与 UTF-16 各一）
 *  4. minLen 参数边界收敛（0 → 2）
 *  5. 空文件 / 纯二进制无命中不报错
 */
public class StringExtractTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        File root = new File("test/_strx_t");
        deleteR(root);
        root.mkdirs();

        // ---------- 1) 基础 ASCII + UTF-16 ----------
        byte[] b = new byte[0];
        b = cat(b, "Hello, World!\n".getBytes());                  // ASCII 命中（\n 断尾）
        b = cat(b, new byte[]{0x00, 0x01, 0x02});                  // 二进制填充
        b = cat(b, "abc");                                          // 短串，minLen=4 剔除
        b = cat(b, new byte[]{0x00, 0x00});                        // 双 \0 分隔（UTF-16 串规范边界）
        byte[] u16 = "Test".getBytes("UTF-16LE");                  // 54 00 65 00 73 00 74 00
        int u16Off = b.length;
        b = cat(b, u16);
        b = cat(b, new byte[]{0x00, 0x00});                        // 双 \0 终止
        byte[] u16b = "Second".getBytes("UTF-16LE");
        int u16bOff = b.length;
        b = cat(b, u16b);                                           // 紧随其后，验证不粘连
        b = cat(b, new byte[]{0x00, 0x00});
        int offStr = 0;
        File f1 = new File(root, "t1.bin");
        write(f1, b);

        StringExtract.Result r = StringExtract.extract(f1, 4);
        check("无错误", r.error == null);
        check("ASCII 命中 Hello, World! 偏移 0",
                hit(r, false, 0, "Hello, World!"));
        check("短串 abc 被剔除", !hitText(r, "abc"));
        check("UTF-16 Test 命中偏移 " + u16Off, hit(r, true, u16Off, "Test"));
        check("UTF-16 Second 命中偏移 " + u16bOff + "（不粘连）",
                hit(r, true, u16bOff, "Second"));
        check("无 TestSecond 粘连串", !hitText(r, "TestSecond"));

        // ---------- 2) 跨 64KB 缓冲边界 ----------
        // ASCII 串骑在边界上：前 65530 字节随机二进制（无 4 连可打印），再放 10 字节串
        byte[] c = new byte[65530];
        fillNonPrintable(c);
        int offA = c.length;
        c = cat(c, "BoundaryAA".getBytes());                        // 10 字节，跨界
        File f2 = new File(root, "t2.bin");
        write(f2, c);
        r = StringExtract.extract(f2, 4);
        check("ASCII 跨界串命中偏移 " + offA, hit(r, false, offA, "BoundaryAA"));

        // UTF-16 串跨界
        byte[] d = new byte[65531];
        fillNonPrintable(d);
        int offU = d.length;
        d = cat(d, "CrossU16".getBytes("UTF-16LE"));               // 16 字节，跨界
        File f3 = new File(root, "t3.bin");
        write(f3, d);
        r = StringExtract.extract(f3, 4);
        check("UTF-16 跨界串命中偏移 " + offU, hit(r, true, offU, "CrossU16"));

        // ---------- 3) minLen 边界 ----------
        r = StringExtract.extract(f1, 0);
        check("minLen 0 收敛为 2：abc 命中", hitText(r, "abc"));
        r = StringExtract.extract(f1, 32);
        check("minLen 32：Hello, World! 仍在（14 字符 <32? 否，应无）",
                !hitText(r, "Hello, World!"));

        // ---------- 4) 空文件 / 纯二进制 ----------
        File f4 = new File(root, "empty.bin");
        write(f4, new byte[0]);
        r = StringExtract.extract(f4, 4);
        check("空文件无命中无错误", r.error == null && r.hits.isEmpty());
        File f5 = new File(root, "raw.bin");
        byte[] raw = new byte[10000];
        fillNonPrintable(raw);
        write(f5, raw);
        r = StringExtract.extract(f5, 4);
        check("纯二进制无命中", r.error == null && r.hits.isEmpty());

        // ---------- 5) 扫描字节数 ----------
        r = StringExtract.extract(f1, 4);
        check("scannedBytes = 文件大小 " + f1.length(), r.scannedBytes == f1.length());

        deleteR(root);
        System.out.println();
        System.out.println("PASS " + passed + " / FAIL " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------- 断言辅助 ----------
    static boolean hit(StringExtract.Result r, boolean u16, long off, String text) {
        for (StringExtract.Hit h : r.hits) {
            if (h.utf16 == u16 && h.offset == off && h.text.equals(text)) return true;
        }
        return false;
    }

    static boolean hitText(StringExtract.Result r, String text) {
        for (StringExtract.Hit h : r.hits) {
            if (h.text.contains(text)) return true;
        }
        return false;
    }

    // ---------- 构造辅助 ----------
    static byte[] cat(byte[] a, byte[] b2) {
        byte[] out = new byte[a.length + b2.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b2, 0, out, a.length, b2.length);
        return out;
    }

    static byte[] cat(byte[] a, String s) { return cat(a, s.getBytes()); }

    /** 填充无 4 连可打印的伪随机字节（保留 \0 与高位字节，避开连续 ASCII） */
    static void fillNonPrintable(byte[] buf) {
        Random rnd = new Random(99);
        for (int i = 0; i < buf.length; i++) {
            int v = rnd.nextInt(256);
            if (v >= 0x20 && v <= 0x7E) v ^= 0x80;   // 强制高位，破坏可打印连串
            buf[i] = (byte) v;
        }
    }

    static void write(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
    }

    static void deleteR(File f) {
        if (f.isDirectory()) { File[] ks = f.listFiles(); if (ks != null) for (File k : ks) deleteR(k); }
        f.delete();
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
