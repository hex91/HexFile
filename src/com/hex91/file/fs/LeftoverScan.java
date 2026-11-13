package com.hex91.file.fs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * v1.99.2（R-06）：找出上次失败留在目录里的临时/备份文件。
 *
 * <p><b>为什么需要它</b>：压缩包写回失败时，{@link com.hex91.file.core.AtomicReplace}
 * 会保留 {@code 名字.bakrepl}（原内容）与临时文件；解压/复制中断时留下 {@code 名字.part}。
 * 这些文件<b>必须</b>保留（很可能是唯一完好的副本），但用户在自己的文件夹里看到
 * 三个陌生文件，既不知道是什么、也不敢删 —— 删除入口得有人给。</p>
 *
 * <p>规则：只认 {@link TempFiles} 定义的那几类后缀，递归不跟进软链接、深度上限与
 * {@link TreeOps} 一致；扫描本身绝不改动任何文件（{@link #delete} 只在用户明确点删除时调用）。</p>
 *
 * <p><b>v2.4.0（R-08 / R-11）</b>：判定交给 {@link TempFiles}（压包写回留下的
 * {@code .reb<ts>.tmp} 以前根本扫不出来），并且扫描与删除都会排除「本进程正在写」
 * 的临时路径 —— 否则用户在大文件拷贝进行中点一下清理，删掉的就是任务正在写的
 * {@code .part}，拷完 rename 失败，几十秒到几分钟全白费。</p>
 */
public final class LeftoverScan {

    /** 备份（替换原文件前挪开的原内容） */
    public static final String BAK = TempFiles.BAK;
    /** 旧版 ApkEditSession 用的备份后缀，v1.99.1 之前留下的 */
    public static final String BAK_LEGACY = TempFiles.BAK_LEGACY;
    /** 复制/下载中断留下的半成品 */
    public static final String PART = TempFiles.PART;

    private LeftoverScan() {}

    /** 一个遗留文件（带大小，供列表展示） */
    public static final class Hit {
        public final File file;
        public final long size;
        /** 它原本属于哪个文件（去掉后缀）；仅用于展示「这是 X 的备份」 */
        public final String owner;

        Hit(File file, long size, String owner) {
            this.file = file;
            this.size = size;
            this.owner = owner;
        }
    }

    /** 是否是遗留文件（只看名字，不碰磁盘） */
    public static boolean isLeftover(File f) {
        return TempFiles.isLeftover(f);
    }

    /** 扫描一个目录（递归，不跟进软链接；不排除活动写入） */
    public static List<Hit> scan(File dir) {
        return scan(dir, null);
    }

    /**
     * 扫描一个目录，排除 {@code active}（{@link TempFiles#snapshot()}）里的路径。
     *
     * <p>扫描期间集合可能变化（新任务开始写），所以这里用快照比对而不是实时查询；
     * 删除入口还会再查一次实时状态，两道闸合起来才挡得住「扫描后任务才启动」的窗口。</p>
     */
    public static List<Hit> scan(File dir, Set<String> active) {
        List<Hit> out = new ArrayList<Hit>();
        scan(dir, out, 0, active);
        return out;
    }

    private static void scan(File dir, List<Hit> out, int depth, Set<String> active) {
        if (dir == null || depth > TreeOps.MAX_DEPTH || LinkUtil.isLink(dir)) return;
        // 回收站里的 .part 是用户主动丢弃的内容，列出来只是噪音
        if (depth == 0 && isTrashDir(dir)) return;
        // 不跟进链接：遗留文件只会出现在用户自己的目录里，跟进链等于去扫别人的盘
        File[] kids = LinkUtil.listNoFollow(dir);
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                // HLS 的 .xxx.parts 是断点续传的清单与分片，属于功能数据：
                // 里面几百个 .part 分片一旦被列出来，用户一删就等于放弃续传
                if (k.getName().endsWith(".parts")) continue;
                scan(k, out, depth + 1, active);
            } else if (isLeftover(k)) {
                if (isWriting(k, active)) continue;   // 正在写：不是遗留，是活的任务
                out.add(new Hit(k, k.length(), ownerOf(k.getName())));
            }
        }
    }

    private static boolean isWriting(File f, Set<String> active) {
        if (TempFiles.isActive(f)) return true;
        return active != null && active.contains(TempFiles.key(f));
    }

    private static boolean isTrashDir(File dir) {
        try {
            File trash = Trash.dir();
            return trash.equals(dir) || trash.getCanonicalPath().equals(dir.getCanonicalPath());
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code a.zip.bakrepl} → {@code a.zip}；认不出后缀就返回原名 */
    static String ownerOf(String name) {
        return TempFiles.ownerOf(name);
    }

    /**
     * 用户明确要求删除时才调用；返回是否删成功。
     *
     * <p>活的任务正在写的临时文件返回 false（调用方提示「任务进行中」）——
     * 返回 false 而不是抛异常，因为界面上只有一句 toast 的位置。</p>
     */
    public static boolean delete(File f) {
        if (f == null || !f.exists()) return true;
        if (!isLeftover(f)) return false;      // 双保险：绝不因为调用方传错而删正经文件
        if (TempFiles.isActive(f)) return false;
        return f.delete();
    }
}
