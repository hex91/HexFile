import com.hex91.file.UiDecor;

/**
 * v1.75.0 视觉组件的颜色计算回归。
 *
 * <p>半透明按钮底、卡片底、状态色全靠这几个纯函数算出来。它们一旦算错，
 * 表现是「暗色主题下卡片比背景还亮」「按钮文字和底完全同色看不见」这类
 * 肉眼才发现的问题，所以在桌面先把边界值钉住。</p>
 */
public class UiDecorTest {

    static int pass = 0, fail = 0;

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

    static void isTrue(boolean v, String msg) {
        if (v) {
            pass++;
            System.out.println("PASS  " + msg);
        } else {
            fail++;
            System.out.println("FAIL  " + msg);
        }
    }

    public static void main(String[] args) {
        int accent = 0xFF1E88E5;

        // ---------------- alpha：换透明度但不改 RGB
        eq(0x241E88E5, UiDecor.alpha(accent, 0x24), "alpha 替换透明通道、保留 RGB");
        eq(0xFF1E88E5, UiDecor.alpha(accent, 255), "alpha=255 完全不透明");
        eq(0x001E88E5, UiDecor.alpha(accent, 0), "alpha=0 全透明但仍保留 RGB");
        eq(0xFF1E88E5, UiDecor.alpha(accent, 999), "alpha 上界夹取（>255 按 255）");
        eq(0x001E88E5, UiDecor.alpha(accent, -5), "alpha 下界夹取（<0 按 0）");
        eq(0x24FFFFFF, UiDecor.alpha(0xFFFFFFFF, 0x24), "白色也能正确套透明度");
        eq(0x24000000, UiDecor.alpha(0xFF000000, 0x24), "黑色也能正确套透明度");

        // ---------------- luma：亮度用于判断叠加色
        eq(255, UiDecor.luma(0xFFFFFFFF), "白色亮度 255");
        eq(0, UiDecor.luma(0xFF000000), "黑色亮度 0");
        eq(149, UiDecor.luma(0xFF00FF00), "纯绿亮度 149（0.587 权重取整，不是 255）");
        isTrue(UiDecor.luma(0xFF808080) > 100 && UiDecor.luma(0xFF808080) < 155, "中灰亮度在中段");

        // ---------------- blend：卡片底在亮/暗主题下的叠加方向
        eq(0xFF000000, UiDecor.blend(0xFF000000, 0xFFFFFFFF, 0),
                "percent=0 原样返回底色");
        eq(0xFF7F7F7F, UiDecor.blend(0xFF000000, 0xFFFFFFFF, 50), "50% 混合得到中灰（127.5 截断为 127）");
        eq(0xFFFFFFFF, UiDecor.blend(0xFF000000, 0xFFFFFFFF, 100), "100% 混合等于叠加色");
        eq(0xFF000000, UiDecor.blend(0xFF000000, 0xFFFFFFFF, -10), "负百分比按 0 处理");
        eq(0xFFFFFFFF, UiDecor.blend(0xFF000000, 0xFFFFFFFF, 300), "百分比上限夹取到 100");
        eq(0xFF0F0F0F, UiDecor.blend(0xFF000000, 0xFFFFFFFF, 6), "低百分比只提亮一点点");
        // 同色叠加不产生偏移
        eq(0xFF123456, UiDecor.blend(0xFF123456, 0xFF123456, 70), "同色混合结果不变");

        // ---------------- 卡片底在暗色主题下不能比背景更亮
        int darkBg = 0xFF101010, darkSurface = 0xFF1C1C1C;
        int darkCard = UiDecor.blend(darkBg, darkSurface, 55);
        isTrue(UiDecor.luma(darkCard) >= UiDecor.luma(darkBg), "暗色卡片不比背景更暗");
        isTrue(UiDecor.luma(darkCard) <= UiDecor.luma(darkSurface) + 1, "暗色卡片不超过 surface 亮度");

        int lightBg = 0xFFFFFFFF, lightSurface = 0xFFF5F5F5;
        int lightCard = UiDecor.blend(lightBg, lightSurface, 70);
        isTrue(UiDecor.luma(lightCard) <= 255 && UiDecor.luma(lightCard) >= UiDecor.luma(lightSurface),
                "亮色卡片亮度落在 surface 与背景之间");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
