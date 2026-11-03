import com.hex91.file.ApkSignerTool;
import com.hex91.file.KeyStoreUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v1.67.0 签名管理核心测试（自包含：keytool 现场生成 JKS/PKCS12 样本，无外部参数）：
 *  1. PKCS12 解析（别名/主题/指纹/可读私钥）
 *  2. JKS 解析（密码同 keystore）
 *  3. JKS 私钥密码与 keystore 密码不同：inspect 标记不可读 → 显式 keyPass 可签
 *  4. JKS 直接签名（signAutoAlias 全新入口）→ ApkSignerTool 校验 v2 生效
 *  5. 错误密码 → 报错；无私钥 keystore → 报错
 */
public class KeyStoreUtilTest {
    static int pass = 0, fail = 0;

    static void chk(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("[PASS] " + name + (detail != null ? "  " + detail : "")); }
        else { fail++; System.out.println("[FAIL] " + name + (detail != null ? "  " + detail : "")); }
    }

    static void chk(String name, boolean ok) { chk(name, ok, null); }

    public static void main(String[] args) throws Exception {
        String keytool = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "keytool"
                + (System.getProperty("os.name").contains("Windows") ? ".exe" : "");
        File work = new File("test/ksbox_tmp");
        rmrf(work); work.mkdirs();

        // ---- keytool 生成三种 keystore ----
        File p12 = new File(work, "t.p12");
        int rc = exec(keytool, "-genkeypair", "-alias", "ali1", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=TestP12,O=HexFile,C=CN", "-validity", "3650",
                "-storetype", "PKCS12", "-storepass", "pass12", "-keypass", "pass12",
                "-keystore", p12.getPath());
        chk("keytool 生成 PKCS12", rc == 0 && p12.isFile());

        File jks = new File(work, "t.jks");
        rc = exec(keytool, "-genkeypair", "-alias", "jk1", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=TestJks,O=HexFile,C=CN", "-validity", "3650",
                "-storetype", "JKS", "-storepass", "jkspass", "-keypass", "jkspass",
                "-keystore", jks.getPath());
        chk("keytool 生成 JKS", rc == 0 && jks.isFile());

        File jks2 = new File(work, "t2.jks");   // 私钥密码 ≠ keystore 密码
        rc = exec(keytool, "-genkeypair", "-alias", "jk2", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=TestJks2,O=HexFile,C=CN", "-validity", "3650",
                "-storetype", "JKS", "-storepass", "sp1234", "-keypass", "kp5678",
                "-keystore", jks2.getPath());
        chk("keytool 生成 JKS（双密码）", rc == 0 && jks2.isFile());

        byte[] p12b = readAll(p12), jksb = readAll(jks), jks2b = readAll(jks2);

        // ---- 1. PKCS12 解析 ----
        List<KeyStoreUtil.AliasInfo> l1 = KeyStoreUtil.inspect(p12b, "pass12".toCharArray(), null);
        chk("PKCS12 条目数=1", l1.size() == 1);
        KeyStoreUtil.AliasInfo a1 = l1.get(0);
        chk("PKCS12 别名/私钥/可读", "ali1".equals(a1.alias) && a1.keyEntry && a1.keyReadable);
        chk("PKCS12 主题", a1.subject != null && a1.subject.contains("TestP12"));
        chk("PKCS12 指纹", a1.sha1 != null && a1.sha1.length() == 59
                && a1.sha256 != null && a1.sha256.length() == 95);
        chk("PKCS12 有效期", a1.notBefore != null && a1.notAfter != null);
        chk("PKCS12 可签名", KeyStoreUtil.validateForSign(l1) == null);

        // ---- 2. JKS 解析 ----
        List<KeyStoreUtil.AliasInfo> l2 = KeyStoreUtil.inspect(jksb, "jkspass".toCharArray(), null);
        chk("JKS 条目/私钥可读", l2.size() == 1 && l2.get(0).keyEntry && l2.get(0).keyReadable);

        // ---- 3. JKS 双密码 ----
        List<KeyStoreUtil.AliasInfo> l3 = KeyStoreUtil.inspect(jks2b, "sp1234".toCharArray(), null);
        chk("JKS 双密码：默认私钥不可读", l3.size() == 1 && l3.get(0).keyEntry && !l3.get(0).keyReadable);
        chk("JKS 双密码：validate 给出提示", KeyStoreUtil.validateForSign(l3) != null);
        List<KeyStoreUtil.AliasInfo> l3b = KeyStoreUtil.inspect(jks2b, "sp1234".toCharArray(),
                "kp5678".toCharArray());
        chk("JKS 双密码：显式 keyPass 可读", l3b.get(0).keyReadable);
        List<KeyStoreUtil.AliasInfo> l3c = KeyStoreUtil.inspect(jks2b, "sp1234".toCharArray(),
                "wrong".toCharArray());
        chk("JKS 双密码：错 keyPass 不可读", !l3c.get(0).keyReadable);

        // ---- 4. JKS 直接签名 + 验证 ----
        File srcApk = new File("test/sign_unsigned.apk");   // 真实未签名样本
        chk("签名样本存在", srcApk.isFile());
        File out = new File(work, "signed_jks.apk");
        ApkSignerTool.signAutoAlias(jksb, "jkspass".toCharArray(), null, null,
                srcApk, out, 21, true, true, true);
        chk("JKS 签名产物生成", out.isFile() && out.length() > 0);
        String sc = com.hex91.file.ApkSignCheck.schemes(out);
        chk("JKS 签名 v2 验证", sc.contains("V2"), sc);

        // 显式别名 + 独立私钥密码
        File out2 = new File(work, "signed_jks2.apk");
        ApkSignerTool.signAutoAlias(jks2b, "sp1234".toCharArray(), "kp5678".toCharArray(), "jk2",
                srcApk, out2, 21, false, true, false);
        chk("JKS 双密码签名（显式 keyPass+别名）", out2.isFile() && out2.length() > 0
                && com.hex91.file.ApkSignCheck.schemes(out2).contains("V2"));

        // 双密码不传 keyPass → 应报清晰错误
        try {
            ApkSignerTool.signAutoAlias(jks2b, "sp1234".toCharArray(), null, null,
                    srcApk, new File(work, "bad.apk"), 21, true, true, true);
            chk("JKS 双密码缺 keyPass 报错", false, "未抛异常");
        } catch (Exception e) {
            chk("JKS 双密码缺 keyPass 报错", true, e.getMessage());
        }

        // ---- 5. 错误密码 ----
        try {
            KeyStoreUtil.inspect(p12b, "wrong".toCharArray(), null);
            chk("错误密码报错", false, "未抛异常");
        } catch (Exception e) {
            chk("错误密码报错", true, null);
        }

        // 无私钥：只导出证书的 keystore 不能签
        File cert = new File(work, "cert.p12");
        rc = exec(keytool, "-exportcert", "-alias", "ali1", "-storetype", "PKCS12",
                "-storepass", "pass12", "-keystore", p12.getPath(),
                "-file", new File(work, "c.cer").getPath());
        if (rc == 0) {
            rc = exec(keytool, "-importcert", "-alias", "c1", "-storetype", "PKCS12",
                    "-storepass", "certpass", "-file", new File(work, "c.cer").getPath(),
                    "-keystore", cert.getPath(), "-noprompt");
            if (rc == 0) {
                List<KeyStoreUtil.AliasInfo> lc = KeyStoreUtil.inspect(readAll(cert),
                        "certpass".toCharArray(), null);
                chk("纯证书 keystore 拒签",
                        KeyStoreUtil.validateForSign(lc) != null && KeyStoreUtil.firstSignableAlias(lc) == null);
            }
        }

        System.out.println("\nPASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    // ---- 辅助 ----

    static int exec(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[4096];
        while (in.read(buf) > 0) { /* 丢弃输出防管道堵死 */ }
        return p.waitFor();
    }

    static byte[] readAll(File f) throws Exception {
        FileInputStream fi = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            fi.close();
        }
    }

    static void rmrf(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) rmrf(c);
        f.delete();
    }
}
