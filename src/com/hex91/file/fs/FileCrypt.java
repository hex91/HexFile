package com.hex91.file.fs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 文件加密 / 解密（自家私有格式，需本工具解密）
 *
 * 设计目标：零第三方依赖，纯 javax.crypto；强 KDF + 认证加密（防篡改、防密码探测）；
 * v1.55.0 起全流程流式处理（8KB 缓冲），任意大小文件不再整体读入内存，支持进度回调。
 *
 * 文件格式（字节序，与 v1.5x 旧版完全兼容）：
 *   "MTENC1"        6 字节魔数
 *   version         1 字节（当前 1）
 *   iterations      4 字节大端 int（PBKDF2 迭代次数）
 *   saltLen         1 字节
 *   salt            saltLen 字节（随机）
 *   iv              16 字节（AES-CBC 初始向量）
 *   ciphertext      AES-256-CBC(PKCS7(plaintext))
 *   mac             32 字节 HMAC-SHA256(authKey, salt || iv || ciphertext)
 *
 * 密钥派生：PBKDF2-HMAC-SHA256(password, salt, iterations, 64B)
 *   = encKey(前 32B, AES-256) || authKey(后 32B, HMAC)
 * 加密顺序：先加密后算 MAC（Encrypt-then-MAC）；解密先整体验 MAC 再解密（两遍扫描）。
 */
public class FileCrypt {

    /**
     * 加解密失败的原因码 —— v2.31.0（F-14/16）。
     *
     * <p>原来这里直接 {@code throw new IllegalArgumentException("源文件不存在")}，
     * UI 侧 {@code toast(e.getMessage())} 把中文原样抛给用户。文案要出英文时，
     * fs 层不该知道语言，所以改成码；{@link #getMessage()} 只留 ASCII 诊断串（日志/单测）。</p>
     *
     * <p>仍 {@code extends IllegalArgumentException}：调用方原有的 catch 一行不改。</p>
     */
    public enum Code {
        /** 待加密的源文件不存在 */
        SRC_MISSING,
        /** 密码为空 */
        PW_EMPTY,
        /** 待解密的 .enc 不存在 */
        ENC_MISSING,
        /** 头部/流长度对不上（文件被截断） */
        CORRUPT,
        /** 魔数不符：不是本工具的加密文件 */
        NOT_OURS,
        /** 版本号不认识（参数：实际版本字节，用 num() 取） */
        BAD_VERSION,
        /** 头部记录的长度/迭代次数不合理 */
        BAD_HEADER
    }

    /** 带错误码的失败（仍是 {@link IllegalArgumentException}，既有 catch 不受影响） */
    public static class Fail extends IllegalArgumentException {
        public final Code code;
        private final byte[] raw;

        Fail(Code code, int... raw) {
            super(diagnose(code, raw));
            this.code = code;
            this.raw = raw.length == 0 ? null : new byte[]{(byte) raw[0]};
        }

        /** 参数里的第一个整数（目前只有 BAD_VERSION 用） */
        public int num() {
            return raw == null ? -1 : raw[0] & 0xFF;
        }
    }

    private static String diagnose(Code code, int[] raw) {
        switch (code) {
            case SRC_MISSING: return "crypt: source file missing";
            case PW_EMPTY: return "crypt: empty password";
            case ENC_MISSING: return "crypt: .enc file missing";
            case CORRUPT: return "crypt: truncated or corrupt container";
            case NOT_OURS: return "crypt: bad magic";
            case BAD_VERSION: return "crypt: unsupported version " + (raw.length > 0 ? raw[0] : -1);
            default: return "crypt: malformed header";
        }
    }

    /** 进度回调（done/total 为字节数；在后台线程调用，UI 更新由调用方切换） */
    public interface Progress {
        void on(long done, long total);
    }

