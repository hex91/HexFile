package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.apk.SmaliTool;

/**
 * Dex/smali 工具文案层（v2.45.0，F-14/32）。
 *
 * <p>{@link SmaliTool} 是纯 apk 层、无 {@code Context}，只返回稳定 ASCII 码
 * （{@code E_*}）与 {@code KEY=VALUE} 码行。错误说明、属性报告、修复报告统一在这里
 * 按资源拼成人类文本，逻辑层不产出任何文案。非本模块的异常原文（{@code Type: msg}）
 * 原样透传，不套前缀。</p>
 */
public final class SmaliReport {

    private SmaliReport() {}

    private static String s(Context c, int id) { return c.getString(id); }
    private static String s(Context c, int id, Object... a) { return c.getString(id, a); }

    /**
     * 错误消息翻译：null 原样返回（调用方以 null 表示成功）；E_* 码按资源翻译；
     * 其余（JDK 异常原文）原样透传。
     */
    public static String err(Context c, String code) {
        if (code == null) return null;
        if (SmaliTool.E_DECOMPILE_FAIL.equals(code)) return s(c, R.string.sm_e_decompile);
        if (SmaliTool.E_COMPILE_NO_OUTPUT.equals(code)) return s(c, R.string.sm_e_no_output);
        if (SmaliTool.E_COMPILE_FAIL.equals(code)) return s(c, R.string.sm_e_compile_fail);
        if (SmaliTool.E_TOO_SMALL.equals(code)) return s(c, R.string.sm_e_too_small);
        if (SmaliTool.E_TOO_BIG.equals(code)) return s(c, R.string.sm_e_too_big);
        if (SmaliTool.E_READ_INCOMPLETE.equals(code)) return s(c, R.string.sm_e_read_incomplete);
        if (SmaliTool.E_NOT_DEX.equals(code)) return s(c, R.string.sm_e_not_dex);
        return code;
    }

    /** dexProps 码行 → 人类可读的多行报告（标签走资源，数值与对齐留在代码） */
    public static String props(Context c, String raw) {
        if (raw == null) return null;
        java.util.Map<String, String> kv = new java.util.LinkedHashMap<String, String>();
        for (String line : raw.split("\n")) {
            int i = line.indexOf('=');
            if (i > 0) kv.put(line.substring(0, i), line.substring(i + 1));
        }
        String ver = kv.get("V");
        if (ver == null) return null;
        StringBuilder out = new StringBuilder();
        out.append(s(c, R.string.sm_prop_version, ver)).append(api(c, kv.get("API"))).append('\n');
        out.append(s(c, R.string.sm_prop_size, sizeText(c, num(kv.get("SZ"))))).append('\n');
        out.append(s(c, R.string.sm_prop_string, val(kv, "S"))).append('\n');
        out.append(s(c, R.string.sm_prop_type, val(kv, "T"))).append('\n');
        out.append(s(c, R.string.sm_prop_proto, val(kv, "P"))).append('\n');
        out.append(s(c, R.string.sm_prop_field, val(kv, "F"))).append('\n');
        out.append(s(c, R.string.sm_prop_method, val(kv, "M"))).append('\n');
        out.append(s(c, R.string.sm_prop_class, val(kv, "C"))).append('\n');
        out.append(s(c, R.string.sm_prop_checksum, status(c, kv.get("CK")))).append('\n');
        out.append(s(c, R.string.sm_prop_signature, status(c, kv.get("SG"))));
        return out.toString();
    }

    /** repairDex 报告码行 → 人类可读的多行文本 */
    public static String repairReport(Context c, String raw) {
        if (raw == null) return null;
        StringBuilder out = new StringBuilder();
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            if (i > 0) out.append('\n');
            out.append(renderLine(c, line));
        }
        return out.toString();
    }

    private static String renderLine(Context c, String line) {
        int eq = line.indexOf('=');
        String key = eq < 0 ? line : line.substring(0, eq);
        String arg = eq < 0 ? "" : line.substring(eq + 1);
        if ("NO_FIX".equals(key)) return s(c, R.string.sm_fix_none);
        if ("FIX_FILE_SIZE".equals(key)) {
            String[] p = arg.split(":");
            return s(c, R.string.sm_fix_file_size, p.length > 0 ? p[0] : arg,
                    p.length > 1 ? p[1] : arg);
        }
        if ("FIX_HEADER_SIZE".equals(key)) return s(c, R.string.sm_fix_header_size, arg);
        if ("FIX_ENDIAN".equals(key)) return s(c, R.string.sm_fix_endian, arg);
        if ("FIX_SHA1".equals(key)) return s(c, R.string.sm_fix_sha1);
        if ("FIX_ADLER".equals(key)) return s(c, R.string.sm_fix_adler);
        if ("MS_NOWRITE".equals(key)) return s(c, R.string.sm_fix_ms_nowrite, arg);
        if ("STREAM_FIX_MS".equals(key)) return s(c, R.string.sm_fix_ms, arg);
        return verify(c, line);
    }

    /** 校验码行：DEXLIB_* / STRUCT_* / OOB:* / MAP_* / HEAD_* → 文案 */
    private static String verify(Context c, String code) {
        if ("DEXLIB_OK".equals(code)) return s(c, R.string.sm_verify_dexlib_ok);
        if ("DEXLIB_READ_INCOMPLETE".equals(code)) return s(c, R.string.sm_verify_dexlib_incomplete);
        if ("DEXLIB_OOM_SKIP".equals(code)) return s(c, R.string.sm_verify_dexlib_oom);
        if (code.startsWith("DEXLIB_FAIL:")) {
            return s(c, R.string.sm_verify_dexlib_fail, code.substring("DEXLIB_FAIL:".length()));
        }
        if ("STRUCT_OK".equals(code)) return s(c, R.string.sm_verify_struct_ok);
        if (code.startsWith("STRUCT_FAIL:")) {
            return s(c, R.string.sm_verify_struct_fail, code.substring("STRUCT_FAIL:".length()));
        }
        if (code.startsWith("OOB:")) return s(c, R.string.sm_struct_oob, code.substring(4));
        if (code.startsWith("MAP_COUNT:")) return s(c, R.string.sm_struct_map_count, code.substring(10));
        if (code.startsWith("HEAD_READ_FAIL:")) return s(c, R.string.sm_struct_head_fail, code.substring(15));
        if ("HEAD_READ_INCOMPLETE".equals(code)) return s(c, R.string.sm_struct_head_incomplete);
        if (code.startsWith("MAP_READ_FAIL:")) return s(c, R.string.sm_struct_map_fail, code.substring(14));
        return code;
    }

    private static String api(Context c, String code) {
        if ("37".equals(code)) return s(c, R.string.sm_api_37);
        if ("38".equals(code)) return s(c, R.string.sm_api_38);
        if ("39".equals(code)) return s(c, R.string.sm_api_39);
        return "";
    }

    private static String status(Context c, String code) {
        return "OK".equals(code) ? s(c, R.string.sm_status_ok) : s(c, R.string.sm_status_bad);
    }

    private static String val(java.util.Map<String, String> kv, String key) {
        String v = kv.get(key);
        return v == null ? "" : v;
    }

    private static long num(String v) {
        try { return Long.parseLong(v); } catch (Exception e) { return 0; }
    }

    private static String sizeText(Context c, long n) {
        if (n >= 1L << 20) return s(c, R.string.sm_size_mb, n / 1048576.0, n);
        if (n >= 1024) return s(c, R.string.sm_size_kb, n / 1024.0, n);
        return s(c, R.string.sm_size_bytes, n);
    }
}
