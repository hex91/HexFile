package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.TrashPolicy;

/**
 * 回收站文案层（v2.46.0，F-14/33）。
 *
 * <p>{@link TrashPolicy.Report#summary()} 只返回稳定 ASCII 码
 * （{@code SKIPPED} / {@code NONE} / {@code CLEANED:<n>:<bytes>}），
 * 逻辑层不产出文案、也不调用 {@link FileOps#formatSize}。这里把码串翻译成界面文本，
 * 未识别的串原样透传（兼容将来新增的状态）。</p>
 */
public final class TrashReport {

    private TrashReport() {}

    private static String s(Context c, int id) { return c.getString(id); }
    private static String s(Context c, int id, Object... a) { return c.getString(id, a); }

    /** 把清理报告码串翻成人类可读的一行摘要 */
    public static String clean(Context c, TrashPolicy.Report r) {
        if (r == null) return "";
        String code = r.summary();
        if (TrashPolicy.Report.CODE_SKIPPED.equals(code)) return s(c, R.string.trash_summary_off);
        if (TrashPolicy.Report.CODE_NONE.equals(code)) return s(c, R.string.trash_clean_none);
        if (code.startsWith(TrashPolicy.Report.CODE_CLEANED + ":")) {
            String[] parts = code.split(":", 3);
            if (parts.length == 3) {
                try {
                    int n = Integer.parseInt(parts[1]);
                    long bytes = Long.parseLong(parts[2]);
                    return s(c, R.string.trash_cleaned, n, FileOps.formatSize(bytes));
                } catch (NumberFormatException ignored) {
                    // 码串被外部篡改时回落原文，不崩溃
                    return code;
                }
            }
        }
        return code;
    }
}