    private static final String MAGIC = "MTENC1";
    private static final int VERSION = 1;
    private static final int ITERATIONS = 100_000;
    private static final int SALT_LEN = 16;
    private static final String ALGO = "AES";
    private static final String TRANSFORM = "AES/CBC/PKCS5Padding";
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final String MAC_ALGO = "HmacSHA256";
    private static final int BUF = 8192;   // 16 的倍数，保证 CBC 块对齐

    /** 加密文件：输出 file + ".enc"（同名同目录）。返回生成的目标文件。 */
    public static File encrypt(File file, String password) throws Exception {
        return encrypt(file, password, null);
    }

    public static File encrypt(File file, String password, Progress cb) throws Exception {
        if (file == null || !file.isFile()) throw new Fail(Code.SRC_MISSING);
        if (password == null || password.isEmpty()) throw new Fail(Code.PW_EMPTY);
        byte[] salt = new byte[SALT_LEN];
        new SecureRandom().nextBytes(salt);
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);

        byte[] encKey = new byte[32];
        byte[] authKey = new byte[32];
        derive(password, salt, encKey, authKey);

        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, ALGO),
                new IvParameterSpec(iv));
        Mac mac = Mac.getInstance(MAC_ALGO);
        mac.init(new SecretKeySpec(authKey, MAC_ALGO));
        mac.update(salt);
        mac.update(iv);

        File out = new File(file.getParentFile(), file.getName() + ".enc");
        long total = file.length();
        try {
            FileOutputStream fo = new FileOutputStream(out);
            try {
                // 头部
                fo.write(MAGIC.getBytes("US-ASCII"));
                fo.write(VERSION);
                writeIntBE(fo, ITERATIONS);
                fo.write(SALT_LEN);
                fo.write(salt);
                fo.write(iv);
                // 流式加密
                FileInputStream fi = new FileInputStream(file);
                try {
                    byte[] buf = new byte[BUF];
                    long done = 0;
                    int n;
                    while ((n = fi.read(buf)) > 0) {
                        byte[] enc = cipher.update(buf, 0, n);
                        if (enc != null && enc.length > 0) {
                            fo.write(enc);
                            mac.update(enc);
                        }
                        done += n;
                        if (cb != null) cb.on(done, total);
                    }
                    byte[] fin = cipher.doFinal();
                    fo.write(fin);
                    mac.update(fin);
                    fo.write(mac.doFinal());
                } finally {
                    try { fi.close(); } catch (IOException ignored) {}
                }
            } finally {
                try { fo.close(); } catch (IOException ignored) {}
            }
        } catch (Exception e) {
            out.delete();   // 中途失败不留半截密文
            throw e;
        }
        return out;
    }

    /** 解密 .enc 文件：输出去掉 .enc 后缀；若原不以 .enc 结尾则加 .dec。密码错误抛 SecurityException。 */
    public static File decrypt(File file, String password) throws Exception {
        return decrypt(file, password, null);
    }

    public static File decrypt(File file, String password, Progress cb) throws Exception {
        if (file == null || !file.isFile()) throw new Fail(Code.ENC_MISSING);
        long total = file.length();
        // 头部
        byte[] hb = new byte[6 + 1 + 4 + 1];
        InputStream hin = new FileInputStream(file);
        try {
            if (readFully(hin, hb) != hb.length) throw new Fail(Code.CORRUPT);
        } finally {
            try { hin.close(); } catch (IOException ignored) {}
        }
        if (!new String(hb, 0, 6, "US-ASCII").equals(MAGIC)) {
            throw new Fail(Code.NOT_OURS);
        }
        if (hb[6] != VERSION) throw new Fail(Code.BAD_VERSION, hb[6]);
        int iter = ((hb[7] & 0xFF) << 24) | ((hb[8] & 0xFF) << 16)
                | ((hb[9] & 0xFF) << 8) | (hb[10] & 0xFF);
        int sl = hb[11] & 0xFF;
        int headerLen = hb.length + sl + 16;
        long ctLen = total - headerLen - 32;
        if (ctLen < 0 || iter <= 0) throw new Fail(Code.BAD_HEADER);
        byte[] salt = new byte[sl];
        byte[] iv = new byte[16];
        InputStream hi2 = new FileInputStream(file);
        try {
            long skip = hb.length;
            while (skip > 0) skip -= hi2.skip(skip);
            if (readFully(hi2, salt) != sl) throw new Fail(Code.CORRUPT);
            if (readFully(hi2, iv) != 16) throw new Fail(Code.CORRUPT);
        } finally {
            try { hi2.close(); } catch (IOException ignored) {}
        }

        byte[] encKey = new byte[32];
        byte[] authKey = new byte[32];
        derive(password, salt, encKey, authKey, iter);

        // 第一遍：整体验 MAC（先验证再解密，防篡改）
        Mac mac = Mac.getInstance(MAC_ALGO);
        mac.init(new SecretKeySpec(authKey, MAC_ALGO));
        mac.update(salt);
        mac.update(iv);
        InputStream vi = new FileInputStream(file);
        try {
            long skip = headerLen;
            while (skip > 0) skip -= vi.skip(skip);
            byte[] buf = new byte[BUF];
            long left = ctLen, done = 0;
            while (left > 0) {
                int n = vi.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new Fail(Code.CORRUPT);
                mac.update(buf, 0, n);
                left -= n;
                done += n;
                if (cb != null) cb.on(done / 2, total);   // 验 MAC 占一半进度
            }
            byte[] tag = new byte[32];
            if (readFully(vi, tag) != 32) throw new Fail(Code.CORRUPT);
            if (!MessageDigest.isEqual(mac.doFinal(), tag)) {
                throw new SecurityException("crypt: bad password or tampered data");
            }
        } finally {
            try { vi.close(); } catch (IOException ignored) {}
        }

        // 第二遍：流式解密
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, ALGO),
                new IvParameterSpec(iv));
        String name = file.getName();
        File out = name.toLowerCase().endsWith(".enc")
                ? new File(file.getParentFile(), name.substring(0, name.length() - 4))
                : new File(file.getParentFile(), name + ".dec");
        try {
            FileInputStream fi = new FileInputStream(file);
            try {
                long skip = headerLen;
                while (skip > 0) skip -= fi.skip(skip);
                FileOutputStream fo = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[BUF];
                    long left = ctLen, done = 0;
                    while (left > 0) {
                        int n = fi.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) break;
                        byte[] dec = cipher.update(buf, 0, n);
                        if (dec != null && dec.length > 0) fo.write(dec);
                        left -= n;
                        done += n;
                        if (cb != null) cb.on(ctLen + done / 2, total);
                    }
                    fo.write(cipher.doFinal());
                } finally {
                    try { fo.close(); } catch (IOException ignored) {}
                }
            } finally {
                try { fi.close(); } catch (IOException ignored) {}
            }
        } catch (Exception e) {
            out.delete();
            throw e;
        }
        return out;
    }

    private static void derive(String password, byte[] salt, byte[] encKey, byte[] authKey)
            throws InvalidKeySpecException, java.security.NoSuchAlgorithmException {
        derive(password, salt, encKey, authKey, ITERATIONS);
    }

    private static void derive(String password, byte[] salt, byte[] encKey, byte[] authKey,
            int iterations)
            throws InvalidKeySpecException, java.security.NoSuchAlgorithmException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 64 * 8);
        SecretKeyFactory skf = SecretKeyFactory.getInstance(KDF);
        byte[] dk = skf.generateSecret(spec).getEncoded();
        System.arraycopy(dk, 0, encKey, 0, 32);
        System.arraycopy(dk, 32, authKey, 0, 32);
    }

    private static void writeIntBE(OutputStream o, int v) throws IOException {
        o.write((v >>> 24) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    /** 尽量读满 len 字节，返回实际读取数（流结束时可能不足） */
    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }
}
