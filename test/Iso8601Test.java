import com.hex91.file.Iso8601;

/**
 * v1.89.0 云盘时间戳解析。
 *
 * <p>mtime 只有一个用途：断点续传时判断「远端文件是不是被换过」。
 * 解析错会让续传判断失效（该重传的接着传），解析崩会让整个目录列不出来 ——
 * 所以这里既要钉住正确值，也要钉住「怪格式不许抛异常」。</p>
 */
public class Iso8601Test {

    static int pass = 0, fail = 0;
    static final long BASE = 1684886400000L;      // 2023-05-24T00:00:00Z

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eql(long expect, long got, String msg) {
        if (expect == got) { pass++; System.out.println("PASS  " + msg + " = " + got); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got); }
    }

    public static void main(String[] args) {
        // ---------------------------------------------------------- Graph 风格
        eql(BASE, Iso8601.parse("2023-05-24T00:00:00Z"), "Graph 标准格式（Z）");
        eql(BASE, Iso8601.parse("2023-05-24T00:00:00.000Z"), "带毫秒 .000");
        eql(BASE + 123, Iso8601.parse("2023-05-24T00:00:00.123Z"), "毫秒保留");
        eql(BASE + 123, Iso8601.parse("2023-05-24T00:00:00.1234567Z"), "七位小数只取前三位");

        // ---------------------------------------------------------- 时区
        eql(BASE - 8 * 3600_000L, Iso8601.parse("2023-05-24T00:00:00+08:00"), "+08:00 换算成 UTC");
        eql(BASE, Iso8601.parse("2023-05-24T08:00:00+08:00"), "+08:00 的同一时刻");
        eql(BASE, Iso8601.parse("2023-05-23T18:30:00-05:30"), "-05:30 负偏移（18:30+5:30=次日 0 点）");
        eql(BASE, Iso8601.parse("2023-05-24T00:00:00+0000"), "+0000（无冒号）");
        eql(BASE, Iso8601.parse("2023-05-24T00:00:00"), "无时区按 UTC");

        // ---------------------------------------------------------- 边界
        eql(0, Iso8601.parse("1970-01-01T00:00:00Z"), "epoch 起点");
        eql(1709210096000L, Iso8601.parse("2024-02-29T12:34:56Z"), "闰年 2 月 29 日");
        eql(BASE, Iso8601.parse("  2023-05-24T00:00:00Z  "), "首尾空白");
        eql(BASE, Iso8601.parse("2023-05-24T00:00:00z"), "小写 z");

        // ---------------------------------------------------------- 坏输入一律 0，不许抛
        eql(0, Iso8601.parse(null), "null → 0");
        eql(0, Iso8601.parse(""), "空串 → 0");
        eql(0, Iso8601.parse("2023-05-24"), "只有日期 → 0（长度不足）");
        eql(0, Iso8601.parse("not a time"), "垃圾 → 0");
        eql(0, Iso8601.parse("2023-13-45T00:00:00Z"), "非法月日 → 0");
        eql(0, Iso8601.parse("2023-05-24T99:99:99Z"), "非法时分秒不崩（Calendar 宽容，但不抛）");

        // 这些是「真实会出现的怪值」，只要不抛异常就算通过
        ok(Iso8601.parse("2023-05-24T00:00:00.5Z") == BASE + 500, "一位小数补零成 .500");
        ok(Iso8601.parse("2023-05-24T00:00:00,123Z") >= 0, "逗号小数（部分服务端）不抛");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
