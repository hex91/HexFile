import com.hex91.file.HlsCrypto;
import com.hex91.file.HlsKey;
import com.hex91.file.HlsPlaylist;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

/**
 * v1.92.0 HLS 媒体清单（下载用）解析 + AES-128 解密。
 *
 * <p>这里每一条错都对应一个「下完播不了」的真实症状：
 * BYTERANGE 漏了 → 同一部片子重复 N 遍；MAP 漏了 → fMP4 打不开；
 * 隐式 IV 算错 → 前 16 字节对、后面全是噪音；GAP 没跳 → 404 直接整体失败。</p>
 */
public class HlsPlaylistTest {

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

    static void eqb(byte[] expect, byte[] got, String msg) {
        if (Arrays.equals(expect, got)) {
            pass++; System.out.println("PASS  " + msg);
        } else {
            fail++; System.out.println("FAIL  " + msg + " 期望 " + Arrays.toString(expect)
                    + " 实际 " + Arrays.toString(got));
        }
    }

    static byte[] hex(String s) {
        String h = s.replace("0x", "").replace(" ", "");
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static void main(String[] args) {
        basic();
        crlfAndBom();
        keys();
        ranges();
        flags();
        kinds();
        crypto();
        System.out.println("-----------------------------------------");
        System.out.println("HlsPlaylistTest pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------ 基本
    static void basic() {
        String pl =
                "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXT-X-TARGETDURATION:10\n"
                + "#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXTINF:9.009,first\n"
                + "seg0.ts\n"
                + "#EXTINF:9.009,\n"
                + "seg1.ts\n"
                + "#EXTINF:3.5,short\n"
                + "sub/seg2.ts?token=abc\n"
                + "#EXT-X-ENDLIST\n";
        HlsPlaylist p = HlsPlaylist.parse(pl, "https://cdn.example.com/vod/index.m3u8");
        eql(HlsPlaylist.KIND_MEDIA, p.kind, "识别为 media playlist");
        eql(3, p.count(), "三个分片");
        eql(3, p.downloadableCount(), "三个都可下载");
        eql(21518, p.totalDurationMs(), "总时长 9.009+9.009+3.5 秒");
        ok(p.hasEndList, "有 ENDLIST → 点播");
        ok(!p.isLive(), "点播不是直播");
        ok(!p.encrypted(), "未加密");
        eqs("未加密", p.encryptionText(), "加密说明");
        eqs("https://cdn.example.com/vod/seg0.ts", p.segments().get(0).url, "相对路径补成绝对");
        eqs("https://cdn.example.com/vod/sub/seg2.ts?token=abc",
                p.segments().get(2).url, "子目录 + 查询串保留");

        // 上级目录 + 站点绝对路径
        String pl2 = "#EXTM3U\n#EXTINF:1,\n../up/seg.ts\n#EXTINF:1,\n/root/seg.ts\n";
        HlsPlaylist p2 = HlsPlaylist.parse(pl2, "https://a.com/x/y/z.m3u8");
        eqs("https://a.com/x/up/seg.ts", p2.segments().get(0).url, "../ 折叠");
        eqs("https://a.com/root/seg.ts", p2.segments().get(1).url, "站点绝对路径");

        // 没有 EXTINF 的分片也要收（某些低码率纯 ts 列表不写时长）
        HlsPlaylist p3 = HlsPlaylist.parse("#EXTM3U\na.ts\nb.ts\n", "https://a.com/p/i.m3u8");
        eql(2, p3.count(), "无 EXTINF 也收分片");
        eql(0, p3.totalDurationMs(), "时长未知按 0");

        // 空 / 垃圾
        eql(HlsPlaylist.KIND_UNKNOWN, HlsPlaylist.parse("", null).kind, "空文本 → UNKNOWN");
        eql(HlsPlaylist.KIND_UNKNOWN, HlsPlaylist.parse(null, null).kind, "null → UNKNOWN");
        eql(HlsPlaylist.KIND_UNKNOWN, HlsPlaylist.parse("<html>404</html>", null).kind,
                "错误页面 → UNKNOWN");
        eqs("ts", p.suggestedExt(), "普通分片建议扩展名 ts");
    }

    // ------------------------------------------------------------ CRLF / BOM
    static void crlfAndBom() {
        String pl = "\uFEFF#EXTM3U\r\n#EXTINF:6,\r\nseg0.ts\r\n#EXT-X-ENDLIST\r\n";
        HlsPlaylist p = HlsPlaylist.parse(pl, "https://cdn.example.com/a/index.m3u8");
        eql(1, p.count(), "CRLF + BOM 仍能解析出分片");
        eqs("https://cdn.example.com/a/seg0.ts", p.segments().get(0).url, "地址尾不残留 \\r");
        ok(p.hasEndList, "CRLF 下 ENDLIST 识别");
    }

    // ------------------------------------------------------------ KEY
    static void keys() {
        String explicit =
                "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x000102030405060708090A0B0C0D0E0F\n"
                + "#EXTINF:10,\n"
                + "seg0.ts\n"
                + "#EXTINF:10,\n"
                + "seg1.ts\n";
        HlsPlaylist p = HlsPlaylist.parse(explicit, "https://cdn.example.com/v/a.m3u8");
        ok(p.encrypted(), "检测到加密");
        HlsKey k = p.segments().get(0).key;
        ok(k.isAes128(), "AES-128");
        eqs("https://cdn.example.com/v/key.bin", k.uri, "KEY 相对地址解析");
        eqb(hex("000102030405060708090A0B0C0D0E0F"), k.ivFor(999), "显式 IV 与序列号无关");
        eqs("AES-128", p.encryptionText(), "加密说明 AES-128");
        ok(p.uniformKey() != null, "全片同一把 key");
        ok(p.blockReason() == null, "AES-128 可自行解密");

        // 隐式 IV + MEDIA-SEQUENCE
        String implicit =
                "#EXTM3U\n"
                + "#EXT-X-MEDIA-SEQUENCE:100\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"https://k.com/k\"\n"
                + "#EXTINF:10,\ns0.ts\n#EXTINF:10,\ns1.ts\n#EXTINF:10,\ns2.ts\n";
        HlsPlaylist q = HlsPlaylist.parse(implicit, "https://cdn.example.com/v/a.m3u8");
        eql(100, q.segments().get(0).sequence, "首片序列号 = MEDIA-SEQUENCE");
        eql(102, q.segments().get(2).sequence, "第三片序列号递增");
        byte[] iv0 = q.segments().get(0).key.ivFor(100);
        eqb(hex("00000000000000000000000000000064"), iv0, "隐式 IV = 序列号大端（100=0x64）");
        byte[] iv2 = q.segments().get(2).key.ivFor(102);
        eql(0x66, iv2[15] & 0xFF, "隐式 IV 用媒体序列号而非下标（102=0x66）");

        // METHOD=NONE 复位：前两片加密、后两片明文
        String reset =
                "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"k1\"\n"
                + "#EXTINF:1,\na.ts\n#EXTINF:1,\nb.ts\n"
                + "#EXT-X-KEY:METHOD=NONE\n"
                + "#EXTINF:1,\nc.ts\n#EXTINF:1,\nd.ts\n";
        HlsPlaylist r = HlsPlaylist.parse(reset, "https://cdn.example.com/v/a.m3u8");
        ok(r.segments().get(0).encrypted(), "第 1 片加密");
        ok(r.segments().get(1).encrypted(), "第 2 片加密");
        ok(!r.segments().get(2).encrypted(), "METHOD=NONE 之后第 3 片明文");
        ok(r.uniformKey() == null, "混用密钥/明文时没有统一 key");

        // 多把 key
        String multi =
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k1\"\n#EXTINF:1,\na.ts\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"k2\"\n#EXTINF:1,\nb.ts\n";
        HlsPlaylist m = HlsPlaylist.parse(multi, "https://cdn.example.com/v/a.m3u8");
        ok(m.uniformKey() == null, "两把 key → 无统一 key");
        eqs("多把密钥", m.encryptionText(), "加密说明：多把密钥");

        // SAMPLE-AES 不可解
        String drm = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n#EXTINF:1,\na.ts\n";
        HlsPlaylist d = HlsPlaylist.parse(drm, "https://cdn.example.com/v/a.m3u8");
        ok(d.blockReason() != null, "SAMPLE-AES 给出阻断原因");
        ok(d.blockReason().contains("DRM"), "阻断原因提到 DRM");
        ok(!d.segments().get(0).key.supported(), "SAMPLE-AES 不支持");

        // IV 长度不足要左补 0；超长截尾
        eqb(hex("000000000000000000000000000000AB"), HlsKey.parseIv("0xAB"), "短 IV 左补 0");
        eqb(hex("00000000000000000000000000000001"),
                HlsKey.parseIv("0x0000000000000000000000000000000001"), "超长 IV 取低 128 位");
        ok(HlsKey.parseIv(null) == null, "无 IV 返回 null");
    }

    // ------------------------------------------------------------ BYTERANGE
    static void ranges() {
        String pl =
                "#EXTM3U\n"
                + "#EXT-X-BYTERANGE:1000@0\n#EXTINF:10,\na.ts\n"
                + "#EXT-X-BYTERANGE:2000\n#EXTINF:10,\na.ts\n"
                + "#EXT-X-BYTERANGE:3000@9000\n#EXTINF:10,\nb.ts\n"
                + "#EXTINF:10,\nc.ts\n";
        HlsPlaylist p = HlsPlaylist.parse(pl, "https://cdn.example.com/v/a.m3u8");
        HlsPlaylist.Segment s0 = p.segments().get(0);
        HlsPlaylist.Segment s1 = p.segments().get(1);
        HlsPlaylist.Segment s2 = p.segments().get(2);
        HlsPlaylist.Segment s3 = p.segments().get(3);
        eql(0, s0.rangeOffset, "显式 offset=0");
        eql(1000, s0.rangeLength, "显式长度 1000");
        eql(1000, s1.rangeOffset, "省略 offset 接上一片结尾（1000）");
        eql(2000, s1.rangeLength, "第二片长度 2000");
        eql(9000, s2.rangeOffset, "换 URL 后显式 offset");
        ok(!s3.hasRange(), "没有 BYTERANGE 的分片是整文件");
        eql(-1, s3.rangeLength, "整文件长度 -1");
        eql(1000, s0.declaredBytes(), "单片预估字节数");
    }

    // ------------------------------------------------------------ MAP / GAP / DISCONTINUITY
    static void flags() {
        String fmp4 =
                "#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n"
                + "#EXTINF:4,\nseg0.m4s\n#EXTINF:4,\nseg1.m4s\n"
                + "#EXT-X-ENDLIST\n";
        HlsPlaylist p = HlsPlaylist.parse(fmp4, "https://cdn.example.com/v/a.m3u8");
        eqs("https://cdn.example.com/v/init.mp4", p.initUrl, "EXT-X-MAP 解析");
        ok(p.likelyFmp4(), "有 MAP → fMP4");
        eqs("mp4", p.suggestedExt(), "fMP4 建议扩展名 mp4");
        eql(2, p.count(), "两个分片");

        String gap =
                "#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-GAP\n#EXTINF:1,\nb.ts\n#EXTINF:1,\nc.ts\n";
        HlsPlaylist g = HlsPlaylist.parse(gap, "https://cdn.example.com/v/a.m3u8");
        eql(3, g.count(), "GAP 也占一个分片位");
        eql(2, g.downloadableCount(), "GAP 不计入可下载");
        ok(g.segments().get(1).gap, "标记出 GAP 分片");

        String disc =
                "#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:1,\nb.ts\n";
        HlsPlaylist d = HlsPlaylist.parse(disc, "https://cdn.example.com/v/a.m3u8");
        ok(d.hasDiscontinuity, "列表级：有不连续");
        ok(d.segments().get(1).discontinuity, "标记在第二个分片上");
        ok(!d.segments().get(0).discontinuity, "第一个分片不受影响");

        // DISCONTINUITY-SEQUENCE 只是序号，不能当成不连续标记
        String ds = "#EXTM3U\n#EXT-X-DISCONTINUITY-SEQUENCE:4\n#EXTINF:1,\na.ts\n";
        HlsPlaylist d2 = HlsPlaylist.parse(ds, "https://cdn.example.com/v/a.m3u8");
        ok(!d2.segments().get(0).discontinuity, "DISCONTINUITY-SEQUENCE 不标记分片");
    }

    // ------------------------------------------------------------ 类型判定
    static void kinds() {
        String master =
                "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720\n720p.m3u8\n";
        HlsPlaylist p = HlsPlaylist.parse(master, "https://cdn.example.com/v/a.m3u8");
        eql(HlsPlaylist.KIND_MASTER, p.kind, "master 识别");
        eql(0, p.count(), "master 本身没有分片");

        String live = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:500\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts\n";
        HlsPlaylist l = HlsPlaylist.parse(live, "https://cdn.example.com/live/a.m3u8");
        ok(l.isLive(), "无 ENDLIST → 直播");
        eql(500, l.mediaSequence, "直播的 MEDIA-SEQUENCE 读取");
        ok(!l.likelyFmp4(), "ts 分片不是 fMP4");
        eqs("ts", l.firstSegmentExt(), "首片扩展名 ts");
    }

    // ------------------------------------------------------------ AES 解密
    static void crypto() throws RuntimeException {
        try {
            byte[] key = hex("000102030405060708090A0B0C0D0E0F");
            byte[] iv = hex("0F0E0D0C0B0A09080706050403020100");
            // 造一段「像 TS」的明文：0x47 起头，188 对齐
            byte[] plain = new byte[188 * 3];
            for (int i = 0; i < plain.length; i++) plain[i] = (byte) i;
            plain[0] = 0x47;
            plain[188] = 0x47;
            plain[376] = 0x47;

            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] enc = c.doFinal(plain);
            ok(enc.length != plain.length, "PKCS5 加密后长度变化");
            ok(!HlsCrypto.looksLikeTs(enc), "密文不会误判成 TS");

            byte[] dec = HlsCrypto.decrypt(enc, key, iv);
            eqb(plain, dec, "AES-128-CBC 解密还原");

            // 明文分片（挂了 KEY 行但其实没加密）原样返回
            byte[] passthrough = HlsCrypto.decrypt(plain, key, iv);
            eqb(plain, passthrough, "明文分片原样返回，不硬解");

            // 错误密钥必须抛异常，而不是返回垃圾
            boolean threw = false;
            try {
                // 长度是 16 的倍数、但内容不是任何媒体格式：模拟「密钥不匹配」
                byte[] bad = new byte[enc.length];
                for (int i = 0; i < bad.length; i++) bad[i] = (byte) (0x5A + (i % 7));
                HlsCrypto.decrypt(bad, key, iv);
            } catch (Exception e) {
                threw = true;
            }
            ok(threw, "密钥不匹配抛异常（不当成明文吞掉）");

            ok(HlsCrypto.looksLikeTs(plain), "looksLikeTs 认出明文 TS");
            byte[] notTs = new byte[400];
            ok(!HlsCrypto.looksLikeTs(notTs), "全 0 不算 TS");

            // fMP4 识别
            byte[] fmp4 = new byte[24];
            fmp4[4] = 's'; fmp4[5] = 't'; fmp4[6] = 'y'; fmp4[7] = 'p';
            ok(HlsCrypto.looksLikeFmp4(fmp4), "styp 认作 fMP4");
            ok(!HlsCrypto.looksLikeFmp4(plain), "TS 不是 fMP4");

            // 密钥长度不对要明确报错
            boolean badKey = false;
            try {
                HlsCrypto.decrypt(enc, new byte[8], iv);
            } catch (Exception e) {
                badKey = true;
            }
            ok(badKey, "8 字节密钥报错");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL  crypto 异常 " + e);
        }
    }
}
