package com.hex91.file.net;

import com.hex91.file.core.PathCore;
import com.hex91.file.fs.Fs;
import com.hex91.file.fs.FsError;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * v2.114.0（O-27）：把 {@link NetFs} 适配成统一的 {@link Fs} 操作面。
 *
 * <p>放在 {@code net} 而不是 {@code fs}：依赖方向是 net → fs（net 可以用 fs 的类型），
 * 反过来会让 fs 层牵着协议实现走，本地实现的单测也得把七个协议一起编译。</p>
 *
 * <p>三处协议能力差异必须显式表达，不允许「看起来成功」：</p>
 * <ul>
 *   <li>{@link #rename} 只能同目录改名（{@code NetFs.rename(path, newName)} 的签名如此），
 *       跨目录直接抛 {@code UNSUPPORTED}，由调用方改用「下载 + 上传 + 删除」。</li>
 *   <li>{@link #openWrite} 的 append 需要协议支持
 *       {@code supportsAppend()}；FTP/SFTP 只能追加到远端末尾，所以偏移取
 *       {@link #stat} 的当前长度，而不是调用方传进来的值。</li>
 *   <li>{@link #stat} 没有对应协议方法：退化为列父目录找同名项。目录层级很深时
 *       这是一次额外请求，但比「每个协议各写一个 stat」可靠。</li>
 * </ul>
 */
public final class NetFsAdapter implements Fs {

    private final NetFs net;

    public NetFsAdapter(NetFs net) {
        if (net == null) throw new IllegalArgumentException("net == null");
        this.net = net;
    }

    @Override
    public List<Entry> list(String path) throws IOException {
        String p = path == null ? "" : PathCore.normalize(path);
        List<NetFs.NetEntry> src = net.list(p);
        List<Entry> out = new ArrayList<Entry>(src == null ? 0 : src.size());
        if (src == null) return out;
        for (NetFs.NetEntry e : src) {
            out.add(new Entry(e.name, PathCore.join(p, e.name), e.dir, e.size, e.mtime, false));
        }
        return out;
    }

    @Override
    public Entry stat(String path) throws IOException {
        String p = path == null ? "" : PathCore.normalize(path);
        if (p.isEmpty()) return new Entry("", "", true, 0L, 0L, false);
        String parent = PathCore.parentOf(p);
        String name = PathCore.baseName(p);
        List<NetFs.NetEntry> src = net.list(parent);
        if (src == null) return null;
        for (NetFs.NetEntry e : src) {
            if (name.equals(e.name)) {
                return new Entry(e.name, p, e.dir, e.size, e.mtime, false);
            }
        }
        return null;
    }

    @Override
    public InputStream openRead(String path) throws IOException {
        return net.openRead(req(path));
    }

    @Override
    public OutputStream openWrite(String path, boolean append) throws IOException {
        String p = req(path);
        if (!append) return net.openWrite(p);
        if (!net.supportsAppend()) {
            throw FsError.of(FsError.Code.UNSUPPORTED, "append");
        }
        Entry cur = stat(p);
        long offset = cur == null || cur.dir ? 0L : cur.size;
        return net.openWriteFrom(p, offset);
    }

    @Override
    public void mkdir(String path) throws IOException {
        net.mkdir(req(path));
    }

    @Override
    public void delete(String path, boolean recursive) throws IOException {
        String p = req(path);
        Entry cur = stat(p);
        if (cur == null) throw FsError.of(FsError.Code.SRC_MISSING, p);
        if (cur.dir && !recursive) {
            // NetFs.delete 的目录删除就是递归；不支持「只删空目录」的语义，
            // 宁可失败也不要把非空目录整棵删掉
            List<Entry> kids = list(p);
            if (kids != null && !kids.isEmpty()) {
                throw FsError.of(FsError.Code.UNSUPPORTED, "delete non-empty dir without recursive");
            }
        }
        net.delete(p, cur.dir);
    }

    @Override
    public void rename(String from, String to) throws IOException {
        String a = req(from);
        String b = req(to);
        if (a.equals(b)) return;
        if (!PathCore.parentOf(a).equals(PathCore.parentOf(b))) {
            throw FsError.of(FsError.Code.UNSUPPORTED, "move across directories");
        }
        if (stat(b) != null) throw FsError.of(FsError.Code.TARGET_EXISTS, b);
        net.rename(a, PathCore.baseName(b));
    }

    /**
     * v2.117.0（O-27 续）：远程复制只做单文件；v2.118.0 增加进度与取消。
     *
     * <p>目录复制要逐层建目录、逐文件搬运，中途断线会留下半棵树 —— 而远程没有
     * 「回滚」手段，用户看到的是一个「好像复制了」但缺文件的目录。在协议层给出
     * 事务语义之前，明确报 {@code UNSUPPORTED}，让调用方走「下载到本地再上传」。</p>
     *
     * <p>不做「量体积」这趟：远程 {@code stat} 拿到的 size 是协议报告的期望值，
     * 与实际流长度不一定相等（转码、压缩传输），拿它当分母会让进度条停在 97%。
     * 所以直接 {@code onPlan(size, 1)} 后按已写字节报告，界面自己决定怎么显示。</p>
     */
    @Override
    public CopyResult copy(String from, String to, Progress p) throws IOException {
        String a = req(from);
        String b = req(to);
        if (a.equals(b)) return new CopyResult(false, 0L, 0L, 0);
        if (b.startsWith(a + "/")) throw FsError.of(FsError.Code.SELF_NEST, b);
        Entry cur = stat(a);
        if (cur == null) throw FsError.of(FsError.Code.SRC_MISSING, a);
        if (cur.dir) throw FsError.of(FsError.Code.UNSUPPORTED, "copy directory");

        long expect = cur.size;
        if (p != null) p.onPlan(expect, 1L);
        if (p != null && p.isCancelled()) return new CopyResult(true, 0L, 0L, 0);

        long done = 0;
        InputStream in = net.openRead(a);
        try {
            OutputStream out = net.openWrite(b);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    // 远程取消后流已经被截断，继续写只会把半份内容留在远端
                    if (p != null && p.isCancelled()) {
                        return new CopyResult(true, done, 0L, 0);
                    }
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        if (p != null && !p.onFile(b, done, done, 1L)) {
            return new CopyResult(true, done, 1L, 0);
        }
        return new CopyResult(false, done, 1L, 0);
    }

    /** v2.117.0：远程只有「同目录原子改名」，跨目录移动会由 {@link #rename} 报不支持 */
    @Override
    public void move(String from, String to) throws IOException {
        rename(from, to);
    }

    @Override
    public void close() {
        net.close();
    }

    private static String req(String path) throws FsError {
        if (path == null || path.trim().isEmpty()) {
            // 远程根的写/删/改名没有意义：空路径只用于 list 根目录
            throw FsError.of(FsError.Code.BAD_PATH);
        }
        return PathCore.normalize(path);
    }
}
