package com.hex91.file.sched;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 真实卷根清单（R-42）。
 *
 * <p>为什么需要这一层：{@link ScheduleRunner#isDangerous(String)} 的纯路径规则只能认出
 * 「/storage 下一层」这种标准写法。厂商自定义挂载点、可移动卷的 uuid 形式、以及
 * {@code Environment} 返回的路径在不同设备上并不统一，光靠字符串会漏。</p>
 *
 * <p>为什么不做成纯静态初始化：{@code Environment} / {@code StorageManager} 都是 Android API，
 * 而在桌面 JVM 上它们只有抛 {@code Stub!} 的桩。单测跑的是纯 Java 逻辑，因此这里只在
 * 真正有 Context 的调用点（保存校验、广播执行）显式收集一次并缓存；桌面路径下
 * {@link #cached()} 永远是 {@code null}，规则退化为纯字符串判定，测试可照常跑。</p>
 *
 * <p>{@code getDirectory()} 是 API 30 才有的方法，24–29 上只能靠 {@code getUuid()} 拼
 * {@code /storage/<uuid>}。每个来源都可能单独失败（老系统、厂商裁剪、权限），
 * 所以统一走 {@link #probe(Callable, String)}：一处 catch、一条诊断，取不到的来源跳过，
 * 不影响其余来源 —— 不要为了「代码整齐」在六个地方各写一个空 catch。</p>
 */
public final class VolumeRoots {

    private static final String TAG = "VolumeRoots";

    /** 首次 {@link #of(Context)} 之前为 null —— 表示「只有纯路径规则可用」 */
    private static volatile List<String> cache;

    private VolumeRoots() {}

    /** 已收集的卷根；未收集过返回 null（调用方按空处理） */
    public static List<String> cached() {
        return cache;
    }

    /**
     * 收集并缓存当前设备的卷根。可以重复调用（插拔 SD 卡后重新收集）。
     *
     * @return 不可变的卷根列表（可能为空，但不会为 null）
     */
    public static List<String> of(Context ctx) {
        List<String> out = new ArrayList<String>();
        if (ctx != null) collect(ctx, out);
        List<String> frozen = out.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(out);
        cache = frozen;
        return frozen;
    }

    /** 清掉缓存（工作目录切换、卷挂载变化后调用） */
    public static void clear() {
        cache = null;
    }

    private static void collect(Context ctx, List<String> out) {
        // 1) 主外部存储：/storage/emulated/0（真实存储根最常见的写法）
        add(out, probe(new Callable<String>() {
            public String call() {
                File ext = Environment.getExternalStorageDirectory();
                return ext == null ? null : ext.getAbsolutePath();
            }
        }, "external"));

        // 2) 各卷的 app 专属目录 → 砍掉 /Android/... 得到卷根（覆盖可移动卷）
        List<String> appRoots = probe(new Callable<List<String>>() {
            public List<String> call() {
                List<String> r = new ArrayList<String>();
                File[] dirs = ctx.getExternalFilesDirs(null);
                if (dirs == null) return r;
                for (int i = 0; i < dirs.length; i++) {
                    if (dirs[i] == null) continue;
                    String s = dirs[i].getAbsolutePath().replace('\\', '/');
                    int cut = s.indexOf("/Android/");
                    String root = cut > 0 ? s.substring(0, cut) : null;
                    if (root != null) r.add(root);
                }
                return r;
            }
        }, "externalFilesDirs");
        if (appRoots != null) {
            for (int i = 0; i < appRoots.size(); i++) add(out, appRoots.get(i));
        }

        // 3) StorageManager 的卷列表：API 30+ 直接给目录，24–29 用 uuid 拼
        List<String> vols = probe(new Callable<List<String>>() {
            public List<String> call() {
                List<String> r = new ArrayList<String>();
                StorageManager sm =
                        (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
                if (sm == null) return r;
                List<StorageVolume> list = sm.getStorageVolumes();
                for (int i = 0; i < list.size(); i++) {
                    StorageVolume v = list.get(i);
                    if (v == null) continue;
                    String dir = probe(new Callable<String>() {
                        public String call() {
                            File d = v.getDirectory();      // API 30+
                            return d == null ? null : d.getAbsolutePath();
                        }
                    }, "volume.directory");
                    if (dir == null) {
                        dir = probe(new Callable<String>() {
                            public String call() {
                                String uuid = v.getUuid();  // API 24+
                                if (uuid != null) return "/storage/" + uuid;
                                if (!v.isPrimary()) return null;
                                File ext = Environment.getExternalStorageDirectory();
                                return ext == null ? null : ext.getAbsolutePath();
                            }
                        }, "volume.uuid");
                    }
                    if (dir != null) r.add(dir);
                }
                return r;
            }
        }, "storageVolumes");
        if (vols != null) {
            for (int i = 0; i < vols.size(); i++) add(out, vols.get(i));
        }
    }

    /**
     * 取一个可能抛异常的来源值。失败返回 null 并记一条诊断 ——
     * 卷根探测失败本身不该让「保存定时任务」跟着失败，但也不该完全无声。
     */
    private static <T> T probe(Callable<T> c, String what) {
        try {
            return c.call();
        } catch (Throwable t) {
            Log.w(TAG, "volume probe <" + what + "> failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    private static void add(List<String> out, String path) {
        if (path == null) return;
        String p = path.trim().replace('\\', '/');
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty() || "/".equals(p)) return;   // 根目录由纯路径规则拦，不必进清单
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).equals(p)) return;
        }
        out.add(p);
    }
}
