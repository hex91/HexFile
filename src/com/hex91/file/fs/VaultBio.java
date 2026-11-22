package com.hex91.file.fs;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import com.hex91.file.core.VaultCrypto;

import java.io.IOException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 指纹快捷解锁的密钥封装（v2.113.0，F-25）。
 *
 * <p><b>它解决的不是「安全性」</b>：隐私空间的安全边界仍然是「口令 + 21 万次 PBKDF2 +
 * 两层密钥」，这一点不因为加了指纹而改变。指纹在这里只是一个<b>取用已解锁密钥的快捷方式</b>——
 * 它把主密钥再包一层，包在一个「必须通过设备认证才能使用」的 KeyStore 密钥里。</p>
 *
 * <p><b>为什么不能只让指纹通过就进</b>：主密钥是口令派生出来的，指纹拿不到口令，也就派不出
 * 主密钥。所以必须在用户输过一次口令（此时手里正好有主密钥）的时候，用 KeyStore 密钥把它
 * 封一份放在 {@code .vault/bio}；之后指纹认证通过 → KeyStore 密钥可用 → 解封主密钥。
 * 这也是「改口令不影响指纹」的原因：改口令只重写头部，主密钥本身没变。</p>
 *
 * <p><b>失败时一律退回口令</b>，不降级、不猜测：设备重新录指纹、锁屏方式被改、应用数据被清，
 * 系统都会直接销毁这个 KeyStore 密钥，解封会抛 {@code KeyPermanentlyInvalidatedException}。
 * 这种情况下封装文件就是废纸，用户输口令即可继续；下次解锁时再重新封装。</p>
 */
public final class VaultBio {

    /** 封装文件名（在 .vault 目录内）。不叫 v.hdr 之类，避免和真正的头部混淆 */
    public static final String FILE = "bio";

    /** 文件魔数：前 4 字节不是它就不认，防止把别的文件当封装数据解析 */
    private static final byte[] MAGIC = new byte[]{'H', 'F', 'B', '1'};

    /** KeyStore 别名。不带版本号：升级时要复用同一把，换名等于让所有封装失效 */
    private static final String ALIAS = "hexfile_vault_bio";

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;
    private static final int IV_LEN = 12;
    private static final int PLAIN_LEN = 64;      // enc + mac 各 32
    private static final int VALID_SECONDS = 30;  // 认证后 30 秒内允许用这把 key

    private VaultBio() {
    }

    /**
     * 这台设备能不能用指纹快捷解锁。
     *
     * <p>只认 API 28+：更低版本没有 {@code android.hardware.biometrics.BiometricPrompt}，
     * 而 {@code KeyguardManager} 的设备凭证 Intent 走完<b>不会</b>给 KeyStore 密钥授权，
     * 解封必然失败 —— 与其给一个点了就报错的入口，不如不显示，让用户直接输口令。</p>
     */
    public static boolean deviceReady(Context c) {
        if (Build.VERSION.SDK_INT < 28 || c == null) return false;
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        // 没设锁屏就没有「设备认证」这回事，也就没有可用的授权
        return km != null && km.isDeviceSecure();
    }

    /** 是否已经存过一份封装（不代表能用：密钥可能已被系统销毁，解封时才知道） */
    public static boolean exists(File dir) {
        return dir != null && new File(dir, FILE).isFile();
    }

