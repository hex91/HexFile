package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.utils.BackupRestore;

/**
 * 还原结果 → 一句给人看的提示 —— v2.29.0（F-14/13）。
 *
 * <p>{@link BackupRestore} 现在只回 {@link BackupRestore.Code} 与数据参数（文件名、pm 输出），
 * 不含任何人类可读文案。映射放在 ui 层，因为只有这里能拿 {@link Context} 取资源；
 * 备份管理页与应用管理页共用这一份，避免同一句话两处维护。</p>
 *
 * <p>新增结果码却忘了加译文时，落到 {@code rs_err_generic} 把码名露出来，而不是显示空白。</p>
 */
final class RestoreText {

    private RestoreText() {}

    static String of(Context ctx, BackupRestore.RestoreOutcome o) {
        if (o == null || o.code == null) return ctx.getString(R.string.rs_err_generic, "");
        switch (o.code) {
            case OK_DATA:
                return ctx.getString(R.string.rs_ok_data);
            case OK_NO_DATA:
                return ctx.getString(R.string.rs_ok_no_data);
            case OK_DATA_FAILED:
                return ctx.getString(R.string.rs_ok_data_failed);
            case ERR_NO_APK:
                return ctx.getString(R.string.rs_err_no_apk);
            case ERR_NO_ROOT:
                return ctx.getString(R.string.rs_err_no_root);
            case ERR_COPY:
                return ctx.getString(R.string.rs_err_copy, o.str(0), o.str(1));
            case ERR_TIMEOUT:
                return ctx.getString(R.string.rs_err_timeout);
            case ERR_SIGN:
                return ctx.getString(R.string.rs_err_sign);
            case ERR_SPLIT:
                return ctx.getString(R.string.rs_err_split);
            case ERR_INSTALL:
                return ctx.getString(R.string.rs_err_install, o.str(0));
            case ERR_UNKNOWN:
                return ctx.getString(R.string.rs_err_unknown);
            default:
                return ctx.getString(R.string.rs_err_generic, o.code.name());
        }
    }
}
