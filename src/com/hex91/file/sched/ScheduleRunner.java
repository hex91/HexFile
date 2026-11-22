package com.hex91.file.sched;

import com.hex91.file.fs.TreeOps;
import com.hex91.file.fs.VaultStore;
import com.hex91.file.fs.ZipUtil;

import java.io.File;

/**
 * 执行一条定时任务（v2.17.0，F-23）。
 *
 * <p>三个动作都复用已有能力，不另写一套文件操作：清空走 {@link TreeOps#delete(File, TreeOps.LinkProbe)}，
 * 压缩走 {@link ZipUtil#createZip(File, java.util.List, TreeOps.LinkProbe)}。
 * 直接调 {@code File.delete()} / 自己递归的写法在符号链接上会删到目标里去 ——
 * 那条坑 {@code TreeOps} 已经踩过并修好了，这里只负责调用。</p>
 *
 * <p>链接探测可注入（与 TreeOps/ZipUtil 同一套设计），桌面单测才能验证「不跟进链接」。</p>
 */
public final class ScheduleRunner {

    /** 递归深度上限：与 TreeOps 一致，防「链接套链接」被展开成无限层 */
    private static final int MAX_DEPTH = 32;

    private ScheduleRunner() {}

    /** 执行结果 */
    public static final class Result {
        /** 失败原因码；{@link #E_NONE} = 全部成功 */
        public static final int E_NONE = 0;
        public static final int E_EMPTY_TASK = 1;
        public static final int E_UNKNOWN_ACTION = 2;
        public static final int E_NO_TARGET = 3;
        public static final int E_DANGEROUS = 4;
        public static final int E_NOT_EXIST = 5;
        public static final int E_NOT_DIR = 6;
        public static final int E_LIST_FAIL = 7;
        public static final int E_CLEAN_PARTIAL = 8;
        public static final int E_BAD_DAYS = 9;
        public static final int E_EXPIRE_PARTIAL = 10;
        public static final int E_NO_PARENT = 11;
        public static final int E_EMPTY_DIR = 12;
        public static final int E_VAULT_ONLY = 13;
        public static final int E_ZIP_FAIL = 14;

        /** 处理掉的条目数：clean=删掉的顶层项，expire=删掉的文件数，zip=打进包的顶层项 */
        public final int done;
        /** 失败原因码；E_NONE = 全部成功。展示文案由 {@link SchedText} 映射 */
        public final int code;
        /** 原因码附带的参数（路径 / 计数），供文案占位使用 */
        public final Object[] args;
        /** ASCII 诊断串（日志用）；成功时为 null */
        public final String error;

        public Result(int done, int code, Object... args) {
            this.done = done;
            this.code = code;
            this.args = args;
            this.error = code == E_NONE ? null : diagnose(code, args);
        }

        public boolean ok() { return code == E_NONE; }

        public Object arg(int i) {
            return args != null && i < args.length ? args[i] : null;
        }

        public String str(int i) {
            Object o = arg(i);
            return o == null ? "" : String.valueOf(o);
        }

        public int num(int i) {
            Object o = arg(i);
            return o instanceof Number ? ((Number) o).intValue() : 0;
        }

        private static String diagnose(int code, Object[] args) {
            StringBuilder sb = new StringBuilder("SCHED E").append(code);
            if (args != null) {
                for (Object a : args) sb.append(' ').append(a);
            }
            return sb.toString();
        }
    }

    public static Result run(ScheduleItem it) {
        return run(it, System.currentTimeMillis(), TreeOps.SYSTEM);
    }

    public static Result run(ScheduleItem it, long now, TreeOps.LinkProbe probe) {
        if (it == null) return new Result(0, Result.E_EMPTY_TASK);
        if (!it.actionKnown()) return new Result(0, Result.E_UNKNOWN_ACTION, it.action);
        String t = it.target == null ? "" : it.target.trim();
        if (t.isEmpty()) return new Result(0, Result.E_NO_TARGET);
        if (isDangerous(t)) return new Result(0, Result.E_DANGEROUS, t);
        File dir = new File(t);
        if (!dir.exists()) return new Result(0, Result.E_NOT_EXIST, t);
        if (!dir.isDirectory()) return new Result(0, Result.E_NOT_DIR, t);
        TreeOps.LinkProbe p = probe == null ? TreeOps.SYSTEM : probe;

        if (ScheduleItem.ACTION_CLEAN.equals(it.action)) return clean(dir, p);
        if (ScheduleItem.ACTION_EXPIRE.equals(it.action)) return expire(dir, now, it.param, p);
        return zip(dir, p);
    }

