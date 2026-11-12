package com.hex91.file;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.89.0 极简 JSON 解析器（纯 Java，零依赖）。
 *
 * <p>为什么不用 {@code org.json}：它只在 android.jar 里，桌面 JVM 跑单测时拿不到，
 * 于是「解析 OAuth 响应」这种最容易写错的代码反而没法测。这里自己写一个够用的
 * 递归下降解析器，解析结果只用到 {@link Map}/{@link List}/{@link String}/{@link Long}
 * /{@link Double}/{@link Boolean}/{@link #NULL} 这几种类型，能在桌面直接断言。</p>
 *
 * <p>只解析，不生成（本项目需要的都是读方向）。行为约定：</p>
 * <ul>
 *   <li>对象 → {@link LinkedHashMap}（保留原顺序，便于排查问题）；</li>
 *   <li>数组 → {@link ArrayList}；</li>
 *   <li>数字 → 无小数点/指数时 {@link Long}，否则 {@link Double}；</li>
 *   <li>null → {@link #NULL} 单例，避免与「键不存在」混淆；</li>
 *   <li>格式错误抛 {@link IllegalArgumentException}，错误信息带位置。</li>
 * </ul>
 */
public final class Json {

    /** JSON 里的 null（用单例表示，才能和「没有这个键」区分开） */
    public static final Object NULL = new Object() {
        @Override public String toString() { return "null"; }
    };

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    // ---------------------------------------------------------------- 入口

    /**
     * 解析整段文本。
     *
     * @throws IllegalArgumentException 语法错误（含字符下标，便于定位）
     */
    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("JSON 为空");
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length())
            throw p.err("多余内容");
        return v;
    }

    /** 解析失败返回 null（用于「响应可能不是 JSON」的容错路径） */
    public static Object parseOrNull(String text) {
        try {
            return parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 访问器

    /** 取对象字段；不是对象或没有该字段返回 null */
    public static Object get(Object o, String key) {
        if (!(o instanceof Map)) return null;
        return ((Map<?, ?>) o).get(key);
    }

    /** 取字符串字段；缺失/类型不符返回空串（调用方通常只想拼提示） */
    public static String str(Object o, String key) {
        Object v = get(o, key);
        if (v == null || v == NULL) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof Double) {
            double d = (Double) v;
            return d == Math.rint(d) && !Double.isInfinite(d)
                    ? String.valueOf((long) d) : String.valueOf(d);
        }
        return String.valueOf(v);
    }

    /** 取数字字段；缺失/非数字返回 def */
    public static long num(Object o, String key, long def) {
        Object v = get(o, key);
        if (v instanceof Long) return (Long) v;
        if (v instanceof Double) return (long) (double) (Double) v;
        if (v instanceof String) {
            try { return Long.parseLong(((String) v).trim()); } catch (NumberFormatException ignore) {}
        }
        return def;
    }

    public static boolean bool(Object o, String key, boolean def) {
        Object v = get(o, key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return "true".equalsIgnoreCase((String) v);
        return def;
    }

    /** 取对象字段为 Map；不是对象返回空 Map（省掉调用方的 null 判断） */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o, String key) {
        Object v = get(o, key);
        if (v instanceof Map) return (Map<String, Object>) v;
        return new LinkedHashMap<String, Object>();
    }

    /** 顶层对象（解析后立即断言是对象） */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObj(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        return new LinkedHashMap<String, Object>();
    }

    /** 取数组字段；不是数组返回空 List */
    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o, String key) {
        Object v = get(o, key);
        if (v instanceof List) return (List<Object>) v;
        return new ArrayList<Object>();
    }

    /**
     * 按点号路径取嵌套值：{@code path(root, "folder.childCount")}。
     *
     * <p>路径里出现纯数字段时优先当数组下标（JSON 字段名不会是纯数字）。
     * 任一层缺失返回 null，不抛异常。</p>
     */
    public static Object path(Object root, String dotted) {
        if (root == null || dotted == null || dotted.isEmpty()) return root;
        Object cur = root;
        int start = 0;
        while (start <= dotted.length()) {
            int dot = dotted.indexOf('.', start);
            String seg = dot < 0 ? dotted.substring(start) : dotted.substring(start, dot);
            if (cur instanceof Map) {
                cur = ((Map<?, ?>) cur).get(seg);
            } else if (cur instanceof List) {
                int idx = parseInt(seg, -1);
                List<?> l = (List<?>) cur;
                cur = idx >= 0 && idx < l.size() ? l.get(idx) : null;
            } else {
                return null;
            }
            if (cur == null || dot < 0) break;
            start = dot + 1;
        }
        return cur;
    }

    /** 按路径取字符串，缺失返回空串 */
    public static String strPath(Object root, String dotted) {
        Object v = path(root, dotted);
        if (v == null || v == NULL) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof Long) return String.valueOf(v);
        if (v instanceof Double) return String.valueOf((double) (Double) v);
        return String.valueOf(v);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---------------------------------------------------------------- 解析

    private Object value() {
        if (i >= s.length()) throw err("意外结束");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return NULL;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++;                                   // {
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw err("对象键必须是字符串");
            String k = string();
            ws();
            if (peek() != ':') throw err("缺少冒号");
            i++;
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; return m; }
            throw err("对象未闭合");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<Object>();
        i++;                                   // [
        ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; return l; }
            throw err("数组未闭合");
        }
    }

    private String string() {
        i++;                                   // 开头的 "
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("字符串未闭合");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            if (i >= s.length()) throw err("转义未闭合");
            char e = s.charAt(i++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("\\u 转义长度不足");
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException nfe) {
                        throw err("\\u 转义非法");
                    }
                    i += 4;
                    break;
                default:
                    // 严格模式应报错，但云服务返回体偶有手写转义，宽松处理更不容易炸
                    sb.append(e);
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek() == '-' || peek() == '+') i++;
        boolean fp = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') { i++; continue; }
            if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { fp = true; i++; continue; }
            break;
        }
        String t = s.substring(start, i);
        if (t.isEmpty() || "-".equals(t) || "+".equals(t)) throw err("非法值");
        try {
            return fp ? (Object) Double.valueOf(t) : (Object) Long.valueOf(t);
        } catch (NumberFormatException e) {
            try {
                return (Object) Double.valueOf(t);
            } catch (NumberFormatException e2) {
                throw err("数字非法: " + t);
            }
        }
    }

    private void expect(String lit) {
        if (!s.startsWith(lit, i)) throw err("期望 " + lit);
        i += lit.length();
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') i++;
            else break;
        }
    }

    private IllegalArgumentException err(String msg) {
        int from = Math.max(0, i - 20), to = Math.min(s.length(), i + 20);
        String near = s.substring(from, to).replace('\n', ' ');
        return new IllegalArgumentException("JSON " + msg + " @ " + i + " …" + near + "…");
    }
}
