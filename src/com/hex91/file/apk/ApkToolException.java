package com.hex91.file.apk;

/**
 * 签名 / 克隆可预期的失败（v2.85.0，F-14/72）。
 *
 * <p>为什么单独一个类：这两处失败全是「用户把参数配错了」，需要给出具体指引，
 * 而不是把异常堆栈甩到 toast 上。核心层只负责报码 + 关键参数，
 * 句子由 UI 层（{@code ApkSignHelper.errorText}）映射到 R.string。</p>
 *
 * <p>继承 {@link IllegalArgumentException} 而不是 IllegalStateException：原调用点
 * 和 {@code test/SignSchemeTest} 都是按「参数不合法」捕获的，保持一致。</p>
 */
public class ApkToolException extends IllegalArgumentException {

    public static final int NO_SCHEME = 1;
    public static final int KEY_ALIAS_MISSING = 2;
    public static final int NO_CERT_CHAIN = 3;
    public static final int NO_KEY_ENTRY = 4;
    public static final int KEY_UNREADABLE = 5;
    public static final int ALIAS_NO_CHAIN = 6;
    public static final int NO_MANIFEST = 7;
    public static final int NO_PACKAGE_ATTR = 8;
    public static final int PACKAGE_UNREADABLE = 9;
    public static final int SAME_PACKAGE = 10;
    public static final int READBACK_PACKAGE = 11;
    public static final int READBACK_ATTR = 12;
    public static final int LEFTOVER_ATTR = 13;

    public final int code;
    /** 别名 / 属性名 */
    public final String text;
    public final String expected;
    public final String actual;

    public ApkToolException(int code) {
        this(code, null, null, null);
    }

    public ApkToolException(int code, String text) {
        this(code, text, null, null);
    }

    public ApkToolException(int code, String text, String expected, String actual) {
        // 兜底消息只给日志和「UI 忘了映射」的极端情况，故意不含中文
        super("ApkTool error " + code + (text == null ? "" : ": " + text));
        this.code = code;
        this.text = text;
        this.expected = expected;
        this.actual = actual;
    }
}
