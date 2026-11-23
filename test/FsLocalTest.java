import com.hex91.file.core.PathCore;
import com.hex91.file.fs.Fs;
import com.hex91.file.fs.FsError;
import com.hex91.file.fs.LocalFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * O-27：{@link LocalFs} 与 {@link PathCore} 的行为护栏。
 *
 * <p>为什么这些用例值得写：{@code Fs} 的存在意义是「同一份调用代码在本地与远程
 * 表现一致」。一旦本地实现把「目标已存在」做成静默覆盖，或者把 {@code ..} 折错，
 * 上层切到远程视图时就会出现「本地正常、远程删错目录」—— 这种问题在真机上
 * 往往表现为「数据没了但没有报错」，最难回溯。</p>
 *
 * <p>与 {@link java.io.File} 的结论逐项对齐：list 项数、stat 大小、目录判定
 * 都直接拿 {@code File} 的返回值当对照，不另立一套期望值。</p>
 */
public class FsLocalTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eq(Object a, Object b, String msg) {
        boolean v = a == null ? b == null : a.equals(b);
        ok(v, msg + (v ? "" : "  (got=" + a + " want=" + b + ")"));
    }

    public static void main(String[] args) throws Exception {
        pathCore();
        File root = mkTempDir();
        try {
            localOps(root);
        } finally {
            deleteTree(root);
        }
        File walkRoot = mkTempDir();
        try {
            walkOps(walkRoot);
        } finally {
            deleteTree(walkRoot);
        }
        File copyRoot = mkTempDir();
        try {
            copyMoveOps(copyRoot);
        } finally {
            deleteTree(copyRoot);
        }
        File progRoot = mkTempDir();
        try {
            copyProgressOps(progRoot);
        } finally {
            deleteTree(progRoot);
        }
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- PathCore

    static void pathCore() {
        eq(PathCore.normalize(""), "", "normalize: 空串");
        eq(PathCore.normalize(null), "", "normalize: null");
        eq(PathCore.normalize("/"), "/", "normalize: 根");
        eq(PathCore.normalize("//a///b/"), "/a/b", "normalize: 压缩重复分隔符与尾斜杠");
        eq(PathCore.normalize("/a/./b"), "/a/b", "normalize: 折叠 .");
        eq(PathCore.normalize("/a/b/../c"), "/a/c", "normalize: 折叠 ..");
        eq(PathCore.normalize("/.."), "/", "normalize: 绝对路径的 .. 不越过根");
        eq(PathCore.normalize("/a/../../b"), "/b", "normalize: 多个 .. 折叠到根");
        eq(PathCore.normalize("a/../../b"), "../b", "normalize: 相对路径保留前导 ..");
        eq(PathCore.normalize("C:\\tmp\\a\\..\\b"), "C:/tmp/b", "normalize: Windows 反斜杠与盘符");

        eq(PathCore.join("", "a"), "a", "join: 空父");
        eq(PathCore.join("/a", "b"), "/a/b", "join: 普通");
        eq(PathCore.join("/a/", "b"), "/a/b", "join: 父已带斜杠不重复");
        eq(PathCore.baseName("/a/b.txt"), "b.txt", "baseName: 普通");
        eq(PathCore.baseName("/a/"), "a", "baseName: 尾斜杠");
        eq(PathCore.baseName("/"), "", "baseName: 根");
        eq(PathCore.parentOf("/a/b.txt"), "/a", "parentOf: 普通");
        eq(PathCore.parentOf("/a"), "/", "parentOf: 一级绝对路径");
        eq(PathCore.parentOf("a"), "", "parentOf: 一级相对路径");
        eq(PathCore.parentOf("C:/a"), "C:/", "parentOf: 盘符根");
        ok(PathCore.isRoot("/"), "isRoot: /");
        ok(PathCore.isRoot("C:/"), "isRoot: C:/");
        ok(!PathCore.isRoot("/a"), "isRoot: /a 不是根");
    }

    // ---------------------------------------------------------------- LocalFs

    static void localOps(File root) throws Exception {
        LocalFs fs = new LocalFs();
        final String base = PathCore.normalize(root.getAbsolutePath());

        // ---- 造树：目录 + 文件混合，名字故意让大小写交叉 ----
        mkdirs(new File(root, "Beta"));
        mkdirs(new File(root, "alpha"));
        write(new File(root, "zeta.txt"), new byte[10]);
        write(new File(root, "Alpha.txt"), new byte[20]);

        List<Fs.Entry> list = fs.list(base);
        eq(list.size(), 4, "list: 项数与 File.listFiles 一致");
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = list.get(i).name;
        eq(names[0], "alpha", "list: 目录优先且忽略大小写（第 1 项 alpha 目录）");
        eq(names[1], "Beta", "list: 第 2 项 Beta 目录");
        eq(names[2], "Alpha.txt", "list: 文件按忽略大小写排序（Alpha.txt 在 zeta 前）");
        eq(names[3], "zeta.txt", "list: 最后一项 zeta.txt");
        ok(list.get(0).dir, "list: alpha 标记为目录");
        ok(!list.get(2).dir, "list: Alpha.txt 标记为文件");
        eq(list.get(2).size, 20L, "list: 文件大小与 File.length 一致");
        eq(list.get(0).path, PathCore.join(base, "alpha"), "list: path 由父路径拼出");
        ok(list.get(0).mtime > 0L, "list: mtime 为 epoch 毫秒");

        // ---- 读 ----
        eq(fs.stat(base).dir, true, "stat: 目录");
        eq(fs.stat(PathCore.join(base, "zeta.txt")).size, 10L, "stat: 文件大小");
        ok(fs.stat(PathCore.join(base, "nope")) == null, "stat: 不存在返回 null");

        InputStream in = fs.openRead(PathCore.join(base, "zeta.txt"));
        try {
            eq(in.read(), 0, "openRead: 能读到内容");
        } finally {
            in.close();
        }

        // ---- 写：覆盖 / 追加 / 自动补父目录 ----
        String sub = PathCore.join(base, "deep/one");
        OutputStream out = fs.openWrite(sub, false);
        out.write(new byte[]{1, 2, 3});
        out.close();
        eq(new File(root, "deep/one").length(), 3L, "openWrite: 自动补齐父目录");
        out = fs.openWrite(sub, false);
        out.write(new byte[]{9});
        out.close();
        eq(new File(root, "deep/one").length(), 1L, "openWrite(false): 截断覆盖");
        out = fs.openWrite(sub, true);
        out.write(new byte[]{8, 7});
        out.close();
        eq(new File(root, "deep/one").length(), 3L, "openWrite(true): 追加");

        // ---- mkdir ----
        fs.mkdir(PathCore.join(base, "newdir"));
        ok(new File(root, "newdir").isDirectory(), "mkdir: 一级目录");
        fs.mkdir(PathCore.join(base, "newdir"));
        ok(true, "mkdir: 已存在是幂等成功（未抛异常）");
        expect(FsError.Code.BAD_PATH, new Body() {
            public void run() throws Exception { new LocalFs().mkdir("  "); }
        }, "mkdir: 空路径报 BAD_PATH");
        expect(FsError.Code.MKDIR_FAIL, new Body() {
            public void run() throws Exception { new LocalFs().mkdir(PathCore.join(base, "no-such-parent/x")); }
        }, "mkdir: 父目录不存在报 MKDIR_FAIL（不偷偷 mkdirs）");
        write(new File(root, "occupied"), new byte[1]);
        expect(FsError.Code.MKDIR_FAIL, new Body() {
            public void run() throws Exception { new LocalFs().mkdir(PathCore.join(base, "occupied")); }
        }, "mkdir: 同名文件已存在报 MKDIR_FAIL");

        // ---- delete ----
        File gone = write(new File(root, "gone.txt"), new byte[1]);
        fs.delete(PathCore.join(base, "gone.txt"), false);
        ok(!gone.exists(), "delete: 文件");
        File empty = new File(root, "empty");
        mkdirs(empty);
        fs.delete(PathCore.join(base, "empty"), false);
        ok(!empty.exists(), "delete: 空目录非递归可删");
        File nonEmpty = new File(root, "nonempty");
        write(new File(nonEmpty, "a.txt"), new byte[1]);
        expect(FsError.Code.DELETE_SRC_FAIL, new Body() {
            public void run() throws Exception { new LocalFs().delete(PathCore.join(base, "nonempty"), false); }
        }, "delete: 非空目录非递归必须失败");
        fs.delete(PathCore.join(base, "nonempty"), true);
        ok(!nonEmpty.exists(), "delete: 非空目录递归删除");
        expect(FsError.Code.SRC_MISSING, new Body() {
            public void run() throws Exception { new LocalFs().delete(PathCore.join(base, "no-such-entry"), true); }
        }, "delete: 不存在报 SRC_MISSING");

        // ---- rename ----
        write(new File(root, "r1.txt"), new byte[2]);
        fs.rename(PathCore.join(base, "r1.txt"), PathCore.join(base, "r2.txt"));
        ok(!new File(root, "r1.txt").exists() && new File(root, "r2.txt").exists(),
                "rename: 同目录改名");
        fs.rename(PathCore.join(base, "r2.txt"), PathCore.join(base, "deep/r2.txt"));
        ok(new File(root, "deep/r2.txt").exists(), "rename: 跨目录移动");
        write(new File(root, "r3.txt"), new byte[1]);
        write(new File(root, "r4.txt"), new byte[1]);
        expect(FsError.Code.TARGET_EXISTS, new Body() {
            public void run() throws Exception {
                new LocalFs().rename(PathCore.join(base, "r3.txt"), PathCore.join(base, "r4.txt"));
            }
        }, "rename: 目标存在不覆盖（TARGET_EXISTS）");
        expect(FsError.Code.SRC_MISSING, new Body() {
            public void run() throws Exception {
                new LocalFs().rename(PathCore.join(base, "no-such-src"), PathCore.join(base, "dst"));
            }
        }, "rename: 源不存在报 SRC_MISSING");

        // ---- list 失败 ----
        expect(FsError.Code.LIST_FAIL, new Body() {
            public void run() throws Exception { new LocalFs().list(PathCore.join(base, "no-such-dir")); }
        }, "list: 不存在报 LIST_FAIL（不是空列表）");

        // ---- close 后仍可用（本地实现无状态，契约如此） ----
        fs.close();
        ok(fs.stat(base) != null, "close: 本地实现 close 后仍可调用");
    }

    // ---------------------------------------------------------------- walk

    /**
     * v2.116.0（O-27 续）：递归遍历的护栏。
     *
     * <p>重点不是「能遍历到文件」，而是四条约束：剪枝真的不进子树、深度上限真的生效、
     * 取消真的能停、根路径错误时不会伪装成空目录。这四条里任何一条失效，上层看到的
     * 都是「扫描结果偏少」而不是报错 —— 用户不会发现，只会在某天觉得「大小对不上」。</p>
     */
    static void walkOps(File root) throws Exception {
        final LocalFs fs = new LocalFs();
        final String base = PathCore.normalize(root.getAbsolutePath());

        mkdirs(new File(root, "sub/deep"));
        mkdirs(new File(root, "empty"));
        write(new File(root, "f1.txt"), new byte[3]);
        write(new File(root, "sub/f2.txt"), new byte[5]);
        write(new File(root, "sub/deep/f3.txt"), new byte[7]);

        // ---- 全量：文件 3 个、目录 3 个、深度递增 ----
        Collector all = new Collector();
        ok(fs.walk(base, all), "walk: 全量走完返回 true");
        eq(all.files.toString(), "[f1.txt, f2.txt, f3.txt]", "walk: 文件集合");
        eq(all.dirs.toString(), "[deep, empty, sub]", "walk: 目录集合（不含根自身）");
        eq(all.pathOf("f1.txt"), PathCore.join(base, "f1.txt"), "walk: 子项 path 是完整路径");
        eq(all.sizeOf("f2.txt"), 5L, "walk: Entry.size 与 File.length 一致");
        eq(all.depthOf("f1.txt"), 1, "walk: 根的直接子项深度为 1");
        eq(all.depthOf("f2.txt"), 2, "walk: 二级目录深度为 2");
        eq(all.depthOf("f3.txt"), 3, "walk: 三级目录深度为 3");

        // ---- 剪枝：不进入 sub，子树里的文件一个都不该出现 ----
        Collector pruned = new Collector() {
            @Override
            public boolean onDir(Fs.Entry e, int depth) {
                return !"sub".equals(e.name);      // 返回 false = 剪掉
            }
        };
        fs.walk(base, pruned);
        eq(pruned.files.toString(), "[f1.txt]", "walk: 剪枝后不进子树");
        ok(!pruned.dirs.contains("deep"), "walk: 剪枝后更深层目录不再回调");

        // ---- 深度上限 ----
        Collector shallow = new Collector();
        fs.walk(base, 1, null, shallow);
        eq(shallow.files.toString(), "[f1.txt]", "walk: maxDepth=1 只取直接子项");
        eq(shallow.dirs.toString(), "[empty, sub]", "walk: maxDepth=1 仍回调一级目录（但不进入）");

        // ---- 取消：第一个文件后停，返回 false ----
        Collector cancelled = new Collector() {
            @Override
            public void onFile(Fs.Entry e, int depth) {
                super.onFile(e, depth);
                cancel = true;
            }
        };
        eq(fs.walk(base, 64, cancelled, cancelled), false, "walk: 被取消时返回 false");
        eq(cancelled.files.size(), 1, "walk: 取消后不再回调后续条目");

        // ---- 根路径错误：必须抛，不能当空目录 ----
        expect(FsError.Code.SRC_MISSING, new Body() {
            public void run() throws Exception {
                new LocalFs().walk(PathCore.join(PathCore.normalize(
                        new File(".").getAbsolutePath()), "no-such-dir"), new Collector());
            }
        }, "walk: 根不存在报 SRC_MISSING");
        expect(FsError.Code.NOT_DIR, new Body() {
            public void run() throws Exception {
                new LocalFs().walk(new File(new File("."), "build.sh").getAbsolutePath(),
                        new Collector());
            }
        }, "walk: 根是文件报 NOT_DIR");
        expect(FsError.Code.BAD_PATH, new Body() {
            public void run() throws Exception { new LocalFs().walk("", new Collector()); }
        }, "walk: 空路径报 BAD_PATH");
    }

    /** 收集式访问者：断言只看集合与深度，不依赖遍历顺序（契约里顺序是不保证的） */
    static class Collector implements Fs.Visitor, Fs.Cancel {
        final java.util.Set<String> files = new java.util.TreeSet<String>();
        final java.util.Set<String> dirs = new java.util.TreeSet<String>();
        final java.util.Map<String, String> path = new java.util.HashMap<String, String>();
        final java.util.Map<String, Long> size = new java.util.HashMap<String, Long>();
        final java.util.Map<String, Integer> depth = new java.util.HashMap<String, Integer>();
        boolean cancel;

        public boolean onDir(Fs.Entry e, int depth) {
            dirs.add(e.name);
            this.depth.put(e.name, depth);
            return !cancel;
        }

        public void onFile(Fs.Entry e, int depth) {
            files.add(e.name);
            path.put(e.name, e.path);
            size.put(e.name, e.size);
            this.depth.put(e.name, depth);
        }

        String pathOf(String name) { return path.get(name); }

        long sizeOf(String name) {
            Long v = size.get(name);
            return v == null ? -1L : v.longValue();
        }

        int depthOf(String name) {
            Integer v = depth.get(name);
            return v == null ? -1 : v.intValue();
        }

        public boolean isCancelled() { return cancel; }
    }

    // ---------------------------------------------------------------- copy / move

    /**
     * v2.117.0（O-27 续）：复制与移动。
     *
     * <p>这几条不是「功能验证」而是「数据完整性验证」：复制覆盖、目录合并、同路径 no-op、
     * 自嵌套拒绝 —— 四条里任意一条写错，表现都是「文件没了但不报错」，
     * 用户要等到下次打开才发现。</p>
     */
    static void copyMoveOps(File root) throws Exception {
        final LocalFs fs = new LocalFs();
        final String base = PathCore.normalize(root.getAbsolutePath());

        write(new File(root, "src/a.txt"), new byte[]{1, 2, 3});
        write(new File(root, "src/sub/b.txt"), new byte[]{1, 2, 3, 4, 5});
        write(new File(root, "dst/keep.txt"), new byte[]{7});

        // ---- 目录合并复制：目标原有文件留下，新文件到位 ----
        fs.copy(p(base, "src"), p(base, "dst"));
        ok(new File(root, "src/a.txt").exists(), "copy: 目录复制后源仍在");
        eq(new File(root, "dst/a.txt").length(), 3L, "copy: 目录复制，一级文件到位");
        eq(new File(root, "dst/sub/b.txt").length(), 5L, "copy: 子目录结构保留");
        eq(new File(root, "dst/keep.txt").length(), 1L, "copy: 目标原有文件保留（合并，不是替换）");

        // ---- 文件覆盖：目标比源长时必须被截断 ----
        write(new File(root, "src/a.txt"), new byte[]{1, 2, 3, 4, 5, 6});
        write(new File(root, "dst/a.txt"), new byte[]{9, 9});
        fs.copy(p(base, "src/a.txt"), p(base, "dst/a.txt"));
        eq(new File(root, "dst/a.txt").length(), 6L, "copy: 覆盖写按源大小截断");

        // ---- 同路径：必须是 no-op，不能把源清空 ----
        fs.copy(p(base, "dst/a.txt"), p(base, "dst/a.txt"));
        eq(new File(root, "dst/a.txt").length(), 6L, "copy: 同路径 no-op（不截断源）");

        // ---- 自嵌套：/src → /src/inner 必须拒绝 ----
        expect(FsError.Code.SELF_NEST, new Body() {
            public void run() throws Exception {
                new LocalFs().copy(p(base, "src"), p(base, "src/inner"));
            }
        }, "copy: 目标在源子树内报 SELF_NEST");
        expect(FsError.Code.SRC_MISSING, new Body() {
            public void run() throws Exception {
                new LocalFs().copy(p(base, "nope"), p(base, "x"));
            }
        }, "copy: 源不存在报 SRC_MISSING");

        // ---- move：同卷改名 ----
        fs.move(p(base, "dst/a.txt"), p(base, "moved.txt"));
        ok(!new File(root, "dst/a.txt").exists(), "move: 源已移除");
        eq(new File(root, "moved.txt").length(), 6L, "move: 内容随目标走");

        // ---- move：目标存在不覆盖（与 rename 一致） ----
        write(new File(root, "dup1.txt"), new byte[]{1});
        write(new File(root, "dup2.txt"), new byte[]{2});
        expect(FsError.Code.TARGET_EXISTS, new Body() {
            public void run() throws Exception {
                new LocalFs().move(p(base, "dup1.txt"), p(base, "dup2.txt"));
            }
        }, "move: 目标存在报 TARGET_EXISTS");

        // ---- move：整棵目录 ----
        fs.move(p(base, "src/sub"), p(base, "movedSub"));
        ok(!new File(root, "src/sub").exists(), "move: 目录源已移除");
        eq(new File(root, "movedSub/b.txt").length(), 5L, "move: 目录内容完整");

        expect(FsError.Code.SRC_MISSING, new Body() {
            public void run() throws Exception {
                new LocalFs().move(p(base, "nope"), p(base, "x"));
            }
        }, "move: 源不存在报 SRC_MISSING");
    }

    /**
     * v2.118.0（O-27 续）：带进度与取消的复制。
     *
     * <p>进度相关的 bug 大多不会让复制失败：界面卡在 99%、取消之后目标里多出半份
     * 数据、或者「已复制 3 GB」其实把链接目标的体积也算进去了。所以重点验三件事：
     * 总量口径、回调次数与文件数一一对应、取消之后目标里的旧文件还在不在。</p>
     */
    static void copyProgressOps(File root) throws Exception {
        final LocalFs fs = new LocalFs();
        final String base = PathCore.normalize(root.getAbsolutePath());
        final int big = 100 * 1024;
        final long total = 3L * big + 1L;

        write(new File(root, "p/a.bin"), new byte[big]);
        write(new File(root, "p/d/b.bin"), new byte[2 * big]);
        write(new File(root, "p/d/c.bin"), new byte[1]);

        // ---- 正常完成：总量 / 回调次数 / 单调递增 ----
        final java.util.List<Object> plan = new java.util.ArrayList<Object>();
        final java.util.List<String> names = new java.util.ArrayList<String>();
        final java.util.List<Long> doneB = new java.util.ArrayList<Long>();
        final java.util.List<Long> doneF = new java.util.ArrayList<Long>();

        Fs.CopyResult r = fs.copy(p(base, "p"), p(base, "q"), new Fs.Progress() {
            public void onPlan(long tb, long tf) {
                plan.add(Long.valueOf(tb));
                plan.add(Long.valueOf(tf));
            }

            public boolean onFile(String path, long size, long db, long df) {
                names.add(PathCore.baseName(path));
                doneB.add(Long.valueOf(db));
                doneF.add(Long.valueOf(df));
                return true;
            }
        });

        ok(!r.cancelled, "copy+progress: 正常完成 cancelled=false");
        eq(2, plan.size(), "copy+progress: onPlan 只回调一次（两个数字）");
        eq(Long.valueOf(total), plan.get(0), "copy+progress: 总量 = 全部文件字节和");
        eq(Long.valueOf(3L), plan.get(1), "copy+progress: 文件数 3");
        eq(3, names.size(), "copy+progress: onFile 回调次数 = 文件数");
        eq(Long.valueOf(total), doneB.get(2), "copy+progress: 最后一笔 doneBytes 等于总量");
        eq(Long.valueOf(3L), doneF.get(2), "copy+progress: doneFiles 最终为 3");
        ok(doneB.get(0) < doneB.get(1) && doneB.get(1) < doneB.get(2),
                "copy+progress: doneBytes 单调递增");
        ok(r.bytes == total && r.files == 3L, "copy+progress: 返回值与回调口径一致");
        eq(new File(root, "q/d/b.bin").length(), 2L * big, "copy+progress: 大文件完整写入");

        // ---- 单文件：onPlan(size, 1) ----
        final java.util.List<Object> plan1 = new java.util.ArrayList<Object>();
        final int[] calls1 = new int[1];
        Fs.CopyResult r2 = fs.copy(p(base, "p/a.bin"), p(base, "one.bin"), new Fs.Progress() {
            public void onPlan(long tb, long tf) {
                plan1.add(Long.valueOf(tb));
                plan1.add(Long.valueOf(tf));
            }

            public boolean onFile(String path, long size, long db, long df) {
                calls1[0]++;
                return true;
            }
        });
        eq(Long.valueOf(big), plan1.get(0), "copy+progress: 单文件总量 = 文件大小");
        eq(Long.valueOf(1L), plan1.get(1), "copy+progress: 单文件数 1");
        eq(1, calls1[0], "copy+progress: 单文件只回调一次");
        ok(!r2.cancelled && r2.files == 1L, "copy+progress: 单文件完成计数为 1");

        // ---- onFile 返回 false：写完当前文件就停，目标原有文件不动 ----
        write(new File(root, "q2/keep.txt"), new byte[]{7});
        Fs.CopyResult r3 = fs.copy(p(base, "p"), p(base, "q2"), new Fs.Progress() {
            public void onPlan(long tb, long tf) {}

            public boolean onFile(String path, long size, long db, long df) {
                return false;
            }
        });
        ok(r3.cancelled, "copy+progress: onFile 返回 false → cancelled");
        ok(r3.files == 1L, "copy+progress: 取消前只完成 1 个文件");
        ok(r3.bytes > 0, "copy+progress: 取消时已完成字节数带出来");
        ok(new File(root, "q2/keep.txt").exists(),
                "copy+progress: 取消不回滚，目标原有文件保留");

        // ---- isCancelled 立刻为 true：量体积阶段就停 ----
        final int[] plans2 = new int[1];
        Fs.CopyResult r4 = fs.copy(p(base, "p"), p(base, "q3"), new Fs.Progress() {
            public void onPlan(long tb, long tf) { plans2[0]++; }

            public boolean onFile(String path, long size, long db, long df) { return true; }

            public boolean isCancelled() { return true; }
        });
        ok(r4.cancelled, "copy+progress: isCancelled 立刻生效");
        ok(r4.files == 0L, "copy+progress: 未开始复制时文件数为 0");
        eq(0, plans2[0], "copy+progress: 量体积被打断时不报总量（没有分母就别报）");
        ok(!new File(root, "q3").exists(), "copy+progress: 一开始就取消不建目标目录");
    }

    static String p(String base, String rel) {
        return PathCore.join(base, rel);
    }

    // ---------------------------------------------------------------- 工具

    interface Body { void run() throws Exception; }

    static void expect(FsError.Code code, Body body, String msg) {
        try {
            body.run();
            ok(false, msg + "  (未抛异常)");
        } catch (FsError e) {
            ok(e.code == code, msg + (e.code == code ? "" : "  (got=" + e.code + ")"));
        } catch (Exception e) {
            ok(false, msg + "  (got=" + e.getClass().getSimpleName() + ":" + e.getMessage() + ")");
        }
    }

    static File mkTempDir() throws Exception {
        File f = File.createTempFile("hf_fs_local", "");
        if (!f.delete() || !f.mkdirs()) throw new IllegalStateException("无法建临时目录");
        return f;
    }

    static void mkdirs(File f) {
        if (!f.isDirectory() && !f.mkdirs()) throw new IllegalStateException("mkdirs: " + f);
    }

    static File write(File f, byte[] data) throws Exception {
        File p = f.getParentFile();
        if (p != null) mkdirs(p);
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(data); } finally { out.close(); }
        return f;
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (int i = 0; i < cs.length; i++) deleteTree(cs[i]);
        f.delete();
    }
}
