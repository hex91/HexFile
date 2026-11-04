package com.hex91.file;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v1.68.0 内置「创建签名」：生成 RSA 密钥对 + 自签 X.509 证书 → 打包成 keystore 字节。
 *
 * <p>证书用自带的最小 DER 编码器生成（不依赖 bcpkix / sun.security.x509，Android 与桌面 JVM 通用），
 * 生成后立刻用 {@link CertificateFactory} 反解 + 验签自检，再通过 {@link KeyStoreUtil#inspect} 确认
 * 私钥可取出 —— 保证导出的 keystore 能直接被 apksig 使用。
 */
public final class KeystoreCreate {

    private KeystoreCreate() {
    }

    /** 创建结果（含可直接落盘的 keystore 字节与展示信息） */
    public static class Result {
        public byte[] store;
        public String storeType;     // PKCS12 / JKS
        public String alias;
        public String subject;
        public String issuer;
        public String notBefore;
        public String notAfter;
        public String sigAlg;
        public String sha1;
        public String sha256;
        public String storePass;
        public String keyPass;
        public int keyBits;

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("存储格式: ").append(storeType).append('\n');
            sb.append("别名: ").append(alias).append('\n');
            sb.append("密钥: RSA ").append(keyBits).append(" 位\n");
            sb.append("主题: ").append(subject).append('\n');
            sb.append("签发者: ").append(issuer).append("（自签名）\n");
            sb.append("有效期: ").append(notBefore).append(" ~ ").append(notAfter).append('\n');
            sb.append("签名算法: ").append(sigAlg).append('\n');
            if (sha1 != null) sb.append("SHA-1: ").append(sha1).append('\n');
            if (sha256 != null) sb.append("SHA-256: ").append(sha256);
            return sb.toString();
        }
    }

    /**
     * 生成一个自签名 keystore。
     *
     * @param cn        证书 CN（如 "HexFile Debug"）
     * @param org       组织 O（可为空）
     * @param country   国家 C（两位，可为空）
     * @param alias     条目别名（为 null 用 "hexfile"）
     * @param pass      统一密码（keystore 与私钥同密码）
     * @param keyBits   RSA 位数（1024~4096，建议 2048）
     * @param validDays 有效期天数（1 ~ 36500）
     */
    public static Result create(String cn, String org, String country, String alias,
            String pass, int keyBits, int validDays) throws Exception {
        if (cn == null || cn.trim().length() == 0) cn = "HexFile";
        if (alias == null || alias.trim().length() == 0) alias = "hexfile";
        if (pass == null || pass.length() < 1) throw new IllegalArgumentException("密码不能为空");
        if (keyBits < 1024) keyBits = 2048;
        if (keyBits > 4096) keyBits = 4096;
        if (validDays < 1) validDays = 1;
        if (validDays > 36500) validDays = 36500;

        KeyPair kp = generateRsa(keyBits);
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 24L * 3600 * 1000);
        Date notAfter = new Date(now + (long) validDays * 24 * 3600 * 1000);

        String dnSubject = dn(cn.trim(), org, country);
        byte[] certDer = selfSignedCert(kp, dnSubject, notBefore, notAfter);

        // 自检 1：证书能被标准 X.509 解析器读出来（Android/桌面同一套 DER 规则）
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(certDer));
        cert.verify(kp.getPublic());                       // 自检 2：自签名可验证
        cert.checkValidity(new Date());                    // 自检 3：当前在有效期内

        // 打包 keystore：优先进 Android/apksigner 都吃的 PKCS12，失败退 JKS
        char[] pw = pass.toCharArray();
        char[] kp2 = pass.toCharArray();
        KeyStore ks = null;
        String type = null;
        Exception last = null;
        for (String t : new String[]{"PKCS12", "JKS"}) {
            try {
                KeyStore k = KeyStore.getInstance(t);
                k.load(null, pw);
                k.setKeyEntry(alias, kp.getPrivate(), kp2, new Certificate[]{cert});
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                k.store(bo, pw);
                ks = k;
                type = t;
                break;
            } catch (Exception e) {
                last = e;
            }
        }
        if (ks == null) throw last != null ? last
                : new IllegalStateException("无法创建 keystore");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ks.store(out, pw);
        byte[] bytes = out.toByteArray();

        // 自检 4：用签名链路的加载/解析逻辑回读一次（否则写盘后才发现打不开）
        List<KeyStoreUtil.AliasInfo> list =
                KeyStoreUtil.inspect(bytes, pw, null);
        String signable = KeyStoreUtil.firstSignableAlias(list);
        if (signable == null)
            throw new IllegalStateException("生成的 keystore 无法取出私钥（"
                    + list.size() + " 个条目）");

        Result r = new Result();
        r.store = bytes;
        r.storeType = type;
        r.alias = signable;
        r.subject = cert.getSubjectX500Principal().getName();
        r.issuer = cert.getIssuerX500Principal().getName();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        r.notBefore = fmt.format(cert.getNotBefore());
        r.notAfter = fmt.format(cert.getNotAfter());
        r.sigAlg = cert.getSigAlgName();
        r.sha1 = KeyStoreUtil.fingerprint(cert, "SHA-1");
        r.sha256 = KeyStoreUtil.fingerprint(cert, "SHA-256");
        r.storePass = pass;
        r.keyPass = pass;
        r.keyBits = keyBits;
        return r;
    }

    private static KeyPair generateRsa(int bits) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(bits, new SecureRandom());
        return g.generateKeyPair();
    }

    /** CN=..., O=..., C=...（空项跳过） */
    private static String dn(String cn, String org, String country) {
        StringBuilder sb = new StringBuilder("CN=").append(cn);
        if (org != null && org.trim().length() > 0) sb.append(", O=").append(org.trim());
        if (country != null && country.trim().length() > 0) {
            String c = country.trim().toUpperCase(Locale.US);
            if (c.length() > 2) c = c.substring(0, 2);
            sb.append(", C=").append(c);
        }
        return sb.toString();
    }

    // ============================================================ 证书 DER

    /**
     * 组装自签名证书：
     * Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
     */
    private static byte[] selfSignedCert(KeyPair kp, String dn, Date from, Date to)
            throws Exception {
        byte[] sigAlg = seq(oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), nul()); // sha256WithRSAEncryption

        Name name = name(dn);
        byte[] serial = integer(new BigInteger(63, new SecureRandom()).setBit(62)
                .add(BigInteger.ONE));   // 正数、非零
        byte[] validity = seq(time(from), time(to));
        byte[] spki = kp.getPublic().getEncoded();   // X.509 SubjectPublicKeyInfo

        // Extensions: basicConstraints(cA=false) + keyUsage(digitalSignature)
        byte[] basicConstraints = ext(oid(new int[]{2, 5, 29, 19}), true,
                octet(seq()));                             // SEQUENCE {} → cA = FALSE
        byte[] keyUsage = ext(oid(new int[]{2, 5, 29, 15}), true,
                octet(bitString(new byte[]{(byte) 0x80}))); // bit0 = digitalSignature
        byte[] extensions = seq(basicConstraints, keyUsage);

        byte[] tbs = seq(
                explicit(0, integer(BigInteger.valueOf(2))),   // version v3
                serial,
                sigAlg,
                name.der,                                     // issuer = subject（自签）
                validity,
                name.der,                                     // subject
                spki,
                explicit(3, extensions));

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(kp.getPrivate());
        signer.update(tbs);
        byte[] sig = signer.sign();

        return seq(tbs, sigAlg, bitString(sig));
    }

    /** 拆解 "CN=a, O=b, C=cn" → RDNSequence */
    private static Name name(String dn) {
        // RDN 顺序按 DER 编码惯例：C, O, CN（与 keytool 展示顺序相反，但都是合法 DN）
        java.util.List<byte[]> rdns = new java.util.ArrayList<byte[]>();
        String cn = null, org = null, country = null;
        for (String part : dn.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            String k = kv[0].trim().toUpperCase(Locale.US);
            String v = kv[1].trim();
            if (k.equals("CN")) cn = v;
            else if (k.equals("O")) org = v;
            else if (k.equals("C")) country = v;
        }
        if (country != null) rdns.add(rdn(new int[]{2, 5, 4, 6}, printable(country)));
        if (org != null) rdns.add(rdn(new int[]{2, 5, 4, 10}, utf8(org)));
        if (cn != null) rdns.add(rdn(new int[]{2, 5, 4, 3}, utf8(cn)));
        if (rdns.isEmpty()) rdns.add(rdn(new int[]{2, 5, 4, 3}, utf8("HexFile")));
        Name n = new Name();
        n.der = seq(rdns.toArray(new byte[0][]));
        return n;
    }

    private static final class Name {
        byte[] der;
    }

    private static byte[] rdn(int[] oidArc, byte[] value) {
        return set(seq(oid(oidArc), value));
    }

    private static byte[] ext(byte[] oidDer, boolean critical, byte[] value) {
        return critical
                ? seq(oidDer, tlv(0x01, new byte[]{0x01}), value)   // BOOLEAN TRUE = 0xFF
                : seq(oidDer, value);
    }

    // ============================================================ 最小 DER 编码

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] octet(byte[] body) {
        return tlv(0x04, body);
    }

    private static byte[] nul() {
        return new byte[]{0x05, 0x00};
    }

    /** BIT STRING（unused bits = 0） */
    private static byte[] bitString(byte[] body) {
        byte[] b = new byte[body.length + 1];
        b[0] = 0;
        System.arraycopy(body, 0, b, 1, body.length);
        return tlv(0x03, b);
    }

    /** [n] EXPLICIT */
    private static byte[] explicit(int tagNo, byte[] inner) {
        return tlv(0xA0 | tagNo, inner);
    }

    private static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    private static byte[] utf8(String s) {
        return tlv(0x0C, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] printable(String s) {
        return tlv(0x13, s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /** 时间：2049 前用 UTCTime(yyMMddHHmmssZ)，之后用 GeneralizedTime */
    private static byte[] time(Date d) {
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.setTime(d);
        int year = c.get(java.util.Calendar.YEAR);
        String s;
        if (year < 2050) {
            s = String.format(Locale.US, "%02d%02d%02d%02d%02d%02dZ",
                    year % 100, c.get(java.util.Calendar.MONTH) + 1,
                    c.get(java.util.Calendar.DAY_OF_MONTH),
                    c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
                    c.get(java.util.Calendar.SECOND));
            return tlv(0x17, s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        s = String.format(Locale.US, "%04d%02d%02d%02d%02d%02dZ", year,
                c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND));
        return tlv(0x18, s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int[] arcs) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int v = arcs[i];
            if (v < 0x80) {
                bo.write(v);
            } else {
                byte[] tmp = new byte[5];
                int n = 0;
                tmp[n++] = (byte) (v & 0x7F);
                v >>>= 7;
                while (v > 0) {
                    tmp[n++] = (byte) ((v & 0x7F) | 0x80);
                    v >>>= 7;
                }
                for (int k = n - 1; k >= 0; k--) bo.write(tmp[k]);
            }
        }
        return tlv(0x06, bo.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] body) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(body.length + 4);
        bo.write(tag);
        int len = body.length;
        if (len < 0x80) {
            bo.write(len);
        } else if (len < 0x100) {
            bo.write(0x81);
            bo.write(len);
        } else if (len < 0x10000) {
            bo.write(0x82);
            bo.write((len >>> 8) & 0xFF);
            bo.write(len & 0xFF);
        } else {
            bo.write(0x83);
            bo.write((len >>> 16) & 0xFF);
            bo.write((len >>> 8) & 0xFF);
            bo.write(len & 0xFF);
        }
        bo.write(body, 0, body.length);
        return bo.toByteArray();
    }

    private static byte[] concat(byte[][] parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
