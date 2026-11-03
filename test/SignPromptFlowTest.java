import com.hex91.file.ApkEditSession;
import com.hex91.file.ApkSignCheck;
import com.hex91.file.ApkSignerTool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Enumeration;

/**
 * 复现用户真机流程：已签名的原包 → 修改 smali（putEdit 模拟 dex 修改）→
 * 退出时是否应弹签名框（needsSignPrompt）→ _edit.apk 的签名状态检测。
 */
public class SignPromptFlowTest {

    static int pass = 0, fail = 0;

    static void chk(String name, boolean ok) {
        chk(name, ok, null);
    }

    static void chk(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("[PASS] " + name + (detail != null ? "  " + detail : "")); }
        else { fail++; System.out.println("[FAIL] " + name + (detail != null ? "  " + detail : "")); }
    }

    static byte[] readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    static boolean hasSigFiles(File apk) throws Exception {
        ZipFile zf = new ZipFile(apk);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA")
                        || n.endsWith(".DSA") || n.endsWith(".EC")
                        || n.equals("META-INF/MANIFEST.MF"))) return true;
            }
            return false;
        } finally { zf.close(); }
    }

    public static void main(String[] args) throws Exception {
        File work = new File("test/signflow_tmp");
        rmrf(work); work.mkdirs();
        File orig = new File(work, "app.apk");

        // 0) 准备：把样本先签成"已签名的原包"（贴近真机场景）
        File sample = new File(args[0]);
        java.io.InputStream ks = new FileInputStream("assets/hexfile.p12");
        ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, sample, orig, 21);
        ks.close();
        chk("0 原包已签名(预置)", ApkSignCheck.schemes(orig).contains("V"), ApkSignCheck.schemes(orig));

        // 1) 模拟修改 smali：读出 classes.dex 改一个字节再登记（内容变化即可）
        ZipFile zf = new ZipFile(orig);
        byte[] dex = readAll2(zf.getInputStream(zf.getEntry("classes.dex")));
        zf.close();
        dex[100] ^= 0x55;
        ApkEditSession.clear(orig);
        File edit = ApkEditSession.putEdit(null, orig, "classes.dex", dex);

        // 2) 退出时应弹签名框？
        chk("1 needsSignPrompt=true(退出应弹窗)", ApkEditSession.needsSignPrompt(orig));
        chk("2 dirty=true", ApkEditSession.get(orig).dirty);

        // 3) _edit.apk 应为未签名状态（无 V1 签名文件残留）
        chk("3 _edit.apk 无 META-INF 签名文件", !hasSigFiles(edit));
        String sc = ApkSignCheck.schemes(edit);
        chk("4 _edit.apk 签名检测=未签名", sc.equals("未签名") || sc.equals("检测失败") == false && !sc.contains("V1"), sc);
        chk("5 _edit.apk 不等于原包字节", !eq(orig, edit));

        // 4) 模拟用户选"暂不签名"→ prompted=true → 不再弹
        ApkEditSession.markPrompted(orig);
        chk("6 暂不签名后不再弹", !ApkEditSession.needsSignPrompt(orig));

        // 5) 再次修改（模拟又改了一个 dex）→ regen → 应重新弹
        byte[] dex2 = dex.clone(); dex2[200] ^= 0xAA;
        ApkEditSession.putEdit(null, orig, "classes.dex", dex2);
        chk("7 二次修改后 needsSignPrompt=true", ApkEditSession.needsSignPrompt(orig));

        // 6) 模拟签名 _edit.apk → markSigned → 不再弹
        File tmp = new File(work, "sign_tmp.apk");
        ks = new FileInputStream("assets/hexfile.p12");
        ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, edit, tmp, 21);
        ks.close();
        ApkEditSession.safeReplace(tmp, edit);
        ApkEditSession.markSigned(orig);
        chk("8 签名后 needsSignPrompt=false", !ApkEditSession.needsSignPrompt(orig));
        chk("9 签名后 _edit.apk 检测有签名", ApkSignCheck.schemes(edit).contains("V"), ApkSignCheck.schemes(edit));

        System.out.println("\nPASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static byte[] readAll2(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    static boolean eq(File a, File b) throws Exception {
        if (a.length() != b.length()) return false;
        FileInputStream fa = new FileInputStream(a), fb = new FileInputStream(b);
        byte[] ba = new byte[65536], bb = new byte[65536];
        int na, nb;
        while (((na = fa.read(ba)) > 0) && ((nb = fb.read(bb)) > 0)) {
            if (na != nb || !java.util.Arrays.equals(ba, 0, na, bb, 0, nb)) { fa.close(); fb.close(); return false; }
        }
        fa.close(); fb.close();
        return true;
    }

    static void rmrf(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) rmrf(c);
        f.delete();
    }
}
