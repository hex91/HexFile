package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.fs.FileOps;
import com.hex91.file.utils.HashUtil;
import com.hex91.file.utils.IntentImport;
import com.hex91.file.utils.ResumePlan;
import com.hex91.file.utils.StorageStat;
import com.hex91.file.utils.ShizukuBridge;
import com.hex91.file.utils.SyncPlan;
import com.hex91.file.utils.UtilError;
import com.hex91.file.utils.WidgetText;

/**
 * utils 层稳定码 → 界面文案（v2.105.0，F-14/92）。
 *
 * <p>utils 和 fs 一样是纯 Java 逻辑层（个别类已有 Context 参数，但依旧不碰 R），
 * 所以摘要/状态只给 ASCII 码，文案统一在这里落地。未知码原样透传。</p>
 */
public final class UtilText {

    private UtilText() {}

    /** 从 "PREFIX:a:b:c" 取第 i 段，越界返回 null */
    private static String part(String code, int i) {
        if (code == null) return null;
        String[] a = code.split(":", -1);
        return i >= 0 && i < a.length ? a[i] : null;
    }

    private static int intPart(String code, int i) {
        String s = part(code, i);
        if (s == null) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }

    /** 哈希短摘要里的失败码 */
    public static String hashBrief(Context c, String brief) {
        if (HashUtil.BRIEF_FAIL.equals(brief)) return c.getString(R.string.ut_hash_fail);
        return brief == null ? "" : brief;
    }

    /** 外部导入结果摘要 */
    public static String importSummary(Context c, IntentImport.Result r) {
        if (r == null) return "";
        StringBuilder sb = new StringBuilder(c.getString(R.string.ut_import_summary, r.ok));
        if (r.skipped > 0) sb.append(c.getString(R.string.ut_import_skipped, r.skipped));
        if (r.failed > 0) sb.append(c.getString(R.string.ut_import_failed, r.failed));
        if (r.cancelled) sb.append(c.getString(R.string.ut_import_cancelled));
        return sb.toString();
    }

    /** 续传原因码 */
    public static String resumeReason(Context c, String code) {
        if (ResumePlan.R_REMOTE_SIZE_UNKNOWN.equals(code))
            return c.getString(R.string.ut_resume_reason_remote_size_unknown);
        if (ResumePlan.R_NO_PART.equals(code)) return c.getString(R.string.ut_resume_reason_no_part);
        if (ResumePlan.R_PART_TOO_BIG.equals(code))
            return c.getString(R.string.ut_resume_reason_part_too_big);
        if (ResumePlan.R_NO_META.equals(code)) return c.getString(R.string.ut_resume_reason_no_meta);
        if (ResumePlan.R_SIZE_CHANGED.equals(code))
            return c.getString(R.string.ut_resume_reason_size_changed);
        if (ResumePlan.R_REMOTE_NEWER.equals(code))
            return c.getString(R.string.ut_resume_reason_remote_newer);
        if (ResumePlan.R_SIZE_MATCH.equals(code))
            return c.getString(R.string.ut_resume_reason_size_match);
        if (ResumePlan.R_REMOTE_UNCHANGED.equals(code))
            return c.getString(R.string.ut_resume_reason_remote_unchanged);
        return code == null ? "" : code;
    }

    /** 续传方案摘要 */
    public static String resumePlan(Context c, ResumePlan.Plan p) {
        if (p == null) return "";
        String reason = resumeReason(c, p.reason);
        if (p.mode == ResumePlan.RESUME)
            return c.getString(R.string.ut_resume, FileOps.formatSize(p.offset), reason);
        if (p.mode == ResumePlan.COMPLETE)
            return c.getString(R.string.ut_resume_complete, reason);
        return c.getString(R.string.ut_resume_fresh, reason);
    }

    /** 抽屉容量两行；StorageStat 只给码 + 已格式化的数据 */
    public static String storageFs(Context c, String code) {
        if (StorageStat.CODE_UNAVAILABLE.equals(code))
            return c.getString(R.string.ut_storage_unavailable);
        if (StorageStat.CODE_FS.equals(part(code, 0))) {
            return c.getString(R.string.ut_storage_fs,
                    part(code, 1), part(code, 2), intPart(code, 3), part(code, 4));
        }
        return code == null ? "" : code;
    }

