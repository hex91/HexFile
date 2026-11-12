package com.hex91.file.apk;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设备端 APK 重签名（v1.9）：复用已内嵌的 apksig 编程式 API。
 *  keystore（PKCS12）放 assets/hexfile.p12，运行时加载私钥+证书链，
 *  签 v1+v2+v3，与桌面 apksigner 行为一致。
 */
public final class ApkSignerTool {

    public static final String KEY_ALIAS = "hexfile";
    public static final char[] KEY_PASSWORD = "android".toCharArray();

    /**
     * 用内置调试密钥签名 apk（输入未签名或已剥离旧签名），签 v1+v2+v3。
     * <p><b>所有权约定（v1.67.1）</b>：{@code keyStoreIn} 由<b>调用方</b>负责关闭，
     * 本方法只读取、不关闭——调用方必须使用 try-finally 或调用方自持流的生命周期。
     * 需要「一次性字节数组」时优先用 {@link #sign(byte[], char[], File, File, int)} 重载。
     */
    public static void sign(InputStream keyStoreIn, char[] password, File in, File out, int minSdk)
            throws Exception {
        sign(keyStoreIn, password, in, out, minSdk, true, true, true);
    }

    /**
     * v1.66.9：内置密钥签名，签名方案可选（v1/v2/v3 任意组合）。
     * 至少启用一种方案，否则抛 IllegalArgumentException。
     */
    public static void sign(InputStream keyStoreIn, char[] password, File in, File out,
            int minSdk, boolean v1, boolean v2, boolean v3) throws Exception {
        if (!v1 && !v2 && !v3)
            throw new IllegalArgumentException("至少需要启用一种签名方案 (v1/v2/v3)");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(keyStoreIn, password);
        java.security.Key key = ks.getKey(KEY_ALIAS, password);
        if (!(key instanceof PrivateKey))
            throw new IllegalStateException("keystore 中无别名 " + KEY_ALIAS + " 的私钥");
        PrivateKey privateKey = (PrivateKey) key;
        java.security.cert.Certificate[] rawChain = ks.getCertificateChain(KEY_ALIAS);
        if (rawChain == null || rawChain.length == 0)
            throw new IllegalStateException("keystore 中无证书链");
        List<X509Certificate> chain = new ArrayList<X509Certificate>();
        for (java.security.cert.Certificate c : rawChain)
            chain.add((X509Certificate) c);

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "HexFile", privateKey, chain).build();
        ApkSigner signer = new ApkSigner.Builder(
                Collections.singletonList(signerConfig))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(v1)
                .setV2SigningEnabled(v2)
                .setV3SigningEnabled(v3)
                .setMinSdkVersion(minSdk)
                .setAlignmentPreserved(true)          // 输入已由 ApkRepack 对齐
                .setLibraryPageAlignmentBytes(4096)   // .so 按 4096 对齐（extractNativeLibs=false 需要）
                .setCreatedBy("HexFile ARSC Editor")
                .build();
        signer.sign();
    }

    /** 便捷重载：从字节数组加载 keystore */
    public static void sign(byte[] keyStoreBytes, char[] password, File in, File out, int minSdk)
            throws Exception {
        sign(new java.io.ByteArrayInputStream(keyStoreBytes), password, in, out, minSdk, true, true, true);
    }

    /** v1.66.9：字节数组 keystore + 可选签名方案 */
    public static void sign(byte[] keyStoreBytes, char[] password, File in, File out,
            int minSdk, boolean v1, boolean v2, boolean v3) throws Exception {
        sign(new java.io.ByteArrayInputStream(keyStoreBytes), password, in, out, minSdk, v1, v2, v3);
    }

    /**
     * 自定义密钥签名（v1.57.0）：自动选取 keystore 中第一个含私钥+证书链的别名。
     *  兼容 PKCS12(.p12/.pfx) 与 JKS(.jks/.keystore)，签 v1+v2+v3。
     */
    public static void signAutoAlias(byte[] keyStoreBytes, char[] password,
            File in, File out, int minSdk) throws Exception {
        signAutoAlias(keyStoreBytes, password, in, out, minSdk, true, true, true);
    }

    /** v1.66.9：自定义密钥 + 可选签名方案 */
    public static void signAutoAlias(byte[] keyStoreBytes, char[] password,
            File in, File out, int minSdk, boolean v1, boolean v2, boolean v3) throws Exception {
        signAutoAlias(keyStoreBytes, password, null, null, in, out, minSdk, v1, v2, v3);
    }

    /** v1.67.0：显式别名 + 独立私钥密码，默认签 v1+v2+v3 */
    public static void signAutoAlias(byte[] keyStoreBytes, char[] storePass, char[] keyPass,
            String alias, File in, File out, int minSdk) throws Exception {
        signAutoAlias(keyStoreBytes, storePass, keyPass, alias, in, out, minSdk, true, true, true);
    }

    /**
     * v1.67.0：完整签名入口——显式别名 + 独立私钥密码（JKS 私钥密码可与 keystore 密码不同）。
     * @param alias    null = 自动选第一个可用私钥条目
     * @param keyPass  null = 用 keystore 密码当私钥密码
     */
    public static void signAutoAlias(byte[] keyStoreBytes, char[] storePass, char[] keyPass,
            String alias, File in, File out, int minSdk,
            boolean v1, boolean v2, boolean v3) throws Exception {
        if (!v1 && !v2 && !v3)
            throw new IllegalArgumentException("至少需要启用一种签名方案 (v1/v2/v3)");
        KeyStore ks = KeyStoreUtil.loadAny(keyStoreBytes, storePass);
        char[] kp = keyPass != null ? keyPass : storePass;
        if (alias == null) {
            for (java.util.Enumeration<String> en = ks.aliases(); en.hasMoreElements();) {
                String a = en.nextElement();
                if (!ks.isKeyEntry(a)) continue;
                try {
                    if (ks.getKey(a, kp) instanceof PrivateKey
                            && ks.getCertificateChain(a) != null
                            && ks.getCertificateChain(a).length > 0) {
                        alias = a;
                        break;
                    }
                } catch (Exception ignored) {
                    // 该别名私钥密码不符（如 JKS 双密码），试下一个
                }
            }
        }
        if (alias == null) throw new IllegalStateException(
                "keystore 中无可用私钥条目（密码错误，或私钥密码与 keystore 密码不同）");
        java.security.Key k = ks.getKey(alias, kp);
        if (!(k instanceof PrivateKey))
            throw new IllegalStateException("别名 " + alias + " 私钥无法读取（私钥密码错误？）");
        PrivateKey privateKey = (PrivateKey) k;
        java.security.cert.Certificate[] rawChain = ks.getCertificateChain(alias);
        if (rawChain == null || rawChain.length == 0)
            throw new IllegalStateException("别名 " + alias + " 无证书链");
        List<X509Certificate> chain = new ArrayList<X509Certificate>();
        for (java.security.cert.Certificate c : rawChain)
            chain.add((X509Certificate) c);
        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "HexFileCustom", privateKey, chain).build();
        new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(v1)
                .setV2SigningEnabled(v2)
                .setV3SigningEnabled(v3)
                .setMinSdkVersion(minSdk)
                .setAlignmentPreserved(true)
                .setLibraryPageAlignmentBytes(4096)
                .setCreatedBy("HexFile Custom Sign")
                .build()
                .sign();
    }
}
