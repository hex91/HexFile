package com.hex91.file;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.99.2（R-06）：找出上次失败留在目录里的临时/备份文件。
 *
 * <p><b>为什么需要它</b>：压缩包写回失败时，{@link AtomicReplace} 会保留
 * {@code 名字.bakrepl}（原内容）与临时文件；解压/复制中断时留下 {@code 名字.part}。
 * 这些文件<b>必须</b>保留（很可能是唯一完好的副本），但用户在自己的文件夹里看到
 * 三个陌生文件，既不知道是什么、也不敢删 —— 删除入口得有人给。</p>
 *
 * <p>规则：只认三种后缀，递归不跟进软链接、深度上限与 {@link TreeOps} 一致；
 * 扫描本身绝不改动任何文件（{@link #delete} 只在用户明确点删除时调用）。</p>
 */
public final class LeftoverScan {

    /** 备份（替换原文件前挪开的原内容） */
    public static final String BAK = AtomicReplace.BAK_SUFFIX;
    /** 旧版 ApkEditSession 用的备份后缀，v1.99.1 之前留下的 */
    public static final String BAK_LEGACY = ".replacebak";
    /** 复制/下载中断留下的半成品 */
    public static final String PART = ".part";

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
        if (f == null) return false;
        String n = f.getName();
        return n.endsWith(BAK) || n.endsWith(BAK_LEGACY) || n.endsWith(PART);
    }

    /** 扫描一个目录（递归，不跟进软链接） */
    public static List<Hit> scan(File dir) {
        List<Hit> out = new ArrayList<Hit>();
        scan(dir, out, 0);
        return out;
    }

    private static void scan(File dir, List<Hit> out, int depth) {
        if (dir == null || depth > TreeOps.MAX_DEPTH || LinkUtil.isLink(dir)) return;
        // 不跟进链接：遗留文件只会出现在用户自己的目录里，跟进链等于去扫别人的盘
        File[] kids = LinkUtil.listNoFollow(dir);
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                scan(k, out, depth + 1);
            } else if (isLeftover(k)) {
                out.add(new Hit(k, k.length(), ownerOf(k.getName())));
            }
        }
    }

    /** {@code a.zip.bakrepl} → {@code a.zip}；认不出后缀就返回原名 */
    static String ownerOf(String name) {
        for (String s : new String[]{BAK, BAK_LEGACY, PART}) {
            if (name.endsWith(s) && name.length() > s.length()) {
                return name.substring(0, name.length() - s.length());
            }
        }
        return name;
    }

    /** 用户明确要求删除时才调用；返回是否删成功 */
    public static boolean delete(File f) {
        if (f == null || !f.exists()) return true;
        if (!isLeftover(f)) return false;      // 双保险：绝不因为调用方传错而删正经文件
        return f.delete();
    }
}
