
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
    private static final ExecutorService POOL =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "root-probe");
                    t.setDaemon(true);
                    return t;
                }
            });

    private RootUtil() {}

    /** 是否已 root 且 su 可用（带缓存；从未检测则现场探测） */
    public static boolean isRootAvailable() {
        Boolean c = cached;
        return c != null && c;
    }

    /** 强制探测（阻塞，超时 4s）。返回是否已 root。 */
    public static boolean detect() {
        return detect("su");
    }

    /** 可注入命令名的探测（测试用）。 */
    static boolean detect(String suCommand) {
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
        try {
            Future<Boolean> f = POOL.submit(new Callable<Boolean>() {
                public Boolean call() throws Exception {
                    Process p = null;
                    try {
                        p = new ProcessBuilder("su", "-c", shellCmd).start();
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
        return POOL.submit(new Callable<String>() {
            public String call() throws Exception {
                Process p = null;
                try {
                    p = new ProcessBuilder("su", "-c", shellCmd).start();
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
        try {
            Future<Boolean> f = POOL.submit(new Callable<Boolean>() {
                public Boolean call() throws Exception {
                    Process p = null;
                    try {
                        p = new ProcessBuilder("su", "-c", shellCmd).start();
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
        try {
            Future<byte[]> f = POOL.submit(new Callable<byte[]>() {
                public byte[] call() throws Exception {
                    Process p = null;
                    try {
                        p = new ProcessBuilder("su", "-c", shellCmd).start();
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
        Process p = new ProcessBuilder("su", "-c", shellCmd).start();
        drain(p);
        return p;
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
    static class Probe implements Callable<Boolean> {
        private final String cmd;
        Probe(String cmd) { this.cmd = cmd; }

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
                p = new ProcessBuilder("su", "-c", "ls -1ap " + quoted).start();
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
}
