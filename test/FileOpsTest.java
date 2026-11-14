import com.hex91.file.fs.FileOps;

import java.io.File;
import java.io.FileOutputStream;

/**
 * v2.7.0：{@link FileOps#readAllBytes} 的桌面用例。
 *
 * <p>这个方法是从 {@code MainActivity.readFileBytes} 下沉来的（签名簇搬走后有三处要用），
 * 契约很短但两处容易做错：</p>
 * <ul>
 *   <li>读不到文件时<b>回退</b> {@code RootIO}，回退也拿不到就必须抛<b>原始异常</b>——
 *       如果被回退吞成 null，调用方会拿到 null 而不是「读失败」，后面是 NPE；</li>
 *   <li>逐字节搬运不能把 {@code 0xFF} 符号扩展成 {@code -1}（用 {@code read()} 配
 *       {@code write(int)} 就会踩到，必须用 {@code read(buf)} 的返回值）。</li>
 * </ul>
 *
 * <p>为什么单独一个文件而不并进别的用例：这是 fs 层唯一一个「带 root 回退」的读取入口，
 * 后面还会被别的簇复用；名字对得上，比塞进 TreeOpsTest 里好找。</p>
 */
public class FileOpsTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        File base = new File("_fileops_probe");
        base.mkdirs();

        // 1) 正常路径：字节全等，含 0x00 与 0xFF 两个边界
        File f = new File(base, "a.bin");
        byte[] data = { 0, 1, 2, 3, (byte) 0xFF, (byte) 0x80, 0x7F };
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(data);
        fo.close();
        byte[] back = FileOps.readAllBytes(f);
        ok(back.length == data.length, "readAllBytes 长度 = " + back.length);
        boolean same = true;
        for (int i = 0; i < data.length; i++) if (data[i] != back[i]) same = false;
        ok(same, "readAllBytes 逐字节相等（含 0x00 / 0xFF / 0x80 边界）");

        // 2) 空文件：返回 0 长度而不是 null
        File empty = new File(base, "empty.bin");
        empty.createNewFile();
        byte[] eb = FileOps.readAllBytes(empty);
        ok(eb != null && eb.length == 0, "readAllBytes 空文件 = 0 字节而非 null");

        // 3) 不存在的文件：抛异常（不是返回 null）。桌面无 root，回退必然失败，
        //    正好锁住「回退失败要抛原始异常」这一条。
        File missing = new File(base, "missing.bin");
        boolean threw = false;
        try {
            FileOps.readAllBytes(missing);
        } catch (Exception e) {
            threw = true;
        }
        ok(threw, "readAllBytes 不存在的文件抛异常（未退化成 null）");

        // 4) 目录：抛异常，不是返回空数组
        boolean dirThrew = false;
        try {
            FileOps.readAllBytes(base);
        } catch (Exception e) {
            dirThrew = true;
        }
        ok(dirThrew, "readAllBytes 传目录抛异常");

        f.delete();
        empty.delete();
        base.delete();

        System.out.println();
        System.out.println("==== FileOpsTest: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
