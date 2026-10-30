import com.hex91.file.FileOps;
import com.hex91.file.RootIO;
import com.hex91.file.RootUtil;

import java.io.File;

/**
 * v1.44.0 RootIO 桌面测试（Windows 无 su → 全部走"无 root"分支）。
 * 验证：非 root 环境下所有方法安全返回 false/null，不抛异常；
 *       RootUtil 新原语（runWithInput/captureOutput/catProcess）同样安全。
 */
public class RootIOTest {
    static int pass = 0, fail = 0;

    static void check(String name, Object actual, Object expect) {
        boolean ok = (actual == null) ? expect == null : actual.equals(expect);
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + "  (actual=" + actual + ", expect=" + expect + ")"); }
    }

    public static void main(String[] args) throws Exception {
        // 桌面无 su：isRootAvailable()==false（若本机异常带 su 也只是走真分支，断言仍按安全返回设计）
        boolean root = RootUtil.isRootAvailable();
        File f = new File("_rootio_probe.tmp");

        if (!root) {
            check("readBytes 无root=null", RootIO.readBytes(f), null);
            check("readText 无root=null", RootIO.readText(f), null);
            check("writeBytes 无root=false", RootIO.writeBytes(f, new byte[0]), false);
            check("writeText 无root=false", RootIO.writeText(f, "x"), false);
            check("delete 无root=false", RootIO.delete(f), false);
            check("rename 无root=false", RootIO.rename(f, "_x"), false);
            check("mkdirs 无root=false", RootIO.mkdirs(f), false);
            check("copyInto 无root=false", RootIO.copyInto(f, new File(".")), false);
            check("moveInto 无root=false", RootIO.moveInto(f, new File(".")), false);
            check("copyOverwrite 无root=false", RootIO.copyOverwrite(f, new File("_y")), false);
            check("catProcess 无root抛异常", safeCat(f), true);

            // RootUtil 新原语非 root 安全性
            check("runWithInput 无root=false", RootUtil.runWithInput("cat > _z", new byte[1]), false);
            check("captureOutput 无root=null", RootUtil.captureOutput("cat _z", 1024), null);
            check("catProcess(cmd) 无root抛异常", safeCatCmd(), true);

            // FileOps 回退路径不改变非 root 语义（普通失败仍返回 false）
            check("FileOps.rename 不存在文件=false", FileOps.rename(f, "_x"), false);
            check("FileOps.deleteRecursive 不存在=false", FileOps.deleteRecursive(f), false);
        } else {
            System.out.println("本机检测到 su，跳过无 root 断言（真机/WSL 环境）");
        }

        System.out.println();
        System.out.println("==== RootIOTest: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }

    static boolean safeCat(File f) {
        try { RootIO.catProcess(f); return false; } catch (Exception e) { return true; }
    }

    static boolean safeCatCmd() {
        try { RootUtil.catProcess("cat _z"); return false; } catch (Exception e) { return true; }
    }
}
