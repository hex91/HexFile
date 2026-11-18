package com.hex91.file.utils;

import com.hex91.file.fs.RootUtil;
import com.hex91.file.fs.WorkDir;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;

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

    /** 备份根目录：<工作目录>/Backups（默认 /sdcard/HexFile/Backups） */
    public static File backupsRoot() {
        return new File(WorkDir.base(), "Backups");
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

    /**
     * 还原结果的细分原因 —— v2.29.0（F-14/13）。
     *
     * <p>以前这里是 {@code public String message}，一句话中文既当结果又当提示；要出英文
     * 只能把 Context 灌进 utils 层，或者同一句话在两处维护。现在只给码 + 数据参数，
     * 文案由 ui 层按资源取（{@code RestoreText}），本层不产任何人类可读文案。</p>
     */
    public enum Code {
        /** 已重装并恢复数据 */
        OK_DATA,
        /** 已重装，备份里没有有效数据 */
        OK_NO_DATA,
        /** 已重装，但数据解包失败 */
        OK_DATA_FAILED,
        /** 备份里没有 APK */
        ERR_NO_APK,
        /** 没有 root */
        ERR_NO_ROOT,
        /** 拷安装包到 /data/local/tmp 失败（参数：APK 文件名、底层输出） */
        ERR_COPY,
        /** pm 安装超时 */
        ERR_TIMEOUT,
        /** 签名不一致 */
        ERR_SIGN,
        /** 分包缺失 */
        ERR_SPLIT,
        /** 其它安装失败（参数：pm 输出） */
        ERR_INSTALL,
        /** 安装失败且 pm 无输出 */
        ERR_UNKNOWN
    }

    /** 还原结果（含细分失败原因，供 UI 直接提示） */
    public static class RestoreOutcome {
        public boolean rootAvailable;
        public boolean installOk;
        public boolean dataOk;
        /** 结果码；新增一个码要同步在 ui 层加译文，否则落到「通用失败」兜底 */
        public Code code;
        /** 码对应的数据参数（文件名、pm 输出之类），不是文案 */
        public Object[] args = EMPTY;

        public String str(int i) {
            return args != null && i < args.length && args[i] != null ? String.valueOf(args[i]) : "";
        }
    }

    private static final Object[] EMPTY = new Object[0];

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
            return fail(o, Code.ERR_NO_APK);
        }
        if (!RootUtil.detect() || !RootUtil.isRootAvailable()) {
            o.rootAvailable = false;
            Log.w(TAG, "restore blocked: root unavailable");
            return fail(o, Code.ERR_NO_ROOT);
        }
        o.rootAvailable = true;

        // 1) 重装 APK（install-create / write / commit 会话流程）
        Log.i(TAG, "restore: start install pkg=" + e.pkg + " apks=" + e.apks.length);
        o.installOk = installApkSet(e.apks, installerPkg, o);
        if (!o.installOk) {
            Log.e(TAG, "restore: install failed: " + o.code);
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
            return fail(o, dataOk ? Code.OK_DATA : Code.OK_DATA_FAILED);
        }
        return fail(o, Code.OK_NO_DATA);
    }

    /** 把结果码（连同数据参数）落到 outcome 上，省掉每处重复两行赋值 */
    private static RestoreOutcome fail(RestoreOutcome o, Code code, Object... args) {
        o.code = code;
        o.args = args == null || args.length == 0 ? EMPTY : args;
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

    /** 安装一组 APK；成功返回 true，失败时把原因码写进 outcome。 */
    private static boolean installApkSet(File[] apks, String installerPkg, RestoreOutcome o) {
        // Android 11+：system_server 无法读取 /sdcard（fuse 标签）上的文件，
        // 必须先把 APK 拷到 /data/local/tmp，再从 tmp 安装。
        List<String> tmpFiles = new ArrayList<>();
        for (File apk : apks) {
            String tmp = "/data/local/tmp/" + apk.getName();
            RootUtil.RunResult cp = RootUtil.runCapture(
                    "cp '" + apk.getPath() + "' '" + tmp + "' 2>&1");
            if (cp == null || cp.exit != 0) {
                cleanupTmp(tmpFiles);
                fail(o, Code.ERR_COPY, apk.getName(), cp == null ? "timeout" : cp.out);
                return false;
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
        if (r == null) {
            fail(o, Code.ERR_TIMEOUT);
            return false;
        }
        if (r.exit != 0) {
            installError(r.out, o);
            return false;
        }
        return true;
    }

    /** 清理 /data/local/tmp 下的临时安装包，避免残留。 */
    private static void cleanupTmp(List<String> tmpFiles) {
        for (String t : tmpFiles) RootUtil.run("rm -f '" + t + "' 2>/dev/null");
    }

    /** pm 安装失败输出 → 可定位的原因码（参数保留 pm 原文，供界面展开） */
    private static void installError(String out, RestoreOutcome o) {
        if (out == null) out = "";
        String lower = out.toLowerCase();
        if (lower.contains("update_incompatible") || lower.contains("signatures do not match")
                || lower.contains("signature mismatch")) {
            fail(o, Code.ERR_SIGN);
            return;
        }
        if (lower.contains("missing_split")) {
            fail(o, Code.ERR_SPLIT);
            return;
        }
        if (lower.contains("install_failed")) {
            fail(o, Code.ERR_INSTALL, out);
            return;
        }
        fail(o, out.isEmpty() ? Code.ERR_UNKNOWN : Code.ERR_INSTALL, out);
    }
}
