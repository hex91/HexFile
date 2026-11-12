package com.hex91.file.fs;

import com.hex91.file.core.*;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * v1.43.0 文件属性（权限串/八进制/所有者/用户组）。
 * - 有 root：su stat -c '%A|%a|%U|%G' 一次拿全（与 MT 显示一致）
 * - 无 root：File.can* 近似（反映应用实际权限，三段位一致处理）
 * - LRU 缓存 512 条（key = path|lastModified），chmod/chown 成功后自动失效
 *   （纯 LinkedHashMap 实现，桌面测试可跑；android.util.LruCache 是桩类）
 * - load() 异步单线程池，回调主线程（列表详情行懒加载用）
 */
public final class FileAttrs {

    /** 属性快照 */
    public static class Attr {
        public String perm;     // 如 "-rw-r--r--"
        public int octal;       // 权限位真值（如 0644）；未知 = -1
        public String owner;    // 如 "root"；未知 = null
        public String group;    // 如 "root"；未知 = null
        public long mtime;      // v1.44.1：修改时间 ms（root 区 File API 拿不到，su stat 补）
        public long size;       // v1.44.1：字节大小（同上；目录为 0）

        /** 八进制显示串（如 "644"） */
        public String octalText() { return octal >= 0 ? Integer.toOctalString(octal) : "?"; }
    }

