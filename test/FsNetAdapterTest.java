import com.hex91.file.fs.Fs;
import com.hex91.file.fs.FsError;
import com.hex91.file.net.NetFs;
import com.hex91.file.net.NetFsAdapter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * O-27：{@link NetFsAdapter} 的三个协议差异点。
 *
 * <p>用内存 {@link NetFs} 而不是真连 FTP/SMB：这几条规则与网络无关，
 * 全是「协议能力不足时该失败还是该换算」的判断。真连服务器反而把
 * 网络抖动引进来，失败了也说不清是规则错还是超时。</p>
 *
 * <p>钉住的三件事：</p>
 * <ul>
 *   <li>根路径 {@code ""} 只用于 list；写 / 删 / 改名传空必须报 {@code BAD_PATH}，
 *       否则一个拼错的路径可能把整棵远程根删掉。</li>
 *   <li>append 的偏移取远端当前长度（FTP/SFTP 只能追加到末尾），
 *       协议不支持 append 时报 {@code UNSUPPORTED}，不能静默改成覆盖。</li>
 *   <li>跨目录改名报 {@code UNSUPPORTED}，目标已存在报 {@code TARGET_EXISTS}。</li>
 * </ul>
 */
public class FsNetAdapterTest {

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
        netPaths();
        walkSection();
        copyMoveSection();
        copyFileSection();
        MemFs mem = new MemFs();
        mem.dirs.add("");
        mem.dirs.add("docs");
        mem.dirs.add("docs/sub");
        mem.put("a.txt", new byte[]{1, 2, 3});
        mem.put("docs/b.txt", new byte[]{4, 5});
        NetFsAdapter fs = new NetFsAdapter(mem);

        // ---- list / stat ----
        List<Fs.Entry> root = fs.list("");
        eq(root.size(), 2, "list 根：1 个目录 + 1 个文件");
        eq(root.get(0).name, "docs", "list 根：目录排前");
        ok(root.get(0).dir, "list 根：docs 是目录");
        eq(root.get(1).path, "a.txt", "list 根：path 为协议相对路径");
        eq(fs.stat("a.txt").size, 3L, "stat: 文件大小");
        eq(fs.stat("docs").dir, true, "stat: 目录");
        ok(fs.stat("nope") == null, "stat: 不存在返回 null");
        ok(fs.stat("").dir, "stat: 根是目录");

        // ---- 空路径只能用于 list ----
        expect(FsError.Code.BAD_PATH, new Body() {
            public void run() throws Exception { new NetFsAdapter(new MemFs()).mkdir(""); }
        }, "mkdir(\"\"): BAD_PATH");
        expect(FsError.Code.BAD_PATH, new Body() {
            public void run() throws Exception { new NetFsAdapter(new MemFs()).openWrite("", false); }
        }, "openWrite(\"\"): BAD_PATH");

        // ---- append：偏移取远端长度 ----
        OutputStream out = fs.openWrite("a.txt", true);
        out.write(new byte[]{9});
        out.close();
        eq(mem.len("a.txt"), 4L, "openWrite(append): 追加到已有内容之后");
        eq(mem.byteAt("a.txt", 3), (byte) 9, "openWrite(append): 新字节在末尾");
        eq(mem.byteAt("a.txt", 0), (byte) 1, "openWrite(append): 原内容未被截断");

        out = fs.openWrite("a.txt", false);
        out.write(new byte[]{7});
        out.close();
        eq(mem.len("a.txt"), 1L, "openWrite(false): 截断覆盖");

        // 不支持 append 的协议必须明确失败
        MemFs noAppend = new MemFs();
        noAppend.dirs.add("");
        noAppend.put("x.txt", new byte[]{1});
        noAppend.append = false;
        expect(FsError.Code.UNSUPPORTED, new Body() {
            public void run() throws Exception {
                new NetFsAdapter(noAppend).openWrite("x.txt", true).close();
            }
        }, "openWrite(append): 协议不支持报 UNSUPPORTED（不改写为覆盖）");

