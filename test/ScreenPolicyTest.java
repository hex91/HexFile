import com.hex91.file.ScreenPolicy;

/**
 * v1.94.0 画面方向与等比比例。
 *
 * 覆盖三类结论：
 *   1) 方向三档循环与脏值收敛；
 *   2) 「横视频」的判据（宽>高，方形不算）；
 *   3) 等比比例只做 min/max，拉伸不参与 —— 保证「永不自动变形」。
 */
public class ScreenPolicyTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eqi(int expect, int got, String msg) {
        if (expect == got) { pass++; System.out.println("PASS  " + msg + " = " + got); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got); }
    }

    static void eqf(float expect, float got, String msg) {
        if (Math.abs(expect - got) < 0.0001f) {
            pass++; System.out.println("PASS  " + msg + " = " + got);
        } else {
            fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got);
        }
    }

    static void eqs(String expect, String got, String msg) {
        if (expect == null ? got == null : expect.equals(got)) {
            pass++; System.out.println("PASS  " + msg + " = " + got);
        } else {
            fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got);
        }
    }

    public static void main(String[] args) {
        eqi(0, ScreenPolicy.AUTO, "AUTO 常量");
        eqi(1, ScreenPolicy.PORTRAIT, "PORTRAIT 常量");
        eqi(2, ScreenPolicy.LANDSCAPE, "LANDSCAPE 常量");

        // ---------------------------------------------------------- 三档循环
        eqi(ScreenPolicy.PORTRAIT, ScreenPolicy.next(ScreenPolicy.AUTO), "自动 → 竖屏");
        eqi(ScreenPolicy.LANDSCAPE, ScreenPolicy.next(ScreenPolicy.PORTRAIT), "竖屏 → 横屏");
        eqi(ScreenPolicy.AUTO, ScreenPolicy.next(ScreenPolicy.LANDSCAPE), "横屏 → 自动");
        int m = ScreenPolicy.AUTO, n = 0;
        while (n < 3) { m = ScreenPolicy.next(m); n++; }
        eqi(ScreenPolicy.AUTO, m, "循环三次回到自动（不会卡死在某档）");

        // ---------------------------------------------------------- 脏值收敛
        eqi(ScreenPolicy.AUTO, ScreenPolicy.normalize(7), "越界 7 → 自动");
        eqi(ScreenPolicy.AUTO, ScreenPolicy.normalize(-3), "负数 → 自动");
        eqi(ScreenPolicy.PORTRAIT, ScreenPolicy.normalize(1), "合法值原样");
        eqi(ScreenPolicy.AUTO, ScreenPolicy.normalize(99), "脏值 99 先收敛到自动");
        eqi(ScreenPolicy.PORTRAIT, ScreenPolicy.next(99), "脏值步进结果：自动 → 竖屏");

        // ---------------------------------------------------------- 文案
        eqs("自动", ScreenPolicy.label(ScreenPolicy.AUTO), "自动 label");
        eqs("竖屏", ScreenPolicy.label(ScreenPolicy.PORTRAIT), "竖屏 label");
        eqs("横屏", ScreenPolicy.label(ScreenPolicy.LANDSCAPE), "横屏 label");
        eqs("自动", ScreenPolicy.label(42), "脏值 label 回落自动");
        eqs("自动（按视频比例）", ScreenPolicy.describe(ScreenPolicy.AUTO), "自动 describe");
        eqs("横屏（锁定）", ScreenPolicy.describe(ScreenPolicy.LANDSCAPE), "横屏 describe");

        // ---------------------------------------------------------- 是否横视频
        ok(ScreenPolicy.isWide(1920, 1080), "1920×1080 是横视频");
        ok(ScreenPolicy.isWide(3840, 2160), "4K 横视频");
        ok(!ScreenPolicy.isWide(1080, 1920), "1080×1920 是竖视频");
        ok(!ScreenPolicy.isWide(1080, 1080), "1:1 不算横视频");
        ok(!ScreenPolicy.isWide(0, 1080), "宽 0 不算");
        ok(!ScreenPolicy.isWide(1920, 0), "高 0 不算");
        ok(!ScreenPolicy.isWide(-1920, 1080), "负宽不算");
        ok(ScreenPolicy.isWide(1921, 1080), "宽只多 1px 仍算横（不设容差，宁可跟片源）");

        // ---------------------------------------------------------- 目标方向
        ok(ScreenPolicy.wantLandscape(ScreenPolicy.AUTO, 1920, 1080, false),
                "AUTO + 横视频 + 当前竖屏 → 要横屏（用户报的核心问题）");
        ok(!ScreenPolicy.wantLandscape(ScreenPolicy.AUTO, 1080, 1920, true),
                "AUTO + 竖视频 + 当前横屏 → 回竖屏");
        ok(ScreenPolicy.wantLandscape(ScreenPolicy.AUTO, 0, 0, true),
                "AUTO + 尺寸未知 + 当前横屏 → 保持横屏（不闪）");
        ok(!ScreenPolicy.wantLandscape(ScreenPolicy.AUTO, 0, 0, false),
                "AUTO + 尺寸未知 + 当前竖屏 → 保持竖屏");
        ok(ScreenPolicy.wantLandscape(ScreenPolicy.LANDSCAPE, 1080, 1920, false),
                "锁定横屏时竖视频也要横（用户说了算）");
        ok(!ScreenPolicy.wantLandscape(ScreenPolicy.PORTRAIT, 1920, 1080, true),
                "锁定竖屏时横视频也竖着放（等比缩小，不裁不变形）");
        ok(!ScreenPolicy.wantLandscape(ScreenPolicy.PORTRAIT, 0, 0, false), "锁定竖屏 + 未知尺寸");
        ok(ScreenPolicy.wantLandscape(ScreenPolicy.LANDSCAPE, 0, 0, false), "锁定横屏 + 未知尺寸");
        ok(!ScreenPolicy.wantLandscape(ScreenPolicy.AUTO, 1080, 1080, true),
                "AUTO + 方形视频 + 当前横屏 → 竖屏（1:1 竖着更满）");

        // ---------------------------------------------------------- 等比比例
        // 手机竖屏 1080×1920 里放 1920×1080 的片子
        eqf(0.5625f, ScreenPolicy.fitScale(1920, 1080, 1080, 1920), "FIT：横屏片在竖屏窗口 → 0.5625");
        eqf(1.7777f, ScreenPolicy.coverScale(1920, 1080, 1080, 1920), "CROP：同一场景 → 1.777 铺满");
        eqf(0.5625f, ScreenPolicy.scaleFor(false, 1920, 1080, 1080, 1920), "scaleFor(FIT) 同 fitScale");
        eqf(1.7777f, ScreenPolicy.scaleFor(true, 1920, 1080, 1080, 1920), "scaleFor(CROP) 同 coverScale");

        // 横屏窗口 1920×1080 放 1080×1920 的竖视频
        eqf(0.5625f, ScreenPolicy.fitScale(1080, 1920, 1920, 1080), "竖视频在横屏窗口 → 同样 0.5625");
        eqf(1.7777f, ScreenPolicy.coverScale(1080, 1920, 1920, 1080), "竖视频铺满 → 1.777");

        // 比例相同的场景：铺满与适配等价（不裁边也不留黑），系数就是窗口/视频比
        eqf(0.6667f, ScreenPolicy.fitScale(1920, 1080, 1280, 720), "16:9 进同比例 1280×720 → 0.6667");
        eqf(ScreenPolicy.fitScale(1920, 1080, 1280, 720),
                ScreenPolicy.coverScale(1920, 1080, 1280, 720),
                "同比例下 FIT 与 CROP 相等（既不裁边也不留黑）");

        // 不合法参数：FIT/CROP 兜底 1，scaleFor 返回 0（调用方保持满屏）
        eqf(1f, ScreenPolicy.fitScale(0, 1080, 1080, 1920), "宽 0 → 1.0 兜底");
        eqf(1f, ScreenPolicy.coverScale(1920, 0, 1080, 1920), "高 0 → 1.0 兜底");
        eqf(0f, ScreenPolicy.scaleFor(false, 1920, 1080, 0, 0), "容器 0 → 0（交给调用方处理）");

        // 等比不变形的核心不变式：FIT 的画面宽高比 == 源宽高比
        for (int i = 0; i < 5; i++) {
            int vw = 640 * (i + 1), vh = 360 * (i + 1);
            int cw = 1080, ch = 1920;
            float s = ScreenPolicy.scaleFor(false, vw, vh, cw, ch);
            float outW = vw * s, outH = vh * s;
            ok(Math.abs(outW / outH - (float) vw / vh) < 0.001f,
                    "FIT 输出比例 == 源比例 " + vw + "×" + vh);
            ok(outW <= cw + 1 && outH <= ch + 1, "FIT 输出不超出窗口 " + Math.round(outW) + "×" + Math.round(outH));
        }

        // 极端超宽片源（21:9）在竖屏窗口：适配后宽度必须等于屏宽
        float s = ScreenPolicy.scaleFor(false, 2560, 1080, 1080, 1920);
        ok(Math.abs(1080 - 2560 * s) < 1.5f, "21:9 在竖屏：宽度贴满屏宽（" + Math.round(2560 * s) + "）");
        ok(1080 * s < 1920, "高度不超窗口（留黑边而不是裁切）");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
