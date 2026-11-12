package com.hex91.file;

import com.hex91.file.core.*;
/**
 * v1.89.0 OAuth 令牌（纯 Java，可单测）。
 *
 * <p>OneDrive 与阿里云盘都返回同一套 OAuth2 字段（{@code access_token} / {@code refresh_token} /
 * {@code expires_in} / {@code token_type}），差别只在错误码文字上，所以共用一个容器。</p>
 *
 * <p>三条容易写错的地方：</p>
 * <ol>
 *   <li><b>刷新响应可能不带 refresh_token</b>（微软经常这样：只换 access）。此时必须沿用旧的
 *       refresh_token，否则下一次刷新直接失效、用户被迫重新授权 —— {@link #renewedFrom} 处理这个；</li>
 *   <li><b>过期要提前判</b>：带着一个「还有 3 秒过期」的 token 去列 500 个文件的目录，
 *       中途过期就会半路 401，所以留 {@link #SKEW_MS} 的余量；</li>
 *   <li><b>错误响应用同一个端点返回 200/400</b>，body 里是 {@code error}，不能只看 HTTP 状态码。</li>
 * </ol>
 */
public final class OAuthToken {

    /** 提前 60 秒视为过期（一次列表请求可能要几十秒） */
    public static final long SKEW_MS = 60_000L;

    public final String access;
    public final String refresh;
    /** 过期时刻（epoch millis）；0 表示服务端没给 expires_in，只能等 401 再刷 */
    public final long expiresAt;
    public final String tokenType;

    public OAuthToken(String access, String refresh, long expiresAt, String tokenType) {
        this.access = access == null ? "" : access;
        this.refresh = refresh == null ? "" : refresh;
        this.expiresAt = expiresAt;
        this.tokenType = tokenType == null || tokenType.isEmpty() ? "Bearer" : tokenType;
    }

    /** 授权服务器返回的错误（含 code，设备码轮询要按 code 分支） */
    public static final class OAuthError extends RuntimeException {
        public final String code;

        public OAuthError(String code, String desc) {
            super(desc == null || desc.isEmpty() ? code : code + ": " + desc);
            this.code = code == null ? "" : code;
        }

        /** 设备码轮询：用户还没操作完，继续等 */
        public boolean pending() {
            return "authorization_pending".equals(code);
        }

        /** 设备码轮询：轮询太快，服务端要求降频 */
        public boolean slowDown() {
            return "slow_down".equals(code);
        }

        public boolean declined() {
            return "authorization_declined".equals(code) || "access_denied".equals(code);
        }

        public boolean expired() {
            return "expired_token".equals(code) || "code_expired".equals(code);
        }
    }

    public boolean hasAccess() {
        return !access.isEmpty();
    }

    public boolean hasRefresh() {
        return !refresh.isEmpty();
    }

    /** 是否已过期（含 {@link #SKEW_MS} 提前量） */
    public boolean expired(long nowMs) {
        return expired(nowMs, SKEW_MS);
    }

    public boolean expired(long nowMs, long skewMs) {
        if (expiresAt <= 0) return false;      // 不知道过期时间就不主动刷，等 401
        return nowMs + skewMs >= expiresAt;
    }

    /**
     * 解析令牌响应。
     *
     * @param nowMs 当前时刻（用于把 expires_in 换算成绝对时间；注入便于单测）
     * @throws OAuthError body 里带 {@code error}
     * @throws IllegalArgumentException 不是 JSON / 缺 access_token
     */
    public static OAuthToken parse(String json, long nowMs) {
        Object root = Json.parseOrNull(json);
        if (!(root instanceof java.util.Map))
            throw new IllegalArgumentException("授权服务器返回的不是 JSON："
                    + (json == null ? "null" : abbreviate(json)));

        // 先认 access_token：OAuth2 的错误响应不会同时带 access_token，
        // 但有些网关会把 error 字段塞进成功响应里，先认令牌比先认错误更不容易误判
        String access = Json.str(root, "access_token");
        if (access.isEmpty()) {
            String err = Json.str(root, "error");
            if (err.isEmpty()) err = Json.str(root, "code");     // 阿里云盘用 code
            if (!err.isEmpty()) {
                String desc = Json.str(root, "error_description");
                if (desc.isEmpty()) desc = Json.str(root, "message");
                throw new OAuthError(err, desc);
            }
            throw new IllegalArgumentException("授权响应里没有 access_token："
                    + (json == null ? "null" : abbreviate(json)));
        }

        long ttl = Json.num(root, "expires_in", 0);
        return new OAuthToken(access, Json.str(root, "refresh_token"),
                ttl > 0 ? nowMs + ttl * 1000L : 0, Json.str(root, "token_type"));
    }

    /**
     * 用刷新响应生成本次会话用的令牌：新响应缺 refresh_token 时沿用 {@code prev} 的。
     */
    public static OAuthToken renewedFrom(String json, long nowMs, OAuthToken prev) {
        OAuthToken t = parse(json, nowMs);
        if (t.hasRefresh() || prev == null || !prev.hasRefresh()) return t;
        return new OAuthToken(t.access, prev.refresh, t.expiresAt, t.tokenType);
    }

    /**
     * 从用户粘贴的内容里抠出 refresh_token。
     *
     * <p>用户从浏览器拿到的东西形态不定：可能是一整段 JSON（localStorage 里 {@code token} 键的值）、
     * 可能是带引号的字符串、也可能只复制了 token 本身。这里统一处理，
     * 免得用户「粘了整段 JSON 却被当成 token 用」而报 invalid_grant。</p>
     */
    public static String extractRefresh(String input) {
        if (input == null) return "";
        String s = input.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("{")) {
            Object root = Json.parseOrNull(s);
            String t = Json.str(root, "refresh_token");
            if (t.isEmpty()) t = Json.strPath(root, "token.refresh_token");
            return t.trim();
        }
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            Object v = Json.parseOrNull(s);
            if (v instanceof String) return ((String) v).trim();
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /** 打日志用：绝不输出完整 token */
    public String describe() {
        return "access=" + mask(access) + " refresh=" + (refresh.isEmpty() ? "-" : mask(refresh))
                + (expiresAt > 0 ? " exp=" + expiresAt : " exp=?");
    }

    private static String mask(String s) {
        if (s == null || s.isEmpty()) return "-";
        if (s.length() <= 8) return "***";
        return s.substring(0, 4) + "…" + s.substring(s.length() - 4) + "(" + s.length() + ")";
    }

    private static String abbreviate(String s) {
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 160 ? t : t.substring(0, 160) + "…";
    }
}
