package com.hex91.file.net;

import com.hex91.file.core.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;
import java.util.TreeMap;

/**
 * v1.89.0 OAuth2 客户端（OneDrive 设备码 / 阿里云盘 refresh_token）。
 *
 * <p>为什么 OneDrive 用<b>设备码</b>而不是授权码：授权码要 redirect_uri，
 * 而应用里没有可被外部回调的 scheme（就算注册了自定义 scheme，用户还得回桌面浏览器点跳转），
 * 更关键的是授权页要求用户在 WebView 里输账号密码 —— 那等于把微软账号交给一个文件管理器去中转。
 * 设备码让用户在<b>系统浏览器</b>里登录，App 只拿到一个一次性的 user_code，
 * 全程碰不到密码，也不用注册 redirect URI。</p>
 *
 * <p>参数细节（都是踩过的）：</p>
 * <ul>
 *   <li>设备码 scope 必须带 {@code offline_access}，否则拿不到 refresh_token，
 *       一小时后就要用户重新授权；</li>
 *   <li>轮询期间服务端返回 HTTP 400 + {@code authorization_pending}，
 *       这不是错误，是「用户还没点完」，必须继续等；</li>
 *   <li>返回 {@code slow_down} 时要把轮询间隔 +5 秒，否则会被持续拒绝；</li>
 *   <li>阿里云盘的 token 端点要 JSON body，微软要 form body，两者不能混。</li>
 * </ul>
 */
public final class OAuthClient {

    /** 令牌更新回调（用来把轮换后的 refresh_token 写回配置） */
    public interface TokenSink {
        void onToken(OAuthToken t);
    }

    public static final String MS_DEVICECODE = "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode";
    public static final String MS_TOKEN = "https://login.microsoftonline.com/common/oauth2/v2.0/token";
    public static final String MS_SCOPE = "Files.ReadWrite.All offline_access";

    public static final String ALIYUN_TOKEN = "https://auth.aliyundrive.com/v2/account/token";

    private OAuthClient() {}

    /** 设备码授权响应 */
    public static final class DeviceCode {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUri;
        public final String message;
        public final int intervalSec;
        public final int expiresInSec;

        DeviceCode(String deviceCode, String userCode, String verificationUri,
                   String message, int intervalSec, int expiresInSec) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.message = message;
            this.intervalSec = intervalSec <= 0 ? 5 : intervalSec;
            this.expiresInSec = expiresInSec <= 0 ? 900 : expiresInSec;
        }

        /** 给用户看的文案（服务端 message 已是本地化的，优先用它） */
        public String instruction() {
            if (message != null && !message.trim().isEmpty()) return message.trim();
            return "请在浏览器打开 " + verificationUri + " 并输入代码 " + userCode;
        }
    }

    // ---------------------------------------------------------------- 设备码

    /** 申请设备码（这一步不需要用户已登录） */
    public static DeviceCode requestDeviceCode(String clientId) throws IOException {
        if (isBlank(clientId)) throw new IOException("Client ID 不能为空（需先在 Azure 注册应用）");
        String body = "client_id=" + enc(clientId) + "&scope=" + enc(MS_SCOPE);
        Object root = Json.parseOrNull(post(MS_DEVICECODE, FORM, body));
        String dc = Json.str(root, "device_code");
        String uc = Json.str(root, "user_code");
        if (dc.isEmpty() || uc.isEmpty()) {
            throw new IOException("申请设备码失败：" + errorText(root, body));
        }
        return new DeviceCode(dc, uc, Json.str(root, "verification_uri"),
                Json.str(root, "message"),
                (int) Json.num(root, "interval", 5),
                (int) Json.num(root, "expires_in", 900));
    }

    /**
     * 轮询一次令牌端点。
     *
     * @throws OAuthToken.OAuthError {@code authorization_pending} / {@code slow_down} 属正常中间态，
     *                               调用方按 {@link OAuthToken.OAuthError#pending()} 分支继续轮询
     */
    public static OAuthToken pollDeviceCode(String clientId, String deviceCode) throws IOException {
        String body = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
                + "&client_id=" + enc(clientId)
                + "&device_code=" + enc(deviceCode);
        return OAuthToken.parse(post(MS_TOKEN, FORM, body), System.currentTimeMillis());
    }

    /** 刷新令牌（微软） */
    public static OAuthToken refreshMs(String clientId, String refreshToken) throws IOException {
        String body = "grant_type=refresh_token&client_id=" + enc(clientId)
                + "&refresh_token=" + enc(refreshToken) + "&scope=" + enc(MS_SCOPE);
        return OAuthToken.parse(post(MS_TOKEN, FORM, body), System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- 阿里云盘

    /**
     * 用 refresh_token 换 access_token。
     *
     * <p>阿里云盘会<b>轮换</b> refresh_token：响应里的新值必须落盘，
     * 否则下次刷新失败（旧 token 用一次就废）。</p>
     */
    public static OAuthToken refreshAliyun(String refreshToken) throws IOException {
        if (isBlank(refreshToken)) throw new IOException("refresh_token 不能为空");
        String body = "{\"grant_type\":\"refresh_token\",\"refresh_token\":"
                + quote(refreshToken) + "}";
        return OAuthToken.parse(post(ALIYUN_TOKEN, JSON, body), System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- HTTP

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String JSON = "application/json";

    /** POST 并返回响应体；HTTP 4xx/5xx 时若 body 是 OAuth 错误则抛 OAuthError */
    public static String post(String url, String contentType, String body) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", contentType + "; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setDoOutput(true);
            byte[] bytes = utf8(body);
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(bytes);
            } finally {
                try { os.close(); } catch (IOException ignore) {}
            }
            int code = c.getResponseCode();
            String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                Object root = Json.parseOrNull(resp);
                String err = Json.str(root, "error");
                if (err.isEmpty()) err = Json.str(root, "code");
                if (!err.isEmpty()) {
                    String desc = Json.str(root, "error_description");
                    if (desc.isEmpty()) desc = Json.str(root, "message");
                    throw new OAuthToken.OAuthError(err, desc);
                }
                throw new IOException("授权服务器 HTTP " + code + "：" + abbreviate(resp));
            }
            return resp;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignore) {}
        }
        return new String(bos.toByteArray(), utf8());
    }

    private static java.nio.charset.Charset utf8() {
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    private static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(utf8());
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s == null ? "" : s;
        }
    }

    /** JSON 字符串字面量（只处理必须转义的字符） */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String errorText(Object root, String fallback) {
        String err = Json.strPath(root, "error");
        String desc = Json.strPath(root, "error_description");
        if (err.isEmpty() && desc.isEmpty()) return abbreviate(fallback);
        return (err.isEmpty() ? "" : err + ": ") + desc;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }

    /** 供上层做「表单 → 字符串」的便捷拼接（保持参数顺序，便于日志比对） */
    public static String form(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<String, String>(params).entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }
}
