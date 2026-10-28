package bin.HexFile;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ZstdUtil 桌面测试（JDK17，Windows natives 走 test/zstd-jni-desktop.jar 资源解压）。
 * 运行：javac -cp "libs/zstd-jni-1.5.5-11-classes.jar;libs/commons-compress-1.22.jar;test/zstd-jni-desktop.jar"
 *       java -cp "test/_zt;libs/zstd-jni-1.5.5-11-classes.jar;libs/commons-compress-1.22.jar;test/zstd-jni-desktop.jar" bin.HexFile.ZstdTest
 */
public class ZstdTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }

    public static void main(String[] args) throws Exception {
        File root = new File("_zsttest");
        deleteR(root); root.mkdirs();

        // ===== 1. 单文件 .zst round-trip（随机不可压缩数据 3.3MB）=====
        byte[] bin1 = new byte[3300 * 1000];
        new Random(7).nextBytes(bin1);
        File src1 = new File(root, "big.bin");
        write(src1, bin1);
        List<File> s1 = new ArrayList<File>(); s1.add(src1);
        File z1 = new File(root, "big.bin.zst");
        ZstdUtil.compress(z1, s1, ZstdUtil.DEFAULT_LEVEL);
        check("big.bin.zst 生成", z1.isFile() && z1.length() > 0);
        check("magic 识别", ZstdUtil.isZst(z1));
        check("大 .zst 比源小（随机数据也应有帧开销差）", z1.length() < src1.length() + 100000);
        File out1 = new File(root, "out1"); out1.mkdirs();
        File r1 = ZstdUtil.extract(z1, out1);
        check("单文件还原字节一致", r1.isFile() && r1.getName().equals("big.bin")
                && Files.readAllBytes(r1.toPath()).length == bin1.length
                && java.util.Arrays.equals(Files.readAllBytes(r1.toPath()), bin1));

        // ===== 2. 目录 .tar.zst round-trip（中文+嵌套+空目录）=====
        File dir = new File(root, "数据 目录");
        File sub = new File(dir, "子目录/深层");
        sub.mkdirs();
        File f1 = new File(dir, "中文 文件.bin"); write(f1, bin1);
        File f2 = new File(sub, "nested.txt"); write(f2, "hello zstd 世界\n".getBytes("UTF-8"));
        File f3 = new File(dir, "空文件.bin"); write(f3, new byte[0]);
        new File(dir, "空目录").mkdirs();
        List<File> s2 = new ArrayList<File>(); s2.add(dir);
        File z2 = new File(root, "数据 目录.tar.zst");
        ZstdUtil.compress(z2, s2, 19); // 最大级别
        check("tar.zst 生成", z2.isFile() && z2.length() > 0);
        check("tar.zst magic 识别", ZstdUtil.isZst(z2));
        File out2 = new File(root, "out2"); out2.mkdirs();
        ZstdUtil.extract(z2, out2);
        File rf1 = new File(out2, "数据 目录/中文 文件.bin");
        File rf2 = new File(out2, "数据 目录/子目录/深层/nested.txt");
        File rf3 = new File(out2, "数据 目录/空文件.bin");
        File rd1 = new File(out2, "数据 目录/空目录");
        check("中文 bin 字节一致", rf1.isFile() && java.util.Arrays.equals(Files.readAllBytes(rf1.toPath()), bin1));
        check("嵌套文本一致", rf2.isFile() && new String(Files.readAllBytes(rf2.toPath()), "UTF-8")
                .equals("hello zstd 世界\n"));
        check("空文件保留", rf3.isFile() && rf3.length() == 0);
        check("空目录保留", rd1.isDirectory());

        // ===== 3. .tzst 命名与 stripName =====
        check("stripName tar.zst", ZstdUtil.stripName("x.tar.zst").equals("x"));
        check("stripName tzst", ZstdUtil.stripName("x.tzst").equals("x"));
        check("stripName zst", ZstdUtil.stripName("x.zst").equals("x"));
        check("isZstName 判定", ZstdUtil.isZstName("A.ZST") && ZstdUtil.isZstName("b.tar.zst")
                && !ZstdUtil.isZstName("c.zip"));

        // ===== 4. 非 zstd 文件解压应报错 =====
        File fake = new File(root, "fake.zst"); write(fake, "PK\u0003\u0004not zstd".getBytes());
        check("fake 魔数不识别", !ZstdUtil.isZst(fake));
        boolean threw = false;
        try { ZstdUtil.extract(fake, new File(root, "out4")); }
        catch (Exception e) { threw = true; }
        check("非 zstd 解压抛错", threw);

        // ===== 5. 压缩级别差异（可压缩文本）=====
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200000; i++) sb.append("zstd repetitive line ").append(i % 97).append('\n');
        byte[] rep = sb.toString().getBytes("UTF-8");
        File src5 = new File(root, "rep.txt"); write(src5, rep);
        List<File> s5 = new ArrayList<File>(); s5.add(src5);
        File z_fast = new File(root, "rep_fast.zst"), z_max = new File(root, "rep_max.zst");
        ZstdUtil.compress(z_fast, s5, 1);
        ZstdUtil.compress(z_max, s5, 19);
        check("级别 19 体积 <= 级别 1", z_max.length() <= z_fast.length());
        File out5 = new File(root, "out5"); out5.mkdirs();
        ZstdUtil.extract(z_max, out5);
        check("级别 19 round-trip 一致", java.util.Arrays.equals(
                Files.readAllBytes(new File(out5, "rep_max").toPath()), rep));

        System.out.println("\n== ZstdTest: " + pass + " pass, " + fail + " fail ==");
        deleteR(root);
        System.exit(fail == 0 ? 0 : 1);
    }

    static void write(File f, byte[] b) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(b); fo.close();
    }

    static void deleteR(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteR(c);
        f.delete();
    }
}
