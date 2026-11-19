package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.apk.KeyStoreUtil;

/**
 * keystore 条目文案层。
 *
 * <p>{@link KeyStoreUtil.AliasInfo} 只持有解析出的数据（别名 / 类型 / 主题 / 有效期 /
 * 指纹 …），面向用户的中英文说明统一在这里按资源拼装，避免逻辑层产出文案。</p>
 *
 * <p>多行格式由「每行一条资源 + {@code '\n'} 拼接」构成，行内标签随语言变化，
 * 但行数与顺序固定，便于对照。</p>
 */
public final class KsReport {

    private KsReport() {}

    /** 单行摘要（列表展示用）：{@code 别名（RSA）} */
    public static String summary(Context c, KeyStoreUtil.AliasInfo a) {
        String type = a.keyEntry
                ? c.getString(R.string.ks_rep_type_key, a.keyAlg == null ? "" : a.keyAlg)
                : c.getString(R.string.ks_rep_type_cert);
        return c.getString(R.string.ks_rep_summary, a.alias, type);
    }

    /** 多行详情（详情弹窗用） */
    public static String detail(Context c, KeyStoreUtil.AliasInfo a) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.getString(R.string.ks_rep_alias, a.alias)).append('\n');
        sb.append(c.getString(R.string.ks_rep_type, a.keyEntry
                ? c.getString(R.string.ks_rep_type_key, a.keyAlg == null ? "" : a.keyAlg)
                : c.getString(R.string.ks_rep_type_cert))).append('\n');
        if (!a.keyReadable && a.keyEntry)
            sb.append(c.getString(R.string.ks_rep_keypass_warn)).append('\n');
        sb.append(c.getString(R.string.ks_rep_subject, nz(a.subject))).append('\n');
        sb.append(c.getString(R.string.ks_rep_issuer, nz(a.issuer))).append('\n');
        sb.append(c.getString(R.string.ks_rep_valid, nz(a.notBefore), nz(a.notAfter))).append('\n');
        sb.append(c.getString(R.string.ks_rep_sigalg, nz(a.sigAlg))).append('\n');
        if (a.sha1 != null) sb.append(c.getString(R.string.ks_rep_sha1, a.sha1)).append('\n');
        if (a.sha256 != null) sb.append(c.getString(R.string.ks_rep_sha256, a.sha256)).append('\n');
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
