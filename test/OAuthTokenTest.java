import com.hex91.file.OAuthToken;

/**
 * v1.89.0 OAuth 令牌解析与刷新语义。
 *
 * <p>重点钉住两件事：<b>刷新响应不带 refresh_token 时必须沿用旧的</b>（否则用户第二天就被踢下线），
 * 以及<b>过期判断要留提前量</b>（列大目录中途过期会变成半路 401）。</p>
 */
public class OAuthTokenTest {

    static int pass = 0, fail = 0;
    static final long T0 = 1_700_000_000_000L;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eql(long expect, long got, String msg) {
        if (expect == got) { pass++; System.out.println("PASS  " + msg + " = " + got); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got); }
    }

    static void eqs(String expect, String got, String msg) {
        if (expect == null ? got == null : expect.equals(got)) {
            pass++; System.out.println("PASS  " + msg + " = " + got);
        } else {
            fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got);
        }
    }

    /** 期望抛出某类异常，返回异常本身（方便继续断言 code） */
    static Exception err(String json) {
        try {
            OAuthToken.parse(json, T0);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    public static void main(String[] args) {
        // ---------------------------------------------------------- 正常响应
        OAuthToken t = OAuthToken.parse("{\"access_token\":\"AT\",\"refresh_token\":\"RT\","
                + "\"expires_in\":3599,\"token_type\":\"Bearer\"}", T0);
        eqs("AT", t.access, "access_token");
        eqs("RT", t.refresh, "refresh_token");
        eql(T0 + 3_599_000L, t.expiresAt, "expires_in 秒 → 绝对过期时刻");
        eqs("Bearer", t.tokenType, "token_type");
        ok(t.hasAccess() && t.hasRefresh(), "hasAccess/hasRefresh");

        OAuthToken noType = OAuthToken.parse("{\"access_token\":\"AT\"}", T0);
        eqs("Bearer", noType.tokenType, "缺 token_type 默认 Bearer");
        eql(0, noType.expiresAt, "缺 expires_in → 过期时间未知（0）");
        ok(!noType.hasRefresh(), "缺 refresh_token");
        ok(!noType.expired(T0), "过期时间未知时不主动判过期（等 401）");

        // 阿里云盘同样字段名，也应能解析
        OAuthToken ali = OAuthToken.parse("{\"access_token\":\"a1\",\"refresh_token\":\"r1\","
                + "\"expires_in\":7200,\"token_type\":\"Bearer\"}", T0);
        eqs("a1", ali.access, "阿里云盘响应同构");
        eql(T0 + 7_200_000L, ali.expiresAt, "7200 秒");

        // ---------------------------------------------------------- 过期判断
        OAuthToken oneHour = OAuthToken.parse("{\"access_token\":\"AT\",\"expires_in\":3600}", T0);
        ok(!oneHour.expired(T0), "刚拿到不过期");
        ok(!oneHour.expired(T0 + 3_000_000L), "50 分钟后仍不过期");
        // 留 60 秒余量：剩 30 秒就该刷新了
        ok(oneHour.expired(T0 + 3_600_000L - 30_000L), "剩 30 秒视为过期（提前量 60s）");
        ok(!oneHour.expired(T0 + 3_600_000L - 90_000L), "剩 90 秒仍可用");
        ok(oneHour.expired(T0 + 3_600_001L), "超过绝对时刻必过期");
        ok(oneHour.expired(T0 + 10_000_000L, 0), "skew=0 时按绝对时刻判");

        // ---------------------------------------------------------- 错误响应
        Exception e = err("{\"error\":\"authorization_pending\",\"error_description\":\"等待用户\"}");
        ok(e instanceof OAuthToken.OAuthError, "error 字段 → OAuthError");
        OAuthToken.OAuthError oe = (OAuthToken.OAuthError) e;
        eqs("authorization_pending", oe.code, "错误码");
        ok(oe.pending(), "authorization_pending → pending()");
        ok(!oe.slowDown() && !oe.declined() && !oe.expired(), "pending 不误判成其他分支");
        ok(e.getMessage().contains("等待用户"), "错误信息带描述");

        ok(((OAuthToken.OAuthError) err("{\"error\":\"slow_down\"}")).slowDown(), "slow_down → slowDown()");
        ok(((OAuthToken.OAuthError) err("{\"error\":\"authorization_declined\"}")).declined(),
                "authorization_declined → declined()");
        ok(((OAuthToken.OAuthError) err("{\"error\":\"access_denied\"}")).declined(),
                "access_denied → declined()");
        ok(((OAuthToken.OAuthError) err("{\"error\":\"expired_token\"}")).expired(),
                "expired_token → expired()");
        ok(((OAuthToken.OAuthError) err("{\"error\":\"code_expired\"}")).expired(),
                "code_expired → expired()");

        // 两者同时出现时以 access_token 为准（正常 OAuth2 不会这样，但网关会乱塞字段）
        OAuthToken both = OAuthToken.parse("{\"error\":\"invalid_grant\",\"access_token\":\"AT\"}", T0);
        eqs("AT", both.access, "error 与 access_token 同时存在时以 access_token 为准");

        // 阿里云盘的错误用 code/message 而不是 error/error_description
        Exception aliErr = err("{\"code\":\"AccessTokenInvalid\",\"message\":\"refresh_token 已失效\"}");
        ok(aliErr instanceof OAuthToken.OAuthError, "阿里云盘 code 字段 → OAuthError");
        eqs("AccessTokenInvalid", ((OAuthToken.OAuthError) aliErr).code, "阿里云盘错误码");
        ok(aliErr.getMessage().contains("已失效"), "阿里云盘 message 带进提示");

        // 非 JSON / 缺 access_token
        Exception html = err("<html>502 Bad Gateway</html>");
        ok(html instanceof IllegalArgumentException && !(html instanceof OAuthToken.OAuthError),
                "HTML 错误页 → IllegalArgumentException");
        ok(html.getMessage().contains("502"), "错误信息里带原文，便于定位代理/网关问题");
        ok(err("{\"token_type\":\"Bearer\"}") instanceof IllegalArgumentException, "缺 access_token 报错");
        ok(err("") instanceof IllegalArgumentException, "空响应报错");
        ok(err(null) instanceof IllegalArgumentException, "null 响应报错");

        // ---------------------------------------------------------- 刷新语义
        OAuthToken prev = new OAuthToken("old", "RT-KEEP", T0, "Bearer");
        // 微软常见：刷新只给新的 access + expires_in，不给 refresh_token
        OAuthToken r1 = OAuthToken.renewedFrom(
                "{\"access_token\":\"new\",\"expires_in\":3600}", T0, prev);
        eqs("new", r1.access, "刷新后 access 更新");
        eqs("RT-KEEP", r1.refresh, "响应缺 refresh_token → 沿用旧的（关键）");
        eql(T0 + 3_600_000L, r1.expiresAt, "刷新后过期时间重算");

        // 阿里云盘会轮换 refresh_token：必须用新的
        OAuthToken r2 = OAuthToken.renewedFrom(
                "{\"access_token\":\"a2\",\"refresh_token\":\"RT-NEW\",\"expires_in\":7200}", T0, prev);
        eqs("RT-NEW", r2.refresh, "响应带新 refresh_token → 用新的（轮换）");

        // 首次授权（没有 prev）时不 panic
        OAuthToken r3 = OAuthToken.renewedFrom("{\"access_token\":\"a3\"}", T0, null);
        eqs("a3", r3.access, "无旧令牌时正常返回");
        eqs("", r3.refresh, "无旧令牌且响应无 refresh → 空");

        // ---------------------------------------------------------- 粘贴内容解析
        eqs("RT", OAuthToken.extractRefresh("{\"access_token\":\"A\",\"refresh_token\":\"RT\"}"),
                "整段 JSON 里取 refresh_token");
        eqs("RT", OAuthToken.extractRefresh("  { \"refresh_token\" : \"RT\" }  "), "带空白的 JSON");
        eqs("RT", OAuthToken.extractRefresh("\"RT\""), "带双引号的裸 token");
        eqs("RT", OAuthToken.extractRefresh("RT"), "裸 token 原样");
        eqs("RT", OAuthToken.extractRefresh("  RT\n"), "裸 token 去空白");
        eqs("", OAuthToken.extractRefresh(""), "空串");
        eqs("", OAuthToken.extractRefresh(null), "null");
        eqs("", OAuthToken.extractRefresh("{\"access_token\":\"A\"}"), "JSON 里没有 refresh_token → 空");
        eqs("RT", OAuthToken.extractRefresh("{\"token\":{\"refresh_token\":\"RT\"}}"), "嵌套 token 字段兜底");

        // describe 不能泄露完整 token
        String d = new OAuthToken("abcdefghijklmnop", "qrstuvwxyz012345", T0, "Bearer").describe();
        ok(!d.contains("abcdefghijklmnop"), "describe 不输出完整 access_token");
        ok(!d.contains("qrstuvwxyz012345"), "describe 不输出完整 refresh_token");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
