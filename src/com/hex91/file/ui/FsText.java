package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.fs.DirReader;
import com.hex91.file.fs.DiskScanUtil;
import com.hex91.file.fs.FsError;
import com.hex91.file.fs.FileClipboard;
import com.hex91.file.fs.FileItem;
import com.hex91.file.fs.FileTask;

/**
 * fs 层稳定码 → 界面文案（v2.103.0，F-14/90）。
 *
 * <p>fs 是纯 Java 逻辑层，不能引用 {@code android.*}。原来 FileClipboard.label()、
 * FileTask.stateText() / title、FileItem.name() / detail()、DiskScanUtil 阶段回调
 * 都直接给中文，切英文只能硬翻或保留中文。现在这些入口只返回稳定 ASCII 码，
 * 由本类统一映射到资源；未知码原样透传，兼容后续新增状态。</p>
 */
public final class FsText {

    private FsText() {}

    /** 从 "PREFIX:123" 码串里取整数；取不到返回 -1 */
    private static int count(String code) {
        if (code == null) return -1;
        int i = code.indexOf(':');
        if (i < 0 || i == code.length() - 1) return -1;
        try {
            return Integer.parseInt(code.substring(i + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String codeOf(String code) {
        if (code == null) return "";
        int i = code.indexOf(':');
        return i < 0 ? code : code.substring(0, i);
    }

    /** 剪贴板按钮 / 提示共用的一行标签 */
    public static String clipboardLabel(Context c, FileClipboard cb) {
        String code = cb == null ? FileClipboard.LABEL_EMPTY : cb.label();
        if (FileClipboard.LABEL_EMPTY.equals(code)) return c.getString(R.string.fs_clip_empty);
        int n = count(code);
        if (n >= 0) {
            if (FileClipboard.LABEL_COPY.equals(codeOf(code)))
                return c.getString(R.string.fs_clip_copy, n);
            if (FileClipboard.LABEL_MOVE.equals(codeOf(code)))
                return c.getString(R.string.fs_clip_move, n);
        }
        return code;
    }

    /** 任务条标题：FileTask.title 只存 "COPY:3" / "MOVE:3" */
    public static String taskTitle(Context c, FileTask t) {
        String code = t == null ? null : t.title;
        int n = count(code);
        if (n >= 0) {
            if (FileTask.TITLE_COPY.equals(codeOf(code)))
                return c.getString(R.string.fs_task_copy, n);
            if (FileTask.TITLE_MOVE.equals(codeOf(code)))
                return c.getString(R.string.fs_task_move, n);
        }
        return code == null ? "" : code;
    }

    /** 任务状态：运行中本来就是 "NN%"，原样透传 */
    public static String stateText(Context c, FileTask t) {
        String code = t == null ? null : t.stateText();
        if (code == null) return "";
        if (FileTask.STATE_QUEUED.equals(code)) return c.getString(R.string.fs_state_queued);
        if (FileTask.STATE_DONE.equals(code)) return c.getString(R.string.fs_state_done);
        if (FileTask.STATE_PARTIAL.equals(code)) return c.getString(R.string.fs_state_partial);
        if (FileTask.STATE_FAILED.equals(code)) return c.getString(R.string.fs_state_failed);
        if (FileTask.STATE_CANCELLED.equals(code)) return c.getString(R.string.fs_state_cancelled);
        if (FileTask.STATE_PAUSED.equals(code)) return c.getString(R.string.fs_state_paused);
        return code;
    }

    /** 文件名：加载占位行的码在这里翻，真实文件名是数据原样返回 */
    public static String itemName(Context c, FileItem it) {
        if (it == null) return "";
        if (FileItem.CODE_LOADING.equals(it.name())) return c.getString(R.string.fs_item_loading);
        return it.name();
    }

    /** 文件详情行：只有逻辑层自己写死的两条短标签需要翻，时间/大小是格式化数据 */
    public static String itemDetail(Context c, FileItem it) {
        String code = it == null ? null : it.detail();
        if (FileItem.DETAIL_UP.equals(code)) return c.getString(R.string.fs_item_up);
        if (FileItem.DETAIL_DIR.equals(code)) return c.getString(R.string.fs_item_dir);
        return code == null ? "" : code;
    }

    /** 链接后缀：目标本身是数据，只有 broken 标记来自代码，换成资源文案 */
    public static String linkSuffix(Context c, FileItem it) {
        String s = it == null ? null : it.linkSuffix();
        if (s == null) return null;
        return s.replace(FileItem.LINK_BROKEN, c.getString(R.string.fs_link_broken));
    }

    /** 重复文件查找的阶段提示 */
    public static String stageText(Context c, int stage) {
        switch (stage) {
            case DiskScanUtil.STAGE_COLLECT: return c.getString(R.string.fs_stage_collect);
            case DiskScanUtil.STAGE_FINGERPRINT: return c.getString(R.string.fs_stage_fingerprint);
            case DiskScanUtil.STAGE_VERIFY: return c.getString(R.string.fs_stage_verify);
            default: return "";
        }
    }

    /** 读目录失败：Result.error 是 ASCII 诊断串，UNKNOWN 走资源 */
    public static String readError(Context c, String error) {
        if (error == null || DirReader.ERROR_UNKNOWN.equals(error))
            return c.getString(R.string.fs_read_unknown);
        return error;
    }

    /** 结构化 fs 异常 → 资源文案；非 FsError 原样透出诊断串 */
    public static String errorText(Context c, Throwable t) {
        if (!(t instanceof FsError)) {
            String m = t == null ? null : t.getMessage();
            if (m != null && !m.isEmpty()) return m;
            return t == null ? "" : t.getClass().getSimpleName();
        }
        FsError e = (FsError) t;
        switch (e.code) {
            case SRC_MISSING:
                return c.getString(R.string.fs_err_src_missing, e.str(0));
            case CHUNK_INVALID:
                return c.getString(R.string.fs_err_chunk_invalid);
            case EMPTY_SOURCE:
                return c.getString(R.string.fs_err_empty_source);
            case TOO_SMALL:
                return c.getString(R.string.fs_err_too_small, e.lng(0), e.lng(1));
            case PART_MISSING:
                return c.getString(R.string.fs_err_part_missing, e.str(0));
            case NOT_PART:
                return c.getString(R.string.fs_err_not_part, e.str(0));
            case NO_SEQUENCE:
                return c.getString(R.string.fs_err_no_sequence);
            case START_MISSING:
                return c.getString(R.string.fs_err_start_missing, e.str(0));
            case PARTS_INCOMPLETE:
                return c.getString(R.string.fs_err_parts_incomplete,
                        e.num(0), e.num(1), e.str(2));
            case SINGLE_PART:
                return c.getString(R.string.fs_err_single_part, e.str(0));
            case PART_SIZE_MISMATCH:
                return c.getString(R.string.fs_err_part_size, e.str(0));
            case MERGE_EMPTY:
                return c.getString(R.string.fs_err_merge_empty);
            case MERGE_SIZE:
                return c.getString(R.string.fs_err_merge_size, e.lng(0), e.lng(1));
            case MKDIR_FAIL:
                return c.getString(R.string.fs_err_mkdir, e.str(0));
            case DELETE_SRC_FAIL:
                return c.getString(R.string.fs_err_delete_src, e.str(0));
            case DEEP_PATH:
                return c.getString(R.string.fs_err_deep, e.str(0));
            case TARGET_BUSY:
                return c.getString(R.string.fs_err_target_busy, e.str(0));
            case CREATE_FAIL:
                return c.getString(R.string.fs_err_create, e.str(0));
            case READ_FAIL:
                return c.getString(R.string.fs_err_read, e.str(0));
            case WRITE_FAIL:
                return c.getString(R.string.fs_err_write, e.str(0));
            case RESUME_EOF:
                return c.getString(R.string.fs_err_resume_eof, e.lng(0));
            case NULL_PATH:
                return c.getString(R.string.fs_err_null_path);
            case LINK_UNSUPPORTED:
                return c.getString(R.string.fs_err_link_unsupported, e.str(0));
            case ZIP_WRITE_FAIL:
                return c.getString(R.string.fs_err_zip_write, e.str(0));
            case ZIP_RENAME_FAIL:
                return c.getString(R.string.fs_err_zip_rename, e.str(0));
            case NO_INPUT:
                return c.getString(R.string.fs_err_no_input);
            case BAD_MAGIC:
                return c.getString(R.string.fs_err_bad_magic, e.str(0));
            case OUTDIR_FAIL:
                return c.getString(R.string.fs_err_outdir, e.str(0));
            case LIST_FAIL:
                return c.getString(R.string.fs_err_list, e.str(0));
            case RENAME_FAIL:
                return c.getString(R.string.fs_err_rename, e.str(0));
            case TARGET_EXISTS:
                return c.getString(R.string.fs_err_target_exists, e.str(0));
            case BAD_PATH:
                return c.getString(R.string.fs_err_bad_path);
            case UNSUPPORTED:
                return c.getString(R.string.fs_err_unsupported, e.str(0));
            default:
                return e.getMessage();
        }
    }

    /** 任务详情里的失败原因：单文件失败保留文件名前缀 */
    public static String taskError(Context c, FileTask t) {
        if (t == null) return "";
        if (t.error instanceof FsError) {
            String text = errorText(c, t.error);
            if (t.errorName != null && !t.errorName.isEmpty()) return t.errorName + ": " + text;
            return text;
        }
        return t.message == null ? "" : t.message;
    }
}
