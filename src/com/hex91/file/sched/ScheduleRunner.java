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
        /** 处理掉的条目数：clean=删掉的顶层项，expire=删掉的文件数，zip=打进包的顶层项 */
        public final int done;
        /** 失败原因；null = 全部成功 */
        public final String error;

        public Result(int done, String error) {
            this.done = done;
            this.error = error;
        }

        public boolean ok() { return error == null; }
    }

    public static Result run(ScheduleItem it) {
        return run(it, System.currentTimeMillis(), TreeOps.SYSTEM);
    }

    public static Result run(ScheduleItem it, long now, TreeOps.LinkProbe probe) {
        if (it == null) return new Result(0, "任务为空");
        if (!it.actionKnown()) return new Result(0, "未知动作: " + it.action);
        String t = it.target == null ? "" : it.target.trim();
        if (t.isEmpty()) return new Result(0, "没有设置目标目录");
        if (isDangerous(t)) return new Result(0, "这个目录不能作为目标: " + t);
        File dir = new File(t);
        if (!dir.exists()) return new Result(0, "目标不存在: " + t);
        if (!dir.isDirectory()) return new Result(0, "目标不是目录: " + t);
        TreeOps.LinkProbe p = probe == null ? TreeOps.SYSTEM : probe;

        if (ScheduleItem.ACTION_CLEAN.equals(it.action)) return clean(dir, p);
        if (ScheduleItem.ACTION_EXPIRE.equals(it.action)) return expire(dir, now, it.param, p);
        return zip(dir, p);
    }

    /**
     * 目标目录是否属于「绝对不能定时清空」的那一类（v2.17.0）。
     *
     * <p>手输路径的定时任务最坏的失败不是「没执行」，而是「执行错了目录」：一次
     * {@code /} 或 {@code /system} 就能把系统清掉，而且用户点保存时并不会有任何感觉。
     * 这些路径没有一个是有理由定时清空的，所以在执行与保存两处都挡一遍。</p>
     */
    public static boolean isDangerous(String path) {
        if (path == null) return true;
        String p = path.trim().replace('\\', '/');
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty() || "/".equals(p)) return true;                  // 根目录
        if ("/storage".equals(p) || "/storage/emulated".equals(p)) return true;
        if ("/sdcard".equals(p)) return true;                            // 存储根（大多是软链）
        String[] sys = {"/system", "/proc", "/sys", "/dev", "/vendor", "/data", "/apex"};
        for (int i = 0; i < sys.length; i++) {
            // 系统挂载点本身拒绝，其子目录放行：/data/local/tmp 之类是有真实用途的
            if (sys[i].equals(p)) return true;
        }
        return false;
    }

    /** 清空目录内容，保留目录本身 */
    private static Result clean(File dir, TreeOps.LinkProbe probe) {
        File[] kids = dir.listFiles();
        if (kids == null) return new Result(0, "无法列出目录（权限不足，或被系统限制）");
        int ok = 0, bad = 0;
        for (int i = 0; i < kids.length; i++) {
            if (isVaultDir(kids[i])) continue;   // v2.18.0：隐私空间绝不清
            if (TreeOps.delete(kids[i], probe)) ok++;
            else bad++;
        }
        // 部分失败要说清：报「成功」但删不掉的东西还在，下次来还在那儿堆着
        return bad == 0 ? new Result(ok, null)
                : new Result(ok, "有 " + bad + " 项删不掉（权限不足或被占用）");
    }

    /** 删除超过 N 天没修改的文件；空目录不动（可能是用户刻意留的结构） */
    private static Result expire(File dir, long now, int days, TreeOps.LinkProbe probe) {
        if (days <= 0) return new Result(0, "保留天数必须大于 0");
        long cut = now - days * 86400000L;
        int[] cnt = new int[2];      // [0]=删掉，[1]=删不掉
        walk(dir, cut, probe, cnt, 0);
        return cnt[1] == 0 ? new Result(cnt[0], null)
                : new Result(cnt[0], "有 " + cnt[1] + " 个文件删不掉（权限不足或被占用）");
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
     * <p>判据是「目录名是 .vault 且里面有 v.hdr」而不是只比名字：用户完全可能自己建一个
     * 叫 .vault 的普通目录，那种该照常清理。只看名字的话，一个要清缓存的任务会莫名其妙
     * 跳过某个目录，而用户没有任何线索知道为什么。</p>
     */
    private static boolean isVaultDir(File f) {
        if (f == null || !f.isDirectory()) return false;
        if (!VaultStore.DIR_NAME.equals(f.getName())) return false;
        return new File(f, "v.hdr").isFile();
    }

    /** 压成「目录名.zip」，放在目录的父目录里（覆盖同名旧包） */
    private static Result zip(File dir, TreeOps.LinkProbe probe) {
        File parent = dir.getParentFile();
        if (parent == null) return new Result(0, "目标目录没有上级，无法决定压缩包放哪");
        File[] kids = dir.listFiles();
        if (kids == null) return new Result(0, "无法列出目录（权限不足，或被系统限制）");
        if (kids.length == 0) return new Result(0, "目录是空的，没有可压缩的内容");
        File out = new File(parent, dir.getName() + ".zip");
        // 隐私空间不进压缩包：包可能被随手分享出去，而用户要的恰恰是它不出去
        java.util.List<File> pack = new java.util.ArrayList<File>();
        for (int i = 0; i < kids.length; i++) {
            if (!isVaultDir(kids[i])) pack.add(kids[i]);
        }
        if (pack.isEmpty()) return new Result(0, "目录里只有隐私空间，没有可压缩的内容");
        boolean ok = ZipUtil.createZip(out, pack, probe);
        return ok ? new Result(pack.size(), null) : new Result(0, "压缩失败: " + out.getPath());
    }
}
