package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.core.CodecCore;

/**
 * 编码转换页文案层（v2.42.0，F-14/29）。
 *
 * <p>{@link CodecCore} 是纯 Java 逻辑层，只抛带 {@link CodecCore.Code} 的
 * {@link CodecCore.Fail}、只认格式号；面向用户的名字、状态栏、提示与错误说明统一在这里
 * 按资源拼装，逻辑层不产出任何文案。</p>
 */
public final class CodecReport {

    private CodecReport() {}

    /** 15 个格式名，顺序与 CodecCore.F_* 常量一一对应（供 Spinner 使用） */
    public static String[] formatNames(Context c) {
        return new String[]{
                c.getString(R.string.cd_fmt_text),
                c.getString(R.string.cd_fmt_hex),
                c.getString(R.string.cd_fmt_hex0x),
                c.getString(R.string.cd_fmt_bin),
                c.getString(R.string.cd_fmt_dec),
                c.getString(R.string.cd_fmt_bytes),
                c.getString(R.string.cd_fmt_unicode),
                c.getString(R.string.cd_fmt_escape),
                c.getString(R.string.cd_fmt_url),
                c.getString(R.string.cd_fmt_base64),
                c.getString(R.string.cd_fmt_base64url),
                c.getString(R.string.cd_fmt_html),
                c.getString(R.string.cd_fmt_json),
                c.getString(R.string.cd_fmt_punycode),
                c.getString(R.string.cd_fmt_base32)
        };
    }

    /** 格式名（越界回落「未知」） */
    public static String formatName(Context c, int fmt) {
        String[] names = formatNames(c);
        return fmt >= 0 && fmt < names.length ? names[fmt] : c.getString(R.string.cd_fmt_unknown);
    }

    /** 副标题：源格式 → 目标格式（输入即转换） */
    public static String subtitle(Context c, int from, int to) {
        return c.getString(R.string.cd_sub, formatName(c, from), formatName(c, to));
    }

    /** 空输入时的状态栏：支持 N 种格式… */
    public static String emptyStatus(Context c) {
        return c.getString(R.string.cd_hint_empty, CodecCore.F_COUNT);
    }

    /** 正常转换后的状态栏：输入 N 字符 [/ M 字节] → 输出 K 字符 */
    public static String status(Context c, int inChars, int bytes, int outChars) {
        String suffix = bytes >= 0 ? c.getString(R.string.cd_status_bytes, bytes) : "";
        return c.getString(R.string.cd_status, inChars, outChars, suffix);
    }

    /** 超大结果截断提示（拼在结果末尾） */
    public static String truncated(Context c, int total, int limit) {
        return c.getString(R.string.cd_truncated, total, limit);
    }

    /** IllegalArgumentException → 文案；Fail 按原因码翻译，其余回落异常自带 message */
    public static String error(Context c, IllegalArgumentException e) {
        if (!(e instanceof CodecCore.Fail)) {
            String m = e.getMessage();
            return m == null || m.isEmpty()
                    ? c.getString(R.string.cd_err_generic)
                    : c.getString(R.string.cd_err_generic_msg, m);
        }
        CodecCore.Fail f = (CodecCore.Fail) e;
        switch (f.code) {
            case UNSUPPORTED_FORMAT:
                return c.getString(R.string.cd_err_unsupported_format, f.num());
            case BAD_HEX_CHAR:
                return c.getString(R.string.cd_err_bad_hex_char, f.ch());
            case HEX_ODD:
                return c.getString(R.string.cd_err_hex_odd, f.num());
            case BAD_BIN_CHAR:
                return c.getString(R.string.cd_err_bad_bin_char, f.ch());
            case DEC_TOO_BIG:
                return c.getString(R.string.cd_err_dec_too_big, arg(f));
            case DEC_RANGE:
                return c.getString(R.string.cd_err_dec_range, f.num());
            case BAD_DEC_CHAR:
                return c.getString(R.string.cd_err_bad_dec_char, f.ch());
            case BAD_HEX_TOKEN:
                return c.getString(R.string.cd_err_bad_hex_token, arg(f));
            case BAD_NUMBER:
                return c.getString(R.string.cd_err_bad_number, arg(f));
            case BYTE_RANGE:
                return c.getString(R.string.cd_err_byte_range, arg(f));
            case ESC_U_HEX:
                return c.getString(R.string.cd_err_esc_u_hex, arg(f));
            case ESC_BIG_U_HEX:
                return c.getString(R.string.cd_err_esc_big_u_hex, arg(f));
            case ESC_X_SHORT:
                return c.getString(R.string.cd_err_esc_x_short);
            case ESC_X_HEX:
                return c.getString(R.string.cd_err_esc_x_hex);
            case ESC_U_SHORT:
                return c.getString(R.string.cd_err_esc_u_short);
            case PERCENT_SHORT:
                return c.getString(R.string.cd_err_percent_short);
            case BAD_PERCENT:
                return c.getString(R.string.cd_err_bad_percent, arg(f));
            case B64_BAD_LEN:
                return c.getString(R.string.cd_err_b64_bad_len);
            case BAD_B64_CHAR:
                return c.getString(R.string.cd_err_bad_b64_char, f.ch());
            case BAD_B32_CHAR:
                return c.getString(R.string.cd_err_bad_b32_char, f.ch());
            case DOMAIN_TOO_LONG:
                return c.getString(R.string.cd_err_domain_too_long);
            case PUNY_BASE_NON_ASCII:
                return c.getString(R.string.cd_err_puny_base_non_ascii, arg(f));
            case PUNY_TRUNCATED:
                return c.getString(R.string.cd_err_puny_truncated, arg(f));
            case PUNY_BAD_CHAR:
                return c.getString(R.string.cd_err_puny_bad_char, arg(f));
            case PUNY_OVERFLOW:
                return c.getString(R.string.cd_err_puny_overflow, arg(f));
            case PUNY_CP_RANGE:
                return c.getString(R.string.cd_err_puny_cp_range, arg(f));
            default:
                return c.getString(R.string.cd_err_generic);
        }
    }

    /** 兜底异常（Throwable）文案 */
    public static String fatal(Context c, Throwable e) {
        return c.getString(R.string.cd_err_fatal, e.toString());
    }

    private static String arg(CodecCore.Fail f) {
        return f.arg() == null ? "" : f.arg();
    }
}
