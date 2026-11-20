package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.apk.ArchiveUtil;

/**
 * 归档失败的文案映射（v2.86.0 F-14/73）。
 *
 * apk 层的 {@link ArchiveUtil} 只抛码（{@link ArchiveUtil.ArchiveException}），
 * 具体句子在这里拼，中英各一套资源。非归档异常保持「类名: 消息」的旧形态。
 */
public final class ArchiveText {

    private ArchiveText() {
    }

    /** 任意异常 → 可显示的一行文案；归档码优先，其余原样透出 */
    public static String errorText(Context c, Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof ArchiveUtil.ArchiveException) {
                return of(c, (ArchiveUtil.ArchiveException) cur);
            }
        }
        String msg = t == null ? null : t.getMessage();
        if (msg == null || msg.isEmpty()) {
            return t == null ? "" : t.getClass().getSimpleName();
        }
        return t.getClass().getSimpleName() + ": " + msg;
    }

    private static String of(Context c, ArchiveUtil.ArchiveException e) {
        String[] a = e.args;
        switch (e.code) {
            case ArchiveUtil.E_7Z_OPEN:
                return c.getString(R.string.arch_err_7z_open, arg(a, 0));
            case ArchiveUtil.E_7Z_ENTRY_ENCRYPTED:
                return c.getString(R.string.arch_err_7z_entry_encrypted, arg(a, 0));
            case ArchiveUtil.E_OPEN:
                return c.getString(R.string.arch_err_open, arg(a, 0), arg(a, 1));
            case ArchiveUtil.E_PW_VERIFY:
                return c.getString(R.string.arch_err_pw_verify, arg(a, 0), arg(a, 1));
            case ArchiveUtil.E_PW_DECRYPT:
                return c.getString(R.string.arch_err_pw_decrypt, arg(a, 0), arg(a, 1));
            case ArchiveUtil.E_ENTRY_PW:
                return c.getString(R.string.arch_err_entry_pw, arg(a, 0), arg(a, 1));
            case ArchiveUtil.E_EXTRACT_FAIL:
                return c.getString(R.string.arch_err_extract_fail,
                        arg(a, 0), arg(a, 1), arg(a, 2));
            case ArchiveUtil.E_EXTRACT_DECRYPT:
                return c.getString(R.string.arch_err_extract_decrypt, arg(a, 0), arg(a, 1));
            case ArchiveUtil.E_ZIP_OPEN:
                return c.getString(R.string.arch_err_zip_open, arg(a, 0));
            case ArchiveUtil.E_ZIP_READ:
                return c.getString(R.string.arch_err_zip_read, arg(a, 0));
            case ArchiveUtil.E_ZIP_ENCRYPTED:
                return c.getString(R.string.arch_err_zip_encrypted);
            case ArchiveUtil.E_ZIP_PW:
                return c.getString(R.string.arch_err_zip_pw, arg(a, 0));
            case ArchiveUtil.E_ZIP_DECRYPT:
                return c.getString(R.string.arch_err_zip_decrypt, arg(a, 0));
            case ArchiveUtil.E_ZIP_EXTRACT:
                return c.getString(R.string.arch_err_zip_extract, arg(a, 0));
            case ArchiveUtil.E_ZIP_ENTRY_MISSING:
                return c.getString(R.string.arch_err_zip_entry_missing, arg(a, 0));
            case ArchiveUtil.E_ENTRY_NAME:
                return c.getString(R.string.arch_err_entry_name, arg(a, 0));
            default:
                return c.getString(R.string.arch_err_unknown);
        }
    }

    /** 参数缺失时给空串，避免 getString 抛 IllegalFormat */
    private static String arg(String[] a, int i) {
        return a != null && i < a.length && a[i] != null ? a[i] : "";
    }
}
