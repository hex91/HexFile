package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.apk.ApkPackager;

/**
 * 解包 / 打包失败的文案映射（v2.93.0 F-14/80）。
 *
 * apk 层的 {@link ApkPackager} 只抛码，句子在这里拼。
 */
public final class PackText {

    private PackText() {
    }

    public static String errorText(Context c, Throwable t) {
        if (!(t instanceof ApkPackager.PackException)) {
            String m = t == null ? null : t.getMessage();
            return m != null && !m.isEmpty() ? m
                    : (t == null ? "" : t.getClass().getSimpleName());
        }
        ApkPackager.PackException e = (ApkPackager.PackException) t;
        switch (e.code) {
            case ApkPackager.E_TARGET_OCCUPIED:
                return c.getString(R.string.pack_err_target_occupied);
            case ApkPackager.E_DANGEROUS_PATH:
                return c.getString(R.string.pack_err_dangerous_path,
                        e.args.length > 0 ? e.args[0] : "");
            case ApkPackager.E_NO_MANIFEST:
                return c.getString(R.string.pack_err_no_manifest);
            default:
                return c.getString(R.string.pack_err_unknown);
        }
    }
}
