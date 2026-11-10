import com.hex91.file.TokenHarvest;

/**
 * v1.90.0 从网页登录现场提取 refresh_token。
 *
 * <p>这段逻辑一旦判断错，用户体验是「授权成功 → 下次打开失败」，而且报错信息完全对不上
 * （token 格式合法，只是类型/账号错了）。所以下面每一条错误分支都要钉住，
 * 尤其是<b>绝不能把 access_token 当成 refresh_token</b>。</p>
 */
public class TokenHarvestTest {

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

    /** 阿里云盘 localStorage 的真实形态：值是一段被二次编码的 JSON */
    static final String REAL = "{\"token\":\"{\\\"access_token\\\":\\\"AT-1\\\","
            + "\\\"refresh_token\\\":\\\"RT-1\\\",\\\"user_id\\\":\\\"u-9527\\\","
            + "\\\"expires_in\\\":7200}\",\"theme\":\"light\"}";

    public static void main(String[] args) {
        // ---------------------------------------------------------- 真实形态
        TokenHarvest.Hit h = TokenHarvest.fromStorageDump(REAL);
        ok(h != null, "真实 dump 能提取到");
        if (h != null) {
            eqs("RT-1", h.refresh, "取到 refresh_token（不是 access_token）");
            eqs("AT-1", h.access, "同时带出 access_token");
            eqs("u-9527", h.userId, "带出 user_id");
            eqs("token", h.key, "记下来源键名");
            ok(h.complete(), "refresh + access 齐全 → complete");
            ok(h.describe().contains("token"), "describe 说明来源");
            ok(!h.describe().contains("RT-1"), "describe 不泄露 token 本身");
        }

        // ---------------------------------------------------------- 值本身就是对象
        h = TokenHarvest.fromStorageDump(
                "{\"login\":{\"refresh_token\":\"RT-2\",\"access_token\":\"AT-2\"}}");
        ok(h != null && "RT-2".equals(h.refresh), "值是内嵌对象（不是字符串）也能取");

        // ---------------------------------------------------------- 值是裸 token
        h = TokenHarvest.fromStorageDump("{\"my_refresh\":\"RT-3\"}");
        ok(h == null, "键名不含 refresh_token 字面量、值也不是 JSON → 不瞎猜，返回 null");

        h = TokenHarvest.fromStorageDump("{\"token\":\"RT-4\"}");
        ok(h == null, "★ 键叫 token 但值是裸串 → 不猜（分不清是 access 还是 refresh，宁可不返回）");

        // ---------------------------------------------------------- 关键反例
        h = TokenHarvest.fromStorageDump(
                "{\"token\":\"{\\\"access_token\\\":\\\"AT-ONLY\\\",\\\"expires_in\\\":7200}\"}");
        ok(h == null, "★ 只有 access_token 时绝不返回它（否则两小时后必然失效）");
        eqs("", TokenHarvest.scanRefresh("{\"access_token\":\"AT-ONLY\"}"),
                "★ scanRefresh 同样不认 access_token");

        eqs("", TokenHarvest.scanRefresh(""), "空串 → 空");
        eqs("", TokenHarvest.scanRefresh(null), "null → 空");
        eqs("", TokenHarvest.scanRefresh("随便一段没有令牌的文字"), "无关键字段 → 空");
        ok(TokenHarvest.fromStorageDump("") == null, "空 dump → null");
        ok(TokenHarvest.fromStorageDump(null) == null, "null dump → null");
        ok(TokenHarvest.fromStorageDump("{}") == null, "空对象 → null");

        // ---------------------------------------------------------- 多候选
        // 换过账号：旧记录还留在 localStorage，新记录键名同样含 token。
        // 两个都完整，靠 user_id / 长度区分不了，但至少要稳定挑一个「完整的」，
        // 不能挑到只有 refresh 没有 access 的半截记录。
        String two = "{\"token\":\"{\\\"refresh_token\\\":\\\"RT-HALF\\\"}\","
                + "\"token_v2\":\"{\\\"access_token\\\":\\\"AT-NEW\\\",\\\"refresh_token\\\":\\\"RT-FULL\\\","
                + "\\\"user_id\\\":\\\"u-2\\\"}\"}";
        h = TokenHarvest.fromStorageDump(two);
        ok(h != null, "多候选时有结果");
        eqs("RT-FULL", h == null ? "" : h.refresh, "多候选优先选完整的那条（有 access + user_id）");

        // 只有一条含 token 键名，另一条键名无关：应当选键名相关的
        String mix = "{\"telemetry\":\"{\\\"refresh_token\\\":\\\"RT-WRONG\\\"}\","
                + "\"token\":\"{\\\"refresh_token\\\":\\\"RT-RIGHT\\\"}\"}";
        h = TokenHarvest.fromStorageDump(mix);
        eqs("RT-RIGHT", h == null ? "" : h.refresh, "键名叫 token 的优先于无关键名");

        // ---------------------------------------------------------- 转义/截断
        // dump 被截断（脚本拼字符串时长度超限）：不是合法 JSON，整段当文本扫
        String broken = "{\"token\":\"{\\\"access_token\\\":\\\"AT-B\\\",\\\"refresh_token\\\":\\\"RT-BROKEN\\\"";
        h = TokenHarvest.fromStorageDump(broken);
        ok(h != null, "★ dump 不是合法 JSON 时退回文本扫描（截断也能救回来）");
        eqs("RT-BROKEN", h == null ? "" : h.refresh, "截断场景取到正确的值");

        // 双引号完整但值里再套一层转义
        eqs("RT-ESC", TokenHarvest.scanRefresh("{\\\"refresh_token\\\":\\\"RT-ESC\\\"}"),
                "★ 值的引号处于转义态（\\\"）时仍能正确读到边界");
        eqs("RT-PLAIN", TokenHarvest.scanRefresh("{\"refresh_token\":\"RT-PLAIN\"}"),
                "普通引号形态");
        eqs("RT-SPACE", TokenHarvest.scanRefresh("{ \"refresh_token\" : \"RT-SPACE\" }"),
                "键值间有空格");
        eqs("RT-SQ", TokenHarvest.scanRefresh("{'refresh_token':'RT-SQ'}"), "单引号写法");

        // 不要在别的键名里误命中：refreshtoken_v2 / my_refresh_token_suffix
        eqs("", TokenHarvest.scanRefresh("{\"refreshtoken_v2\":\"X\"}"),
                "★ 长键名 refreshtoken_v2 不算 refresh_token");
        eqs("", TokenHarvest.scanRefresh("{\"my_refresh_token_suffix\":\"X\"}"),
                "★ 后缀型键名也不误命中");

        // 嵌套
        eqs("RT-NEST", TokenHarvest.scanRefresh("{\"token\":{\"refresh_token\":\"RT-NEST\"}}"),
                "嵌套在 token 对象里");

        // 空值不误当命中，继续往后找
        eqs("RT-LATER", TokenHarvest.scanRefresh(
                "{\"refresh_token\":\"\",\"x\":{\"refresh_token\":\"RT-LATER\"}}"),
                "★ 空值不算命中，继续找下一个");

        // ---------------------------------------------------------- describe / 打分
        TokenHarvest.Hit a = TokenHarvest.fromStorageDump("{\"token\":\"{\\\"refresh_token\\\":\\\"R1\\\"}\"}");
        TokenHarvest.Hit b = TokenHarvest.fromStorageDump(
                "{\"token\":\"{\\\"refresh_token\\\":\\\"R2\\\",\\\"access_token\\\":\\\"A2\\\"}\"}");
        ok(TokenHarvest.score(b) > TokenHarvest.score(a), "完整的分数更高");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
