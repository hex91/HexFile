package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.apk.ArscEditor;
import com.hex91.file.apk.ArscPlus;

/**
 * ARSC++ 保存链路的文案映射（v2.87.0 F-14/74，v2.89.0 并入 ArscEditor）。
 *
 * apk 层的 {@link ArscPlus} 与 {@link ArscEditor} 只抛码，句子在这里拼。
 * 非本类异常原样透出消息。
 */
public final class ArscText {

    private ArscText() {
    }

    public static String errorText(Context c, Throwable t) {
        if (t instanceof ArscEditor.ArscEditException) {
            return editText(c, (ArscEditor.ArscEditException) t);
        }
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

    /** ArscEditor 的结构化编辑失败（新增/改名/删除） */
    private static String editText(Context c, ArscEditor.ArscEditException e) {
        String[] a = e.args;
        switch (e.code) {
            case ArscEditor.E_NO_TYPE:
                return c.getString(R.string.arsc_err_no_type, arg(a, 0));
            case ArscEditor.E_NO_CONFIG:
                return c.getString(R.string.arsc_err_no_config, arg(a, 0),
                        arg(a, 1).isEmpty() ? c.getString(R.string.arsc_default_cfg) : arg(a, 1));
            case ArscEditor.E_ID_TAKEN:
                return c.getString(R.string.arsc_err_id_taken, arg(a, 0));
            case ArscEditor.E_NOT_LOCATABLE:
                return c.getString(R.string.arsc_err_not_locatable, arg(a, 0));
            case ArscEditor.E_COMPACT_ENTRY:
                return c.getString(R.string.arsc_err_compact_entry, arg(a, 0));
            case ArscEditor.E_DELETE_NOT_LOCATABLE:
                return c.getString(R.string.arsc_err_del_not_locatable, arg(a, 0));
            case ArscEditor.E_DELETE_MISSING:
                return c.getString(R.string.arsc_err_del_missing, arg(a, 0));
            case ArscEditor.E_BLOCK_ADD:
                return c.getString(R.string.arsc_err_block_add, arg(a, 0), arg(a, 1));
            case ArscEditor.E_BLOCK_EDIT:
                return c.getString(R.string.arsc_err_block_edit, arg(a, 0), arg(a, 1));
            case ArscEditor.E_BLOCK_DELETE:
                return c.getString(R.string.arsc_err_block_delete, arg(a, 0), arg(a, 1));
            default:
                return c.getString(R.string.arsc_err_unknown);
        }
    }

    private static String arg(String[] a, int i) {
        return a != null && i < a.length && a[i] != null ? a[i] : "";
    }
}
