import com.hex91.file.core.OpenWithKind;

/**
 * v2.122.0 引入、v2.123.0 改单页：「打开方式」适用性判定的桌面用例。
 *
 * <p>这部分是纯 JDK 逻辑，能在无设备环境验证「哪种文件该出现哪些入口」：
 * 通用方式（十六进制/文本/字符串/属性）对任何文件都成立，专用方式只在扩展名匹配时出现。
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
        layout();
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void universal() {
        ok(OpenWithKind.applies(OpenWithKind.HEX, null), "未知文件仍可用十六进制");
        ok(OpenWithKind.applies(OpenWithKind.TEXT, "a.unknown"), "未知文件仍可用文本");
        ok(OpenWithKind.applies(OpenWithKind.STRINGS, ""), "无扩展名仍可用字符串");
        ok(OpenWithKind.applies(OpenWithKind.PROPERTIES, "a.bin"), "属性对任何文件都显示");
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

    static void layout() {
        ok(OpenWithKind.BUILT_INS.length == 13, "单页网格 13 项定义");
        ok(OpenWithKind.BUILT_INS[2] == OpenWithKind.PROPERTIES, "属性排在文本之后");
        ok(OpenWithKind.BUILT_INS[OpenWithKind.BUILT_INS.length - 1] == OpenWithKind.SYSTEM,
                "系统应用入口收尾");
        // .so 这类未知文件：通用四项 + 系统入口，不能出现 Arsc/Dex/PDF 等专用项
        int shown = 0;
        for (OpenWithKind k : OpenWithKind.BUILT_INS)
            if (OpenWithKind.applies(k, "libapp.so")) shown++;
        ok(shown == 5, "未知 .so 只显示 5 项（十六进制/文本/属性/字符串/系统应用）");
    }
}