        // ---- rename ----
        fs.rename("a.txt", "c.txt");
        ok(!mem.has("a.txt") && mem.has("c.txt"), "rename: 同目录改名");
        expect(FsError.Code.UNSUPPORTED, new Body() {
            public void run() throws Exception {
                new NetFsAdapter(mem).rename("c.txt", "docs/c.txt");
            }
        }, "rename: 跨目录报 UNSUPPORTED");
        mem.put("d.txt", new byte[]{0});
        expect(FsError.Code.TARGET_EXISTS, new Body() {
            public void run() throws Exception {
                new NetFsAdapter(mem).rename("c.txt", "d.txt");
            }
        }, "rename: 目标存在报 TARGET_EXISTS");

        // ---- delete ----
        expect(FsError.Code.UNSUPPORTED, new Body() {
            public void run() throws Exception {
                new NetFsAdapter(mem).delete("docs", false);
            }
        }, "delete: 非空目录非递归报 UNSUPPORTED（不整棵删）");
        fs.delete("docs", true);
        ok(!mem.has("docs/b.txt") && !mem.hasDir("docs"), "delete: 递归删除整棵目录");
        expect(FsError.Code.SRC_MISSING, new Body() {
            public void run() throws Exception { new NetFsAdapter(mem).delete("gone", true); }
        }, "delete: 不存在报 SRC_MISSING");

        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 工具

    /**
     * v2.115.0：{@code NetFs} 的三个静态路径工具已收敛到 {@code PathCore}，
     * 20 处调用点没改。这里钉住「收敛后语义没漂」——尤其是远程的根是空串，
     * 不是文件系统的 {@code "/"}。
     */
    static void netPaths() {
        eq(NetFs.join("a", "b.txt"), "a/b.txt", "NetFs.join: 普通");
        eq(NetFs.join("", "b.txt"), "b.txt", "NetFs.join: 空父");
        eq(NetFs.join("a/", "b.txt"), "a/b.txt", "NetFs.join: 父带尾斜杠不出双斜杠");
        eq(NetFs.baseName("a/b.txt"), "b.txt", "NetFs.baseName: 普通");
        eq(NetFs.baseName("a/"), "a", "NetFs.baseName: 尾斜杠");
        eq(NetFs.baseName("/"), "", "NetFs.baseName: 根");
        eq(NetFs.parentOf("a/b"), "a", "NetFs.parentOf: 两级");
        eq(NetFs.parentOf("a"), "", "NetFs.parentOf: 一级");
        eq(NetFs.parentOf(""), "", "NetFs.parentOf: 空");
        eq(NetFs.parentOf("/a"), "", "NetFs.parentOf: 前导斜杠仍视为远程根");
        eq(NetFs.parentOf("a/"), "", "NetFs.parentOf: 尾斜杠不影响父目录");
        eq(NetFs.parentOf("/a/b"), "a", "NetFs.parentOf: 前导斜杠被吃掉");
    }

    // ---------------------------------------------------------------- walk