    public static String storageFree(Context c, String code) {
        if (StorageStat.CODE_UNAVAILABLE.equals(code))
            return c.getString(R.string.ut_storage_unavailable);
        if (StorageStat.CODE_FREE.equals(part(code, 0))) {
            return c.getString(R.string.ut_storage_free, part(code, 1), part(code, 2));
        }
        return code == null ? "" : code;
    }

    /** 云同步预览摘要 */
    public static String syncSummary(Context c, SyncPlan.Plan p) {
        if (p == null) return "";
        StringBuilder sb = new StringBuilder(c.getString(R.string.ut_sync_summary,
                p.upload, p.download, p.conflict, p.skip));
        if (p.localOnly > 0) sb.append('\n').append(
                c.getString(R.string.ut_sync_local_only, p.localOnly));
        if (p.remoteOnly > 0) sb.append('\n').append(
                c.getString(R.string.ut_sync_remote_only, p.remoteOnly));
        return sb.toString();
    }

    /** 桌面小组件顶行容量摘要 */
    public static String widgetSummary(Context c, String code) {
        if (WidgetText.CODE_UNAVAILABLE.equals(code))
            return c.getString(R.string.ut_widget_unavailable);
        if (WidgetText.CODE_LEFT.equals(part(code, 0))) {
            return c.getString(R.string.ut_widget_summary, part(code, 1), part(code, 2));
        }
        return code == null ? "" : code;
    }

    /** 结构化 utils 异常 → 资源文案；非 UtilError 原样透出诊断串 */
    public static String errorText(Context c, Throwable t) {
        if (!(t instanceof UtilError)) {
            String m = t == null ? null : t.getMessage();
            if (m != null && !m.isEmpty()) return m;
            return t == null ? "" : t.getClass().getSimpleName();
        }
        UtilError e = (UtilError) t;
        switch (e.code) {
            case IMG_SRC_MISSING:
                return c.getString(R.string.ut_err_img_src, e.str(0));
            case IMG_BOUNDS_FAIL:
                return c.getString(R.string.ut_err_img_decode_bounds);
            case IMG_DECODE_FAIL:
                return c.getString(R.string.ut_err_img_decode);
            case IMG_ENCODE_FAIL:
                return c.getString(R.string.ut_err_img_encode);
            case IMPORT_NO_DEST:
                return c.getString(R.string.ut_err_no_dest);
            case IMPORT_MKDIR_FAIL:
                return c.getString(R.string.ut_err_mkdir, e.str(0));
            case IMPORT_RENAME_FAIL:
                return c.getString(R.string.ut_err_rename, e.str(0));
            default:
                return e.getMessage();
        }
    }

    /** 导入结果里的第 i 条错误，保留文件名前缀 */
    public static String importError(Context c, IntentImport.Result r, int i) {
        if (r == null || i < 0 || i >= r.errors.size()) return "";
        if (i >= r.causes.size()) return r.errors.get(i);
        String text = errorText(c, r.causes.get(i));
        String name = i < r.errorNames.size() ? r.errorNames.get(i) : null;
        if (name != null && !name.isEmpty()) return name + ": " + text;
        return text;
    }

    /** Shizuku 设置页状态码 */
    public static String shizukuStatus(Context c, String code) {
        String base = part(code, 0);
        if (ShizukuBridge.S_UNSUPPORTED.equals(base))
            return c.getString(R.string.ut_shiz_unsupported);
        if (ShizukuBridge.S_OFF.equals(base)) return c.getString(R.string.ut_shiz_off);
        if (ShizukuBridge.S_NOT_FOUND.equals(base)) return c.getString(R.string.ut_shiz_not_found);
        if (ShizukuBridge.S_UNAUTHORIZED.equals(base))
            return c.getString(R.string.ut_shiz_unauthorized);
        if (ShizukuBridge.S_CONNECTED.equals(base))
            return c.getString(R.string.ut_shiz_connected, part(code, 1));
        return code == null ? "" : code;
    }
}
