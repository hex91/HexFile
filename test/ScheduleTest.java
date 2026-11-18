import com.hex91.file.sched.ScheduleCodec;
import com.hex91.file.sched.ScheduleItem;
import com.hex91.file.sched.ScheduleRule;
import com.hex91.file.sched.ScheduleRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * v2.17.0（F-23）：定时任务用例。
 *
 * <p>三块各自会因为不同原因悄悄坏掉，因此分开钉：</p>
 * <ol>
 *   <li><b>规则与下次时间</b>：算错就是「到点不执行」或「一分钟内执行十次」，两者都不会崩；</li>
 *   <li><b>编解码</b>：一条任务的路径里若混进制表符，不转义就会被切成两半，
 *       读回来是目标路径被截断的任务 —— 定时清空的可能是错的目录；</li>
 *   <li><b>执行</b>：清空/过期/压缩都动真实文件，用临时目录跑真包，看删对了没有。</li>
 * </ol>
 */
public class ScheduleTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eq(int want, int got, String msg) {
        boolean v = want == got;
        ok(v, msg + (v ? "" : "（期望 " + want + "，实际 " + got + "）"));
    }

    static void eq(long want, long got, String msg) {
        boolean v = want == got;
        ok(v, msg + (v ? "" : "（期望 " + want + "，实际 " + got + "）"));
    }

    static void eq(String want, String got, String msg) {
        boolean v = want == null ? got == null : want.equals(got);
        ok(v, msg + (v ? "" : "（期望 " + want + "，实际 " + got + "）"));
    }

    public static void main(String[] args) throws Exception {
        rules();
        codec();
        runner();
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 规则

    static void rules() {
        ok(ScheduleRule.valid("daily 23:00"), "规则: daily 23:00 合法");
        ok(ScheduleRule.valid("weekly 1,3,5 07:30"), "规则: weekly 多天 合法");
        ok(ScheduleRule.valid("  daily   9:05 "), "规则: 多余空白与一位小时被容忍");
        ok(!ScheduleRule.valid("daily 24:00"), "规则: 24:00 非法");
        ok(!ScheduleRule.valid("daily 23:60"), "规则: 23:60 非法");
        ok(!ScheduleRule.valid("daily"), "规则: 只有 daily 非法");
        ok(!ScheduleRule.valid("weekly 0 10:00"), "规则: 星期 0 非法");
        ok(!ScheduleRule.valid("weekly 8 10:00"), "规则: 星期 8 非法");
        ok(!ScheduleRule.valid("monthly 1 10:00"), "规则: 不认识的周期非法");
        ok(!ScheduleRule.valid("daily 23-00"), "规则: 缺冒号非法");
        ok(!ScheduleRule.valid(null), "规则: null 非法");
        ok(!ScheduleRule.valid(""), "规则: 空串非法");

        // 以某个已知时刻为基准造「今天 23:00」
        long base = at(2026, 10, 18, 22, 0);          // 2026-11-18 22:00（周三）
        long today23 = at(2026, 10, 18, 23, 0);
        long tomorrow23 = at(2026, 10, 19, 23, 0);
        eq(today23, ScheduleRule.nextRun("daily 23:00", base),
                "nextRun: daily 22:00 时算出今天 23:00");
        eq(tomorrow23, ScheduleRule.nextRun("daily 23:00", today23),
                "nextRun: 恰好 23:00:00.000 时推到明天（否则一分钟内反复触发）");
        eq(at(2026, 10, 19, 0, 0), ScheduleRule.nextRun("daily 00:00", at(2026, 10, 18, 0, 0)),
                "nextRun: 午夜规则在 00:00 整点时指向明天 00:00");
        eq(at(2026, 10, 19, 3, 0), ScheduleRule.nextRun("daily 03:00", at(2026, 10, 18, 23, 59)),
                "nextRun: 跨零点（23:59 → 次日 03:00）");

        // 2026-11-18 是周三 → dow=3
        eq(3, ScheduleRule.dow(cal(2026, 10, 18)), "dow: 2026-11-18 是周三（3）");
        eq(7, ScheduleRule.dow(cal(2026, 10, 22)), "dow: 2026-11-22 是周日（7）");
        eq(at(2026, 10, 18, 23, 0), ScheduleRule.nextRun("weekly 3 23:00", base),
                "nextRun: weekly 当天未过点 → 今天");
        eq(at(2026, 10, 20, 23, 0), ScheduleRule.nextRun("weekly 5 23:00", base),
                "nextRun: weekly 周五 → 后天");
        eq(at(2026, 10, 22, 23, 0), ScheduleRule.nextRun("weekly 7 23:00", base),
                "nextRun: weekly 周日从周三算 → 本周日 11-22");
        // 周日 23:30 已过 → 下周日 11-29
        eq(at(2026, 10, 29, 23, 0),
                ScheduleRule.nextRun("weekly 7 23:00", at(2026, 10, 22, 23, 30)),
                "nextRun: 周日已过点 → 下周日 11-29");
        eq(at(2026, 10, 23, 7, 30),
                ScheduleRule.nextRun("weekly 1,2 07:30", at(2026, 10, 18, 9, 0)),
                "nextRun: 表里是周一周二、今天周三 → 下周一 11-23");
        eq(-1, ScheduleRule.nextRun("bogus 1", base), "nextRun: 非法规则返回 -1");

        // prevRun：判断「这一轮跑过没有」的依据
        eq(at(2026, 10, 17, 23, 0), ScheduleRule.prevRun("daily 23:00", base),
                "prevRun: 今天 22:00 → 昨天 23:00（今天那次还没到）");
        eq(today23, ScheduleRule.prevRun("daily 23:00", today23),
                "prevRun: 恰好 23:00 → 就是今天这次（与 nextRun 用相反的边界）");
        eq(at(2026, 10, 18, 23, 0), ScheduleRule.prevRun("daily 23:00", at(2026, 10, 18, 23, 59)),
                "prevRun: 23:59 → 仍是今天 23:00");
        eq(at(2026, 10, 11, 23, 0), ScheduleRule.prevRun("weekly 3 23:00", base),
                "prevRun: weekly 周三 22:00 → 上周三");
        eq(at(2026, 10, 18, 23, 0), ScheduleRule.prevRun("weekly 3 23:00", at(2026, 10, 18, 23, 30)),
                "prevRun: weekly 周三 23:30 → 本周三");
        eq(-1, ScheduleRule.prevRun("bad", base), "prevRun: 非法规则返回 -1");
        eq("1,3,5", ScheduleRule.weekdays(0b0010101), "weekdays: 掩码回填文本");
        eq(0b0010101, ScheduleRule.parse("weekly 1,3,5 10:00").mask, "parse: 掩码位置正确");
    }

    // ---------------------------------------------------------------- 编解码

    static void codec() {
        List<ScheduleItem> in = new ArrayList<>();
        ScheduleItem a = new ScheduleItem();
        a.id = 1; a.enabled = true; a.rule = "daily 23:00";
        a.action = ScheduleItem.ACTION_CLEAN; a.target = "/sdcard/Download/cache";
        a.lastRun = 1700000000000L; a.lastResult = "已清空 12 项";
        in.add(a);

        ScheduleItem b = new ScheduleItem();
        b.id = 2; b.enabled = false; b.rule = "weekly 1,4 07:30";
        b.action = ScheduleItem.ACTION_EXPIRE; b.target = "/sdcard/DCIM\todd\\name\nline";
        b.param = 30; b.lastResult = "";
        in.add(b);

        String text = ScheduleCodec.encode(in);
        List<ScheduleItem> out = ScheduleCodec.decode(text);
        eq(2, out.size(), "codec: 往返两条");
        if (out.size() == 2) {
            eq("daily 23:00", out.get(0).rule, "codec: 规则原样回来");
            eq("/sdcard/Download/cache", out.get(0).target, "codec: 路径原样回来");
            eq(1700000000000L, out.get(0).lastRun, "codec: lastRun 精确保留（long 不是 int）");
            ok(out.get(0).enabled, "codec: enabled=true 保留");
            ok(!out.get(1).enabled, "codec: enabled=false 保留");
            eq(30, out.get(1).param, "codec: param 保留");
            eq("/sdcard/DCIM\todd\\name\nline", out.get(1).target,
                    "codec: 路径里的制表符/反斜杠/换行都完整（不转义就会被切成两条）");
        }

        eq(0, ScheduleCodec.decode("").size(), "codec: 空文本 → 空表");
        eq(0, ScheduleCodec.decode(null).size(), "codec: null → 空表");
        eq(0, ScheduleCodec.decode("garbage\n1\t1").size(), "codec: 无格式头 → 空表");
        eq(0, ScheduleCodec.decode("hexfile-schedule-1\n1\t1\tclean").size(),
                "codec: 列数不足的半截行被丢弃");
        List<ScheduleItem> partial = ScheduleCodec.decode(
                "hexfile-schedule-1\n1\t1\tclean\t0\t0\tdaily 23:00\t/sdcard/a\t\n"
                        + "2\t1\tnonsense\t0\t0\tdaily 23:00\t/sdcard/b\t\n");
        eq(1, partial.size(), "codec: 认不出的动作被跳过（不执行也不写回）");
        eq(3, ScheduleCodec.nextId(out), "codec: nextId = 最大 id + 1");
        eq(1, ScheduleCodec.nextId(new ArrayList<ScheduleItem>()), "codec: 空表 nextId = 1");
    }

    // ---------------------------------------------------------------- 执行

    static void runner() throws Exception {
        File root = new File("test/tmp_sched");
        deleteTree(root);
        mkdirs(root);

        // clean
        File c = mkdirs(new File(root, "clean"));
        write(new File(c, "a.txt"), "a");
        write(new File(c, "sub/b.txt"), "b");
        ScheduleItem it = new ScheduleItem();
        it.action = ScheduleItem.ACTION_CLEAN;
        it.target = c.getAbsolutePath();
        ScheduleRunner.Result r = ScheduleRunner.run(it);
        ok(r.ok(), "clean: 全部删掉时无错误");
        eq(2, r.done, "clean: 删掉 2 项（文件 + 子目录）");
        ok(c.isDirectory(), "clean: 目录本身保留");
        eq(0, c.listFiles() == null ? -1 : c.listFiles().length, "clean: 目录已空");

        // 危险目标：定时清空最坏的失败是「清错了目录」
        ok(ScheduleRunner.isDangerous("/"), "dangerous: / 被拒");
        ok(ScheduleRunner.isDangerous("  /  "), "dangerous: 带空白的 / 被拒");
        ok(ScheduleRunner.isDangerous("/system"), "dangerous: /system 被拒");
        ok(ScheduleRunner.isDangerous("/system/"), "dangerous: 尾斜杠不影响判断");
        ok(ScheduleRunner.isDangerous("/data"), "dangerous: /data 本身被拒");
        ok(ScheduleRunner.isDangerous("/sdcard"), "dangerous: 存储根被拒");
        ok(ScheduleRunner.isDangerous("/storage/emulated"), "dangerous: /storage/emulated 被拒");
        ok(!ScheduleRunner.isDangerous("/data/local/tmp"), "dangerous: 系统挂载点的子目录放行");
        ok(!ScheduleRunner.isDangerous("/sdcard/Download/cache"), "dangerous: 普通目录放行");
        ok(!ScheduleRunner.isDangerous("/storage/emulated/0/DCIM"), "dangerous: 存储子目录放行");
        ok(ScheduleRunner.isDangerous(null), "dangerous: null 被拒");
        ScheduleItem dm = new ScheduleItem();
        dm.action = ScheduleItem.ACTION_CLEAN;
        dm.target = "/";
        ok(!ScheduleRunner.run(dm).ok(), "dangerous: 执行阶段也挡（不靠 UI 单点把关）");

        // 目标不存在 / 不是目录
        ScheduleItem bad = new ScheduleItem();
        bad.action = ScheduleItem.ACTION_CLEAN;
        bad.target = new File(root, "nope").getAbsolutePath();
        ok(!ScheduleRunner.run(bad).ok(), "clean: 目标不存在 → 报错而不是静默成功");
        File file = write(new File(root, "afile.txt"), "x");
        bad.target = file.getAbsolutePath();
        ok(!ScheduleRunner.run(bad).ok(), "clean: 目标是文件 → 报错");
        bad.target = "";
        ok(!ScheduleRunner.run(bad).ok(), "clean: 没设置目标 → 报错");

        // expire
        long now = System.currentTimeMillis();
        File e = mkdirs(new File(root, "expire"));
        File old1 = write(new File(e, "old.txt"), "old");
        File old2 = write(new File(e, "sub/old2.txt"), "old2");
        File fresh = write(new File(e, "new.txt"), "new");
        ok(old1.setLastModified(now - 10L * 86400000L), "expire: 造一个 10 天前的文件");
        ok(old2.setLastModified(now - 20L * 86400000L), "expire: 造一个 20 天前的深层文件");
        ScheduleItem ex = new ScheduleItem();
        ex.action = ScheduleItem.ACTION_EXPIRE;
        ex.target = e.getAbsolutePath();
        ex.param = 7;
        ScheduleRunner.Result er = ScheduleRunner.run(ex, now, null);
        ok(er.ok(), "expire: 无错误");
        eq(2, er.done, "expire: 删掉 2 个过期文件");
        ok(!old1.exists(), "expire: 10 天前的文件没了");
        ok(!old2.exists(), "expire: 深层过期文件也没了");
        ok(fresh.exists(), "expire: 新文件保留");
        ok(e.isDirectory() && new File(e, "sub").isDirectory(), "expire: 空目录本身不删");

        ex.param = 0;
        ok(!ScheduleRunner.run(ex, now, null).ok(), "expire: 保留 0 天视为配置错误（不是「全删」）");

        // zip
        File z = mkdirs(new File(root, "pack"));
        write(new File(z, "1.txt"), "one");
        write(new File(z, "2.txt"), "two");
        write(new File(z, "d/3.txt"), "three");
        ScheduleItem zt = new ScheduleItem();
        zt.action = ScheduleItem.ACTION_ZIP;
        zt.target = z.getAbsolutePath();
        ScheduleRunner.Result zr = ScheduleRunner.run(zt);
        ok(zr.ok(), "zip: 压缩成功");
        eq(3, zr.done, "zip: 打包 3 个顶层项");
        File out = new File(root, "pack.zip");
        ok(out.isFile() && out.length() > 0, "zip: 生成 pack.zip（放在父目录）");
        ZipFile zf = new ZipFile(out);
        try {
            // 4 = 1.txt + 2.txt + d/ 目录条目 + d/3.txt（目录条目也算一条，别按文件数算）
            eq(4, zf.size(), "zip: 包内 4 个条目（2 个文件 + 目录 + 子目录里的文件）");
        } finally {
            zf.close();
        }

        // 空目录压缩：明确报错，不要生成一个 0 字节的包骗人
        File empty = mkdirs(new File(root, "empty"));
        zt.target = empty.getAbsolutePath();
        ok(!ScheduleRunner.run(zt).ok(), "zip: 空目录 → 报错（不生成空包）");

        deleteTree(root);
    }

    // ---------------------------------------------------------------- 工具

    static Calendar cal(int y, int month0, int d) {
        Calendar c = Calendar.getInstance();
        c.set(y, month0, d, 12, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    static long at(int y, int month0, int d, int h, int m) {
        Calendar c = Calendar.getInstance();
        c.set(y, month0, d, h, m, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static File mkdirs(File d) {
        if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory())
            throw new IllegalStateException("建目录失败: " + d);
        return d;
    }

    static File write(File f, String text) throws Exception {
        File p = f.getParentFile();
        if (p != null) mkdirs(p);
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(text.getBytes("UTF-8"));
        } finally {
            o.close();
        }
        return f;
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        if (!f.delete()) System.out.println("  (清理失败: " + f + ")");
    }
}
