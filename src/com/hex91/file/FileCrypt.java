package com.hex91.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 文件加密 / 解密（对标 MT「加密」功能，自家私有格式，需本工具解密）
 *
 * 设计目标：零第三方依赖，纯 javax.crypto；强 KDF + 认证加密（防篡改、防密码探测）。
 *
 * 文件格式（字节序）：
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
 * 加密顺序：先加密后算 MAC（Encrypt-then-MAC），解密先验 MAC 再解密。
 */
public class FileCrypt {

    private static final String MAGIC = "MTENC1";
    private static final int VERSION = 1;
    private static final int KEY_BITS = 256;
    private static final int ITERATIONS = 100_000;
    private static final int SALT_LEN = 16;
    private static final String ALGO = "AES";
    private static final String TRANSFORM = "AES/CBC/PKCS5Padding";
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final String MAC_ALGO = "HmacSHA256";

    /** 加密文件：输出 file + ".enc"（同名同目录）。返回生成的目标文件。 */
    public static File encrypt(File file, String password) throws Exception {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("源文件不存在");
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("密码为空");
        byte[] salt = new byte[SALT_LEN];
        new SecureRandom().nextBytes(salt);
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);

        byte[] plain = readAll(file);
        byte[] encKey = new byte[32];
        byte[] authKey = new byte[32];
        derive(password, salt, encKey, authKey);

        // 加密
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, ALGO), new IvParameterSpec(iv));
        byte[] ct = c.doFinal(plain);

        // MAC over salt || iv || ciphertext
        Mac mac = Mac.getInstance(MAC_ALGO);
        mac.init(new SecretKeySpec(authKey, MAC_ALGO));
        mac.update(salt);
        mac.update(iv);
        mac.update(ct);
        byte[] tag = mac.doFinal();

        // 组装
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        bo.write(MAGIC.getBytes("US-ASCII"));
        bo.write(VERSION);
        writeIntBE(bo, ITERATIONS);
        bo.write(SALT_LEN);
        bo.write(salt);
        bo.write(iv);
        bo.write(ct);
        bo.write(tag);

        File out = new File(file.getParentFile(), file.getName() + ".enc");
        writeFile(out, bo.toByteArray());
        // 抹除明文副本
        Arrays.fill(plain, (byte) 0);
        return out;
    }

    /** 解密 .enc 文件：输出去掉 .enc 后缀；若原不以 .enc 结尾则加 .dec。密码错误抛 SecurityException。 */
    public static File decrypt(File file, String password) throws Exception {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("加密文件不存在");
        byte[] blob = readAll(file);
        int off = 0;
        // 魔数
        byte[] magic = Arrays.copyOfRange(blob, off, off + 6);
        if (!new String(magic, "US-ASCII").equals(MAGIC)) {
            throw new IllegalArgumentException("不是本工具加密的文件（魔数不符）");
        }
        off += 6;
        int ver = blob[off++] & 0xFF;
        if (ver != VERSION) throw new IllegalArgumentException("不支持的版本: " + ver);
        int iter = readIntBE(blob, off); off += 4;
        int sl = blob[off++] & 0xFF;
        byte[] salt = Arrays.copyOfRange(blob, off, off + sl); off += sl;
        byte[] iv = Arrays.copyOfRange(blob, off, off + 16); off += 16;
        int ctLen = blob.length - off - 32;
        if (ctLen < 0) throw new IllegalArgumentException("文件长度异常");
        byte[] ct = Arrays.copyOfRange(blob, off, off + ctLen); off += ctLen;
        byte[] tag = Arrays.copyOfRange(blob, off, off + 32);

        byte[] encKey = new byte[32];
        byte[] authKey = new byte[32];
        derive(password, salt, encKey, authKey);

        // 验 MAC（先验证再解密，防侧信道/篡改）
        Mac mac = Mac.getInstance(MAC_ALGO);
        mac.init(new SecretKeySpec(authKey, MAC_ALGO));
        mac.update(salt);
        mac.update(iv);
        mac.update(ct);
        byte[] exp = mac.doFinal();
        if (!MessageDigest.isEqual(exp, tag)) {
            throw new SecurityException("密码错误或文件被篡改");
        }

        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, ALGO), new IvParameterSpec(iv));
        byte[] plain = c.doFinal(ct);

        File out;
        String name = file.getName();
        if (name.toLowerCase().endsWith(".enc")) {
            out = new File(file.getParentFile(), name.substring(0, name.length() - 4));
        } else {
            out = new File(file.getParentFile(), name + ".dec");
        }
        writeFile(out, plain);
        Arrays.fill(plain, (byte) 0);
        return out;
    }

    private static void derive(String password, byte[] salt, byte[] encKey, byte[] authKey)
            throws InvalidKeySpecException, java.security.NoSuchAlgorithmException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 64 * 8);
        SecretKeyFactory skf = SecretKeyFactory.getInstance(KDF);
        byte[] dk = skf.generateSecret(spec).getEncoded();
        System.arraycopy(dk, 0, encKey, 0, 32);
        System.arraycopy(dk, 32, authKey, 0, 32);
    }

    private static void writeIntBE(java.io.ByteArrayOutputStream bo, int v) {
        bo.write((v >>> 24) & 0xFF);
        bo.write((v >>> 16) & 0xFF);
        bo.write((v >>> 8) & 0xFF);
        bo.write(v & 0xFF);
    }

    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
