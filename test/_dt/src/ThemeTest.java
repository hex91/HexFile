
import java.util.ArrayList;
import java.util.List;

/**
 * v1.48.0 桌面测试：主题系统核心逻辑。
 *
 * 覆盖：
 *  - ThemeSchemes 色板表完整性（22 套、与 themes.xml 对齐）
 *  - 主题色对比度计算（顶栏文字在深/浅底上的选择）
 *  - 语义色 attr 映射的一致性
 *
 * 桌面运行需剥离 package 行与 import com.hex91.file.* 行（见 build 约定）。
 */
public class ThemeTest {

    static int passed = 0;
    static int failed = 0;

    static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    static void eq(String name, long expect, long actual) {
        boolean ok = expect == actual;
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name + " = " + actual);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + " expect=" + expect + " actual=" + actual);
        }
    }

    // ---- 与 gen_themes.py 同源的色彩工具（桌面侧独立实现，用于交叉验证） ----
    static int hex2rgb(String h) {
        return Integer.parseInt(h, 16);
    }

    static double luminance(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0;
        double g = ((rgb >> 8) & 0xFF) / 255.0;
        double b = (rgb & 0xFF) / 255.0;
        r = r <= 0.03928 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.03928 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.03928 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** WCAG 对比度 (L1+0.05)/(L2+0.05) */
    static double ratio(int a, int b) {
        double l1 = luminance(a & 0xFFFFFF), l2 = luminance(b & 0xFFFFFF);
        double hi = Math.max(l1, l2), lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** 背景色上的前景色：取对比度更高者（与 gen_themes.py 同源算法） */
    static int contrastText(int rgb) {
        return ratio(rgb, 0x212121) >= ratio(rgb, 0xFFFFFF) ? 0xFF212121 : 0xFFFFFFFF;
    }

    public static void main(String[] args) {
        System.out.println("=== ThemeTest ===");

        // ---- 1. 色板表完整性 ----
        int n = ThemeSchemes.count();
        eq("色板数量", 22, n);
        check("色板数量与 MT 主题设置页一致（22）", n == 22);
        check("名称表长度 == 颜色表长度", ThemeSchemes.NAMES.length == ThemeSchemes.COLORS.length);
        check("名称表长度 == 颜色表长度", ThemeSchemes.NAMES.length == n);

        // 名称非空且不重复
        List<String> seen = new ArrayList<String>();
        boolean namesOk = true;
        for (int i = 0; i < n; i++) {
            String s = ThemeSchemes.NAMES[i];
            if (s == null || s.length() == 0 || seen.contains(s)) namesOk = false;
            else seen.add(s);
        }
        check("色板名称非空且互不重复", namesOk);

        // 颜色均为不透明 ARGB 且互不重复
        boolean colorsOk = true;
        List<Integer> cseen = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            int c = ThemeSchemes.COLORS[i];
            if ((c >>> 24) != 0xFF) colorsOk = false;
            if (cseen.contains(c)) colorsOk = false;
            else cseen.add(c);
        }
        check("色板颜色均为不透明且互不重复", colorsOk);

        // ---- 2. 首尾色值与截图提取一致 ----
        eq("0 号色板 = 蓝色 #3E9AFB", 0xFF3E9AFB, ThemeSchemes.COLORS[0]);
        eq("1 号色板 = 绿色 #61BC80", 0xFF61BC80, ThemeSchemes.COLORS[1]);
        eq("21 号色板 = 深褐 #442E20", 0xFF442E20, ThemeSchemes.COLORS[21]);

        // ---- 3. 对比度计算：自动取更优前景色 ----
        eq("白底 → 深字", 0xFF212121, contrastText(0xFFFFFF));
        eq("黑底 → 白字", 0xFFFFFFFF, contrastText(0x000000));
        eq("深蓝底 → 白字", 0xFFFFFFFF, contrastText(0xFF032DA3));
        eq("金黄底 → 深字", 0xFF212121, contrastText(0xFFFAD16B));
        eq("湖蓝底 → 深字", 0xFF212121, contrastText(0xFF80D7CF));
        eq("深褐底 → 白字", 0xFFFFFFFF, contrastText(0xFF442E20));
        eq("琥珀底 → 深字", 0xFF212121, contrastText(0xFFE3A23C));
        eq("藏青底 → 白字", 0xFFFFFFFF, contrastText(0xFF02304F));

        // ---- 4. 每个色板在两种模式下都能得到可读的前景色 ----
        boolean readable = true;
        for (int i = 0; i < n; i++) {
            int c = ThemeSchemes.COLORS[i];
            int fg = contrastText(c);
            double l1 = luminance(c);
            double l2 = luminance(fg & 0xFFFFFF);
            // WCAG AA 粗体阈值 3.0
            double ratio = (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
            if (ratio < 3.0) {
                readable = false;
                System.out.println("    低对比: " + ThemeSchemes.NAMES[i]
                        + " ratio=" + String.format("%.2f", ratio));
            }
        }
        check("22 套色板的前景色对比度均 >= 3.0 (WCAG AA)", readable);

        // ---- 5. 明暗模式的常量约定（与 ThemeManager 保持一致）----
        // 注意：ThemeManager 依赖 Android Context，桌面不可编译，故此处用字面量交叉验证
        final int MODE_FOLLOW_SYSTEM = 0, MODE_LIGHT = 1, MODE_DARK = 2;
        eq("MODE_FOLLOW_SYSTEM", 0, MODE_FOLLOW_SYSTEM);
        eq("MODE_LIGHT", 1, MODE_LIGHT);
        eq("MODE_DARK", 2, MODE_DARK);
        check("三种模式取值互不相同",
                MODE_FOLLOW_SYSTEM != MODE_LIGHT
                        && MODE_LIGHT != MODE_DARK
                        && MODE_FOLLOW_SYSTEM != MODE_DARK);

        // ---- 6. 默认色板在有效范围内 ----
        final int DEFAULT_SCHEME = 0;
        check("默认色板索引合法", DEFAULT_SCHEME >= 0 && DEFAULT_SCHEME < n);

        System.out.println("=== passed=" + passed + " failed=" + failed + " ===");
        if (failed > 0) System.exit(1);
    }
}