    /**
     * v2.116.0（O-27 续）：远程实现走的是 {@code Fs.walk} 的默认版本（基于 {@code list}）。
     *
     * <p>为什么单独测：{@link com.hex91.file.fs.LocalFs} 覆盖成 {@code File} 快路径之后，
     * 默认实现就只剩远程在用 —— 本地用例再多也覆盖不到它。而远程的根是空串，
     * 与文件系统的 {@code "/"} 不是一回事，这条差异只有在这里测得出来。</p>
     */
    static void walkSection() throws Exception {
        MemFs tree = new MemFs();
        tree.dirs.add("");
        tree.dirs.add("d1");
        tree.dirs.add("d1/d2");
        tree.put("r.txt", new byte[]{1});
        tree.put("d1/a.txt", new byte[]{2});
        tree.put("d1/d2/b.txt", new byte[]{3});
        NetFsAdapter fs = new NetFsAdapter(tree);

        Probe all = new Probe();
        eq(fs.walk("", all), true, "walk 远程根：走完返回 true（空串是根，不是错误）");
        eq(all.files.toString(), "[a.txt, b.txt, r.txt]", "walk 远程根：文件集合");
        eq(all.dirs.toString(), "[d1, d2]", "walk 远程根：目录集合");

        Probe pruned = new Probe() {
            @Override
            public boolean onDir(Fs.Entry e, int depth) {
                return !"d1".equals(e.name);
            }
        };
        fs.walk("", pruned);
        eq(pruned.files.toString(), "[r.txt]", "walk 远程：剪枝后不进子树");

        Probe shallow = new Probe();
        fs.walk("", 1, null, shallow);
        eq(shallow.files.toString(), "[r.txt]", "walk 远程：maxDepth=1");
        eq(shallow.dirs.toString(), "[d1]", "walk 远程：maxDepth=1 只有一级目录");

        Probe cancelled = new Probe() {
            @Override
            public void onFile(Fs.Entry e, int depth) {
                super.onFile(e, depth);
                stop = true;
            }
        };
        eq(fs.walk("", 64, cancelled, cancelled), false, "walk 远程：取消后返回 false");
        eq(cancelled.files.size(), 1, "walk 远程：取消后不再回调");

        // 子目录读不出来：回调 onError，其余子树照走
        MemFs flaky = new MemFs() {
            @Override
            public List<NetEntry> list(String path) throws IOException {
                if ("bad".equals(path)) throw new IOException("denied");
                return super.list(path);
            }
        };
        flaky.dirs.add("");
        flaky.dirs.add("bad");
        flaky.put("ok.txt", new byte[]{1});
        Probe skip = new Probe();
        eq(new NetFsAdapter(flaky).walk("", skip), true, "walk 远程：子目录读不出仍继续走完");
        eq(skip.files.toString(), "[ok.txt]", "walk 远程：坏目录不影响兄弟节点");
        eq(skip.errors, 1, "walk 远程：坏目录回调 onError 一次");

        expect(FsError.Code.SRC_MISSING, new Body() {
            public void run() throws Exception {
                new NetFsAdapter(tree).walk("no-such", new Probe());
            }
        }, "walk 远程：根不存在报 SRC_MISSING");
        expect(FsError.Code.NOT_DIR, new Body() {
            public void run() throws Exception {
                new NetFsAdapter(tree).walk("r.txt", new Probe());
            }
        }, "walk 远程：根是文件报 NOT_DIR");
    }

    // ---------------------------------------------------------------- copy / move

