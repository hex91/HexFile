package com.hex91.file.utils;

import com.hex91.file.core.*;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * v1.83.0 网络存储凭据的加密钥匙串。
 *
 * <p>密钥优先级：
 * <ol>
 *   <li>API 23+ 用 AndroidKeyStore 里的 AES 密钥 —— 私钥不出 TEE/StrongBox，
 *       应用被 root 后直接读 SharedPreferences 也拿不到可用的密钥；</li>
 *   <li>API 21/22 没有 Keystore AES，用 PBKDF2(ANDROID_ID + 包名) 派生 —— 强度有限，
 *       但比明文强，且不会让老设备保存密码直接崩；</li>
 *   <li>两级都失败（极少数 ROM）→ {@link #available()} 为 false，
 *       调用方退回明文，并在 UI 上提示。</li>
 * </ol>
 *
 * <p>密钥不可导出，所以只能用 {@link javax.crypto.SecretKey} 对象做 Cipher，
 * 不要试图 {@code getEncoded()}。</p>
 */
public final class CredentialVault {

    private static final String ANDROID_KS = "AndroidKeyStore";
    private static final String ALIAS = "hf_cred_v1";
    private static final String SALT = "hexfile-cred-v1";

    private static SecretKey key;
    private static boolean inited;
    private static boolean ok;

    private CredentialVault() {}

    /** 首次使用前调用（幂等） */
    public static synchronized void init(Context ctx) {
        if (inited) return;
        inited = true;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    key = fromAndroidKeyStore();
                } catch (Throwable t) {
                    key = legacyKey(ctx);      // 少数 ROM 的 Keystore 会抛，降级不崩
                }
            } else {
                key = legacyKey(ctx);
            }
            ok = key != null;
        } catch (Throwable t) {
            key = null;
            ok = false;
        }
    }

    public static boolean available() { return ok; }

    public static boolean isEncrypted(String s) {
        return CredentialCodec.isEncrypted(s);
    }

    /** 加密；不可用时原样返回明文（调用方应提示用户） */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        if (!ok || key == null) return plain;
        try {
            return CredentialCodec.encrypt(plain, key);
        } catch (Throwable t) {
            return plain;
        }
    }

    /** 解密；非密文原样返回（兼容旧版本留下的明文密码） */
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!CredentialCodec.isEncrypted(stored)) return stored;
        if (!ok || key == null) return stored;
        try {
            return CredentialCodec.decrypt(stored, key);
        } catch (Throwable t) {
            // 密钥丢失（卸载重装/换机恢复备份）→ 解不开，返回原文让用户重填，
            // 而不是静默用空密码反复连接失败
            return stored;
        }
    }

    // ------------------------------------------------------------------ 密钥

    private static SecretKey fromAndroidKeyStore() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KS);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KS);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /** API 21/22 与 Keystore 异常时的兜底：PBKDF2 派生 256 位密钥 */
    private static SecretKey legacyKey(Context ctx) {
        try {
            String id = "no-id";
            try {
                id = Settings.Secure.getString(ctx.getContentResolver(),
                        Settings.Secure.ANDROID_ID);
            } catch (Throwable ignored) {
            }
            if (id == null) id = "no-id";
            String seed = id + "|" + ctx.getPackageName() + "|" + SALT;
            PBEKeySpec spec = new PBEKeySpec(seed.toCharArray(), SALT.getBytes("UTF-8"), 20000, 256);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            byte[] raw = f.generateSecret(spec).getEncoded();
            SecretKey k = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            return k;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 供桌面/调试观测（不含密钥内容） */
    static String describe() {
        return "vault ok=" + ok + " key=" + (key == null ? "null" : key.getAlgorithm());
    }
}
