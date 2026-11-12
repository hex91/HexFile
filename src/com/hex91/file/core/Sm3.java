package com.hex91.file;

/**
 * v1.70.0 国密 SM3 杂凑算法（GB/T 32905-2016），纯 Java 实现。
 *
 * <p>结构：分组 512 bit、输出 256 bit，压缩函数 64 轮；IV 与常量 Tj 按标准写死。
 * 没有依赖 BouncyCastle —— 项目只带 apksigner，没有 bcprov，自己实现反而更可控。
 *
 * <p>标准测试向量：SM3("abc") = 66c7f0f4 62eeedd9 d1f2d46b dc10e4e2
 * 4167c487 5cf2f7a2 297da02b 8f4ba8e0（见 ToolboxCoreTest）。
 */
public final class Sm3 {

    private static final int[] IV = {
            0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
            0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e
    };

    private Sm3() {
    }

    public static byte[] digest(byte[] msg) {
        if (msg == null) msg = new byte[0];
        int len = msg.length;
        int padLen = ((len + 8) / 64 + 1) * 64;        // 至少留 8 字节放比特长度
        byte[] m = new byte[padLen];
        System.arraycopy(msg, 0, m, 0, len);
        m[len] = (byte) 0x80;
        long bits = (long) len * 8;
        for (int i = 0; i < 8; i++) {
            m[padLen - 1 - i] = (byte) (bits >>> (8 * i));
        }

        int[] v = IV.clone();
        int[] w = new int[68];
        int[] w1 = new int[64];
        for (int off = 0; off < padLen; off += 64) {
            for (int i = 0; i < 16; i++) {
                w[i] = ((m[off + i * 4] & 0xFF) << 24)
                        | ((m[off + i * 4 + 1] & 0xFF) << 16)
                        | ((m[off + i * 4 + 2] & 0xFF) << 8)
                        | (m[off + i * 4 + 3] & 0xFF);
            }
            for (int i = 16; i < 68; i++) {
                w[i] = p1(w[i - 16] ^ w[i - 9] ^ rotl(w[i - 3], 15))
                        ^ rotl(w[i - 13], 7) ^ w[i - 6];
            }
            for (int i = 0; i < 64; i++) w1[i] = w[i] ^ w[i + 4];

            int a = v[0], b = v[1], c = v[2], d = v[3];
            int e = v[4], f = v[5], g = v[6], h = v[7];
            for (int j = 0; j < 64; j++) {
                int t = j < 16 ? 0x79cc4519 : 0x7a879d8a;
                int ss1 = rotl(rotl(a, 12) + e + rotl(t, j % 32), 7);
                int ss2 = ss1 ^ rotl(a, 12);
                int ff = j < 16 ? (a ^ b ^ c) : ((a & b) | (a & c) | (b & c));
                int gg = j < 16 ? (e ^ f ^ g) : ((e & f) | (~e & g));
                int tt1 = ff + d + ss2 + w1[j];
                int tt2 = gg + h + ss1 + w[j];
                d = c;
                c = rotl(b, 9);
                b = a;
                a = tt1;
                h = g;
                g = rotl(f, 19);
                f = e;
                e = p0(tt2);
            }
            v[0] ^= a; v[1] ^= b; v[2] ^= c; v[3] ^= d;
            v[4] ^= e; v[5] ^= f; v[6] ^= g; v[7] ^= h;
        }

        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) (v[i] >>> 24);
            out[i * 4 + 1] = (byte) (v[i] >>> 16);
            out[i * 4 + 2] = (byte) (v[i] >>> 8);
            out[i * 4 + 3] = (byte) v[i];
        }
        return out;
    }

    private static int rotl(int x, int n) {
        int s = n & 31;
        return s == 0 ? x : (x << s) | (x >>> (32 - s));
    }

    private static int p0(int x) {
        return x ^ rotl(x, 9) ^ rotl(x, 17);
    }

    private static int p1(int x) {
        return x ^ rotl(x, 15) ^ rotl(x, 23);
    }
}
