package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.core.ArscDefs;
import com.hex91.file.apk.AxmlFile;
import com.hex91.file.apk.AxmlTextSync;

/**
 * Manifest AXML 相关失败的文案映射（v2.88.0 F-14/75，v2.92.0 并入格式错误）。
 *
 * apk 层的 {@link AxmlTextSync} 与 {@link AxmlFile} 只抛码；句子与「类型名」的本地化都在这里。
 */
public final class AxmlSyncText {

    private AxmlSyncText() {
    }

    public static String errorText(Context c, Throwable t) {
        if (t instanceof AxmlFile.AxmlFormatException) {
            return formatText(c, (AxmlFile.AxmlFormatException) t);
        }
        if (!(t instanceof AxmlTextSync.SyncException)) {
            String m = t == null ? null : t.getMessage();
            return m != null && !m.isEmpty() ? m
                    : (t == null ? "" : t.getClass().getSimpleName());
        }
        AxmlTextSync.SyncException e = (AxmlTextSync.SyncException) t;
        String[] a = e.args;
        switch (e.code) {
            case AxmlTextSync.E_COUNT:
                return c.getString(R.string.axsync_err_count, arg(a, 0), arg(a, 1));
            case AxmlTextSync.E_RENAME:
                return c.getString(R.string.axsync_err_rename, arg(a, 0), arg(a, 1));
            case AxmlTextSync.E_DEL_ATTR:
                return c.getString(R.string.axsync_err_del_attr, arg(a, 0), arg(a, 1));
            case AxmlTextSync.E_BAD_VALUE:
                return c.getString(R.string.axsync_err_bad_value,
                        arg(a, 0), arg(a, 1), arg(a, 2), typeName(c, e.dataType));
            case AxmlTextSync.E_ADD_ATTR:
                return c.getString(R.string.axsync_err_add_attr, arg(a, 0), arg(a, 1));
            case AxmlTextSync.E_LINE_NO_ELEM:
                return c.getString(R.string.axsync_err_line_no_elem, arg(a, 0));
            case AxmlTextSync.E_LINE_UNCLOSED:
                return c.getString(R.string.axsync_err_line_unclosed, arg(a, 0));
            case AxmlTextSync.E_TAIL_UNCLOSED:
                return c.getString(R.string.axsync_err_tail_unclosed);
            case AxmlTextSync.E_LINE_NO_EQ:
                return c.getString(R.string.axsync_err_line_no_eq, arg(a, 0), arg(a, 1));
            case AxmlTextSync.E_LINE_NO_QUOTE:
                return c.getString(R.string.axsync_err_line_no_quote, arg(a, 0), arg(a, 1));
            case AxmlTextSync.E_LINE_NEWLINE:
                return c.getString(R.string.axsync_err_line_newline, arg(a, 0));
            default:
                return c.getString(R.string.axsync_err_unknown);
        }
    }

    /** 文件不是合法 AXML（解析阶段） */
    private static String formatText(Context c, AxmlFile.AxmlFormatException e) {
        String[] a = e.args;
        switch (e.code) {
            case AxmlFile.E_TOO_SMALL:
                return c.getString(R.string.axml_fmt_too_small);
            case AxmlFile.E_NOT_AXML:
                return c.getString(R.string.axml_fmt_not_axml, arg(a, 0));
            case AxmlFile.E_DECLARED_TOO_LONG:
                return c.getString(R.string.axml_fmt_declared_too_long);
            case AxmlFile.E_CHUNK_RANGE:
                return c.getString(R.string.axml_fmt_chunk_range, arg(a, 0), arg(a, 1));
            case AxmlFile.E_POOL_COUNT:
                return c.getString(R.string.axml_fmt_pool_count, arg(a, 0));
            default:
                return c.getString(R.string.axml_fmt_unknown);
        }
    }

    /** AXML 数据类型码 → 本地化名称（原 typeName 搬到 UI 层） */
    private static String typeName(Context c, int t) {
        int res;
        switch (t) {
            case ArscDefs.TYPE_INT_DEC:
                res = R.string.axml_type_dec;
                break;
            case ArscDefs.TYPE_INT_HEX:
                res = R.string.axml_type_hex;
                break;
            case ArscDefs.TYPE_INT_BOOLEAN:
                res = R.string.axml_type_bool;
                break;
            case ArscDefs.TYPE_REFERENCE:
                res = R.string.axml_type_ref;
                break;
            case ArscDefs.TYPE_FLOAT:
                res = R.string.axml_type_float;
                break;
            default:
                res = R.string.axml_type_other;
                break;
        }
        return c.getString(res);
    }

    private static String arg(String[] a, int i) {
        return a != null && i < a.length && a[i] != null ? a[i] : "";
    }
}
