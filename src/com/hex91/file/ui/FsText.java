package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.fs.DirReader;
import com.hex91.file.fs.DiskScanUtil;
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
}
