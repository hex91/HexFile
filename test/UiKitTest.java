import com.hex91.file.UiKit;

/** v1.73.0 全局字号收敛：系统 fontScale 必须被限制在 [0.90, 1.10]，非法值回落 1.0 */
public class UiKitTest {
    static int n = 0;
    static void ok(String w) { n++; System.out.println("  ok: " + w); }

    static void eq(float got, float want, String what) {
        if (Math.abs(got - want) > 0.0001f) {
            throw new AssertionError(what + ": expect " + want + " got " + got);
        }
        ok(what + "=" + got);
    }

    public static void main(String[] a) {
        // 区间内原样保留（尊重用户「稍微大一点」的偏好）
        eq(UiKit.clampFontScale(1.0f), 1.0f, "1.0");
        eq(UiKit.clampFontScale(1.05f), 1.05f, "1.05");
        eq(UiKit.clampFontScale(0.95f), 0.95f, "0.95");
        // 上界：国产 ROM 常见的 1.15~2.0 全部压到 1.10
        eq(UiKit.clampFontScale(1.15f), UiKit.FONT_SCALE_MAX, "1.15");
        eq(UiKit.clampFontScale(1.3f), UiKit.FONT_SCALE_MAX, "1.3");
        eq(UiKit.clampFontScale(2.0f), UiKit.FONT_SCALE_MAX, "2.0");
        eq(UiKit.clampFontScale(9f), UiKit.FONT_SCALE_MAX, "9.0");
        // 下界：字号设得极小的设备兜底，避免文字看不清
        eq(UiKit.clampFontScale(0.5f), UiKit.FONT_SCALE_MIN, "0.5");
        eq(UiKit.clampFontScale(0.85f), UiKit.FONT_SCALE_MIN, "0.85");
        // 非法值按 1.0
        eq(UiKit.clampFontScale(0f), 1.0f, "0");
        eq(UiKit.clampFontScale(-1f), 1.0f, "-1");
        eq(UiKit.clampFontScale(Float.NaN), 1.0f, "NaN");

        // needsAdjust：差值超过 0.004 才重建 Context，避免无谓开销
        check(!UiKit.needsAdjust(1.0f, 1.0f), "no adjust when equal");
        check(!UiKit.needsAdjust(1.0f, 1.002f), "no adjust under threshold");
        check(UiKit.needsAdjust(1.3f, 1.1f), "adjust when clamped");
        check(UiKit.needsAdjust(0.5f, 0.9f), "adjust when raised");

        System.out.println("UIKIT PASS (" + n + ")");
    }

    static void check(boolean b, String w) {
        if (!b) throw new AssertionError(w);
        ok(w);
    }
}
