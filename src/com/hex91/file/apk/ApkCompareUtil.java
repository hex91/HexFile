package com.hex91.file.apk;

import com.hex91.file.utils.HashUtil;
import com.hex91.file.fs.CompareUtil;
import android.content.pm.PackageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * APK 对比（v1.17.0）：
 *  - 清单层：ApkUtil.rows() 两侧取值，按标签对齐，标出 不同/仅A/仅B
 *  - 条目层：zip 条目清单对比（CompareUtil.compareEntries），新增/修改/删除。
 *    v2.12.0（F-12）起「修改」同时看 CRC32：同长度但内容不同也算修改，不再只比大小
 *  - 附加：文件大小、SHA-256 整体哈希
 */
public final class ApkCompareUtil {

    /** 清单行对比结果 */
    public static class RowDiff {
        public String label;
        public String valA;   // null = A 无此标签
        public String valB;
        public boolean same;
    }

    public static class Result {
        public List<RowDiff> rows = new ArrayList<RowDiff>();
        public int nRowDiff;
        public CompareUtil.Result entries;      // zip 条目差异（只含差异项）
        public String shaA, shaB;
        public long sizeA, sizeB;
        public String titleA, titleB;
        public String error = null;
    }

    private ApkCompareUtil() {}

    /** 对比两个 APK（耗时操作，放后台线程） */
    public static Result compare(File a, File b, PackageManager pm, android.content.Context ctx) {
        Result r = new Result();
        r.sizeA = a.length();
        r.sizeB = b.length();
        r.titleA = ApkUtil.appTitle(a, pm);
        r.titleB = ApkUtil.appTitle(b, pm);
        try {
            r.shaA = HashUtil.hash(a, "SHA-256");
            r.shaB = HashUtil.hash(b, "SHA-256");
        } catch (Throwable ignored) {}
        // 清单层
        Map<String, String> ma = toMap(ApkUtil.rows(a, pm, ctx));
        Map<String, String> mb = toMap(ApkUtil.rows(b, pm, ctx));
        // 以 A 的顺序为基准，B 多出的标签附在后面
        List<String> labels = new ArrayList<String>(ma.keySet());
        for (String k : mb.keySet()) if (!ma.containsKey(k)) labels.add(k);
        for (String label : labels) {
            RowDiff rd = new RowDiff();
            rd.label = label;
            rd.valA = ma.get(label);
            rd.valB = mb.get(label);
            rd.same = rd.valA == null ? rd.valB == null : rd.valA.equals(rd.valB);
            r.rows.add(rd);
            if (!rd.same) r.nRowDiff++;
        }
        // 条目层
        r.entries = CompareUtil.compareEntries(
                CompareUtil.readZipEntries(a), CompareUtil.readZipEntries(b));
        return r;
    }

    private static Map<String, String> toMap(List<String[]> rows) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (rows != null) {
            for (String[] kv : rows) {
                if (kv != null && kv.length >= 2 && kv[0] != null)
                    m.put(kv[0], kv[1] == null ? "" : kv[1]);
            }
        }
        return m;
    }
}
