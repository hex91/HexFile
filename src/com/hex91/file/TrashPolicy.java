package com.hex91.file;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.List;

/**
 * v1.82.0 回收站保留策略：按天数 + 容量自动清理。
 *
 * <p>规则写在 {@code HexFile/Trash/policy.conf}（纯文本 key=value），
 * 不依赖 SharedPreferences，桌面可直接单测；Trash 目录被整个删掉后策略也随之重置，
 * 不会出现「设置还在、回收站没了」的错位。</p>
 *
 * <p>清理顺序：先删超过保留天数的，再按「最旧优先」删到容量达标。
 * 任何一步失败都只跳过该项，不影响其余条目；策略关闭时直接返回。</p>
 */
public final class TrashPolicy {

    /** 默认保留 30 天 */
    public static final int DEFAULT_DAYS = 30;
    /** 默认最多占 512MB */
    public static final long DEFAULT_BYTES = 512L * 1024 * 1024;
    public static final String CONF_NAME = "policy.conf";

    /** 默认关闭：自动清理会真删文件，必须由用户主动开启 */
    public boolean enabled = false;
    public int maxDays = DEFAULT_DAYS;
    public long maxBytes = DEFAULT_BYTES;

    /** 一次清理的结果 */
    public static class Report {
        public int removed;
        public long freed;
        public boolean skipped;

        public String summary() {
            if (skipped) return "自动清理已关闭";
            if (removed == 0) return "无需清理";
            return "已清理 " + removed + " 项，释放 " + FileOps.formatSize(freed);
        }
    }

    public static TrashPolicy load(File trashDir) {
        TrashPolicy p = new TrashPolicy();
        File f = new File(trashDir, CONF_NAME);
        if (!f.isFile()) return p;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("enabled".equals(k)) {
                    // 只认 true/false，写坏了就保持默认（否则 Boolean.parseBoolean("maybe") = false，
                    // 会把「配置损坏」误当成「用户关掉了自动清理」）
                    if ("true".equalsIgnoreCase(v)) p.enabled = true;
                    else if ("false".equalsIgnoreCase(v)) p.enabled = false;
                }
                else if ("maxDays".equals(k)) p.maxDays = safeInt(v, DEFAULT_DAYS);
                else if ("maxBytes".equals(k)) p.maxBytes = safeLong(v, DEFAULT_BYTES);
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
        return p;
    }

    public boolean save(File trashDir) {
        OutputStreamWriter w = null;
        try {
            trashDir.mkdirs();
            w = new OutputStreamWriter(new FileOutputStream(new File(trashDir, CONF_NAME)), "UTF-8");
            w.write("enabled=" + enabled + "\n");
            w.write("maxDays=" + maxDays + "\n");
            w.write("maxBytes=" + maxBytes + "\n");
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 执行一次清理。
     *
     * @param now 当前时间（ms），测试可注入固定值
     */
    public Report apply(File trashDir, long now) {
        Report rep = new Report();
        if (!enabled) {
            rep.skipped = true;
            return rep;
        }
        List<Trash.Entry> list = Trash.listIn(trashDir);
        if (list.isEmpty()) return rep;

        // ---- 1) 过期条目 ----
        if (maxDays > 0) {
            long ttl = maxDays * 24L * 3600 * 1000;
            for (Trash.Entry e : list) {
                if (now - e.time > ttl) {
                    long sz = Trash.sizeOf(e.store);
                    if (Trash.purgeIn(trashDir, e)) {
                        rep.removed++;
                        rep.freed += sz;
                    }
                }
            }
        }

        // ---- 2) 容量上限（最旧优先）----
        // 不依赖 id 前缀的时间戳：按 meta 里的真实删除时间排序，「最旧」才可信
        if (maxBytes > 0) {
            List<Trash.Entry> left = Trash.listIn(trashDir);
            java.util.Collections.sort(left, new java.util.Comparator<Trash.Entry>() {
                public int compare(Trash.Entry a, Trash.Entry b) {
                    return Long.compare(a.time, b.time);
                }
            });
            long total = 0;
            for (Trash.Entry e : left) total += Trash.sizeOf(e.store);
            for (int i = 0; i < left.size() && total > maxBytes; i++) {
                Trash.Entry e = left.get(i);
                long sz = Trash.sizeOf(e.store);
                if (Trash.purgeIn(trashDir, e)) {
                    total -= sz;
                    rep.removed++;
                    rep.freed += sz;
                }
            }
        }
        return rep;
    }

    private static int safeInt(String v, int def) {
        try { return Integer.parseInt(v); } catch (Exception e) { return def; }
    }

    private static long safeLong(String v, long def) {
        try { return Long.parseLong(v); } catch (Exception e) { return def; }
    }
}
