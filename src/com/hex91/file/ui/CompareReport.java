package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.apk.ApkCompareUtil;
import com.hex91.file.core.TextDiffUtil;
import com.hex91.file.fs.CompareUtil;

/**
 * 对比工具文案层（v2.38.0）。
 *
 * <p>目录 / 文本 / 哈希 / APK 四种对比的结果数据分别由
 * {@link CompareUtil}、{@link TextDiffUtil}、{@link ApkCompareUtil} 产出，
 * 三者都只给状态码与数值；面向用户的标题、汇总、单条说明与可复制报告统一在这里拼装。</p>
 *
 * <p>报告里 {@code A:} / {@code B:} / {@code SHA-256:} / {@code ---} / {@code +++} /
 * {@code @@} 这些是格式记号，中英共用同一套（diff 惯例），不进资源。</p>
 */
public final class CompareReport {

    private CompareReport() {}

    // ============================================================ 标题

    /** 模式标题（窗口顶部第一行） */
    public static String modeTitle(Context c, String mode) {
        if (CompareActivity.MODE_FOLDER.equals(mode)) return c.getString(R.string.cmp_mode_folder);
        if (CompareActivity.MODE_TEXT.equals(mode)) return c.getString(R.string.cmp_mode_text);
        if (CompareActivity.MODE_HASH.equals(mode)) return c.getString(R.string.cmp_mode_hash);
        if (CompareActivity.MODE_APK.equals(mode)) return c.getString(R.string.cmp_mode_apk);
        return c.getString(R.string.cmp_mode_other);
    }

    /** 顶部标题：模式 + 两侧文件名 */
    public static String title(Context c, String mode, String nameA, String nameB) {
        return c.getString(R.string.cmp_title, modeTitle(c, mode), nameA, nameB);
    }

    // ============================================================ 目录对比

