import com.hex91.file.CodecCore;
import com.hex91.file.CryptoCore;
import com.hex91.file.RadixCore;
import com.hex91.file.Sm3;
import com.hex91.file.Sm4;
import com.hex91.file.TimeCore;

import java.util.TimeZone;

/**
 * v1.70.0 工具箱核心测试：编码转换 / 加解密 / 进制 / 时间戳。
 * 纯 JVM 跑，不需要设备：./test/run_core.sh ToolboxCoreTest
 */
public class ToolboxCoreTest {

    static int pass = 0, fail = 0;

    static void eq(String what, Object expect, Object actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        if (ok) {
            pass++;
            System.out.println("  ok   " + what);
        } else {
            fail++;
            System.out.println("  FAIL " + what + "\n       expect: " + expect + "\n       actual: " + actual);
        }
    }

    static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  ok   " + what);
        } else {
            fail++;
            System.out.println("  FAIL " + what);
        }
    }

    static void throwsErr(String what, Runnable r) {
        try {
            r.run();
            fail++;
            System.out.println("  FAIL " + what + "（应当报错但没有）");
        } catch (IllegalArgumentException e) {
            pass++;
            System.out.println("  ok   " + what + " -> " + e.getMessage());
        } catch (Exception e) {
            fail++;
            System.out.println("  FAIL " + what + "（异常类型不对：" + e + "）");
        }
    }

    public static void main(String[] a) {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+8"));

        System.out.println("[1] 编码：HEX / BIN / DEC / 字节数组");
        String txt = "中文abc";
        String hex = CodecCore.convert(txt, CodecCore.F_TEXT, CodecCore.F_HEX);
        eq("文本→HEX", "E4 B8 AD E6 96 87 61 62 63", hex);
        eq("HEX→文本（往返）", txt, CodecCore.convert(hex, CodecCore.F_HEX, CodecCore.F_TEXT));
        eq("HEX 0x 前缀写法", txt, CodecCore.convert("0xE4 0xB8 0xAD 0xE6 0x96 0x87 0x61 0x62 0x63",
                CodecCore.F_HEX, CodecCore.F_TEXT));
        eq("HEX 逗号/\\x 混写", txt, CodecCore.convert("E4,B8,AD,\\xE6,96,87,61,62,63",
                CodecCore.F_HEX, CodecCore.F_TEXT));
        eq("HEX→BIN", "11100100 10111000 10101101", CodecCore.convert("E4B8AD",
                CodecCore.F_HEX, CodecCore.F_BIN).substring(0, 26));
        eq("BIN→HEX", "E4B8AD", CodecCore.convert("111001001011100010101101",
                CodecCore.F_BIN, CodecCore.F_HEX).replace(" ", ""));
        eq("HEX→DEC", "228 184 173", CodecCore.convert("E4B8AD", CodecCore.F_HEX, CodecCore.F_DEC));
        eq("DEC→文本", txt, CodecCore.convert("228 184 173 230 150 135 97 98 99",
                CodecCore.F_DEC, CodecCore.F_TEXT));
        eq("字节数组 0x 写法", "45 66 67", CodecCore.convert("0x45,0x66,0x67",
                CodecCore.F_BYTES, CodecCore.F_HEX));
        eq("字节数组 十进制写法", "48 69", CodecCore.convert("72,105",
                CodecCore.F_BYTES, CodecCore.F_HEX));
        eq("字节数组 含 a-f 的 token 按十六进制", "0A AB", CodecCore.convert("10,ab",
                CodecCore.F_BYTES, CodecCore.F_HEX));
        throwsErr("HEX 奇数位报错", new Runnable() {
            public void run() {
                CodecCore.toBytes("ABC", CodecCore.F_HEX);
            }
        });

        System.out.println("[2] 编码：Unicode / 转义 / JSON / HTML");
        eq("文本→Unicode", "\\u4E2D\\u6587\\u0061\\u0062\\u0063",
                CodecCore.convert(txt, CodecCore.F_TEXT, CodecCore.F_UNICODE));
        eq("Unicode→文本", txt, CodecCore.convert("\\u4E2D\\u6587abc",
                CodecCore.F_UNICODE, CodecCore.F_TEXT));
        eq("Unicode 小写/emoji", "\uD83D\uDE00", CodecCore.convert("\\uD83D\\uDE00",
                CodecCore.F_UNICODE, CodecCore.F_TEXT));
        eq("转义：换行制表", "a\\nb\\tc", CodecCore.convert("a\nb\tc",
                CodecCore.F_TEXT, CodecCore.F_ESCAPE));
        eq("转义：反解析", "a\nb\tc", CodecCore.convert("a\\nb\\tc",
                CodecCore.F_ESCAPE, CodecCore.F_TEXT));
        eq("转义：高位字节往返", "\\xE4\\xB8\\xAD", CodecCore.escapeBytes(new byte[]{(byte) 0xE4, (byte) 0xB8, (byte) 0xAD}));
        eq("转义：\\xNN 反解析", "45 66", CodecCore.convert("\\x45\\x66",
                CodecCore.F_ESCAPE, CodecCore.F_HEX));
        eq("转义：\\u4E2D 反解析", "中", CodecCore.convert("\\u4E2D",
                CodecCore.F_ESCAPE, CodecCore.F_TEXT));
        eq("JSON：转义", "\"a\\\"b\\\\c\\n\"", CodecCore.convert("a\"b\\c\n",
                CodecCore.F_TEXT, CodecCore.F_JSON));
        eq("JSON：反解析", "a\"b\\c\n", CodecCore.convert("\"a\\\"b\\\\c\\n\"",
                CodecCore.F_JSON, CodecCore.F_TEXT));
        eq("HTML：实体编码", "&lt;a&gt; &#20013;&#25991; &amp;", CodecCore.convert("<a> 中文 &",
                CodecCore.F_TEXT, CodecCore.F_HTML));
        eq("HTML：实体解码", "<a> 中文 &", CodecCore.convert("&lt;a&gt; &#20013;&#25991; &amp;",
                CodecCore.F_HTML, CodecCore.F_TEXT));
        eq("HTML：十六进制实体", "中", CodecCore.decodeHtml("&#x4E2D;"));

        System.out.println("[3] 编码：URL / Base64 / Base32 / 中文域名");
        eq("URL 编码", "%E4%B8%AD%E6%96%87%20a%2Bb", CodecCore.convert("中文 a+b",
                CodecCore.F_TEXT, CodecCore.F_URL));
        eq("URL 解码", "中文 a+b", CodecCore.convert("%E4%B8%AD%E6%96%87%20a%2Bb",
                CodecCore.F_URL, CodecCore.F_TEXT));
        eq("URL 解码 +(表单空格)", "a b", new String(CodecCore.percentDecode("a+b"),
                java.nio.charset.Charset.forName("UTF-8")));
        eq("Base64 编码", "aGVsbG8=", CodecCore.convert("hello", CodecCore.F_TEXT, CodecCore.F_BASE64));
        eq("Base64 解码", "hello", CodecCore.convert("aGVsbG8=", CodecCore.F_BASE64, CodecCore.F_TEXT));
        eq("Base64 无填充也能解", "hello", CodecCore.convert("aGVsbG8", CodecCore.F_BASE64, CodecCore.F_TEXT));
        eq("Base64 带换行也能解", "hello", CodecCore.convert("aGVs\nbG8=", CodecCore.F_BASE64, CodecCore.F_TEXT));
        eq("Base64URL 无填充", "-_8", CodecCore.base64Encode(new byte[]{(byte) 0xFB, (byte) 0xFF}, true, false));
        eq("Base64 中文往返", "中文", CodecCore.convert(CodecCore.convert("中文",
                CodecCore.F_TEXT, CodecCore.F_BASE64), CodecCore.F_BASE64, CodecCore.F_TEXT));
        eq("Base32 编码", "MFRGG===", CodecCore.base32Encode("abc".getBytes(
                java.nio.charset.Charset.forName("UTF-8"))));
        eq("Base32 解码", "abc", new String(CodecCore.base32Decode("MFRGG==="),
                java.nio.charset.Charset.forName("UTF-8")));
        eq("中文域名→Punycode", "xn--fiq228c.com", CodecCore.punyEncodeDomain("中文.com"));
        eq("中国→Punycode", "xn--fiqs8s", CodecCore.punyEncodeDomain("中国"));
        eq("Punycode→中文", "中文.com", CodecCore.punyDecodeDomain("xn--fiq228c.com"));
        eq("Punycode 带 www", "www.xn--fiqs8s.cn", CodecCore.punyEncodeDomain("www.中国.cn"));
        eq("全角句号也算分隔符", "xn--fiqs8s.cn", CodecCore.punyEncodeDomain("中国。cn"));
        eq("Punycode 解码往返", "测试.中国", CodecCore.punyDecodeDomain(
                CodecCore.punyEncodeDomain("测试.中国")));
        eq("URL 前缀被忽略", "xn--fiqs8s.cn", CodecCore.punyEncodeDomain("https://中国.cn/abc"));

        System.out.println("[4] 编码：任意格式互转（HEX→Base64→URL→文本）");
        String b64 = CodecCore.convert(hex, CodecCore.F_HEX, CodecCore.F_BASE64);
        eq("HEX→Base64", "5Lit5paHYWJj", b64);
        String url = CodecCore.convert(b64, CodecCore.F_BASE64, CodecCore.F_URL);
        eq("Base64→URL（对字节做百分号编码）", "%E4%B8%AD%E6%96%87abc", url);
        eq("URL→HEX 回到原点", hex, CodecCore.convert(url, CodecCore.F_URL, CodecCore.F_HEX));

        System.out.println("[5] 加解密：Base64 / 摘要");
        eq("Base64 编码(算法入口)", "aGVsbG8=", CryptoCore.run(CryptoCore.A_B64_ENC, true, "hello", "", "", 0, 0));
        eq("Base64 解码(算法入口)", "hello", CryptoCore.run(CryptoCore.A_B64_DEC, false, "aGVsbG8=", "", "", 0, 0));
        eq("MD5(abc)", "900150983cd24fb0d6963f7d28e17f72", CryptoCore.run(CryptoCore.A_MD5, true, "abc", "", "", 0, 0));
        eq("SHA-1(abc)", "a9993e364706816aba3e25717850c26c9cd0d89d", CryptoCore.run(CryptoCore.A_SHA1, true, "abc", "", "", 0, 0));
        eq("SHA-256(abc)", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CryptoCore.run(CryptoCore.A_SHA256, true, "abc", "", "", 0, 0));
        eq("SHA-512(abc)", "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                        + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
                CryptoCore.run(CryptoCore.A_SHA512, true, "abc", "", "", 0, 0));
        eq("CRC32(abc)", "352441c2", CryptoCore.run(CryptoCore.A_CRC32, true, "abc", "", "", 0, 0));
        eq("HMAC-SHA256", "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                CryptoCore.run(CryptoCore.A_HMAC256, true, "The quick brown fox jumps over the lazy dog",
                        "key", "", 0, 0));
        eq("HMAC-SM3 长度", 64, CryptoCore.run(CryptoCore.A_HMAC_SM3, true, "abc", "key", "", 0, 0).length());
        ok("HMAC-SM3 密钥不同结果不同", !CryptoCore.run(CryptoCore.A_HMAC_SM3, true, "abc", "k1", "", 0, 0)
                .equals(CryptoCore.run(CryptoCore.A_HMAC_SM3, true, "abc", "k2", "", 0, 0)));

        System.out.println("[6] 加解密：国密 SM3 / SM4");
        eq("SM3(abc)", "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
                CryptoCore.hex(Sm3.digest("abc".getBytes(java.nio.charset.Charset.forName("UTF-8")))));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append("abcd");
        eq("SM3(abcd×16)", "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732",
                CryptoCore.hex(Sm3.digest(sb.toString().getBytes(java.nio.charset.Charset.forName("UTF-8")))));
        eq("SM3(空)", "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
                CryptoCore.hex(Sm3.digest(new byte[0])));

        byte[] k = {0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
                (byte) 0xFE, (byte) 0xDC, (byte) 0xBA, (byte) 0x98, 0x76, 0x54, 0x32, 0x10};
        int[] rk = Sm4.expandKey(k);
        byte[] blk = new byte[16];
        System.arraycopy(k, 0, blk, 0, 16);
        byte[] ct = new byte[16];
        Sm4.encryptBlock(rk, blk, 0, ct, 0);
        eq("SM4 标准向量加密", "681edf34d206965e86b3e94f536e4246", CryptoCore.hex(ct));
        byte[] back = new byte[16];
        Sm4.decryptBlock(rk, ct, 0, back, 0);
        eq("SM4 标准向量解密", CryptoCore.hex(k), CryptoCore.hex(back));

        String sm4ct = CryptoCore.run(CryptoCore.A_SM4, true, "国密测试 SM4", "0123456789abcdef", "", CryptoCore.MODE_ECB, CryptoCore.OUT_HEX);
        eq("SM4 ECB 往返", "国密测试 SM4",
                CryptoCore.run(CryptoCore.A_SM4, false, sm4ct, "0123456789abcdef", "", CryptoCore.MODE_ECB, CryptoCore.OUT_HEX));
        String sm4cbc = CryptoCore.run(CryptoCore.A_SM4, true, "国密测试 SM4", "0123456789abcdef",
                "1234567890abcdef", CryptoCore.MODE_CBC, CryptoCore.OUT_B64);
        eq("SM4 CBC 往返", "国密测试 SM4", CryptoCore.run(CryptoCore.A_SM4, false, sm4cbc,
                "0123456789abcdef", "1234567890abcdef", CryptoCore.MODE_CBC, CryptoCore.OUT_B64));
        ok("SM4 CBC 的 IV 影响结果", !sm4cbc.equals(CryptoCore.run(CryptoCore.A_SM4, true, "国密测试 SM4",
                "0123456789abcdef", "fedcba0987654321", CryptoCore.MODE_CBC, CryptoCore.OUT_B64)));

        System.out.println("[7] 加解密：AES / DES / 3DES");
        String aes = CryptoCore.run(CryptoCore.A_AES, true, "hello 世界", "0123456789abcdef", "", CryptoCore.MODE_ECB, CryptoCore.OUT_HEX);
        eq("AES ECB 往返", "hello 世界", CryptoCore.run(CryptoCore.A_AES, false, aes,
                "0123456789abcdef", "", CryptoCore.MODE_ECB, CryptoCore.OUT_HEX));
        String aesCbc = CryptoCore.run(CryptoCore.A_AES, true, "hello 世界", "0123456789abcdef",
                "abcdefghijklmnop", CryptoCore.MODE_CBC, CryptoCore.OUT_B64);
        eq("AES CBC 往返", "hello 世界", CryptoCore.run(CryptoCore.A_AES, false, aesCbc,
                "0123456789abcdef", "abcdefghijklmnop", CryptoCore.MODE_CBC, CryptoCore.OUT_B64));
        eq("AES 短密钥自动补 0", true, CryptoCore.run(CryptoCore.A_AES, false,
                CryptoCore.run(CryptoCore.A_AES, true, "abc", "short", "", CryptoCore.MODE_ECB, CryptoCore.OUT_B64),
                "short", "", CryptoCore.MODE_ECB, CryptoCore.OUT_B64).equals("abc"));
        String des = CryptoCore.run(CryptoCore.A_DES, true, "des test", "12345678", "", CryptoCore.MODE_ECB, CryptoCore.OUT_B64);
        eq("DES ECB 往返", "des test", CryptoCore.run(CryptoCore.A_DES, false, des, "12345678", "", CryptoCore.MODE_ECB, CryptoCore.OUT_B64));
        String tdes = CryptoCore.run(CryptoCore.A_3DES, true, "3des test", "1234567890abcdef", "", CryptoCore.MODE_CBC, CryptoCore.OUT_HEX);
        eq("3DES 16 字节密钥往返", "3des test", CryptoCore.run(CryptoCore.A_3DES, false, tdes,
                "1234567890abcdef", "", CryptoCore.MODE_CBC, CryptoCore.OUT_HEX));
        throwsErr("AES 密钥过长报错", new Runnable() {
            public void run() {
                CryptoCore.run(CryptoCore.A_AES, true, "x", "0123456789012345678901234567890123", "", 0, 0);
            }
        });
        throwsErr("密钥错解密报错", new Runnable() {
            public void run() {
                CryptoCore.run(CryptoCore.A_AES, false, aes, "fedcba9876543210", "", CryptoCore.MODE_ECB, CryptoCore.OUT_HEX);
            }
        });
        eq("hex: 前缀密钥", "abc", CryptoCore.run(CryptoCore.A_AES, false,
                CryptoCore.run(CryptoCore.A_AES, true, "abc", "hex:00112233445566778899aabbccddeeff", "", 0, 0),
                "hex:00112233445566778899aabbccddeeff", "", 0, 0));
        String aesHex = CryptoCore.run(CryptoCore.A_AES, true, "abc", "0123456789abcdef", "", CryptoCore.MODE_ECB, CryptoCore.OUT_HEX);
        eq("密文自动识别 HEX（去空格）", "abc", CryptoCore.run(CryptoCore.A_AES, false,
                aesHex.replace(" ", ""), "0123456789abcdef", "", CryptoCore.MODE_ECB, CryptoCore.OUT_B64));
        eq("密文自动识别 Base64", "abc", CryptoCore.run(CryptoCore.A_AES, false,
                CryptoCore.run(CryptoCore.A_AES, true, "abc", "0123456789abcdef", "", CryptoCore.MODE_ECB, CryptoCore.OUT_B64),
                "0123456789abcdef", "", CryptoCore.MODE_ECB, CryptoCore.OUT_HEX));

        System.out.println("[8] 进制转换");
        RadixCore.Result r = RadixCore.convert("255", 10);
        eq("255→二进制", "11111111", r.values[0]);
        eq("255→八进制", "377", r.values[1]);
        eq("255→十进制", "255", r.values[2]);
        eq("255→十六进制", "FF", r.values[3]);
        eq("255→36 进制", "73", r.values[4]);
        eq("255 位宽", 8, r.bits);
        eq("255 HEX 字节", "FF", r.hexBytes);
        RadixCore.Result r2 = RadixCore.convert("0xFF", 10);
        eq("0x 前缀自动识别", "255", r2.values[2]);
        RadixCore.Result r3 = RadixCore.convert("-1", 10);
        eq("负数", "-1", r3.values[2]);
        eq("负数二进制", "-1", r3.values[0]);
        eq("二进制分组", "1111 1111", RadixCore.groupBits("11111111"));
        eq("分组不对齐", "1 0000 0000", RadixCore.groupBits("100000000"));
        java.math.BigInteger big = java.math.BigInteger.ONE.shiftLeft(100);
        eq("2^100 → 十六进制", "10000000000000000000000000", RadixCore.toBase(big, 16));
        eq("36 进制解析 Z=35", "35", RadixCore.toBase(RadixCore.parse("Z", 36), 10));
        eq("36 进制解析 ZZ", "1295", RadixCore.toBase(RadixCore.parse("ZZ", 36), 10));
        throwsErr("非法字符报错", new Runnable() {
            public void run() {
                RadixCore.convert("12G", 16);
            }
        });
        throwsErr("八进制 8 报错", new Runnable() {
            public void run() {
                RadixCore.convert("18", 8);
            }
        });

        System.out.println("[9] 时间戳");
        TimeCore.Stamp s10 = TimeCore.parseStamp("1700000000");
        eq("10 位按秒", 1700000000000L, s10.millis);
        eq("10 位单位", "秒", s10.unit);
        TimeCore.Stamp s13 = TimeCore.parseStamp("1700000000123");
        eq("13 位按毫秒", 1700000000123L, s13.millis);
        eq("13 位单位", "毫秒", s13.unit);
        eq("16 位按微秒", 1700000000123L, TimeCore.parseStamp("1700000000123456").millis);
        eq("本地时间格式化", "2023-11-15 06:13:20", TimeCore.fmt(1700000000000L, "yyyy-MM-dd HH:mm:ss", null));
        eq("UTC 格式化", "2023-11-14 22:13:20", TimeCore.fmt(1700000000000L, "yyyy-MM-dd HH:mm:ss", "UTC"));
        eq("星期", "周三", TimeCore.weekday(1700000000000L));
        eq("时间→时间戳", 1704081600000L, TimeCore.parseDateTime("2024-01-01 12:00:00"));
        eq("斜杠分隔", 1704081600000L, TimeCore.parseDateTime("2024/01/01 12:00:00"));
        eq("只要日期", 1704038400000L, TimeCore.parseDateTime("2024-01-01"));
        eq("ISO 带 Z", 1704081600000L, TimeCore.parseDateTime("2024-01-01T12:00:00+08:00"));
        eq("紧凑写法", 1704081600000L, TimeCore.parseDateTime("20240101120000"));
        eq("纯数字走时间戳", 1700000000000L, TimeCore.parseDateTime("1700000000"));
        eq("带毫秒", 1704081600123L, TimeCore.parseDateTime("2024-01-01 12:00:00.123"));
        eq("点分日期", 1704081600000L, TimeCore.parseDateTime("2024.01.01 12:00:00"));
        eq("ISO 带 Z", 1704067200000L, TimeCore.parseDateTime("2024-01-01T00:00:00Z"));
        ok("相对时间含「前」", TimeCore.relative(System.currentTimeMillis() - 90_000L).endsWith("前"));
        ok("未来时间含「后」", TimeCore.relative(System.currentTimeMillis() + 90_000L).endsWith("后"));
        throwsErr("乱写时间报错", new Runnable() {
            public void run() {
                TimeCore.parseDateTime("明年这个时候");
            }
        });
        throwsErr("乱写时间戳报错", new Runnable() {
            public void run() {
                TimeCore.parseStamp("12a4");
            }
        });

        System.out.println();
        System.out.println("RESULT  pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
