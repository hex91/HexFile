package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.net.SyncRunner;

/**
 * 同步结果文案（v3.0.0 F-14）。
 *
 * <p>{@link SyncRunner.Report} 只保留统计字段（summary() 为 ASCII 诊断串），
 * 中文/英文句子在这里拼。与 apk 层 {@code PackText} / net 层 {@code NetText} 同套路。</p>
 */
public final class SyncText {

    private SyncText() {
    }

    /** 完成后的状态行。 */
    public static String summaryText(Context c, SyncRunner.Report r) {
        String base = r.failed > 0
                ? c.getString(R.string.sy_summary_fail, r.uploaded, r.downloaded, r.conflicts, r.failed)
                : c.getString(R.string.sy_summary, r.uploaded, r.downloaded, r.conflicts);
        if (r.cancelled) base += "\n" + c.getString(R.string.sy_summary_cancelled);
        return base;
    }
}
