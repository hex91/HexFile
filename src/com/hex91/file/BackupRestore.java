package com.hex91.file;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.54.0 备份管理核心：扫描 HexFile/Backups、列出所有备份（不论是否已安装），
 * 并提供带细分失败原因的「还原」能力（重装 APK + 恢复数据目录）。
 *
 * 设计要点：
 * - 备份目录归属 HexFile 自身（u0_aXXX, 770），与应用是否安装无关 —— 列表/读取无需 root。
 * - 还原的「重装」与「数据解包」均依赖 root（pm 安装会话 + tar 写入 /data），无 root 直接报错。
 * - 还原未安装应用时，数据目录默认按 /data/data/<pkg> 处理，不再依赖已卸载的 AppRow。
 * - 通过 RootUtil.runCapture 读取 pm 输出的失败原因，区分「签名不一致 / 分包缺失 / 其它」。
 */
public final class BackupRestore {

    private static final Pattern SESSION_ID = Pattern.compile("\\[(\\d+)\\]");

    private BackupRestore() {}

    /** 备份根目录：/sdcard/HexFile/Backups */
    public static File backupsRoot() {
        return new File(Environment.getExternalStorageDirectory(), "HexFile/Backups");
    }

    /** 单个备份条目 */
    public static class BackupEntry {
        public String pkg;          // 包名（即备份目录名）
        public File dir;            // 备份目录
        public File[] apks;         // 备份的 APK 文件（含 base + split）
        public File dataTar;        // 数据备份 data.tar.gz / data.tar（无则为 null）
        public boolean installed;   // 当前设备是否已安装该应用
        public String label;        // 已安装取应用名；否则取包名
    }

    /** 还原结果（含细分失败原因，供 UI 直接提示） */
    public static class RestoreOutcome {
        public boolean rootAvailable;
        public boolean installOk;
        public boolean dataOk;
        public String message;
    }

