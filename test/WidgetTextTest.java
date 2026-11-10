import com.hex91.file.WidgetText;

/**
 * v1.85.0 小组件的容量文案与进度。
 *
 * <p>卡片只有 4 格宽，顶行还得塞下应用名和刷新按钮，
 * 所以「剩 12.3G / 128G」这种极端压缩的写法必须逐字钉住——
 * 多一个小数点就会把刷新按钮挤出屏幕。</p>
 */
public class WidgetTextTest {

    static int pass = 0, fail = 0;
    static final long G = 1024L * 1024 * 1024;
    static final long M = 1024L * 1024;

    static void eqs(String expect, String got, String msg) {
        if (expect == null ? got == null : expect.equals(got)) {
            pass++;
            System.out.println("PASS  " + msg + " = " + got);
        } else {
            fail++;
            System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got);
        }
    }

    static void eq(int expect, int got, String msg) {
        if (expect == got) {
            pass++;
            System.out.println("PASS  " + msg + " = " + got);
        } else {
            fail++;
            System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got);
        }
    }

    public static void main(String[] args) {
        // ------------------------------------------------------------------ size
        eqs("0", WidgetText.size(0), "0 字节");
        eqs("0", WidgetText.size(-1), "负数当未知");
        eqs("512B", WidgetText.size(512), "不足 1K 直接给字节");
        eqs("1K", WidgetText.size(1024), "1024 → 1K");
        eqs("2K", WidgetText.size(1536), "1.5K 四舍五入到 2K");
        eqs("1M", WidgetText.size(M), "1M");
        eqs("980M", WidgetText.size(980L * M), "980M 不升位");
        eqs("1G", WidgetText.size(G), "1G 不带小数点");
        eqs("12.3G", WidgetText.size((long) (12.3 * G)), "12.3G 保留一位");
        eqs("128G", WidgetText.size(128L * G), "≥100G 取整，省宽度");

        // --------------------------------------------------------------- summary
        eqs("存储不可用", WidgetText.summary(0, 0), "总容量未知");
        eqs("剩 12.3G / 128G", WidgetText.summary(128L * G, (long) (12.3 * G)),
                "正常摘要");
        eqs("剩 0 / 128G", WidgetText.summary(128L * G, 0), "已满");
        eqs("剩 128G / 128G", WidgetText.summary(128L * G, 128L * G), "全空");
        eqs("剩 0 / 128G", WidgetText.summary(128L * G, -5), "负可用量归零");

        // ------------------------------------------------------------ usedPercent
        eq(0, WidgetText.usedPercent(0, 0), "总容量 0 不显示进度");
        eq(0, WidgetText.usedPercent(100, 100), "全空 = 0%");
        eq(50, WidgetText.usedPercent(100, 50), "半满 = 50%");
        eq(100, WidgetText.usedPercent(100, 0), "全满 = 100%");
        eq(0, WidgetText.usedPercent(100, 150), "可用量大于总量时截断为 0%");
        eq(100, WidgetText.usedPercent(100, -10), "负可用量按全满处理");
        eq(33, WidgetText.usedPercent(300, 200), "三分之一 → 33%（四舍五入）");
        eq(67, WidgetText.usedPercent(300, 100), "三分之二 → 67%");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
