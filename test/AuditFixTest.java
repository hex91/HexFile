import com.hex91.file.CodecCore;
import com.hex91.file.CryptoCore;
import com.hex91.file.HlsCrypto;
import com.hex91.file.HlsDownloader;
import com.hex91.file.HlsPlaylist;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * v1.95.4：一轮代码审查里「能写成断言」的修复项。
 *
 * <p>覆盖：AES 的 hex: IV、明文 fMP4 分片不被误解密、emoji 域名的 punycode 往返、
 * 续传指纹对清单变化敏感。并发那两个（闸门提前归零 / 失败后无限等）在
 * {@code HlsDownloaderTest} 里用本地 HTTP 服务复现。</p>
 */
public class AuditFixTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eqs(String expect, String got, String msg) {
        if (expect == null ? got == null : expect.equals(got)) {
            pass++; System.out.println("PASS  " + msg + " = " + got);
        } else {
            fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got);
        }
    }

    public static void main(String[] args) throws Exception {
        hexIv();
        plainFmp4();
        punyEmoji();
        fingerprintSensitive();

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /** P2-1：IV 必须和密钥同一套规则支持 hex:，且结果要与标准 JCE 一致 */
    static void hexIv() throws Exception {
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        for (int i = 0; i < 16; i++) { key[i] = (byte) i; iv[i] = (byte) i; }

        // 0x00..0x0F 这 16 个字符的 UTF-8 就是 16 个字节，可当「原始 IV」用
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append((char) i);

        String keyHex = "hex:" + hexOf(key);
        String ivHex = "hex:" + hexOf(iv);
        String plain = "0123456789abcdef";

        String byHex = CryptoCore.run(CryptoCore.A_AES, true, plain, keyHex, ivHex,
                CryptoCore.MODE_CBC, CryptoCore.OUT_HEX);
        String byText = CryptoCore.run(CryptoCore.A_AES, true, plain, keyHex, sb.toString(),
                CryptoCore.MODE_CBC, CryptoCore.OUT_HEX);
        eqs(byHex, byText, "IV 的 hex: 与等字节文本等价");

        // 与标准 JCE 对一遍，防止「两边一起错」
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.IvParameterSpec(iv));
        String expect = hexOf(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        eqs(expect, byHex, "hex: IV 的密文与 JCE 一致");
    }

    /** P1-2：明文 fMP4 分片（styp 开头、长度是 16 的倍数）不能被当成密文 */
    static void plainFmp4() throws Exception {
        byte[] seg = new byte[32];
        seg[3] = 0x08;                                  // box size = 8
        seg[4] = 's'; seg[5] = 't'; seg[6] = 'y'; seg[7] = 'p';
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        for (int i = 0; i < 16; i++) key[i] = (byte) (i * 7 + 1);

        byte[] out = HlsCrypto.decrypt(seg, key, iv);
        ok(Arrays.equals(seg, out), "明文 fMP4 分片原样放行（不被误解密）");

        // TS 明文同样要放行（原有行为，防止回归）
        byte[] ts = new byte[188 * 2 + 1];
        ts[0] = 0x47; ts[188] = 0x47; ts[376] = 0x47;
        ok(Arrays.equals(ts, HlsCrypto.decrypt(ts, key, iv)), "明文 TS 分片仍然原样放行");
    }

    /** P2-2：BMP 外字符（emoji）与普通汉字的 punycode 往返 */
    static void punyEmoji() {
        String[] cases = {"😀😀.com", "😀a😀.com", "例子.测试", "a😀b😀c.cn", "普通域名.com"};
        for (String s : cases) {
            String enc = CodecCore.punyEncodeDomain(s);
            String dec = CodecCore.punyDecodeDomain(enc);
            eqs(s, dec, "punycode 往返 " + s + " → " + enc);
        }
    }

    /** P2-3：指纹必须对「分片数 / 首末片地址」敏感，否则会拼进上一次的片子 */
    static void fingerprintSensitive() {
        String head = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n";
        HlsPlaylist p1 = HlsPlaylist.parse(head + "#EXTINF:4,\nseg0.ts\n#EXTINF:4,\nseg1.ts\n",
                "https://h/v.m3u8");
        HlsPlaylist p2 = HlsPlaylist.parse(head + "#EXTINF:4,\nseg0.ts\n#EXTINF:4,\nseg1.ts\n"
                + "#EXTINF:4,\nseg2.ts\n", "https://h/v.m3u8");
        HlsPlaylist p3 = HlsPlaylist.parse(head + "#EXTINF:4,\nseg0.ts\n#EXTINF:4,\nseg9.ts\n",
                "https://h/v.m3u8");

        String f1 = HlsDownloader.fingerprint("https://h/v.m3u8", p1.segments());
        String f1b = HlsDownloader.fingerprint("https://h/v.m3u8", p1.segments());
        String f2 = HlsDownloader.fingerprint("https://h/v.m3u8", p2.segments());
        String f3 = HlsDownloader.fingerprint("https://h/v.m3u8", p3.segments());

        eqs(f1, f1b, "同一份清单 → 指纹稳定");
        ok(!f1.equals(f2), "分片数变化 → 指纹不同");
        ok(!f1.equals(f3), "末片地址变化 → 指纹不同");
        ok(p1.segments().size() > 0, "清单解析出分片（前置条件）");
    }

    static String hexOf(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));   // CryptoCore 输出大写
        return sb.toString();
    }
}
