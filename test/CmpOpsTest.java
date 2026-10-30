package bin.HexFile;

import com.hex91.file.FileOps;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Random;

/**
 * FileOps.copyOverwrite 桌面测试（v1.37.0 对比模式同步原语）：
 *  1. 单文件复制到不存在父目录（自动 mkdirs）字节级一致
 *  2. 覆盖已有目标文件（截断写，长→短不残留尾巴）
 *  3. 目录递归合并复制：同名文件覆盖、目标额外文件保留
 *  4. 源不存在 → 返回 false 不抛异常
 */
public class CmpOpsTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        File root = new File("test/_cmpops_t");
        deleteR(root);
        root.mkdirs();

        // ---------- 1) 单文件 → 深层不存在父目录 ----------
        byte[] data = new byte[300_000];
        new Random(7).nextBytes(data);
        File src = new File(root, "A/sub/deep/file1.bin");
        src.getParentFile().mkdirs();
        write(src, data);

        File dst = new File(root, "B/other/nested/file1.bin");
        boolean ok = FileOps.copyOverwrite(src, dst);
        check("复制到深层不存在父目录返回 true", ok);
        check("目标父目录自动创建", dst.isFile());
        check("字节级一致", Arrays.equals(data, read(dst)));

        // ---------- 2) 覆盖已有目标（长→短 截断写） ----------
        byte[] longer = new byte[500_000];
        new Random(8).nextBytes(longer);
        write(dst, longer);
        ok = FileOps.copyOverwrite(src, dst);
        check("覆盖写返回 true", ok);
        check("覆盖后长度 = 源长度", dst.length() == data.length);
        check("覆盖后内容一致（无残留尾巴）", Arrays.equals(data, read(dst)));

        // ---------- 3) 目录递归合并复制 ----------
        File dirA = new File(root, "A/pkg");
        new File(dirA, "keep.txt").getParentFile().mkdirs();
        write(new File(dirA, "keep.txt"), "aaa".getBytes());
        write(new File(dirA, "same.bin"), new byte[]{1, 2, 3});
        File subA = new File(dirA, "sub/x.txt");
        subA.getParentFile().mkdirs();
        write(subA, "xxx".getBytes());

        File dirB = new File(root, "B/pkg");
        dirB.mkdirs();
        write(new File(dirB, "extra.txt"), "extra".getBytes());   // B 侧额外文件
        write(new File(dirB, "same.bin"), new byte[]{9, 9, 9});   // B 侧旧内容

        ok = FileOps.copyOverwrite(dirA, dirB);
        check("目录合并复制返回 true", ok);
        check("同名文件被覆盖", read(new File(dirB, "same.bin"))[0] == 1);
        check("目标额外文件保留", new File(dirB, "extra.txt").isFile());
        check("子目录递归复制", new File(dirB, "sub/x.txt").isFile());
        check("A 侧源未被改动", read(new File(dirA, "keep.txt"))[0] == 'a');

        // ---------- 4) 源不存在 → false ----------
        boolean bad = FileOps.copyOverwrite(new File(root, "A/no_such.bin"),
                new File(root, "B/no_such.bin"));
        check("源不存在返回 false（不抛异常）", bad == false);

        deleteR(root);
        System.out.println();
        System.out.println("PASS " + passed + " / FAIL " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }

    static void write(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
    }

    static byte[] read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[(int) f.length()];
        int off = 0, n;
        while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
        in.close();
        return buf;
    }

    static void deleteR(File f) {
        if (f.isDirectory()) { File[] ks = f.listFiles(); if (ks != null) for (File k : ks) deleteR(k); }
        f.delete();
    }
}