    private static final Map<String, Attr> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, Attr>(256, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, Attr> e) {
                    return size() > 512;
                }
            });
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static volatile Object MAIN;   // android.os.Handler，惰性创建（桌面测试不触发）

    private FileAttrs() {}

    private static Object mainHandler() {
        Object h = MAIN;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            MAIN = h;
        }
        return h;
    }

    private static String key(File f) {
        return f.getPath() + "|" + f.lastModified();
    }

    /** 取缓存（未加载过返回 null；列表详情行用它，命中即显示权限） */
    public static Attr get(File f) {
        return CACHE.get(key(f));
    }

    /** 异步加载（回调主线程；内部已加载也回调一次，方便统一刷新 UI） */
    public static void load(final File f, final Callback cb) {
        POOL.execute(new Runnable() {
            public void run() {
                final Attr a = stat(f);
                ((Handler) mainHandler()).post(new Runnable() {
                    public void run() { cb.onAttr(a); }
                });
            }
        });
    }

    public interface Callback { void onAttr(Attr a); }

    /** 同步取属性：先查缓存，miss 则 stat（root 优先，失败走近似） */
    public static Attr stat(File f) {
        String k = key(f);
        Attr c = CACHE.get(k);
        if (c != null) return c;
        Attr a = RootUtil.isRootAvailable() ? statViaSu(f.getPath()) : statFallback(f);
        if (a != null) CACHE.put(k, a);
        return a;
    }

    /** su stat -c '%A|%a|%U|%G|%Y|%s'：权限+所有者+修改时间+大小一次拿全（v1.44.1 扩展） */
    static Attr statViaSu(String path) {
        try {
            String quoted = "'" + path.replace("'", "'\\''") + "'";
            Future<String> f = RootUtil.submitOutput(
                    "stat -c '%A|%a|%U|%G|%Y|%s' " + quoted);
            String out = f.get(8, TimeUnit.SECONDS);
            if (out == null) return statFallback(new File(path));
            String[] p = out.trim().split("\\|");
            if (p.length < 4) return statFallback(new File(path));
            Attr a = new Attr();
            a.perm = p[0];
            // v1.43.0 修复：%a 输出的是八进制串，必须按 8 进制解析（此前按十进制导致勾选映射全错）
            try { a.octal = Integer.parseInt(p[1].trim(), 8); } catch (Exception e) { a.octal = -1; }
            a.owner = p[2].isEmpty() ? String.valueOf("?") : p[2];
            a.group = p[3].isEmpty() ? "?" : p[3];
            // v1.44.1：%Y 秒级 mtime、%s 字节大小（root 区 File API 全为 0，靠这里补显示）
            if (p.length >= 6) {
                try { a.mtime = Long.parseLong(p[4].trim()) * 1000L; } catch (Exception e) { a.mtime = 0; }
                try { a.size = Long.parseLong(p[5].trim()); } catch (Exception e) { a.size = 0; }
            }
            return a;
        } catch (Exception e) {
            return statFallback(new File(path));
        }
    }

    /** 无 root 近似：can* 反映应用实际权限；三段一致（sdcard 挂载视图下通常成立） */
    public static Attr statFallback(File f) {
        Attr a = new Attr();
        String t = (f.canRead() ? "r" : "-") + (f.canWrite() ? "w" : "-")
                + (f.canExecute() ? "x" : "-");
        StringBuilder sb = new StringBuilder(f.isDirectory() ? "d" : "-");
        sb.append(t).append(t).append(t);
        a.perm = sb.toString();
        a.octal = -1;
        a.owner = null;   // 未知
        a.group = null;
        a.mtime = f.lastModified();   // v1.44.1
        a.size = f.length();
        return a;
    }

    /**
     * chmod（root）。octal 为权限位真值。
     * v1.43.1 返回值语义：0=已实际生效；1=命令成功但回读权限未变化（FUSE 合成存储如
     * /sdcard 会忽略 chmod，退出码仍为 0）；-1=命令失败（无 root / 超时）。
     * 无论结果如何都回读真实属性刷新缓存，保证 UI 与磁盘一致。
     */
    public static int chmod(File f, int octal) {
        int mode = octal & 07777;
        if (!RootUtil.isRootAvailable()) {
            // v1.80.0：没有 root 也能改「本应用拥有」的文件（应用私有目录、SAF 授权目录下的文件），
            // 之前一律直接返回失败，等于把这条能力锁死了
            try {
                android.system.Os.chmod(f.getPath(), mode);
            } catch (Throwable e) {
                return -1;
            }
            CACHE.remove(key(f));
            Attr fa = statFallback(f);
            if (fa != null) CACHE.put(key(f), fa);
            return 0;
        }
        String quoted = "'" + f.getPath().replace("'", "'\\''") + "'";
        boolean ok = RootUtil.run("chmod " + Integer.toOctalString(mode) + " " + quoted);
        if (!ok) return -1;
        CACHE.remove(key(f));
        Attr a = statViaSu(f.getPath());   // 回读真实值（绕过缓存）
        if (a != null) CACHE.put(key(f), a);
        if (a == null || a.octal < 0) return 0;   // 无法回读时按成功处理
        return ((a.octal & 07777) == mode) ? 0 : 1;
    }

    /**
     * v1.80.0：递归 chmod（目录连同子项）。
     * 用 Java 递归而不是 {@code chmod -R}，是为了无 root 时也能走 Os.chmod 这条路径；
     * 深度上限 64 层，防止异常软链接环把主线程/工作线程拖死（调用方仍需放后台线程）。
     */
    public static int chmodTree(File f, int octal) {
        return chmodTree(f, octal, 0);
    }

    private static int chmodTree(File f, int octal, int depth) {
        // v1.99.1（S-1 同类）：chmod 本身会跟随链接改到目标上。递归改权限时只要子树里
        // 有一条指向 /system 或别的应用数据的链接，就等于把对方的权限改了 —— 链接既不改也不进。
        if (LinkUtil.isLink(f)) return 0;
        int rc = chmod(f, octal);
        if (depth >= 64 || !f.isDirectory()) return rc;
        File[] kids = f.listFiles();
        if (kids == null) return rc;
        for (File k : kids) {
            int r = chmodTree(k, octal, depth + 1);
            if (r != 0) rc = r;          // 有失败就记下来（成功项已生效，不回滚）
        }
        return rc;
    }

    /**
     * v1.80.0：修改时间。
     * 先试 {@link File#setLastModified(long)}（应用自己的文件通常能成功），失败再走 root 的
     * {@code touch -t}。注意 touch 用设备本地时区解释时间串，所以格式化也用默认时区。
     */
    public static boolean setMtime(File f, long ms) {
        boolean ok = false;
        try {
            ok = f.setLastModified(ms);
        } catch (Throwable ignore) {
        }
        if (!ok && RootUtil.isRootAvailable()) {
            String t = new java.text.SimpleDateFormat("yyyyMMddHHmm.ss", java.util.Locale.US)
                    .format(new java.util.Date(ms));
            String quoted = "'" + f.getPath().replace("'", "'\\''") + "'";
            ok = RootUtil.run("touch -t " + t + " " + quoted);
        }
        CACHE.remove(key(f));
        return ok;
    }

    /**
     * chown（root）。owner 或 group 可为 null（不变更）。
     * v1.43.1 返回值语义同 {@link #chmod}：0=生效；1=命令成功但回读未变化；-1=失败。
     */
    public static int chown(File f, String owner, String group) {
        if (!RootUtil.isRootAvailable()) return -1;
        String spec;
        if (owner != null && group != null) spec = owner + ":" + group;
        else if (owner != null) spec = owner;
        else if (group != null) spec = ":" + group;
        else return 0;
        String quoted = "'" + f.getPath().replace("'", "'\\''") + "'";
        boolean ok = RootUtil.run("chown " + spec + " " + quoted);
        if (!ok) return -1;
        CACHE.remove(key(f));
        Attr a = statViaSu(f.getPath());   // 回读真实值
        if (a != null) CACHE.put(key(f), a);
        if (a == null || a.owner == null) return 0;
        boolean ownerOk = owner == null || owner.equals(a.owner);
        boolean groupOk = group == null || group.equals(a.group);
        return (ownerOk && groupOk) ? 0 : 1;
    }

    /** 失效单条（外部改动后调用） */
    public static void invalidate(File f) { CACHE.remove(key(f)); }
}
