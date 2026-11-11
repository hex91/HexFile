import com.hex91.file.PlaySpeed;

/** v1.91.0 倍速档位表：上下限、吸附、边界步进、显示文案。 */
public class PlaySpeedTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
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
        eqf(3.0f, PlaySpeed.MAX, "上限 3 倍（需求硬约束）");
        eqf(0.25f, PlaySpeed.MIN, "下限 0.25 倍");

        // ---------------------------------------------------------- clamp
        eqf(3.0f, PlaySpeed.clamp(9f), "超过上限被收敛到 3.0");
        eqf(3.0f, PlaySpeed.clamp(3.0001f), "极限上溢也是 3.0");
        eqf(0.25f, PlaySpeed.clamp(0.01f), "低于下限收敛到 0.25");
        eqf(1.0f, PlaySpeed.clamp(0f), "0 → 1.0（不是 0.25，0 倍无法播放）");
        eqf(1.0f, PlaySpeed.clamp(-2f), "负数 → 1.0");
        eqf(1.0f, PlaySpeed.clamp(Float.NaN), "NaN → 1.0");
        eqf(2.0f, PlaySpeed.clamp(2.0f), "合法值原样返回");

        // ---------------------------------------------------------- 吸附
        eqf(1.0f, PlaySpeed.snap(1.02f), "1.02 吸附到 1.0");
        eqf(1.5f, PlaySpeed.snap(1.4f), "1.4 吸附到 1.5");
        eqf(2.0f, PlaySpeed.snap(2.2f), "2.2 吸附到 2.0");
        eqf(0.25f, PlaySpeed.snap(0.1f), "过小吸附到最低档");
        eqf(3.0f, PlaySpeed.snap(99f), "过大吸附到最高档");
        eqf(3f, PlaySpeed.indexOf(1.0f), "1.0 的下标是 3");

        // ---------------------------------------------------------- 步进
        eqf(1.25f, PlaySpeed.step(1.0f, 1), "加速一档：1.0 → 1.25");
        eqf(0.75f, PlaySpeed.step(1.0f, -1), "减速一档：1.0 → 0.75");
        eqf(2.0f, PlaySpeed.step(1.75f, 1), "1.75 → 2.0");
        eqf(3.0f, PlaySpeed.step(3.0f, 1), "已在最高档：不动（不循环）");
        eqf(0.25f, PlaySpeed.step(0.25f, -1), "已在最低档：不动（不循环）");
        eqf(1.0f, PlaySpeed.step(1.0f, 0), "方向 0：不变");
        eqf(1.75f, PlaySpeed.step(1.4f, 1), "未对齐的入参先吸附到 1.5 再步进 → 1.75");
        // 从 1.0 连点到 3.0 需要的次数（UI 上不能太远）
        float s = 1.0f;
        int n = 0;
        while (s < 3.0f && n < 20) { s = PlaySpeed.step(s, 1); n++; }
        eqf(3.0f, s, "连点可达最高档");
        ok(n <= 6, "到 3 倍最多 6 次（实际 " + n + " 次）");

        // ---------------------------------------------------------- 文案
        eqs("1.0×", PlaySpeed.label(1.0f), "1 倍显示 1.0×");
        eqs("1.5×", PlaySpeed.label(1.5f), "1.5 倍");
        eqs("1.25×", PlaySpeed.label(1.25f), "1.25 倍保留两位");
        eqs("3.0×", PlaySpeed.label(3.0f), "3 倍");
        eqs("1.0×", PlaySpeed.label(0f), "非法值回落 1.0×");

        // ---------------------------------------------------------- 是否改动过
        ok(!PlaySpeed.isModified(1.0f), "1.0 不算加速");
        ok(!PlaySpeed.isModified(1.0005f), "抖动不算加速");
        ok(PlaySpeed.isModified(1.25f), "1.25 算加速");
        ok(PlaySpeed.isModified(0.5f), "慢放也算改动");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
