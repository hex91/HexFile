package com.hex91.file.apk;

import com.hex91.file.fs.FileOps;
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
 * {@link #check}：方案串 + 三态结论（信息页签名状态栏用）；
 * {@link #detailRows}：签名详情页逐行数据（校验结论/各方案/每张证书的序列号、
 * 颁发者、MD5/SHA-1/SHA-256 等），行值可点击复制。
 *
 * <p>v2.84.0（F-14/71）：本类不再产文案 —— 结论返回 {@link Scheme#state}，
 * 详情行返回 {@link Row}（标签码 + 值码 + 原文），文字由 UI 层映射。</p>
 *
 * 注意 v1 的两种语义：
 *   - isVerifiedUsingV1Scheme()：apksig 仅在「minSdk<24 或无 v2/v3 签名」时才
 *     真正校验 v1（API 24+ 平台根本不看 JAR 签名，故跳过校验并报 false）。
 *     因此 minSdk>=24 且带 v2/v3 的包（如重打包产物）会显示 v1=false，
 *     但其 META-INF/*.SF + *.RSA 签名文件实际存在且有效。
 *   - hasJarSignatureFiles()：按签名文件存在性判断，用于方案串展示（对齐 MT）。
 */
public class ApkSignCheck {

    /** 三态结论 */
    public static final int ST_UNSIGNED = 0;
    public static final int ST_VERIFIED = 1;
    public static final int ST_FAILED = 2;

    /** 方案检测结果：state 决定文案，list 是 "V1+V2+V3"（无具体方案时为空） */
    public static final class Scheme {
        public int state = ST_UNSIGNED;
        public String list = "";
    }

    /* 详情行的标签码 */
    public static final int ROW_RESULT = 1;
    public static final int ROW_V1 = 2;
    public static final int ROW_V2 = 3;
    public static final int ROW_V3 = 4;
    public static final int ROW_CERT_COUNT = 5;
    public static final int ROW_SUBJECT = 6;
    public static final int ROW_ISSUER = 7;
    public static final int ROW_SERIAL = 8;
    public static final int ROW_SIG_ALG = 9;
    public static final int ROW_NOT_AFTER = 10;
    public static final int ROW_WARNINGS = 11;
    public static final int ROW_ERRORS = 12;
    public static final int ROW_MD5 = 13;
    public static final int ROW_SHA1 = 14;
    public static final int ROW_SHA256 = 15;

    /* 详情行的值码；0 表示直接用 value 原文 */
    public static final int V_PASS = 1;
    public static final int V_FAIL = 2;
    public static final int V_V1_OK = 3;
    public static final int V_V1_FILES = 4;
    public static final int V_NONE = 5;
    public static final int V_SIGNED = 6;
    public static final int V_COUNT = 7;
    public static final int V_CALC_FAIL = 8;

    /** 详情行：{@link #label} 标签码，{@link #tagNo} > 0 时前缀「证书[n] 」 */
    public static final class Row {
        public final int label;
        public final int valueCode;
        public final String value;
        public final int count;
        public final int tagNo;

        Row(int label, int valueCode, String value, int count, int tagNo) {
            this.label = label;
            this.valueCode = valueCode;
            this.value = value;
            this.count = count;
            this.tagNo = tagNo;
        }
    }

    /** 签名方案 + 三态结论；读不出来时 state = {@link #ST_FAILED} */
    public static Scheme check(File apk) {
        Scheme out = new Scheme();
        try {
            ApkVerifier.Result r = new ApkVerifier.Builder(apk).build().verify();
            StringBuilder s = new StringBuilder();
            if (r.isVerifiedUsingV1Scheme() || hasJarSignatureFiles(apk)) s.append("V1+");
            if (r.isVerifiedUsingV2Scheme()) s.append("V2+");
            if (r.isVerifiedUsingV3Scheme()) s.append("V3+");
            if (r.isVerifiedUsingV31Scheme()) s.append("V3.1+");
            if (r.isVerifiedUsingV4Scheme()) s.append("V4+");
            if (s.length() > 0) {
                out.list = s.substring(0, s.length() - 1);
                out.state = ST_VERIFIED;
            } else {
                out.state = r.isVerified() ? ST_VERIFIED : ST_UNSIGNED;
            }
        } catch (Exception e) {
            out.state = ST_FAILED;
        }
        return out;
    }

    /** 签名详情页行列表；失败时返回只含一行错误说明的列表 */
    public static List<Row> detailRows(File apk) {
        List<Row> rows = new ArrayList<Row>();
        try {
            ApkVerifier.Result r = new ApkVerifier.Builder(apk).build().verify();
            rows.add(row(ROW_RESULT, r.isVerified() ? V_PASS : V_FAIL));
            boolean v1Ok = r.isVerifiedUsingV1Scheme();
            boolean v1Files = hasJarSignatureFiles(apk);
            rows.add(row(ROW_V1, v1Ok ? V_V1_OK : v1Files ? V_V1_FILES : V_NONE));
            rows.add(row(ROW_V2, r.isVerifiedUsingV2Scheme() ? V_SIGNED : V_NONE));
            rows.add(row(ROW_V3, r.isVerifiedUsingV3Scheme() ? V_SIGNED : V_NONE));

            List<X509Certificate> certs = r.getSignerCertificates();
            int n = certs == null ? 0 : certs.size();
            rows.add(new Row(ROW_CERT_COUNT, 0, String.valueOf(n), 0, 0));
            if (certs != null) {
                for (int i = 0; i < n; i++) {
                    X509Certificate c = certs.get(i);
                    int tag = n > 1 ? i + 1 : 0;
                    rows.add(new Row(ROW_SUBJECT, 0, dn(c.getSubjectX500Principal()), 0, tag));
                    rows.add(new Row(ROW_ISSUER, 0, dn(c.getIssuerX500Principal()), 0, tag));
                    rows.add(new Row(ROW_SERIAL, 0,
                            c.getSerialNumber().toString(16).toUpperCase(), 0, tag));
                    rows.add(new Row(ROW_SIG_ALG, 0, c.getSigAlgName(), 0, tag));
                    rows.add(new Row(ROW_NOT_AFTER, 0,
                            FileOps.formatTime(c.getNotAfter().getTime()), 0, tag));
                    rows.add(fpRow(ROW_MD5, c.getEncoded(), "MD5", tag));
                    rows.add(fpRow(ROW_SHA1, c.getEncoded(), "SHA-1", tag));
                    rows.add(fpRow(ROW_SHA256, c.getEncoded(), "SHA-256", tag));
                }
            }
            if (!r.getWarnings().isEmpty()) {
                rows.add(new Row(ROW_WARNINGS, V_COUNT, null, r.getWarnings().size(), 0));
            }
            if (!r.isVerified() && !r.getErrors().isEmpty()) {
                rows.add(new Row(ROW_ERRORS, V_COUNT, null, r.getErrors().size(), 0));
            }
            return rows;
        } catch (Exception e) {
            rows.add(new Row(ROW_ERRORS, 0, e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), 0, 0));
            return rows;
        }
    }

    private static Row row(int label, int valueCode) {
        return new Row(label, valueCode, null, 0, 0);
    }

    private static Row fpRow(int label, byte[] data, String alg, int tagNo) {
        String s = fp(data, alg);
        return s == null ? new Row(label, V_CALC_FAIL, null, 0, tagNo)
                : new Row(label, 0, s, 0, tagNo);
    }

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

    /** 连续十六进制大写指纹（无空格，复制即用）；算不出来返回 null，由 UI 显示占位 */
    private static String fp(byte[] data, String alg) {
        try {
            byte[] d = MessageDigest.getInstance(alg).digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02X", b));
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
