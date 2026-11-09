import com.hex91.file.FileAttrs;

import java.io.File;
import java.io.FileOutputStream;

/**
 * v1.80.0 属性编辑回归（桌面 JVM）。
 *
 * <p>桌面没有 root、也没有 android.system.Os 的真实实现，所以这里只钉住那条
 * 「纯 Java 也一定能走通」的路径：<b>改修改时间</b>。要点是：</p>
 * <ul>
 *   <li>写进去的时间要真的落到文件上（不是只改缓存）；</li>
 *   <li>改完要清掉属性缓存，否则列表详情行会一直显示旧时间；</li>
 *   <li>文件不存在时返回 false，而不是抛异常把调用方带崩。</li>
 * </ul>
 */
public class FileAttrsTest {

    static int pass = 0, fail = 0;
    static final File ROOT = new File("build/attrtest");

    static void ok(boolean v, String msg) {
        if (v) {
            pass++;
            System.out.println("PASS  " + msg);
        } else {
            fail++;
            System.out.println("FAIL  " + msg);
        }
    }

    static void del(File f) {
        if (f.isDirectory()) {
            File[] k = f.listFiles();
            if (k != null) for (File x : k) del(x);
        }
        f.delete();
    }

    public static void main(String[] args) throws Exception {
        del(ROOT);
        ROOT.mkdirs();

        // ---------------------------------------------------------- 1. 基本写入
        File f = new File(ROOT, "a.txt");
        FileOutputStream fo = new FileOutputStream(f);
        fo.write("hello".getBytes("UTF-8"));
        fo.close();

        // 固定一个整秒时间（部分文件系统只存秒级 mtime，带毫秒会截断导致假失败）
        long target = 1700000000000L;
        ok(FileAttrs.setMtime(f, target), "setMtime：普通文件返回成功");
        long got = f.lastModified();
        ok(Math.abs(got - target) <= 2000, "setMtime：落盘时间与目标一致（实际 " + got + "）");

        // ---------------------------------------------------------- 2. 属性缓存失效
        FileAttrs.Attr a = FileAttrs.stat(f);
        ok(a != null, "stat：能取到属性快照");
        // 再改一次，缓存必须失效，否则 stat 会返回旧 mtime
        long target2 = 1600000000000L;
        FileAttrs.setMtime(f, target2);
        FileAttrs.Attr b = FileAttrs.stat(f);
        ok(b != null && Math.abs(b.mtime - target2) <= 2000,
                "setMtime：改完再 stat 拿到的是新时间（" + (b == null ? -1 : b.mtime) + "）");

        // ---------------------------------------------------------- 3. 失败路径
        File missing = new File(ROOT, "no-such-file.bin");
        ok(!FileAttrs.setMtime(missing, target), "setMtime：文件不存在返回 false，不抛异常");

        // ---------------------------------------------------------- 4. 目录也能改
        File dir = new File(ROOT, "sub");
        dir.mkdirs();
        ok(FileAttrs.setMtime(dir, target), "setMtime：目录同样可改时间");
        ok(Math.abs(dir.lastModified() - target) <= 2000, "setMtime：目录时间已生效");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        del(ROOT);
        System.exit(fail == 0 ? 0 : 1);
    }
}
