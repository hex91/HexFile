package com.hex91.file.fs;

import com.hex91.file.utils.*;
/**
 * v1.86.0：统一的「特权 shell」后端选择器。
 *
 * <p>应用里所有需要提权的命令（列 /data 目录、chmod、pm 安装、su cat 读写）原本一律写死
 * {@code su -c}，没有 root 就整功能不可用。现在改成走这里挑后端：</p>
 *
 * <ul>
 *   <li><b>ROOT</b>：{@code su -c}，uid 0，能力最全；</li>
 *   <li><b>SHIZUKU</b>：{@code sh -c} 以 uid 2000（shell）执行，无需 root，
 *       但 {@code /data/data/<别的应用>} 这类 700 目录仍读不到；</li>
 *   <li><b>NONE</b>：都没有，调用方拿到失败分支（与旧行为一致）。</li>
 * </ul>
 *
 * <p>这样改一处选择逻辑，{@link RootUtil} 的 7 个执行入口（run / submitOutput /
 * runWithInput / captureOutput / catProcess / runCapture / suList）自动同时支持两种后端，
 * 上层 60+ 个业务调用点一行都不用改。</p>
 */
public final class Shell {

    public static final int NONE = 0;
    public static final int ROOT = 1;
    public static final int SHIZUKU = 2;

    private Shell() {}

    /**
     * 当前可用后端。
     *
     * @param allowRoot 用户是否允许使用 Root（设置页开关）。关闭后即使有 su 也走 Shizuku
     */
    public static int backend(boolean allowRoot) {
        if (allowRoot && RootUtil.isRealRoot()) return ROOT;
        if (ShizukuBridge.usable()) return SHIZUKU;
        return NONE;
    }

    /** 是否有任一可用后端 */
    public static boolean available(boolean allowRoot) {
        return backend(allowRoot) != NONE;
    }

    /** 后端名字（状态展示 / 日志用） */
    public static String name(int backend) {
        if (backend == ROOT) return "Root";
        if (backend == SHIZUKU) return "Shizuku";
        return "无";
    }

    /**
     * 启动命令进程。失败抛异常，由调用方统一转成 null / false。
     *
     * <p>Shizuku 分支不能在主线程调用（binder 事务），本类的调用点都在
     * {@link RootUtil} 的单线程池里，天然满足。</p>
     */
    public static Process start(String shellCmd, boolean allowRoot) throws Exception {
        int b = backend(allowRoot);
        if (b == ROOT) {
            return new ProcessBuilder("su", "-c", shellCmd).start();
        }
        if (b == SHIZUKU) {
            return ShizukuBridge.start(shellCmd);
        }
        throw new IllegalStateException("没有可用的提权后端");
    }
}
