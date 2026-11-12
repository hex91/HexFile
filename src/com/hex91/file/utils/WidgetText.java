package com.hex91.file.utils;

/**
 * v1.85.0 小组件的文案与进度计算（纯 Java，可桌面单测）。
 *
 * <p>小组件只有 4 格宽，顶行还要放名字和刷新按钮，所以存储信息必须极度精简：
 * {@code "剩 12.3G / 128G"} 而不是 {@code "剩余 12.3 GB，共 128 GB"}。</p>
 */
public final class WidgetText {

    private WidgetText() {}

    /** 紧凑容量：980M / 12.3G / 128G（手机存储到不了 T 级，不实现 T 分支） */
    public static String size(long bytes) {
        if (bytes <= 0) return "0";
        final long G = 1024L * 1024 * 1024;
        final long M = 1024L * 1024;
        if (bytes >= G) {
            double v = (double) bytes / G;
            return (v >= 100 ? String.valueOf(Math.round(v)) : one(v)) + "G";
        }
        if (bytes >= M) return Math.round((double) bytes / M) + "M";
        if (bytes >= 1024) return Math.round((double) bytes / 1024) + "K";
        return bytes + "B";
    }

    /** 12.34 → "12.3"；12.0 → "12"（去掉多余的小数点，省宽度） */
    private static String one(double v) {
        long r = Math.round(v * 10);
        if (r % 10 == 0) return String.valueOf(r / 10);
        return String.valueOf(r / 10.0);
    }

    /** 顶行摘要：{@code 剩 12.3G / 128G} */
    public static String summary(long total, long avail) {
        if (total <= 0) return "存储不可用";
        return "剩 " + size(Math.max(0, avail)) + " / " + size(total);
    }

    /** 已用百分比 0~100（总容量未知时返回 0，避免进度条瞎跳） */
    public static int usedPercent(long total, long avail) {
        if (total <= 0) return 0;
        long used = total - Math.max(0, Math.min(avail, total));
        int p = (int) Math.round(used * 100.0 / total);
        return Math.max(0, Math.min(100, p));
    }
}
