package com.hex91.file;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.CRC32;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * v1.70.0 工具箱：加解密 / 摘要核心（纯 Java + javax.crypto，桌面可单测）。
 *
 * <p>对称算法统一走「{@link #sym} 组装 ECB/CBC + PKCS#7」这一条路，AES / DES / 3DES 用 JDK 的
 * Cipher，SM4 用自研 {@link Sm4}（项目没有 BouncyCastle，Android 也没有内置 SM4）。
 *
 * <p>密钥容错：文本密钥不足长度时用 0x00 右补齐到合法长度（16/24/32 等），过长直接报错，
 * 而不是把一个坏密钥悄悄算出一个坏结果 —— 报错信息里会写清需要的字节数。
 */
public final class CryptoCore {

    // ---------------- 算法常量 ----------------
    public static final int A_B64_ENC  = 0;
    public static final int A_B64_DEC  = 1;
    public static final int A_B32_ENC  = 2;
    public static final int A_B32_DEC  = 3;
    public static final int A_URL_ENC  = 4;
    public static final int A_URL_DEC  = 5;
    public static final int A_MD5      = 6;
    public static final int A_SHA1     = 7;
    public static final int A_SHA256   = 8;
    public static final int A_SHA512   = 9;
    public static final int A_SM3      = 10;
    public static final int A_CRC32    = 11;
    public static final int A_HMAC256  = 12;
    public static final int A_HMAC_SM3 = 13;
    public static final int A_AES      = 14;
    public static final int A_DES      = 15;
    public static final int A_3DES     = 16;
    public static final int A_SM4      = 17;

    public static final String[] NAMES = {
            "Base64 编码", "Base64 解码", "Base32 编码", "Base32 解码",
            "URL 编码", "URL 解码",
            "MD5", "SHA-1", "SHA-256", "SHA-512", "SM3", "CRC32",
            "HMAC-SHA256", "HMAC-SM3",
            "AES", "DES", "3DES", "SM4"
    };

    public static final int MODE_ECB = 0;
    public static final int MODE_CBC = 1;
    public static final int OUT_B64 = 0;
    public static final int OUT_HEX = 1;

    public static String name(int a) {
        return (a >= 0 && a < NAMES.length) ? NAMES[a] : "未知";
    }

    /** 是否需要密钥（对称加密与 HMAC） */
    public static boolean needKey(int a) {
        return a >= A_HMAC256 && a <= A_HMAC_SM3 || isSym(a);
    }

    /** 是否需要 IV（只有分组密码的 CBC 模式用） */
    public static boolean needIv(int a) {
        return isSym(a);
    }

    public static boolean isSym(int a) {
        return a >= A_AES && a <= A_SM4;
    }

    public static boolean isHash(int a) {
        return a >= A_MD5 && a <= A_HMAC_SM3;
    }

    /** 对称算法需要的密钥字节数说明（含合法长度集合） */
    public static String keyHint(int a) {
        switch (a) {
            case A_AES:  return "16 / 24 / 32 字节（AES-128/192/256），不足自动补 0";
            case A_DES:  return "8 字节（DES），不足自动补 0";
            case A_3DES: return "16 或 24 字节（3DES），不足自动补 0";
            case A_SM4:  return "16 字节（SM4），不足自动补 0";
            case A_HMAC256:
            case A_HMAC_SM3: return "任意长度";
            default: return "";
        }
    }

    public static int blockSize(int a) {
        switch (a) {
            case A_AES:  return 16;
            case A_DES:  return 8;
            case A_3DES: return 8;
            case A_SM4:  return 16;
            default: return 16;
        }
    }

    private CryptoCore() {
    }

    // ============================================================ 统一入口

    /**
     * @param alg     算法常量
     * @param encrypt true 加密/编码，false 解密/解码（对摘要无效）
     * @param input   输入文本
     * @param key     密钥（文本；以 {@code hex:} 开头时按十六进制解析）
     * @param iv      IV（文本；空则全 0）
     * @param mode    {@link #MODE_ECB} / {@link #MODE_CBC}
     * @param outFmt  密文输出格式 {@link #OUT_B64} / {@link #OUT_HEX}
     */
    public static String run(int alg, boolean encrypt, String input, String key, String iv,
                             int mode, int outFmt) {
        if (input == null) input = "";
        switch (alg) {
            case A_B64_ENC: return CodecCore.base64Encode(input.getBytes(utf8()), false, true);
            case A_B64_DEC: return new String(CodecCore.base64Decode(input), utf8());
            case A_B32_ENC: return CodecCore.base32Encode(input.getBytes(utf8()));
            case A_B32_DEC: return new String(CodecCore.base32Decode(input), utf8());
            case A_URL_ENC: return CodecCore.percentEncode(input.getBytes(utf8()));
            case A_URL_DEC: return new String(CodecCore.percentDecode(input), utf8());
            case A_MD5:     return digestHex("MD5", input.getBytes(utf8()));
            case A_SHA1:    return digestHex("SHA-1", input.getBytes(utf8()));
            case A_SHA256:  return digestHex("SHA-256", input.getBytes(utf8()));
            case A_SHA512:  return digestHex("SHA-512", input.getBytes(utf8()));
            case A_SM3:     return hex(Sm3.digest(input.getBytes(utf8())));
            case A_CRC32:   return crc32(input.getBytes(utf8()));
            case A_HMAC256: return hex(hmac("HmacSHA256", toKeyBytes(key), input.getBytes(utf8())));
            case A_HMAC_SM3:return hex(hmacSm3(toKeyBytes(key), input.getBytes(utf8())));
            default:
                return sym(alg, encrypt, input, key, iv, mode, outFmt);
        }
    }

    // ============================================================ 对称加解密

    private static String sym(int alg, boolean encrypt, String input, String key, String iv,
                              int mode, int outFmt) {
        byte[] kb = fitKey(alg, toKeyBytes(key));
        if (kb == null) throw new IllegalArgumentException("请填写密钥（" + keyHint(alg) + "）");
        if (alg == A_SM4) return sm4(encrypt, input, kb, iv, mode, outFmt);

        String algName = alg == A_AES ? "AES" : (alg == A_DES ? "DES" : "DESede");
        String jceMode = mode == MODE_CBC ? "/CBC/PKCS5Padding" : "/ECB/PKCS5Padding";
        try {
            byte[] data;
            if (encrypt) {
                data = input.getBytes(utf8());
            } else {
                data = parseCipherText(input);
            }
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance(algName + jceMode);
            if (alg == A_AES) {
                c.init(encrypt ? javax.crypto.Cipher.ENCRYPT_MODE : javax.crypto.Cipher.DECRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(kb, "AES"),
                        ivSpec(alg, iv, mode));
            } else if (alg == A_DES) {
                c.init(encrypt ? javax.crypto.Cipher.ENCRYPT_MODE : javax.crypto.Cipher.DECRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(kb, "DES"),
                        ivSpec(alg, iv, mode));
            } else {
                // 3DES 的 16 字节密钥要补成 24 字节（K1K2K1），否则 JCE 直接拒绝
                byte[] k24 = kb;
                if (kb.length == 16) {
                    k24 = new byte[24];
                    System.arraycopy(kb, 0, k24, 0, 16);
                    System.arraycopy(kb, 0, k24, 16, 8);
                }
                c.init(encrypt ? javax.crypto.Cipher.ENCRYPT_MODE : javax.crypto.Cipher.DECRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(k24, "DESede"),
                        ivSpec(alg, iv, mode));
            }
            byte[] out = c.doFinal(data);
            if (encrypt) {
                return outFmt == OUT_HEX ? hexUpper(out) : CodecCore.base64Encode(out, false, true);
            }
            return new String(out, utf8());
        } catch (javax.crypto.BadPaddingException e) {
            throw new IllegalArgumentException("解密失败：填充校验不通过（密钥 / IV / 模式不对）");
        } catch (javax.crypto.IllegalBlockSizeException e) {
            throw new IllegalArgumentException("解密失败：密文长度不是分组的整数倍，或密文不完整");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("运算失败：" + e.getMessage());
        }
    }

    private static javax.crypto.spec.IvParameterSpec ivSpec(int alg, String iv, int mode) {
        if (mode != MODE_CBC) return null;
        byte[] base = fitIv(alg, iv);
        return new javax.crypto.spec.IvParameterSpec(base);
    }

    private static byte[] fitIv(int alg, String iv) {
        int bs = blockSize(alg);
        // 与密钥同一套规则：支持 hex:000102…，否则按 UTF-8 文本。
        // 以前直接 getBytes()，用户按密钥的习惯填 hex: 时，32 个 ASCII 字符
        // 会被截成 16 个字符当 IV —— 解密结果必然错，而 AES 有时碰巧通过填充校验，
        // 于是静默输出一堆垃圾，比直接报错难查得多。
        byte[] src = toKeyBytes(iv);
        byte[] out = new byte[bs];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, bs));
        return out;
    }

    /** SM4 手工组装 ECB/CBC + PKCS#7（JDK 没有 SM4） */
    private static String sm4(boolean encrypt, String input, byte[] key, String iv,
                              int mode, int outFmt) {
        int[] rk;
        try {
            rk = Sm4.expandKey(key);
        } catch (IllegalArgumentException e) {
            throw e;
        }
        byte[] ivb = mode == MODE_CBC ? fitIv(A_SM4, iv) : null;
        if (encrypt) {
            byte[] plain = input.getBytes(utf8());
            int pad = 16 - (plain.length % 16);
            byte[] in = new byte[plain.length + pad];
            System.arraycopy(plain, 0, in, 0, plain.length);
            for (int i = plain.length; i < in.length; i++) in[i] = (byte) pad;
            byte[] out = new byte[in.length];
            byte[] prev = ivb;
            for (int off = 0; off < in.length; off += 16) {
                byte[] blk = new byte[16];
                for (int i = 0; i < 16; i++) blk[i] = (byte) (in[off + i] ^ (prev == null ? 0 : prev[i]));
                Sm4.encryptBlock(rk, blk, 0, out, off);
                if (prev != null) {                    // 只有 CBC 才回填链值，ECB 各块独立
                    prev = new byte[16];
                    System.arraycopy(out, off, prev, 0, 16);
                }
            }
            return outFmt == OUT_HEX ? hexUpper(out) : CodecCore.base64Encode(out, false, true);
        }
        byte[] ct = parseCipherText(input);
        if (ct.length == 0 || ct.length % 16 != 0) {
            throw new IllegalArgumentException("解密失败：密文长度必须是 16 的整数倍（当前 " + ct.length + " 字节）");
        }
        byte[] out = new byte[ct.length];
        byte[] prev = ivb;
        for (int off = 0; off < ct.length; off += 16) {
            Sm4.decryptBlock(rk, ct, off, out, off);
            if (prev != null) {
                for (int i = 0; i < 16; i++) out[off + i] ^= prev[i];
                prev = new byte[16];
                System.arraycopy(ct, off, prev, 0, 16);
            }
        }
        int pad = out[out.length - 1] & 0xFF;
        if (pad < 1 || pad > 16) throw new IllegalArgumentException("解密失败：填充校验不通过（密钥 / IV / 模式不对）");
        for (int i = out.length - pad; i < out.length; i++) {
            if ((out[i] & 0xFF) != pad) throw new IllegalArgumentException("解密失败：填充校验不通过（密钥 / IV / 模式不对）");
        }
        return new String(out, 0, out.length - pad, utf8());
    }

    // ============================================================ 摘要 / HMAC

    public static byte[] digest(String jceAlg, byte[] data) {
        try {
            return MessageDigest.getInstance(jceAlg).digest(data);
        } catch (Exception e) {
            throw new IllegalArgumentException("不支持该摘要算法：" + jceAlg);
        }
    }

    public static String digestHex(String jceAlg, byte[] data) {
        return hex(digest(jceAlg, data));
    }

    public static byte[] hmac(String jceAlg, byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance(jceAlg);
            m.init(new SecretKeySpec(key, jceAlg));
            return m.doFinal(data);
        } catch (Exception e) {
            throw new IllegalArgumentException("HMAC 失败：" + e.getMessage());
        }
    }

    /** HMAC-SM3：把 SM3 当作 HMAC 的 H 函数（b=64 字节分组），自己算 ipad/opad */
    public static byte[] hmacSm3(byte[] key, byte[] data) {
        int block = 64;
        byte[] k = key.length > block ? Sm3.digest(key) : key;
        byte[] ipad = new byte[block], opad = new byte[block];
        for (int i = 0; i < block; i++) {
            ipad[i] = (byte) (i < k.length ? k[i] ^ 0x36 : 0x36);
            opad[i] = (byte) (i < k.length ? k[i] ^ 0x5C : 0x5C);
        }
        byte[] inner = Sm3.digest(concat(ipad, data));
        return Sm3.digest(concat(opad, inner));
    }

    public static String crc32(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data, 0, data.length);
        return String.format(Locale.US, "%08x", c.getValue());
    }

    // ============================================================ 密钥 / 密文解析

    /** 支持 {@code hex:0011aabb} 前缀指定十六进制密钥；否则按 UTF-8 文本 */
    public static byte[] toKeyBytes(String key) {
        if (key == null) return new byte[0];
        String k = key.trim();
        if (k.toLowerCase(Locale.US).startsWith("hex:")) {
            return CodecCore.toBytes(k.substring(4), CodecCore.F_HEX);
        }
        return key.getBytes(utf8());
    }

    /** 把密钥补齐/校正到算法允许的长度；返回 null 表示密钥为空 */
    private static byte[] fitKey(int alg, byte[] key) {
        if (key.length == 0) return null;
        switch (alg) {
            case A_AES:
                if (key.length <= 16) return pad(key, 16);
                if (key.length <= 24) return pad(key, 24);
                if (key.length <= 32) return pad(key, 32);
                throw new IllegalArgumentException("AES 密钥最长 32 字节（当前 " + key.length + "）");
            case A_DES:
                if (key.length > 8) throw new IllegalArgumentException("DES 密钥必须 8 字节（当前 " + key.length + "）");
                return pad(key, 8);
            case A_3DES:
                if (key.length <= 16) return pad(key, 16);
                if (key.length <= 24) return pad(key, 24);
                throw new IllegalArgumentException("3DES 密钥最长 24 字节（当前 " + key.length + "）");
            case A_SM4:
                if (key.length > 16) throw new IllegalArgumentException("SM4 密钥必须 16 字节（当前 " + key.length + "）");
                return pad(key, 16);
            default:
                return key;
        }
    }

    private static byte[] pad(byte[] src, int len) {
        if (src.length == len) return src;
        byte[] out = new byte[len];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, len));
        return out;
    }

    /**
     * 密文输入自动识别：纯十六进制（可带空格/0x）按 HEX，否则按 Base64。
     * 解密场景下手输密文最常见的就是这两种，省一个下拉框。
     */
    public static byte[] parseCipherText(String s) {
        String t = s.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("请填写密文");
        boolean looksHex = true;
        int digits = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (CodecCore.hexVal(c) >= 0) { digits++; continue; }
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ',' || c == ':') continue;
            if (c == 'x' || c == 'X') continue;
            looksHex = false;
            break;
        }
        if (looksHex && digits > 0 && digits % 2 == 0) {
            return CodecCore.toBytes(t, CodecCore.F_HEX);
        }
        return CodecCore.base64Decode(t);
    }

    // ============================================================ 小工具

    public static String hex(byte[] d) {
        return CodecCore.hexDump(d, "", "", true);
    }

    public static String hexUpper(byte[] d) {
        return CodecCore.hexDump(d, "", "", false);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static java.nio.charset.Charset utf8() {
        return java.nio.charset.Charset.forName("UTF-8");
    }
}
