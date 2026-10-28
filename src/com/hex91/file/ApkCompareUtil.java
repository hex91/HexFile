package com.hex91.file;

import android.content.pm.PackageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * APK 对比（v1.17.0）：
 *  - 清单层：ApkUtil.rows() 两侧取值，按标签对齐，标出 不同/仅A/仅B
 *  - 条目层：zip 条目清单对比（CompareUtil.compareEntries），新增/修改/删除
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
    public static Result compare(File a, File b, PackageManager pm) {
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
        Map<String, String> ma = toMap(ApkUtil.rows(a, pm));
        Map<String, String> mb = toMap(ApkUtil.rows(b, pm));
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

    /** 可复制的文本报告 */
    public static String report(Result r, String nameA, String nameB) {
        StringBuilder sb = new StringBuilder();
        sb.append("APK 对比报告\n");
        sb.append("A: ").append(nameA).append(" (").append(CompareUtil.fmtSize(r.sizeA)).append(")\n");
        sb.append("B: ").append(nameB).append(" (").append(CompareUtil.fmtSize(r.sizeB)).append(")\n");
        sb.append("SHA-256: ").append(r.shaA == null ? "?" : r.shaA).append('\n');
        sb.append("SHA-256: ").append(r.shaB == null ? "?" : r.shaB).append('\n');
        sb.append("\n[清单差异] (").append(r.nRowDiff).append(" 项不同)\n");
        for (RowDiff rd : r.rows) {
            if (rd.same) continue;
            sb.append("· ").append(rd.label).append('\n');
            sb.append("   A: ").append(rd.valA == null ? "(无)" : rd.valA).append('\n');
            sb.append("   B: ").append(rd.valB == null ? "(无)" : rd.valB).append('\n');
        }
        CompareUtil.Result e = r.entries;
        if (e != null) {
            sb.append("\n[条目差异] 新增 ").append(e.nAdded)
              .append(" · 修改 ").append(e.nModified)
              .append(" · 删除 ").append(e.nDeleted)
              .append(" · 相同 ").append(e.nSame).append('\n');
            for (CompareUtil.Item it : e.items) {
                String tag = it.status == CompareUtil.ADDED ? "+ "
                        : it.status == CompareUtil.DELETED ? "- " : "~ ";
                sb.append(tag).append(it.relPath);
                if (it.note != null && !it.note.isEmpty()) sb.append("  (").append(it.note).append(")");
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
