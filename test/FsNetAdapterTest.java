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
    static final class MemFs implements NetFs {
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
