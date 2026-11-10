import com.hex91.file.SyncPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.87.0 云同步差异计算。
 *
 * <p>这些断言对应的是「会不会误删 / 会不会用旧覆盖新」，
 * 每一条错了都是用户数据事故，所以边界全部钉住。</p>
 */
public class SyncPlanTest {

    static int pass = 0, fail = 0;
    static final long TOL = 3000;      // 3 秒容差
    static final long T0 = 1_700_000_000_000L;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eq(int expect, int got, String msg) {
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

    static SyncPlan.Entry f(String path, long size, long mtime) {
        return new SyncPlan.Entry(path, false, size, mtime);
    }

    static SyncPlan.Entry d(String path) {
        return new SyncPlan.Entry(path, true, 0, 0);
    }

    static List<SyncPlan.Entry> list(SyncPlan.Entry... es) {
        List<SyncPlan.Entry> l = new ArrayList<SyncPlan.Entry>();
        for (SyncPlan.Entry e : es) l.add(e);
        return l;
    }

    public static void main(String[] args) {
        // ---------------------------------------------------------- 两边完全相同
        SyncPlan.Plan p1 = SyncPlan.build(
                list(f("a.txt", 100, T0)),
                list(f("a.txt", 100, T0)),
                SyncPlan.MODE_BOTH, TOL);
        eq(0, p1.work(), "完全相同：无需传输");
        eq(1, p1.skip, "完全相同：计为跳过");

        // mtime 差 1 秒（秒级精度误差）也应视为相同
        SyncPlan.Plan p2 = SyncPlan.build(
                list(f("a.txt", 100, T0)),
                list(f("a.txt", 100, T0 + 1000)),
                SyncPlan.MODE_BOTH, TOL);
        eq(0, p2.work(), "mtime 差 1 秒：容差内视为相同");

        // ---------------------------------------------------------- 本地独有
        SyncPlan.Plan up = SyncPlan.build(
                list(f("new.txt", 10, T0)),
                list(),
                SyncPlan.MODE_UP, TOL);
        eq(1, up.upload, "上传模式：本地独有 → 上传");
        eq(0, up.localOnly, "上传模式：不算「仅本地」");

        SyncPlan.Plan down = SyncPlan.build(
                list(f("new.txt", 10, T0)),
                list(),
                SyncPlan.MODE_DOWN, TOL);
        eq(0, down.work(), "下载模式：本地独有不传");
        eq(1, down.localOnly, "下载模式：计为仅本地");

        // ---------------------------------------------------------- 远端独有
        SyncPlan.Plan rdown = SyncPlan.build(
                list(),
                list(f("cloud.txt", 20, T0)),
                SyncPlan.MODE_DOWN, TOL);
        eq(1, rdown.download, "下载模式：远端独有 → 下载");

        SyncPlan.Plan rup = SyncPlan.build(
                list(),
                list(f("cloud.txt", 20, T0)),
                SyncPlan.MODE_UP, TOL);
        eq(0, rup.work(), "上传模式：远端独有不动它");
        eq(1, rup.remoteOnly, "上传模式：计为仅远端");

        // ---------------------------------------------------------- 双向：谁更新谁赢
        SyncPlan.Plan newerRemote = SyncPlan.build(
                list(f("doc.txt", 100, T0)),
                list(f("doc.txt", 200, T0 + 60_000)),
                SyncPlan.MODE_BOTH, TOL);
        eq(1, newerRemote.download, "双向：远端更新 → 下载");
        eq(0, newerRemote.upload, "双向：远端更新 → 不上传");

        SyncPlan.Plan newerLocal = SyncPlan.build(
                list(f("doc.txt", 200, T0 + 60_000)),
                list(f("doc.txt", 100, T0)),
                SyncPlan.MODE_BOTH, TOL);
        eq(1, newerLocal.upload, "双向：本地更新 → 上传");

        // ---------------------------------------------------------- 双向：真冲突
        SyncPlan.Plan conflict = SyncPlan.build(
                list(f("doc.txt", 100, T0 + 500)),
                list(f("doc.txt", 200, T0)),
                SyncPlan.MODE_BOTH, TOL);
        eq(1, conflict.conflict, "双向：大小不同且时间相近 → 冲突");
        eq(0, conflict.upload, "冲突不算上传");
        eq(1, conflict.work(), "冲突计入待办");

        // 目录 vs 文件同名
        SyncPlan.Plan typeClash = SyncPlan.build(
                list(d("thing")),
                list(f("thing", 5, T0)),
                SyncPlan.MODE_BOTH, TOL);
        eq(1, typeClash.conflict, "目录与文件同名 → 冲突");

        // 两边都是目录
        SyncPlan.Plan bothDir = SyncPlan.build(
                list(d("photos")),
                list(d("photos")),
                SyncPlan.MODE_BOTH, TOL);
        eq(1, bothDir.skip, "两边都是目录 → 跳过");

        // ---------------------------------------------------------- 上传模式的目录
        SyncPlan.Plan dirUp = SyncPlan.build(
                list(d("newdir")),
                list(),
                SyncPlan.MODE_UP, TOL);
        eq(1, dirUp.upload, "上传模式：本地独有目录也上传（建目录）");
        ok(dirUp.items.get(0).dir, "该条目标记为目录");

        SyncPlan.Plan dirDown = SyncPlan.build(
                list(d("localdir")),
                list(),
                SyncPlan.MODE_DOWN, TOL);
        eq(1, dirDown.localOnly, "下载模式：本地独有目录计为仅本地");

        // ---------------------------------------------------------- 文件与目录混合
        SyncPlan.Plan mix = SyncPlan.build(
                list(d("a"), f("a/x.txt", 10, T0), f("b.txt", 5, T0 + 100_000)),
                list(f("a/x.txt", 10, T0), f("b.txt", 5, T0)),
                SyncPlan.MODE_BOTH, TOL);
        eq(1, mix.upload, "混合：b.txt 本地更新 → 上传");
        eq(1, mix.skip, "混合：a/x.txt 相同 → 跳过");
        eq(0, mix.conflict, "混合：无冲突");

        // ---------------------------------------------------------- summary 文案
        ok(p1.summary().contains("跳过 1"), "summary 含跳过数");
        ok(rup.summary().contains("仅远端存在 1"), "summary 提示仅远端");
        ok(down.summary().contains("仅本地存在 1"), "summary 提示仅本地");

        // ---------------------------------------------------------- norm 路径
        eqs("a/b.txt", SyncPlan.norm("/a/b.txt"), "去掉前导斜杠");
        eqs("a/b.txt", SyncPlan.norm("a/b.txt/"), "去掉末尾斜杠");
        eqs("a/b.txt", SyncPlan.norm("a\\\\b.txt"), "反斜杠转正斜杠");
        eqs("a/b.txt", SyncPlan.norm("a//b.txt"), "折叠重复斜杠");
        eqs("", SyncPlan.norm(""), "空路径");
        eqs("", SyncPlan.norm(null), "null 路径");

        // ---------------------------------------------------------- 冲突副本命名
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(2026, java.util.Calendar.NOVEMBER, 10, 11, 30, 0);
        long ts = c.getTimeInMillis();
        eqs("a.conflict-20261110-1130.txt", SyncPlan.conflictName("a.txt", ts),
                "普通扩展名保留");
        eqs("dir/b.conflict-20261110-1130.md", SyncPlan.conflictName("dir/b.md", ts),
                "子目录保留");
        eqs("noext.conflict-20261110-1130", SyncPlan.conflictName("noext", ts),
                "无扩展名直接追加");
        eqs(".hidden.conflict-20261110-1130", SyncPlan.conflictName(".hidden", ts),
                "点开头文件按无扩展名处理");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
