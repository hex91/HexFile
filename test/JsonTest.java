import com.hex91.file.Json;

import java.util.List;
import java.util.Map;

/**
 * v1.89.0 极简 JSON 解析器。
 *
 * <p>它要解析的是 OAuth 授权服务器和云盘 API 的返回体 —— 一旦解析错，
 * 表现是「明明授权成功却提示没拿到 access_token」这种极难查的问题，
 * 所以把数字类型、转义、空值、异常输入都钉住。</p>
 */
public class JsonTest {

    static int pass = 0, fail = 0;

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

    static boolean throwsIAE(String json) {
        try {
            Json.parse(json);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    public static void main(String[] args) {
        // ---------------------------------------------------------- 基本类型
        Object o = Json.parse("{\"a\":\"x\",\"n\":42,\"f\":1.5,\"t\":true,\"z\":null}");
        ok(o instanceof Map, "顶层对象 → Map");
        eqs("x", Json.str(o, "a"), "字符串字段");
        eql(42, Json.num(o, "n", -1), "整数字段");
        eql(-5, Json.num(o, "t", -5), "布尔不是数字 → 默认值");
        ok(Json.get(o, "z") == Json.NULL, "null 映射成 NULL 单例");
        ok(Json.get(o, "missing") == null, "缺键返回 null（区别于 NULL）");
        eqs("", Json.str(o, "missing"), "缺键的字符串 → 空串");
        eql(-1, Json.num(o, "missing", -1), "缺键的数字 → 默认值");
        ok(Json.bool(Json.parse("{\"b\":true}"), "b", false), "布尔 true");
        ok(!Json.bool(Json.parse("{\"b\":false}"), "b", true), "布尔 false");
        ok(Json.bool(Json.parse("{}"), "b", true), "缺布尔 → 默认值");

        // 数字的 Long / Double 之分：文件大小必须是精确整数
        Object big = Json.parse("{\"size\":1099511627776}");
        ok(Json.get(big, "size") instanceof Long, "无小数点 → Long（大文件大小不丢精度）");
        eql(1099511627776L, Json.num(big, "size", 0), "TB 级大小精确");
        ok(Json.get(Json.parse("{\"v\":1e3}"), "v") instanceof Double, "指数写法 → Double");
        eql(1000, Json.num(Json.parse("{\"v\":1e3}"), "v", 0), "1e3 = 1000");
        eql(-7, Json.num(Json.parse("{\"v\":-7}"), "v", 0), "负数");
        eql(2, Json.num(Json.parse("{\"v\":\"2\"}"), "v", 0), "字符串数字也接受（部分网关会这么返回）");

        // ---------------------------------------------------------- 数组 / 嵌套
        Object arr = Json.parse("{\"items\":[{\"name\":\"a\"},{\"name\":\"b\"}],\"empty\":[]}");
        List<Object> items = Json.arr(arr, "items");
        eql(2, items.size(), "数组长度");
        eqs("b", Json.str(items.get(1), "name"), "数组内对象字段");
        eql(0, Json.arr(arr, "empty").size(), "空数组");
        eql(0, Json.arr(arr, "nope").size(), "非数组字段 → 空列表（省掉 null 判断）");

        // 点号路径
        Object deep = Json.parse("{\"root\":{\"folder\":{\"childCount\":3}},\"list\":[{\"id\":\"x\"}]}");
        ok(Json.path(deep, "root.folder.childCount") instanceof Long, "嵌套路径可取值");
        eql(3, ((Long) Json.path(deep, "root.folder.childCount")).longValue(), "嵌套数字字段");
        eqs("x", Json.strPath(deep, "list.0.id"), "路径里的数字段当数组下标");
        ok(Json.path(deep, "root.nope.deeper") == null, "路径断在中间 → null");
        eqs("", Json.strPath(deep, "root.nope.deeper"), "断掉的路径取字符串 → 空串");
        ok(Json.path(deep, "") == deep, "空路径返回自身");

        // ---------------------------------------------------------- 转义
        Object esc = Json.parse("{\"s\":\"a\\\"b\\\\c\\/d\\ne\\tf\",\"u\":\"\\u4e2d\\u6587\"}");
        eqs("a\"b\\c/d\ne\tf", Json.str(esc, "s"), "引号/反斜杠/斜杠/换行/制表符转义");
        eqs("中文", Json.str(esc, "u"), "\\u 转义（中文）");
        eqs("中", Json.str(Json.parse("{\"u\":\"\\u4E2D\"}"), "u"), "\\u 大写十六进制");

        // ---------------------------------------------------------- 容错
        eqs("x", Json.str(Json.parse("\uFEFF {\"a\":\"x\"} "), "a"), "BOM 与首尾空白可容忍");
        ok(Json.parseOrNull("not json") == null, "非 JSON → parseOrNull 返回 null");
        ok(Json.parseOrNull("") == null, "空串 → null");
        ok(Json.parseOrNull(null) == null, "null → null");
        ok(Json.parseOrNull("{\"a\":1}") != null, "合法 JSON → 非 null");

        // 错误必须抛（不能静默返回半个对象）
        ok(throwsIAE("{\"a\":1"), "对象未闭合抛异常");
        ok(throwsIAE("{\"a\" 1}"), "缺冒号抛异常");
        ok(throwsIAE("[1,2"), "数组未闭合抛异常");
        ok(throwsIAE("{\"a\":1} junk"), "尾部多余内容抛异常");
        ok(throwsIAE("{} {}"), "两个对象抛异常");
        ok(throwsIAE(""), "空文本抛异常");
        ok(throwsIAE(null), "null 抛异常");
        ok(throwsIAE("{a:1}"), "未加引号的键抛异常");

        // 空结构
        eql(0, Json.asObj(Json.parse("{}")).size(), "空对象");
        eql(0, Json.arr(Json.parse("[]"), "x").size(), "顶层数组取字段 → 空列表");
        eql(0, Json.asObj(Json.parse("[1]")).size(), "非对象 asObj → 空 Map");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
