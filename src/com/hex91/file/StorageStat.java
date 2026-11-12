package com.hex91.file;

import android.os.Environment;

/**
 * 存储容量统计（v2.0.0，O-15 刀5）：路径 → 真正可写分区 → StatFs。
 *
 * <p>从 MainActivity 搬出来，因为现在有两处要用：顶栏「储存」那一栏（updateHeader）
 * 与抽屉的「根目录 / 内部存储」两行。放在这里比让抽屉去求助于 Activity 更直接。</p>
 */
final class StorageStat {

    private StorageStat() {
    }

    /**
     * v1.72.0：把任意路径映射到「真正存放数据的可写分区」。
     *
     * <p>以前直接 StatFs("/")，拿到的是 system 分区（只读、约 1G、getAvailableBytes 恒为 0），
     * 于是抽屉里根目录显示「987M 已用 / 987M（100%）」，和 MT 等管理器显示的可用空间对不上。
     * 根目录下真正可写的是 /data，所以 / 与各系统只读挂载点统一按 /data 统计。</p>
     */
    static String statPathFor(String path) {
        String data = Environment.getDataDirectory().getAbsolutePath();
        if (path == null || path.isEmpty() || path.equals("/")) return data;
        String[] ro = {"/system", "/vendor", "/product", "/odm", "/apex", "/sbin", "/etc", "/init"};
        for (String p : ro) {
            if (path.equals(p) || path.startsWith(p + "/")) return data;
        }
        return path;
    }

    /** 返回 {总量, 可用}；路径自身拿不到就逐级回退（→ /data → 外部存储），全失败返回 null */
    static long[] usageOf(String path) {
        String data = Environment.getDataDirectory().getAbsolutePath();
        String ext = Environment.getExternalStorageDirectory().getAbsolutePath();
        String[] tries = {statPathFor(path), data, ext};
        for (String t : tries) {
            try {
                android.os.StatFs sf = new android.os.StatFs(t);
                long total = sf.getTotalBytes();
                if (total > 0) return new long[]{total, sf.getAvailableBytes()};
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static String statFs(String path) {
        long[] u = usageOf(path);
        if (u == null) return "容量不可用";
        long total = u[0], avail = u[1];
        long used = total > avail ? total - avail : 0;
        int pct = total > 0 ? (int) (used * 100 / total) : 0;
        return FileOps.formatSize(used) + " 已用 / " + FileOps.formatSize(total)
                + "（" + pct + "%）  可用 " + FileOps.formatSize(avail);
    }

    static String statFree(String path) {
        long[] u = usageOf(path);
        if (u == null) return "容量不可用";
        return "可用 " + FileOps.formatSize(u[1]) + " / 共 " + FileOps.formatSize(u[0]);
    }
}
