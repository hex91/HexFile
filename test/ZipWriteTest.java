package bin.HexFile;

import com.hex91.file.ZipUtil;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** v1.14 桌面单测：ZipUtil.writeEntry / removeEntries（重建式写回与删除） */
public class ZipWriteTest {
    static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        File dir = new File("ziptest_work");
        rm(dir); dir.mkdirs();

        // 造样本 zip：a/readme.txt, a/b/code.smali, assets/config.xml, lib/x.so(STORED)
        File zip = new File(dir, "sample.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            put(zos, "a/readme.txt", "hello v1\nline2".getBytes("UTF-8"));
            put(zos, "a/b/code.smali", ".method hi\nreturn-void\n.end method".getBytes("UTF-8"));
            put(zos, "assets/config.xml", "<config/>".getBytes("UTF-8"));
            byte[] so = new byte[5000]; new Random(42).nextBytes(so);
            ZipEntry se = new ZipEntry("lib/x.so");
            se.setMethod(ZipEntry.STORED);
            se.setSize(so.length); se.setCompressedSize(so.length);
            CRC32 c = new CRC32(); c.update(so); se.setCrc(c.getValue());
            zos.putNextEntry(se); zos.write(so); zos.closeEntry();
        }

        // ---- 测试 1：替换已有条目 ----
        check(ZipUtil.writeEntry(zip, "a/readme.txt", "EDITED-99".getBytes("UTF-8")), "writeEntry 替换返回 true");
        eq(read(zip, "a/readme.txt"), "EDITED-99", "替换后内容正确");
        eq(read(zip, "a/b/code.smali"), ".method hi\nreturn-void\n.end method", "其它条目不受影响");
        eq(read(zip, "assets/config.xml"), "<config/>", "config 保留");

        // ---- 测试 2：新增条目（含子目录路径） ----
        check(ZipUtil.writeEntry(zip, "newdir/added.json", "{\"k\":1}".getBytes("UTF-8")), "writeEntry 新增返回 true");
        eq(read(zip, "newdir/added.json"), "{\"k\":1}", "新增条目可读");

        // ---- 测试 3：STORED 条目原样保留 ----
        byte[] so2 = readBytes(zip, "lib/x.so");
        check(so2.length == 5000, "STORED 条目大小不变(" + so2.length + ")");
        // 验证仍是 STORED
        boolean stored = false;
        try (ZipFile zf = new ZipFile(zip)) {
            stored = zf.getEntry("lib/x.so").getMethod() == ZipEntry.STORED;
        }
        check(stored, "STORED 压缩方式保留");

        // ---- 测试 4：删除条目（文件 + 目录子树） ----
        List<String> kill = new ArrayList<String>();
        kill.add("a/b");                                   // 目录 → 应删掉 a/b/code.smali
        kill.add("assets/config.xml");
        check(ZipUtil.removeEntries(zip, kill), "removeEntries 返回 true");
        check(!exists(zip, "a/b/code.smali"), "目录子树已删除");
        check(!exists(zip, "assets/config.xml"), "指定文件已删除");
        check(exists(zip, "a/readme.txt"), "同级其它条目保留");
        eq(read(zip, "a/readme.txt"), "EDITED-99", "删除后其它条目内容不变");
        check(exists(zip, "lib/x.so"), "STORED 条目删除操作后保留");

        // ---- 测试 5：APK 签名剥离 ----
        File apk = new File(dir, "sample.apk");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(apk))) {
            put(zos, "AndroidManifest.xml", "<manifest/>".getBytes("UTF-8"));
            put(zos, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0".getBytes("UTF-8"));
            put(zos, "META-INF/CERT.SF", "Signature".getBytes("UTF-8"));
            put(zos, "META-INF/CERT.RSA", new byte[]{1, 2, 3});
            put(zos, "META-INF/services/java.lang.Object", "x".getBytes("UTF-8")); // 应保留
        }
        check(ZipUtil.writeEntry(apk, "AndroidManifest.xml", "<manifest v2/>".getBytes("UTF-8")), "apk writeEntry true");
        check(!exists(apk, "META-INF/MANIFEST.MF"), "apk: MANIFEST.MF 剥离");
        check(!exists(apk, "META-INF/CERT.SF"), "apk: CERT.SF 剥离");
        check(!exists(apk, "META-INF/CERT.RSA"), "apk: CERT.RSA 剥离");
        check(exists(apk, "META-INF/services/java.lang.Object"), "apk: services 保留");
        eq(read(apk, "AndroidManifest.xml"), "<manifest v2/>", "apk: 条目已更新");

        System.out.println("==== RESULT: pass=" + pass + " fail=" + fail + " ====");
        rm(dir);
        System.exit(fail == 0 ? 0 : 1);
    }

    // ---------- helpers ----------
    static void put(ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }
    static String read(File zip, String name) throws Exception {
        return new String(readBytes(zip, name), "UTF-8");
    }
    static byte[] readBytes(File zip, String name) throws Exception {
        try (ZipFile zf = new ZipFile(zip)) {
            InputStream in = zf.getInputStream(zf.getEntry(name));
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        }
    }
    static boolean exists(File zip, String name) throws Exception {
        try (ZipFile zf = new ZipFile(zip)) { return zf.getEntry(name) != null; }
    }
    static void check(boolean b, String label) {
        System.out.println((b ? "[PASS] " : "[FAIL] ") + label);
        if (b) pass++; else fail++;
    }
    static void eq(String got, String want, String label) {
        check(want.equals(got), label + (want.equals(got) ? "" : "  (got=" + got + ")"));
    }
    static void rm(File f) {
        if (f.isDirectory()) for (File k : f.listFiles()) rm(k);
        f.delete();
    }
}
