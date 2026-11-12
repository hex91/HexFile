package com.hex91.file.net;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * HLS 分片的 AES-128-CBC 解密 —— v1.92.0，纯 Java（桌面 JVM 与 Android 共用）。
 *
 * <p>规范写得很短：{@code METHOD=AES-128} 表示「整段按 AES-128-CBC 加密，PKCS7 填充」。
 * 但真实站点里有两种常见偏差，硬按规范解会直接抛异常把整条下载打断：</p>
 * <ol>
 *   <li><b>明明是明文却挂着 KEY</b>：切换清晰度时 KEY 行没删干净，或首尾片段不加密。
 *       所以解密前先看首字节是不是 TS 同步字节（0x47），是就原样返回 —— 这比
 *       「解密失败再猜」可靠，因为错误密钥解出来的垃圾可能撞巧通过填充校验。</li>
 *   <li><b>NoPadding 包装</b>：有的打包器把数据补齐到 16 的倍数后按 NoPadding 加密，
 *       末尾没有合法的填充字节。PKCS5 解不开，退回 NoPadding。</li>
 * </ol>
 */
public final class HlsCrypto {

    /** 一个 TS 包 188 字节，同步字节 0x47 */
    private static final int TS_PACKET = 188;

    private HlsCrypto() {}

    /**
     * 解密一段分片。
     *
     * @param data 原始字节（可能是明文）
     * @param key  16 字节密钥
     * @param iv   16 字节 IV（见 {@link HlsKey#ivFor(long)}）
     * @return 明文；输入为明文时原样返回
     */
    public static byte[] decrypt(byte[] data, byte[] key, byte[] iv) throws GeneralSecurityException {
        if (data == null || data.length == 0) return data;
        if (key == null || key.length != 16) throw new GeneralSecurityException("AES-128 密钥长度必须是 16 字节");
        if (iv == null || iv.length != 16) throw new GeneralSecurityException("AES IV 长度必须是 16 字节");

        // 明文分片：不加密却带了 KEY 行，原样返回（否则一定解出乱码）。
        // fMP4 也要放行：styp/moof 开头的分片长度常常正好是 16 的倍数，
        // 只认 TS 的话会把它当密文丢进 CBC，最后报「密钥或 IV 与清单不匹配」。
        if (looksLikeTs(data) || looksLikeFmp4(data)) return data;
        if (data.length % 16 != 0) {
            // 不是 16 的倍数，绝不可能是 CBC 密文；当成明文交出去，让后面的转封装去报具体错误
            return data;
        }

        try {
            return cipher(data, key, iv, "AES/CBC/PKCS5Padding");
        } catch (GeneralSecurityException first) {
            // 少数打包器按 NoPadding 加密（数据本身已补齐到 16 的倍数、末尾没有填充字节）。
            // 但 NoPadding 解密永远不会报错 —— 密钥错了也照样吐出一堆垃圾。
            // 所以解完必须看一眼「像不像媒体」，不像就按密钥错误处理，
            // 否则用户拿到的是一个能生成、但播不了的文件，比直接报错难查得多。
            byte[] raw = cipher(data, key, iv, "AES/CBC/NoPadding");
            if (looksLikeMedia(raw)) return raw;
            throw new GeneralSecurityException("分片解密失败：密钥或 IV 与清单不匹配");
        }
    }

    /** 解密结果的合理性判断：TS / fMP4 / ADTS / 裸 NAL 起始码，认一个就算 */
    static boolean looksLikeMedia(byte[] d) {
        if (looksLikeTs(d) || looksLikeFmp4(d)) return true;
        if (d == null || d.length < 4) return false;
        // ADTS（AAC）：0xFFF 同步字
        if ((d[0] & 0xFF) == 0xFF && ((d[1] & 0xF0) == 0xF0)) return true;
        // 裸 H.264/H.265：00 00 00 01 起始码
        return d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 1;
    }

    private static byte[] cipher(byte[] data, byte[] key, byte[] iv, String mode)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(mode);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    /**
     * 字节嗅探 MPEG-TS：188 字节一个包、同步字节 0x47。
     * <p>至少看三个包（间隔 188）才下结论，单个 0x47 太容易误判。</p>
     */
    public static boolean looksLikeTs(byte[] d) {
        if (d == null || d.length < TS_PACKET * 2 + 1) return false;
        if (d[0] != 0x47) return false;
        if (d[TS_PACKET] != 0x47) return false;
        return d.length < TS_PACKET * 2 + 1 || d[TS_PACKET * 2] == 0x47;
    }

    /** fMP4 分片：第 4~8 字节是 'ftyp'/'styp'/'moof'/'moov' 之一 */
    public static boolean looksLikeFmp4(byte[] d) {
        if (d == null || d.length < 12) return false;
        String box = new String(new char[]{(char) d[4], (char) d[5], (char) d[6], (char) d[7]});
        return "ftyp".equals(box) || "styp".equals(box) || "moof".equals(box) || "moov".equals(box);
    }
}