    /**
     * v2.117.0（O-27 续）：远程复制只做单文件，目录复制明确报不支持。
     *
     * <p>为什么这条值得单测：远程复制如果「尽力而为」，断线后留下的半棵树比直接失败
     * 更难排查 —— 用户看到目录在、点进去少一半文件，会以为是同步问题而不是复制失败。
     * 这里钉住「宁可失败也要说清楚」。</p>
     */
    static void copyMoveSection() throws Exception {
        MemFs mem = new MemFs();
        mem.dirs.add("");
        mem.dirs.add("d1");
        mem.put("a.txt", new byte[]{1, 2, 3, 4});
        mem.put("d1/b.txt", new byte[]{5});
        NetFsAdapter fs = new NetFsAdapter(mem);

        fs.copy("a.txt", "a2.txt");
        ok(mem.has("a2.txt"), "copy 远程：单文件复制产生新路径");
        eq(mem.len("a2.txt"), 4L, "copy 远程：字节数一致");
        eq(mem.byteAt("a2.txt", 3), (byte) 4, "copy 远程：末尾字节正确");
        eq(mem.len("a.txt"), 4L, "copy 远程：源未被截断");

        expect(FsError.Code.UNSUPPORTED, new Body() {
            public void run() throws Exception {
                new NetFsAdapter(mem).copy("d1", "d1copy");
            }
        }, "copy 远程：目录复制报 UNSUPPORTED（不留半棵树）");
        expect(FsError.Code.SRC_MISSING, new Body() {
            public void run() throws Exception {
                new NetFsAdapter(mem).copy("nope", "x");
            }
        }, "copy 远程：源不存在报 SRC_MISSING");

        fs.move("a2.txt", "a3.txt");
        ok(mem.has("a3.txt") && !mem.has("a2.txt"), "move 远程：同目录改名");
        expect(FsError.Code.UNSUPPORTED, new Body() {
            public void run() throws Exception {
                new NetFsAdapter(mem).move("a3.txt", "d1/a3.txt");
            }
        }, "move 远程：跨目录报 UNSUPPORTED");

        // ---- v2.118.0：远程复制的进度口径 ----
        final java.util.List<Object> plan = new java.util.ArrayList<Object>();
        final int[] calls = new int[1];
        Fs.CopyResult r = fs.copy("a3.txt", "a4.txt", new Fs.Progress() {
            public void onPlan(long tb, long tf) {
                plan.add(Long.valueOf(tb));
                plan.add(Long.valueOf(tf));
            }

            public boolean onFile(String path, long size, long db, long df) {
                calls[0]++;
                return true;
            }
        });
        eq(Long.valueOf(4L), plan.get(0), "copy 远程进度：onPlan 用 stat 的期望大小（不另跑一趟量体积）");
        eq(Long.valueOf(1L), plan.get(1), "copy 远程进度：文件数 1");
        eq(1, calls[0], "copy 远程进度：onFile 回调一次");
        ok(!r.cancelled && r.files == 1L, "copy 远程进度：完成计数为 1");

        // --- 取消：远程没有回滚，已写入的部分必须留在远端并如实报取消 ---
        Fs.CopyResult r2 = fs.copy("a3.txt", "a5.txt", new Fs.Progress() {
            public void onPlan(long tb, long tf) {}

            public boolean onFile(String path, long size, long db, long df) {
                return false;
            }
        });
        ok(r2.cancelled, "copy 远程进度：onFile 返回 false → cancelled");
        ok(mem.has("a5.txt"), "copy 远程进度：取消时已写入内容保留（远程无回滚，不能假装没发生）");
    }

    // ------------------------------------------------------------ copyFile（远程只支持从头写）

    static void copyFileSection() throws Exception {
        MemFs mem = new MemFs();
        mem.dirs.add("");
        mem.put("src.bin", new byte[]{1, 2, 3, 4, 5});

        Fs.FileCopy r = new NetFsAdapter(mem).copyFile("src.bin", "dst.bin", 0L, null);
        ok(!r.paused && !r.stopped, "copyFile 远程：正常完成");
        eq(5L, r.bytes, "copyFile 远程：bytes 等于源长度");
        eq(5L, mem.len("dst.bin"), "copyFile 远程：远端出现完整内容");

        Fs.FileCopy r2 = new NetFsAdapter(mem).copyFile("src.bin", "stop.bin", 0L, new Fs.Step() {
            public int at(long written) {
                return STOP;
            }
        });
        ok(r2.stopped, "copyFile 远程：STOP → stopped");
        eq(0L, r2.bytes, "copyFile 远程：开局就停 → 0 字节");

        expect(FsError.Code.UNSUPPORTED, new Body() {
            public void run() throws Exception {
                new NetFsAdapter(mem).copyFile("src.bin", "resume.bin", 1024L, null);
            }
        }, "copyFile 远程：远端没有「本地半截文件」，续写点无从校验 → UNSUPPORTED");

        expect(FsError.Code.UNSUPPORTED, new Body() {
            public void run() throws Exception {
                new NetFsAdapter(mem).copyFile("src.bin", "pause.bin", 0L, new Fs.Step() {
                    public int at(long written) {
                        return PAUSE;
                    }
                });
            }
        }, "copyFile 远程：保不住半截文件 → 暂停报 UNSUPPORTED，而不是静默继续跑");
    }

