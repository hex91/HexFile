package com.hex91.file.core;

/**
 * 进制转换的可预期失败（v2.102.0，F-14/core）。
 *
 * <p>core 层不能依赖 R，这里只给错误码 + 参数；{@code getMessage()} 是全 ASCII 诊断串。
 * 中文/英文文案由 UI（{@code ui/RadixText}）按码取资源。</p>
 *
 * <p>extends {@link IllegalArgumentException}：保住原有 catch 分支语义。</p>
 */
public class RadixException extends IllegalArgumentException {

    public static final int EMPTY_INPUT = 1;
    public static final int BASE_RANGE = 2;
    public static final int ILLEGAL_CHAR = 3;
    public static final int CHAR_OVER_BASE = 4;

    public final int code;
    public final char ch;
    public final int base;

    public RadixException(int code) {
        this(code, '\0', -1);
    }

    public RadixException(int code, char ch, int base) {
        super("RADIX " + code + (ch == '\0' ? "" : " ch=" + ch) + (base < 0 ? "" : " base=" + base));
        this.code = code;
        this.ch = ch;
        this.base = base;
    }
}
