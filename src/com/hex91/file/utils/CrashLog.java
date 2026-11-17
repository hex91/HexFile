package com.hex91.file.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v2.12.0（F-18）：崩溃落盘 + 诊断包。
 *
 * <p><b>为什么必须做</b>：这个工程的 {@link Bg} 在注释里就写着「默认
 * UncaughtExceptionHandler 只打到 stderr，release 版本里基本看不到」。也就是说
 * 用户在真机上闪退，我们手里唯一的信息是「闪退了」——没有堆栈、没有版本、没有机型。
 * 厂商 ROM 的兼容问题（生物识别、分屏、文件选择器）基本只能靠现场排。</p>
 *
 * <p>三个硬约束，写的时候必须守：</p>
 * <ol>
 *   <li><b>不能吞异常</b>：写完文件一定要把异常交回原来的 handler，让系统照常弹
 *       「应用已停止」并杀进程。否则界面会停在半死状态，用户以为只是卡了一下。</li>
 *   <li><b>处理器本身不能再崩</b>：写文件全程 try/catch + 静态重入标志，
 *       真崩了就退到 {@code System.err} —— 崩溃处理器再崩会变成无输出死循环。</li>
 *   <li><b>不能无限增长</b>：只留最近 {@link #KEEP} 份，单份 ≤ {@link #MAX_BYTES}。
 *       用户可能连续崩几十次，不设上限等于往用户存储里倒垃圾。</li>
 * </ol>
 *
 * <p>诊断包（{@link #exportDiag}）脱敏后再打包：{@code pass/token/secret/sig/auth}
 * 附近的整段字符串替换成 {@code [redacted]}，偏好里的敏感键只留键名不留值。
 * 这一条不是「顺手做」——诊断包是要用户主动发给别人的东西。</p>
 */
public final class CrashLog {

    /** files/crash 下的文件名前缀；排序即时间序（定长数字，字符串比较可用） */
    private static final String PREFIX = "crash-";
    /** 只保留最近几份：连续崩溃不至于把用户存储写满 */
    public static final int KEEP = 5;
    /** 单份上限：堆栈 + 200 行日志正常在 30 KB 内，256 KB 足够且不会拖慢启动 */
    public static final long MAX_BYTES = 256 * 1024;
    /** 诊断包内单个文件上限（偏好/日志/崩溃各一份） */
    private static final long DIAG_FILE_MAX = 512 * 1024;

    private static final String PREF = "hf_crash";
    private static final String KEY_SEEN = "last_seen";

    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
    private static final SimpleDateFormat HUMAN =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    /** 应用 Context（Application 注入后一直可用；没装 Application 时由 install 兜底） */
    private static Context appCtx;
    /** 写文件重入标志：崩溃处理器自己崩时不再递归 */
    private static boolean writing;

    private CrashLog() {}

    // ------------------------------------------------------------------ 安装

    /**
     * 装上全局未捕获异常处理器（幂等）。
     *
     * <p>由 {@code HfApp}（Application 子类）在进程启动最早处调用一次；
     * 二次调用直接返回，避免把我们自己的 handler 套成 prev（那样会重复写两份）。</p>
     */
    public static synchronized void install(Context c) {
        if (c == null) return;
        appCtx = c.getApplicationContext();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof Handler) return;
        Thread.setDefaultUncaughtExceptionHandler(new Handler(prev));
        LogRing.add("CrashLog", "installed, prev=" + (prev == null ? "none" : prev.getClass().getName()));
    }

    /** 是否已安装（诊断包 meta 里会写，用来区分「崩溃发生在装 handler 之前」） */
    public static boolean installed() {
        return Thread.getDefaultUncaughtExceptionHandler() instanceof Handler;
    }

    private static final class Handler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler prev;

        Handler(Thread.UncaughtExceptionHandler prev) {
            this.prev = prev;
        }

        public void uncaughtException(Thread t, Throwable e) {
            try {
                write(appCtx, t, e);
            } catch (Throwable second) {
                // 走到这里说明连崩溃报告都写不出来（存储满/目录不可写）：
                // 只能退到 stderr，绝不能再抛
                System.err.println("HexFile: crash log failed: " + second);
            }
            LogRing.addLine("FATAL " + (t == null ? "?" : t.getName()) + ": " + e);
            if (prev != null) {
                prev.uncaughtException(t, e);
                return;
            }
            // 系统没给默认 handler（极少见）：自己结束进程，别留半死状态
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        }
    }

    // ------------------------------------------------------------------ 读写

    /** 崩溃报告目录（files/crash） */
    public static File dir(Context c) {
        Context app = app(c);
        return new File(app.getFilesDir(), "crash");
    }

    private static Context app(Context c) {
        if (c != null) return c.getApplicationContext();
        return appCtx;
    }

    /** 现存崩溃报告，**新的在前**（文件名里的时间戳定长，字典序即时间序） */
    public static List<File> list(Context c) {
        List<File> out = new ArrayList<File>();
        Context app = app(c);
        if (app == null) return out;
        File[] fs = dir(app).listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isFile() && f.getName().startsWith(PREFIX)) out.add(f);
        }
        java.util.Collections.sort(out, new Comparator<File>() {
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());   // 倒序
            }
        });
        return out;
    }

    /** 最近一次崩溃报告；没有则 null */
    public static File newest(Context c) {
        List<File> all = list(c);
        return all.isEmpty() ? null : all.get(0);
    }

    /** 未读崩溃数（用户还没在崩溃页看过的那几份） */
    public static int pendingCount(Context c) {
        Context app = app(c);
        if (app == null) return 0;
        String seen = app.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_SEEN, "");
        List<File> all = list(app);
        int n = 0;
        for (File f : all) {
            if (seen != null && seen.compareTo(f.getName()) >= 0) break;   // 倒序，后面都更旧
            n++;
        }
        return n;
    }

    /** 把这批崩溃标记为已读（用户打开过崩溃页 / 主动忽略） */
    public static void markSeen(Context c) {
        Context app = app(c);
        if (app == null) return;
        File f = newest(app);
        if (f == null) return;
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_SEEN, f.getName()).apply();
    }

    /** 删除全部崩溃报告（崩溃页的「删除」） */
    public static void clear(Context c) {
        Context app = app(c);
        if (app == null) return;
        List<File> all = list(app);
        for (File f : all) {
            if (!f.delete()) LogRing.add("CrashLog", "delete failed: " + f.getName());
        }
        markSeen(app);
    }

    /** 崩溃报告正文：读不到返回空串（界面上显示「空」比抛异常好） */
    public static String read(Context c, File f) {
        if (f == null || !f.isFile()) return "";
        try {
            long len = f.length();
            byte[] data = new byte[(int) Math.min(len, MAX_BYTES)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n <= 0) break;
                    off += n;
                }
            } finally {
                in.close();
            }
            return new String(data, "UTF-8");
        } catch (Throwable t) {
            LogRing.add("CrashLog", "read failed: " + t);
            return "";
        }
    }

    // ------------------------------------------------------------------ 落盘

    /** 写一份崩溃报告；返回落盘文件（写失败抛异常，由 handler 兜底） */
    static File write(Context c, Thread t, Throwable e) throws IOException {
        Context app = app(c);
        if (app == null) return null;
        if (writing) return null;      // 重入保护：处理器内部又崩了就不再写
        writing = true;
        try {
            File d = dir(app);
            if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
                throw new IOException("cannot create " + d.getAbsolutePath());
            }
            File f = new File(d, PREFIX + STAMP.format(new Date()) + ".txt");
            StringBuilder sb = new StringBuilder(8192);
            sb.append("# HexFile crash report\n");
            sb.append(meta(app));
            sb.append("thread: ").append(t == null ? "?" : t.getName() + " (id=" + t.getId() + ")")
                    .append('\n');
            sb.append("handler_installed: ").append(installed()).append('\n');
            sb.append("\n--- last log lines (newest last) ---\n");
            sb.append(LogRing.dump(200));
            sb.append("\n--- stack ---\n");
            sb.append(stackOf(e));
            byte[] bytes = sb.toString().getBytes("UTF-8");
            FileOutputStream fo = new FileOutputStream(f);
            try {
                fo.write(bytes, 0, (int) Math.min(bytes.length, MAX_BYTES));
                fo.flush();
            } finally {
                fo.close();
            }
            prune(d);
            return f;
        } finally {
            writing = false;
        }
    }

    /** 只留最近 KEEP 份；按文件名（时间戳）倒序，删尾部的 */
    private static void prune(File d) {
        File[] fs = d.listFiles();
        if (fs == null || fs.length <= KEEP) return;
        Arrays.sort(fs, new Comparator<File>() {
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());
            }
        });
        for (int i = KEEP; i < fs.length; i++) {
            if (!fs[i].delete()) LogRing.add("CrashLog", "prune failed: " + fs[i].getName());
        }
    }

    private static String stackOf(Throwable e) {
        if (e == null) return "(no throwable)\n";
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        try {
            e.printStackTrace(pw);
        } finally {
            pw.flush();
            pw.close();
        }
        // 因果链最深处往往是真正的原因（比如包装过的 IOException）
        return sw.toString();
    }

    /** 版本/机型/系统：排厂商问题第一眼要看的东西 */
    private static String meta(Context app) {
        StringBuilder sb = new StringBuilder();
        sb.append("package: ").append(app.getPackageName()).append('\n');
        sb.append("version: ").append(verName(app)).append(" (").append(verCode(app)).append(")\n");
        sb.append("time: ").append(HUMAN.format(new Date())).append('\n');
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("abi: ").append(Build.CPU_ABI).append('\n');
        sb.append("files_dir: ").append(app.getFilesDir().getAbsolutePath()).append('\n');
        return sb.toString();
    }

    private static String verName(Context app) {
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            LogRing.add("CrashLog", "versionName unreadable: " + t);
            return "?";
        }
    }

    private static int verCode(Context app) {
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return pi.versionCode;
        } catch (Throwable t) {
            LogRing.add("CrashLog", "versionCode unreadable: " + t);
            return -1;
        }
    }

    // ------------------------------------------------------------------ 诊断包

    /**
     * 打一个诊断包到 cacheDir 并返回：版本/设备 + 最近日志 + 全部崩溃 + 两份偏好快照。
     *
     * <p>放缓存目录而不是工作目录：用户分享完就不需要它了，系统清缓存时会一起收走，
     * 不会在 /sdcard 里留下一个「不知道干什么用」的 zip。</p>
     */
    public static File exportDiag(Context c) throws Exception {
        Context app = app(c);
        if (app == null) throw new IOException("no context");
        File out = new File(app.getCacheDir(), "hexfile-diag-" + STAMP.format(new Date()) + ".zip");
        ZipOutputStream z = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)));
        try {
            put(z, "meta.txt", meta(app)
                    + "handler_installed: " + installed() + "\n"
                    + "log_lines: " + LogRing.size() + "\n"
                    + "crash_files: " + list(app).size() + "\n");
            put(z, "log/ring.txt", LogRing.dump(200));
            for (File f : list(app)) {
                put(z, "crash/" + f.getName(), read(app, f));
            }
            put(z, "prefs/hf_settings.txt", prefs(app, "hf_settings"));
            put(z, "prefs/hexfile_task.txt", prefs(app, "hexfile_task"));
        } finally {
            z.close();
        }
        LogRing.add("CrashLog", "diag exported " + out.getName() + " (" + out.length() + " B)");
        return out;
    }

    private static void put(ZipOutputStream z, String name, String text) throws IOException {
        byte[] data = redact(text == null ? "" : text).getBytes("UTF-8");
        z.putNextEntry(new ZipEntry(name));
        z.write(data, 0, (int) Math.min(data.length, DIAG_FILE_MAX));
        z.closeEntry();
    }

    /** 偏好快照：敏感键只留「键名（已隐藏）」，其余原样 */
    private static String prefs(Context app, String name) {
        StringBuilder sb = new StringBuilder();
        SharedPreferences sp = app.getSharedPreferences(name, Context.MODE_PRIVATE);
        Map<String, ?> all = sp.getAll();
        List<String> keys = new ArrayList<String>(all.keySet());
        java.util.Collections.sort(keys);
        for (String k : keys) {
            if (sensitive(k)) {
                sb.append(k).append(" = [hidden]\n");
                continue;
            }
            Object v = all.get(k);
            sb.append(k).append(" = ").append(v == null ? "null" : String.valueOf(v)).append('\n');
        }
        return sb.toString();
    }

    private static boolean sensitive(String key) {
        if (key == null) return false;
        String k = key.toLowerCase(Locale.US);
        return k.indexOf("pass") >= 0 || k.indexOf("token") >= 0 || k.indexOf("secret") >= 0
                || k.indexOf("credential") >= 0 || k.indexOf("ks_") >= 0 || k.indexOf("auth") >= 0;
    }

    /**
     * 脱敏：整段「含敏感词的 token」替换成 {@code [redacted]}。
     *
     * <p>连词一起替换（而不是把值打码、留下 {@code pass=***}）是刻意的：
     * 诊断包要能放心贴到工单里，正文里连「这里有个密码字段」都不该出现。</p>
     */
    public static String redact(String text) {
        if (text == null || text.length() == 0) return "";
        // 两段：① 含敏感词的整个 token（password=abc / ks_pass: / Authorization:）；
        // ② 紧跟其后的一个取值 token（key: value 中间有空格的情况）。
        // 只吃一个 token 而不是整行：堆栈里的「哪个方法、哪一行」比后面的说明更值钱，
        // 整行抹掉会让诊断包只剩一串 [redacted]。
        return text.replaceAll(
                "(?i)\\S*(pass|token|secret|sig|auth|credential)\\S*(\\s*[:=]?\\s*\\S+){0,1}",
                "[redacted]");
    }
}