    /** 收集式访问者；同时当取消信号用，省掉两个匿名类 */
    static class Probe implements Fs.Visitor, Fs.Cancel {
        final java.util.Set<String> files = new java.util.TreeSet<String>();
        final java.util.Set<String> dirs = new java.util.TreeSet<String>();
        int errors;
        boolean stop;

        public boolean onDir(Fs.Entry e, int depth) {
            dirs.add(e.name);
            return true;
        }

        public void onFile(Fs.Entry e, int depth) {
            files.add(e.name);
        }

        public void onError(Fs.Entry dir, IOException e) {
            errors++;
        }

        public boolean isCancelled() { return stop; }
    }

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

    /** 内存版 NetFs：只实现适配器用得到的语义，行为按「协议能力」可配置 */
    static class MemFs implements NetFs {
        final Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        final Set<String> dirs = new LinkedHashSet<String>();
        boolean append = true;

        void put(String path, byte[] data) { files.put(path, data); }
        boolean has(String path) { return files.containsKey(path); }
        boolean hasDir(String path) { return dirs.contains(path); }
        long len(String path) { byte[] b = files.get(path); return b == null ? -1 : b.length; }
        byte byteAt(String path, int i) { return files.get(path)[i]; }

        @Override
        public List<NetEntry> list(String path) throws IOException {
            if (!dirs.contains(path)) throw new IOException("no such dir: " + path);
            String prefix = path.isEmpty() ? "" : path + "/";
            List<NetEntry> out = new ArrayList<NetEntry>();
            for (String d : dirs) {
                if (d.equals(path) || !d.startsWith(prefix)) continue;
                String rest = d.substring(prefix.length());
                if (rest.isEmpty() || rest.indexOf('/') >= 0) continue;
                out.add(new NetEntry(rest, true, 0, 0));
            }
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                String k = e.getKey();
                if (!k.startsWith(prefix)) continue;
                String rest = k.substring(prefix.length());
                if (rest.isEmpty() || rest.indexOf('/') >= 0) continue;
                out.add(new NetEntry(rest, false, e.getValue().length, 0));
            }
            return out;
        }

        @Override
        public InputStream openRead(String path) throws IOException {
            byte[] b = files.get(path);
            if (b == null) throw new IOException("no such file: " + path);
            return new ByteArrayInputStream(b);
        }

        @Override
        public OutputStream openWrite(final String path) {
            return new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    super.close();
                    files.put(path, toByteArray());
                }
            };
        }

        @Override
        public void mkdir(String path) { dirs.add(path); }

        @Override
        public void delete(String path, boolean dir) {
            if (!dir) { files.remove(path); return; }
            String prefix = path + "/";
            List<String> fk = new ArrayList<String>(files.keySet());
            for (String k : fk) if (k.startsWith(prefix)) files.remove(k);
            List<String> dk = new ArrayList<String>(dirs);
            for (String k : dk) if (k.equals(path) || k.startsWith(prefix)) dirs.remove(k);
        }

        @Override
        public void rename(String path, String newName) {
            int i = path.lastIndexOf('/');
            String to = i < 0 ? newName : path.substring(0, i + 1) + newName;
            byte[] b = files.remove(path);
            if (b != null) files.put(to, b);
        }

        @Override
        public void close() {}

        @Override
        public boolean supportsAppend() { return append; }

        @Override
        public OutputStream openWriteFrom(final String path, long offset) {
            final byte[] old = files.get(path);
            final int keep = old == null ? 0 : (int) Math.min(offset, old.length);
            return new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    super.close();
                    byte[] add = toByteArray();
                    byte[] merged = new byte[keep + add.length];
                    if (keep > 0) System.arraycopy(old, 0, merged, 0, keep);
                    System.arraycopy(add, 0, merged, keep, add.length);
                    files.put(path, merged);
                }
            };
        }
    }
}
