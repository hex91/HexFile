import com.hex91.file.FileItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * v2.0.1（F-16）：{@link FileItem#isDir()} 的目录判定缓存。
 *
 * <p>这条缓存把「每问一次就 stat 一次」变成「一个条目只 stat 一次」。风险不在省下的那点时间，
 * 而在语义被改坏：</p>
 * <ol>
 *   <li>{@code forceDir}（root 的 {@code ls -p} 结论 / 链接指向目录）可能在本对象构造<b>之后</b>
 *       才被 {@code fillLink} 置上 —— 它必须直接短路，不能进缓存，否则「先问过、后被标记」的会答错；</li>
 *   <li>{@code file == null} 的条目（读取中占位）不能 NPE；</li>
 *   <li>压缩包条目看 {@code archDir}、{@code ..} 返回项恒为目录，都不该被缓存干扰；</li>
 *   <li>{@code FileItem} 是 {@code Serializable}，反序列化不走字段初始化，缓存要显式回到「未知」。</li>
 * </ol>
 */
public class FileItemCacheTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        File base = new File(System.getProperty("java.io.tmpdir"), "hf_f16_" + System.nanoTime());
        base.mkdirs();

        // ---------- 1. 基本判定 ----------
        File dir = new File(base, "d1");
        dir.mkdirs();
        File plain = write(new File(base, "f1.txt"), "hello");

        FileItem di = new FileItem(dir);
        ok(di.isDir(), "目录 -> isDir()=true");
        ok(di.isDir() && di.isDir(), "重复问同一对象结果稳定");

        FileItem fi = new FileItem(plain);
        ok(!fi.isDir(), "普通文件 -> isDir()=false");

        // ---------- 2. 缓存生效：首次判定之后不再看磁盘 ----------
        File swap = new File(base, "d2");
        swap.mkdirs();
        FileItem cached = new FileItem(swap);
        ok(cached.isDir(), "d2 先是目录");
        swap.delete();
        write(swap, "now a file");                       // 同路径换成文件
        ok(!new FileItem(swap).isDir(), "新对象按当前磁盘状态：不是目录");
        ok(cached.isDir(), "老对象仍答「是目录」= 没有再次 stat（缓存命中）");

        // ---------- 3. forceDir 短路：不依赖 file，也不受缓存影响 ----------
        FileItem forced = new FileItem((File) null);
        ok(!forced.isDir(), "file=null 且未标记 -> false，不 NPE");
        forced.forceDir = true;
        ok(forced.isDir(), "forceDir=true 直接短路为目录");
        FileItem late = new FileItem(plain);
        ok(!late.isDir(), "先问一次：普通文件 -> false");
        late.forceDir = true;
        ok(late.isDir(), "之后被 fillLink 标记 forceDir 仍能翻盘（forceDir 不进缓存）");

        // ---------- 4. 压缩包条目 / 占位行 / .. 返回项 ----------
        FileItem ad = new FileItem("res/", "res", true, 0);
        ok(ad.isDir(), "压缩包内目录条目 -> archDir");
        FileItem af = new FileItem("res/a.txt", "a.txt", false, 12);
        ok(!af.isDir(), "压缩包内文件条目 -> 不是目录");
        ok(FileItem.archUp().isDir(), "压缩包内 .. 恒为目录");
        ok(!FileItem.loading().isDir(), "读取中占位行 -> false");
        ok(FileItem.up(base).isDir(), ".. 返回项（file 指向上级真实目录）-> true");

        // ---------- 5. 序列化后缓存回到「未知」 ----------
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(new FileItem(dir));
        oos.close();
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()));
        FileItem back = (FileItem) ois.readObject();
        ois.close();
        ok(back.isDir(), "反序列化后 dirCache 重置，重新判定仍为目录");

        // ---------- 收尾 ----------
        deleteTree(base);

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static File write(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes("UTF-8"));
        } finally {
            o.close();
        }
        return f;
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
