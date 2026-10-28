package bin.HexFile;

import java.io.*;
import java.util.*;

/**
 * 加密 ZIP 回归测试（v1.33.1，基于 Zip4j 之后）
 * - 用 ZipUtil 生成 AES-256 与 兼容 ZipCrypto 两种加密 ZIP
 * - 用 Zip4j 独立读回并比对内容（Java 端 round-trip）
 * - 错误密码必须被拒绝
 * （真正的跨工具兼容由 verify_zip4j.py 用 pyzipper / 标准 zipfile 独立校验）
 */
public class ZipCryptTest {
    static File outDir = new File("test/_zipout");
    static File srcDir = new File(outDir, "src");
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + name);
        if (ok) pass++; else fail++;
    }

    static void writeFile(File f, byte[] data) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
    }
    static byte[] readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    static void buildSource() throws IOException {
        writeFile(new File(srcDir, "readme.txt"), "MTClone Zip4j AES test\n".getBytes("UTF-8"));
        byte[] payload = new byte[512];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        writeFile(new File(srcDir, "sub/payload.bin"), payload);
        writeFile(new File(srcDir, "small.dat"), "x".getBytes("UTF-8"));
    }

    static List<File> listFiles(File d) {
        List<File> r = new ArrayList<File>();
        File[] kids = d.listFiles();
        if (kids == null) return r;
        for (File k : kids) {
            if (k.isDirectory()) r.addAll(listFiles(k));
            else r.add(k);
        }
        return r;
    }
    static String rel(File f, File base) {
        String bp = base.getAbsolutePath().replace('\\', '/');
        String fp = f.getAbsolutePath().replace('\\', '/');
        return fp.substring(bp.length() + 1);
    }

    static boolean compareDir(File a, File b) throws IOException {
        List<File> fa = listFiles(a);
        List<File> fb = listFiles(b);
        if (fa.size() != fb.size()) return false;
        for (File f : fa) {
            File other = new File(b, rel(f, a));
            if (!other.exists()) return false;
            if (!Arrays.equals(readFile(f), readFile(other))) return false;
        }
        return true;
    }

    static void clean(File d) {
        if (!d.exists()) return;
        for (File k : d.listFiles() == null ? new File[0] : d.listFiles()) {
            if (k.isDirectory()) clean(k);
            k.delete();
        }
    }

    public static void main(String[] args) throws Exception {
        outDir.mkdirs();
        clean(outDir);
        buildSource();
        List<File> src = Arrays.asList(srcDir);

        File aesZip = new File(outDir, "sample_aes256.zip");
        check("createZipEncrypted(AES256) 成功",
                ZipUtil.createZipEncrypted(aesZip, src, "secret123", 3) && aesZip.exists());

        File compatZip = new File(outDir, "sample_zipcrypto.zip");
        check("createZipEncryptedCompat 成功",
                ZipUtil.createZipEncryptedCompat(compatZip, src, "secret123") && compatZip.exists());

        // Zip4j 独立读回（Java 端 round-trip）
        File exAes = new File(outDir, "ex_aes"); exAes.mkdirs();
        net.lingala.zip4j.ZipFile z1 = new net.lingala.zip4j.ZipFile(aesZip, "secret123".toCharArray());
        z1.extractAll(exAes.getAbsolutePath()); z1.close();
        check("AES zip 读回内容一致", compareDir(srcDir, exAes));

        File exC = new File(outDir, "ex_compat"); exC.mkdirs();
        net.lingala.zip4j.ZipFile z2 = new net.lingala.zip4j.ZipFile(compatZip, "secret123".toCharArray());
        z2.extractAll(exC.getAbsolutePath()); z2.close();
        check("ZipCrypto zip 读回内容一致", compareDir(srcDir, exC));

        // 错误密码必须失败
        boolean pwdFail = false;
        try {
            net.lingala.zip4j.ZipFile z3 = new net.lingala.zip4j.ZipFile(aesZip, "wrong".toCharArray());
            z3.extractAll(new File(outDir, "ex_bad").getAbsolutePath()); z3.close();
        } catch (Exception e) { pwdFail = true; }
        check("错误密码被拒绝", pwdFail);

        System.out.println("ZipCryptTest: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
