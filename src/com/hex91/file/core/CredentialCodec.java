package com.hex91.file.core;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * v1.83.0 网络存储密码的加密封装（AES-256/GCM）。
 *
 * <p>存储格式（单行文本，直接放进 SharedPreferences 的 JSON 里）：
 * <pre>enc:v1:&lt;Base64(12 字节 IV || 密文+16 字节 GCM tag)&gt;</pre>
 * 前缀让「旧版本留下的明文密码」可以被识别并在启动时迁移。</p>
 *
 * <p>密钥不进这个类：设备上由 AndroidKeyStore 提供（不可导出），
 * 桌面单测用 {@code SecretKeySpec} 传进来，所以这里纯 Java、可测。</p>
 *
 * <p>GCM 自带完整性校验：密文被改一个字节会抛异常而不是解出垃圾。</p>
 */
public final class CredentialCodec {

    public static final String PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private CredentialCodec() {}

    public static boolean isEncrypted(String s) {
        return s != null && s.startsWith(PREFIX);
    }

    /** 加密；key 必须支持 AES（16/24/32 字节） */
    public static String encrypt(String plain, SecretKey key) throws Exception {
        if (plain == null) plain = "";
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
        byte[] out = new byte[IV_LEN + ct.length];
        System.arraycopy(iv, 0, out, 0, IV_LEN);
        System.arraycopy(ct, 0, out, IV_LEN, ct.length);
        return PREFIX + Base64Codec.encode(out);
    }

    /** 解密；格式非法/密钥不对/密文被改都抛异常，由调用方兜底 */
    public static String decrypt(String stored, SecretKey key) throws Exception {
        if (!isEncrypted(stored)) return stored;
        byte[] raw = Base64Codec.decode(stored.substring(PREFIX.length()));
        if (raw == null || raw.length <= IV_LEN) throw new IllegalArgumentException("密文格式错误");
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(raw, 0, iv, 0, IV_LEN);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] pt = c.doFinal(raw, IV_LEN, raw.length - IV_LEN);
        return new String(pt, "UTF-8");
    }
}
