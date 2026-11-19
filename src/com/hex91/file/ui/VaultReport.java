package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.core.VaultCrypto;

/**
 * 隐私空间文案层（v2.43.0，F-14/30）。
 *
 * <p>{@link VaultCrypto} 及其同模块的 {@code VaultStore}/{@code VaultIndex} 是纯 JDK 逻辑层，
 * 只抛带 {@link VaultCrypto.Code} 的异常（类型保留：{@code Fail}/{@code AuthFail}/
 * {@code StateFail}/{@code IoFail}，分别继承 IllegalArgumentException/SecurityException/
 * IllegalStateException/IOException，不改变调用方的 catch 控制流）。面向用户的错误说明统一在
 * 这里按资源拼装，逻辑层不产出任何文案。</p>
 *
 * <p>非本模块的异常（例如调用方用资源串自己构造的 IOException）保留其原始 message，
 * 不套「失败：」前缀，避免二次包装把已有的完整提示割裂。</p>
 */
public final class VaultReport {

    private VaultReport() {}

    /**
     * 把任意异常翻成可显示文案。
     *
     * @return 带码异常按码翻译；{@code Cancelled} 回落「已取消」；
     *         其余保留原始 message（空则由调用方决定兜底，这里给通用串）
     */
    public static String msg(Context c, Throwable t) {
        if (t == null) return "";
        if (t instanceof VaultCrypto.Cancelled) return c.getString(R.string.vault_cancel);
        if (t instanceof VaultCrypto.VaultCoded) return code(c, (VaultCrypto.VaultCoded) t);
        String m = t.getMessage();
        return m == null || m.isEmpty() ? c.getString(R.string.vc_err_generic) : m;
    }

    private static String code(Context c, VaultCrypto.VaultCoded e) {
        String arg = e.arg();
        switch (e.code()) {
            case MASTER_LEN:
                return c.getString(R.string.vc_err_master_len, arg);
            case PASSWORD_EMPTY:
                return c.getString(R.string.vc_err_password_empty);
            case SALT_EMPTY:
                return c.getString(R.string.vc_err_salt_empty);
            case ITER_RANGE:
                return c.getString(R.string.vc_err_iter_range);
            case HEADER_LEN:
                return c.getString(R.string.vc_err_header_len, arg);
            case HEADER_MAGIC:
                return c.getString(R.string.vc_err_header_magic);
            case HEADER_ITER:
                return c.getString(R.string.vc_err_header_iter, arg);
            case HEADER_SALT_LEN:
                return c.getString(R.string.vc_err_header_salt_len);
            case WRONG_PASSWORD:
                return c.getString(R.string.vc_err_wrong_password);
            case HEADER_BROKEN:
                return c.getString(R.string.vc_err_header_broken);
            case ENC_SRC_MISSING:
                return c.getString(R.string.vc_err_enc_src_missing);
            case DEC_SRC_MISSING:
                return c.getString(R.string.vc_err_dec_src_missing);
            case NO_MASTER:
                return c.getString(R.string.vc_err_no_master);
            case FILE_LEN_BAD:
                return c.getString(R.string.vc_err_file_len_bad);
            case FILE_DAMAGED:
                return c.getString(R.string.vc_err_file_damaged);
            case FILE_VERIFY_FAIL:
                return c.getString(R.string.vc_err_file_verify_fail);
            case BLOB_MAGIC:
                return c.getString(R.string.vc_err_blob_magic);
            case BLOB_VERSION:
                return c.getString(R.string.vc_err_blob_version, arg);
            case FILE_ID_MISMATCH:
                return c.getString(R.string.vc_err_file_id_mismatch);
            case ID_MISSING:
                return c.getString(R.string.vc_err_id_missing);
            case ID_NOT_HEX:
                return c.getString(R.string.vc_err_id_not_hex);
            case ID_LEN_BAD:
                return c.getString(R.string.vc_err_id_len_bad);
            case PLAIN_EMPTY:
                return c.getString(R.string.vc_err_plain_empty);
            case BOX_LEN_BAD:
                return c.getString(R.string.vc_err_box_len_bad);
            case BOX_MAGIC:
                return c.getString(R.string.vc_err_box_magic);
            case BOX_VERSION:
                return c.getString(R.string.vc_err_box_version);
            case BOX_VERIFY_FAIL:
                return c.getString(R.string.vc_err_box_verify_fail);
            case ID_FORMAT:
                return c.getString(R.string.vc_err_id_format);
            case INDEX_EMPTY:
                return c.getString(R.string.vc_err_index_empty);
            case INDEX_MAGIC:
                return c.getString(R.string.vc_err_index_magic);
            case DIR_NULL:
                return c.getString(R.string.vc_err_dir_null);
            case PASSWORD_SHORT:
                return c.getString(R.string.vc_err_password_short);
            case ALREADY_EXISTS:
                return c.getString(R.string.vc_err_already_exists);
            case MKDIR_FAIL:
                return c.getString(R.string.vc_err_mkdir_fail, arg);
            case WRITE_FAIL:
                return c.getString(R.string.vc_err_write_fail, arg);
            case INDEX_WRITE_FAIL:
                return c.getString(R.string.vc_err_index_write_fail);
            case ENTRY_MISSING:
                return c.getString(R.string.vc_err_entry_missing);
            case BLOB_MISSING:
                return c.getString(R.string.vc_err_blob_missing, arg);
            case NAME_EMPTY:
                return c.getString(R.string.vc_err_name_empty);
            case FILE_MISSING:
                return c.getString(R.string.vc_err_file_missing, arg);
            case FILE_TOO_BIG:
                return c.getString(R.string.vc_err_file_too_big, arg);
            case READ_URI_FAIL:
                return c.getString(R.string.vc_err_read_uri);
            default:
                return c.getString(R.string.vc_err_generic);
        }
    }
}
