package com.hex91.file;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APK 信息解析（v1.57.0）：
 *   基于 AxmlFile 二进制解析 AndroidManifest，收集包名/版本/SDK/权限/四大组件，
 *   叠加 ZIP 层信息（ABI/dex/条目数）与签名方案（ApkSignCheck）。纯只读，不改包。
 */
public final class ApkInfoUtil {

    public static final class Info {
        public String pkg = "?";
        public String versionName = "?";
        public String versionCode = "?";
        public String minSdk = "?", targetSdk = "?";
        public String compileSdk = "";
        public final List<String> permissions = new ArrayList<String>();
        public final List<String[]> activities = new ArrayList<String[]>();  // [name, exported]
        public final List<String[]> services = new ArrayList<String[]>();
        public final List<String[]> receivers = new ArrayList<String[]>();
        public final List<String[]> providers = new ArrayList<String[]>();
        public final List<String> abis = new ArrayList<String>();
        public int dexCount;
        public long mainDexMethods = -1;   // -1 = 未统计
        public String signSchemes = "?";
        public long fileSize;
        public int entryCount;
        public long lastMod;
    }

    private ApkInfoUtil() {}

    public static Info parse(File apk) throws Exception {
        Info info = new Info();
        info.fileSize = apk.length();
        info.lastMod = apk.lastModified();

        byte[] mf = ApkCloneTool.readZipEntry(apk, "AndroidManifest.xml");
        AxmlFile ax = AxmlFile.parse(mf);
        for (AxmlFile.XElem el : ax.elements) {
            String n = el.name;
            if ("manifest".equals(n)) {
                info.pkg = sval(ax, el, "package");
                String vn = sval(ax, el, "versionName");
                if (vn != null) info.versionName = vn;
                String vc = sval(ax, el, "versionCode");
                if (vc != null) info.versionCode = vc;
            } else if ("uses-sdk".equals(n)) {
                String v = sval(ax, el, "minSdkVersion");
                if (v != null) info.minSdk = v;
                v = sval(ax, el, "targetSdkVersion");
                if (v != null) info.targetSdk = v;
            } else if ("uses-permission".equals(n)) {
                String v = sval(ax, el, "name");
                if (v != null && !info.permissions.contains(v)) info.permissions.add(v);
            } else if ("permission".equals(n) || "uses-permission-sdk-23".equals(n)) {
                if ("uses-permission-sdk-23".equals(n)) {
                    String v = sval(ax, el, "name");
                    if (v != null && !info.permissions.contains(v)) info.permissions.add(v);
                }
            } else if ("activity".equals(n) || "activity-alias".equals(n)) {
                info.activities.add(comp(ax, el, n));
            } else if ("service".equals(n)) {
                info.services.add(comp(ax, el, n));
            } else if ("receiver".equals(n)) {
                info.receivers.add(comp(ax, el, n));
            } else if ("provider".equals(n)) {
                info.providers.add(comp(ax, el, n));
            } else if ("uses-split".equals(n) || "sdk-library".equals(n)) {
                // 忽略
            }
        }
        // compileSdk（platformBuildVersionCode 属性，存在才显示）
        for (AxmlFile.XAttr a : ax.elements.get(0).attrs) {
            if ("platformBuildVersionCode".equals(a.name)) {
                info.compileSdk = ax.valueDisplay(a);
                break;
            }
        }

        ZipFile zf = new ZipFile(apk);
        try {
            info.entryCount = zf.size();
            Set<String> abiSet = new LinkedHashSet<String>();
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) {
                ZipEntry ze = en.nextElement();
                if (ze.isDirectory()) continue;
                String name = ze.getName();
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    int s2 = name.indexOf('/', 4);
                    if (s2 > 4) abiSet.add(name.substring(4, s2));
                } else if (name.endsWith(".dex") && name.indexOf('/') < 0
                        && name.startsWith("classes")) {
                    info.dexCount++;
                }
            }
            info.abis.addAll(abiSet);
            if (info.dexCount > 0) {
                try {
                    info.mainDexMethods = dexMethodCount(
                            ApkCloneTool.readZipEntry(apk, "classes.dex"));
                } catch (Throwable ignore) { /* 方法数统计失败不阻断 */ }
            }
        } finally {
            zf.close();
        }
        try {
            info.signSchemes = ApkSignCheck.schemes(apk);
        } catch (Throwable t) {
            info.signSchemes = "读取失败";
        }
        return info;
    }

    /** dex 头 96 字节里的 method_ids size（偏移 88，uint32） */
    private static long dexMethodCount(byte[] dex) {
        if (dex == null || dex.length < 96) return -1;
        if (dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x' || dex[3] != '\n') return -1;
        return (dex[88] & 0xFFL) | (dex[89] & 0xFFL) << 8
                | (dex[90] & 0xFFL) << 16 | (dex[91] & 0xFFL) << 24;
    }

    /** 组件行：[类名, exported 标记] */
    private static String[] comp(AxmlFile ax, AxmlFile.XElem el, String kind) {
        String name = sval(ax, el, "name");
        String exp = sval(ax, el, "exported");
        String flag;
        if ("true".equals(exp)) flag = "导出";
        else if ("false".equals(exp)) flag = "内部";
        else flag = hasIntentFilter(ax, el) ? "默认导出" : "默认内部";
        return new String[]{ (name != null ? name : "?"), kind + " · " + flag };
    }

    /** 该元素的直接子元素里有没有 intent-filter（AXML 同一标签同名元素通过 path 判定父子） */
    private static boolean hasIntentFilter(AxmlFile ax, AxmlFile.XElem el) {
        String prefix = el.path + "/";
        for (AxmlFile.XElem e : ax.elements) {
            if (e.depth == el.depth + 1 && e.path.startsWith(prefix)
                    && "intent-filter".equals(e.name)) return true;
        }
        return false;
    }

    private static String sval(AxmlFile ax, AxmlFile.XElem el, String name) {
        for (AxmlFile.XAttr a : el.attrs) {
            if (name.equals(a.name)) {
                if (a.isString() && a.rawValueIdx >= 0) return ax.strings.get(a.rawValueIdx);
                return ax.valueDisplay(a);
            }
        }
        return null;
    }

    static String fmtSize(long len) {
        if (len < 1024) return len + " B";
        double v = len;
        for (String u : new String[]{"KB", "MB", "GB"}) {
            v /= 1024;
            if (v < 1024) return String.format(java.util.Locale.US, "%.1f %s", v, u);
        }
        return String.format(java.util.Locale.US, "%.1f TB", v / 1024);
    }

    /** 导出为可读 txt */
    public static String toText(Info i, File apk) {
        StringBuilder b = new StringBuilder();
        String ln = "──────────────────────────────\n";
        b.append("APK 信息 · HexFile v1.57\n").append(ln);
        b.append("文件: ").append(apk.getName()).append('\n');
        b.append("大小: ").append(fmtSize(i.fileSize))
                .append("（").append(i.entryCount).append(" 个条目）\n");
        b.append("修改: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(i.lastMod))).append("\n\n");
        b.append("〔基本信息〕\n");
        b.append("包名: ").append(i.pkg).append('\n');
        b.append("版本: ").append(i.versionName).append(" (").append(i.versionCode).append(")\n");
        b.append("SDK: min ").append(i.minSdk).append(" / target ").append(i.targetSdk);
        if (!i.compileSdk.isEmpty()) b.append(" / 编译 ").append(i.compileSdk);
        b.append("\n签名: ").append(i.signSchemes).append("\n\n");
        b.append("〔Native 库〕\n");
        if (i.abis.isEmpty()) b.append("（无 lib 目录）\n");
        else for (String abi : i.abis) b.append("ABI: ").append(abi).append('\n');
        b.append('\n');
        b.append("〔DEX〕\n");
        b.append("dex 文件: ").append(i.dexCount).append(" 个\n");
        if (i.mainDexMethods >= 0) b.append("classes.dex 方法数: ").append(i.mainDexMethods).append('\n');
        b.append('\n');
        comp(b, "Activity", i.activities);
        comp(b, "Service", i.services);
        comp(b, "Receiver", i.receivers);
        comp(b, "Provider", i.providers);
        b.append("〔权限〕").append(i.permissions.size()).append(" 项\n");
        for (String p : i.permissions) b.append("  ").append(p).append('\n');
        return b.toString();
    }

    private static void comp(StringBuilder b, String title, List<String[]> list) {
        b.append("〔").append(title).append("〕").append(list.size()).append(" 个\n");
        for (String[] c : list) b.append("  ").append(c[0]).append("（").append(c[1]).append("）\n");
        b.append('\n');
    }
}
