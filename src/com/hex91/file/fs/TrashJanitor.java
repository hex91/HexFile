package com.hex91.file.fs;

/**
 * v1.82.0 回收站自动清理的触发点。
 *
 * <p>只做两件事：进程内最多执行一次、在后台线程跑。
 * 清理失败（无权限、目录被删）静默忽略 —— 这是「顺手打扫」，
 * 不能因为它让主界面弹错误框。</p>
 */
public final class TrashJanitor {

    private static volatile boolean started;

    private TrashJanitor() {}

    /** 启动时调用；应用进程存活期间只跑一次 */
    public static void startOnce() {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    java.io.File dir = Trash.dir();
                    if (!dir.isDirectory()) return;          // 没用过回收站，不必建目录
                    TrashPolicy p = TrashPolicy.load(dir);
                    if (!p.enabled) return;
                    TrashPolicy.Report r = p.apply(dir, System.currentTimeMillis());
                    if (r.removed > 0)
                        android.util.Log.i("HexFile", "trash auto-clean: " + r.summary());
                } catch (Throwable ignored) {
                }
            }
        }, "trash-janitor");
        t.setDaemon(true);
        t.start();
    }
}
