package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.apk.Decompiler;

/**
 * 反编译失败的文案映射（v2.91.0 F-14/78）。
 *
 * apk 层的 {@link Decompiler} 只抛码；句子在这里拼，技术细节（CFR 报错、长度等）走参数。
 */
public final class DecompileText {

    private DecompileText() {
    }

    public static String errorText(Context c, Throwable t) {
        if (!(t instanceof Decompiler.DecompileException)) {
            return Decompiler.err(t);
        }
        Decompiler.DecompileException e = (Decompiler.DecompileException) t;
        String[] a = e.args;
        switch (e.code) {
            case Decompiler.E_NOT_LOADED:
                return c.getString(R.string.dcp_err_not_loaded);
            case Decompiler.E_CFR_EMPTY:
                return arg(a, 0).isEmpty()
                        ? c.getString(R.string.dcp_err_cfr_empty)
                        : c.getString(R.string.dcp_err_cfr_empty_why, arg(a, 0));
            case Decompiler.E_BAD_NAME_LEN:
                return c.getString(R.string.dcp_err_bad_name_len, arg(a, 0));
            case Decompiler.E_BAD_DATA_LEN:
                return c.getString(R.string.dcp_err_bad_data_len, arg(a, 0));
            case Decompiler.E_NO_CLASSES:
                return c.getString(R.string.dcp_err_no_classes);
            default:
                return c.getString(R.string.dcp_err_unknown);
        }
    }

    private static String arg(String[] a, int i) {
        return a != null && i < a.length && a[i] != null ? a[i] : "";
    }
}
