package com.hex91.file.utils;

import java.io.IOException;

/**
 * utils 层结构化异常（v2.106.0，F-14/93）。
 *
 * <p>utils 不能引用 Context/R，原来 ImageBatchUtil / IntentImport 直接把中文 IOException
 * 抛给 UI。这里保留码 + 数据参数，{@code getMessage()} 只给 ASCII 诊断串，
 * 文案统一由 {@code ui/UtilText.errorText()} 映射。仍 extends IOException，既有 catch 不变。</p>
 */
public class UtilError extends IOException {

    public enum Code {
        IMG_SRC_MISSING,
        IMG_BOUNDS_FAIL,
        IMG_DECODE_FAIL,
        IMG_ENCODE_FAIL,
        IMPORT_NO_DEST,
        IMPORT_MKDIR_FAIL,
        IMPORT_RENAME_FAIL
    }

    public final Code code;
    private final Object[] args;

    private UtilError(Code code, Object[] args) {
        super(diagnose(code, args));
        this.code = code;
        this.args = args;
    }

    public static UtilError of(Code code, Object... args) {
        return new UtilError(code, args);
    }

    public String str(int i) {
        if (args == null || i < 0 || i >= args.length) return "";
        Object v = args[i];
        return v == null ? "" : String.valueOf(v);
    }

    private static String diagnose(Code code, Object[] args) {
        String s = "UTIL " + code;
        if (args == null || args.length == 0) return s;
        StringBuilder sb = new StringBuilder(s).append(':');
        for (Object a : args) sb.append(' ').append(String.valueOf(a));
        return sb.toString();
    }
}
