package com.hex91.file.net;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * v1.88.0 AWS Signature V4 签名（纯 Java，可在桌面单测）。
 *
 * <p>对象存储这一层只认签名，不认「账号」：阿里云 OSS、腾讯云 COS、MinIO、Backblaze B2、
 * 七牛、AWS S3 全是同一套 SigV4，用户把控制台里的 AccessKey/SecretKey 填进来就能用。
 * 相比 OneDrive / 阿里云盘的 OAuth，这里不需要注册开发者应用、不需要跳浏览器授权，
 * 也就没有「应用审核没过 → 功能白做」的风险。</p>
 *
 * <p>实现严格按官方文档的四段式：CanonicalRequest → StringToSign → SigningKey → Signature。
 * 易错的三个点都在这里钉死了：</p>
 * <ul>
 *   <li>URI 编码用**大写**十六进制，且 {@code /} 在路径里不编码、在 query 里要编码；</li>
 *   <li>CanonicalHeaders 每行以 {@code \n} 结尾，之后还要再来一个 {@code \n} 空行；</li>
 *   <li>query 必须按参数名字典序排列，同名参数再按值排序。</li>
 * </ul>
 */
public final class S3Signer {

    public static final String ALGO = "AWS4-HMAC-SHA256";
    /** 空 body 的 SHA-256（GET/DELETE 用） */
    public static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    /** 不校验 body 的占位值：仅 HTTPS 下允许，省掉大文件上传前的整份哈希 */
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private S3Signer() {}

    // ----------------------------------------------------------------
    // 基础编码 / 摘要
    // ----------------------------------------------------------------

    public static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = HEX[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[b[i] & 0xF];
        }
        return new String(out);
    }

    public static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    /**
     * SigV4 的 URI 编码：只放行 {@code A-Z a-z 0-9 - . _ ~}，
     * 其余一律 {@code %XX}（大写），{@code /} 按 {@code encodeSlash} 决定编不编。
     */
    public static String uriEncode(String s, boolean encodeSlash) {
        if (s == null) return "";
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length + 16);
        for (byte value : bytes) {
            int c = value & 0xFF;
            boolean plain = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (plain) sb.append((char) c);
            else if (c == '/' && !encodeSlash) sb.append('/');
            else sb.append('%').append(Character.toUpperCase(HEX[(c >> 4) & 0xF]))
                    .append(Character.toUpperCase(HEX[c & 0xF]));
        }
        return sb.toString();
    }

    /** 对象的 key 编码成 URI 路径：逐段编码、保留 `/` */
    public static String encodePath(String key) {
        String k = key == null ? "" : key;
        while (k.startsWith("/")) k = k.substring(1);
        return uriEncode(k, false);
    }

    // ----------------------------------------------------------------
    // 时间
    // ----------------------------------------------------------------

    /** 20130524T000000Z（UTC） */
    public static String amzDate(long epochMs) {
        return fmt("yyyyMMdd'T'HHmmss'Z'", epochMs);
    }

    /** 20130524（UTC） */
    public static String dateStamp(long epochMs) {
        return fmt("yyyyMMdd", epochMs);
    }

    private static String fmt(String pattern, long epochMs) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(epochMs));
    }

    // ----------------------------------------------------------------
    // 四段式
    // ----------------------------------------------------------------

    /**
     * CanonicalRequest。
     *
     * @param canonicalUri    已编码的路径（以 / 开头）
     * @param canonicalQuery  已编码且已排序的查询串（无参数传空串）
     * @param headers         小写头名 → 已 trim 的值（必须包含 host、x-amz-date、
     *                        x-amz-content-sha256）
     * @param signedHeaders   分号连接的头名（小写、字典序）
     * @param payloadHash     body 的 SHA-256 十六进制，或 {@link #UNSIGNED_PAYLOAD}
     */
    public static String canonicalRequest(String method, String canonicalUri, String canonicalQuery,
                                          Map<String, String> headers, String signedHeaders,
                                          String payloadHash) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(method).append('\n')
          .append(canonicalUri == null ? "/" : canonicalUri).append('\n')
          .append(canonicalQuery == null ? "" : canonicalQuery).append('\n');
        for (Map.Entry<String, String> e : normalized(headers).entrySet()) {
            sb.append(e.getKey()).append(':').append(e.getValue()).append('\n');
        }
        sb.append('\n')                       // CanonicalHeaders 之后必须有这一空行
          .append(signedHeaders).append('\n')
          .append(payloadHash);
        return sb.toString();
    }

    /** StringToSign = 算法 + 时间 + scope + CanonicalRequest 的哈希 */
    public static String stringToSign(String amzDate, String scope, String canonicalRequest) {
        return ALGO + "\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(utf8(canonicalRequest));
    }

    /** scope 形如 {@code 20130524/us-east-1/s3/aws4_request} */
    public static String scope(String dateStamp, String region, String service) {
        return dateStamp + "/" + region + "/" + service + "/aws4_request";
    }

    /** SigningKey：HKDF 风格的四级 HMAC（日期 → 区域 → 服务 → aws4_request） */
    public static byte[] signingKey(String secretKey, String dateStamp, String region, String service) {
        byte[] kDate = hmacSha256(utf8("AWS4" + secretKey), utf8(dateStamp));
        byte[] kRegion = hmacSha256(kDate, utf8(region));
        byte[] kService = hmacSha256(kRegion, utf8(service));
        return hmacSha256(kService, utf8("aws4_request"));
    }

    /** 计算签名（十六进制）。 */
    public static String signature(String secretKey, String dateStamp, String region, String service,
                                   String stringToSign) {
        return hex(hmacSha256(signingKey(secretKey, dateStamp, region, service),
                utf8(stringToSign)));
    }

    /** 生成 Authorization 头。headers 里须已包含 host / x-amz-date / x-amz-content-sha256。 */
    public static String authorization(String accessKey, String secretKey, String region, String service,
                                       String method, String canonicalUri, String canonicalQuery,
                                       Map<String, String> headers, String payloadHash, long epochMs) {
        String signedHeaders = joinSortedKeys(headers);
        String cr = canonicalRequest(method, canonicalUri, canonicalQuery, headers, signedHeaders, payloadHash);
        String scope = scope(dateStamp(epochMs), region, service);
        String sts = stringToSign(amzDate(epochMs), scope, cr);
        String sig = signature(secretKey, dateStamp(epochMs), region, service, sts);
        return ALGO + " Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + sig;
    }

    /** 参与签名的头名（小写、字典序、分号连接） */
    public static String joinSortedKeys(Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        for (String k : normalized(headers).keySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(k);
        }
        return sb.toString();
    }

    /**
     * 签名用的头集合：先统一小写 + 折叠值内空白，再按字典序排。
     *
     * <p>SigV4 要求「头名小写后」参与排序。v1.95.3 及以前直接对原 map 建 TreeMap，
     * 调用方传 {@code X-Amz-Date} 时按大写排（'X' &lt; 'h'），签出来是
     * {@code x-amz-date;host}，而服务端算的是 {@code host;x-amz-date} —— 必然 403。
     * 头值也要 trim 后把连续空格折成一个，否则多行/带缩进的头会签不过。</p>
     */
    private static Map<String, String> normalized(Map<String, String> headers) {
        Map<String, String> m = new TreeMap<String, String>();
        if (headers == null) return m;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            String v = e.getValue() == null ? "" : e.getValue().trim().replaceAll(" +", " ");
            m.put(k.toLowerCase(Locale.US), v);
        }
        return m;
    }

    /** 文本的 UTF-8 字节 */
    private static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
