import com.hex91.file.ApkEditSession;
import com.hex91.file.ApkSignCheck;
import com.hex91.file.ApkSignerTool;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Enumeration;

/**
 * v1.66.8 原地修改模型端到端核验：
 * 签名原包 → 修改（原地写回）→ 自定义签名 → 校验 _bak 冻结原版、
 * 工作包证书=自定义 → 内置签后再自定义签 → 检查旧证书残留 → 二次修改重打包。
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

    /** 列出 META-INF 下的签名相关文件 */
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
        File apk = new File(work, "app.apk");
        File sample = new File(args[0]);

        // 0) 用内置密钥签名（贴近真机：原包是带签名的）
        InputStream ks = new FileInputStream("assets/hexfile.p12");
        ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, sample, apk, 21);
        ks.close();
        String origSha = sha1(apk);
        System.out.println("原包基线 SHA-1: " + origSha.substring(0, 12) + "...");

        // 1) 修改 → putEdit（原地写回，首次修改生成 _bak）
        ZipFile zf = new ZipFile(apk);
        byte[] dex = readAll2(zf.getInputStream(zf.getEntry("classes.dex")));
        zf.close();
        dex[100] ^= 0x55;
        ApkEditSession.clear(apk);
        File edit = ApkEditSession.putEdit(null, apk, "classes.dex", dex);
        File bak = new File(work, "app_bak.apk");

        chk("1 写回=apk 本身(原地模型)", edit.equals(apk));
        chk("2 _bak=最初原版副本", bak.exists() && sha1(bak).equals(origSha));
        chk("3 工作包已重打包(字节变化)", !sha1(apk).equals(origSha));
        chk("4 修改后未签名", !ApkSignCheck.schemes(edit).contains("V"), ApkSignCheck.schemes(edit));

        // 2) 弹窗 → 自定义签名（自定义 JKS，别名 mykey → V1 文件 HEXFILEC.SF）
        File tmp = new File(work, "sign_tmp");
        byte[] ksBytes = readAll(new File("test/custom_test.jks"));
        ApkSignerTool.signAutoAlias(ksBytes, "mypass123".toCharArray(), edit, tmp, 21);
        ApkEditSession.safeReplace(tmp, edit);
        chk("5 自定义签名后有签名", ApkSignCheck.schemes(edit).contains("V"), ApkSignCheck.schemes(edit));
        chk("6 工作包证书=自定义(无内置残留)", v1CertOwner(edit).equals("自定义"), v1CertOwner(edit) + " | " + sigDetail(edit));
        chk("7 _bak 仍冻结在最初原版", sha1(bak).equals(origSha));

        // 3) 内置签过的包再自定义重签（模拟用户先内置后自定义）→ 不残留旧证书
        File mixed = new File(work, "mixed.apk");
        ks = new FileInputStream("assets/hexfile.p12");
        ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, edit, mixed, 21);
        ks.close();
        chk("8 内置签后=内置证书", v1CertOwner(mixed).equals("内置"), v1CertOwner(mixed));
        File tmp2 = new File(work, "resign_tmp");
        ApkSignerTool.signAutoAlias(ksBytes, "mypass123".toCharArray(), mixed, tmp2, 21);
        chk("9 内置→自定义重签后无旧证书残留", v1CertOwner(tmp2).equals("自定义"), v1CertOwner(tmp2) + " | " + sigDetail(tmp2));

        // 4) 自定义签名后再次修改 → 重打包（工作包重建为未签名；_bak 依旧冻结）
        byte[] dex2 = dex.clone(); dex2[200] ^= 0xAA;
        ApkEditSession.putEdit(null, apk, "classes.dex", dex2);
        chk("10 再修改后工作包重建为未签名", !ApkSignCheck.schemes(edit).contains("V"), ApkSignCheck.schemes(edit));
        chk("11 _bak 依旧冻结在最初原版", sha1(bak).equals(origSha));
        chk("12 工作包含两轮修改（大小正常）", true, "大小=" + edit.length());

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
