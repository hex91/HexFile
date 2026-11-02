import com.hex91.file.ApkRepack;
import java.io.*;
public class BakTest {
    public static void main(String[] a) throws Exception {
        File d = new File("test/bak_tmp"); d.mkdirs();
        File apk = new File(d, "app.apk");
        write(apk, "ORIGINAL_V1");
        File bak = ApkRepack.backupOriginal(apk);
        chk("备份生成", bak.exists() && bak.getName().equals("app_bak.apk"), bak.getName());
        chk("备份内容=原版", read(bak).equals("ORIGINAL_V1"), read(bak));
        write(apk, "MODIFIED_V2");
        File bak2 = ApkRepack.backupOriginal(apk);
        chk("二次修改不覆盖备份", read(bak2).equals("ORIGINAL_V1"), read(bak2));
        chk("原包已被写为修改版", read(apk).equals("MODIFIED_V2"), read(apk));
        System.out.println("BakTest done");
    }
    static void chk(String n, boolean ok, String info) {
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + n + (ok ? "" : " << " + info));
        if (!ok) System.exit(1);
    }
    static void write(File f, String s) throws Exception { FileOutputStream o = new FileOutputStream(f); o.write(s.getBytes("UTF-8")); o.close(); }
    static String read(File f) throws Exception { FileInputStream i = new FileInputStream(f); byte[] b = new byte[64]; int n = i.read(b); i.close(); return new String(b, 0, n, "UTF-8"); }
}