    /**
     * 目标目录是否属于「绝对不能定时清空」的那一类（v2.17.0 / R-42）。
     *
     * <p>手输路径的定时任务最坏的失败不是「没执行」，而是「执行错了目录」：一次
     * {@code /} 或 {@code /system} 就能把系统清掉，而且用户点保存时并不会有任何感觉。
     * 这些路径没有一个是有理由定时清空的，所以在执行与保存两处都挡一遍。</p>
     *
     * <p>v2.17.0 只拦了「挂载点本身」，于是 {@code /storage/emulated/0} ——
     * {@code Environment.getExternalStorageDirectory()} 的返回值，也是真实存储根最
     * 常见的写法 —— 被放行，一次清空就删掉 DCIM / Download / Android/data。
     * R-42 补两层：① 纯路径规则认「/storage 下的卷根形式」与「/storage/emulated/<用户>」；
     * ② 调用方注入的真实卷根（{@link VolumeRoots}）逐一比对，覆盖厂商自定义挂载点。</p>
     */
    public static boolean isDangerous(String path) {
        return isDangerous(path, VolumeRoots.cached());
    }

    /**
     * @param extraRoots 额外的卷根（真实存储根 / 可移动卷根）。为空时只走纯路径规则 ——
     *                   桌面单测走这条，不触碰 {@code android.os} 的 stub。
     */
    public static boolean isDangerous(String path, java.util.List<String> extraRoots) {
        if (path == null) return true;
        String p = normPath(path);
        if (p.isEmpty()) return true;
        // 先折叠 "." / ".."：/storage/emulated/0/../0 与 /storage/emulated/0 是同一个目录，
        // 字符串上看不出来。自己实现而不是用 java.nio.file.Paths：那是 API 26+，
        // 且 Windows 桌面上 "/x" 会被解析成当前盘根，单测与真机结论会不一致。
        String n = collapse(p);
        if (hitRule(n)) return true;
        // 再解析软链：/sdcard 在很多设备上是指向 /storage/emulated/0 的链接
        String c = canonical(n);
        if (!c.equals(n) && hitRule(c)) return true;
        return hitRoots(n, c, extraRoots);
    }

