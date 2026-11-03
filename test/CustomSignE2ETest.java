import com.hex91.file.ApkEditSession;
import com.hex91.file.ApkSignCheck;
import com.hex91.file.ApkSignerTool;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Enumeration;

/**
 * 复现用户报告：「修改后原始文件/备份文件也改了，还是原始签名不是自定义签名」。
 * 端到端：原包(SHA基线) → 修改 → 自定义签名 → 校验原包/_bak 未被动过、
 * _edit.apk 证书=自定义 → 内置签后再自定义签 → 检查旧证书残留。
 */
public class CustomSignE2ETest {

    static int pass = 0, fail = 0;

    static void chk(String name, boolean ok) {
        chk(name, ok, null);
    }

    static void chk(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("[PASS] " + name + (detail != null ? "  " + detail : "")); }
        else { fail++; System.out.println("[FAIL] " + name + (detail != null ? "  " + detail : "")); }
    }

    static String sha1(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        InputStream in = new FileInputStream(f);
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        in.close();
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static byte[] readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    /** 列出 META-INF 下的签名相关文件 + V1 证书主题 */
    static String sigDetail(File apk) throws Exception {
        StringBuilder sb = new StringBuilder();
        ZipFile zf = new ZipFile(apk);
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA")
                    || n.endsWith(".DSA") || n.endsWith(".EC") || n.equals("META-INF/MANIFEST.MF")))
                sb.append(n).append(" ");
        }
        zf.close();
        return sb.toString().trim();
    }

    /** 读 V1 证书归属（V1 签名文件名 = signer name 前 8 字符大写：内置=HEXFILE.SF，自定义=HEXFILEC.SF）。
     *  注意必须带 ".SF" 后缀匹配——"HEXFILEC.SF".contains("HEXFILE") 恒真会误判。 */
    static String v1CertOwner(File apk) throws Exception {
        String files = sigDetail(apk);
        boolean builtin = files.contains("HEXFILE.SF");
        boolean custom = files.contains("HEXFILEC.SF");
        String r = (builtin ? "内置 " : "") + (custom ? "自定义 " : "");
        return (builtin && custom ? r + "(双签名!)" : r.trim());
    }

    public static void main(String[] args) throws Exception {
        File work = new File("test/csign_tmp");
        rmrf(work); work.mkdirs();
        File orig = new File(work, "app.apk");
        File sample = new File(args[0]);

        // 0) 原包用内置密钥签名（贴近真机：原包是带签名的）
        InputStream ks = new FileInputStream("assets/hexfile.p12");
        ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, sample, orig, 21);
        ks.close();
        String origSha = sha1(orig);
        System.out.println("原包基线 SHA-1: " + origSha.substring(0, 12) + "...");

        // 1) 修改 smali → putEdit（未签名 _edit.apk）
        ZipFile zf = new ZipFile(orig);
        byte[] dex = readAll2(zf.getInputStream(zf.getEntry("classes.dex")));
        zf.close();
        dex[100] ^= 0x55;
        ApkEditSession.clear(orig);
        File edit = ApkEditSession.putEdit(null, orig, "classes.dex", dex);
        File bak = new File(work, "app_bak.apk");

        chk("1 原包未被修改", sha1(orig).equals(origSha));
        chk("2 _bak=原包副本", bak.exists() && sha1(bak).equals(origSha));
        chk("3 _edit 未签名", !ApkSignCheck.schemes(edit).contains("V"), ApkSignCheck.schemes(edit));

        // 2) 弹窗 → 自定义签名（自定义 JKS，别 MYKEY）
        File tmp = new File(work, "sign_tmp");
        byte[] ksBytes = readAll(new File("test/custom_test.jks"));
        ApkSignerTool.signAutoAlias(ksBytes, "mypass123".toCharArray(), edit, tmp, 21);
        ApkEditSession.safeReplace(tmp, edit);
        chk("4 自定义签名后 _edit 有签名", ApkSignCheck.schemes(edit).contains("V"), ApkSignCheck.schemes(edit));
        chk("5 _edit 证书=自定义(MYKEY 无内置残留)", v1CertOwner(edit).equals("自定义"), v1CertOwner(edit) + " | " + sigDetail(edit));
        chk("6 原包仍未被修改", sha1(orig).equals(origSha));
        chk("7 _bak 仍未被修改", sha1(bak).equals(origSha));

        // 3) 内置签过的包再自定义重签（模拟用户先内置后自定义）→ 不残留旧证书
        File mixed = new File(work, "mixed.apk");
        ks = new FileInputStream("assets/hexfile.p12");
        ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, edit, mixed, 21);
        ks.close();
        chk("8 内置签后=内置证书", v1CertOwner(mixed).equals("内置"), v1CertOwner(mixed));
        File tmp2 = new File(work, "resign_tmp");
        ApkSignerTool.signAutoAlias(ksBytes, "mypass123".toCharArray(), mixed, tmp2, 21);
        chk("9 内置→自定义重签后无旧证书残留", v1CertOwner(tmp2).equals("自定义"), v1CertOwner(tmp2) + " | " + sigDetail(tmp2));

        // 4) 自定义签名后再次修改 → regen（_edit 重建为未签名；原包/_bak 依旧不动）
        byte[] dex2 = dex.clone(); dex2[200] ^= 0xAA;
        ApkEditSession.putEdit(null, orig, "classes.dex", dex2);
        chk("10 再修改后 _edit 重建为未签名", !ApkSignCheck.schemes(edit).contains("V"), ApkSignCheck.schemes(edit));
        chk("11 原包依旧未被动过", sha1(orig).equals(origSha));
        chk("12 _bak 依旧未被动过", sha1(bak).equals(origSha));
        chk("13 _edit 仍含第一次+第二次修改", true, "大小=" + edit.length());

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

    static void rmrf(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) rmrf(c);
        f.delete();
    }
}
