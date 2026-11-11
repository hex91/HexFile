import com.hex91.file.StreamUrl;

/**
 * v1.91.0 流地址识别与清洗。
 *
 * <p>这些断言对应的都是「用户从微信复制了一段话粘进来」的真实形态：
 * 前后带中文、带引号、带全角空格、带「提取码」。播放器只要拿到带前缀的串，
 * 底层就会把它当成非法 URI 直接报错，用户看到的只是「播放失败」。</p>
 */
public class StreamUrlTest {

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

    static void eql(int expect, int got, String msg) {
        if (expect == got) { pass++; System.out.println("PASS  " + msg + " = " + got); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got); }
    }

    public static void main(String[] args) {
        // ---------------------------------------------------------- normalize
        eqs("https://a.com/x.m3u8", StreamUrl.normalize("  https://a.com/x.m3u8  "), "去首尾空格");
        eqs("https://a.com/x.m3u8", StreamUrl.normalize("\"https://a.com/x.m3u8\""), "去双引号");
        eqs("https://a.com/x.m3u8", StreamUrl.normalize("“https://a.com/x.m3u8”"), "去中文引号");
        eqs("https://a.com/x.m3u8", StreamUrl.normalize("【https://a.com/x.m3u8】"), "去中文方括号");
        eqs("https://a.com/x.m3u8", StreamUrl.normalize("<https://a.com/x.m3u8>"), "去尖括号");
        eqs("https://a.com/x.m3u8", StreamUrl.normalize("\u3000https://a.com/x.m3u8\u3000"),
                "全角空格（trim 不处理，必须自己剥）");
        ok(StreamUrl.normalize("   ") == null, "全空白 → null");
        ok(StreamUrl.normalize(null) == null, "null → null");

        // ---------------------------------------------------------- 从文本里抽地址
        eqs("https://a.com/live/x.m3u8",
                StreamUrl.normalize("复制这段内容后打开手机：https://a.com/live/x.m3u8 提取码: abcd"),
                "中文前后缀里抽 URL");
        eqs("https://a.com/x.m3u8?t=1",
                StreamUrl.normalize("[链接](https://a.com/x.m3u8?t=1)"),
                "Markdown 链接里抽 URL");
        eqs("https://a.com/x.m3u8",
                StreamUrl.normalize("看这个：https://a.com/x.m3u8。"),
                "结尾中文句号不吞进地址");
        eqs("http://1.2.3.4:8080/live.m3u8",
                StreamUrl.normalize("地址 http://1.2.3.4:8080/live.m3u8，请用浏览器打开"),
                "端口 + 中文逗号截断");
        ok(StreamUrl.normalize("没有任何链接的一段话") == null, "无链接 → null");

        // ---------------------------------------------------------- 协议白名单
        ok(StreamUrl.isSupported("http://a/b.mp4"), "http 允许");
        ok(StreamUrl.isSupported("https://a/b.mp4"), "https 允许");
        ok(StreamUrl.isSupported("rtsp://a/b"), "rtsp 允许");
        ok(StreamUrl.isSupported("rtmp://a/b"), "rtmp 允许");
        ok(StreamUrl.isSupported("file:///sdcard/a.mp4"), "file 允许");
        ok(StreamUrl.isSupported("content://media/1"), "content 允许");
        ok(!StreamUrl.isSupported("javascript:alert(1)"), "javascript: 拒绝");
        ok(!StreamUrl.isSupported("intent://x#Intent;end"), "intent: 拒绝");
        ok(!StreamUrl.isSupported("data:text/html,<b>x"), "data: 拒绝");
        ok(!StreamUrl.isSupported("b.mp4"), "裸路径（无 scheme）拒绝");
        ok(!StreamUrl.isSupported(null), "null 拒绝");
        // 含空格/中文的地址：协议层面仍是合法 http 地址（编码由播放内核负责，
        // 播放器构造 Uri 时会把裸空格换成 %20），这里不能误判为「不支持」
        ok(StreamUrl.isSupported("https://a.com/我的 视频.mp4"), "含空格中文仍算 http 地址");

        // ---------------------------------------------------------- 类型判定
        eql(StreamUrl.KIND_HLS, StreamUrl.kindOf("https://a.com/x.m3u8"), "m3u8 → HLS");
        eql(StreamUrl.KIND_HLS, StreamUrl.kindOf("https://a.com/x.m3u8?token=abc&e=1"), "带查询串仍是 HLS");
        eql(StreamUrl.KIND_HLS, StreamUrl.kindOf("https://a.com/x.M3U8"), "扩展名大小写不敏感");
        eql(StreamUrl.KIND_DASH, StreamUrl.kindOf("https://a.com/x.mpd"), "mpd → DASH");
        eql(StreamUrl.KIND_RTSP, StreamUrl.kindOf("rtsp://cam/stream1"), "rtsp → RTSP");
        eql(StreamUrl.KIND_RTMP, StreamUrl.kindOf("rtmp://live/app/key"), "rtmp → RTMP");
        eql(StreamUrl.KIND_PROGRESSIVE, StreamUrl.kindOf("https://a.com/x.mp4"), "mp4 → 渐进流");
        eql(StreamUrl.KIND_PROGRESSIVE, StreamUrl.kindOf("https://a.com/live/12345"), "无扩展名 → 渐进流（交给嗅探）");
        eql(StreamUrl.KIND_LOCAL, StreamUrl.kindOf("file:///sdcard/x.mkv"), "file → 本地");
        eql(StreamUrl.KIND_LOCAL, StreamUrl.kindOf("content://m/1"), "content → 本地");
        eql(StreamUrl.KIND_UNKNOWN, StreamUrl.kindOf("ftp://a/b"), "ftp → 未知");
        eql(StreamUrl.KIND_UNKNOWN, StreamUrl.kindOf(null), "null → 未知");

        eqs("HLS 直播/点播（m3u8）", StreamUrl.kindLabel(StreamUrl.KIND_HLS), "类型文案");

        // ---------------------------------------------------------- 标题
        eqs("x.m3u8", StreamUrl.fileName("https://a.com/live/x.m3u8?t=1"), "文件名去查询串");
        eqs("中文.mp4", StreamUrl.fileName("https://a.com/%E4%B8%AD%E6%96%87.mp4"), "文件名 URL 解码");
        eqs("https://a.com", StreamUrl.fileName("https://a.com"), "无路径 → 用 origin 当标题");
        eqs("https://a.com", StreamUrl.fileName("https://a.com/"), "根路径 → 用 origin");
        eqs("100%.mp4", StreamUrl.fileName("https://a.com/100%25.mp4"), "%25 → %");
        eqs("坏%zz", StreamUrl.fileName("https://a.com/坏%zz"), "畸形百分号原样返回，不抛异常");

        // ---------------------------------------------------------- origin / path
        eqs("https://a.com:8443", StreamUrl.originOf("https://a.com:8443/x/y?z=1"), "origin 带端口");
        eqs("https://a.com", StreamUrl.originOf("https://a.com"), "origin 无路径");
        eqs("/x/y.m3u8", StreamUrl.pathOf("https://a.com/x/y.m3u8?a=1#b"), "path 去查询与 fragment");
        eqs("/a/b", StreamUrl.pathOf("rtsp://cam:554/a/b"), "rtsp path");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
