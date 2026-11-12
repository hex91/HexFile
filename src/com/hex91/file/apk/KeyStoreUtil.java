package com.hex91.file.apk;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v1.67.0 签名管理核心（纯 Java，可桌面单测）：
 *  - loadAny：PKCS12 / JKS / BKS 统一加载（BKS 依赖平台 BC provider，桌面无则自然跳过）
 *  - inspect：列出全部条目 + 证书详情（主题/签发者/有效期/算法/SHA-1/SHA-256 指纹）
 *  - JKS 常见坑：私钥密码可与 keystore 密码不同 → keyPass 参数单列，签名单独传
 */
public final class KeyStoreUtil {

    private KeyStoreUtil() {}

    /** 单个条目（别名）的解析结果 */
    public static class AliasInfo {
        public String alias;
        public boolean keyEntry;       // 含私钥
        public boolean keyReadable;    // 用给定密码能取出私钥
        public boolean hasChain;       // 有证书链
        public String subject;         // 证书主题 DN
        public String issuer;          // 签发者 DN
        public String notBefore;       // 有效期起 yyyy-MM-dd
        public String notAfter;        // 有效期止 yyyy-MM-dd
        public String sigAlg;          // 签名算法
        public String keyAlg;          // 私钥算法（RSA/EC/…）
        public String sha1;            // 证书 SHA-1 指纹（冒号分十六进制）
        public String sha256;          // 证书 SHA-256 指纹
        public int chainLen;

        /** 单行摘要（列表展示用） */
        public String summary() {
            return alias + "（" + (keyEntry ? keyAlg : "证书") + "）";
        }

        /** 多行详情（详情弹窗用） */
        public String detail() {
            StringBuilder sb = new StringBuilder();
            sb.append("别名: ").append(alias).append('\n');
            sb.append("类型: ").append(keyEntry ? "私钥条目（" + keyAlg + "）" : "受信证书").append('\n');
            if (!keyReadable && keyEntry)
                sb.append("⚠ 私钥密码与 keystore 密码不同，需填写私钥密码\n");
            sb.append("主题: ").append(subject).append('\n');
            sb.append("签发者: ").append(issuer).append('\n');
            sb.append("有效期: ").append(notBefore).append(" ~ ").append(notAfter).append('\n');
            sb.append("签名算法: ").append(sigAlg).append('\n');
            if (sha1 != null) sb.append("SHA-1: ").append(sha1).append('\n');
            if (sha256 != null) sb.append("SHA-256: ").append(sha256).append('\n');
            return sb.toString();
        }
    }

    /** 统一加载：PKCS12 → JKS → BKS，全失败抛最后一个异常 */
    public static KeyStore loadAny(byte[] bytes, char[] storePass) throws Exception {
        Exception last = null;
        for (String type : new String[]{"PKCS12", "JKS", "BKS"}) {
            try {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(new ByteArrayInputStream(bytes), storePass);
                return ks;
            } catch (Exception e) { last = e; }
        }
        throw last != null ? last
                : new IllegalStateException("无法加载 keystore（支持 .p12/.pfx/.jks/.keystore/.bks）");
    }

    /**
     * 解析 keystore 全部条目。
     * @param storePass keystore 密码
     * @param keyPass   私钥密码；null = 用 keystore 密码试
     */
    public static List<AliasInfo> inspect(byte[] bytes, char[] storePass, char[] keyPass)
            throws Exception {
        KeyStore ks = loadAny(bytes, storePass);
        List<AliasInfo> out = new ArrayList<AliasInfo>();
        for (java.util.Enumeration<String> en = ks.aliases(); en.hasMoreElements();) {
            String a = en.nextElement();
            AliasInfo info = new AliasInfo();
            info.alias = a;
            info.keyEntry = ks.isKeyEntry(a);
            Certificate[] chain = ks.getCertificateChain(a);
            if (chain == null) {
                Certificate c = ks.getCertificate(a);
                if (c != null) chain = new Certificate[]{c};
            }
            info.hasChain = chain != null && chain.length > 0;
            info.chainLen = info.hasChain ? chain.length : 0;
            if (info.hasChain && chain[0] instanceof X509Certificate) {
                X509Certificate c = (X509Certificate) chain[0];
                info.subject = c.getSubjectX500Principal().getName();
                info.issuer = c.getIssuerX500Principal().getName();
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                info.notBefore = fmt.format(c.getNotBefore());
                info.notAfter = fmt.format(c.getNotAfter());
                info.sigAlg = c.getSigAlgName();
                try { info.keyAlg = c.getPublicKey().getAlgorithm(); } catch (Throwable ignored) {}
                info.sha1 = fingerprint(c, "SHA-1");
                info.sha256 = fingerprint(c, "SHA-256");
            }
            if (info.keyEntry) {
                char[] kp = keyPass != null ? keyPass : storePass;
                try {
                    java.security.Key k = ks.getKey(a, kp);
                    info.keyReadable = k instanceof PrivateKey;
                    if (info.keyReadable && info.keyAlg == null)
                        info.keyAlg = k.getAlgorithm();
                } catch (Throwable ignored) {
                    info.keyReadable = false;
                }
            } else {
                info.keyReadable = false;
            }
            out.add(info);
        }
        return out;
    }

    /** 从解析结果里挑第一个「可取出私钥且有证书链」的别名；没有返回 null */
    public static String firstSignableAlias(List<AliasInfo> list) {
        for (AliasInfo i : list)
            if (i.keyEntry && i.keyReadable && i.hasChain) return i.alias;
        return null;
    }

    /** 证书指纹（冒号分大写十六进制） */
    public static String fingerprint(X509Certificate c, String alg) throws Exception {
        byte[] d = MessageDigest.getInstance(alg).digest(c.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.US, "%02X", d[i] & 0xFF));
        }
        return sb.toString();
    }

    /** keystore 是否可正常用于签名（有可读私钥 + 证书链），返回错误原因或 null */
    public static String validateForSign(List<AliasInfo> list) {
        boolean anyKey = false;
        for (AliasInfo i : list) {
            if (i.keyEntry) {
                anyKey = true;
                if (i.keyReadable && i.hasChain) return null;
            }
        }
        if (!anyKey) return "keystore 中没有私钥条目";
        return "私钥无法读取：密码错误，或私钥密码与 keystore 密码不同（请填写私钥密码）";
    }
}
