package com.hex91.file;

import com.android.apksig.ApkVerifier;

import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.security.auth.x500.X500Principal;

/**
 * APK 签名校验 —— 基于 Google 官方 apksig 库（与 apksigner / MT 同源方案）。
 * schemes(): 返回 "V1"/"V1+V2"/"V1+V2+V3" 等方案串（信息页签名状态栏用）；
 * detailRows(): 签名详情页逐行数据（校验结论/各方案/每张证书的序列号、
 * 颁发者、MD5/SHA-1/SHA-256 等），行值可点击复制。
 *
 * 注意 v1 的两种语义：
 *   - isVerifiedUsingV1Scheme()：apksig 仅在「minSdk<24 或无 v2/v3 签名」时才
 *     真正校验 v1（API 24+ 平台根本不看 JAR 签名，故跳过校验并报 false）。
 *     因此 minSdk>=24 且带 v2/v3 的包（如重打包产物）会显示 v1=false，
 *     但其 META-INF/*.SF + *.RSA 签名文件实际存在且有效。
 *   - hasJarSignatureFiles()：按签名文件存在性判断，用于方案串展示（对齐 MT）。
 */
public class ApkSignCheck {

    /** 签名方案串，如 "V1+V2+V3"；未签名返回 "未签名"，异常返回 "检测失败" */
    public static String schemes(File apk) {
        try {
            ApkVerifier.Result r = new ApkVerifier.Builder(apk).build().verify();
            StringBuilder s = new StringBuilder();
            if (r.isVerifiedUsingV1Scheme() || hasJarSignatureFiles(apk)) s.append("V1+");
            if (r.isVerifiedUsingV2Scheme()) s.append("V2+");
            if (r.isVerifiedUsingV3Scheme()) s.append("V3+");
            if (r.isVerifiedUsingV31Scheme()) s.append("V3.1+");
            if (r.isVerifiedUsingV4Scheme()) s.append("V4+");
            if (s.length() == 0) return r.isVerified() ? "已验证" : "未签名";
            return s.substring(0, s.length() - 1);
        } catch (Exception e) {
            return "检测失败";
        }
    }

    /** 签名详情页行列表 {label, value}；失败时返回含错误行的列表 */
    public static List<String[]> detailRows(File apk) {
        List<String[]> rows = new ArrayList<String[]>();
        try {
            ApkVerifier.Result r = new ApkVerifier.Builder(apk).build().verify();
            rows.add(new String[]{"校验结果", r.isVerified() ? "✅ 通过" : "❌ 未通过"});
            boolean v1Ok = r.isVerifiedUsingV1Scheme();
            boolean v1Files = hasJarSignatureFiles(apk);
            rows.add(new String[]{"v1 (JAR)",
                    v1Ok ? "✅ 已校验" : v1Files ? "✅ 有签名(平台跳过校验)" : "— 无"});
            rows.add(new String[]{"v2 (APK Sig)", mark(r.isVerifiedUsingV2Scheme())});
            rows.add(new String[]{"v3 (Key Rot)", mark(r.isVerifiedUsingV3Scheme())});

            List<X509Certificate> certs = r.getSignerCertificates();
            rows.add(new String[]{"证书数量", String.valueOf(certs == null ? 0 : certs.size())});
            if (certs != null) {
                for (int i = 0; i < certs.size(); i++) {
                    X509Certificate c = certs.get(i);
                    String tag = certs.size() > 1 ? "证书[" + (i + 1) + "] " : "";
                    rows.add(new String[]{tag + "主体", dn(c.getSubjectX500Principal())});
                    rows.add(new String[]{tag + "颁发者", dn(c.getIssuerX500Principal())});
                    rows.add(new String[]{tag + "序列号",
                            c.getSerialNumber().toString(16).toUpperCase()});
                    rows.add(new String[]{tag + "算法", c.getSigAlgName()});
                    rows.add(new String[]{tag + "有效期至",
                            FileOps.formatTime(c.getNotAfter().getTime())});
                    rows.add(new String[]{tag + "MD5", fp(c.getEncoded(), "MD5")});
                    rows.add(new String[]{tag + "SHA-1", fp(c.getEncoded(), "SHA-1")});
                    rows.add(new String[]{tag + "SHA-256", fp(c.getEncoded(), "SHA-256")});
                }
            }
            if (!r.getWarnings().isEmpty())
                rows.add(new String[]{"警告", r.getWarnings().size() + " 条"});
            if (!r.isVerified() && !r.getErrors().isEmpty())
                rows.add(new String[]{"错误", r.getErrors().size() + " 条"});
            return rows;
        } catch (Exception e) {
            rows.add(new String[]{"错误", e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage())});
            return rows;
        }
    }

    private static String mark(boolean b) { return b ? "✅ 已签名" : "— 无"; }

    /**
     * v1 (JAR) 签名文件是否存在：META-INF/&lt;base&gt;.SF 且有配套
     * .RSA/.DSA/.EC 签名块。apksig 对 minSdk>=24 且带 v2/v3 的包会跳过 v1
     * 校验（isVerifiedUsingV1Scheme()=false），但签名文件仍在且有效。
     */
    private static boolean hasJarSignatureFiles(File apk) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(apk);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("META-INF/") && n.endsWith(".SF")) {
                    String base = n.substring(0, n.length() - 3); // "META-INF/XXX"
                    if (zf.getEntry(base + ".RSA") != null
                            || zf.getEntry(base + ".DSA") != null
                            || zf.getEntry(base + ".EC") != null) return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (zf != null) {
                try { zf.close(); } catch (Exception ignore) { }
            }
        }
    }

    /** 可读 DN：RFC1779 格式，邮箱显示为 EMAILADDRESS=xxx 而非 OID 十六进制 */
    private static String dn(X500Principal p) {
        try {
            return p.getName(X500Principal.RFC1779)
                    .replace("OID.1.2.840.113549.1.9.1=", "EMAILADDRESS=");
        } catch (Exception e) {
            return p.getName();
        }
    }

    /** 连续十六进制大写指纹（无空格，复制即用） */
    private static String fp(byte[] data, String alg) {
        try {
            byte[] d = MessageDigest.getInstance(alg).digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02X", b));
            return hex.toString();
        } catch (Exception e) {
            return "(计算失败)";
        }
    }
}
