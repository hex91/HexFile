package com.hex91.file.fs;

import com.hex91.file.core.PathCore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * v2.114.0（O-27）：{@link Fs} 的本地实现 —— 直接落在 {@code java.io.File} 上。
 *
 * <p>刻意不做「每个方法都再过一层」的包装：本地路径是主路径，性能与行为必须和
 * 现有 {@code FileOps} / {@code DirReader} 一致，多一层抽象只会让「为什么这里慢」
 * 更难查。{@link Fs} 只负责统一签名与错误码。</p>
 *
 * <p>无状态：{@link #close()} 是空实现，关闭后仍可继续调用。桌面单测与真机跑的是
 * 同一份代码（不含 {@code android.*}）。</p>
 */
public final class LocalFs implements Fs {

    @Override
    public List<Entry> list(String path) throws IOException {
        String p = require(path);
        File dir = new File(p);
        File[] arr = dir.listFiles();
        if (arr == null) {
            // 不存在与无权限在 File 上都是 null：对调用方是同一件事「这个目录列不出来」
            throw FsError.of(FsError.Code.LIST_FAIL, p);
        }

        // 排序键先取一遍：comparator 里直接 isDirectory()/length() 会对每对元素各 stat 一次
        Item[] items = new Item[arr.length];
        for (int i = 0; i < arr.length; i++) {
            File f = arr[i];
            int k = LinkUtil.kind(f);
            // 指向目录的链接按目录排（与 DirReader.fillLink 的 forceDir 一致）
            boolean isDir = f.isDirectory() || k == LinkUtil.DIR;
            items[i] = new Item(f, isDir, isDir ? 0L : f.length(), f.lastModified(),
                    k != LinkUtil.NONE);
        }
        Arrays.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                int r = a.file.getName().compareToIgnoreCase(b.file.getName());
                if (r != 0) return r;
                // 忽略大小写相等的两个名字（README / readme）靠区分大小写定序，避免顺序不稳
                return a.file.getName().compareTo(b.file.getName());
            }
        });

        List<Entry> out = new ArrayList<Entry>(items.length);
        for (Item it : items) {
            out.add(new Entry(it.file.getName(), PathCore.join(p, it.file.getName()),
                    it.dir, it.size, it.mtime, it.link));
        }
        return out;
    }

    /** 排序用的临时载体：避免 Integer 装箱与 comparator 里的重复 stat */
    private static final class Item {
        final File file;
        final boolean dir;
        final long size;
        final long mtime;
        final boolean link;

        Item(File file, boolean dir, long size, long mtime, boolean link) {
            this.file = file;
            this.dir = dir;
            this.size = size;
            this.mtime = mtime;
            this.link = link;
        }
    }

    @Override
    public Entry stat(String path) throws IOException {
        String p = require(path);
        File f = new File(p);
        if (!f.exists()) return null;
        int k = LinkUtil.kind(f);
        boolean dir = f.isDirectory() || k == LinkUtil.DIR;
        return new Entry(f.getName(), p, dir, dir ? 0L : f.length(), f.lastModified(),
                k != LinkUtil.NONE);
    }

    @Override
    public InputStream openRead(String path) throws IOException {
        String p = require(path);
        File f = new File(p);
        if (!f.exists()) throw FsError.of(FsError.Code.SRC_MISSING, p);
        if (f.isDirectory()) throw FsError.of(FsError.Code.READ_FAIL, p);
        try {
            return new FileInputStream(f);
        } catch (IOException e) {
            throw FsError.of(FsError.Code.READ_FAIL, p);
        }
    }

    @Override
    public OutputStream openWrite(String path, boolean append) throws IOException {
        String p = require(path);
        File f = new File(p);
        if (f.isDirectory()) throw FsError.of(FsError.Code.WRITE_FAIL, p);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw FsError.of(FsError.Code.MKDIR_FAIL, parent.getPath());
        }
        try {
            return new FileOutputStream(f, append);
        } catch (IOException e) {
            throw FsError.of(FsError.Code.CREATE_FAIL, p);
        }
    }

    @Override
    public void mkdir(String path) throws IOException {
        String p = require(path);
        File f = new File(p);
        if (f.isDirectory()) return;
        if (f.exists()) throw FsError.of(FsError.Code.MKDIR_FAIL, p);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            // 契约是「仅一级」：父不存在时明确失败，不偷偷 mkdirs
            throw FsError.of(FsError.Code.MKDIR_FAIL, p);
        }
        if (!f.mkdir()) throw FsError.of(FsError.Code.MKDIR_FAIL, p);
    }

    @Override
    public void delete(String path, boolean recursive) throws IOException {
        String p = require(path);
        File f = new File(p);
        if (!f.exists() && LinkUtil.kind(f) == LinkUtil.NONE) {
            throw FsError.of(FsError.Code.SRC_MISSING, p);
        }
        // 链接只删链接本身：跟随进去会把目标内容一起删掉
        if (!f.isDirectory() || LinkUtil.kind(f) != LinkUtil.NONE) {
            if (!f.delete()) throw FsError.of(FsError.Code.DELETE_SRC_FAIL, p);
            return;
        }
        if (!recursive) {
            if (!f.delete()) throw FsError.of(FsError.Code.DELETE_SRC_FAIL, p);
            return;
        }
        deleteTree(p);
    }

    /** 显式栈递归：几万层深目录也不会把调用栈打爆 */
    private void deleteTree(String root) throws IOException {
        Deque<File> stack = new ArrayDeque<File>();
        List<File> dirs = new ArrayList<File>();
        stack.push(new File(root));
        while (!stack.isEmpty()) {
            File dir = stack.pop();
            File[] arr = dir.listFiles();
            if (arr == null) throw FsError.of(FsError.Code.DELETE_SRC_FAIL, dir.getPath());
            for (File f : arr) {
                boolean isLink = LinkUtil.kind(f) != LinkUtil.NONE;
                if (f.isDirectory() && !isLink) {
                    stack.push(f);
                    dirs.add(f);
                } else if (!f.delete()) {
                    throw FsError.of(FsError.Code.DELETE_SRC_FAIL, f.getPath());
                }
            }
        }
        // 后序删除目录本身
        for (int i = dirs.size() - 1; i >= 0; i--) {
            File d = dirs.get(i);
            if (!d.delete()) throw FsError.of(FsError.Code.DELETE_SRC_FAIL, d.getPath());
        }
        File r = new File(root);
        if (!r.delete()) throw FsError.of(FsError.Code.DELETE_SRC_FAIL, r.getPath());
    }

    @Override
    public void rename(String from, String to) throws IOException {
        String a = require(from);
        String b = require(to);
        if (a.equals(b)) return;
        File src = new File(a);
        if (!src.exists() && LinkUtil.kind(src) == LinkUtil.NONE) {
            throw FsError.of(FsError.Code.SRC_MISSING, a);
        }
        File dst = new File(b);
        if (dst.exists()) {
            throw FsError.of(FsError.Code.TARGET_EXISTS, b);
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            throw FsError.of(FsError.Code.RENAME_FAIL, b);
        }
        if (!src.renameTo(dst)) throw FsError.of(FsError.Code.RENAME_FAIL, b);
    }

    /**
     * v2.117.0（O-27 续）：复制；v2.118.0 增加进度与取消。
     *
     * <p>三处与「朴素实现」不同，都是踩过的坑：</p>
     * <ul>
     *   <li><b>同路径直接返回</b>：{@code FileOutputStream} 先截断再读，源等于目标时
     *       会把文件清空 —— 这不是「复制失败」，是数据丢失且没有任何异常。</li>
     *   <li><b>目标在源子树内直接失败</b>：{@code /a → /a/b} 会一边遍历一边新建目录，
     *       轻则重复复制，重则无限增长直到磁盘满。</li>
     *   <li><b>量体积与复制分两趟</b>：没有分母的进度条只能显示「已复制 3.2 GB」，
     *       用户无法判断还要多久。量体积不读内容，只 lstat，代价远小于复制本身。</li>
     * </ul>
     *
     * <p>取消用 {@link Stop}（运行期异常，单例、不填栈）向上穿，在边界转成
     * {@link CopyResult#cancelled}，不让「用户主动停止」变成界面上的一条报错。</p>
     */
    @Override
    public CopyResult copy(String from, String to, Progress p) throws IOException {
        String a = require(from);
        String b = require(to);
        if (a.equals(b)) return new CopyResult(false, 0L, 0L, 0);
        if (b.startsWith(a + "/")) throw FsError.of(FsError.Code.SELF_NEST, b);

        final File src = new File(a);
        if (!src.exists() && LinkUtil.kind(src) == LinkUtil.NONE) {
            throw FsError.of(FsError.Code.SRC_MISSING, a);
        }
        File dst = new File(b);
        if (LinkUtil.isLink(src)) {
            // 与 TreeOps 同一策略：链接复刻为链接，不把目标内容拷一份
            if (!TreeOps.SYSTEM.copyLink(src, dst)) {
                throw FsError.of(FsError.Code.LINK_UNSUPPORTED, a);
            }
            return new CopyResult(false, 0L, 0L, 0);
        }

        final Acc acc = new Acc();
        if (!src.isDirectory()) {
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw FsError.of(FsError.Code.MKDIR_FAIL, parent.getPath());
            }
            long total = src.length();
            if (p != null) p.onPlan(total, 1L);
            if (p != null && p.isCancelled()) return new CopyResult(true, 0L, 0L, 0);
            try {
                copyOne(src, dst, p, acc);
                if (p != null && !p.onFile(b, total, acc.bytes, acc.files)) throw Stop.I;
            } catch (Stop s) {
                return new CopyResult(true, acc.bytes, acc.files, acc.skipped);
            }
            return new CopyResult(false, acc.bytes, acc.files, acc.skipped);
        }

        // ---- 第一趟：量体积 ----
        final long[] tot = new long[2];
        Cancel sig = new Cancel() {
            public boolean isCancelled() {
                return p != null && p.isCancelled();
            }
        };
        boolean done = walk(a, TreeOps.MAX_DEPTH, sig, new Visitor() {
            public boolean onDir(Entry dir, int depth) {
                return true;
            }

            public void onFile(Entry f, int depth) {
                if (f.link) return;   // 链接复刻不占数据，不计入总量
                tot[0] += f.size;
                tot[1]++;
            }
        });
        if (!done) return new CopyResult(true, 0L, 0L, 0);
        if (p != null) p.onPlan(tot[0], tot[1]);

        // ---- 第二趟：复制 ----
        Deque<Pair> stack = new ArrayDeque<Pair>();
        stack.push(new Pair(src, dst, 0));
        try {
            while (!stack.isEmpty()) {
                Pair f = stack.pop();
                if (p != null && p.isCancelled()) throw Stop.I;
                if (!f.dst.isDirectory() && !f.dst.mkdirs() && !f.dst.isDirectory()) {
                    throw FsError.of(FsError.Code.MKDIR_FAIL, f.dst.getPath());
                }
                File[] kids = f.src.listFiles();
                if (kids == null) continue;   // 与 TreeOps 一致：读不出就跳过，不中断整趟
                for (File k : kids) {
                    if (p != null && p.isCancelled()) throw Stop.I;
                    File d = new File(f.dst, k.getName());
                    if (LinkUtil.kind(k) != LinkUtil.NONE) {
                        if (!TreeOps.SYSTEM.copyLink(k, d)) acc.skipped++;
                        continue;
                    }
                    if (k.isDirectory()) {
                        if (f.depth + 1 > TreeOps.MAX_DEPTH) {
                            throw FsError.of(FsError.Code.DEEP_PATH, k.getPath());
                        }
                        stack.push(new Pair(k, d, f.depth + 1));
                    } else {
                        copyOne(k, d, p, acc);
                        if (p != null && !p.onFile(d.getPath(), k.length(),
                                acc.bytes, acc.files)) {
                            throw Stop.I;
                        }
                    }
                }
            }
        } catch (Stop s) {
            return new CopyResult(true, acc.bytes, acc.files, acc.skipped);
        }
        return new CopyResult(false, acc.bytes, acc.files, acc.skipped);
    }

    /**
     * 单文件复制（截断覆盖写）。
     *
     * <p>取消检查放在块边界而不是文件边界：一个 4 GB 视频按 64 KB 走 65536 次，
     * 每次判一个 volatile 布尔，代价可以忽略；但只在文件边界判，用户按了取消要等
     * 半分钟才有反应。</p>
     */
    private void copyOne(File src, File dst, Progress p, Acc acc) throws IOException {
        InputStream in;
        try {
            in = new FileInputStream(src);
        } catch (IOException e) {
            throw FsError.of(FsError.Code.READ_FAIL, src.getPath());
        }
        try {
            OutputStream out;
            try {
                out = new FileOutputStream(dst);
            } catch (IOException e) {
                throw FsError.of(FsError.Code.WRITE_FAIL, dst.getPath());
            }
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    acc.bytes += n;
                    if (p != null && p.isCancelled()) throw Stop.I;
                }
            } finally {
                out.close();
            }
        } catch (IOException e) {
            // 读写中途的失败分不出是源还是目标，统一按写入失败报；两者都在诊断串里
            throw e instanceof FsError ? e : FsError.of(FsError.Code.WRITE_FAIL, dst.getPath());
        } finally {
            in.close();
        }
        acc.files++;
    }

    /** 复制累加器：取消时要把已完成量带出来，所以不能只是局部变量 */
    private static final class Acc {
        long bytes;
        long files;
        int skipped;
    }

    /** 待复制目录对：源与目标必须一起入栈，否则恢复不了对应关系 */
    private static final class Pair {
        final File src;
        final File dst;
        final int depth;

        Pair(File src, File dst, int depth) {
            this.src = src;
            this.dst = dst;
            this.depth = depth;
        }
    }

    /**
     * 复制取消信号。
     *
     * <p>用异常而不是返回值：取消可能发生在任意一层（块循环、目录循环、栈循环），
     * 逐层返回 {@code false} 需要每个循环都改签名，漏一处就是「按了取消还在跑」。
     * 单例 + 不填栈，避免高频抛出时不断分配栈帧。</p>
     */
    private static final class Stop extends RuntimeException {
        static final Stop I = new Stop();

        private Stop() {
            super("copy cancelled", null, false, false);
        }
    }


    /**
     * v2.117.0（O-27 续）：移动 = 先改名，跨卷退化为「复制 + 删除源」。
     *
     * <p>{@code renameTo} 返回 false 时无法区分「跨卷」与「其它原因」，但目标是否存在
     * 已经在前面判过，剩下的主要原因就是 EXDEV。退化路径完成后源删不掉时抛
     * {@code SRC_LEFT} 而不是失败 —— 数据已经在目标位置了，报失败会让用户重试，
     * 结果是两份。</p>
     */
    @Override
    public void move(String from, String to) throws IOException {
        String a = require(from);
        String b = require(to);
        if (a.equals(b)) return;
        if (b.startsWith(a + "/")) throw FsError.of(FsError.Code.SELF_NEST, b);

        File src = new File(a);
        if (!src.exists() && LinkUtil.kind(src) == LinkUtil.NONE) {
            throw FsError.of(FsError.Code.SRC_MISSING, a);
        }
        File dst = new File(b);
        if (dst.exists()) throw FsError.of(FsError.Code.TARGET_EXISTS, b);
        if (src.renameTo(dst)) return;

        copy(a, b);
        boolean removed = true;
        try {
            delete(a, true);
        } catch (IOException e) {
            removed = false;   // 源没删掉：下面按「是否真的还在」判定，不重复报错
        }
        if (!removed && (src.exists() || LinkUtil.kind(src) != LinkUtil.NONE)) {
            throw FsError.of(FsError.Code.SRC_LEFT, a);
        }
    }

    /**
     * v2.116.0（O-27 续）：{@code File} 快路径遍历。
     *
     * <p>与 {@link FsWalk} 的差别只有「省掉什么」：不走 {@link #list}，因此不做排序、
     * 也不为每个子项拼相对路径再丢给 {@code new File(...)} 解析回来。几十万文件的磁盘
     * 分析里，这两件事都不改变结果，但会让每条记录多一次字符串分配。</p>
     *
     * <p>链接判定用 {@link LinkUtil#kind}（{@code lstat}），不用 {@code isDirectory}：
     * 后者会跟随链接，指向目录的链接会被当成可进入的目录，进而统计翻倍、遇到环爆栈。</p>
     */
    @Override
    public boolean walk(String root, int maxDepth, Cancel cancel, Visitor v) throws IOException {
        if (v == null) throw new IllegalArgumentException("visitor == null");
        String base = require(root);
        File r = new File(base);
        if (!r.exists() && LinkUtil.kind(r) == LinkUtil.NONE) {
            throw FsError.of(FsError.Code.SRC_MISSING, base);
        }
        if (!r.isDirectory()) throw FsError.of(FsError.Code.NOT_DIR, base);

        int limit = maxDepth < 1 ? 1 : maxDepth;
        Deque<Frame> stack = new ArrayDeque<Frame>();
        stack.push(new Frame(r, base, 0));

        while (!stack.isEmpty()) {
            Frame f = stack.pop();
            if (cancel != null && cancel.isCancelled()) return false;

            File[] arr = f.file.listFiles();
            if (arr == null) {
                // 根读不出来是调用方的问题；子目录按权限不足跳过，继续扫其余的
                if (f.depth == 0) throw FsError.of(FsError.Code.LIST_FAIL, f.path);
                v.onError(dirEntry(f.file, f.path), FsError.of(FsError.Code.LIST_FAIL, f.path));
                continue;
            }

            for (File c : arr) {
                if (cancel != null && cancel.isCancelled()) return false;
                String p = PathCore.join(f.path, c.getName());
                int k = LinkUtil.kind(c);
                if (k != LinkUtil.NONE) {
                    // 链接：目标大小归目标所有，这里报 0；link=true 让调用方自己决定要不要
                    boolean dir = k == LinkUtil.DIR;
                    v.onFile(new Entry(c.getName(), p, dir, dir ? 0L : c.length(),
                            c.lastModified(), true), f.depth + 1);
                    continue;
                }
                if (c.isDirectory()) {
                    Entry e = dirEntry(c, p);
                    int d = f.depth + 1;
                    if (v.onDir(e, d) && d < limit) stack.push(new Frame(c, p, d));
                } else {
                    v.onFile(new Entry(c.getName(), p, false, c.length(), c.lastModified(), false),
                            f.depth + 1);
                }
            }
        }
        return true;
    }

    private static Entry dirEntry(File f, String path) {
        return new Entry(f.getName(), path, true, 0L, f.lastModified(), false);
    }

    /** 待处理目录：{@code path} 单独存，避免每层反复 {@code getAbsolutePath()} 与规范化 */
    private static final class Frame {
        final File file;
        final String path;
        final int depth;

        Frame(File file, String path, int depth) {
            this.file = file;
            this.path = path;
            this.depth = depth;
        }
    }

    /** 统一入口检查：null / 空串是调用方的编程错误，这里给稳定错误码 */
    private static String require(String path) throws FsError {
        if (path == null || path.trim().isEmpty()) {
            throw FsError.of(FsError.Code.BAD_PATH);
        }
        return PathCore.normalize(path);
    }
}
