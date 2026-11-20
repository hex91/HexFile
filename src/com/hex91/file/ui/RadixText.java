package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.core.RadixException;

/** 进制转换错误的文案映射（v2.102.0 F-14），RadixException 只给码 + 参数。 */
public final class RadixText {

    private RadixText() {
    }

    public static String errorText(Context c, Throwable t) {
        if (!(t instanceof RadixException)) {
            String m = t == null ? null : t.getMessage();
            return m != null && !m.isEmpty() ? m : (t == null ? "" : t.getClass().getSimpleName());
        }
        RadixException e = (RadixException) t;
        switch (e.code) {
            case RadixException.EMPTY_INPUT:
                return c.getString(R.string.radix_err_empty);
            case RadixException.BASE_RANGE:
                return c.getString(R.string.radix_err_base_range);
            case RadixException.ILLEGAL_CHAR:
                return c.getString(R.string.radix_err_illegal_char, String.valueOf(e.ch));
            case RadixException.CHAR_OVER_BASE:
                return c.getString(R.string.radix_err_char_base, String.valueOf(e.ch), e.base);
            default:
                return c.getString(R.string.radix_err_unknown);
        }
    }
}
