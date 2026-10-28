import com.hex91.file.AppManagerActivity;

/** v1.45.0 应用管理桌面测试：导出文件名清洗（纯静态逻辑，不依赖 Android 运行时） */
public class AppExportTest {
    static int pass = 0, fail = 0;

    static void check(String name, Object actual, Object expect) {
        boolean ok = (actual == null) ? expect == null : actual.equals(expect);
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + "  (actual=" + actual + ", expect=" + expect + ")"); }
    }

    public static void main(String[] args) {
        check("常规名",AppManagerActivity.AppRow.exportFileName("微信", "8.0.49"), "微信_8.0.49.apk");
        check("空格转下划线",AppManagerActivity.AppRow.exportFileName("My App", "1.0"), "My_App_1.0.apk");
        check("斜杠冒号清洗",AppManagerActivity.AppRow.exportFileName("a/b:c", "1.0"), "a_b_c_1.0.apk");
        check("Windows 非法字符",AppManagerActivity.AppRow.exportFileName("x*y?z", "2.0"), "x_y_z_2.0.apk");
        check("多点版本",AppManagerActivity.AppRow.exportFileName("App", "1.2.3-beta.1"), "App_1.2.3-beta.1.apk");
        check("超长截断80",AppManagerActivity.AppRow.exportFileName(repeat("A", 100), "1.0").length(), 84);
        check("空标签回退",AppManagerActivity.AppRow.exportFileName("", "1.0"), "_1.0.apk");
        System.out.println();
        System.out.println("==== AppExportTest: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
