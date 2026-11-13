package com.hex91.file.net;

import com.hex91.file.core.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v1.90.0 从网页登录现场「捡」出 refresh_token。
 *
 * <p>背景：阿里云盘没有对个人开放的 OAuth 注册入口，但它的网页版登录成功后会把
 * token 写进本站 localStorage。于是可以在内嵌的 WebView 里让用户正常登录（短信/密码/扫码都行），
 * 再由页面脚本把 localStorage 里「含 refresh_token 的那一项」dump 出来交给我们。
 * 用户不用再开 F12、不用理解什么是 Local Storage。</p>
 *
 * <p><b>为什么提取逻辑要单独成一个类并且可单测</b>：这段代码一旦判断错，表现是
 * 「授权成功 → 下次打开失败」，而且错误信息完全对不上（token 格式合法，只是类型错了）。
 * 所以所有可疑分支都必须在桌面上钉住：</p>
 * <ul>
 *   <li>把 {@code access_token} 当成 {@code refresh_token} 存下去 —— 最常见的错。
 *       access_token 只有 2 小时，存它等于每次都让用户重新登录；</li>
 *   <li>localStorage 的值<b>是被二次编码的 JSON 字符串</b>（引号已转义成 {@code \"}），
 *       直接按普通 JSON 解析会抛异常，得先认出来；</li>
 *   <li>同一个域下可能残留多个登录项（换过账号、SDK 缓存），选错就登进了别的账号；</li>
 *   <li>值可能压根不是 JSON（老版本存的是裸字符串），只能靠文本扫描兜底。</li>
 * </ul>
 */
public final class TokenHarvest {

    /** 一次命中的结果 */
    public static final class Hit {
        /** refresh_token（一定非空，否则不会构造 Hit） */
        public final String refresh;
        /** access_token（可能为空：说明拿到的不是完整 token 对象） */
        public final String access;
        /** user_id（用于提示「登的是哪个账号」，可能为空） */
        public final String userId;
        /** 命中的 localStorage 键名（可能为空：来自纯文本扫描） */
        public final String key;

        Hit(String refresh, String access, String userId, String key) {
            this.refresh = refresh == null ? "" : refresh;
            this.access = access == null ? "" : access;
            this.userId = userId == null ? "" : userId;
            this.key = key == null ? "" : key;
        }

        /** 是否像是完整的登录态（refresh + access 同在） */
        public boolean complete() {
            return !refresh.isEmpty() && !access.isEmpty();
        }

        /** 给用户看的说明，避免「成功了但不知道成功在哪」 */
        public String describe() {
            StringBuilder sb = new StringBuilder("已获取登录凭证");
            if (!key.isEmpty()) sb.append("（来源 ").append(key).append("）");
            if (!userId.isEmpty()) sb.append("，账号 ").append(shorten(userId));
            if (!complete()) sb.append("；注意：没同时看到 access_token，若稍后连接失败请重新登录");
            return sb.toString();
        }

        private static String shorten(String s) {
            return s.length() <= 10 ? s : s.substring(0, 6) + "…" + s.substring(s.length() - 4);
        }

        @Override public String toString() {
            return "Hit{key=" + key + ", userId=" + userId + ", complete=" + complete() + "}";
        }
    }

    private TokenHarvest() {}

    /**
     * 从「localStorage 全量 dump」里提取。
     *
     * <p>dump 的格式是脚本拼出来的 JSON 对象：{@code {"token":"...","其他键":"..."}}。
     * 每个值可能是三形态之一：JSON 对象、二次编码的 JSON 字符串、裸文本。三种都要吃下。</p>
     *
     * @return 最佳候选；一个都找不到返回 {@code null}
     */
    public static Hit fromStorageDump(String dump) {
        if (dump == null) return null;
        String s = dump.trim();
        if (s.isEmpty()) return null;

        List<Hit> hits = new ArrayList<Hit>();

        Map<String, Object> map = Json.asObj(Json.parseOrNull(s));
        if (map != null && !map.isEmpty()) {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                Object v = e.getValue();
                if (!(v instanceof String)) continue;
                Hit h = hitFromValue((String) v, e.getKey());
                if (h != null) hits.add(h);
            }
        }

        // dump 本身不是合法 JSON（脚本被截断、转义层级不一样）：整段当文本扫
        if (hits.isEmpty()) {
            String r = scanRefresh(s);
            if (!r.isEmpty()) hits.add(new Hit(r, scanValue(s, "access_token"),
                    scanValue(s, "user_id"), ""));
        }
        if (hits.isEmpty()) return null;
        return best(hits);
    }

    /**
     * 从任意文本里扫出 refresh_token。
     *
     * <p>只认<b>键名</b> {@code refresh_token}，绝不认 {@code access_token} ——
     * 这条是刻意的：把 access_token 存成 refresh_token，症状是「刚授权完能用，两小时后失效」，
     * 排查起来要命。返回空串比返回错的好。</p>
     */
    public static String scanRefresh(String text) {
        return scanValue(text, "refresh_token");
    }

    /** 扫一个键的字符串值（键名按 JSON 字面量匹配，容忍已转义的引号） */
    public static String scanValue(String text, String field) {
        if (text == null || field == null || field.isEmpty()) return "";
        int from = 0;
        while (true) {
            int at = text.indexOf(field, from);
            if (at < 0) return "";
            from = at + field.length();
            // 必须是一个完整的键名（避免 refreshtoken_v2 之类误匹配）
            if (at > 0 && isKeyChar(text.charAt(at - 1))) continue;
            int after = from;
            while (after < text.length() && isKeyChar(text.charAt(after))) after++;
            int v = valueStart(text, after);
            if (v < 0) continue;
            String val = readValue(text, v);
            if (!val.isEmpty()) return val;
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 一个值（可能是 JSON、转义 JSON、裸文本）→ Hit */
    private static Hit hitFromValue(String raw, String key) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;

        Map<String, Object> o = Json.asObj(Json.parseOrNull(v));
        if (o != null) {
            String r = Json.str(o, "refresh_token");
            if (!r.isEmpty()) {
                return new Hit(r, Json.str(o, "access_token"),
                        Json.str(o, "user_id"), key);
            }
        }
        // 转义 JSON / 裸字符串：退回文本扫描
        String r = scanRefresh(v);
        if (r.isEmpty()) return null;
        return new Hit(r, scanValue(v, "access_token"), scanValue(v, "user_id"), key);
    }

    /**
     * 打分挑最佳。localStorage 里可能同时有多条（换过账号、旧 SDK 缓存），
     * 选错的后果是登进别人的账号，所以宁可偏向「最完整的那条」。
     */
    public static Hit best(List<Hit> hits) {
        Hit top = null;
        int topScore = Integer.MIN_VALUE;
        for (Hit h : hits) {
            int score = score(h);
            if (score > topScore) {
                top = h;
                topScore = score;
            }
        }
        return top;
    }

    public static int score(Hit h) {
        int s = 0;
        String k = h.key == null ? "" : h.key.toLowerCase();
        if (k.contains("token")) s += 100;      // 键名就叫 token / access_token
        if (k.contains("ali") || k.contains("drive")) s += 20;
        if (h.complete()) s += 50;              // 完整登录态，不是半截
        if (!h.userId.isEmpty()) s += 30;
        s += Math.min(20, h.refresh.length() / 20);   // 极短的值大概率是占位
        return s;
    }

    /** 跳过 `"`、`:`、空白，返回值的第一个字符下标；不是键值结构返回 -1 */
    private static int valueStart(String s, int i) {
        int p = i;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == ':' ) { p++; break; }
            if (c == '"' || c == '\'' || c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\\') { p++; continue; }
            return -1;          // 出现了别的字符，说明这不是「键:值」
        }
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n'
                    || c == '"' || c == '\'' || c == '\\') { p++; continue; }
            break;
        }
        return p < s.length() ? p : -1;
    }

    /**
     * 读一个值，到下一个引号或分隔符为止。
     *
     * <p>转义形态（{@code \"abc\"}）与普通形态（{@code "abc"}）都走这里：
     * 前导的 {@code \"} / {@code "} 已经被 {@link #valueStart} 跳过，读到 {@code "} 就结束。</p>
     */
    private static String readValue(String s, int i) {
        StringBuilder sb = new StringBuilder();
        for (int p = i; p < s.length(); p++) {
            char c = s.charAt(p);
            // 转义形态的结束引号（`abc\"`）：这个反斜杠属于引号，不属于值
            if (c == '\\' && p + 1 < s.length() && s.charAt(p + 1) == '"') break;
            if (c == '"' || c == '\'' || c == ',' || c == '}' || c == ']') break;
            if (c == '\\' && p + 1 < s.length()) {
                char n = s.charAt(p + 1);
                if (n == 'n' || n == 'r' || n == 't') { sb.append(' '); p++; continue; }
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    private static boolean isKeyChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }
}
