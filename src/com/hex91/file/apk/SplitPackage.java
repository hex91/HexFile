package com.hex91.file.apk;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * v2.13.0（F-03）：分包安装包（{@code .apks} / {@code .xapk} / {@code .apkm}）的结构解析。
 *
 * <p><b>三者其实是同一种东西</b>：一个 zip，里面装着 base + 若干 split 的 apk，外加各自
 * 的一点元数据（bundletool 的 {@code toc.pb}、APKPure 的 {@code manifest.json}、
 * APKMirror 的 {@code info.json}）。所以这里不按格式分支，只按「条目是不是 .apk」分类 ——
 * 格式差异全在元数据上，而安装根本不需要读元数据。</p>
 *
 * <p><b>为什么不解析 JSON</b>：三条路各有一份 schema，而包名/版本/应用名全都能从 base apk
 * 自己的 {@code AndroidManifest} 里拿到（{@code PackageManager.getPackageArchiveInfo}）。
 * 少一套解析就少一处「某家打包器字段名不一样」的坑。</p>
 *
 * <p>纯 {@code java.util.zip}，不碰 Android API，因此可以在桌面单测里跑真实 zip。
 * 解包（{@link com.hex91.file.utils.SplitInstaller}）与安装（PackageInstaller 会话）
 * 分别由上层负责。</p>
 */
public final class SplitPackage {

    private SplitPackage() {}

    /** 扩展名是不是分包安装包（入参须小写） */
    public static boolean isSplitName(String lowerName) {
        if (lowerName == null) return false;
        return lowerName.endsWith(".apks")
                || lowerName.endsWith(".xapk")
                || lowerName.endsWith(".apkm");
    }

    /** 给界面看的格式名：APKS / XAPK / APKM；不是分包包返回 null */
    public static String formatName(String lowerName) {
        if (lowerName == null) return null;
        if (lowerName.endsWith(".apks")) return "APKS";
        if (lowerName.endsWith(".xapk")) return "XAPK";
        if (lowerName.endsWith(".apkm")) return "APKM";
        return null;
    }

    /** zip 内一个条目（apk 或 obb） */
    public static final class Item {
        /** zip 内路径，'/' 分隔，保留子目录（{@code splits/base-master.apk}） */
        public final String path;
        /** 未压缩字节数；-1 表示 zip 头里没写 */
        public final long size;

        Item(String path, long size) {
            this.path = path;
            this.size = size;
        }
    }

    /** 扫描结果 */
    public static final class Contents {
        public final List<Item> apks = new ArrayList<Item>();
        /** xapk 常见：{@code Android/obb/<pkg>/*.obb} */
        public final List<Item> obb = new ArrayList<Item>();
        /** 其余条目数（json / toc.pb / 图标 / 说明） */
        public int other;
        /** 压缩包自身大小 */
        public long fileBytes;
        /** 全部 apk 未压缩字节和（用于进度分母） */
        public long apkBytes;

        public boolean hasApks() { return !apks.isEmpty(); }

        /**
         * 分包数 = apk 总数 − 1（base）。
         *
         * <p>之所以只是「−1」而不去猜哪个是 base：bundletool 的叫 {@code base-master.apk}，
         * APKPure 的叫 {@code com.foo.apk}，APKMirror 的叫 {@code base.apk}，
         * 还有把 base 也放进 {@code splits/} 的。这个数字只用来在对话框里给个规模感，
         * 少算一个比「自信地说错」要好。</p>
         */
        public int splitCount() { return apks.isEmpty() ? 0 : apks.size() - 1; }
    }

    /**
     * 扫描分包包。不是 zip / 读不了 → 抛 IOException（由调用方转成「包已损坏」）。
     */
    public static Contents scan(File pkg) throws IOException {
        Contents c = new Contents();
        c.fileBytes = pkg.length();
        ZipFile z = new ZipFile(pkg);
        try {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String path = e.getName();
                String lower = path.toLowerCase(java.util.Locale.US);
                if (lower.endsWith(".apk")) {
                    Item it = new Item(path, e.getSize());
                    c.apks.add(it);
                    if (it.size > 0) c.apkBytes += it.size;
                } else if (lower.endsWith(".obb")) {
                    c.obb.add(new Item(path, e.getSize()));
                } else {
                    c.other++;
                }
            }
        } finally {
            z.close();
        }
        return c;
    }

    /**
     * 从 zip 路径取一个可安全落盘的文件名。
     *
     * <p>这里刻意<b>只取最后一段</b>而不是保留子目录：解包目录是临时目录，装完就删，
     * 层次没有意义；而只取末段就天然免疫 {@code ../} 与绝对路径（zip-slip），
     * 不需要再写一套路径校验。剩下的清洗针对的是「末段本身不合法」：
     * 控制字符、Windows 保留字符、纯点、结尾点空格。</p>
     */
    public static String safeLeaf(String zipPath) {
        if (zipPath == null) return "split.apk";
        String s = zipPath.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == 0x7f) continue;                  // 控制字符
            if ("/\\:*?\"<>|".indexOf(ch) >= 0) continue;            // 路径分隔与 Windows 保留字符
            sb.append(ch);
        }
        String out = sb.toString().trim();
        // 纯点（"." ".."）与空名都不合法；结尾点/空格在 Windows 上会被吃掉
        while (out.endsWith(".") || out.endsWith(" ")) out = out.substring(0, out.length() - 1);
        if (out.isEmpty() || out.replace(".", "").isEmpty()) return "split.apk";
        if (out.length() > 100) {
            // 截断但保住扩展名：安装时按扩展名判断的代码（以及人）都还认得出它是 apk
            int dot = out.lastIndexOf('.');
            String ext = dot > 0 && out.length() - dot <= 6 ? out.substring(dot) : "";
            out = out.substring(0, 100 - ext.length()) + ext;
        }
        return out;
    }
}
