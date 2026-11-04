import java.io.File;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.List;

import com.hex91.file.ApkSignerTool;
import com.hex91.file.KeyStoreUtil;
import com.hex91.file.KeystoreCreate;

/**
 * KeystoreCreate 桌面回归（v1.68.0）：
 * 生成自签 keystore → 解析证书 → 验签 → 用 apksig 真签一个 APK → 能回读证书。
 */
public class KeystoreCreateTest {

    static int pass = 0, fail = 0;

    static void check(boolean cond, String msg) {
        if (cond) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        String passwd = "hexfile123";
        long t0 = System.currentTimeMillis();
        KeystoreCreate.Result r = KeystoreCreate.create(
                "HexFile Test", "HexFile", "CN", "hexfiletest", passwd, 2048, 30 * 365);
        long cost = System.currentTimeMillis() - t0;
        System.out.println("生成耗时 " + cost + "ms，store=" + r.store.length + " 字节，"
                + r.storeType + "，别名 " + r.alias);
        System.out.println(r.summary());

        check(r.store != null && r.store.length > 1000, "keystore 字节非空");
        check("PKCS12".equals(r.storeType) || "JKS".equals(r.storeType), "存储格式可用");
        check(r.alias != null && r.alias.length() > 0, "有可用别名");
        check(r.subject.contains("HexFile Test"), "主题含 CN");
        check(r.sha256 != null && r.sha256.split(":").length == 32, "SHA-256 指纹 32 段");
        check(cost < 15000, "生成耗时合理（" + cost + "ms）");

        // ---- 用签名链路的解析逻辑回读 ----
        List<KeyStoreUtil.AliasInfo> list = KeyStoreUtil.inspect(
                r.store, passwd.toCharArray(), null);
        check(list.size() == 1, "回读 1 个条目（实际 " + list.size() + "）");
        KeyStoreUtil.AliasInfo info = list.get(0);
        check(info.keyEntry && info.keyReadable, "私钥可取出");
        check(info.hasChain && info.chainLen >= 1, "证书链完整");
        check("RSA".equalsIgnoreCase(info.keyAlg), "私钥算法 RSA（" + info.keyAlg + "）");
        check(!info.issuer.isEmpty(), "签发者非空（自签名）");

        // ---- 密码错误必须失败 ----
        boolean wrongRejected = false;
        try {
            KeyStoreUtil.loadAny(r.store, "badpass".toCharArray());
        } catch (Throwable t) {
            wrongRejected = true;
        }
        check(wrongRejected, "错误密码被拒绝");

        // ---- 私钥能签、证书能验 ----
        KeyStore ks = KeyStoreUtil.loadAny(r.store, passwd.toCharArray());
        PrivateKey pk = (PrivateKey) ks.getKey(r.alias, passwd.toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(r.alias);
        byte[] msg = "hexfile-sign-test".getBytes("UTF-8");
        Signature sg = Signature.getInstance("SHA256withRSA");
        sg.initSign(pk);
        sg.update(msg);
        byte[] sig = sg.sign();
        Signature vf = Signature.getInstance("SHA256withRSA");
        vf.initVerify(cert.getPublicKey());
        vf.update(msg);
        check(vf.verify(sig), "私钥签名 / 公钥验证通过");
        check(cert.getNotAfter().after(new java.util.Date()), "证书未过期");

        // ---- apksig 真签一个 APK ----
        File unsigned = new File("test/sign_unsigned.apk");
        if (unsigned.isFile()) {
            File out = new File("test/bin_kscreate_signed.apk");
            try {
                ApkSignerTool.signAutoAlias(r.store, passwd.toCharArray(), null, null,
                        unsigned, out, 21);
                check(out.isFile() && out.length() > 0, "apksig 用新 keystore 签名成功（"
                        + out.length() + " 字节）");
                // 从签名产物里回读证书（签名方案串 + 证书主题）
                String schemes = com.hex91.file.ApkSignCheck.schemes(out);
                List<String[]> rows = com.hex91.file.ApkSignCheck.detailRows(out);
                StringBuilder sb = new StringBuilder(String.valueOf(schemes));
                for (String[] row : rows) sb.append(" | ").append(row[0]).append('=').append(row[1]);
                check(schemes != null && schemes.length() > 0, "签名产物可被校验: " + sb);
                check(sb.toString().contains("HexFile Test"), "签名产物证书主题回到 CN");
                out.delete();
            } catch (Throwable t) {
                check(false, "apksig 签名失败: " + t);
            }
        } else {
            System.out.println("SKIP 无 sign_unsigned.apk");
        }

        // ---- 参数兜底 ----
        try {
            KeystoreCreate.create("X", null, null, null, "", 2048, 30);
            check(false, "空密码应被拒绝");
        } catch (IllegalArgumentException e) {
            check(true, "空密码被拒绝");
        }

        System.out.println();
        System.out.println("RESULT  pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
