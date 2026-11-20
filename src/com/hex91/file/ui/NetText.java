package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.net.NetFsError;

/**
 * 网络存储异常的文案映射（v2.94.0 F-14）。
 *
 * <p>net 层（{@link NetFsError}）只抛码 + 数据参数，句子在这里按资源取，
 * 与同包 {@code HlsDownloadActivity.errorText} / apk 层 {@code PackText} 同套路。
 * 非 NetFsError（网络超时、DNS、系统异常等）保留系统自带 message，原样透出。</p>
 */
public final class NetText {

    private NetText() {
    }

    /** Throwable → 可读文案：只接管 NetFsError，其余原样透出。 */
    public static String errorText(Context c, Throwable t) {
        if (!(t instanceof NetFsError)) {
            String m = t == null ? null : t.getMessage();
            return m != null && !m.isEmpty() ? m
                    : (t == null ? "" : t.getClass().getSimpleName());
        }
        NetFsError e = (NetFsError) t;
        switch (e.code) {
            case EMPTY_ENDPOINT:
                return c.getString(R.string.netfs_err_empty_endpoint);
            case EMPTY_BUCKET:
                return c.getString(R.string.netfs_err_empty_bucket);
            case EMPTY_AK:
                return c.getString(R.string.netfs_err_empty_ak);
            case EMPTY_SK:
                return c.getString(R.string.netfs_err_empty_sk);
            case BAD_ENDPOINT:
                return c.getString(R.string.netfs_err_bad_endpoint, e.str(0));
            case OP_FAIL:
                return c.getString(R.string.netfs_err_op_fail,
                        op(c, e.num(0)), e.str(1), e.num(2),
                        hint(c, e.num(3)), detail(c, e.str(4)));
            case RANGE_IGNORED:
                return c.getString(R.string.netfs_err_range_ignored);
            case TMP_DIR_MISSING:
                return c.getString(R.string.netfs_err_tmp_missing);
            case TMP_DIR_FAIL:
                return c.getString(R.string.netfs_err_tmp_dir, e.str(0));
            case TOO_LARGE:
                return c.getString(R.string.netfs_err_too_large);
            case RENAME_DETAIL:
                return c.getString(R.string.netfs_err_rename_detail, e.str(0));
            case RESP_PARSE:
                return c.getString(R.string.netfs_err_resp_parse, e.str(0));
            case SHA_UNAVAILABLE:
                return c.getString(R.string.netfs_err_sha_unavailable);
            default:
                return c.getString(R.string.netfs_err_unknown);
        }
    }

    /** 操作码 → 动词短语（"列目录" / "读取" …），供整句模板引用。 */
    private static String op(Context c, int code) {
        switch (code) {
            case NetFsError.OP_LIST:   return c.getString(R.string.netfs_op_list);
            case NetFsError.OP_READ:   return c.getString(R.string.netfs_op_read);
            case NetFsError.OP_RESUME: return c.getString(R.string.netfs_op_resume);
            case NetFsError.OP_UPLOAD: return c.getString(R.string.netfs_op_upload);
            case NetFsError.OP_MKDIR:  return c.getString(R.string.netfs_op_mkdir);
            case NetFsError.OP_DELETE: return c.getString(R.string.netfs_op_delete);
            case NetFsError.OP_RENAME: return c.getString(R.string.netfs_op_rename);
            default:                   return "";
        }
    }

    /** HTTP 状态码 → 括号提示（整句在资源里，含前导空格与全角括号）。 */
    private static String hint(Context c, int code) {
        switch (code) {
            case NetFsError.HINT_S3_REGION:   return c.getString(R.string.netfs_hint_s3_region);
            case NetFsError.HINT_S3_SIGN:     return c.getString(R.string.netfs_hint_s3_sign);
            case NetFsError.HINT_S3_NOTFOUND: return c.getString(R.string.netfs_hint_s3_notfound);
            default:                          return "";
        }
    }

    /** 服务端 Message 片段（整句在资源里，含「 · 」前导分隔符）。 */
    private static String detail(Context c, String msg) {
        return msg == null || msg.isEmpty() ? "" : c.getString(R.string.netfs_detail_sep, msg);
    }
}