    /**
     * 用当前主密钥封装一份，覆盖旧的。
     *
     * <p>调用点只有一个：用户开启「指纹快捷解锁」。此时必须已经有主密钥（即刚输过口令），
     * 否则没有东西可封。</p>
     */
    public static void enroll(File dir, VaultCrypto.Master m) throws Exception {
        if (dir == null || m == null) throw new IllegalArgumentException("dir/master required");
        SecretKey k = ensureKey();
        byte[] plain = new byte[PLAIN_LEN];
        System.arraycopy(m.enc, 0, plain, 0, 32);
        System.arraycopy(m.mac, 0, plain, 32, 32);
        byte[] body;
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, k);
            // 绑目录：换一个 .vault 目录（换工作目录）后旧封装不该还能解出新目录的密钥
            c.updateAAD(aad(dir));
            byte[] ct = c.doFinal(plain);
            byte[] iv = c.getIV();
            if (iv == null || iv.length != IV_LEN) throw new IOException("unexpected IV");
            ByteArrayOutputStream bo = new ByteArrayOutputStream(MAGIC.length + 1 + iv.length + ct.length);
            bo.write(MAGIC);
            bo.write(iv.length);
            bo.write(iv);
            bo.write(ct);
            body = bo.toByteArray();
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        writeAtomic(new File(dir, FILE), body);
    }

    /**
     * 解封主密钥。任何一步不对都抛异常，由调用方退回口令输入。
     *
     * <p>这里不吞异常：用户需要知道「指纹这条路暂时不通」，而不是看到一个没反应的按钮。</p>
     */
    public static VaultCrypto.Master unwrap(File dir) throws Exception {
        if (dir == null) throw new IllegalArgumentException("dir required");
        byte[] all = readAll(new File(dir, FILE));
        int head = MAGIC.length + 1;
        if (all.length < head + IV_LEN + 16) throw new IOException("bio: too short");
        for (int i = 0; i < MAGIC.length; i++) {
            if (all[i] != MAGIC[i]) throw new IOException("bio: bad magic");
        }
        int ivLen = all[MAGIC.length] & 0xFF;
        if (ivLen != IV_LEN) throw new IOException("bio: bad iv length");
        byte[] iv = Arrays.copyOfRange(all, head, head + IV_LEN);
        byte[] ct = Arrays.copyOfRange(all, head + IV_LEN, all.length);

        SecretKey k = loadKey();
        if (k == null) throw new IOException("bio: key missing");
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
        c.updateAAD(aad(dir));
        byte[] plain = c.doFinal(ct);
        try {
            if (plain.length != PLAIN_LEN) throw new IOException("bio: bad plain length");
            return new VaultCrypto.Master(Arrays.copyOfRange(plain, 0, 32),
                    Arrays.copyOfRange(plain, 32, 64));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /**
     * 丢掉封装。
     *
     * @param dropKey 是否连 KeyStore 密钥一起删。用户主动关闭时要删（否则密钥白留），
     *                而「重新封装」不需要删 —— 删了反而要重新生成一把。
     */
    public static void forget(File dir, boolean dropKey) {
        if (dir != null) {
            File f = new File(dir, FILE);
            if (f.isFile() && !f.delete()) {
                // 删不掉也不算致命：下次 enroll 会覆盖它，用户也可以手动清目录
                f.deleteOnExit();
            }
        }
        if (!dropKey) return;
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
        } catch (Exception e) {
            // 密钥删不掉时封装文件已经没了，结果是「下次要重新录一次指纹」，不影响安全
            android.util.Log.w("HexFile", "VaultBio: drop key failed: " + e);
        }
    }

    // ---------------------------------------------------------------- KeyStore

    /** 取现有密钥；没有就生成一把。生成需要设备已设锁屏，否则系统会拒绝 */
    private static SecretKey ensureKey() throws Exception {
        SecretKey existing = loadKey();
        if (existing != null) return existing;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 时间型授权：最近 30 秒内通过过设备认证（指纹/面容/锁屏）就能用。
                // 不用 CryptoObject 模式是为了兼容更多厂商实现 —— 那个模式下
                // 部分 ROM 会要求 BiometricPrompt 直接持有 Cipher，绑定关系更脆。
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(VALID_SECONDS)
                .build();
        kg.init(spec);
        return kg.generateKey();
    }

    private static SecretKey loadKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) return null;
        java.security.Key k = ks.getKey(ALIAS, null);
        return k instanceof SecretKey ? (SecretKey) k : null;
    }

    // ---------------------------------------------------------------- 文件

    /** 同样的「先写临时文件再改名」，避免断电留下半截封装（半截 = 永远解不开） */
    private static void writeAtomic(File dst, byte[] data) throws IOException {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            os.write(data);
            os.flush();
            os.getFD().sync();
        } finally {
            closeQuiet(os);
        }
        if (!tmp.renameTo(dst)) {
            if (!dst.delete() || !tmp.renameTo(dst)) {
                tmp.delete();
                throw new IOException("bio: rename failed");
            }
        }
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(64, f.length()));
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            closeQuiet(in);
        }
    }

    private static byte[] aad(File dir) {
        String p = dir.getAbsolutePath();
        return p == null ? new byte[0] : p.getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    private static void closeQuiet(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            android.util.Log.w("HexFile", "VaultBio: close failed: " + e);
        }
    }
}
