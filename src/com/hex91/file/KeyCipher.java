package com.hex91.file;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * v1.67.1 签名密码落盘加密：
 *  - Android 6.0（API 23）及以上：AES-256/GCM 密钥由 Android Keystore 生成并常驻 TEE/StrongBox，
 *    应用只能「使用」不能导出；密码文件即便被拷走也无法在别的设备/应用解出明文。
 *  - Android 5.x（API 21-22）：系统无 Keystore AES-GCM，自动降级明文存储（导入界面已提示）。
 *  - 兼容旧数据：没有 aes1: 前缀的值按明文读取（升级后自动在下次保存时加密）。
 */
public final class KeyCipher {

    private KeyCipher() {}

    private static final String ALIAS = "hexfile_sigkey_v1";
    private static final String PREFIX = "aes1:";

    /** 是否具备真正加密能力（用于界面提示） */
    public static boolean encrypted() {
        return Build.VERSION.SDK_INT >= 23;
    }

    /** 加密（空/失败时原样返回，绝不阻断导入） */
    public static String seal(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        if (Build.VERSION.SDK_INT < 23) return plain;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            byte[] all = new byte[1 + iv.length + ct.length];
            all[0] = (byte) iv.length;
            System.arraycopy(iv, 0, all, 1, iv.length);
            System.arraycopy(ct, 0, all, 1 + iv.length, ct.length);
            return PREFIX + Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Throwable t) {
            return plain;
        }
    }

    /** 解密；无前缀视为明文（旧数据），解密失败返回空串 */
    public static String open(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        if (Build.VERSION.SDK_INT < 23) return "";
        try {
            byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            int ivLen = all[0] & 0xFF;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 1, ivLen));
            return new String(c.doFinal(all, 1 + ivLen, all.length - 1 - ivLen), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 取（或首次创建）Keystore 内的 AES 密钥 */
    private static synchronized SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
