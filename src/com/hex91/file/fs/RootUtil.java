package com.hex91.file.fs;

import com.hex91.file.utils.ShizukuBridge;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * v1.42.0 Root 权限检测。
 * 原理：尝试执行 `su -c id`，若命令成功执行且输出含 uid=0( 则设备已 root 且授权通过。
 * 结果进程内缓存（root 状态运行期不变），首次调用阻塞（超时 4s 视为无 root）。
 * 桌面可测：Windows 上 su 不存在 → false；逻辑分支（超时/异常/输出过滤）单测覆盖。
 */
public final class RootUtil {

    private static volatile Boolean cached;      // null=未检测

    /**
     * v1.83.0：用户总开关。关闭后所有 root 能力（检测/执行/读写）一律走失败分支，
     * 调用方无需逐处判断——66 个调用点自动遵守，降低「想纯普通权限跑」时的心智负担。
     */
    private static volatile boolean enabled = true;
    private static final ExecutorService POOL =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "root-probe");
                    t.setDaemon(true);
                    return t;
                }
            });

    private RootUtil() {}

    /**
     * 是否有「特权 shell」可用：真 root，或者 Shizuku 已连接且已授权。
     *
     * <p>v1.86.0 起语义扩展（原来只表示 su）。保留方法名是为了让 60+ 个调用点
     * 「有特权就放开 /data 等受限目录」的判断继续成立；需要区分真正的 uid 0 用
     * {@link #isRealRoot()}。</p>
     */
    public static boolean isRootAvailable() {
        if (!enabled) return false;
        if (isRealRoot()) return true;
        return ShizukuBridge.usable();
    }

    /** 真的 root（su 可用且拿到 uid=0），不含 Shizuku */
    public static boolean isRealRoot() {
        Boolean c = cached;
        return c != null && c;
    }

    /** 当前实际使用的后端：Root / Shizuku / 无（状态展示用） */
    public static String backendName() {
        return Shell.name(Shell.backend(enabled));
    }

    /** 用户是否允许使用 Root（设置页开关；默认允许，实际有无 root 由 isRootAvailable 决定） */
    public static boolean isEnabled() {
        return enabled;
    }

    /** 设置偏好：与工作目录共用 hf_settings */
    public static final String PREF = "hf_settings";

    /** 偏好键：true/false，默认 true */
    public static final String KEY_ENABLED = "root_enabled";

    /** 启动时按用户偏好恢复开关（BaseActivity 每次创建都会调，幂等） */
    public static void init(android.content.Context c) {
        if (c == null) return;
        try {
            setEnabled(c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, true));
        } catch (Throwable ignored) {
        }
    }

    /** 保存用户选择并立即生效 */
    public static void saveEnabled(android.content.Context c, boolean on) {
        setEnabled(on);
        try {
            c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_ENABLED, on).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 打开/关闭 Root 能力。关闭时清掉探测缓存并强制置 false，
     * 之后 {@link #isRootAvailable()} 与所有执行方法都返回失败，等同「无 root 设备」。
     */
    public static void setEnabled(boolean on) {
        enabled = on;
        if (!on) cached = Boolean.FALSE;
    }

    /**
     * 强制探测（阻塞，超时 4s）。返回是否可用特权 shell——
     * su 探测失败时会再看 Shizuku，二者任一可用即为 true。
     */
    public static boolean detect() {
        if (!enabled) return false;
        Boolean c = cached;
        boolean su = (c != null) ? c : detectSu("su");
        return su || ShizukuBridge.usable();
    }

    /** 只探测 su（可注入命令名，测试用）；不叠加 Shizuku */
    public static boolean detectSu(String suCommand) {
        if (!enabled) return false;
        Boolean c = cached;
        if (c != null) return c;
        boolean ok;
        try {
            Future<Boolean> f = POOL.submit(new Probe(suCommand));
            ok = f.get(4, TimeUnit.SECONDS);
        } catch (Exception e) {
            ok = false;   // 超时 / 中断 / 拒绝 —— 一律视为无 root
        }
        cached = ok;
        return ok;
    }

    /**
     * v1.43.0：执行 root shell 命令（阻塞 ≤8s）。返回退出码是否为 0。
     * stdout 被丢弃；需要输出用 {@link #submitOutput}。
     */
    public static boolean run(final String shellCmd) {
        if (!isRootAvailable()) return false;
        try {
            Future<Boolean> f = POOL.submit(new Callable<Boolean>() {
                public Boolean call() throws Exception {
                    Process p = null;
                    try {
                        p = Shell.start(shellCmd, enabled);
                        drain(p);
                        return p.waitFor() == 0;
                    } finally {
                        if (p != null) try { p.destroy(); } catch (Exception ignored) {}
                    }
                }
            });
            return f.get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * v1.43.0：执行 root shell 命令并返回 stdout 文本（trim）。
     * 失败（非零退出/超时）返回 null。
     */
    public static Future<String> submitOutput(final String shellCmd) {
        if (!isRootAvailable()) return done(null);
        return POOL.submit(new Callable<String>() {
            public String call() throws Exception {
                Process p = null;
                try {
                    p = Shell.start(shellCmd, enabled);
                    drain(p);
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(p.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[1024];
                    int n;
                    while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
                    r.close();
                    return p.waitFor() == 0 ? sb.toString().trim() : null;
                } finally {
                    if (p != null) try { p.destroy(); } catch (Exception ignored) {}
                }
            }
        });
    }

    /**
     * v1.44.0：执行命令并向 stdin 写入数据（root 写文件：cat > 'path'）。
     * 阻塞 ≤15s。返回退出码是否为 0。
     */
    public static boolean runWithInput(final String shellCmd, final byte[] data) {
        if (!isRootAvailable()) return false;
        try {
            Future<Boolean> f = POOL.submit(new Callable<Boolean>() {
                public Boolean call() throws Exception {
                    Process p = null;
                    try {
                        p = Shell.start(shellCmd, enabled);
                        drain(p);
                        java.io.OutputStream os = p.getOutputStream();
                        os.write(data);
                        os.close();
                        return p.waitFor() == 0;
                    } finally {
                        if (p != null) try { p.destroy(); } catch (Exception ignored) {}
                    }
                }
            });
            return f.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * v1.44.0：捕获 stdout 为字节数组（二进制安全，供 su cat 读 root 区文件）。
     * 超过 maxBytes / 失败 / 超时（10s）返回 null。
     */
    public static byte[] captureOutput(final String shellCmd, final long maxBytes) {
        if (!isRootAvailable()) return null;
        try {
            Future<byte[]> f = POOL.submit(new Callable<byte[]>() {
                public byte[] call() throws Exception {
                    Process p = null;
                    try {
                        p = Shell.start(shellCmd, enabled);
                        drain(p);
                        java.io.InputStream is = p.getInputStream();
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            if (bos.size() + n > maxBytes) return null;
                            bos.write(buf, 0, n);
                        }
                        is.close();
                        return p.waitFor() == 0 ? bos.toByteArray() : null;
                    } finally {
                        if (p != null) try { p.destroy(); } catch (Exception ignored) {}
                    }
                }
            });
            return f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * v1.44.0：启动 su 进程执行命令（stderr 已排水）。调用方流式读 getInputStream()，
     * 读完自行 waitFor + destroy。启动失败抛异常。
     */
    public static Process catProcess(String shellCmd) throws Exception {
        if (!isRootAvailable()) throw new IllegalStateException("没有可用的提权后端");
        Process p = Shell.start(shellCmd, enabled);
        drain(p);
        return p;
    }

    /** 已完成、值为 v 的 Future（Root 关闭时给 {@link #submitOutput} 兜底） */
    private static <T> Future<T> done(final T v) {
        java.util.concurrent.FutureTask<T> t = new java.util.concurrent.FutureTask<T>(
                new Callable<T>() {
                    public T call() { return v; }
                });
        t.run();
        return t;
    }

    /** stderr 排水（防管道塞满死锁） */
    private static void drain(final Process p) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] buf = new byte[512];
                    while (p.getErrorStream().read(buf) != -1) { /* 排水 */ }
                } catch (Exception ignored) {}
            }
        }, "su-stderr");
        t.setDaemon(true);
        t.start();
    }

    /** 探测任务：su -c id → 输出含 uid=0( 才算通过 */
    public static class Probe implements Callable<Boolean> {
        private final String cmd;
        public Probe(String cmd) { this.cmd = cmd; }

        @Override
        public Boolean call() throws Exception {
            Process p = null;
            try {
                p = new ProcessBuilder(cmd, "-c", "id").start();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[256];
                int n;
                while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
                r.close();
                int code = p.waitFor();
                return code == 0 && sb.toString().contains("uid=0(");
            } finally {
                if (p != null) try { p.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * v1.42.0：root 列目录。`su -c ls -1ap <path>`——
     * -1 每行一条，-a 含隐藏，-p 目录追加 / 尾缀（据此区分文件/文件夹，无需 stat）。
     * 返回原始行（含 ./ ../，由调用方过滤）；失败（无 root / 超时 / 路径不存在）返回 null。
     */
    public static List<String> suList(String path) {
        try {
            Future<List<String>> f = POOL.submit(new LsJob(path));
            return f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** ls 任务：stdout 逐行读 + stderr 独立线程排水（防管道塞满死锁） */
    static class LsJob implements Callable<List<String>> {
        private final String path;
        LsJob(String path) { this.path = path; }

        @Override
        public List<String> call() throws Exception {
            Process p = null;
            try {
                String quoted = "'" + path.replace("'", "'\\''") + "'";
                p = Shell.start("ls -1ap " + quoted, enabled);
                final Process fp = p;
                Thread errDrain = new Thread(new Runnable() {
                    public void run() {
                        try {
                            byte[] buf = new byte[512];
                            while (fp.getErrorStream().read(buf) != -1) { /* 排水 */ }
                        } catch (Exception ignored) {}
                    }
                }, "ls-stderr");
                errDrain.setDaemon(true);
                errDrain.start();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()));
                List<String> out = new ArrayList<>();
                String line;
                while ((line = r.readLine()) != null) out.add(line);
                r.close();
                int code = p.waitFor();
                return code == 0 ? out : null;
            } finally {
                if (p != null) try { p.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    /** v1.54.0：执行结果（退出码 + 合并后的输出文本，便于还原时区分失败原因） */
    public static final class RunResult {
        public int exit;
        public String out;
    }

    /**
     * v1.54.0：执行 root 命令并捕获输出（无论退出码是否为零，stdout/stderr 合并后返回）。
     * 用于还原安装流程：需要读取 `pm install-commit` 的失败原因（签名冲突 / 分包缺失等）。
     * 超时 / 异常 / 未 root 返回 null。
     */
    public static RunResult runCapture(final String shellCmd) {
        return runCapture(shellCmd, 20);
    }

    /**
     * v1.54.1：带超时重载。注意：超时仅放弃等待（返回 null），不会杀掉正在执行的
     * root 命令进程（避免误杀已接近完成的安装），进程会在后台自然结束。
     */
    public static RunResult runCapture(final String shellCmd, long timeoutSec) {
        try {
            Future<RunResult> f = POOL.submit(new Callable<RunResult>() {
                public RunResult call() throws Exception {
                    Process p = null;
                    try {
                        p = Shell.start(shellCmd + " 2>&1", enabled);
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(p.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        char[] buf = new char[1024];
                        int n;
                        while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
                        r.close();
                        RunResult res = new RunResult();
                        res.exit = p.waitFor();
                        res.out = sb.toString().trim();
                        return res;
                    } finally {
                        if (p != null) try { p.destroy(); } catch (Exception ignored) {}
                    }
                }
            });
            return f.get(timeoutSec, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}
