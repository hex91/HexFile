package com.hex91.file;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
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

    /** 用内置调试密钥签名 apk（输入未签名或已剥离旧签名） */
    public static void sign(InputStream keyStoreIn, char[] password, File in, File out, int minSdk)
            throws Exception {
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
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
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
        sign(new java.io.ByteArrayInputStream(keyStoreBytes), password, in, out, minSdk);
    }

    /**
     * 自定义密钥签名（v1.57.0）：自动选取 keystore 中第一个含私钥+证书链的别名。
     *  兼容 PKCS12(.p12/.pfx) 与 JKS(.jks/.keystore)。
     */
    public static void signAutoAlias(byte[] keyStoreBytes, char[] password,
            File in, File out, int minSdk) throws Exception {
        KeyStore ks = loadAny(keyStoreBytes, password);
        String alias = null;
        for (java.util.Enumeration<String> en = ks.aliases(); en.hasMoreElements();) {
            String a = en.nextElement();
            if (ks.isKeyEntry(a) && ks.getKey(a, password) instanceof PrivateKey
                    && ks.getCertificateChain(a) != null
                    && ks.getCertificateChain(a).length > 0) {
                alias = a;
                break;
            }
        }
        if (alias == null) throw new IllegalStateException("keystore 中无可用私钥条目");
        PrivateKey privateKey = (PrivateKey) ks.getKey(alias, password);
        List<X509Certificate> chain = new ArrayList<X509Certificate>();
        for (java.security.cert.Certificate c : ks.getCertificateChain(alias))
            chain.add((X509Certificate) c);
        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "HexFileCustom", privateKey, chain).build();
        new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(minSdk)
                .setAlignmentPreserved(true)
                .setLibraryPageAlignmentBytes(4096)
                .setCreatedBy("HexFile Custom Sign")
                .build()
                .sign();
    }

    private static KeyStore loadAny(byte[] bytes, char[] password) throws Exception {
        Exception last = null;
        for (String type : new String[]{"PKCS12", "JKS"}) {
            try {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(new java.io.ByteArrayInputStream(bytes), password);
                return ks;
            } catch (Exception e) { last = e; }
        }
        throw last != null ? last : new IllegalStateException("无法加载 keystore");
    }
}