    /** 归一：反斜杠转正斜杠、折叠重复斜杠、去尾斜杠（保留根 "/"） */
    private static String normPath(String path) {
        String src = path.trim().replace('\\', '/');
        StringBuilder sb = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '/' && sb.length() > 0 && sb.charAt(sb.length() - 1) == '/') continue;
            sb.append(ch);
        }
        String p = sb.toString();
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    /** 折叠 "." 与 ".."（绝对路径，纯字符串，不碰文件系统） */
    private static String collapse(String p) {
        String[] seg = p.split("/");
        java.util.ArrayList<String> st = new java.util.ArrayList<String>(seg.length);
        for (int i = 0; i < seg.length; i++) {
            String s = seg[i];
            if (s.isEmpty() || ".".equals(s)) continue;
            if ("..".equals(s)) {
                if (!st.isEmpty()) st.remove(st.size() - 1);
                continue;
            }
            st.add(s);
        }
        if (st.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder(p.length());
        for (int i = 0; i < st.size(); i++) sb.append('/').append(st.get(i));
        return sb.toString();
    }

    /** canonical 化失败（不存在 / 无权限 / 相对路径）时回落原值，宁可多判不能漏判 */
    private static String canonical(String p) {        try {
            String c = normPath(new File(p).getCanonicalPath());
            return c.isEmpty() ? p : c;
        } catch (Exception e) {
            return p;
        } catch (Throwable t) {
            return p;
        }
    }

    /** 纯路径规则：不依赖任何 Android API，桌面单测可直接覆盖 */
    private static boolean hitRule(String p) {
        // Windows 来源的路径大小写不定；存储挂载点按不区分大小写处理（FAT32 本来也不区分）
        String lp = p.toLowerCase(java.util.Locale.ROOT);
        if ("/".equals(lp)) return true;
        if ("/sdcard".equals(lp) || "/mnt/sdcard".equals(lp)) return true;
        if ("/storage".equals(lp) || "/mnt".equals(lp)) return true;
        String[] sys = {"/system", "/proc", "/sys", "/dev", "/vendor", "/data", "/apex",
                "/storage/emulated", "/mnt/media_rw", "/mnt/user", "/mnt/runtime"};
        for (int i = 0; i < sys.length; i++) {
            // 系统挂载点本身拒绝，其子目录放行：/data/local/tmp 之类是有真实用途的
            if (sys[i].equals(lp)) return true;
        }
        // /storage 的下一层一律是卷根：/storage/emulated/<用户> 与 /storage/XXXX-XXXX
        if (depth(lp, "/storage") == 1) return true;
        if (isEmulatedUser(lp)) return true;
        // 可移动卷的另一套挂载形式
        if (depth(lp, "/mnt/media_rw") == 1) return true;
        if (depth(lp, "/mnt/user") == 1) return true;
        return false;
    }

    /** base 之下还有几层；不在 base 之下返回 -1。depth("/storage","/storage") = 0 */
    private static int depth(String p, String base) {
        if (p.equals(base)) return 0;
        if (!p.startsWith(base + "/")) return -1;
        String rest = p.substring(base.length() + 1);
        int n = 1;
        for (int i = 0; i < rest.length(); i++) {
            if (rest.charAt(i) == '/') n++;
        }
        return n;
    }

    /** /storage/emulated/<数字> 是主存储根；再深一层（DCIM、Download）才放行 */
    private static boolean isEmulatedUser(String lp) {
        String prefix = "/storage/emulated/";
        if (!lp.startsWith(prefix)) return false;
        String rest = lp.substring(prefix.length());
        if (rest.isEmpty()) return false;
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    /** 与调用方注入的真实卷根比对（canonical 形式也算一遍，软链卷根才拦得住） */
    private static boolean hitRoots(String p, String c, java.util.List<String> extraRoots) {
        if (extraRoots == null || extraRoots.isEmpty()) return false;
        for (int i = 0; i < extraRoots.size(); i++) {
            String r = extraRoots.get(i);
            if (r == null) continue;
            String rn = normPath(r);
            if (rn.isEmpty()) continue;
            if (rn.equals(p) || rn.equals(c)) return true;
            if (canonical(rn).equals(p)) return true;
        }
        return false;
    }

    /** 清空目录内容，保留目录本身 */
    private static Result clean(File dir, TreeOps.LinkProbe probe) {
        File[] kids = dir.listFiles();
        if (kids == null) return new Result(0, Result.E_LIST_FAIL);
        int ok = 0, bad = 0;
        for (int i = 0; i < kids.length; i++) {
            if (isVaultDir(kids[i])) continue;   // v2.18.0：隐私空间绝不清
            if (TreeOps.delete(kids[i], probe)) ok++;
            else bad++;
        }
        // 部分失败要说清：报「成功」但删不掉的东西还在，下次来还在那儿堆着
        return bad == 0 ? new Result(ok, Result.E_NONE)
                : new Result(ok, Result.E_CLEAN_PARTIAL, bad);
    }

    /** 删除超过 N 天没修改的文件；空目录不动（可能是用户刻意留的结构） */
    private static Result expire(File dir, long now, int days, TreeOps.LinkProbe probe) {
        if (days <= 0) return new Result(0, Result.E_BAD_DAYS);
        long cut = now - days * 86400000L;
        int[] cnt = new int[2];      // [0]=删掉，[1]=删不掉
        walk(dir, cut, probe, cnt, 0);
        return cnt[1] == 0 ? new Result(cnt[0], Result.E_NONE)
                : new Result(cnt[0], Result.E_EXPIRE_PARTIAL, cnt[1]);
    }

    private static void walk(File dir, long cut, TreeOps.LinkProbe probe, int[] cnt, int depth) {
        if (depth > MAX_DEPTH) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (int i = 0; i < kids.length; i++) {
            File k = kids[i];
            // 先判链接再判目录：isDirectory() 会跟随链接，跟进就会删到链接目标里去
            if (probe.isLink(k)) continue;
            if (isVaultDir(k)) continue;          // v2.18.0：隐私空间绝不清
            if (k.isDirectory()) {
                walk(k, cut, probe, cnt, depth + 1);
                continue;
            }
            if (k.lastModified() >= cut) continue;
            if (k.delete()) cnt[0]++;
            else cnt[1]++;
        }
    }

    /**
     * 隐私空间目录（F-22）：定时清理 / 过期 / 压缩都必须绕开它。
     *
     * <p>R-39 起判据收到 {@link VaultStore#isVaultDir(File)}：搜索与存储分析也要用同一套，
     * 三处各写一份必然走偏（原来这里看「名字 + v.hdr」，搜索那边干脆没判）。</p>
     */
    private static boolean isVaultDir(File f) {
        return VaultStore.isVaultDir(f);
    }

    /** 压成「目录名.zip」，放在目录的父目录里（覆盖同名旧包） */
    private static Result zip(File dir, TreeOps.LinkProbe probe) {
        File parent = dir.getParentFile();
        if (parent == null) return new Result(0, Result.E_NO_PARENT);
        File[] kids = dir.listFiles();
        if (kids == null) return new Result(0, Result.E_LIST_FAIL);
        if (kids.length == 0) return new Result(0, Result.E_EMPTY_DIR);
        File out = new File(parent, dir.getName() + ".zip");
        // 隐私空间不进压缩包：包可能被随手分享出去，而用户要的恰恰是它不出去
        java.util.List<File> pack = new java.util.ArrayList<File>();
        for (int i = 0; i < kids.length; i++) {
            if (!isVaultDir(kids[i])) pack.add(kids[i]);
        }
        if (pack.isEmpty()) return new Result(0, Result.E_VAULT_ONLY);
        boolean ok = ZipUtil.createZip(out, pack, probe);
        return ok ? new Result(pack.size(), Result.E_NONE)
                : new Result(0, Result.E_ZIP_FAIL, out.getPath());
    }
}