    /** 列出全部备份（按包名排序）。无备份或读取失败返回空列表。 */
    public static List<BackupEntry> listBackups(Context ctx) {
        List<BackupEntry> list = new ArrayList<>();
        File root = backupsRoot();
        File[] subs = root.listFiles(new FileFilter() {
            public boolean accept(File f) {
                return f.isDirectory();
            }
        });
        if (subs == null) return list;
        PackageManager pm = ctx == null ? null : ctx.getPackageManager();
        for (File d : subs) {
            BackupEntry e = new BackupEntry();
            e.pkg = d.getName();
            e.dir = d;
            e.apks = d.listFiles(new FileFilter() {
                public boolean accept(File f) {
                    return f.isFile() && f.getName().endsWith(".apk");
                }
            });
            if (e.apks == null) e.apks = new File[0];
            File gz = new File(d, "data.tar.gz");
            File plain = new File(d, "data.tar");
            e.dataTar = gz.exists() ? gz : (plain.exists() ? plain : null);
            if (pm != null) {
                try {
                    e.installed = pm.getPackageInfo(e.pkg, 0) != null;
                    e.label = (String) pm.getApplicationInfo(e.pkg, 0).loadLabel(pm);
                } catch (Exception ignored) {
                    e.installed = false;
                    e.label = e.pkg;
                }
            } else {
                e.installed = false;
                e.label = e.pkg;
            }
            if (e.label == null || e.label.isEmpty()) e.label = e.pkg;
            list.add(e);
        }
        list.sort(new java.util.Comparator<BackupEntry>() {
            public int compare(BackupEntry a, BackupEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return list;
    }

    /**
     * 还原：重装 APK（含 split）+ 视情况恢复数据目录。
     * 必须在后台线程调用（含阻塞 IO + root 命令）。
     */
    public static RestoreOutcome restore(BackupEntry e, String installerPkg) {
        RestoreOutcome o = new RestoreOutcome();
        if (e == null || e.apks == null || e.apks.length == 0) {
            o.message = "备份中无 APK 文件，无法还原";
            return o;
        }
        if (!RootUtil.detect() || !RootUtil.isRootAvailable()) {
            o.rootAvailable = false;
            o.message = "未获取 Root 权限：还原需 root 才能重装应用并写入 /data（请先在 Magisk 等授权 HexFile）";
            return o;
        }
        o.rootAvailable = true;

        // 1) 重装 APK（install-create / write / commit 会话流程）
        String installMsg = installApkSet(e.apks, installerPkg);
        o.installOk = installMsg == null;
        if (!o.installOk) {
            o.message = installMsg;
            return o;
        }

        // 2) 恢复数据（root 下 tar 解包 + 修正属主 + SELinux 上下文）
        if (e.dataTar != null) {
            String dd = "/data/data/" + e.pkg;
            String cmd = "tar -xzf '" + e.dataTar.getPath() + "' -C / 2>/dev/null"
                    + " || tar -xf '" + e.dataTar.getPath() + "' -C /";
            RootUtil.RunResult r = RootUtil.runCapture(cmd);
            boolean dataOk = r != null && r.exit == 0;
            if (dataOk) {
                // 重装后系统重新分配 UID，按当前目录属主修正 tar 内旧 UID 文件
                RootUtil.run("chown -R $(stat -c %u '" + dd + "' 2>/dev/null):"
                        + "$(stat -c %u '" + dd + "' 2>/dev/null) '" + dd + "' 2>/dev/null");
                RootUtil.run("restorecon -R '" + dd + "' 2>/dev/null");
            }
            o.dataOk = dataOk;
            o.message = dataOk ? "已重装并恢复数据" : "已重装，但数据恢复失败（tar 解包错误，可能备份损坏）";
        } else {
            o.message = "已重装（备份中无数据，仅恢复应用本体）";
        }
        return o;
    }

    /** 安装一组 APK；成功返回 null，失败返回友好原因。 */
    private static String installApkSet(File[] apks, String installerPkg) {
        RootUtil.RunResult create =
                RootUtil.runCapture("pm install-create -r -i " + installerPkg + " 2>&1");
        if (create == null) return "Root 执行失败（超时或被 Magisk 拒绝）";
        if (create.exit != 0) return "创建安装会话失败：" + create.out;
        Integer sid = parseSessionId(create.out);
        if (sid == null) return "无法解析安装会话 ID：" + create.out;

        for (File apk : apks) {
            long len = apk.length();
            RootUtil.RunResult wr = RootUtil.runCapture(
                    "pm install-write -S " + len + " " + sid + " "
                            + apk.getName() + " " + apk.getPath() + " 2>&1");
            if (wr == null) return "写入安装包超时：" + apk.getName();
            if (wr.exit != 0) return "写入安装包失败：" + apk.getName() + " —— " + wr.out;
        }

        RootUtil.RunResult cm = RootUtil.runCapture("pm install-commit " + sid + " 2>&1");
        if (cm == null) return "提交安装超时";
        if (cm.exit != 0) return friendlyInstallError(cm.out);
        return null;
    }

    /** 将 pm 安装失败输出翻译为可定位的原因 */
    private static String friendlyInstallError(String out) {
        if (out == null) out = "";
        String lower = out.toLowerCase();
        if (lower.contains("update_incompatible") || lower.contains("signatures do not match")
                || lower.contains("signature mismatch")) {
            return "签名不一致：当前已装版本与备份 APK 签名不同，请先卸载该应用再还原";
        }
        if (lower.contains("missing_split")) {
            return "分包缺失：备份不完整（漏了 split 分包），无法还原，请重新备份";
        }
        if (lower.contains("install_failed")) {
            return "安装失败：" + out;
        }
        return "重装失败：" + (out.isEmpty() ? "未知原因（pm 无输出）" : out);
    }

    private static Integer parseSessionId(String s) {
        if (s == null) return null;
        Matcher m = SESSION_ID.matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }
}
