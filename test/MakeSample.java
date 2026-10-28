package bin.HexFile;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 生成 Zstd 真机测试大样本（桌面运行，输出到 _zsamples/）：
 *  1. sample_big.zst   —— 单文件 frame：约 300MB 混合数据（25% 随机不可压缩 + 可压缩文本/零块）
 *  2. sample_dir.tar.zst —— 目录 TAR+Zstd：约 400 文件（含 1 个 60MB 随机大文件、中文/嵌套/空目录）
 * 用法：java bin.HexFile.MakeSample [_zsamples]
 */
public class MakeSample {

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "_zsamples");
        out.mkdirs();
        Random rnd = new Random(42);

        // ===== 1. 单文件 .zst：300MB 混合 =====
        File big = new File(out, "sample_big.zst");
        if (!big.exists()) {
            File src = new File(out, "_src_big.bin");
            long total = 300L * 1024 * 1024;
            byte[] buf = new byte[1024 * 1024];
            byte[] text = "MTClone zstd 压缩测试数据行 0123456789 abcdefghijklmnopqrstuvwxyz\n"
                    .getBytes("UTF-8");
            FileOutputStream fo = new FileOutputStream(src);
            long left = total;
            long i = 0;
            while (left > 0) {
                int n = (int) Math.min(buf.length, left);
                int kind = (int) (i++ % 4);
                if (kind == 0) {          // 25% 随机（不可压缩）
                    byte[] tmp = new byte[n];
                    rnd.nextBytes(tmp);
                    System.arraycopy(tmp, 0, buf, 0, n);
                } else if (kind == 1) {   // 重复文本
                    for (int p = 0; p < n; p++) buf[p] = text[p % text.length];
                } else if (kind == 2) {   // 零块
                    java.util.Arrays.fill(buf, 0, n, (byte) 0);
                } else {                  // 递增模式
                    for (int p = 0; p < n; p++) buf[p] = (byte) (p % 251);
                }
                fo.write(buf, 0, n);
                left -= n;
            }
            fo.close();
            List<File> s = new ArrayList<File>(); s.add(src);
            System.out.println("compressing sample_big.zst (level 3) ...");
            long t0 = System.currentTimeMillis();
            ZstdUtil.compress(big, s, 3);
            System.out.println("  -> " + big.getName() + " " + big.length() / 1048576
                    + "MB in " + (System.currentTimeMillis() - t0) / 1000 + "s");
            src.delete();
        }

        // ===== 2. 目录 .tar.zst：400+ 文件 =====
        File dir = new File(out, "sample_dir");
        File z2 = new File(out, "sample_dir.tar.zst");
        if (!z2.exists()) {
            for (int d = 1; d <= 8; d++) {
                File sub = new File(dir, "模块_" + d + "/资源");
                sub.mkdirs();
                for (int f = 1; f <= 50; f++) {
                    File fp = new File(sub, "文件_" + f + ".txt");
                    if (fp.exists()) continue;
                    byte[] b = new byte[2048 + rnd.nextInt(4096)];
                    rnd.nextBytes(b);
                    for (int p = 0; p < b.length; p++) if (p % 3 != 0) b[p] = (byte) ('a' + p % 26);
                    FileOutputStream fo = new FileOutputStream(fp);
                    fo.write(b); fo.close();
                }
            }
            File bigDir = new File(dir, "随机大文件");
            bigDir.mkdirs();
            File bigF = new File(bigDir, "noise_60mb.bin");
            if (!bigF.exists()) {
                byte[] buf = new byte[1024 * 1024];
                FileOutputStream fo = new FileOutputStream(bigF);
                long left = 60L * 1024 * 1024;
                while (left > 0) {
                    int n = (int) Math.min(buf.length, left);
                    rnd.nextBytes(buf);
                    fo.write(buf, 0, n);
                    left -= n;
                }
                fo.close();
            }
            new File(dir, "空 目录").mkdirs();
            List<File> s = new ArrayList<File>(); s.add(dir);
            System.out.println("compressing sample_dir.tar.zst (level 3) ...");
            long t0 = System.currentTimeMillis();
            ZstdUtil.compress(z2, s, 3);
            System.out.println("  -> " + z2.getName() + " " + z2.length() / 1048576
                    + "MB in " + (System.currentTimeMillis() - t0) / 1000 + "s");
        }

        System.out.println("samples ready in: " + out.getAbsolutePath());
        for (File f : out.listFiles()) {
            if (!f.getName().startsWith("_src"))
                System.out.println("  " + f.getName() + "  " + f.length() / 1048576 + "MB");
        }
    }
}
