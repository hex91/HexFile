import com.hex91.file.M3u8Playlist;

/**
 * v1.91.0 HLS master 解析。
 *
 * <p>这里错一处，表现是「画质菜单点哪个都放不出来」或「选了 720P 实际播的还是 1080P」。
 * 重点盯三个坑：CRLF 行尾、CODECS 引号里的逗号、相对路径解析。</p>
 */
public class M3u8PlaylistTest {

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

    static void eql(long expect, long got, String msg) {
        if (expect == got) { pass++; System.out.println("PASS  " + msg + " = " + got); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got); }
    }

    public static void main(String[] args) {
        // ---------------------------------------------------------- master（CRLF）
        String master =
                "#EXTM3U\r\n"
                + "#EXT-X-VERSION:3\r\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=4200000,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\"\r\n"
                + "1080p/index.m3u8\r\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f,mp4a.40.2\"\r\n"
                + "720p/index.m3u8\r\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=640x360\r\n"
                + "360p/index.m3u8\r\n";
        M3u8Playlist p = M3u8Playlist.parse(master, "https://cdn.example.com/live/playlist.m3u8");
        eql(M3u8Playlist.KIND_MASTER, p.kind, "识别为 master");
        eql(3, p.variants().size(), "三档画质");
        ok(p.hasChoice(), "多档 → 可选择");
        eqs("https://cdn.example.com/live/1080p/index.m3u8",
                p.variants().get(0).url, "相对路径按 base 目录解析（CRLF 已剥）");
        eql(1920, p.variants().get(0).width, "宽");
        eql(1080, p.variants().get(0).height, "高");
        eqs("1080p", p.variants().get(0).label, "标签取短边 + p");
        eql(4200000, p.variants().get(0).bandwidth, "码率");
        eqs("avc1.640028,mp4a.40.2", p.variants().get(0).codecs,
                "CODECS 引号内的逗号不切分");
        eqs("1920×1080 · 4.2 Mbps", p.variants().get(0).detail(), "详情行");

        // 顺序：码率降序
        eqs("1080p", p.byQualityDesc().get(0).label, "最高画质排第一");
        eqs("360p", p.byQualityDesc().get(2).label, "最低画质排最后");
        eqs("1080p", p.best().label, "best() = 最高档");
        ok(p.byLabel("720p") != null, "按标签查找命中");
        ok(p.byLabel("720P") == null, "标签查找区分大小写（标签是自产的值）");

        // ---------------------------------------------------------- 只有一档
        String one =
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=854x480\na.m3u8\n";
        M3u8Playlist p1 = M3u8Playlist.parse(one, "https://h/x.m3u8");
        eql(1, p1.variants().size(), "只有一档");
        ok(!p1.hasChoice(), "一档 → 无可选画质");

        // ---------------------------------------------------------- 无分辨率
        M3u8Playlist p2 = M3u8Playlist.parse(
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8\n", "https://h/x.m3u8");
        eqs("800 Kbps", p2.variants().get(0).label, "无分辨率 → 用码率当标签");
        eql(0, p2.variants().get(0).shortSide(), "无分辨率 → 短边 0");

        // ---------------------------------------------------------- media playlist
        String media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n"
                + "#EXTINF:6.0,\nseg1.ts\n#EXTINF:6.0,\nseg2.ts\n#EXT-X-ENDLIST\n";
        M3u8Playlist p3 = M3u8Playlist.parse(media, "https://h/live.m3u8");
        eql(M3u8Playlist.KIND_MEDIA, p3.kind, "media playlist 识别");
        eql(0, p3.variants().size(), "media 无变体");
        ok(!p3.hasChoice(), "media → 无画质可选");

        // ---------------------------------------------------------- I-FRAME 不混入
        String iframe = "#EXTM3U\n"
                + "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=90000,RESOLUTION=320x180,URI=\"iframe.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720\nv720.m3u8\n";
        M3u8Playlist p4 = M3u8Playlist.parse(iframe, "https://h/master.m3u8");
        eql(1, p4.variants().size(), "I 帧轨道不进画质菜单");
        eql(M3u8Playlist.KIND_MASTER, p4.kind, "含 I 帧也仍是 master");

        // ---------------------------------------------------------- 地址解析
        eqs("https://cdn/a/b.m3u8",
                M3u8Playlist.resolve("https://cdn/x/y.m3u8", "https://cdn/a/b.m3u8"), "绝对地址不变");
        eqs("https://cdn/x/b.m3u8",
                M3u8Playlist.resolve("https://cdn/x/y.m3u8", "b.m3u8"), "同级相对");
        eqs("https://cdn/b.m3u8",
                M3u8Playlist.resolve("https://cdn/x/y.m3u8", "../b.m3u8"), "上级相对");
        eqs("https://cdn/x/b.m3u8",
                M3u8Playlist.resolve("https://cdn/x/y.m3u8", "a/../b.m3u8"), "相对路径内的 .. 一并折叠");
        eqs("https://cdn/x/b.m3u8",
                M3u8Playlist.normalizePath("https://cdn/x/a/../b.m3u8"), "normalizePath 折叠 ..");
        eqs("https://cdn/x/b.m3u8",
                M3u8Playlist.resolve("https://cdn/x/y.m3u8", "./b.m3u8?t=1#f")
                        .split("\\?")[0], "带查询串的相对路径");
        eqs("https://other/b.m3u8",
                M3u8Playlist.resolve("https://cdn/x/y.m3u8", "//other/b.m3u8"), "协议相对补 scheme");
        eqs("https://cdn/b.m3u8",
                M3u8Playlist.resolve("https://cdn/x/y.m3u8", "/b.m3u8"), "站点绝对路径");
        eqs("b.m3u8", M3u8Playlist.resolve(null, "b.m3u8"), "无 base 原样返回");
        eqs("https://cdn/x/t/b.m3u8",
                M3u8Playlist.resolve("https://cdn/x/y.m3u8?a=1", "t/b.m3u8"), "base 带查询串也能解析");
        // 端口不能丢
        eqs("http://1.2.3.4:8080/hls/b.m3u8",
                M3u8Playlist.resolve("http://1.2.3.4:8080/hls/y.m3u8?x=1", "b.m3u8"), "端口保留");
        // 本地 HLS（file://）
        eqs("file:///sdcard/a/b.m3u8",
                M3u8Playlist.resolve("file:///sdcard/a/m.m3u8", "b.m3u8"), "file:// 相对解析");

        // ---------------------------------------------------------- 属性解析边界
        eqs("1920x1080", M3u8Playlist.attrValue("RESOLUTION=1920x1080,NAME=\"1080p\"", "RESOLUTION"),
                "属性取值：值里的 x 不影响切分");
        eqs("A,B", M3u8Playlist.attrValue("X=1,NAME=\"A,B\"", "NAME"), "引号内逗号保留");
        ok(M3u8Playlist.attrValue("A=1", "B") == null, "不存在的键 → null");
        ok(M3u8Playlist.attrValue(null, "A") == null, "null 入参不崩");

        // ---------------------------------------------------------- 脏输入
        ok(M3u8Playlist.parseOrEmpty(null, null).kind == M3u8Playlist.KIND_UNKNOWN, "null → UNKNOWN");
        ok(M3u8Playlist.parseOrEmpty("", null).kind == M3u8Playlist.KIND_UNKNOWN, "空串 → UNKNOWN");
        ok(M3u8Playlist.parseOrEmpty("<html>404</html>", null).kind == M3u8Playlist.KIND_UNKNOWN,
                "非 m3u8（例如 404 页面）→ UNKNOWN");
        // STREAM-INF 后面没有地址行（被截断）
        M3u8Playlist p5 = M3u8Playlist.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n", "https://h/m.m3u8");
        eql(0, p5.variants().size(), "截断的 master 不产生空地址变体");
        // 地址行前有空行与注释
        M3u8Playlist p6 = M3u8Playlist.parse(
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n\n# 注释\nv.m3u8\n", "https://h/m.m3u8");
        eql(1, p6.variants().size(), "地址行前的空行/注释被跳过");

        // ---------------------------------------------------------- v1.95.4 回归：相对 / 无路径 base
        // 「只填域名」的地址以前会拼成 https://seg.ts（主机丢了）
        eqs("https://host/seg.ts", M3u8Playlist.resolve("https://host", "seg.ts"),
                "base 无路径：https://host + seg.ts");
        eqs("https://host:8080/seg.ts", M3u8Playlist.resolve("https://host:8080", "seg.ts"),
                "带端口的 base 同理");
        // 相对 base 不能被补成站点绝对路径
        eqs("seg.ts", M3u8Playlist.resolve("playlist.m3u8", "seg.ts"),
                "相对 base：同目录");
        eqs("dir/seg.ts", M3u8Playlist.resolve("dir/playlist.m3u8", "seg.ts"),
                "相对 base 带目录");
        // 绝对 base 不能被破坏
        eqs("https://h/a/b/seg.ts", M3u8Playlist.resolve("https://h/a/b/m.m3u8", "seg.ts"),
                "绝对 base 正常");
        eqs("https://h/a/seg.ts", M3u8Playlist.resolve("https://h/a/b/m.m3u8", "../seg.ts"),
                "绝对 base 的 ../ 折叠");

        // ---------------------------------------------------------- v1.95.4 回归：BOM
        M3u8Playlist bom = M3u8Playlist.parse(
                "\uFEFF#EXTM3U\n#EXTINF:4,\nseg1.ts\n", "https://h/m.m3u8");
        eql(M3u8Playlist.KIND_MEDIA, bom.kind, "带 BOM 的清单仍识别为 media");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
