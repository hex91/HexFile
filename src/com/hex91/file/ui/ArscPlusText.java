package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.apk.ArscPlus;

/**
 * ARSC++ XML 解析失败的文案映射（v2.87.0 F-14/74）。
 *
 * apk 层的 {@link ArscPlus} 只抛码，句子在这里拼。非本类异常原样透出消息。
 */
public final class ArscPlusText {

    private ArscPlusText() {
    }

    public static String errorText(Context c, Throwable t) {
        if (!(t instanceof ArscPlus.ArscPlusException)) {
            String m = t == null ? null : t.getMessage();
            return m != null && !m.isEmpty() ? m
                    : (t == null ? "" : t.getClass().getSimpleName());
        }
        ArscPlus.ArscPlusException e = (ArscPlus.ArscPlusException) t;
        String[] a = e.args;
        switch (e.code) {
            case ArscPlus.E_COMMENT_OPEN:
                return c.getString(R.string.arp_err_comment_open);
            case ArscPlus.E_PI_OPEN:
                return c.getString(R.string.arp_err_pi_open);
            case ArscPlus.E_TAG_OPEN:
                return c.getString(R.string.arp_err_tag_open);
            case ArscPlus.E_STRAY_END:
                return c.getString(R.string.arp_err_stray_end, arg(a, 0));
            case ArscPlus.E_TAG_MISMATCH:
                return c.getString(R.string.arp_err_tag_mismatch, arg(a, 0), arg(a, 1));
            case ArscPlus.E_NESTED:
                return c.getString(R.string.arp_err_nested, arg(a, 0));
            case ArscPlus.E_NO_NAME:
                return c.getString(R.string.arp_err_no_name, arg(a, 0));
            case ArscPlus.E_SELF_CLOSE:
                return c.getString(R.string.arp_err_self_close, arg(a, 0), arg(a, 1));
            case ArscPlus.E_UNCLOSED_ENTRY:
                return c.getString(R.string.arp_err_unclosed_entry, arg(a, 0), arg(a, 1));
            case ArscPlus.E_ADD_NEEDS_EDITOR:
                return c.getString(R.string.arp_err_add_needs_editor);
            case ArscPlus.E_ADD_EXISTS:
                return c.getString(R.string.arp_err_add_exists, arg(a, 0));
            case ArscPlus.E_NOT_FOUND:
                return c.getString(R.string.arp_err_not_found, arg(a, 0), arg(a, 1));
            case ArscPlus.E_DELETE_NEEDS_EDITOR:
                return c.getString(R.string.arp_err_delete_needs_editor);
            case ArscPlus.E_RENAME_NEEDS_EDITOR:
                return c.getString(R.string.arp_err_rename_needs_editor);
            case ArscPlus.E_BAD_VALUE:
                return c.getString(R.string.arp_err_bad_value, arg(a, 0), arg(a, 1));
            default:
                return c.getString(R.string.arp_err_unknown);
        }
    }

    private static String arg(String[] a, int i) {
        return a != null && i < a.length && a[i] != null ? a[i] : "";
    }
}
