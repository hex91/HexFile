import com.hex91.file.S3Signer;

import java.util.HashMap;
import java.util.Map;

/**
 * v1.88.0 对象存储 SigV4 签名。
 *
 * <p>签名错了不会「报个错就算了」，而是被服务端统一回 403 SignatureDoesNotMatch，
 * 用户只会看到「连不上」，所以这里用 AWS 官方示例（GET Object）做端到端比对：
 * 期望签名取自官方文档，任何一个换行、编码大小写、头顺序写错都会立刻对不上。</p>
 *
 * <p>另一条硬要求是时间必须按 UTC 格式化——手机时区是 UTC+8 时，
 * 用本地时区算出的 x-amz-date 会比服务端快 8 小时，服务端直接拒签。</p>
 */
public class S3SignerTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eqs(String expect, String got, String msg) {
        if (expect == null ? got == null : expect.equals(got)) {
            pass++; System.out.println("PASS  " + msg);
        } else {
            fail++; System.out.println("FAIL  " + msg + "\n      期望 " + expect + "\n      实际 " + got);
        }
    }

    // AWS 文档示例固定值
    static final String AK = "AKIAIOSFODNN7EXAMPLE";
    static final String SK = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    static final long T = 1369353600000L;   // 2013-05-24T00:00:00Z

    public static void main(String[] args) {
        // ---------------------------------------------------------- 摘要
        eqs("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                S3Signer.sha256Hex(new byte[0]), "空串 SHA-256");
        eqs(S3Signer.EMPTY_SHA256, S3Signer.sha256Hex(new byte[0]), "EMPTY_SHA256 常量一致");
        eqs("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                S3Signer.sha256Hex(utf8("abc")), "SHA-256(\"abc\") 标准向量");

        // RFC 4231 用例 2：HMAC-SHA256("Jefe", "what do ya want for nothing?")
        eqs("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                S3Signer.hex(S3Signer.hmacSha256(utf8("Jefe"), utf8("what do ya want for nothing?"))),
                "RFC 4231 HMAC-SHA256 向量");

        // ---------------------------------------------------------- 时间（必须 UTC）
        eqs("20130524T000000Z", S3Signer.amzDate(T), "amzDate 按 UTC 格式化");
        eqs("20130524", S3Signer.dateStamp(T), "dateStamp 按 UTC 格式化");
        eqs("20130524/us-east-1/s3/aws4_request",
                S3Signer.scope(S3Signer.dateStamp(T), "us-east-1", "s3"), "scope 拼接");

        // ---------------------------------------------------------- URI 编码
        eqs("abc-._~", S3Signer.uriEncode("abc-._~", false), "保留未保留字符中的 - . _ ~");
        eqs("a%20b", S3Signer.uriEncode("a b", false), "空格编码为 %20（不是 +）");
        eqs("%2F", S3Signer.uriEncode("/", true), "encodeSlash=true 时 / 被编码");
        eqs("/", S3Signer.uriEncode("/", false), "encodeSlash=false 时 / 保留");
        eqs("%E6%96%87%E4%BB%B6.txt", S3Signer.uriEncode("文件.txt", false), "中文按 UTF-8 大写十六进制");
        eqs("%2B%3D%26", S3Signer.uriEncode("+=&", false), "特殊字符编码且大写");
        eqs("a/b%20c/d.txt", S3Signer.uriEncode("a/b c/d.txt", false), "混合：路径符保留、空格编码");
        eqs("", S3Signer.uriEncode(null, false), "null 安全");
        eqs("dir/%E6%96%87%E4%BB%B6.txt", S3Signer.encodePath("/dir/文件.txt"), "encodePath 去前导斜杠并逐段编码");

        // ---------------------------------------------------------- CanonicalRequest 结构
        Map<String, String> h = new HashMap<String, String>();
        h.put("host", "examplebucket.s3.amazonaws.com");
        h.put("range", "bytes=0-9");
        h.put("x-amz-content-sha256", S3Signer.EMPTY_SHA256);
        h.put("x-amz-date", "20130524T000000Z");
        String signed = S3Signer.joinSortedKeys(h);
        eqs("host;range;x-amz-content-sha256;x-amz-date", signed, "SignedHeaders 字典序");

        String cr = S3Signer.canonicalRequest("GET", "/test.txt", "", h, signed, S3Signer.EMPTY_SHA256);
        eqs("7344ae5b7ee6c3e7e6b0fe0640412a37625d1fbfff95c48bbb2dc43964946972",
                S3Signer.sha256Hex(utf8(cr)),
                "官方示例 CanonicalRequest 的哈希");
        ok(cr.startsWith("GET\n/test.txt\n\n"), "CanonicalRequest 前三行（空 query 也要占一行）");
        ok(cr.contains("host:examplebucket.s3.amazonaws.com\n"), "含 host 头");
        ok(cr.contains("\n\n" + signed + "\n"), "CanonicalHeaders 与 SignedHeaders 之间有空行");
        ok(cr.endsWith(S3Signer.EMPTY_SHA256), "末行为 payload 哈希");

        // 头值前后的空格必须被 trim（服务端签名会 trim）
        Map<String, String> h2 = new HashMap<String, String>();
        h2.put("host", "  examplebucket.s3.amazonaws.com  ");
        eqs("GET\n/\n\nhost:examplebucket.s3.amazonaws.com\n\nhost\n" + S3Signer.EMPTY_SHA256,
                S3Signer.canonicalRequest("GET", "/", "", h2, "host", S3Signer.EMPTY_SHA256),
                "头值 trim");

        // ---------------------------------------------------------- 签名密钥与最终签名
        eqs("dbb893acc010964918f1fd433add87c70e8b0db6be30c1fbeafefa5ec6ba8378",
                S3Signer.hex(S3Signer.signingKey(SK, "20130524", "us-east-1", "s3")),
                "SigningKey 四级 HMAC");

        String auth = S3Signer.authorization(AK, SK, "us-east-1", "s3",
                "GET", "/test.txt", "", h, S3Signer.EMPTY_SHA256, T);
        eqs("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request"
                        + ", SignedHeaders=host;range;x-amz-content-sha256;x-amz-date"
                        + ", Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
                auth, "官方示例 GET Object 的 Authorization 头");

        // 换时间 → 签名必须变（防止把时间漏进 StringToSign）
        String auth2 = S3Signer.authorization(AK, SK, "us-east-1", "s3",
                "GET", "/test.txt", "", h, S3Signer.EMPTY_SHA256, T + 3600_000L);
        ok(!auth.equals(auth2), "时间变了签名必须变");

        // 换密钥 → 签名必须变
        String auth3 = S3Signer.authorization("AKIAOTHEREXAMPLE00", SK, "us-east-1", "s3",
                "GET", "/test.txt", "", h, S3Signer.EMPTY_SHA256, T);
        ok(!auth.equals(auth3), "AccessKey 变了 Authorization 也要变");

        // 空 query 与带 query 的签名不同
        Map<String, String> h3 = new HashMap<String, String>();
        h3.put("host", "examplebucket.s3.amazonaws.com");
        h3.put("x-amz-content-sha256", S3Signer.EMPTY_SHA256);
        h3.put("x-amz-date", "20130524T000000Z");
        String a4 = S3Signer.authorization(AK, SK, "us-east-1", "s3", "GET", "/", "list-type=2&prefix=a%2F",
                h3, S3Signer.EMPTY_SHA256, T);
        String a5 = S3Signer.authorization(AK, SK, "us-east-1", "s3", "GET", "/", "prefix=a%2F&list-type=2",
                h3, S3Signer.EMPTY_SHA256, T);
        ok(!a4.equals(a5), "query 顺序不同 → 签名不同（调用方必须先排序，不能靠这里兜）");

        // 区域不同 → 签名不同
        String a6 = S3Signer.authorization(AK, SK, "cn-hangzhou", "s3", "GET", "/test.txt", "",
                h, S3Signer.EMPTY_SHA256, T);
        ok(!auth.equals(a6), "region 参与签名");

        // ---------------------------------------------------------- v1.95.4 回归：头名先小写再排序
        // 以前拿原 map 建 TreeMap，'X' (88) < 'h' (104) → x-amz-date 排到 host 前面，
        // 与服务端算出的 SignedHeaders 不同 → 必然 403。S3Fs 自己传的都是小写所以没暴露。
        Map<String, String> hUp = new HashMap<String, String>();
        hUp.put("X-Amz-Date", "20130524T000000Z");
        hUp.put("host", "examplebucket.s3.amazonaws.com");
        hUp.put("X-Amz-Content-SHA256", S3Signer.EMPTY_SHA256);
        eqs("host;x-amz-content-sha256;x-amz-date", S3Signer.joinSortedKeys(hUp),
                "大写头名先小写再排序");

        // 头值里的连续空格与首尾空白都要折叠（SigV4 CanonicalHeaders 规则）
        Map<String, String> hUpVal = new HashMap<String, String>();
        hUpVal.put("Host", "  examplebucket.s3.amazonaws.com  ");
        eqs("GET\n/\n\nhost:examplebucket.s3.amazonaws.com\n\nhost\n" + S3Signer.EMPTY_SHA256,
                S3Signer.canonicalRequest("GET", "/", "", hUpVal, "host", S3Signer.EMPTY_SHA256),
                "大写头名 + 值 trim");
        Map<String, String> hFold = new HashMap<String, String>();
        hFold.put("x-amz-meta-note", "a    b");
        ok(S3Signer.canonicalRequest("GET", "/", "", hFold, "x-amz-meta-note", "")
                        .contains("x-amz-meta-note:a b\n"),
                "头值连续空格折叠成一个");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