    /** 目录对比汇总（A/B 路径 + 四类计数 + 哈希/截断/遍历警告） */
    public static String folderSummary(Context c, CompareUtil.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.getString(R.string.cmp_sum_ab, r.rootA, r.rootB)).append('\n');
        sb.append(c.getString(R.string.cmp_sum_counts,
                r.nAdded, r.nModified, r.nDeleted, r.nSame));
        if (r.hashVerified) sb.append(c.getString(R.string.cmp_sum_hash));
        if (r.truncated) sb.append(c.getString(R.string.cmp_sum_trunc, CompareUtil.MAX_ITEMS));
        if (r.error != null) sb.append(c.getString(R.string.cmp_sum_err, r.error));
        return sb.toString();
    }

    /** 单条差异说明；无说明时返回空串（调用方据此决定是否加副标题行） */
    public static String note(Context c, CompareUtil.Item it) {
        switch (it.noteKind) {
            case CompareUtil.NOTE_SIZE:
                return c.getString(R.string.cmp_note_size,
                        CompareUtil.fmtSize(it.sizeA), CompareUtil.fmtSize(it.sizeB));
            case CompareUtil.NOTE_TIME:
                return c.getString(R.string.cmp_note_time,
                        CompareUtil.fmtSize(it.sizeA), CompareUtil.fmtSize(it.sizeB));
            case CompareUtil.NOTE_HASH:
                return c.getString(R.string.cmp_note_hash,
                        nz(it.noteA), nz(it.noteB));
            case CompareUtil.NOTE_CRC:
                return c.getString(R.string.cmp_note_crc, crc(it.crcA), crc(it.crcB));
            default:
                return "";
        }
    }

    /** 目录对比可复制报告：汇总 + 每行「+/-/~ 路径  (说明)」 */
    public static String folderReport(Context c, CompareUtil.Result r) {
        StringBuilder sb = new StringBuilder(folderSummary(c, r)).append('\n');
        for (CompareUtil.Item it : r.items) {
            sb.append(sign(it)).append(it.relPath).append(it.dir ? "/" : "");
            String n = note(c, it);
            if (!n.isEmpty()) sb.append(c.getString(R.string.cmp_note_wrap, n));
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 差异标记：+ 新增 / - 删除 / ~ 修改 */
    public static String sign(CompareUtil.Item it) {
        if (it.status == CompareUtil.ADDED) return "+ ";
        if (it.status == CompareUtil.DELETED) return "- ";
        return "~ ";
    }

    // ============================================================ 文本对比

    /** 文本对比汇总：A 行数(编码) → B 行数 · 新增 · 删除 */
    public static String textSummary(Context c, TextDiffUtil.Result r) {
        return c.getString(R.string.cmp_text_summary,
                r.totalA, r.encoding, r.totalB, r.nAdd, r.nDel);
    }

    /** 无法比对的原因（超字节 / 超行数 / 其它异常） */
    public static String textError(Context c, TextDiffUtil.Result r) {
        if (r.errCode == TextDiffUtil.ERR_TOO_BIG_BYTES)
            return c.getString(R.string.cmp_text_err_bytes, r.errArg);
        if (r.errCode == TextDiffUtil.ERR_TOO_MANY_LINES)
            return c.getString(R.string.cmp_text_err_lines, r.errArg);
        return r.error == null ? c.getString(R.string.cmp_text_unknown) : r.error;
    }

    /** 文本对比可复制报告（unified 风格：--- / +++ / @@ 为 diff 惯例，不翻译） */
    public static String textReport(Context c, TextDiffUtil.Result r, String nameA, String nameB) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(nameA).append('\n');
        sb.append("+++ ").append(nameB).append('\n');
        if (r.tooBig || r.error != null) {
            sb.append("!! ").append(textError(c, r)).append('\n');
            return sb.toString();
        }
        sb.append(c.getString(R.string.cmp_diff_stat,
                r.totalA, r.totalB, r.nAdd, r.nDel)).append('\n');
        for (TextDiffUtil.Line l : r.lines) {
            if (l.type == TextDiffUtil.EQ) continue;
            sb.append(l.type == TextDiffUtil.ADD ? "+ " : "- ");
            if (l.type == TextDiffUtil.ADD) sb.append(l.bNo).append('\t');
            else sb.append(l.aNo).append('\t');
            sb.append(l.text).append('\n');
        }
        return sb.toString();
    }

    // ============================================================ 哈希对比

    /** 哈希报告头：标题 + 两侧绝对路径 */
    public static String hashReportHead(Context c, String pathA, String pathB) {
        return c.getString(R.string.cmp_hash_report_head, pathA, pathB);
    }

    /** 一行哈希：算法名 + 值（读取失败用 ? 占位） */
    public static String hashLine(Context c, String alg, String hash) {
        return c.getString(R.string.cmp_hash_line, alg, hash == null ? "?" : hash);
    }

    // ============================================================ APK 对比

    /** APK 对比汇总：两侧大小 + SHA 是否一致 + 是否完全一致 */
    public static String apkSummary(Context c, ApkCompareUtil.Result r) {
        StringBuilder sb = new StringBuilder(c.getString(R.string.cmp_apk_summary,
                CompareUtil.fmtSize(r.sizeA), CompareUtil.fmtSize(r.sizeB)));
        if (r.shaA != null && r.shaA.equals(r.shaB))
            sb.append(c.getString(R.string.cmp_apk_same_sha));
        boolean allSame = r.nRowDiff == 0
                && (r.entries == null || r.entries.items.isEmpty());
        if (allSame) sb.append('\n').append(c.getString(R.string.cmp_apk_identical));
        return sb.toString();
    }

    /** 清单差异段标题 */
    public static String apkManifestHead(Context c, int nDiff) {
        return c.getString(R.string.cmp_apk_manifest_head, nDiff);
    }

    /** 条目差异段标题 */
    public static String apkEntriesHead(Context c, int added, int modified, int deleted) {
        return c.getString(R.string.cmp_apk_entries_head, added, modified, deleted);
    }

    /** APK 对比可复制报告 */
    public static String apkReport(Context c, ApkCompareUtil.Result r,
            String nameA, String nameB) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.getString(R.string.cmp_apk_report_title)).append('\n');
        sb.append(c.getString(R.string.cmp_apk_r_ab, nameA,
                CompareUtil.fmtSize(r.sizeA), nameB, CompareUtil.fmtSize(r.sizeB))).append('\n');
        sb.append(c.getString(R.string.cmp_apk_r_sha, r.shaA == null ? "?" : r.shaA)).append('\n');
        sb.append(c.getString(R.string.cmp_apk_r_sha, r.shaB == null ? "?" : r.shaB)).append('\n');
        sb.append(c.getString(R.string.cmp_apk_r_manifest, r.nRowDiff)).append('\n');
        for (ApkCompareUtil.RowDiff rd : r.rows) {
            if (rd.same) continue;
            sb.append("· ").append(rd.label).append('\n');
            sb.append(c.getString(R.string.cmp_apk_r_va,
                    rd.valA == null ? c.getString(R.string.cmp_apk_none) : rd.valA)).append('\n');
            sb.append(c.getString(R.string.cmp_apk_r_vb,
                    rd.valB == null ? c.getString(R.string.cmp_apk_none) : rd.valB)).append('\n');
        }
        CompareUtil.Result e = r.entries;
        if (e != null) {
            sb.append(c.getString(R.string.cmp_apk_r_entries,
                    e.nAdded, e.nModified, e.nDeleted)).append('\n');
            for (CompareUtil.Item it : e.items) {
                sb.append(sign(it)).append(it.relPath);
                String n = note(c, it);
                if (!n.isEmpty()) sb.append(c.getString(R.string.cmp_note_wrap, n));
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 辅助

    private static String crc(long v) { return String.format(java.util.Locale.US, "%08X", v); }

    private static String nz(String s) { return s == null ? "?" : s; }
}
