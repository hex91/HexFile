import com.hex91.file.BatchRenameCore;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 批量重命名核心逻辑桌面测试（纯 Java，无需 Android）。
 * 运行：javac -cp src/bin test/BatchRenameTest.java && java -cp src/bin:test bin.HexFile.BatchRenameTest
 */
public class BatchRenameTest {
    static int pass = 0, fail = 0;

    static void eq(String got, String exp, String msg) {
        if (got == null ? exp == null : got.equals(exp)) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL: " + msg + "  got=[" + got + "] exp=[" + exp + "]");
        }
    }

    static void True(boolean v, String msg) {
        if (v) pass++;
        else { fail++; System.out.println("FAIL: " + msg); }
    }

    public static void main(String[] args) {
        testSplit();
        testNumber();
        testReplace();
        testExt();
        testConflict();
        testComputeAll();
        System.out.println("\nBatchRenameTest: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void testSplit() {
        eq(BatchRenameCore.splitName("foo.txt")[0], "foo", "split base1");
        eq(BatchRenameCore.splitName("foo.txt")[1], ".txt", "split ext1");
        eq(BatchRenameCore.splitName("foo")[1], "", "split noext");
        eq(BatchRenameCore.splitName("foo.tar.gz")[0], "foo.tar", "split multi-ext base");
        eq(BatchRenameCore.splitName("foo.tar.gz")[1], ".gz", "split multi-ext ext");
        eq(BatchRenameCore.splitName(".gitignore")[1], "", "split dotfile noext");
        eq(BatchRenameCore.splitName(".config.bak")[0], ".config", "split dotfile base");
        eq(BatchRenameCore.splitName(".config.bak")[1], ".bak", "split dotfile ext");
    }

    static void testNumber() {
        // 默认：前缀 img_ 起始1 步长1 位数3 后缀空 保留扩展名
        eq(BatchRenameCore.modeNumber("a.jpg", "img_", 1, 1, 3, "", true, 0), "img_001.jpg", "num#1");
        eq(BatchRenameCore.modeNumber("a.jpg", "img_", 1, 1, 3, "", true, 1), "img_002.jpg", "num#2");
        eq(BatchRenameCore.modeNumber("a.jpg", "img_", 1, 2, 3, "", true, 2), "img_005.jpg", "num step2");
        eq(BatchRenameCore.modeNumber("a.jpg", "p_", 10, 1, 4, "_x", true, 0), "p_0010_x.jpg", "num pad4+suffix");
        eq(BatchRenameCore.modeNumber("a.jpg", "x", 1, 1, 3, "", false, 0), "x001", "num dropExt");
        eq(BatchRenameCore.modeNumber("a", "v", 0, 1, 2, "", true, 0), "v00", "num start0 pad2 noext");
    }

    static void testReplace() {
        // 字面替换（保留扩展名）
        eq(BatchRenameCore.modeReplace("hello_world.txt", "world", "earth", false, true, true),
                "hello_earth.txt", "rep literal keepExt");
        // 全替换
        eq(BatchRenameCore.modeReplace("a_b_a.txt", "a", "X", false, true, true),
                "X_b_X.txt", "rep all");
        // 不区分大小写
        eq(BatchRenameCore.modeReplace("Hello.TXT", "hello", "hi", false, false, false),
                "hi.TXT", "rep ci");
        // 区分大小写不匹配
        eq(BatchRenameCore.modeReplace("Hello.txt", "hello", "hi", false, true, false),
                "Hello.txt", "rep cs no-match");
        // 正则 + 分组（保留扩展名作用于主名）
        eq(BatchRenameCore.modeReplace("img0102.jpg", "img(\\d+)", "p_$1", true, true, true),
                "p_0102.jpg", "rep regex group");
        // 空查找不改变
        eq(BatchRenameCore.modeReplace("abc.txt", "", "x", false, true, true),
                "abc.txt", "rep empty find");
    }

    static void testExt() {
        eq(BatchRenameCore.modeExt("a.png", "jpg", true), "a.jpg", "ext change");
        eq(BatchRenameCore.modeExt("a.png", ".JPG", true), "a.JPG", "ext change w/dot");
        eq(BatchRenameCore.modeExt("a.png", "", true), "a", "ext strip");
        eq(BatchRenameCore.modeExt("noext", "txt", true), "noext", "ext onlyWithExt skip");
        eq(BatchRenameCore.modeExt("noext", "txt", false), "noext.txt", "ext add when not only");
    }

    static void testConflict() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "br_test_" + System.nanoTime());
        dir.mkdirs();
        try {
            File f1 = new File(dir, "a.txt"); f1.createNewFile();
            File f2 = new File(dir, "b.txt"); f2.createNewFile();
            File f3 = new File(dir, "c.txt"); f3.createNewFile();
            File exist = new File(dir, "keep.txt"); exist.createNewFile();
            List<File> src = Arrays.asList(f1, f2, f3);

            // 编号命名不冲突
            List<String> nn = Arrays.asList("x001.txt", "x002.txt", "x003.txt");
            boolean[] cf = BatchRenameCore.detectConflicts(src, nn);
            True(!cf[0] && !cf[1] && !cf[2], "conflict none");

            // 编号命名产生重复目标
            List<String> nn2 = Arrays.asList("x001.txt", "x001.txt", "x003.txt");
            boolean[] cf2 = BatchRenameCore.detectConflicts(src, nn2);
            True(cf2[0] && cf2[1] && !cf2[2], "conflict dup target");

            // 目标撞上已存在的 keep.txt
            List<String> nn3 = Arrays.asList("keep.txt", "x002.txt", "x003.txt");
            boolean[] cf3 = BatchRenameCore.detectConflicts(src, nn3);
            True(cf3[0], "conflict existing file");

            // 空名冲突
            List<String> nn4 = Arrays.asList("", "x002.txt", "x003.txt");
            boolean[] cf4 = BatchRenameCore.detectConflicts(src, nn4);
            True(cf4[0], "conflict empty");
        } catch (Exception e) {
            fail++; System.out.println("FAIL: exception " + e);
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    static void testComputeAll() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "br_test2_" + System.nanoTime());
        dir.mkdirs();
        try {
            File f1 = new File(dir, "1.png"); f1.createNewFile();
            File f2 = new File(dir, "2.png"); f2.createNewFile();
            List<File> src = Arrays.asList(f1, f2);
            List<String> nn = BatchRenameCore.computeAll(src, 2,
                    null, 0, 0, 0, null, false,
                    null, null, false, false, false,
                    "jpg", true);
            eq(nn.get(0), "1.jpg", "computeAll ext");
            eq(nn.get(1), "2.jpg", "computeAll ext2");
        } catch (Exception e) {
            fail++; System.out.println("FAIL: exception " + e);
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }
}
