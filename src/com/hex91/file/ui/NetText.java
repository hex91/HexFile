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

            // ---- 云盘通用码 ----
            case NOT_AUTHED:
                return c.getString(R.string.netfs_err_not_authed, cloud(c, e.num(0)));
            case AUTH_LOST:
                return c.getString(R.string.netfs_err_auth_lost, cloud(c, e.num(0)));
            case AUTH_EXPIRED:
                return c.getString(R.string.netfs_err_auth_expired, cloud(c, e.num(0)), e.str(1));
            case API_FAIL:
                return c.getString(R.string.netfs_err_api_fail, e.str(0), e.str(1), e.str(2),
                        hint(c, e.num(3)));
            case HTTP_FAIL:
                return c.getString(R.string.netfs_err_http_fail, e.str(0), e.num(1), hint(c, e.num(2)));
            case HTTP_DETAIL_FAIL:
                return c.getString(R.string.netfs_err_http_detail, e.str(0), e.num(1),
                        hint(c, e.num(2)), detail(c, e.str(3)));
            case PATH_NOT_FOUND:
                return c.getString(R.string.netfs_err_path_not_found, e.str(0));
            case NO_DOWNLOAD_URL:
                return c.getString(R.string.netfs_err_no_download_url, e.str(0));
            case ROOT_NOT_ALLOWED:
                return c.getString(R.string.netfs_err_root_not_allowed, action(c, e.str(0)));
            case BAD_NAME:
                return nameError(c, e.num(0), e.str(1));
            case FIELD_REQUIRED:
                return c.getString(R.string.netfs_err_field_required, e.str(0));
            case QUOTA_EXHAUSTED:
                return c.getString(R.string.netfs_err_quota);
            case TOO_MANY_REQUESTS:
                return c.getString(R.string.netfs_err_too_many);
            case FILE_TYPE_FORBIDDEN:
                return c.getString(R.string.netfs_err_file_type);
            case NAME_INVALID:
                return c.getString(R.string.netfs_err_name_invalid);
            case NO_DRIVE_ID:
                return c.getString(R.string.netfs_err_no_drive_id);
            case NO_PART_URLS:
                return c.getString(R.string.netfs_err_no_part_urls);
            case CREATE_UPLOAD_FAIL:
                return c.getString(R.string.netfs_err_create_upload);
            case LOCAL_EOF_EARLY:
                return c.getString(R.string.netfs_err_local_eof);
            case PART_UPLOAD_FAIL:
                return c.getString(R.string.netfs_err_part_upload, e.num(0), detail(c, e.str(1)));
            case UPLOAD_TOO_LARGE:
                return c.getString(R.string.netfs_err_upload_too_large, e.str(0), e.str(1), e.str(2));
            case EMPTY_UPLOAD_PATH:
                return c.getString(R.string.netfs_err_empty_upload_path);
            case NO_ITEM_ID:
                return c.getString(R.string.netfs_err_no_item_id, e.str(0));

            // ---- 传统协议码 ----
            case PROTO_FAIL:
                return c.getString(R.string.netfs_err_proto_fail, proto(c, e.str(0)),
                        e.str(1), detail(c, e.str(2)), hint(c, e.num(3)));
            case CONNECT_FAIL:
                return c.getString(R.string.netfs_err_connect_fail, proto(c, e.str(0)),
                        detail(c, e.str(1)), hint(c, e.num(2)));
            case DISCONNECTED:
                return c.getString(R.string.netfs_err_disconnected, proto(c, e.str(0)));
            case BAD_GREETING:
                return c.getString(R.string.netfs_err_bad_greeting, detail(c, e.str(0)));
            case PASV_FAIL:
                return c.getString(R.string.netfs_err_pasv_fail, proto(c, e.str(0)),
                        detail(c, e.str(1)), hint(c, e.num(2)));
            case REPLY_PARSE:
                return c.getString(R.string.netfs_err_reply_parse, proto(c, e.str(0)),
                        detail(c, e.str(1)));
            case TRANSFER_INCOMPLETE:
                return c.getString(R.string.netfs_err_transfer_incomplete, proto(c, e.str(0)),
                        e.str(1), detail(c, e.str(2)));
            case OP_FAIL_REASON:
                return c.getString(R.string.netfs_err_proto_fail, proto(c, e.str(0)),
                        op(c, e.num(1)), detail(c, e.str(3)), hint(c, e.num(4)));
            case RANGE_MISMATCH:
                return c.getString(R.string.netfs_err_range_mismatch, e.str(0));
            case UPLOAD_FAIL:
                return c.getString(R.string.netfs_err_upload_fail, detail(c, e.str(0)));
            case STREAM_CLOSED:
                return c.getString(R.string.netfs_err_stream_closed);
            case MKDIR_FAIL:
                return c.getString(R.string.netfs_err_mkdir_fail, e.str(0), e.num(1));

            // ---- OAuth 码 ----
            case OAUTH_DEVICE_CODE:
                return c.getString(R.string.netfs_err_oauth_device, detail(c, e.str(0)));
            case OAUTH_HTTP:
                return c.getString(R.string.netfs_err_oauth_http, e.num(0), e.str(1));

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
            case NetFsError.HINT_CLOUD_NOT_FOUND:    return c.getString(R.string.netfs_hint_cloud_not_found);
            case NetFsError.HINT_CLOUD_AUTH_EXPIRED: return c.getString(R.string.netfs_hint_cloud_auth);
            case NetFsError.HINT_CLOUD_NO_PERM:      return c.getString(R.string.netfs_hint_cloud_perm);
            case NetFsError.HINT_CLOUD_QUOTA:        return c.getString(R.string.netfs_hint_cloud_quota);
            case NetFsError.HINT_CLOUD_413:          return c.getString(R.string.netfs_hint_cloud_413);
            case NetFsError.HINT_ALI_NOT_FOUND:  return c.getString(R.string.netfs_hint_ali_not_found);
            case NetFsError.HINT_ALI_QUOTA:      return c.getString(R.string.netfs_hint_ali_quota);
            case NetFsError.HINT_ALI_BUSY:       return c.getString(R.string.netfs_hint_ali_busy);
            case NetFsError.HINT_ALI_FILE_TYPE:  return c.getString(R.string.netfs_hint_ali_file_type);
            case NetFsError.HINT_ALI_NAME:       return c.getString(R.string.netfs_hint_ali_name);
            case NetFsError.HINT_WEBDAV_NOT_FOUND:   return c.getString(R.string.netfs_hint_cloud_not_found);
            case NetFsError.HINT_WEBDAV_NO_METHOD:   return c.getString(R.string.netfs_hint_cloud_no_method);
            case NetFsError.HINT_FTP_LOGIN:   return c.getString(R.string.netfs_hint_ftp_login);
            case NetFsError.HINT_FTP_PASV:   return c.getString(R.string.netfs_hint_ftp_pasv);
            case NetFsError.HINT_FTP_NO_PERM: return c.getString(R.string.netfs_hint_ftp_no_perm);
            case NetFsError.HINT_SFTP_CONNECT: return c.getString(R.string.netfs_hint_sftp_connect);
            case NetFsError.HINT_SFTP_NOT_FOUND: return c.getString(R.string.netfs_hint_sftp_not_found);
            case NetFsError.HINT_SFTP_NO_PERM: return c.getString(R.string.netfs_hint_sftp_no_perm);
            case NetFsError.HINT_SFTP_DISCONNECTED: return c.getString(R.string.netfs_hint_sftp_disconnected);
            case NetFsError.HINT_WEBDAV_AUTH:       return c.getString(R.string.netfs_hint_webdav_auth);
            case NetFsError.HINT_WEBDAV_FORBIDDEN:  return c.getString(R.string.netfs_hint_webdav_forbidden);
            default:                          return "";
        }
    }

    /** 协议名（ASCII 常量 → 展示名，专有名词中英同形，仍走资源便于统一）。 */
    private static String proto(Context c, String code) {
        if (NetFsError.PROTO_FTP.equals(code))    return c.getString(R.string.netfs_proto_ftp);
        if (NetFsError.PROTO_SFTP.equals(code))   return c.getString(R.string.netfs_proto_sftp);
        if (NetFsError.PROTO_WEBDAV.equals(code)) return c.getString(R.string.netfs_proto_webdav);
        if (NetFsError.PROTO_SMB.equals(code))    return c.getString(R.string.netfs_proto_smb);
        return code;
    }

    /** 云盘名（码 → 资源），给「尚未授权 / 授权失效」整句当参数。 */
    private static String cloud(Context c, int code) {
        switch (code) {
            case NetFsError.CLOUD_ALIYUN:   return c.getString(R.string.netfs_cloud_aliyun);
            case NetFsError.CLOUD_ONEDRIVE: return c.getString(R.string.netfs_cloud_onedrive);
            default:                        return "";
        }
    }

    /** 根目录动作（ASCII 码 → 资源词），给「不能对根目录 X」整句当参数。 */
    private static String action(Context c, String code) {
        if ("download".equals(code)) return c.getString(R.string.netfs_action_download);
        if ("operate".equals(code))  return c.getString(R.string.netfs_action_operate);
        return "";
    }

    /** CloudPath.NameError → 文案（名称非法的五种原因）。 */
    private static String nameError(Context c, int code, String arg) {
        switch (code) {
            case com.hex91.file.net.CloudPath.NameError.EMPTY:
                return c.getString(R.string.netfs_name_empty);
            case com.hex91.file.net.CloudPath.NameError.TOO_LONG:
                return c.getString(R.string.netfs_name_too_long);
            case com.hex91.file.net.CloudPath.NameError.BAD_CHAR:
                return c.getString(R.string.netfs_name_bad_char, arg);
            case com.hex91.file.net.CloudPath.NameError.CONTROL_CHAR:
                return c.getString(R.string.netfs_name_control);
            case com.hex91.file.net.CloudPath.NameError.DOT_ONLY:
                return c.getString(R.string.netfs_name_dot);
            default:
                return c.getString(R.string.netfs_err_name_invalid);
        }
    }

    /** 服务端 Message 片段（整句在资源里，含「 · 」前导分隔符）。 */
    private static String detail(Context c, String msg) {
        return msg == null || msg.isEmpty() ? "" : c.getString(R.string.netfs_detail_sep, msg);
    }
}
