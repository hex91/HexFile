import com.hex91.file.core.OpenWithKind;

/**
 * v2.122.0：分类「打开方式」的适用性判定桌面用例。
 *
 * <p>这部分是纯 JDK 逻辑，能在无设备环境验证「哪种文件该出现哪些入口」：
 * 通用方式（十六进制/文本/字符串）对任何文件都成立，专用方式只在扩展名匹配时出现。
 * 判错会导致用户点开一个 .png 却看到「Arsc 功能」，或未知文件连十六进制都用不了。</p>
 */
public class OpenWithKindTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) {
        universal();
        byExtension();
        categories();
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void universal() {
        ok(OpenWithKind.applies(OpenWithKind.HEX, null), "未知文件仍可用十六进制");
        ok(OpenWithKind.applies(OpenWithKind.TEXT, "a.unknown"), "未知文件仍可用文本");
        ok(OpenWithKind.applies(OpenWithKind.STRINGS, ""), "无扩展名仍可用字符串");
        ok(OpenWithKind.applies(OpenWithKind.SYSTEM, "a.bin"), "系统应用入口恒成立");
        ok(!OpenWithKind.applies(null, "a.apk"), "null 类型不显示");
    }

    static void byExtension() {
        ok(OpenWithKind.applies(OpenWithKind.IMAGE, "pic.PNG"), "扩展名大小写不敏感");
        ok(!OpenWithKind.applies(OpenWithKind.ARSC, "pic.png"), "图片不显示 Arsc 功能");
        ok(OpenWithKind.applies(OpenWithKind.APK_INFO, "app.apk"), "APK 显示信息入口");
        ok(OpenWithKind.applies(OpenWithKind.ARSC, "app.apk"), "APK 显示 Arsc 功能");
        ok(OpenWithKind.applies(OpenWithKind.DEX, "classes.dex"), "dex 显示 Dex 功能");
        ok(!OpenWithKind.applies(OpenWithKind.DEX, "classes.dex.bak"), "非 dex 不显示 Dex 功能");
        ok(OpenWithKind.applies(OpenWithKind.ARCHIVE, "a.7z"), "7z 显示压缩包浏览");
        ok(OpenWithKind.applies(OpenWithKind.ARCHIVE, "a.TAR.GZ"), "tar.gz 按末段 gz 识别");
        ok(OpenWithKind.applies(OpenWithKind.VIDEO, "movie.mkv"), "视频显示播放入口");
        ok(!OpenWithKind.applies(OpenWithKind.VIDEO, "movie.ts"), "ts 默认当源码不显示播放");
        ok(OpenWithKind.applies(OpenWithKind.FONT, "f.ttf"), "字体显示预览");
        ok(OpenWithKind.applies(OpenWithKind.PDF, "a.pdf"), "PDF 显示预览");
    }

    static void categories() {
        ok(OpenWithKind.BUILT_INS.length == 11, "内置页 11 项");
        ok(OpenWithKind.APP_CATEGORIES.length == 4, "系统应用页 4 个分类");
        ok("text/*".equals(OpenWithKind.mimeOfCategory("text/*")), "文本分类 MIME");
        ok("image/*".equals(OpenWithKind.mimeOfCategory("image/png")), "图片分类归一到通配");
        ok("video/*".equals(OpenWithKind.mimeOfCategory("video/*")), "视频分类 MIME");
        ok("audio/*".equals(OpenWithKind.mimeOfCategory("audio/*")), "音频分类 MIME");
        ok("*/*".equals(OpenWithKind.mimeOfCategory(null)), "null 分类兜底为全类型");
    }
}
