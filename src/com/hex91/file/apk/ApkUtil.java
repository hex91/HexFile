package com.hex91.file.apk;

import com.hex91.file.R;
import com.hex91.file.fs.FileOps;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * APK 解析（对标 MT 的 APK信息弹窗）：
 * rows() 输出 {标签, 值} 行列表，含包名/版本/大小/签名/加固/系统要求/已安装信息；
 * 每行值可在界面点击复制。
 */
public class ApkUtil {

    /** 返回 {label, value} 行列表；解析失败返回 null（标签按 ctx 的语言取，行序与标签文案无关） */
    public static List<String[]> rows(File apk, PackageManager pm, Context ctx) {
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(apk.getPath(),
                    PackageManager.GET_SIGNATURES);
            if (pi == null) return null;
            List<String[]> rows = new ArrayList<String[]>();
            ApplicationInfo ai = pi.applicationInfo;
            if (ai != null && ai.sourceDir == null) ai.sourceDir = apk.getPath();

            rows.add(new String[]{ctx.getString(R.string.apk_row_pkg), pi.packageName});
            rows.add(new String[]{ctx.getString(R.string.apk_row_version), String.valueOf(pi.versionCode)});
            rows.add(new String[]{ctx.getString(R.string.apk_row_size), FileOps.formatSize(apk.length())});

            int cert = pi.signatures == null ? 0 : pi.signatures.length;
            rows.add(new String[]{ctx.getString(R.string.apk_row_sign),
                    cert == 0 ? ctx.getString(R.string.apk_row_unsigned)
                            : ctx.getString(R.string.apk_row_detecting)});
            rows.add(new String[]{ctx.getString(R.string.apk_row_cert_count), String.valueOf(cert)});
            rows.add(new String[]{ctx.getString(R.string.apk_row_shell), packStatus(apk, ctx)});

            if (ai != null) {
                rows.add(new String[]{ctx.getString(R.string.apk_row_target), apiText(ai.targetSdkVersion)});
                if (Build.VERSION.SDK_INT >= 24)
                    rows.add(new String[]{ctx.getString(R.string.apk_row_min), apiText(ai.minSdkVersion)});
                rows.add(new String[]{ctx.getString(R.string.apk_row_perm_count), pi.requestedPermissions == null
                        ? "0" : String.valueOf(pi.requestedPermissions.length)});
            }

            // 设备上已安装的同包名应用信息
            try {
                PackageInfo inst = pm.getPackageInfo(pi.packageName, 0);
                rows.add(new String[]{ctx.getString(R.string.apk_row_installed),
                        inst.versionName + " (" + inst.versionCode + ")"});
                if (inst.applicationInfo != null) {
                    rows.add(new String[]{ctx.getString(R.string.apk_row_data_dir), inst.applicationInfo.dataDir});
                    rows.add(new String[]{"UID", String.valueOf(inst.applicationInfo.uid)});
                }
                rows.add(new String[]{ctx.getString(R.string.apk_row_first_install),
                        FileOps.formatTime(inst.firstInstallTime)});
                rows.add(new String[]{ctx.getString(R.string.apk_row_last_update),
                        FileOps.formatTime(inst.lastUpdateTime)});
            } catch (Exception ignored) { }

            rows.add(new String[]{ctx.getString(R.string.apk_row_path), apk.getAbsolutePath()});
            return rows;
        } catch (Exception e) {
            return null;
        }
    }

    /** 弹窗标题：应用名 + 版本名 */
    public static String appTitle(File apk, PackageManager pm) {
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(apk.getPath(), 0);
            if (pi == null) return apk.getName();
            CharSequence label = pi.applicationInfo != null
                    ? pm.getApplicationLabel(pi.applicationInfo) : apk.getName();
            return label + "  " + pi.versionName;
        } catch (Exception e) {
            return apk.getName();
        }
    }

    /** APK 图标（从包资源加载），失败返回 null 由调用方兜底 */
    public static Drawable icon(File apk, PackageManager pm) {
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(apk.getPath(), 0);
            if (pi != null && pi.applicationInfo != null) {
                if (pi.applicationInfo.sourceDir == null)
                    pi.applicationInfo.sourceDir = apk.getPath();
                return pm.getApplicationIcon(pi.applicationInfo);
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 加固检测：扫描 zip 条目名匹配常见加固方案特征 */
    private static String packStatus(File apk, Context ctx) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(apk);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName().toLowerCase();
                if (n.contains("libjiagu")            // 360
                        || n.contains("libsecexe") || n.contains("libsecmain")  // 梆梆
                        || n.contains("libdexhelper")  // 腾讯乐固
                        || n.contains("libshell")      // 腾讯
                        || n.contains("ijiami")        // 爱加密
                        || n.contains("libmobisec")    // 阿里聚安全
                        || n.contains("libnesec")      // 网易易盾
                        || n.contains("libtup") || n.contains("libexecmain")) {
                    return ctx.getString(R.string.apk_row_shell_found, n);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) { }
        }
        return ctx.getString(R.string.apk_row_shell_none);
    }

    /** API 级别 → "Android X.X (API N)" */
    private static String apiText(int api) {
        String v;
        switch (api) {
            case 36: v = "16";  break;
            case 35: v = "15";  break;
            case 34: v = "14";  break;
            case 33: v = "13";  break;
            case 32: v = "12L"; break;
            case 31: v = "12";  break;
            case 30: v = "11";  break;
            case 29: v = "10";  break;
            case 28: v = "9";   break;
            case 27: v = "8.1"; break;
            case 26: v = "8.0"; break;
            case 25: v = "7.1"; break;
            case 24: v = "7.0"; break;
            case 23: v = "6.0"; break;
            case 22: v = "5.1"; break;
            case 21: v = "5.0"; break;
            case 19: v = "4.4"; break;
            default: return "Android (API " + api + ")";
        }
        return "Android " + v + " (API " + api + ")";
    }
}
