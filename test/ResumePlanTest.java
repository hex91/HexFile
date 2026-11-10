import com.hex91.file.ResumePlan;

import java.io.File;

/**
 * v1.88.0 断点续传决策。
 *
 * <p>续传出错的后果不是「传不动」，而是「拼出一个大小对、内容错的文件」，
 * 所以这里把每条保守分支都钉住：任何拿不准的情况都必须回 FRESH。</p>
 */
public class ResumePlanTest {

    static int pass = 0, fail = 0;
    static final long T0 = 1_700_000_000_000L;
    static final long SIZE = 10_000_000L;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eq(int expect, int got, String msg) {
        if (expect == got) { pass++; System.out.println("PASS  " + msg + " = " + got); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got); }
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

    public static void main(String[] args) {
        // ---------------------------------------------------------- 正常续传
        ResumePlan.Plan p = ResumePlan.decide(true, 4_000_000L, SIZE, SIZE, T0, T0);
        eq(ResumePlan.RESUME, p.mode, "远端未变 → 续传");
        eql(4_000_000L, p.offset, "偏移 = 已有长度");

        // mtime 有几十秒抖动（FTP 只到分钟）仍在容差内
        ResumePlan.Plan jitter = ResumePlan.decide(true, 1000, SIZE, SIZE, T0, T0 + 59_000);
        eq(ResumePlan.RESUME, jitter.mode, "mtime 差 59 秒：容差内续传");

        // 远端 mtime 未知（FTP DOS 风格解析失败）不阻断续传
        ResumePlan.Plan noMtime = ResumePlan.decide(true, 1000, SIZE, SIZE, T0, 0);
        eq(ResumePlan.RESUME, noMtime.mode, "远端 mtime 未知：仍可续传");

        // ---------------------------------------------------------- 必须重传
        eq(ResumePlan.FRESH, ResumePlan.decide(false, 0, SIZE, SIZE, T0, T0).mode,
                "没有临时文件 → 从头");
        eq(ResumePlan.FRESH, ResumePlan.decide(true, 0, SIZE, SIZE, T0, T0).mode,
                "临时文件长度为 0 → 从头");
        eq(ResumePlan.FRESH, ResumePlan.decide(true, 500, SIZE + 1, SIZE, T0, T0).mode,
                "远端大小变了 → 从头（文件被换成同名新版）");
        eq(ResumePlan.FRESH, ResumePlan.decide(true, 500, SIZE, 0, 0, T0).mode,
                "缺少 sidecar → 从头（不能凭空接字节）");
        eq(ResumePlan.FRESH, ResumePlan.decide(true, 500, SIZE, SIZE, T0, T0 + 10 * 60_000L).mode,
                "远端 mtime 变 10 分钟 → 从头");
        eq(ResumePlan.FRESH, ResumePlan.decide(true, SIZE + 100, SIZE, SIZE, T0, T0).mode,
                "临时文件比远端大 → 从头（远端缩水）");
        eq(ResumePlan.FRESH, ResumePlan.decide(true, 500, -1, -1, T0, T0).mode,
                "远端大小未知 → 从头");

        // ---------------------------------------------------------- 已完整
        ResumePlan.Plan done = ResumePlan.decide(true, SIZE, SIZE, SIZE, T0, T0);
        eq(ResumePlan.COMPLETE, done.mode, "临时文件已完整 → 只需改名");
        ok(done.isComplete(), "isComplete() 一致");
        eql(SIZE, done.offset, "完整时 offset = 全长");

        // 无 sidecar 但长度正好等于远端：仍然重传。长度对不代表内容对，
        // 这条 .hpart 可能是别的同名文件残留下来的，改名后就再也发现不了。
        eq(ResumePlan.FRESH, ResumePlan.decide(true, SIZE, SIZE, 0, 0, T0).mode,
                "无 sidecar → 从头（长度凑巧相等也不冒险）");

        // ---------------------------------------------------------- 自定义容差
        eq(ResumePlan.RESUME, ResumePlan.decide(true, 500, SIZE, SIZE, T0, T0 + 5000, 10_000).mode,
                "自定义容差 10 秒：差 5 秒仍续");
        eq(ResumePlan.FRESH, ResumePlan.decide(true, 500, SIZE, SIZE, T0, T0 + 5000, 1000).mode,
                "自定义容差 1 秒：差 5 秒重传");

        // ---------------------------------------------------------- 文案
        ok(p.describe().contains("续传"), "续传文案含「续传」");
        ok(p.describe().contains("3.8 MB"), "续传文案含已下载大小: " + p.describe());
        ok(ResumePlan.decide(false, 0, SIZE, SIZE, T0, T0).describe().contains("从头"),
                "重传文案含「从头」");

        // ---------------------------------------------------------- 路径
        File dest = new File("/sdcard/Download/movie.mp4");
        // 用 dest.getPath() 拼接而不是写死字符串：桌面 JVM 在 Windows 上分隔符是 \
        eqs(dest.getPath() + ".hpart", ResumePlan.partOf(dest).getPath(), "临时文件路径");
        eqs(dest.getPath() + ".hpart.meta", ResumePlan.metaOf(dest).getPath(), "校验文件路径");
        ok(ResumePlan.partOf(dest).getName().equals("movie.mp4.hpart"), "临时文件保留原扩展名");
        ok(ResumePlan.partOf(dest).getParentFile().equals(dest.getParentFile()), "临时文件与原文件同目录");

        // ---------------------------------------------------------- sidecar 读写
        String text = ResumePlan.metaText(123456789L, T0);
        long[] m = ResumePlan.parseMeta(text);
        ok(m != null, "sidecar 可解析");
        eql(123456789L, m[0], "sidecar size");
        eql(T0, m[1], "sidecar mtime");

        ok(ResumePlan.parseMeta(text.replace("\n", "\r\n")) != null, "CRLF 也能解析");
        ok(ResumePlan.parseMeta(text + "\n\n") != null, "尾部多余换行可容忍");
        ok(ResumePlan.parseMeta(null) == null, "null 返回 null");
        ok(ResumePlan.parseMeta("") == null, "空串返回 null");
        ok(ResumePlan.parseMeta("v1\nabc\n1\n") == null, "非法 size 返回 null");
        ok(ResumePlan.parseMeta("v2\n1\n2\n") == null, "版本不符返回 null");
        ok(ResumePlan.parseMeta("v1\n1\n") == null, "字段不足返回 null");

        // 旧版本/半截 sidecar 不能让 decide 抛异常
        long[] bad = ResumePlan.parseMeta("垃圾内容");
        ok(bad == null, "垃圾 sidecar → null（调用方按无 sidecar 处理）");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
