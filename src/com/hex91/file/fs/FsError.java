package com.hex91.file.fs;

import java.io.IOException;

/**
 * fs 层结构化异常（v2.104.0，F-14/91）。
 *
 * <p>原来 FileSplit / FileTaskEngine / TreeOps / ZstdUtil 直接把中文 IOException 抛给 UI，
 * 由调用方 {@code toast(e.getMessage())} 原样显示。fs 是纯 Java 层，不能依赖 Context/R，
 * 所以这里只保留错误码 + 数据参数；{@code getMessage()} 只输出 ASCII 诊断串（日志 / 单测）。
 * 文案由 {@code ui/FsText.errorText(Context, Throwable)} 统一映射。</p>
 *
 * <p>仍然 {@code extends IOException}：原有 catch / throws 签名与重试分支全部保持不变。</p>
 */
public class FsError extends IOException {

    /** 稳定错误码；新增码只需同时补 ui/FsText 的映射 */
    public enum Code {
        SRC_MISSING,
        CHUNK_INVALID,
        EMPTY_SOURCE,
        TOO_SMALL,
        PART_MISSING,
        NOT_PART,
        NO_SEQUENCE,
        START_MISSING,
        PARTS_INCOMPLETE,
        SINGLE_PART,
        PART_SIZE_MISMATCH,
        MERGE_EMPTY,
        MERGE_SIZE,
        MKDIR_FAIL,
        DELETE_SRC_FAIL,
        DEEP_PATH,
        TARGET_BUSY,
        CREATE_FAIL,
        READ_FAIL,
        WRITE_FAIL,
        RESUME_EOF,
        NULL_PATH,
        LINK_UNSUPPORTED,
        ZIP_WRITE_FAIL,
        ZIP_RENAME_FAIL,
        NO_INPUT,
        BAD_MAGIC,
        OUTDIR_FAIL
    }

    public final Code code;
    private final Object[] args;

    private FsError(Code code, Object[] args) {
        super(diagnose(code, args));
        this.code = code;
        this.args = args;
    }

    /** 构造一个带码异常；参数是纯数据（路径 / 数量 / 底层技术串），不含用户文案 */
    public static FsError of(Code code, Object... args) {
        return new FsError(code, args);
    }

    public Object arg(int i) {
        if (args == null || i < 0 || i >= args.length) return null;
        return args[i];
    }

    public String str(int i) {
        Object v = arg(i);
        return v == null ? "" : String.valueOf(v);
    }

    public int num(int i) {
        Object v = arg(i);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    public long lng(int i) {
        Object v = arg(i);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    /** 日志 / 桌面单测看到的稳定诊断串，始终 ASCII 前缀 */
    private static String diagnose(Code code, Object[] args) {
        String s = "FS " + code;
        if (args == null || args.length == 0) return s;
        StringBuilder sb = new StringBuilder(s).append(':');
        for (Object a : args) sb.append(' ').append(String.valueOf(a));
        return sb.toString();
    }

    @Override
    public String toString() {
        return getClass().getName() + "[" + getMessage() + "]";
    }
}
