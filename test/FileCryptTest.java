package bin.HexFile;

import com.hex91.file.FileCrypt;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Random;

/** FileCrypt 桌面验证：加密/解密 round-trip、密码错误拒绝、大文件。 */
public class FileCryptTest {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  PASS " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        }
    }
    static boolean eq(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "mtcrypt_" + System.nanoTime());
        dir.mkdirs();

        // 1) 普通文本 round-trip
        File f1 = new File(dir, "hello.txt");
        byte[] t1 = "MT 文件加密测试 hello world 中文 🔒".getBytes("UTF-8");
        write(f1, t1);
        File e1 = FileCrypt.encrypt(f1, "pass123");
        check("加密生成 .enc", e1.exists() && e1.getName().equals("hello.txt.enc"));
        File d1 = FileCrypt.decrypt(e1, "pass123");
        check("解密文件名去 .enc", d1.getName().equals("hello.txt"));
        check("解密内容一致", eq(readAll(d1), t1));

        // 2) 密码错误拒绝
        boolean threw = false;
        try { FileCrypt.decrypt(e1, "wrongpass"); }
        catch (SecurityException | IllegalArgumentException ex) { threw = true; }
        check("密码错误被拒绝", threw);

        // 3) 二进制/大文件 round-trip
        File f2 = new File(dir, "blob.bin");
        byte[] big = new byte[1024 * 1024];
        new Random(12345).nextBytes(big);
        write(f2, big);
        File e2 = FileCrypt.encrypt(f2, "密钥🔑long");
        File d2 = FileCrypt.decrypt(e2, "密钥🔑long");
        check("大文件(1MB)解密一致", eq(readAll(d2), big));

        // 4) 空文件
        File f3 = new File(dir, "empty.txt");
        write(f3, new byte[0]);
        File e3 = FileCrypt.encrypt(f3, "p");
        File d3 = FileCrypt.decrypt(e3, "p");
        check("空文件加密解密", eq(readAll(d3), new byte[0]));

        // 5) 非加密文件解密应失败
        boolean threw2 = false;
        try { FileCrypt.decrypt(f1, "p"); } catch (IllegalArgumentException ex) { threw2 = true; }
        check("非加密文件解密失败", threw2);

        System.out.println("FileCryptTest: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void write(File f, byte[] data) throws IOException {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(data); }
    }
}
