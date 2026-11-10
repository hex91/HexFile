import com.hex91.file.Contrast;

/**
 * v1.83.0 颜色对比度回归。
 *
 * <p>背景：用户反馈「回收站顶栏按钮不显示」。根因是部分色板把 hfTopBar 与 hfAccent
 * 设成同一色值，而胶囊按钮用 accent 当文字色 —— 文字与背景同色，对比度 1.0，
 * 视觉上就是按钮没画出来。这里把「同色即隐形」与「ensure 之后必须达 AA」钉死。</p>
 */
public class ContrastTest {

    static int pass = 0, fail = 0;

    static void isTrue(boolean v, String msg) {
        if (v) {
            pass++;
            System.out.println("PASS  " + msg);
        } else {
            fail++;
            System.out.println("FAIL  " + msg);
        }
    }

    static void eq(int expect, int got, String msg) {
        if (expect == got) {
            pass++;
            System.out.println("PASS  " + msg + " = " + String.format("0x%08X", got));
        } else {
            fail++;
            System.out.println("FAIL  " + msg + " 期望 " + String.format("0x%08X", expect)
                    + " 实际 " + String.format("0x%08X", got));
        }
    }

    static void near(double expect, double got, double eps, String msg) {
        if (Math.abs(expect - got) <= eps) {
            pass++;
            System.out.println("PASS  " + msg + " = " + String.format("%.3f", got));
        } else {
            fail++;
            System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got);
        }
    }

    public static void main(String[] args) {
        // ---------------------------------------------------------- 基本性质
        near(21.0, Contrast.ratio(0xFF000000, 0xFFFFFFFF), 0.01, "纯黑对纯白 = 21");
        near(1.0, Contrast.ratio(0xFF1E88E5, 0xFF1E88E5), 0.0001, "同色对比度 = 1");
        near(Contrast.ratio(0xFF000000, 0xFFFFFFFF),
                Contrast.ratio(0xFFFFFFFF, 0xFF000000), 0.0001, "对比度对称");
        isTrue(Contrast.ratio(0xFF212121, 0xFFFFFFFF) > Contrast.ratio(0xFF212121, 0xFF9E9E9E),
                "白色在深灰上比中灰更清楚");

        // ---------------------------------------------------------- readableOn
        eq(0xFFFFFFFF, Contrast.readableOn(0xFF212121), "深底 → 白字");
        eq(0xFFFFFFFF, Contrast.readableOn(0xFF1E1E1E), "近黑底 → 白字");
        eq(0xFF000000, Contrast.readableOn(0xFFFFFFFF), "纯白底 → 黑字");
        eq(0xFF000000, Contrast.readableOn(0xFFE3A23C), "琥珀底 → 黑字");

        // ---------------------------------------------------------- 复现并修复原 bug
        // 这三套色板的 hfTopBar 与 hfAccent 完全相同（themes.xml 里 0x3E9AFB/0x61BC80/0xE3A23C）
        int[] bars = {0xFF3E9AFB, 0xFF61BC80, 0xFFE3A23C};
        String[] names = {"蓝 #3E9AFB", "绿 #61BC80", "琥珀 #E3A23C"};
        int topText = 0xFF212121;   // 这三套色板的 hfTopBarText 都是深灰

        for (int i = 0; i < bars.length; i++) {
            int bar = bars[i];
            near(1.0, Contrast.ratio(bar, bar), 0.0001,
                    names[i] + "：accent 文字画在同色顶栏上 = 完全隐形（旧实现根因）");
            int fixed = Contrast.ensure(bar, bar, Contrast.MIN_TEXT);
            isTrue(Contrast.ratio(bar, fixed) >= Contrast.MIN_TEXT,
                    names[i] + "：ensure 之后对比度达 AA（" + String.format("%.2f",
                            Contrast.ratio(bar, fixed)) + "）");
            isTrue(Contrast.ratio(bar, topText) >= Contrast.MIN_TEXT,
                    names[i] + "：顶栏前景色本身达 AA（新实现取它当按钮色）");
        }

        // 深色顶栏（默认黑/深灰两套）上白字必须够亮
        isTrue(Contrast.ratio(0xFF212121, 0xFFFFFFFF) >= Contrast.MIN_TEXT,
                "默认浅色主题顶栏：白字达 AA");
        isTrue(Contrast.ratio(0xFF1E1E1E, 0xFFFFFFFF) >= Contrast.MIN_TEXT,
                "暗色主题顶栏：白字达 AA");

        // 中等亮度顶栏（#2865A5 蓝、#407C54 绿）上白字仍要够
        isTrue(Contrast.ratio(0xFF2865A5, 0xFFFFFFFF) >= Contrast.MIN_TEXT,
                "深蓝顶栏 #2865A5：白字达 AA");
        isTrue(Contrast.ratio(0xFF407C54, 0xFFFFFFFF) >= Contrast.MIN_TEXT,
                "深绿顶栏 #407C54：白字达 AA");

        // ---------------------------------------------------------- ensure 行为
        eq(0xFFFFFFFF, Contrast.ensure(0xFF000000, 0xFFFFFFFF, Contrast.MIN_TEXT),
                "已达标 → 原样返回");
        eq(0xFFC62828, Contrast.ensure(0xFFFFFFFF, 0xFFC62828, 1.0),
                "阈值 1.0 视为已达标 → 不动危险色");
        int danger = Contrast.ensure(0xFF3E9AFB, 0xFFC62828, Contrast.MIN_TEXT);
        isTrue(Contrast.ratio(0xFF3E9AFB, danger) >= Contrast.MIN_TEXT,
                "危险色在蓝顶栏上被推到可辨");
        // 极端：中灰底 + 中灰字，任何方向都要能救回来
        int rescued = Contrast.ensure(0xFF808080, 0xFF7F7F7F, Contrast.MIN_TEXT);
        isTrue(Contrast.ratio(0xFF808080, rescued) >= Contrast.MIN_TEXT,
                "近同色中灰也能救到达标（" + String.format("%.2f",
                        Contrast.ratio(0xFF808080, rescued)) + "）");

        // ---------------------------------------------------------- mix
        eq(0xFFFFFFFF, Contrast.mix(0xFFFFFFFF, 0xFF000000, 0.0), "mix p=0 → a");
        eq(0xFF000000, Contrast.mix(0xFFFFFFFF, 0xFF000000, 1.0), "mix p=1 → b");
        eq(0xFF808080, Contrast.mix(0xFFFFFFFF, 0xFF000000, 0.5), "mix 中点 = 128 灰");
        eq(0x8000FF00, Contrast.mix(0x80FF0000, 0xFF00FF00, 1.0), "mix 保留 a 的 alpha");
        eq(0xFF1E88E5, Contrast.mix(0xFF1E88E5, 0xFFFFFFFF, 0.0), "mix p=0 原样");

        // ---------------------------------------------------------- luminance 边界
        near(0.0, Contrast.luminance(0xFF000000), 0.0001, "黑亮度 0");
        near(1.0, Contrast.luminance(0xFFFFFFFF), 0.0001, "白亮度 1");
        isTrue(Contrast.luminance(0xFF00FF00) > Contrast.luminance(0xFFFF0000),
                "绿的相对亮度高于红（G 权重 0.7152）");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
