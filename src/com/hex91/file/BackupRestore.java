package com.hex91.file;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    private static final String TAG = "HexFile";

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
            Log.w(TAG, "restore blocked: root unavailable");
            return o;
        }
        o.rootAvailable = true;

        // 1) 重装 APK（install-create / write / commit 会话流程）
        Log.i(TAG, "restore: start install pkg=" + e.pkg + " apks=" + e.apks.length);
        String installMsg = installApkSet(e.apks, installerPkg);
        o.installOk = installMsg == null;
        if (!o.installOk) {
            o.message = installMsg;
            Log.e(TAG, "restore: install failed: " + installMsg);
            return o;
        }
        Log.i(TAG, "restore: install ok, pkg=" + e.pkg);

        // 2) 恢复数据（root 下 tar 解包 + 修正属主 + SELinux 上下文）
        File dataTar = pickValidDataTar(e);
        if (dataTar != null) {
            String dd = "/data/user/0/" + e.pkg;
            Log.i(TAG, "restore: extract data from " + dataTar.getPath());
            boolean dataOk = extractData(dataTar, dd);
            o.dataOk = dataOk;
            o.message = dataOk ? "已重装并恢复数据" : "已重装，但数据恢复失败（备份数据损坏，无法解包）";
        } else {
            o.message = "已重装（备份中无有效数据，仅恢复应用本体）";
        }
        return o;
    }

    /**
     * 解包数据归档到真实数据目录并修正属主/SELinux 上下文。
     * 应用内 su（KernelSU）处于隔离挂载命名空间：/data/data 是刚建的空壳目录，
     * 必须经 nsenter 进入 init(pid 1) 命名空间操作真实路径；无 nsenter 的环境
     * （如 Magisk）回退为直接解包。
     */
    private static boolean extractData(File tar, String dd) {
        String p = tar.getPath();
        String xflag = p.endsWith(".gz") ? "-xzf" : "-xf";
        RootUtil.RunResult r = RootUtil.runCapture(
                "nsenter -t 1 -m -- tar " + xflag + " '" + p + "' -C / 2>&1", 600);
        boolean ok = r != null && r.exit == 0;
        if (!ok) {
            Log.w(TAG, "restore: nsenter extract failed, fallback direct: "
                    + (r == null ? "timeout" : r.out));
            r = RootUtil.runCapture("tar " + xflag + " '" + p + "' -C / 2>&1", 600);
            ok = r != null && r.exit == 0;
        }
        if (!ok) {
            Log.e(TAG, "restore: data extract failed: " + (r == null ? "timeout" : r.out));
            return false;
        }
        // 重装后系统重新分配 UID：按当前目录属主修正 tar 内旧 UID 文件。
        // chown/restorecon 都需要真实路径，因此同样经 nsenter 执行。
        RootUtil.runCapture("nsenter -t 1 -m -- sh -c 'chown -R $(stat -c %u " + dd
                + "):$(stat -c %g " + dd + ") " + dd + " && restorecon -R " + dd + "' 2>&1", 120);
        return true;
    }

    /**
     * 选择有效的数据归档：优先 data.tar.gz（gzip），其次 data.tar（plain）。
     * 若文件存在但实质为空/损坏（tar 无法识别），返回 null，避免还原时误用坏档。
     */
    private static File pickValidDataTar(BackupEntry e) {
        File[] candidates = { new File(e.dir, "data.tar.gz"), new File(e.dir, "data.tar") };
        for (File f : candidates) {
            if (!f.exists() || !f.isFile()) continue;
            // 至少大于一个 tar 块（512B），且能被 tar 列出 → 视为有效
            if (f.length() < 512) { Log.w(TAG, "data archive too small, skip: " + f.getName()); continue; }
            RootUtil.RunResult t = RootUtil.runCapture("tar -tf '" + f.getPath() + "' >/dev/null 2>&1");
            if (t != null && t.exit == 0) return f;
            Log.w(TAG, "data archive invalid, skip: " + f.getName());
        }
        return null;
    }

    /** 安装一组 APK；成功返回 null，失败返回友好原因。 */
    private static String installApkSet(File[] apks, String installerPkg) {
        // Android 11+：system_server 无法读取 /sdcard（fuse 标签）上的文件，
        // 必须先把 APK 拷到 /data/local/tmp，再从 tmp 安装。
        List<String> tmpFiles = new ArrayList<>();
        for (File apk : apks) {
            String tmp = "/data/local/tmp/" + apk.getName();
            RootUtil.RunResult cp = RootUtil.runCapture(
                    "cp '" + apk.getPath() + "' '" + tmp + "' 2>&1");
            if (cp == null || cp.exit != 0) {
                cleanupTmp(tmpFiles);
                return "复制安装包失败（sdcard 权限）：" + apk.getName() + " —— " + (cp == null ? "timeout" : cp.out);
            }
            RootUtil.run("chmod 644 '" + tmp + "' 2>/dev/null");
            tmpFiles.add(tmp);
        }

        // 单一 APK 用 pm install；多个（split 分包）用 pm install-multiple。
        StringBuilder sb = new StringBuilder();
        if (apks.length == 1) sb.append("pm install -r -i ").append(installerPkg);
        else sb.append("pm install-multiple -r -i ").append(installerPkg);
        for (String t : tmpFiles) sb.append(" '").append(t).append("'");
        sb.append(" 2>&1");
        Log.i(TAG, "restore: run " + (apks.length == 1 ? "pm install" : "pm install-multiple")
                + " apks=" + apks.length);
        RootUtil.RunResult r = RootUtil.runCapture(sb.toString(), 180);
        cleanupTmp(tmpFiles);
        if (r == null) return "安装超时（请确认已授予 Root 且设备未卡顿）";
        if (r.exit != 0) return friendlyInstallError(r.out);
        return null;
    }

    /** 清理 /data/local/tmp 下的临时安装包，避免残留。 */
    private static void cleanupTmp(List<String> tmpFiles) {
        for (String t : tmpFiles) RootUtil.run("rm -f '" + t + "' 2>/dev/null");
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
}
